package dev.tmn.watchtest.bridge

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class Delivery { NORMAL, SILENT }
enum class Grouping { AUTOMATIC, APP, SEPARATE, CONVERSATION }
enum class DisplayStyle { AUTOMATIC, MESSAGES, INBOX, TEXT }
data class AppSettings(val delivery: Delivery = Delivery.NORMAL, val grouping: Grouping = Grouping.AUTOMATIC,
    val displayStyle: DisplayStyle = DisplayStyle.AUTOMATIC, val hidden: Boolean = false)
data class SourceApp(val id: String, val displayName: String = "", val settings: AppSettings = AppSettings(), val lastSeen: Long = 0) {
    val name get() = displayName.ifBlank { AppCatalog.fallbackName(id) }
}
class AppCatalog {
    private val apps = linkedMapOf<String, SourceApp>()
    var revision = 0L
        private set
    fun all() = apps.values.toList()
    fun get(id: String) = apps[id] ?: SourceApp(id)
    fun observe(id: String, now: Long): Boolean {
        if (id.isBlank()) return false
        val old = apps[id]
        apps[id] = (old ?: SourceApp(id)).copy(lastSeen = maxOf(old?.lastSeen ?: 0, now))
        if (old == null) revision++
        return old == null
    }
    fun name(id: String, value: String) {
        val clean = value.trim().take(256)
        if (clean.isNotEmpty() && get(id).displayName != clean) { apps[id] = get(id).copy(displayName = clean); revision++ }
    }
    fun settings(id: String, settings: AppSettings) { apps[id] = get(id).copy(settings = settings); revision++ }
    fun restore(saved: List<SourceApp>) { apps.clear(); saved.forEach { apps[it.id] = it }; revision++ }
    companion object {
        fun fallbackName(id: String): String = when (id) {
            "com.apple.MobileSMS" -> "Messages"
            "com.hammerandchisel.discord" -> "Discord"
            "org.whispersystems.signal" -> "Signal"
            "com.google.Gmail" -> "Gmail"
            "com.apple.mobilemail" -> "Mail"
            "com.burbn.instagram" -> "Instagram"
            "net.whatsapp.WhatsApp" -> "WhatsApp"
            else -> id.substringAfterLast('.').replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifBlank { "iPhone app" }
        }
    }
}
object AppCatalogCodec {
    fun write(output: OutputStream, apps: List<SourceApp>) {
        val data = DataOutputStream(output)
        data.writeInt(0x57414333); data.writeInt(apps.size)
        apps.forEach {
            data.writeUTF(it.id); data.writeUTF(it.displayName)
            data.writeUTF(it.settings.delivery.name); data.writeUTF(it.settings.grouping.name)
            data.writeLong(it.lastSeen)
            data.writeUTF(it.settings.displayStyle.name)
            data.writeBoolean(it.settings.hidden)
        }
        data.flush()
    }
    fun read(input: InputStream): List<SourceApp> {
        val data = DataInputStream(input)
        val version = data.readInt()
        require(version in 0x57414331..0x57414333)
        val count = data.readInt(); require(count >= 0 && count <= data.available() / 16)
        val apps = List(count) {
            val id = data.readUTF(); val name = data.readUTF()
            val legacyDelivery = data.readUTF()
            val delivery = if (legacyDelivery == "MUTED") Delivery.NORMAL else Delivery.valueOf(legacyDelivery)
            val grouping = Grouping.valueOf(data.readUTF()); val seen = data.readLong()
            val style = if (version >= 0x57414332) DisplayStyle.valueOf(data.readUTF()) else DisplayStyle.AUTOMATIC
            val hidden = if (version >= 0x57414333) data.readBoolean() else legacyDelivery == "MUTED"
            SourceApp(id, name, AppSettings(delivery, grouping, style, hidden), seen)
        }
        require(data.read() == -1 && apps.map { it.id }.distinct().size == apps.size)
        return apps
    }
}

/** Built-in policy; resetting an app to AppSettings() opts back into these defaults. */
object AppDefaults {
    fun combinesMail(app: String, category: Int = -1) =
        app in setOf("com.google.Gmail", "com.apple.mobilemail") || category == 6
}
