package dev.tmn.watchtest.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

class TrayDismissalTest {
    private val now = Instant.parse("2026-10-13T20:00:00Z")
    private fun fields(body: String = "One", group: String = "Plans", date: String = "20261013T120000") =
        mapOf(0 to AncsConversationRules.MESSAGES, 1 to "Alex", 2 to group, 3 to body, 5 to date)
    private fun event(uid: Long, replay: Boolean = false) = AncsProtocol.Event(0, if (replay) 4 else 0, 4, 1, uid)
    private fun history() = NotificationHistory().apply { beginSession("phone") }
    private fun restored(h: NotificationHistory): NotificationHistory {
        val bytes = ByteArrayOutputStream()
        HistoryCodec.write(bytes, h.saved(), h.dismissed(), h.trayClearCutoffs())
        val saved = HistoryCodec.read(ByteArrayInputStream(bytes.toByteArray()))
        return NotificationHistory().apply { restore(saved.records, saved.dismissed, saved.trayClearedThrough); beginSession("phone") }
    }

    @Test fun swipeKeepsHistoryAndEditsCannotRepostIt() {
        val h = history(); h.upsert(event(1), fields(), now)
        h.dismissConversation(h.snapshot().single().tag)
        assertEquals(1, h.saved().size)
        assertEquals(1, h.snapshot().size)
        assertTrue(h.activeSnapshot().isEmpty())
        assertTrue(h.upsert(event(1).copy(eventId = 1), fields("Edited"), now).updated.isEmpty())
        assertEquals("Edited", h.snapshot().single().messages.single().text)
        assertTrue(h.regroup().updated.mapNotNull(h::notificationCard).isEmpty())
    }

    @Test fun restartReplayStaysHiddenAndOnlyFreshItemsEnterTray() {
        val h = history(); h.upsert(event(1), fields(), now)
        h.dismissConversation(h.snapshot().single().tag)
        val r = restored(h)
        assertTrue(r.upsert(event(20, true), fields(), now).updated.isEmpty())
        val fresh = r.upsert(event(21), fields("New"), now).updated.single()
        assertEquals(listOf("New"), fresh.messages.map { it.text })
        assertEquals(listOf("One", "New"), r.snapshot().single().messages.map { it.text })
        assertEquals(listOf("New"), r.activeSnapshot().single().messages.map { it.text })
    }

    @Test fun undatedDismissedReplayStaysHiddenButLiveRepeatIsNew() {
        val h = history(); h.upsert(event(1), fields(date = ""), now)
        h.dismissConversation(h.snapshot().single().tag)
        val r = restored(h)
        assertTrue(r.upsert(event(20, true), fields(date = ""), now).updated.isEmpty())
        assertEquals(1, r.saved().size)
        assertEquals(1, r.upsert(event(21), fields(date = ""), now).updated.single().messages.size)
        assertEquals(2, r.saved().size)
    }

    @Test fun lateDeleteIntentDoesNotDismissAnewMessage() {
        val h = history(); h.upsert(event(1), fields(), now)
        val old = h.snapshot().single()
        h.upsert(event(2), fields("New"), now)
        h.dismissConversation(old.tag, old.messages.maxOf { it.uid })
        assertEquals(listOf("New"), h.activeSnapshot().single().messages.map { it.text })
    }

    @Test fun bulkClearSuppressesQueuedBacklogAcrossRestartButNotNewOrOtherPhone() {
        val h = history(); h.upsert(event(1), fields(), now)
        h.dismissAllNotifications(now.toEpochMilli())
        val r = restored(h)
        assertTrue(r.upsert(event(20, true), fields("Queued old", "Previously unseen"), now).updated.isEmpty())
        assertTrue(r.upsert(event(21, true), fields("Undated backlog", "Another", ""), now).updated.isEmpty())
        assertEquals(3, r.saved().size)
        assertEquals(1, r.upsert(event(22), fields("Fresh", "Plans"), now).updated.size)
        r.beginSession("other-phone")
        assertEquals(1, r.upsert(event(23, true), fields(), now).updated.size)
    }

    @Test fun singleGroupClearSuppressesItsQueuedOlderItemsOnly() {
        val h = history(); h.upsert(event(1), fields(), now)
        h.dismissRecords(h.saved().map { it.id }.toSet(), now.toEpochMilli())
        assertTrue(h.upsert(event(2, true), fields("Queued item"), now).updated.isEmpty())
        assertEquals(1, h.upsert(event(3, true), fields("Other", "Other group"), now).updated.size)
    }

    @Test fun programmaticCancelIsNotUserDismissalAndDelayedCallbacksKeepNewPosts() {
        val tracker = NativeTrayTracker()
        tracker.posted("a", setOf(1)); tracker.posted("b", setOf(2))
        tracker.forget("a")
        assertTrue(tracker.reconcile(setOf("b")).records.isEmpty())
        tracker.posted("a", setOf(1, 3))
        tracker.forgetThrough("a", 1)
        val missing = tracker.reconcile(emptySet())
        assertTrue(missing.clearedTogether)
        assertEquals(setOf(1L, 2L, 3L), missing.records)
        assertTrue(tracker.isEmpty())
    }

    @Test fun asynchronouslyQueuedNativePostIsNotMistakenForADismissal() {
        val tracker = NativeTrayTracker()
        tracker.posted("a", setOf(1), 100)
        assertTrue(tracker.reconcile(emptySet(), 200).records.isEmpty())
        assertFalse(tracker.isEmpty())
        assertEquals(setOf(1L), tracker.reconcile(emptySet(), 1200).records)
    }

    @Test fun settingsResetAndRegroupNeverResurrectDismissedRecords() {
        val catalog = AppCatalog()
        val h = NotificationHistory(catalog).apply { beginSession("phone") }
        h.upsert(event(1), fields(), now)
        h.dismissConversation(h.snapshot().single().tag)
        val app = fields().getValue(0)
        catalog.settings(app, AppSettings(grouping = Grouping.APP, hidden = true))
        h.regroup()
        catalog.settings(app, AppSettings())
        h.regroup()
        assertTrue(h.activeSnapshot().isEmpty())
        assertEquals(1, h.saved().size)
    }

    @Test fun messagesPostedAfterBulkClearStillAppearOnReconnect() {
        val h = history(); h.upsert(event(1), fields(), now)
        h.dismissAllNotifications(now.toEpochMilli())
        val r = restored(h)
        assertEquals(1, r.upsert(event(2, true), fields("Later", date = "20261015T120000"), now.plusSeconds(172800)).updated.size)
    }
}
