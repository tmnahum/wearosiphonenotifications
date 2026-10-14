package dev.tmn.watchtest.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Explicit, private delete intent: clearing a watch card also resets its local conversation. */
class HistoryDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra("tag") ?: return
        val pending = goAsync()
        val repository = HistoryRepository.get(context)
        repository.whenReady {
            try {
                repository.history.clearConversation(tag)
                repository.changed()
                repository.flush { pending.finish() }
            } catch (_: Exception) { pending.finish() }
        }
    }
    companion object {
        fun pendingIntent(context: Context, tag: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, HistoryDismissReceiver::class.java)
                .setData(Uri.fromParts("relay-history", tag, null)).putExtra("tag", tag),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
