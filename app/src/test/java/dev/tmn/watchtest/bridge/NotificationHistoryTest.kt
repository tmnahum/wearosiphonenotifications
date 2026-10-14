package dev.tmn.watchtest.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class NotificationHistoryTest {
    private val now = Instant.parse("2026-10-13T20:00:00Z")
    private fun event(uid: Long, replay: Boolean = false, modified: Boolean = false) =
        AncsProtocol.Event(if (modified) 1 else 0, if (replay) 4 else 0, 4, 1, uid)
    private fun fields(body: String = "Hello", date: String = "20261013T160000", sender: String = "Alex") =
        mapOf(0 to AncsConversationRules.MESSAGES, 1 to sender, 2 to "Plans", 3 to body, 5 to date)
    private fun history() = NotificationHistory().apply { beginSession("phone-A") }
    private fun roundTrip(history: NotificationHistory): NotificationHistory {
        val output = ByteArrayOutputStream()
        HistoryCodec.write(output, history.saved(), history.dismissed())
        val decoded = HistoryCodec.read(ByteArrayInputStream(output.toByteArray()))
        return NotificationHistory().apply { restore(decoded.records, decoded.dismissed); beginSession("phone-A") }
    }
    @Test fun retainsMoreThanTwentyFiveMessagesAndThirtyTwoChats() {
        val history = history()
        for (uid in 1L..60L) history.upsert(event(uid), fields(body = "Message $uid"), now)
        assertEquals(60, history.snapshot().single().messages.size)
        for (uid in 61L..100L) history.upsert(event(uid), fields() + (2 to "Group $uid"), now)
        assertEquals(41, history.cardCount)
        assertEquals(100, roundTrip(history).saved().size)
    }
    @Test fun reconnectReusesHistoryAndFillsGapWithNewRemoteIds() {
        val history = history()
        history.upsert(event(1), fields(), now)
        val tag = history.snapshot().single().tag
        history.endSession()
        history.beginSession("phone-A")
        history.upsert(event(99, replay = true), fields(), now.plusSeconds(60))
        history.upsert(event(100, replay = true), fields("Gap message", "20261013T160030"), now.plusSeconds(60))
        assertEquals(listOf("Hello", "Gap message"), history.snapshot().single().messages.map { it.text })
        assertEquals(tag, history.snapshot().single().tag)
    }
    @Test fun identicalOccurrencesMatchOneForOneAndLiveRepeatsSurvive() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.upsert(event(2), fields(), now)
        val restored = roundTrip(history)
        restored.upsert(event(30, true), fields(), now)
        restored.upsert(event(31, true), fields(), now)
        assertEquals(2, restored.saved().size)
        restored.upsert(event(32, true), fields(), now)
        restored.upsert(event(33), fields(), now)
        assertEquals(4, restored.saved().size)
    }
    @Test fun sameUidInNewSessionDoesNotOverwriteDifferentMessage() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.beginSession("phone-A")
        history.upsert(event(1), fields("New"), now)
        assertEquals(2, history.saved().size)
    }
    @Test fun editsUseSessionMappingAfterReplay() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.beginSession("phone-A")
        history.upsert(event(8, true), fields(), now)
        history.upsert(event(8, modified = true), fields("Edited"), now)
        assertEquals(listOf("Edited"), history.snapshot().single().messages.map { it.text })
    }
    @Test fun missingOrInvalidDatesAreNotGuessedAcrossReconnects() {
        val history = history()
        history.upsert(event(1), fields(date = ""), now)
        history.beginSession("phone-A")
        history.upsert(event(2, true), fields(date = ""), now)
        assertEquals(2, history.saved().size)
    }
    @Test fun phonesRemainSeparateEvenWithIdenticalContentAndDates() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.beginSession("phone-B")
        history.upsert(event(1, true), fields(), now)
        assertEquals(2, history.cardCount)
        assertEquals(2, history.saved().size)
    }
    @Test fun phoneDismissalCancelsLastActiveCardButRetainsHistory() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.upsert(event(2), fields("Second"), now)
        assertTrue(history.remove(1).removed.isEmpty())
        assertEquals(setOf(history.snapshot().single().tag), history.remove(2).removed)
        assertEquals(2, history.saved().size)
    }
    @Test fun explicitConversationDeletionSuppressesReplayAcrossRestart() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.clearConversation(history.snapshot().single().tag)
        assertTrue(history.snapshot().isEmpty())
        // An edit to a locally dismissed notification must not resurrect it this session.
        history.upsert(event(1, modified = true), fields("Edited"), now)
        assertTrue(history.snapshot().isEmpty())
        val restored = roundTrip(history)
        restored.upsert(event(50, true), fields(), now)
        assertTrue(restored.snapshot().isEmpty())
        restored.upsert(event(51), fields("Fresh message"), now)
        assertEquals(listOf("Fresh message"), restored.snapshot().single().messages.map { it.text })
    }
    @Test fun clearOneConversationDoesNotAffectAnotherAndTagsSurviveRestore() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.upsert(event(2), fields() + (2 to "Other"), now)
        val other = history.snapshot().first { it.title == "Other" }
        history.clearConversation(history.snapshot().first { it.title == "Plans" }.tag)
        assertEquals(other, roundTrip(history).snapshot().single())
    }
    @Test fun clearAllSuppressesOldBacklogButAcceptsNewMessages() {
        val history = history()
        history.upsert(event(1), fields(), now)
        history.upsert(event(2), fields() + (2 to "Other"), now)
        history.clear()
        assertTrue(history.saved().isEmpty())
        val restored = roundTrip(history)
        restored.upsert(event(50, true), fields(), now)
        restored.upsert(event(51, true), fields() + (2 to "Other"), now)
        assertTrue(restored.snapshot().isEmpty())
        restored.upsert(event(52), fields("New"), now)
        assertEquals(1, restored.saved().size)
    }
    @Test fun undatedTimestampSurvivesDiskRoundTrip() {
        val history = history()
        history.upsert(event(1), fields(date = ""), now)
        assertEquals(now, roundTrip(history).snapshot().single().messages.single().timestamp)
    }
    @Test fun unicodeAndEmptyFieldsRoundTrip() {
        val history = history()
        history.upsert(event(1), fields("Hello 🌙\nمرحبا", sender = "Zoë"), now)
        assertEquals(history.snapshot(), roundTrip(history).snapshot())
    }
    @Test fun malformedAndTruncatedFilesAreRejected() {
        val history = history()
        history.upsert(event(1), fields(), now)
        val output = ByteArrayOutputStream()
        HistoryCodec.write(output, history.saved())
        val bytes = output.toByteArray()
        for (length in listOf(0, 4, 8, bytes.size - 1)) {
            assertThrows(Exception::class.java) { HistoryCodec.read(ByteArrayInputStream(bytes.copyOf(length))) }
        }
        bytes[0] = 0
        assertThrows(Exception::class.java) { HistoryCodec.read(ByteArrayInputStream(bytes)) }
    }
}
