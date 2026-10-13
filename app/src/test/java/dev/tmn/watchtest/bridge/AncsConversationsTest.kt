package dev.tmn.watchtest.bridge

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AncsConversationsTest {
    private val now = Instant.parse("2026-10-13T20:00:00Z")
    private fun event(uid: Long, kind: Int = 0) = AncsProtocol.Event(kind, 0, 4, 1, uid)
    private fun fields(sender: String = "Alex", channel: String = "#plans", server: String = "Friends", body: String = "Hi") =
        mapOf(0 to AncsConversationRules.DISCORD,
            1 to "\u2068$sender\u2069 (\u2068$channel\u2069, \u2068$server\u2069)", 2 to "", 3 to body)

    @Test fun differentSendersInSameChannelBecomeOneCard() {
        val store = AncsConversationStore()
        val first = store.upsert(event(1), fields(), now).updated.single()
        val second = store.upsert(event(2), fields(sender = "Sam"), now.plusSeconds(1)).updated.single()
        assertEquals(first.tag, second.tag)
        assertEquals(listOf("Alex", "Sam"), second.messages.map { it.sender })
        assertTrue(second.conversation)
        assertEquals(1, store.cardCount)
    }
    @Test fun channelsServersAndAppsStaySeparate() {
        val store = AncsConversationStore()
        store.upsert(event(1), fields(), now)
        store.upsert(event(2), fields(channel = "#other"), now)
        store.upsert(event(3), fields(server = "Other server"), now)
        store.upsert(event(4), fields() + (0 to "another.app"), now)
        assertEquals(4, store.cardCount)
        assertEquals(3, store.conversationCount)
    }
    @Test fun messagesGroupSubtitleCombinesSendersButNotOtherGroups() {
        val store = AncsConversationStore()
        fun message(sender: String, group: String) = mapOf(0 to AncsConversationRules.MESSAGES, 1 to sender, 2 to group, 3 to "Test")
        store.upsert(event(1), message("Alex", "Weekend plans"), now)
        store.upsert(event(2), message("Sam", "Weekend plans"), now.plusSeconds(1))
        store.upsert(event(3), message("Alex", "Other plans"), now.plusSeconds(2))
        assertEquals(2, store.conversationCount)
        val card = store.snapshot().first { it.title == "Weekend plans" }
        assertEquals(listOf("Alex", "Sam"), card.messages.map { it.sender })
        assertNull(AncsConversationRules.match(message("Messages", "Weekend plans")))
        assertFalse(AncsConversationRules.match(message("Alex", ""))!!.isGroup)
    }
    @Test fun directMessagesGroupBySenderAndUseDirectMessagingStyle() {
        val store = AncsConversationStore()
        fun direct(sender: String, body: String) = mapOf(0 to AncsConversationRules.MESSAGES, 1 to sender, 2 to "", 3 to body)
        val first = store.upsert(event(1), direct("Alex", "Hello"), now).updated.single()
        store.upsert(event(2), direct("Alex", "One more thing"), now.plusSeconds(1))
        store.upsert(event(3), direct("Sam", "Different chat"), now.plusSeconds(2))
        val alex = store.snapshot().first { it.title == "Alex" }
        assertEquals(first.tag, alex.tag)
        assertEquals(listOf("Hello", "One more thing"), alex.messages.map { it.text })
        assertTrue(alex.conversation)
        assertFalse(alex.isGroupConversation)
        assertEquals(2, store.cardCount)
    }
    @Test fun directContactAndSameNamedGroupNeverMerge() {
        val store = AncsConversationStore()
        store.upsert(event(1), mapOf(0 to AncsConversationRules.MESSAGES, 1 to "Alex", 2 to "", 3 to "Direct"), now)
        store.upsert(event(2), mapOf(0 to AncsConversationRules.MESSAGES, 1 to "Sam", 2 to "Alex", 3 to "Group"), now)
        assertEquals(2, store.cardCount)
        assertEquals(setOf(false, true), store.snapshot().map { it.isGroupConversation }.toSet())
    }
    @Test fun genericMessagesTitlesAndUnknownAppsDoNotBecomeDirectChats() {
        for (title in listOf("", "Messages", "iMessage", "New Message", "3 new messages")) {
            assertNull(AncsConversationRules.match(mapOf(0 to AncsConversationRules.MESSAGES, 1 to title, 3 to "Notification")))
        }
        assertNull(AncsConversationRules.match(mapOf(0 to "unknown.app", 1 to "Alex", 3 to "Hello")))
        assertNull(AncsConversationRules.match(mapOf(0 to AncsConversationRules.MESSAGES, 1 to "Alex", 3 to "")))
    }
    @Test fun sameGroupNameAcrossAppsDoesNotMerge() {
        val store = AncsConversationStore()
        store.upsert(event(1), fields(channel = "#plans", server = "Friends"), now)
        store.upsert(event(2), mapOf(0 to AncsConversationRules.MESSAGES, 1 to "Alex", 2 to "#plans · Friends", 3 to "Test"), now)
        assertEquals(2, store.conversationCount)
    }
    @Test fun punctuationInsideLabelsIsNotASeparator() {
        val match = AncsConversationRules.match(fields(sender = "Alex (A, B)", channel = "#a,b", server = "S (x,y)"))!!
        assertEquals(listOf("S (x,y)", "#a,b"), match.context.labels)
        assertEquals("Alex (A, B)", match.sender)
    }
    @Test fun unrecognizedAndHiddenFormatsStaySeparate() {
        val store = AncsConversationStore()
        val hidden = fields() + (1 to "Discord") + (3 to "New message")
        store.upsert(event(1), hidden, now)
        store.upsert(event(2), hidden, now)
        store.upsert(event(3), fields() + (0 to "com.apple.MobileSMS") + (1 to "Messages"), now)
        assertEquals(3, store.cardCount)
        assertEquals(0, store.conversationCount)
        assertNull(AncsConversationRules.match(fields() + (3 to "")))
        assertNull(AncsConversationRules.match(fields() + (2 to "unrecognized context")))
    }
    @Test fun modifiedUidReplacesMessageAndRepeatedTextFromDifferentUidsSurvives() {
        val store = AncsConversationStore()
        store.upsert(event(1), fields(body = "Hi"), now)
        store.upsert(event(2), fields(body = "Hi"), now.plusSeconds(1))
        assertEquals(2, store.snapshot().single().messages.size)
        store.upsert(event(1, 1), fields(body = "Edited"), now.plusSeconds(2))
        assertEquals(listOf("Edited", "Hi"), store.snapshot().single().messages.map { it.text })
        assertTrue(store.upsert(event(1, 1), fields(body = "Edited"), now.plusSeconds(3)).updated.isEmpty())
    }
    @Test fun removeOneMessageUpdatesCardAndLastRemovalCancelsIt() {
        val store = AncsConversationStore()
        store.upsert(event(1), fields(), now)
        val card = store.upsert(event(2), fields(sender = "Sam"), now).updated.single()
        val removal = store.remove(1)
        assertEquals(listOf(2L), removal.updated.single().messages.map { it.uid })
        assertEquals(setOf(card.tag), store.remove(2).removed)
        assertEquals(0, store.cardCount)
    }
    @Test fun movingUidToDifferentConversationRemovesOldCard() {
        val store = AncsConversationStore()
        val old = store.upsert(event(1), fields(), now).updated.single()
        val moved = store.upsert(event(1, 1), fields(channel = "#other"), now)
        assertEquals(setOf(old.tag), moved.removed)
        assertEquals(1, store.cardCount)
    }
    @Test fun olderBacklogCannotEvictNewConversation() {
        val store = AncsConversationStore(maxCards = 2)
        store.upsert(event(1), fields() + (5 to "20261013T120000"), now)
        store.upsert(event(2), fields(channel = "#second") + (5 to "20261013T130000"), now)
        val old = store.upsert(event(3).copy(flags = 4), fields(channel = "#old") + (5 to "20260101T120000"), now)
        assertTrue(old.updated.isEmpty())
        assertTrue(old.removed.isEmpty())
        assertEquals(setOf("#plans · Friends", "#second · Friends"), store.snapshot().map { it.title }.toSet())
        // An undated historical event must not masquerade as a newly arrived message.
        assertTrue(store.upsert(event(4).copy(flags = 4), fields(channel = "#undated"), now).updated.isEmpty())
    }
    @Test fun messageAndCardBoundsAndSessionReset() {
        val store = AncsConversationStore(maxCards = 2, maxMessages = 2)
        for (uid in 1L..3L) store.upsert(event(uid), fields(), now.plusSeconds(uid))
        assertEquals(listOf(2L,3L), store.snapshot().single().messages.map { it.uid })
        val oldestTag = store.snapshot().single().tag
        store.upsert(event(4), fields(channel = "#second"), now.plusSeconds(4))
        assertEquals(setOf(oldestTag), store.upsert(event(5), fields(channel = "#third"), now.plusSeconds(5)).removed)
        assertEquals(2, store.cardCount)
        assertTrue(store.remove(1).updated.isEmpty())
        store.clear()
        assertTrue(store.snapshot().isEmpty())
    }
}
