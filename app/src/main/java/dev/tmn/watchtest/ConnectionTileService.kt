package dev.tmn.watchtest

import android.graphics.Color
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.concurrent.futures.CallbackToFutureAdapter
import dev.tmn.watchtest.bridge.AncsDiagnostics
import dev.tmn.watchtest.bridge.ConnectionPhase

/** A status-only Tile: no saved messages or connection side effects when swiping to it. */
class ConnectionTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) = completed(
        TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(300_000)
            .setTileTimeline(TimelineBuilders.Timeline.Builder()
                .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(Layout.Builder().setRoot(layout(requestParams)).build()).build())
                .build())
            .build()
    )

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        completed(ResourceBuilders.Resources.Builder().setVersion("1").build())

    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        // Refresh a cached card when it becomes visible, including after process recreation.
        getUpdater(this).requestUpdate(ConnectionTileService::class.java)
    }

    private fun layout(request: RequestBuilders.TileRequest): LayoutElement {
        val phase = AncsDiagnostics.phase
        val status = when (phase) {
            ConnectionPhase.CONNECTED -> "Connected"
            ConnectionPhase.DISCONNECTED -> "Disconnected"
            ConnectionPhase.RETRYING -> "Reconnecting"
            ConnectionPhase.ERROR -> "Needs attention"
            else -> "Connecting"
        }
        val detail = when (phase) {
            ConnectionPhase.CONNECTED -> "Receiving notifications"
            ConnectionPhase.DISCONNECTED -> "Open Connection to connect"
            ConnectionPhase.ERROR -> "Open Connection for details"
            else -> "Preparing your notifications"
        }
        val color = when (phase) {
            ConnectionPhase.CONNECTED -> Color.rgb(130, 230, 205)
            ConnectionPhase.ERROR -> Color.rgb(255, 173, 153)
            else -> Color.rgb(177, 186, 194)
        }
        val openConnection = ModifiersBuilders.Clickable.Builder().setId("connection")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName).setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_CONNECTION_SETTINGS,
                        ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
                    .build()).build()).build()
        val content = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(text(status, 22f, color))
            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(text(detail, 12f, Color.WHITE))
            .build()
        return PrimaryLayout.Builder(request.deviceConfiguration)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(text("iPhone Connection", 12f, Color.WHITE))
            .setContent(content)
            .setPrimaryChipContent(CompactChip.Builder(this, "Connection", openConnection,
                request.deviceConfiguration).build())
            .build()
    }

    private fun <T> completed(value: T) = CallbackToFutureAdapter.getFuture<T> { completer ->
        completer.set(value)
        "connection-tile"
    }

    private fun text(value: String, size: Float, color: Int) = Text.Builder().setText(value)
        .setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
        .setMaxLines(2).setMultilineAlignment(TEXT_ALIGN_CENTER).build()
}
