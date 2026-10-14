package dev.tmn.watchtest.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Explicit, private delete intent: watch dismissal hides the posted items while keeping their history. */
class HistoryDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra("tag") ?: return
        val pending = goAsync()
        val repository = HistoryRepository.get(context)
        repository.whenReady {
            try {
                repository.dismissNative(tag, intent.getLongExtra("throughId", Long.MAX_VALUE))
                repository.flush { pending.finish() }
            } catch (_: Exception) { pending.finish() }
        }
    }
    companion object {
        fun pendingIntent(context: Context, tag: String, throughId: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, HistoryDismissReceiver::class.java)
                .setData(Uri.fromParts("relay-history", "$tag:$throughId", null)).putExtra("tag", tag).putExtra("throughId", throughId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
