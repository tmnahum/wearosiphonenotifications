package dev.tmn.watchtest.bridge

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Private future-history archive. Access only on the repository's serial I/O worker. */
class NotificationLog(file: File) {
    private val db = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
        execSQL("""CREATE TABLE IF NOT EXISTS notification_log (
            id INTEGER PRIMARY KEY, observed_at INTEGER NOT NULL, event_kind INTEGER NOT NULL,
            app TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL,
            body TEXT NOT NULL, posted_at TEXT NOT NULL, pre_existing INTEGER NOT NULL)""")
        execSQL("CREATE INDEX IF NOT EXISTS notification_log_time ON notification_log(observed_at)")
        version = 1
    }
    private var lastPruned = Long.MIN_VALUE
    fun append(event: AncsProtocol.Event, fields: Map<Int, String>, now: Long) {
        prune(now)
        db.insertOrThrow("notification_log", null, ContentValues().apply {
            put("observed_at", now)
            put("event_kind", event.eventId)
            put("app", fields[0].orEmpty())
            put("title", fields[1].orEmpty())
            put("subtitle", fields[2].orEmpty())
            put("body", fields[3].orEmpty())
            put("posted_at", fields[5].orEmpty())
            put("pre_existing", if (event.flags and 4 != 0) 1 else 0)
        })
    }
    fun prune(now: Long, force: Boolean = false) {
        if (force || lastPruned == Long.MIN_VALUE || now < lastPruned || now - lastPruned >= 3_600_000) {
            db.delete("notification_log", "observed_at < ?", arrayOf(NotificationLogRetention.cutoff(now).toString()))
            lastPruned = now
        }
    }
    fun clear() { db.delete("notification_log", null, null) }
}
