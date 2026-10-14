package dev.tmn.watchtest

import android.app.Application
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.tmn.watchtest.bridge.AncsDiagnostics
import androidx.wear.tiles.TileService

class WatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val handler = Handler(Looper.getMainLooper())
        val update = Runnable {
            ComplicationDataSourceUpdateRequester.create(this,
                ComponentName(this, ConnectionComplicationService::class.java)).requestUpdateAll()
            TileService.getUpdater(this).requestUpdate(ConnectionTileService::class.java)
        }
        // Coalesce setup transitions; notification arrivals never trigger watch-face updates.
        AncsDiagnostics.onPhaseChanged = {
            handler.removeCallbacks(update)
            handler.postDelayed(update, 1_000)
        }
    }
}
