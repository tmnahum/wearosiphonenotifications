package dev.tmn.watchtest.bridge

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/** Shared main-thread state; file I/O and restoration run on one serial worker. */
@android.annotation.SuppressLint("StaticFieldLeak") // Application context, never an Activity.
class HistoryRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "notification-history-v1.bin"))
    private val appFile = AtomicFile(File(app.noBackupFilesDir, "source-apps-v1.bin"))
    private val postedCards = NativeTrayTracker()
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    // Catch a tray clear even if Wear delays the notification's delete-intent callback.
    fun reconcileDismissals() {
        if (!loaded || !notifications.areNotificationsEnabled() || postedCards.isEmpty()) return
        val active = notifications.activeNotifications.mapNotNull { it.tag }.toSet()
        val missing = postedCards.reconcile(active, android.os.SystemClock.elapsedRealtime())
        if (missing.records.isEmpty()) return
        if (missing.clearedTogether) history.dismissAllNotifications()
        history.dismissRecords(missing.records)
        changed(); flush()
    }
    fun nativePosted(card: AncsCard) { postedCards.posted(card.tag, card.messages.map { it.uid }.toSet(), android.os.SystemClock.elapsedRealtime()) }
    fun cancelNative(tag: String, id: Int = 0) {
        postedCards.forget(tag) // Programmatic removal must never masquerade as watch dismissal.
        notifications.cancel(tag, id)
    }
    fun dismissNative(tag: String, throughId: Long) {
        reconcileDismissals()
        history.dismissConversation(tag, throughId)
        postedCards.forgetThrough(tag, throughId)
        changed()
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var catalog = AppCatalog()
        private set
    var history = NotificationHistory(catalog)
        private set
    private val log by lazy { NotificationLog(File(app.noBackupFilesDir, "notification-log-v1.db")) }
    var logError: String? = null
        private set
    var loaded = false
        private set
    var error: String? = null
        private set
    private var historyReadFailed = false
    private var catalogReadFailed = false
    private val waiting = mutableListOf<() -> Unit>()
    private val saveTask = Runnable { saveNow() }
    private val maintenance = object : Runnable {
        override fun run() { pruneLog(); main.postDelayed(this, 3_600_000) }
    }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    init {
        main.postDelayed(maintenance, 3_600_000)
        worker.execute {
            val appsResult = runCatching { if (exists(appFile)) appFile.openRead().use(AppCatalogCodec::read) else emptyList() }
            val restoredCatalog = AppCatalog().apply { restore(appsResult.getOrDefault(emptyList())) }
            val result = runCatching {
                if (exists(file)) file.openRead().use(HistoryCodec::read) else SavedHistory(emptyList(), emptySet())
            }.mapCatching { saved ->
                // Migrate existing installations: every previously observed app appears in Settings.
                saved.records.forEach { restoredCatalog.observe(it.fields[0].orEmpty(), it.receivedAt.toEpochMilli()) }
                NotificationHistory(restoredCatalog).apply { restore(saved.records, saved.dismissed, saved.trayClearedThrough) }
            }
            main.post {
                catalog = restoredCatalog
                history = result.getOrElse { NotificationHistory(catalog) }
                historyReadFailed = result.isFailure
                catalogReadFailed = appsResult.isFailure
                loaded = true
                updateReadError()
                val callbacks = waiting.toList(); waiting.clear(); callbacks.forEach { it() }
                broadcast()
                changed() // Persist app discovery/migration, preserving any unreadable original file.
            }
            accessLog { prune(System.currentTimeMillis(), force = true) }
        }
    }
    private fun updateReadError() {
        error = when {
            historyReadFailed -> "Saved history couldn't be read. It has been preserved; new previews are temporary until local data is cleared."
            catalogReadFailed -> "App settings couldn't be read. The original settings file has been preserved."
            else -> null
        }
    }
    fun whenReady(action: () -> Unit) { if (loaded) action() else waiting.add(action) }
    fun recordNotification(event: AncsProtocol.Event, fields: Map<Int, String>) {
        reconcileDismissals()
        val copy = fields.toMap(); val now = System.currentTimeMillis()
        catalog.observe(fields[0].orEmpty(), now)
        worker.execute { accessLog { append(event, copy, now) } }
    }
    fun appNameReceived(id: String, name: String) {
        if (name.isBlank()) return
        reconcileDismissals()
        catalog.name(id, name)
        val old = history.snapshot().filter { it.app == id }.map { it.tag }.toSet()
        val changes = history.regroup()
        changes.removed.filter { it in old }.forEach { cancelNative(it) }
        changed(); appsChanged(id)
    }
    fun updateApp(id: String, settings: AppSettings) {
        check(loaded)
        reconcileDismissals()
        // Cancel old grouping/muted cards immediately; receiver redraws current live cards silently.
        history.snapshot().filter { it.app == id }.forEach { cancelNative(it.tag) }
        catalog.settings(id, settings)
        history.regroup()
        changed(); flush(); appsChanged(id)
    }
    private fun appsChanged(id: String) {
        app.sendBroadcast(Intent(EVENT_APPS).setPackage(app.packageName).putExtra("app", id))
    }
    fun clearLocalData() {
        history.snapshot().forEach { cancelNative(it.tag) }
        history.clear()
        historyReadFailed = false // Explicit user clearing authorizes replacing an unreadable history.
        updateReadError()
        clearLog(); changed(); flush()
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
        if (loaded && !main.hasCallbacks(saveTask)) main.postDelayed(saveTask, 750)
        broadcast()
    }
    fun flush(onComplete: (() -> Unit)? = null) {
        main.removeCallbacks(saveTask)
        if (loaded) saveNow(onComplete) else whenReady { saveNow(onComplete) }
    }
    private fun atomicWrite(file: AtomicFile, write: (java.io.OutputStream) -> Unit) {
        val stream = file.startWrite()
        try { write(stream); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun saveNow(onComplete: (() -> Unit)? = null) {
        val snapshot = history.saved(); val dismissed = history.dismissed(); val cutoffs = history.trayClearCutoffs(); val apps = catalog.all()
        val writeHistory = !historyReadFailed; val writeApps = !catalogReadFailed
        worker.execute {
            val result = runCatching {
                if (writeHistory) atomicWrite(file) { HistoryCodec.write(it, snapshot, dismissed, cutoffs) }
                if (writeApps) atomicWrite(appFile) { AppCatalogCodec.write(it, apps) }
            }
            main.post {
                val previous = error
                updateReadError()
                if (result.isFailure) error = "Local changes could not be saved. Check free storage on the watch."
                if (error != previous) broadcast()
                onComplete?.invoke()
            }
        }
    }
    private fun broadcast() { app.sendBroadcast(Intent(EVENT_HISTORY).setPackage(app.packageName)) }
    companion object {
        const val EVENT_HISTORY = "dev.tmn.watchtest.HISTORY"
        const val EVENT_APPS = "dev.tmn.watchtest.APPS"
        private var instance: HistoryRepository? = null
        fun get(context: Context): HistoryRepository = instance ?: HistoryRepository(context).also { instance = it }
    }
}
