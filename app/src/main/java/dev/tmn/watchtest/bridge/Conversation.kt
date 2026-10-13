package dev.tmn.watchtest.bridge

import android.content.Context
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import java.time.Instant

/** Normalized message delivered by a transport adapter such as ANCS. */
data class BridgeMessage(
    val sourceApp: String,
    /** Stable only when the source provides a real conversation identifier. */
    val conversationId: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean,
    val sender: String?,
    val text: String,
    val timestamp: Instant,
)

/** Session-scoped conversation preview. Callers must provide an explicit identity to group. */
class ConversationPreview(
    val sourceApp: String,
    val conversationId: String,
    val title: String?,
    val isGroupConversation: Boolean,
    private val maxMessages: Int = 8,
) {
    private val messages = ArrayDeque<BridgeMessage>()

    fun add(message: BridgeMessage) {
        require(message.sourceApp == sourceApp && message.conversationId == conversationId) {
            "A preview may contain messages from one source conversation"
        }
        if (message.text.isBlank()) return
        messages.removeAll { it.timestamp == message.timestamp && it.sender == message.sender && it.text == message.text }
        messages.addLast(message)
        while (messages.size > maxMessages.coerceAtLeast(1)) messages.removeFirst()
    }

    fun snapshot(): List<BridgeMessage> = messages.toList()

    fun asMessagingStyle(): NotificationCompat.MessagingStyle {
        val self = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(self)
        title?.takeIf(String::isNotBlank)?.let(style::setConversationTitle)
        messages.forEach { message ->
            val sender = message.sender?.takeIf(String::isNotBlank)?.let {
                Person.Builder().setName(it).build()
            }
            style.addMessage(message.text, message.timestamp.toEpochMilli(), sender)
        }
        style.setGroupConversation(isGroupConversation)
        return style
    }

    fun buildNotification(
        context: Context,
        channelId: String,
        smallIcon: Int,
        contentIntent: PendingIntent,
    ): android.app.Notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(smallIcon)
        .setContentTitle(title?.takeIf(String::isNotBlank) ?: sourceApp)
        .setContentText(messages.lastOrNull()?.let { "${it.sender?.let { name -> "$name: " } ?: ""}${it.text}" } ?: "")
        .setStyle(asMessagingStyle())
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()

}

/**
 * Safe grouping boundary for bridge adapters. ANCS has no thread ID, so an ANCS adapter
 * should leave conversationId null until it has a verified source-specific identity.
 */
class ConversationPreviews(
    private val maxMessagesPerConversation: Int = 8,
    private val maxConversations: Int = 32,
) {
    private val previews = linkedMapOf<Pair<String, String>, ConversationPreview>()

    fun add(message: BridgeMessage): ConversationPreview? {
        val id = message.conversationId?.takeIf(String::isNotBlank) ?: return null
        val key = message.sourceApp to id
        if (!previews.containsKey(key) && previews.size >= maxConversations.coerceAtLeast(1)) {
            previews.remove(previews.keys.first())
        }
        val preview = previews.getOrPut(key) {
            ConversationPreview(message.sourceApp, id, message.conversationTitle,
                message.isGroupConversation, maxMessagesPerConversation)
        }
        preview.add(message)
        return preview
    }

    /** ANCS session identifiers and data must be discarded when the BLE session ends. */
    fun clearSession() = previews.clear()
}
