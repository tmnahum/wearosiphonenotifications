package dev.tmn.watchtest.bridge

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Display-context grouping, not an iOS thread ID. Keys are display-label heuristics. */
data class ConversationContext(val app: String, val rule: String, val labels: List<String>)
data class ConversationMatch(val context: ConversationContext, val title: String, val sender: String, val isGroup: Boolean = true, val message: String? = null)

object AncsConversationRules {
    const val MESSAGES = "com.apple.MobileSMS"
    const val DISCORD = "com.hammerandchisel.discord"
    // Observed on this iPhone. Discord isolates each display label with FSI/PDI.
    // Requiring the actual delimiters avoids splitting punctuation inside a sender/channel name.
    private val discordChannel = Regex("^\\u2068([^\\u2068\\u2069]+)\\u2069 \\(\\u2068(#[^\\u2068\\u2069]+)\\u2069, \\u2068([^\\u2068\\u2069]+)\\u2069\\)$")

    private fun isGenericMessagesTitle(title: String): Boolean =
        title.lowercase() in setOf("messages", "imessage", "message", "new message", "text message", "sms") ||
            Regex("^\\d+ (?:new )?(?:i)?messages?$", RegexOption.IGNORE_CASE).matches(title)

    private fun usableLabel(label: String, app: String): Boolean =
        label.isNotBlank() && label.lowercase() !in setOf("notification", "new notification", "new message", "messages", "message", "signal", "discord", "instagram", "iphone app") &&
            !label.equals(AppCatalog.fallbackName(app), true) && !Regex("^\\d+ (new )?(messages|notifications)$", RegexOption.IGNORE_CASE).matches(label)

    fun resolve(fields: Map<Int, String>, settings: AppSettings, appName: String): ConversationMatch? {
        val app = fields[0].orEmpty()
        if (settings.grouping == Grouping.SEPARATE) return null
        if (settings.grouping == Grouping.APP) return ConversationMatch(
            ConversationContext(app, "app-inbox", emptyList()), appName,
            fields[1].orEmpty().ifBlank { appName }, true)
        match(fields)?.let { return it }
        val title = fields[1].orEmpty().trim()
        val subtitle = fields[2].orEmpty().trim()
        if (app.isBlank() || !usableLabel(title, app) || title.equals(appName, true) || fields[3].orEmpty().isBlank()) return null
        if (fields[3].orEmpty().lowercase() in setOf("new message", "new notification")) return null
        // Preserve exact context. Never use a sender alone when a subtitle distinguishes chats.
        return ConversationMatch(ConversationContext(app, "fallback-title-subtitle", listOf(title, subtitle)),
            listOf(title, subtitle).filter(String::isNotBlank).joinToString(" · "), title, subtitle.isNotBlank())
    }

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
        if (app == "org.whispersystems.signal") {
            val title = fields[1].orEmpty().trim()
            val subtitle = fields[2].orEmpty().trim()
            if (!usableLabel(title, app) || fields[3].orEmpty().isBlank()) return null
            if (subtitle.isNotBlank()) return ConversationMatch(
                ConversationContext(app, "signal-subtitle", listOf(subtitle)), subtitle, title)
            // Signal's English group notification title format. Ambiguous/localized titles
            // fall back to the complete visible title instead of guessing a separator.
            val parts = title.split(" in ")
            if (parts.size == 2 && parts.all(String::isNotBlank)) return ConversationMatch(
                ConversationContext(app, "signal-group-title", listOf(parts[1])), parts[1], parts[0])
            val body = fields[3].orEmpty()
            val prefix = body.substringBefore(": ", "")
            val hasSenderPrefix = prefix.length in 1..80 && '\n' !in prefix &&
                prefix.lowercase() !in setOf("http", "https") && body.length > prefix.length + 2
            // iOS communication notifications may export group title with sender in body.
            // Grouping uses the complete title even when a particular preview omits a sender.
            return ConversationMatch(ConversationContext(app, "signal-visible-title", listOf(title)), title,
                if (hasSenderPrefix) prefix else title, hasSenderPrefix,
                if (hasSenderPrefix) body.substring(prefix.length + 2) else null)
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

/** Conversation model with optional bounds. The history adapter supplies durable local IDs. */
class AncsConversationStore(private val maxCards: Int = Int.MAX_VALUE, private val maxMessages: Int = Int.MAX_VALUE,
    private val appSettings: ((String) -> AppSettings)? = null,
    private val appName: (String) -> String = AppCatalog::fallbackName,
) {
    init { require(maxCards > 0 && maxMessages > 0) }
    private data class Key(val source: String, val context: ConversationContext?, val singleUid: Long?)
    private data class Entry(val key: Key, val app: String, val title: String, val isGroup: Boolean, val message: AncsCardMessage)
    private val entries = linkedMapOf<Long, Entry>()
    private val tags = linkedMapOf<Key, String>()
    val conversationCount get() = tags.keys.count { it.context != null }
    val cardCount get() = tags.size
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun upsert(event: AncsProtocol.Event, fields: Map<Int, String>, now: Instant, source: String = "", trackChanges: Boolean = true, timestampOverride: Instant? = null): AncsCardChanges {
        val before = if (trackChanges) snapshot().associateBy { it.tag } else emptyMap()
        val previous = entries.remove(event.uid)
        val sourceApp = fields[0].orEmpty()
        val match = appSettings?.let { AncsConversationRules.resolve(fields, it(sourceApp), appName(sourceApp)) }
            ?: if (appSettings == null) AncsConversationRules.match(fields) else null
        val key = Key(source, match?.context, if (match == null) event.uid else null)
        val app = fields[0].orEmpty().ifBlank { "iPhone" }
        val title = match?.title ?: fields[1].orEmpty().ifBlank { app }
        val body = if (match != null && match.context.rule != "app-inbox") match.message ?: fields[3].orEmpty() else
            listOf(fields[2].orEmpty(), fields[3].orEmpty()).filter(String::isNotBlank).joinToString("\n")
        val timestamp = timestampOverride ?: runCatching {
            LocalDateTime.parse(fields[5].orEmpty(), dateFormat).atZone(ZoneId.systemDefault()).toInstant()
        }.getOrNull() ?: previous?.message?.timestamp ?: if (event.flags and 4 != 0) Instant.EPOCH else now
        entries[event.uid] = Entry(key, app, title, match?.isGroup == true,
            AncsCardMessage(event.uid, match?.sender, body.ifBlank { "Notification preview unavailable" }, timestamp))
        if (key !in tags) tags[key] = stableTag(key)
        // Tags remain stable when adding another sender. Retention uses notification time,
        // because the iPhone can replay its backlog in an arbitrary order.
        val tag = tags.remove(key)!!
        tags[key] = tag
        if (maxMessages != Int.MAX_VALUE) {
            entries.values.filter { it.key == key }.sortedBy { it.message.timestamp }
                .dropLast(maxMessages).forEach { entries.remove(it.message.uid) }
        }
        while (tags.size > maxCards) {
            val oldest = tags.keys.minBy { candidate ->
                entries.values.filter { it.key == candidate }.maxOfOrNull { it.message.timestamp } ?: Instant.MIN
            }
            entries.entries.removeAll { it.value.key == oldest }
            tags.remove(oldest)
        }
        if (previous != null || maxMessages != Int.MAX_VALUE || maxCards != Int.MAX_VALUE) pruneEmptyKeys()
        return if (trackChanges) changes(before) else AncsCardChanges(emptyList(), emptySet())
    }

    fun remove(uid: Long): AncsCardChanges = removeAll(setOf(uid))

    fun removeAll(uids: Set<Long>): AncsCardChanges {
        val before = snapshot().associateBy { it.tag }
        entries.keys.removeAll(uids)
        pruneEmptyKeys()
        return changes(before)
    }

    fun snapshot(): List<AncsCard> {
        val grouped = entries.values.groupBy { it.key }
        return tags.mapNotNull { (key, tag) ->
        val members = grouped[key].orEmpty().sortedBy { it.message.timestamp }
        val latest = members.lastOrNull() ?: return@mapNotNull null
        AncsCard(tag, latest.app, latest.title, key.context != null, members.any { it.isGroup }, members.map { it.message })
    }

    }

    private fun stableTag(key: Key): String {
        val values = listOf(key.source, key.context?.app.orEmpty(), key.context?.rule.orEmpty()) +
            (key.context?.labels ?: listOf(key.singleUid.toString()))
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return "ancs:card:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun pruneEmptyKeys() { tags.keys.retainAll(entries.values.map { it.key }.toSet()) }
    private fun changes(before: Map<String, AncsCard>): AncsCardChanges {
        val after = snapshot().associateBy { it.tag }
        return AncsCardChanges(after.values.filter { before[it.tag] != it }, before.keys - after.keys)
    }
    fun clear() { entries.clear(); tags.clear() }
}
