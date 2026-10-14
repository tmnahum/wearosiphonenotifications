package dev.tmn.watchtest.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.TimeZone

class AppFeaturesTest {
    private val now = Instant.parse("2026-10-13T20:00:00Z")
    private fun fields(app: String = "org.whispersystems.signal", title: String = "Weekend plans", body: String = "Alex: Hello") =
        mapOf(0 to app, 1 to title, 2 to "", 3 to body, 5 to "20261013T160000")
    private fun event(uid: Long) = AncsProtocol.Event(0, 0, 4, 1, uid)

    @Test fun backlogCannotEvictFreshEventsAtQueueCapacity() {
        val session = AncsSession(capacity = 2)
        session.enqueue(event(1)); session.enqueue(event(2))
        session.enqueue(event(3).copy(flags = 4))
        assertEquals(1L, session.next()!!.uid)
        session.discardCurrent()
        assertEquals(2L, session.next()!!.uid)
    }
    @Test fun appNameRequestEncodingAndEveryFragmentBoundary() {
        val id = "com.example.mail"
        val expected = byteArrayOf(1) + id.toByteArray() + byteArrayOf(0, 0)
        assertArrayEquals(expected, AncsAppAttributes(id).request())
        val text = "Example 📬".toByteArray()
        val response = expected + byteArrayOf(text.size.toByte(), 0) + text
        for (split in 0..response.size) {
            val request = AncsAppAttributes(id)
            request.append(response.copyOfRange(0, split))
            if (split < response.size) request.append(response.copyOfRange(split, response.size))
            assertFalse(request.complete)
            request.acknowledgeWrite()
            assertTrue(request.complete)
            assertEquals("Example 📬", request.name)
        }
    }
    @Test fun appNameWriteBeforeResponseAndMalformedResponses() {
        val request = AncsAppAttributes("x")
        request.acknowledgeWrite(); assertFalse(request.complete)
        request.append(byteArrayOf(1, 120, 0, 0, 0, 0))
        assertTrue(request.complete); assertEquals("", request.name)
        assertThrows(IllegalArgumentException::class.java) { AncsAppAttributes("x").append(byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { AncsAppAttributes("x").append(ByteArray(20_000)) }
        assertThrows(IllegalArgumentException::class.java) { AncsAppAttributes("bad\u0000id") }
    }
    @Test fun appPreferencesAndFetchedNameSurviveRoundTrip() {
        val catalog = AppCatalog()
        catalog.observe("com.google.Gmail", 42)
        catalog.settings("com.google.Gmail", AppSettings(Delivery.SILENT, Grouping.APP))
        catalog.name("com.google.Gmail", "Gmail")
        catalog.observe("com.google.Gmail", 99)
        val output = ByteArrayOutputStream()
        AppCatalogCodec.write(output, catalog.all())
        val restored = AppCatalog().apply { restore(AppCatalogCodec.read(ByteArrayInputStream(output.toByteArray()))) }
        assertEquals(catalog.all(), restored.all())
        assertEquals(AppSettings(Delivery.SILENT, Grouping.APP), restored.get("com.google.Gmail").settings)
        assertEquals("Gmail", restored.get("com.google.Gmail").name)
    }
    @Test fun signalVisibleGroupTitleCombinesDifferentSenderPrefixes() {
        val history = NotificationHistory().apply { beginSession("phone") }
        history.upsert(event(1), fields(), now)
        history.upsert(event(2), fields(body = "Sam: Another message"), now)
        val card = history.snapshot().single()
        assertTrue(card.isGroupConversation)
        assertEquals(listOf("Alex", "Sam"), card.messages.map { it.sender })
        assertEquals(listOf("Hello", "Another message"), card.messages.map { it.text })
    }
    @Test fun signalTitleAndSubtitleGroupVariants() {
        val a = AncsConversationRules.match(fields(title = "Alex in Weekend plans", body = "Hello"))!!
        val b = AncsConversationRules.match(fields(title = "Sam in Weekend plans", body = "Hi"))!!
        assertEquals(a.context, b.context)
        val subtitle = AncsConversationRules.match(fields(title = "Alex") + (2 to "Weekend plans"))!!
        assertEquals("Weekend plans", subtitle.title)
        assertNull(AncsConversationRules.match(fields(title = "Signal", body = "New message")))
    }
    @Test fun fallbackUsesBothLabelsAndNeverCrossesApps() {
        val history = NotificationHistory().apply { beginSession("phone") }
        val input = fields("com.example.social", "Alex", "Hello")
        history.upsert(event(1), input + (2 to "One group"), now)
        history.upsert(event(2), input + (2 to "One group"), now)
        history.upsert(event(3), input + (2 to "Other group"), now)
        history.upsert(event(4), input + (0 to "another.app") + (2 to "One group"), now)
        assertEquals(3, history.cardCount)
        assertEquals(2, history.snapshot().first().messages.size)
    }
    @Test fun genericFallbackStaysSeparate() {
        val history = NotificationHistory().apply { beginSession("phone") }
        history.upsert(event(1), fields("com.example.app", "New message", "Preview hidden"), now)
        history.upsert(event(2), fields("com.example.app", "New message", "Preview hidden"), now)
        assertEquals(2, history.cardCount)
    }
    @Test fun regroupingRetainsAllRecordsAndSessionUidEdits() {
        val catalog = AppCatalog()
        val history = NotificationHistory(catalog).apply { beginSession("phone") }
        history.upsert(event(1), fields("com.google.Gmail", "Alex", "One"), now)
        history.upsert(event(2), fields("com.google.Gmail", "Sam", "Two") + (2 to "Subject"), now)
        catalog.settings("com.google.Gmail", AppSettings(Delivery.MUTED, Grouping.APP))
        val changes = history.regroup()
        assertEquals(2, changes.removed.size)
        assertEquals(1, history.cardCount)
        assertEquals("Gmail", history.snapshot().single().title)
        assertEquals("Subject\nTwo", history.snapshot().single().messages.last().text)
        history.upsert(event(1).copy(eventId = 1), fields("com.google.Gmail", "Alex", "Edited"), now)
        assertEquals(2, history.saved().size)
        catalog.settings("com.google.Gmail", AppSettings(grouping = Grouping.SEPARATE))
        history.regroup()
        assertEquals(2, history.cardCount)
        assertTrue(history.snapshot().none { it.conversation })
    }
    @Test fun restoredTimestampsDoNotShiftWithWatchTimezone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val history = NotificationHistory().apply { beginSession("phone"); upsert(event(1), fields(), now) }
            val savedTime = history.snapshot().single().messages.single().timestamp
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val restored = NotificationHistory().apply { restore(history.saved()) }
            assertEquals(savedTime, restored.snapshot().single().messages.single().timestamp)
            restored.beginSession("phone")
            restored.upsert(event(2).copy(flags = 4), fields(), now)
            assertEquals(savedTime, restored.snapshot().single().messages.single().timestamp)
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun appCatalogRejectsTruncationAndPreservesDefaultPreferences() {
        val catalog = AppCatalog()
        catalog.observe("com.example.MailClient", 1)
        assertEquals("Mail Client", catalog.get("com.example.MailClient").name)
        assertEquals(AppSettings(), catalog.get("com.example.MailClient").settings)
        val output = ByteArrayOutputStream(); AppCatalogCodec.write(output, catalog.all())
        assertThrows(Exception::class.java) { AppCatalogCodec.read(ByteArrayInputStream(output.toByteArray().dropLast(1).toByteArray())) }
    }
}
