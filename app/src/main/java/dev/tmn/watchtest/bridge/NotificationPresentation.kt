package dev.tmn.watchtest.bridge

/** Presentation is independent of how messages are grouped into cards. */
object NotificationPresentation {
    fun resolve(setting: DisplayStyle, category: Int, app: String): DisplayStyle {
        if (setting != DisplayStyle.AUTOMATIC) return setting
        // Popular-app defaults also cover iOS apps which label DMs as Other.
        // Explicit user choice above always takes precedence.
        return when (app) {
            "com.apple.MobileSMS", "org.whispersystems.signal", "com.hammerandchisel.discord",
            "net.whatsapp.WhatsApp", "com.burbn.instagram" -> DisplayStyle.MESSAGES
            "com.google.Gmail", "com.apple.mobilemail" -> DisplayStyle.INBOX
            else -> when (category) {
                4 -> DisplayStyle.MESSAGES // ANCS Social
                6 -> DisplayStyle.INBOX // ANCS Email
                else -> DisplayStyle.TEXT
            }
        }
    }

    fun itemText(message: AncsCardMessage): String =
        message.sender?.takeIf { it.isNotBlank() }?.let { "$it: ${message.text}" } ?: message.text

    // Bound native payload size only; saved history remains uncapped.
    fun expandedText(messages: List<AncsCardMessage>): String = messages.takeLast(25)
        .joinToString("\n\n", transform = ::itemText).takeLast(12_000)
}
