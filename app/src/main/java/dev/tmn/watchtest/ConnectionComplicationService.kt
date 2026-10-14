package dev.tmn.watchtest

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.tmn.watchtest.bridge.AncsDiagnostics
import dev.tmn.watchtest.bridge.ConnectionPhase
import java.time.Instant

/** Reads live process state: a stored Bluetooth bond is not an active ANCS connection. */
class ConnectionComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(data(request.complicationType, AncsDiagnostics.phase))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, ConnectionPhase.CONNECTED)

    private fun data(type: ComplicationType, phase: ConnectionPhase): ComplicationData? {
        val label = when (phase) {
            ConnectionPhase.CONNECTED -> "Linked"
            ConnectionPhase.DISCONNECTED -> "Offline"
            ConnectionPhase.ERROR -> "Check"
            else -> "Linking"
        }
        val description = text("iPhone: ${phase.title}. Tap to open connection settings.")
        val tap = PendingIntent.getActivity(this, 100,
            Intent(this, MainActivity::class.java).setAction("dev.tmn.watchtest.CONNECTION")
                .putExtra(MainActivity.EXTRA_CONNECTION_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Do not leave a cached 'Linked' visible indefinitely after an abrupt process death.
        // Periodic refresh is a fallback; ordinary state transitions request push updates.
        val validity = TimeRange.between(Instant.now(), Instant.now().plusSeconds(600))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(label), description)
                .setTitle(text("iPhone")).setTapAction(tap).setValidTimeRange(validity).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(phase.title), description)
                .setTitle(text("iPhone connection")).setTapAction(tap).setValidTimeRange(validity).build()
            else -> null
        }
    }
    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
