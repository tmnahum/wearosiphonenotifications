package dev.tmn.watchtest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tmn.watchtest.bridge.AncsBridgeService
import dev.tmn.watchtest.bridge.AncsCard
import dev.tmn.watchtest.bridge.AncsConversationRules
import dev.tmn.watchtest.bridge.AncsDiagnostics
import dev.tmn.watchtest.bridge.HistoryRepository
import dev.tmn.watchtest.bridge.ConnectionPhase
import dev.tmn.watchtest.bridge.Delivery
import dev.tmn.watchtest.bridge.Grouping
import java.text.DateFormat
import java.util.Date

/** Small, scrollable native screens with large touch targets for a round watch. */
class MainActivity : Activity() {
    private val repository by lazy { HistoryRepository.get(this) }
    private val preferences by lazy { getSharedPreferences("relay", MODE_PRIVATE) }
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private lateinit var content: LinearLayout
    private var statusView: TextView? = null
    private var connectionButton: Button? = null
    private var connectionAction: Button? = null
    private var appsList: LinearLayout? = null
    private var appsRevision = -1L
    private var renderedCatalogRevision = -1L
    private var selectedApp: String? = null
    private var appPageSize = 20
    private var historyList: LinearLayout? = null
    private var renderedCards: List<AncsCard>? = null
    private var renderedHistoryLoaded = false
    private var screen = "home"
    private var visibleMessages = 50
    private var visibleConversations = 20
    private var selectedTag: String? = null
    private var permissionAction: (() -> Unit)? = null
    private var registered = false
    private var resumed = false
    private val accent = Color.rgb(130, 230, 205)
    private val muted = Color.rgb(177, 186, 194)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { navigateBack() }
        showHome()
        repository.whenReady {
            if (!isDestroyed) {
                resumeEnabledConnection()
                refresh()
            }
        }
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        resumeEnabledConnection()
        repository.pruneLog()
        if (!registered) {
            registerReceiver(receiver, IntentFilter().apply {
                addAction(AncsBridgeService.EVENT_STATE)
                addAction(AncsBridgeService.EVENT_FIELDS)
                addAction(HistoryRepository.EVENT_HISTORY)
                addAction(HistoryRepository.EVENT_APPS)
            }, RECEIVER_NOT_EXPORTED)
            registered = true
        }
        refresh()
    }
    override fun onPause() {
        resumed = false
        if (registered) { unregisterReceiver(receiver); registered = false }
        super.onPause()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    private fun page(name: String, title: String) {
        screen = name
        statusView = null
        connectionButton = null
        connectionAction = null
        appsList = null
        appsRevision = -1
        historyList = null
        renderedCards = null
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(36))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(content)
        })
        label(title, 22f, Color.WHITE, bold = true)
    }
    private fun label(text: String, size: Float = 13f, color: Int = muted, bold: Boolean = false,
                      parent: LinearLayout = content, centered: Boolean = true): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = if (centered) Gravity.CENTER else Gravity.START
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(5), dp(4), dp(7))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(text: String, primary: Boolean = false, parent: LinearLayout = content, action: () -> Unit): Button {
        val view = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) accent else Color.rgb(35, 40, 45))
                cornerRadius = dp(24).toFloat()
            }
            setOnClickListener { action() }
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(4) })
        return view
    }
    private fun showHome() {
        page("home", getString(R.string.app_name))
        statusView = label("", 13f, accent).apply { minLines = 2 }
        connectionButton = button("Connect iPhone", primary = true) {
            if (AncsDiagnostics.phase == ConnectionPhase.DISCONNECTED) connectSaved() else showConnection()
        }
        button("Apps & delivery") { showApps() }
        label("Recent conversations", 16f, Color.WHITE, bold = true)
        historyList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        button("Settings") { showSettings() }
        refresh()
    }
    private fun refresh() {
        when (screen) {
            "home" -> {
                connectionButton?.text = when (AncsDiagnostics.phase) {
                    ConnectionPhase.DISCONNECTED -> "Connect iPhone"
                    ConnectionPhase.ERROR -> "Fix connection"
                    else -> "Connection settings"
                }
                statusView?.text = AncsDiagnostics.phase.title + when {
                    !notifications.areNotificationsEnabled() -> "\nWatch notifications are disabled"
                    repository.error != null -> "\nLocal storage needs attention"
                    AncsDiagnostics.phase == ConnectionPhase.CONNECTED -> "\nNotifications are enabled"
                    else -> "\n" + AncsDiagnostics.phase.detail
                }
                val cards = repository.history.snapshot().sortedByDescending { it.messages.last().timestamp }
                if (renderedCards == cards && renderedHistoryLoaded == repository.loaded && renderedCatalogRevision == repository.catalog.revision) return
                renderedCards = cards
                renderedHistoryLoaded = repository.loaded
                renderedCatalogRevision = repository.catalog.revision
                historyList?.let { list ->
                    list.removeAllViews()
                    if (cards.isEmpty()) label(if (repository.loaded) "Messages will appear here when your iPhone sends notifications." else "Loading saved conversations…", parent = list)
                    cards.take(visibleConversations).forEach { card ->
                        val latest = card.messages.last()
                        button("${card.title}\n${appName(card.app)} · ${card.messages.size} ${if (card.messages.size == 1) "message" else "messages"}", parent = list) {
                            selectedTag = card.tag
                            visibleMessages = 50
                            showConversation()
                        }
                        label(latest.text, 12f, parent = list).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
                    }
                    if (cards.size > visibleConversations) button("More conversations", parent = list) {
                        visibleConversations += 20
                        renderedCards = null
                        refresh()
                    }
                }
            }
            "connection" -> {
                statusView?.text = connectionText()
                connectionAction?.text = when (AncsDiagnostics.phase) {
                    ConnectionPhase.CONNECTED -> "Disconnect"
                    ConnectionPhase.DISCONNECTED -> "Connect"
                    ConnectionPhase.ERROR -> "Try again"
                    else -> "Cancel connection"
                }
            }
            "apps" -> refreshApps()
            "diagnostics" -> statusView?.text = diagnosticsText()
            // Do not jump back to the top while someone reads a conversation. The newest
            // snapshot appears on next open, with an explicit refresh action in the thread.
        }
    }
    private fun showConversation() {
        val card = repository.history.snapshot().firstOrNull { it.tag == selectedTag }
        page("conversation", card?.title ?: "Conversation")
        button("Back") { showHome() }
        if (card == null) { label("This conversation is no longer saved."); return }
        label(appName(card.app))
        if (card.messages.size > visibleMessages) button("Show older messages") {
            visibleMessages += 50
            showConversation()
        }
        card.messages.takeLast(visibleMessages).forEach { message ->
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = GradientDrawable().apply { setColor(Color.rgb(25, 31, 36)); cornerRadius = dp(16).toFloat() }
                content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            }
            if (message.sender != null) label(message.sender, 13f, accent, true, bubble, false)
            label(message.text, 15f, Color.WHITE, parent = bubble, centered = false)
            label(if (message.timestamp == java.time.Instant.EPOCH) "Time unavailable" else
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date.from(message.timestamp)),
                10f, parent = bubble, centered = false)
        }
        label("Saved from iPhone notifications")
        button("Refresh") { showConversation() }
        button("All conversations") { showHome() }
    }
    private fun showConnection() {
        page("connection", "Connection")
        statusView = label(connectionText(), color = accent)
        connectionAction = button("Connect", primary = true) {
            when (AncsDiagnostics.phase) {
                ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR -> connectSaved()
                else -> startBridge(AncsBridgeService.ACTION_DISCONNECT)
            }
        }
        button("Choose paired iPhone") { withPermissions { showPhones() } }
        refresh()
        label("On your iPhone, enable Share System Notifications in this watch’s Bluetooth settings.")
        button("Back") { showHome() }
    }
    private fun connectionText(): String {
        val phase = AncsDiagnostics.phase
        val detail = if (phase == ConnectionPhase.ERROR) AncsDiagnostics.status else phase.detail
        return "${phase.title}\n$detail"
    }
    private fun showApps() {
        page("apps", "Apps & delivery")
        label("Every app seen on your iPhone appears here.")
        appsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        button("Back") { showHome() }
        refreshApps()
    }
    private fun refreshApps() {
        if (!repository.loaded) {
            appsList?.let { it.removeAllViews(); label("Loading app settings…", parent = it) }
            return
        }
        if (appsRevision == repository.catalog.revision) return
        appsRevision = repository.catalog.revision
        val list = appsList ?: return
        list.removeAllViews()
        val apps = repository.catalog.all().sortedBy { it.name.lowercase() }
        if (apps.isEmpty()) label("Apps appear after their first notification arrives.", parent = list)
        apps.take(appPageSize).forEach { app ->
            val delivery = when (app.settings.delivery) { Delivery.NORMAL -> "Normal"; Delivery.SILENT -> "Silent"; Delivery.MUTED -> "Muted" }
            button("${app.name} · $delivery", parent = list) { selectedApp = app.id; showAppSettings() }
        }
        if (apps.size > appPageSize) button("More apps", parent = list) { appPageSize += 20; appsRevision = -1; refreshApps() }
    }
    private fun showAppSettings() {
        val id = selectedApp ?: return showApps()
        val app = repository.catalog.get(id)
        page("appSettings", app.name)
        val deliveryLabels = arrayOf("Normal", "Silent", "Muted")
        button("Delivery: ${deliveryLabels[app.settings.delivery.ordinal]}") {
            AlertDialog.Builder(this).setTitle("Delivery")
                .setSingleChoiceItems(deliveryLabels, app.settings.delivery.ordinal) { dialog, which ->
                    repository.updateApp(id, repository.catalog.get(id).settings.copy(delivery = Delivery.entries[which]))
                    dialog.dismiss(); showAppSettings()
                }.setNegativeButton("Cancel", null).show()
        }
        label(when (app.settings.delivery) {
            Delivery.NORMAL -> "Show notifications with your watch’s normal alerts."
            Delivery.SILENT -> "Show notifications without sound or vibration."
            Delivery.MUTED -> "Hide this app’s watch notifications. Local history and log are still kept."
        })
        val groupingLabels = arrayOf("Automatic", "One thread per app", "Separate notifications")
        button("Grouping: ${groupingLabels[app.settings.grouping.ordinal]}") {
            AlertDialog.Builder(this).setTitle("Grouping")
                .setSingleChoiceItems(groupingLabels, app.settings.grouping.ordinal) { dialog, which ->
                    repository.updateApp(id, repository.catalog.get(id).settings.copy(grouping = Grouping.entries[which]))
                    dialog.dismiss(); showAppSettings()
                }.setNegativeButton("Cancel", null).show()
        }
        label(when (app.settings.grouping) {
            Grouping.AUTOMATIC -> "Use chat context when available. Other notifications group by matching title and subtitle."
            Grouping.APP -> "Combine everything from ${app.name} into one preview."
            Grouping.SEPARATE -> "Keep each notification as its own card."
        })
        button("Back") { showApps() }
    }
    private fun showSettings() {
        page("settings", "Settings")
        button("Connection") { showConnection() }
        button("Apps & delivery") { showApps() }
        button("Notification settings") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        label("Saved on this watch\nHistory stays until you clear its watch notification or clear it here. Clearing on iPhone keeps this history. No message-count limit or cloud backup.")
        label("Notification log · 14 days\nA separate local log is kept for future history browsing.")
        button("Clear all local data") {
            AlertDialog.Builder(this).setTitle("Clear all local data?")
                .setMessage("Deletes saved conversations and the 14-day notification log on this watch. Messages on your iPhone are unaffected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    repository.whenReady {
                        repository.clearLocalData()
                        showHome()
                    }
                }.show()
        }
        button("Diagnostics") { showDiagnostics() }
        button("Back") { showHome() }
    }
    private fun showDiagnostics() {
        page("diagnostics", "Diagnostics")
        statusView = label(diagnosticsText(), 12f)
        button("Back") { showSettings() }
    }
    private fun diagnosticsText() = "${AncsDiagnostics.status}\n\nReceived: ${AncsDiagnostics.received}\nPosted: ${AncsDiagnostics.posted}\nSaved cards: ${repository.history.cardCount}\n${repository.error ?: repository.logError ?: "Storage ready"}"

    private fun resumeEnabledConnection() {
        if (resumed && repository.loaded && AncsDiagnostics.phase == ConnectionPhase.DISCONNECTED &&
            preferences.getBoolean("enabled", false) &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            preferences.getString("phone", null)?.let { startBridge(AncsBridgeService.ACTION_CONNECT, it) }
        }
    }
    private fun connectSaved() = withPermissions {
        val address = preferences.getString("phone", null)
        if (address == null) showPhones() else {
            startBridge(AncsBridgeService.ACTION_CONNECT, address)
            showConnection()
        }
    }
    @SuppressLint("MissingPermission")
    private fun showPhones() {
        page("phones", "Choose iPhone")
        label("Select your iPhone from devices already paired with this watch.")
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter?.isEnabled != true) label("Turn on Bluetooth on the watch, then try again.")
        else {
            val devices = adapter.bondedDevices.sortedBy { it.name.orEmpty() }
            if (devices.isEmpty()) label("No paired devices found.")
            devices.forEach { device ->
                button("${device.name ?: "Paired device"}\nPaired · ${device.address.takeLast(5)}") {
                    startBridge(AncsBridgeService.ACTION_CONNECT, device.address)
                    showConnection()
                }
            }
        }
        button("Back") { showConnection() }
    }
    private fun withPermissions(action: () -> Unit) {
        val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            permissionAction = action
            requestPermissions(missing.toTypedArray(), 11)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = permissionAction
        permissionAction = null
        if (requestCode == 11 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else {
            page("permission", "Permission needed")
            label("Allow nearby devices and notifications to receive messages from your iPhone.")
            button("App settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            button("Back") { showHome() }
        }
    }
    private fun startBridge(action: String, address: String? = null) {
        if (action == AncsBridgeService.ACTION_CONNECT) AncsDiagnostics.phase = if (repository.loaded) ConnectionPhase.CONNECTING else ConnectionPhase.LOADING
        startForegroundService(Intent(this, AncsBridgeService::class.java).setAction(action)
            .apply { if (address != null) putExtra(AncsBridgeService.EXTRA_ADDRESS, address) })
    }
    private fun navigateBack() {
        when (screen) {
            "home" -> finish()
            "diagnostics" -> showSettings()
            "phones" -> showConnection()
            "appSettings" -> showApps()
            else -> showHome()
        }
    }
    private fun appName(app: String) = repository.catalog.get(app).name
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
