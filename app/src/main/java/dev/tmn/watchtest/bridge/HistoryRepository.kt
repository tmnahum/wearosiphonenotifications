package dev.tmn.watchtest.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/** One main-thread model shared by service and UI; all file I/O runs on a serial worker. */
@android.annotation.SuppressLint("StaticFieldLeak") // Application context only; process-scoped repository.
class HistoryRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "notification-history-v1.bin"))
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var history = NotificationHistory()
        private set
    private val log by lazy { NotificationLog(File(app.noBackupFilesDir, "notification-log-v1.db")) }
    var logError: String? = null
        private set
    var loaded = false
        private set
    var error: String? = null
        private set
    private val waiting = mutableListOf<() -> Unit>()
    private val saveTask = Runnable { saveNow() }
    private val maintenance = object : Runnable {
        override fun run() {
            pruneLog()
            main.postDelayed(this, 3_600_000)
        }
    }

    init {
        main.postDelayed(maintenance, 3_600_000)
        worker.execute {
            val result = runCatching {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                    file.openRead().use(HistoryCodec::read) else SavedHistory(emptyList(), emptySet())
            }.mapCatching { saved ->
                NotificationHistory().apply { restore(saved.records, saved.dismissed) }
            }
            main.post {
                result.fold(onSuccess = { restored -> history = restored }, onFailure = {
                    error = "Saved history could not be read. New messages will start a fresh history."
                })
                loaded = true
                val callbacks = waiting.toList()
                waiting.clear()
                callbacks.forEach { it() }
                broadcast()
            }
            accessLog { prune(System.currentTimeMillis(), force = true) }
        }
    }
    fun whenReady(action: () -> Unit) { if (loaded) action() else waiting.add(action) }
    fun recordNotification(event: AncsProtocol.Event, fields: Map<Int, String>) {
        val copy = fields.toMap()
        val now = System.currentTimeMillis()
        worker.execute { accessLog { append(event, copy, now) } }
    }
    fun clearLog() { worker.execute { accessLog { clear() } } }
    fun pruneLog() { worker.execute { accessLog { prune(System.currentTimeMillis()) } } }
    private fun accessLog(action: NotificationLog.() -> Unit) {
        val failed = runCatching { log.action() }.isFailure
        main.post {
            val message = if (failed) "Notification log could not be updated." else null
            if (message != logError) { logError = message; broadcast() }
        }
    }
    fun changed() {
        if (!main.hasCallbacks(saveTask)) main.postDelayed(saveTask, 750)
        broadcast()
    }
    fun flush(onComplete: (() -> Unit)? = null) {
        main.removeCallbacks(saveTask)
        if (loaded) saveNow(onComplete) else onComplete?.invoke()
    }
    private fun saveNow(onComplete: (() -> Unit)? = null) {
        val snapshot = history.saved()
        val dismissed = history.dismissed()
        worker.execute {
            val result = runCatching {
                val stream = file.startWrite()
                try {
                    HistoryCodec.write(stream, snapshot, dismissed)
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
            }
            main.post {
                val newError = if (result.isFailure) "Could not save history on this watch. Recent messages may be lost on restart." else null
                if (error != newError) { error = newError; broadcast() }
                onComplete?.invoke()
            }
        }
    }
    private fun broadcast() { app.sendBroadcast(Intent(EVENT_HISTORY).setPackage(app.packageName)) }
    companion object {
        const val EVENT_HISTORY = "dev.tmn.watchtest.HISTORY"
        private var instance: HistoryRepository? = null
        fun get(context: Context): HistoryRepository = instance ?: HistoryRepository(context).also { instance = it }
    }
}
