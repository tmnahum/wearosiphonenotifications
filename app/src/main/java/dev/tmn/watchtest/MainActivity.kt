package dev.tmn.watchtest

import android.Manifest
import android.app.Activity
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import java.util.Date

class MainActivity : Activity() {
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private lateinit var status: TextView
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
        label(getString(R.string.heading), 20f)
        label(getString(R.string.intro), 13f)
        button(R.string.send) { requestOrSend() }
        status = label("", 12f)
        button(R.string.clear) {
            notifications.cancel(TEST_ID)
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
        status.text = when {
            !notifications.areNotificationsEnabled() -> getString(R.string.permission_needed)
            notifications.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE -> getString(R.string.channel_blocked)
            else -> lastStatus ?: getString(R.string.ready)
        }
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

    private fun updateStatus(message: String) {
        lastStatus = message
        status.text = message
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "test_messages"
        private const val TEST_ID = 1
        private const val PERMISSION_REQUEST = 10
    }
}
