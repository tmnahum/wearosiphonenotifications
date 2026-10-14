package dev.tmn.watchtest.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant

class NotificationPresentationTest {
    private val fields = (0..5).associateWith { when (it) {
        0 -> "com.example.alerts"
        1 -> "Updates"
        3 -> "Synthetic update"
        5 -> "20261013T160000"
        else -> ""
    } }

    @Test fun popularAppDefaultsBeatCategoryButExplicitStylesWin() {
        assertEquals(DisplayStyle.MESSAGES, NotificationPresentation.resolve(DisplayStyle.AUTOMATIC, 4, "com.example.alerts"))
        for (app in listOf("com.apple.MobileSMS", "org.whispersystems.signal", "com.hammerandchisel.discord", "net.whatsapp.WhatsApp", "com.burbn.instagram")) {
            for (category in listOf(-1, 0, 4, 6, 255)) {
                assertEquals(DisplayStyle.MESSAGES, NotificationPresentation.resolve(DisplayStyle.AUTOMATIC, category, app))
                assertEquals(DisplayStyle.TEXT, NotificationPresentation.resolve(DisplayStyle.TEXT, category, app))
                assertEquals(DisplayStyle.INBOX, NotificationPresentation.resolve(DisplayStyle.INBOX, category, app))
            }
        }
        for (app in listOf("com.google.Gmail", "com.apple.mobilemail")) {
            assertEquals(DisplayStyle.INBOX, NotificationPresentation.resolve(DisplayStyle.AUTOMATIC, 0, app))
        }
        for (category in listOf(0, 1, 2, 3, 5, 7, 8, 9, 10, 11, 255)) {
            assertEquals(DisplayStyle.TEXT, NotificationPresentation.resolve(DisplayStyle.AUTOMATIC, category, "com.example.alerts"))
        }
        for (style in DisplayStyle.entries.filter { it != DisplayStyle.AUTOMATIC }) {
            for (category in listOf(-1, 0, 4, 6)) assertEquals(style, NotificationPresentation.resolve(style, category, "com.google.Gmail"))
        }
        assertEquals(DisplayStyle.INBOX, NotificationPresentation.resolve(DisplayStyle.AUTOMATIC, -1, "com.google.Gmail"))
        assertEquals(DisplayStyle.MESSAGES, NotificationPresentation.resolve(DisplayStyle.AUTOMATIC, -1, "org.whispersystems.signal"))
        assertEquals(DisplayStyle.TEXT, NotificationPresentation.resolve(DisplayStyle.AUTOMATIC, -1, "com.example.alerts"))
    }

    @Test fun legacyCatalogPreservesPreferencesAndDefaultsStyle() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x57414331); writeInt(1)
            writeUTF("com.example.alerts"); writeUTF("Alerts")
            writeUTF("SILENT"); writeUTF("APP"); writeLong(123)
        }
        val app = AppCatalogCodec.read(ByteArrayInputStream(bytes.toByteArray())).single()
        assertEquals(AppSettings(Delivery.SILENT, Grouping.APP, DisplayStyle.AUTOMATIC), app.settings)
        assertEquals(123L, app.lastSeen)
        val updated = app.copy(settings = app.settings.copy(displayStyle = DisplayStyle.TEXT))
        val saved = ByteArrayOutputStream(); AppCatalogCodec.write(saved, listOf(updated))
        assertEquals(updated, AppCatalogCodec.read(ByteArrayInputStream(saved.toByteArray())).single())
    }

    @Test fun legacyHistoryUpgradesWithoutDroppingMessagesOrDismissals() {
        val bytes = ByteArrayOutputStream()
        val hash = "a".repeat(64)
        DataOutputStream(bytes).apply {
            writeInt(0x57485231); writeInt(1); writeInt(1)
            writeLong(123); writeUTF("phone"); writeLong(1_000)
            for (key in 0..5) writeUTF(fields.getValue(key))
            writeInt(1); writeUTF(hash)
        }
        val old = HistoryCodec.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(-1, old.records.single().category)
        assertEquals(fields, old.records.single().fields)
        assertEquals(setOf(hash), old.dismissed)
        val output = ByteArrayOutputStream(); HistoryCodec.write(output, old.records, old.dismissed)
        assertEquals(old, HistoryCodec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun categorySurvivesSaveRestoreRegroupAndReplayAndStyleKeepsIdentity() {
        val catalog = AppCatalog()
        val history = NotificationHistory(catalog)
        history.beginSession("phone")
        val event = AncsProtocol.Event(0, 0, 6, 1, 9)
        history.upsert(event, fields, Instant.now())
        val originalTag = history.snapshot().single().tag
        val output = ByteArrayOutputStream(); HistoryCodec.write(output, history.saved())
        val restored = NotificationHistory(catalog)
        restored.restore(HistoryCodec.read(ByteArrayInputStream(output.toByteArray())).records)
        assertEquals(6, restored.snapshot().single().messages.single().category)
        catalog.settings(fields.getValue(0), AppSettings(displayStyle = DisplayStyle.TEXT))
        restored.regroup()
        assertEquals(originalTag, restored.snapshot().single().tag)
        assertEquals(6, restored.snapshot().single().messages.single().category)
        restored.beginSession("phone")
        restored.upsert(event.copy(uid = 20, flags = 4, category = 4), fields, Instant.now())
        assertEquals(1, restored.saved().size)
        assertEquals(4, restored.saved().single().category)
        assertEquals(4, restored.snapshot().single().messages.single().category)
    }

    @Test fun plainCombinedCardsRetainMultipleItemsAndSenders() {
        val messages = listOf(
            AncsCardMessage(1, "First alert", "One", Instant.EPOCH),
            AncsCardMessage(2, "Second alert", "Two", Instant.EPOCH))
        assertEquals("First alert: One\n\nSecond alert: Two", NotificationPresentation.expandedText(messages))
    }
}
