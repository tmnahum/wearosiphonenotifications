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
import android.view.InputDevice
import android.view.MotionEvent
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
import dev.tmn.watchtest.bridge.DisplayStyle
import dev.tmn.watchtest.bridge.AppDefaults
import dev.tmn.watchtest.bridge.AppSettings
import java.text.DateFormat
import java.util.Date

/** Small, scrollable native screens with large touch targets for a round watch. */
class MainActivity : Activity() {
    private val repository by lazy { HistoryRepository.get(this) }
    private val preferences by lazy { getSharedPreferences("relay", MODE_PRIVATE) }
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private lateinit var content: LinearLayout
    private lateinit var pageScroll: ScrollView
    private var crown: CrownScrollController? = null
    private var settingsFromConversation = false
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
                openDestination(intent)
                refresh()
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        repository.whenReady { if (!isDestroyed) openDestination(intent) }
    }
    private fun openDestination(destination: Intent) {
        if (destination.getBooleanExtra(EXTRA_CONNECTION_SETTINGS, false)) {
            destination.removeExtra(EXTRA_CONNECTION_SETTINGS)
            showConnection()
            return
        }
        val app = destination.getStringExtra(EXTRA_APP_SETTINGS) ?: return
        destination.removeExtra(EXTRA_APP_SETTINGS)
        selectedApp = app
        settingsFromConversation = false
        showAppSettings()
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) && ::pageScroll.isInitialized) {
            crown?.onRotary(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
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
        crown?.stop()
        if (registered) { unregisterReceiver(receiver); registered = false }
        super.onPause()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    private fun page(name: String, title: String) {
        crown?.stop()
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
        pageScroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            isFocusableInTouchMode = true
            addView(content)
        }
        setContentView(pageScroll)
        pageScroll.requestFocus()
        crown = CrownScrollController(pageScroll, { pageScroll.scrollBy(0, it) }, { pageScroll.fling(it) })
        pageScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) crown?.onTouchDown()
            false
        }
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
        label("Notification history", 16f, Color.WHITE, bold = true)
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
                        button("${card.title}\n${appName(card.app)} · ${card.messages.size} ${if (card.messages.size == 1) "item" else "items"}", parent = list) {
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
        button("${appName(card.app)} settings") {
            selectedApp = card.app
            settingsFromConversation = true
            showAppSettings()
        }
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
        button("All history") { showHome() }
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
            val delivery = if (app.settings.hidden) "Hidden" else if (app.settings.delivery == Delivery.SILENT) "Shown · silent" else "Shown · alerts"
            button("${app.name} · $delivery", parent = list) { selectedApp = app.id; settingsFromConversation = false; showAppSettings() }
        }
        if (apps.size > appPageSize) button("More apps", parent = list) { appPageSize += 20; appsRevision = -1; refreshApps() }
    }
    private fun showAppSettings() {
        val id = selectedApp ?: return showApps()
        val app = repository.catalog.get(id)
        page("appSettings", app.name)
        val notificationLabels = arrayOf("Show with alerts", "Show silently", "Hidden")
        val notificationChoice = when {
            app.settings.hidden -> 2
            app.settings.delivery == Delivery.SILENT -> 1
            else -> 0
        }
        button("Notifications: ${notificationLabels[notificationChoice]}") {
            AlertDialog.Builder(this).setTitle("Notifications")
                .setSingleChoiceItems(notificationLabels, notificationChoice) { dialog, which ->
                    val settings = repository.catalog.get(id).settings
                    repository.updateApp(id, when (which) {
                        0 -> settings.copy(hidden = false, delivery = Delivery.NORMAL)
                        1 -> settings.copy(hidden = false, delivery = Delivery.SILENT)
                        else -> settings.copy(hidden = true)
                    })
                    dialog.dismiss(); showAppSettings()
                }.setNegativeButton("Cancel", null).create().also { showChoiceDialog(it) }
        }
        label("With alerts follows your watch’s sound and vibration settings. Silently shows cards without alerts. Hidden keeps notifications only in app history. Dismissed items stay dismissed.")
        val groupingLabels = arrayOf("App default", "Combine all notifications", "Separate notifications", "By conversation")
        button("Grouping: ${groupingLabels[app.settings.grouping.ordinal]}") {
            AlertDialog.Builder(this).setTitle("Grouping")
                .setSingleChoiceItems(groupingLabels, app.settings.grouping.ordinal) { dialog, which ->
                    repository.updateApp(id, repository.catalog.get(id).settings.copy(grouping = Grouping.entries[which]))
                    dialog.dismiss(); showAppSettings()
                }.setNegativeButton("Cancel", null).create().also { showChoiceDialog(it) }
        }
        label(when (app.settings.grouping) {
            Grouping.AUTOMATIC -> if (AppDefaults.combinesMail(id)) "Default: combine all mail from ${app.name} into one card." else "Default: email combines into one app card; chats use conversation context; other alerts match title and subtitle."
            Grouping.CONVERSATION -> "Group by chat context or matching title and subtitle, including for mail."
            Grouping.APP -> "Put all ${app.name} notifications in one card, even from different chats or senders."
            Grouping.SEPARATE -> "Keep each notification as its own card."
        })
        val styleLabels = arrayOf("Automatic", "Message previews", "Inbox list", "Expanded text")
        button("Display style: ${styleLabels[app.settings.displayStyle.ordinal]}") {
            AlertDialog.Builder(this).setTitle("Display style")
                .setSingleChoiceItems(styleLabels, app.settings.displayStyle.ordinal) { dialog, which ->
                    repository.updateApp(id, repository.catalog.get(id).settings.copy(displayStyle = DisplayStyle.entries[which]))
                    dialog.dismiss(); showAppSettings()
                }.setNegativeButton("Cancel", null).create().also { showChoiceDialog(it) }
        }
        label(when (app.settings.displayStyle) {
            DisplayStyle.AUTOMATIC -> "Popular chat apps (including Instagram) use messages; Gmail and Mail use inbox lists. Other apps use the latest iPhone category: social → messages, email → inbox, otherwise text."
            DisplayStyle.MESSAGES -> "Show sender names and messages as a conversation preview."
            DisplayStyle.INBOX -> "Show recent notifications as a compact list."
            DisplayStyle.TEXT -> "Show notification text without a conversation layout."
        })
        label("Display style changes how a card looks. Grouping controls which notifications share it. Saved history is unchanged.")
        button("Reset to app defaults") {
            repository.updateApp(id, AppSettings())
            showAppSettings()
        }
        label("Reset restores Show with alerts, app-default grouping and Automatic display. Mail combines into one card. History and dismissals are preserved.")
        button("Back") { leaveAppSettings() }
    }
    private fun leaveAppSettings() {
        if (settingsFromConversation) showConversation() else showApps()
    }
    private fun showChoiceDialog(dialog: AlertDialog) {
        dialog.show()
        val list = dialog.listView
        val dialogCrown = CrownScrollController(list, { list.scrollListBy(it) }, { list.fling(it) })
        list.isFocusableInTouchMode = true
        list.requestFocus()
        list.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                dialogCrown.onRotary(event); true
            } else false
        }
        list.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) dialogCrown.onTouchDown()
            false
        }
        dialog.setOnDismissListener { dialogCrown.stop() }
    }

    private fun showSettings() {
        page("settings", "Settings")
        button("Connection") { showConnection() }
        button("Apps & delivery") { showApps() }
        button("Notification settings") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        label("Saved on this watch\nClearing the watch tray hides notifications but keeps this history. Clearing on iPhone also keeps history. Delete history using Clear all local data below. No message-count limit or cloud backup.")
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
        label("Watch-face status\nEdit your watch face, choose a text complication slot, then select iPhone connection status. Tap it to open Connection.")
        label("Connection Tile\nAdd iPhone connection status to your watch’s Tiles for a full connection card. Tap Connection to manage it.")
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
            "home" -> moveTaskToBack(true)
            "diagnostics" -> showSettings()
            "phones" -> showConnection()
            "appSettings" -> leaveAppSettings()
            else -> showHome()
        }
    }
    private fun appName(app: String) = repository.catalog.get(app).name
    companion object {
        const val EXTRA_APP_SETTINGS = "source_app_settings"
        const val EXTRA_CONNECTION_SETTINGS = "connection_settings"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
