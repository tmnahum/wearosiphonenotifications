package dev.tmn.watchtest.bridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import java.time.Instant
import dev.tmn.watchtest.MainActivity
import dev.tmn.watchtest.R
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.UUID

/** All transport and diagnostic state is confined to the main looper. */
@SuppressLint("MissingPermission")
class AncsBridgeService : Service() {
    companion object {
        const val ACTION_SCAN = "dev.tmn.watchtest.ancs.SCAN"
        const val ACTION_CONNECT = "dev.tmn.watchtest.ancs.CONNECT"
        const val ACTION_DISCONNECT = "dev.tmn.watchtest.ancs.DISCONNECT"
        const val EVENT_DEVICE = "dev.tmn.watchtest.ancs.DEVICE"
        const val EVENT_STATE = "dev.tmn.watchtest.ancs.STATE"
        const val EVENT_FIELDS = "dev.tmn.watchtest.ancs.FIELDS"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "ancs_diagnostics"
        private const val MESSAGES = "iphone_notifications"
        private const val NOTIFICATION_ID = 41
        private val ANCS = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        private val NS = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        private val CP = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        private val DS = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val bluetooth by lazy { getSystemService(BluetoothManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var subscribing: UUID? = null
    private var ready = false
    private var timeout: Runnable? = null
    private var reconnectTask: Runnable? = null
    private var requestTask: Runnable? = null
    private var selectedAddress: String? = null
    private var retryAttempt = 0
    private val session = AncsSession(capacity = 512)
    private val conversations = AncsConversationStore()

    override fun onCreate() {
        super.onCreate()
        // A killed/replaced process cannot keep ANCS session identifiers alive.
        notifications.activeNotifications.filter { it.tag?.startsWith("ancs:") == true }
            .forEach { notifications.cancel(it.tag, it.id) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, foregroundNotification(AncsDiagnostics.status))
        try {
            when (intent?.action) {
                ACTION_SCAN -> listPairedDevices()
                ACTION_CONNECT -> intent.getStringExtra(EXTRA_ADDRESS)?.let { connect(it) }
                ACTION_DISCONNECT -> {
                    selectedAddress = null
                    closeSession()
                    publishState("Disconnected")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } catch (_: SecurityException) { fail("Bluetooth permission is required.") }
        return START_NOT_STICKY
    }
    override fun onDestroy() { selectedAddress = null; closeSession(); super.onDestroy() }

    private fun listPairedDevices() {
        AncsDiagnostics.devices.clear()
        if (bluetooth.adapter?.isEnabled != true) {
            publishState("Bluetooth is off or unavailable")
            return
        }
        // Names are labels, not identity evidence. Offer all existing bonds, never create one.
        bluetooth.adapter.bondedDevices.forEach { device ->
            val label = "${device.name ?: "Bluetooth device"} · paired · ${device.address.takeLast(5)}"
            AncsDiagnostics.devices[device.address] = label
        }
        send(EVENT_DEVICE)
        if (!ready) publishState("Select your already-paired iPhone.")
    }

    private fun connect(address: String, retry: Boolean = false) {
        val device = bluetooth.adapter?.bondedDevices?.firstOrNull { it.address == address }
        if (device == null) { publishState("Select an existing paired device; automatic pairing is disabled."); return }
        selectedAddress = address
        if (!retry) retryAttempt = 0
        closeSession()
        publishState("Connecting to paired iPhone…")
        // This overload delivers callbacks on the same looper as service/UI state.
        gatt = device.connectGatt(this, retry, callback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, handler)
        armTimeout("Connection timed out.", if (retry) 60_000 else 30_000, retry = true)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (client !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("Disconnected (Bluetooth status $status).", retry = true)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                publishState("Connected. Discovering notification service…")
                armTimeout("Service discovery timed out.")
                if (!client.discoverServices()) fail("Could not start service discovery.")
            }
        }
        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (client !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed ($status)."); return }
            val service = client.getService(ANCS)
            control = service?.getCharacteristic(CP)
            val data = service?.getCharacteristic(DS)
            if (control == null || data == null || service.getCharacteristic(NS) == null) {
                fail("iPhone notification service unavailable. Check Share System Notifications, then reconnect.")
                return
            }
            subscribe(client, data)
        }
        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (client !== gatt || descriptor.uuid != CCCD || descriptor.characteristic.uuid != subscribing) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Notification subscription failed ($status)."); return }
            if (subscribing == DS) {
                val source = client.getService(ANCS)?.getCharacteristic(NS)
                if (source == null) fail("Notification service changed. Reconnect.") else subscribe(client, source)
            } else {
                subscribing = null
                ready = true
                retryAttempt = 0
                cancelTimeout()
                publishState("ANCS connected. Waiting for iPhone notifications.")
                processNext()
            }
        }
        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (client !== gatt || characteristic.uuid != CP || session.current == null) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // ANCS errors have no response stream. A removed UID can legitimately return 0xA2.
                if (status in 0xA0..0xA3) {
                    cancelTimeout()
                    session.discardCurrent()
                    publishState("iPhone declined a field request ($status). Continuing…")
                    processNext()
                } else fail("Notification field write failed ($status).", retry = true)
                return
            }
            session.acknowledgeWrite()
            finishResponse()
        }
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (client !== gatt) return
            when (characteristic.uuid) {
                NS -> AncsProtocol.parseEvent(value)?.let { event ->
                    AncsDiagnostics.events++
                    session.enqueue(event)
                    if (event.eventId == AncsProtocol.EVENT_REMOVED) {
                        renderChanges(conversations.remove(event.uid), silent = true)
                        AncsDiagnostics.removeLayout(event.uid)
                        AncsDiagnostics.records.removeAll { it.first == event.uid }
                        send(EVENT_FIELDS)
                    }
                    processNext()
                }
                DS -> {
                    if (session.current == null) return
                    try { session.append(value); finishResponse() }
                    catch (error: IllegalArgumentException) { fail("Notification protocol error: ${error.message}. Reconnect.") }
                    catch (_: IllegalStateException) { fail("Unexpected notification response. Reconnect.") }
                }
            }
        }
        override fun onServiceChanged(client: BluetoothGatt) {
            if (client === gatt) fail("iPhone services changed.", retry = true)
        }
    }

    private fun subscribe(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        subscribing = characteristic.uuid
        val descriptor = characteristic.getDescriptor(CCCD)
        if (descriptor == null || !client.setCharacteristicNotification(characteristic, true)) {
            fail("Could not enable iPhone notifications."); return
        }
        armTimeout("Notification subscription timed out. Check Share System Notifications.")
        if (client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS)
            fail("Could not start notification subscription.")
    }
    private fun processNext() {
        if (!ready || session.current != null || session.pendingCount == 0 || requestTask != null) return
        val client = gatt ?: return
        // Yield out of the GATT callback before starting the next ATT operation.
        requestTask = Runnable {
            requestTask = null
            if (client === gatt && ready) writeNext()
        }.also { handler.postDelayed(it, 100) }
    }
    private fun writeNext() {
        if (!ready) return
        val client = gatt ?: return
        val characteristic = control ?: return
        val event = session.next() ?: return
        armTimeout("Notification response timed out. Reconnect to start a fresh session.")
        val status = client.writeCharacteristic(characteristic, AncsProtocol.getNotificationAttributes(event.uid),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (status != BluetoothStatusCodes.SUCCESS)
            fail("Could not request notification fields ($status).", retry = true)
    }
    private fun finishResponse() {
        if (!session.complete) return
        cancelTimeout()
        session.finish()?.let { result ->
            AncsDiagnostics.received++
            AncsDiagnostics.recordLayout(result.event.uid, result.fields)
            val labels = listOf("App identifier", "Title", "Subtitle", "Message", "Message size", "Date", "Positive action", "Negative action")
            val record = labels.mapIndexed { id, name -> "$name: ${result.fields[id].orEmpty()}" }.joinToString("\n") +
                "\nEvent: ${result.event.eventId}\nFlags: ${result.event.flags}\nCategory: ${result.event.category}\nUID (session only): ${result.event.uid}"
            AncsDiagnostics.record(result.event.uid, record)
            renderChanges(conversations.upsert(result.event, result.fields, Instant.now()),
                silent = result.event.flags and 5 != 0 || result.event.eventId == AncsProtocol.EVENT_MODIFIED)
            publishState("Receiving iPhone notifications: ${AncsDiagnostics.received} received, ${AncsDiagnostics.posted} posted.")
            send(EVENT_FIELDS)
        }
        processNext()
    }
    private fun renderChanges(changes: AncsCardChanges, silent: Boolean) {
        changes.removed.forEach { notifications.cancel(it, 0) }
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(NotificationChannel(MESSAGES, "iPhone notifications", NotificationManager.IMPORTANCE_HIGH))
        changes.updated.forEach { card ->
            val latest = card.messages.last()
            val builder = NotificationCompat.Builder(this, MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(card.title).setContentText(latest.text)
                .setSubText(when (card.app) {
                    AncsConversationRules.DISCORD -> "Discord"
                    "com.apple.MobileSMS" -> "Messages"
                    "org.whispersystems.signal" -> "Signal"
                    else -> card.app
                })
                .setContentIntent(openApp()).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setWhen(latest.timestamp.toEpochMilli()).setShowWhen(true)
                .setSilent(silent)
            if (card.conversation) {
                val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                    .setConversationTitle(card.title).setGroupConversation(card.isGroupConversation)
                card.messages.forEach { message ->
                    style.addMessage(message.text, message.timestamp.toEpochMilli(),
                        Person.Builder().setName(message.sender ?: "Sender unavailable").build())
                }
                builder.setStyle(style).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            } else builder.setStyle(NotificationCompat.BigTextStyle().bigText(latest.text))
            notifications.notify(card.tag, 0, builder.build())
            AncsDiagnostics.posted++
        }
    }
    private fun armTimeout(message: String, delay: Long = 15_000, retry: Boolean = false) {
        cancelTimeout()
        timeout = Runnable { fail(message, retry) }.also { handler.postDelayed(it, delay) }
    }
    private fun cancelTimeout() { timeout?.let(handler::removeCallbacks); timeout = null }
    private fun closeSession() {
        requestTask?.let(handler::removeCallbacks)
        requestTask = null
        reconnectTask?.let(handler::removeCallbacks)
        reconnectTask = null
        cancelTimeout()
        val old = gatt
        gatt = null // Ignore callbacks from the old client, including disconnects.
        try { old?.disconnect(); old?.close() } catch (_: SecurityException) { }
        ready = false
        control = null
        subscribing = null
        session.clear()
        conversations.snapshot().forEach { notifications.cancel(it.tag, 0) }
        conversations.clear()
        AncsDiagnostics.clearSession()
        send(EVENT_FIELDS)
    }
    private fun fail(message: String, retry: Boolean = false) {
        closeSession()
        val address = selectedAddress
        if (retry && address != null) {
            val delay = (2_000L shl retryAttempt.coerceAtMost(5)).coerceAtMost(60_000L)
            retryAttempt++
            publishState("$message Retrying in ${delay / 1000}s…")
            reconnectTask = Runnable {
                reconnectTask = null
                if (selectedAddress == address) {
                    try { connect(address, retry = true) }
                    catch (_: SecurityException) { fail("Bluetooth permission is required.") }
                }
            }.also { handler.postDelayed(it, delay) }
        } else publishState(message)
    }
    private fun publishState(message: String) {
        AncsDiagnostics.status = message
        send(EVENT_STATE)
        notifications.notify(NOTIFICATION_ID, foregroundNotification(message))
    }
    private fun send(action: String) { sendBroadcast(Intent(action).setPackage(packageName)) }
    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun foregroundNotification(text: String): Notification {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "iPhone bridge status", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("iPhone bridge").setContentText(text).setContentIntent(openApp()).setOngoing(true).build()
    }
    /** Safe ADB diagnostics: deliberately excludes notification fields and device addresses. */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("ANCS ready=$ready events=${AncsDiagnostics.events} received=${AncsDiagnostics.received} posted=${AncsDiagnostics.posted} queued=${session.pendingCount} inFlight=${session.current != null} cards=${conversations.cardCount} conversations=${conversations.conversationCount}")
        writer.println("Status: ${AncsDiagnostics.status}")
        if (args?.firstOrNull() == "layout") {
            val query = args.drop(1).joinToString(" ").trim()
            if (query.isNotBlank()) AncsDiagnostics.describeLayouts(query).forEach(writer::println)
        }
    }
}
