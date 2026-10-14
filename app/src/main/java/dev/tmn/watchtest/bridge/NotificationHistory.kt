package dev.tmn.watchtest.bridge

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Local IDs are durable. Remote UIDs are never persisted or reused across ANCS sessions. */
data class SavedNotification(val id: Long, val source: String, val fields: Map<Int, String>, val receivedAt: Instant)

class NotificationHistory {
    private val store = AncsConversationStore()
    private val records = linkedMapOf<Long, SavedNotification>()
    private val remoteIds = mutableMapOf<Long, Long>()
    private val replayCandidates = linkedSetOf<Long>()
    private var source = ""
    private var nextId = System.currentTimeMillis() * 1_000L
    private val clearedFingerprints = mutableSetOf<String>()
    private val ignoredUids = mutableSetOf<Long>()
    val cardCount get() = store.cardCount
    val conversationCount get() = store.conversationCount
    fun snapshot() = store.snapshot()
    fun saved() = records.values.toList()
    fun dismissed() = clearedFingerprints.toSet()

    fun beginSession(phone: String) {
        source = phone
        remoteIds.clear()
        ignoredUids.clear()
        replayCandidates.clear()
        replayCandidates.addAll(records.values.filter { it.source == phone }.map { it.id })
    }
    fun endSession() { remoteIds.clear(); ignoredUids.clear(); replayCandidates.clear() }

    fun upsert(event: AncsProtocol.Event, fields: Map<Int, String>, now: Instant): AncsCardChanges {
        if (event.uid in ignoredUids) return AncsCardChanges(emptyList(), emptySet())
        if (event.flags and 4 != 0 && validDate(fields[5]) && fingerprint(source, fields) in clearedFingerprints) {
            ignoredUids.add(event.uid)
            return AncsCardChanges(emptyList(), emptySet())
        }
        val existing = remoteIds[event.uid]
        // Only replayed notifications are matched across sessions. Live repeated messages
        // always get separate IDs, even with identical text and second-resolution timestamps.
        val matched = if (existing == null && event.flags and 4 != 0 && validDate(fields[5])) {
            replayCandidates.firstOrNull { id -> records[id]?.let { sameNotification(it.fields, fields) } == true }
        } else null
        val id = existing ?: matched ?: nextId++
        replayCandidates.remove(id) // Each historical occurrence can match at most once.
        remoteIds[event.uid] = id
        val previous = records[id]
        val saved = SavedNotification(id, source, fields.filterKeys { it in 0..5 }.toMap(), previous?.receivedAt ?: now)
        records[id] = saved
        val changes = store.upsert(event.copy(uid = id), fields, saved.receivedAt, source)
        snapshot().flatMap { it.messages }.firstOrNull { it.uid == id }?.let {
            records[id] = saved.copy(receivedAt = it.timestamp)
        }
        prune()
        // On a new service instance, an unchanged replay still represents an active phone
        // notification and needs a silent tray card. Disk restoration alone never posts cards.
        val replayCard = if (matched != null) snapshot().firstOrNull { card -> card.messages.any { it.uid == id } } else null
        return if (replayCard != null && changes.updated.none { it.tag == replayCard.tag })
            changes.copy(updated = changes.updated + replayCard) else changes
    }

    /** Dismiss the active card, but keep collected conversation history for the app/next message. */
    fun remove(uid: Long): AncsCardChanges {
        val id = remoteIds.remove(uid) ?: return AncsCardChanges(emptyList(), emptySet())
        val card = snapshot().firstOrNull { it.messages.any { message -> message.uid == id } }
            ?: return AncsCardChanges(emptyList(), emptySet())
        val active = remoteIds.values.toSet()
        return if (card.messages.any { it.uid in active }) AncsCardChanges(emptyList(), emptySet())
        else AncsCardChanges(emptyList(), setOf(card.tag))
    }

    fun restore(saved: List<SavedNotification>, dismissed: Set<String> = emptySet()) {
        store.clear(); records.clear(); endSession(); clearedFingerprints.clear()
        clearedFingerprints.addAll(dismissed)
        require(saved.map { it.id }.distinct().size == saved.size)
        saved.forEach { record ->
            require(record.id in 1 until Long.MAX_VALUE)
            records[record.id] = record
            store.upsert(AncsProtocol.Event(0, 0, 0, 0, record.id), record.fields, record.receivedAt, record.source, trackChanges = false)
            nextId = maxOf(nextId, record.id + 1)
        }
        prune()
    }
    fun clearConversation(tag: String): AncsCardChanges {
        val ids = snapshot().firstOrNull { it.tag == tag }?.messages?.map { it.uid }?.toSet()
            ?: return AncsCardChanges(emptyList(), emptySet())
        ids.forEach { id ->
            records.remove(id)?.let { clearedFingerprints.add(fingerprint(it.source, it.fields)) }
        }
        store.removeAll(ids)
        ignoredUids.addAll(remoteIds.filterValues { it in ids }.keys)
        remoteIds.entries.removeAll { it.value in ids }
        replayCandidates.removeAll(ids)
        return AncsCardChanges(emptyList(), setOf(tag))
    }
    fun clear() {
        records.values.forEach { clearedFingerprints.add(fingerprint(it.source, it.fields)) }
        ignoredUids.addAll(remoteIds.keys)
        records.clear()
        store.clear()
        remoteIds.clear()
        replayCandidates.clear()
    }
    private fun fingerprint(phone: String, fields: Map<Int, String>): String {
        val values = listOf(phone) + listOf(0, 1, 2, 3, 5).map { fields[it].orEmpty() }
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun prune() {
        val retained = snapshot().flatMap { it.messages }.map { it.uid }.toSet()
        records.keys.retainAll(retained)
        replayCandidates.retainAll(retained)
        remoteIds.entries.removeAll { it.value !in retained }
    }
    private fun validDate(date: String?) = runCatching {
        LocalDateTime.parse(date.orEmpty(), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
    }.isSuccess
    private fun sameNotification(a: Map<Int, String>, b: Map<Int, String>) =
        listOf(0, 1, 2, 3, 5).all { a[it].orEmpty() == b[it].orEmpty() }
}

data class SavedHistory(val records: List<SavedNotification>, val dismissed: Set<String>)

/** Versioned format with field-size and file-length validation. No Java object deserialization and no session Bluetooth handles. */
object HistoryCodec {
    private const val MAGIC = 0x57485231
    fun write(output: OutputStream, records: List<SavedNotification>, dismissed: Set<String> = emptySet()) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(1)
        data.writeInt(records.size)
        records.forEach { record ->
            data.writeLong(record.id)
            data.writeUTF(record.source)
            data.writeLong(record.receivedAt.toEpochMilli())
            for (key in 0..5) data.writeUTF(record.fields[key].orEmpty())
        }
        data.writeInt(dismissed.size)
        dismissed.forEach(data::writeUTF)
        data.flush()
    }
    fun read(input: InputStream): SavedHistory {
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC && data.readInt() == 1) { "Unsupported history format" }
        val count = data.readInt()
        require(count >= 0 && count <= data.available() / 30)
        val records = List(count) {
            val id = data.readLong()
            val source = data.readUTF()
            require(source.length <= 128)
            val received = Instant.ofEpochMilli(data.readLong())
            val fields = (0..5).associateWith { data.readUTF().also { require(it.length <= 4096) } }
            SavedNotification(id, source, fields, received)
        }
        val dismissedCount = data.readInt()
        require(dismissedCount >= 0 && dismissedCount <= data.available() / 66)
        val dismissed = List(dismissedCount) { data.readUTF().also { require(it.matches(Regex("[0-9a-f]{64}"))) } }.toSet()
        require(data.read() == -1) { "Trailing history data" }
        return SavedHistory(records, dismissed)
    }
}
