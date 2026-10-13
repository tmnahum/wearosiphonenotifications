package dev.tmn.watchtest.bridge

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Display-context grouping, not an iOS thread ID. Scoped to one ANCS connection. */
data class ConversationContext(val app: String, val rule: String, val labels: List<String>)
data class ConversationMatch(val context: ConversationContext, val title: String, val sender: String, val isGroup: Boolean = true)

object AncsConversationRules {
    const val MESSAGES = "com.apple.MobileSMS"
    const val DISCORD = "com.hammerandchisel.discord"
    // Observed on this iPhone. Discord isolates each display label with FSI/PDI.
    // Requiring the actual delimiters avoids splitting punctuation inside a sender/channel name.
    private val discordChannel = Regex("^\\u2068([^\\u2068\\u2069]+)\\u2069 \\(\\u2068(#[^\\u2068\\u2069]+)\\u2069, \\u2068([^\\u2068\\u2069]+)\\u2069\\)$")

    private fun isGenericMessagesTitle(title: String): Boolean =
        title.lowercase() in setOf("messages", "imessage", "message", "new message", "text message", "sms") ||
            Regex("^\\d+ (?:new )?(?:i)?messages?$", RegexOption.IGNORE_CASE).matches(title)

    fun match(fields: Map<Int, String>): ConversationMatch? {
        val app = fields[0].orEmpty()
        if (app == MESSAGES) {
            // Observed iMessage named-group layout on this iPhone: title=sender, subtitle=group.
            // This is a visible-label key, not the unexposed iOS thread identifier.
            val sender = fields[1].orEmpty().trim()
            val group = fields[2].orEmpty().trim()
            if (sender.isBlank() || fields[3].orEmpty().isBlank() || isGenericMessagesTitle(sender)) return null
            if (group.isNotBlank()) {
                return ConversationMatch(ConversationContext(app, "messages-group-subtitle", listOf(group)), group, sender)
            }
            // No exported group context: treat the visible sender as a direct-chat label.
            // This cannot distinguish two contacts with the same display name, or an iOS
            // preview that omits group context. It is intentionally scoped to Messages.
            return ConversationMatch(ConversationContext(app, "messages-direct-sender", listOf(sender)),
                sender, sender, isGroup = false)
        }
        if (app != DISCORD || fields[2].orEmpty().isNotBlank() || fields[3].orEmpty().isBlank()) return null
        val parts = discordChannel.matchEntire(fields[1].orEmpty())?.groupValues ?: return null
        val sender = parts[1].trim()
        val channel = parts[2].trim()
        val server = parts[3].trim()
        if (sender.isBlank() || channel.length <= 1 || server.isBlank()) return null
        return ConversationMatch(ConversationContext(app, "discord-channel-labels", listOf(server, channel)),
            "$channel · $server", sender)
    }
}

data class AncsCardMessage(val uid: Long, val sender: String?, val text: String, val timestamp: Instant)
data class AncsCard(
    val tag: String,
    val app: String,
    val title: String,
    val conversation: Boolean,
    val isGroupConversation: Boolean,
    val messages: List<AncsCardMessage>,
)
data class AncsCardChanges(val updated: List<AncsCard>, val removed: Set<String>)

/** Bounded active-notification model. UIDs identify messages, including edits/removals. */
class AncsConversationStore(private val maxCards: Int = 32, private val maxMessages: Int = 8) {
    init { require(maxCards > 0 && maxMessages > 0) }
    private data class Key(val context: ConversationContext?, val singleUid: Long?)
    private data class Entry(val key: Key, val app: String, val title: String, val isGroup: Boolean, val message: AncsCardMessage)
    private val entries = linkedMapOf<Long, Entry>()
    private val tags = linkedMapOf<Key, String>()
    private var nextTag = 0L
    val conversationCount get() = tags.keys.count { it.context != null }
    val cardCount get() = tags.size
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun upsert(event: AncsProtocol.Event, fields: Map<Int, String>, now: Instant): AncsCardChanges {
        val before = snapshot().associateBy { it.tag }
        val previous = entries.remove(event.uid)
        val match = AncsConversationRules.match(fields)
        val key = Key(match?.context, if (match == null) event.uid else null)
        val app = fields[0].orEmpty().ifBlank { "iPhone" }
        val title = match?.title ?: fields[1].orEmpty().ifBlank { app }
        val body = if (match != null) fields[3].orEmpty() else
            listOf(fields[2].orEmpty(), fields[3].orEmpty()).filter(String::isNotBlank).joinToString("\n")
        val timestamp = runCatching {
            LocalDateTime.parse(fields[5].orEmpty(), dateFormat).atZone(ZoneId.systemDefault()).toInstant()
        }.getOrNull() ?: previous?.message?.timestamp ?: if (event.flags and 4 != 0) Instant.EPOCH else now
        entries[event.uid] = Entry(key, app, title, match?.isGroup == true,
            AncsCardMessage(event.uid, match?.sender, body.ifBlank { "Notification preview unavailable" }, timestamp))
        if (key !in tags) tags[key] = "ancs:card:${nextTag++}"
        // Tags remain stable when adding another sender. Retention uses notification time,
        // because the iPhone can replay its backlog in an arbitrary order.
        val tag = tags.remove(key)!!
        tags[key] = tag
        entries.values.filter { it.key == key }.sortedBy { it.message.timestamp }
            .dropLast(maxMessages).forEach { entries.remove(it.message.uid) }
        pruneEmptyKeys()
        while (tags.size > maxCards) {
            val oldest = tags.keys.minBy { candidate ->
                entries.values.filter { it.key == candidate }.maxOfOrNull { it.message.timestamp } ?: Instant.MIN
            }
            entries.entries.removeAll { it.value.key == oldest }
            tags.remove(oldest)
        }
        pruneEmptyKeys()
        return changes(before)
    }

    fun remove(uid: Long): AncsCardChanges {
        val before = snapshot().associateBy { it.tag }
        entries.remove(uid)
        pruneEmptyKeys()
        return changes(before)
    }

    fun snapshot(): List<AncsCard> = tags.mapNotNull { (key, tag) ->
        val members = entries.values.filter { it.key == key }.sortedBy { it.message.timestamp }
        val latest = members.lastOrNull() ?: return@mapNotNull null
        AncsCard(tag, latest.app, latest.title, key.context != null, latest.isGroup, members.map { it.message })
    }

    private fun pruneEmptyKeys() { tags.keys.retainAll(entries.values.map { it.key }.toSet()) }
    private fun changes(before: Map<String, AncsCard>): AncsCardChanges {
        val after = snapshot().associateBy { it.tag }
        return AncsCardChanges(after.values.filter { before[it.tag] != it }, before.keys - after.keys)
    }
    fun clear() { entries.clear(); tags.clear(); nextTag = 0 }
}
