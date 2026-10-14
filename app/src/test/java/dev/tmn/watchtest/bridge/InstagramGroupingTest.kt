package dev.tmn.watchtest.bridge

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InstagramGroupingTest {
    private fun fields(sender: String, group: String = "") =
        mapOf(0 to AncsConversationRules.INSTAGRAM, 1 to sender, 2 to group, 3 to "Synthetic hello")

    @Test fun differentSendersShareGroupButDirectChatsAndOtherGroupsStaySeparate() {
        val history = NotificationHistory()
        history.beginSession("test-phone")
        fun add(uid: Long, sender: String, group: String = "") {
            history.upsert(AncsProtocol.Event(0, 0, 0, 1, uid), fields(sender, group), Instant.EPOCH)
        }
        add(1, "alex_test", "Weekend plans")
        add(2, "sam_test", "Weekend plans")
        add(3, "alex_test")
        add(4, "alex_test", "Other plans")
        add(5, "Weekend plans") // Contact name identical to group name must not merge.
        val cards = history.snapshot()
        assertEquals(4, cards.size)
        val group = cards.single { it.isGroupConversation && it.title == "Weekend plans" }
        assertEquals(listOf("alex_test", "sam_test"), group.messages.map { it.sender })
        assertTrue(group.messages.all { it.category == 0 })
        assertEquals(2, cards.count { !it.isGroupConversation })
    }

    @Test fun explicitGroupingSettingsStillOverrideInstagramRules() {
        val fields = fields("alex_test", "Weekend plans")
        assertNull(AncsConversationRules.resolve(fields, AppSettings(grouping = Grouping.SEPARATE), "Instagram"))
        val combined = AncsConversationRules.resolve(fields, AppSettings(grouping = Grouping.APP), "Instagram")!!
        assertEquals("app-inbox", combined.context.rule)
        assertEquals("Instagram", combined.title)
    }

    @Test fun missingOrGenericPreviewsDoNotInventAnInstagramConversation() {
        assertNull(AncsConversationRules.match(fields("Instagram")))
        assertNull(AncsConversationRules.match(fields("")))
        assertNull(AncsConversationRules.match(fields("alex_test") + (3 to "")))
    }
}
