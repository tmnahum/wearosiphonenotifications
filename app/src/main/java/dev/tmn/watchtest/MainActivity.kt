package dev.tmn.watchtest

import android.Manifest
import android.app.Activity
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import dev.tmn.watchtest.bridge.BridgeMessage
import dev.tmn.watchtest.bridge.ConversationPreviews
import dev.tmn.watchtest.bridge.AncsBridgeService
import dev.tmn.watchtest.bridge.AncsDiagnostics

class MainActivity : Activity() {
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private lateinit var status: TextView
    private lateinit var bridgeStatus: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var fieldsView: TextView
    private var renderedDevices: Map<String, String>? = null
    private var receiverRegistered = false
    private var lastStatus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.channel_description)
                enableVibration(true)
            }
        )
        lastStatus = savedInstanceState?.getString("status")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(30), dp(26), dp(34))
        }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(8))
            content.addView(this)
        }
        fun button(resource: Int, action: () -> Unit) {
            content.addView(Button(this).apply {
                setText(resource)
                isAllCaps = false
                textSize = 14f
                minHeight = dp(48)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        label(getString(R.string.iphone_bridge_heading), 18f)
        bridgeStatus = label(getString(R.string.iphone_bridge_ready), 12f).apply { minLines = 3 }
        button(R.string.scan_iphone) { requestAndScan() }
        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        button(R.string.disconnect_iphone) { startBridgeService(AncsBridgeService.ACTION_DISCONNECT) }
        button(R.string.toggle_details) {
            fieldsView.visibility = if (fieldsView.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        fieldsView = TextView(this).apply {
            visibility = android.view.View.GONE
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(12))
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label(getString(R.string.heading), 16f)
        button(R.string.send) { requestOrSend() }
        button(R.string.group_preview) { sendGroupPreviewSample() }
        status = label("", 12f)
        button(R.string.clear) {
            notifications.cancel(TEST_ID)
            notifications.cancel(GROUP_PREVIEW_ID)
            updateStatus(getString(R.string.cleared))
        }
        button(R.string.settings) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AncsBridgeService.EVENT_DEVICE)
                addAction(AncsBridgeService.EVENT_STATE)
                addAction(AncsBridgeService.EVENT_FIELDS)
            }
            registerReceiver(bridgeReceiver, filter, RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        renderBridge()
        status.text = when {
            !notifications.areNotificationsEnabled() -> getString(R.string.permission_needed)
            notifications.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE -> getString(R.string.channel_blocked)
            else -> lastStatus ?: getString(R.string.ready)
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(bridgeReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private val bridgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { renderBridge() }
    }

    private fun renderBridge() {
        if (bridgeStatus.text.toString() != AncsDiagnostics.status) bridgeStatus.text = AncsDiagnostics.status
        val records = AncsDiagnostics.records.toList().asReversed().joinToString("\n\n────────\n\n") { it.second }
        if (fieldsView.text.toString() != records) fieldsView.text = records
        // Status/field updates must not replace focused buttons or collapse the scroll content.
        if (renderedDevices == AncsDiagnostics.devices) return
        renderedDevices = AncsDiagnostics.devices.toMap()
        deviceList.removeAllViews()
        AncsDiagnostics.devices.forEach { (address, label) ->
            deviceList.addView(Button(this).apply {
                text = getString(R.string.connect_named_device, label)
                isAllCaps = false
                setOnClickListener {
                    startBridgeService(AncsBridgeService.ACTION_CONNECT) {
                        putExtra(AncsBridgeService.EXTRA_ADDRESS, address)
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun requestAndScan() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), BLUETOOTH_PERMISSION_REQUEST)
        else beginScan()
    }

    private fun beginScan() {
        startBridgeService(AncsBridgeService.ACTION_SCAN)
    }

    private fun startBridgeService(action: String, fill: Intent.() -> Unit = {}) {
        val intent = Intent(this, AncsBridgeService::class.java).setAction(action).apply(fill)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("status", lastStatus)
        super.onSaveInstanceState(outState)
    }

    private fun requestOrSend() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        } else {
            sendNotification()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) sendNotification()
            else updateStatus(getString(R.string.permission_denied))
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) beginScan()
            else bridgeStatus.text = getString(R.string.bluetooth_permissions_needed)
        }
    }

    private fun sendNotification() {
        if (!notifications.areNotificationsEnabled()) {
            updateStatus(getString(R.string.permission_denied))
            return
        }
        if (notifications.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            updateStatus(getString(R.string.channel_blocked))
            return
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val body = getString(R.string.notification_body, time)
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        notifications.notify(TEST_ID, notification)
        updateStatus(getString(R.string.posted, time))
    }

    private fun sendGroupPreviewSample() {
        if (!notifications.areNotificationsEnabled()) {
            updateStatus(getString(R.string.permission_denied))
            return
        }
        val now = Instant.now()
        val previews = ConversationPreviews()
        val messages = listOf(
            BridgeMessage("sample.chat", "weekend-plans", "Weekend plans", true, "Alex", "Anyone free Saturday?", now.minusSeconds(120)),
            BridgeMessage("sample.chat", "weekend-plans", "Weekend plans", true, "Sam", "I can do the afternoon", now.minusSeconds(60)),
            BridgeMessage("sample.chat", "weekend-plans", "Weekend plans", true, "Alex", "Great, let's meet at 2", now),
        )
        val preview = messages.mapNotNull(previews::add).last()
        val openApp = PendingIntent.getActivity(this, GROUP_PREVIEW_ID,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(GROUP_PREVIEW_ID, preview.buildNotification(
            this, CHANNEL, R.drawable.ic_notification, openApp,
        ))
        updateStatus(getString(R.string.group_preview_posted))
    }

    private fun updateStatus(message: String) {
        lastStatus = message
        status.text = message
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "test_messages"
        private const val TEST_ID = 1
        private const val GROUP_PREVIEW_ID = 2
        private const val PERMISSION_REQUEST = 10
        private const val BLUETOOTH_PERMISSION_REQUEST = 11
    }
}
