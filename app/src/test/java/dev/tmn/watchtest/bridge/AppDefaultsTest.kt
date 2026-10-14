package dev.tmn.watchtest.bridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AppDefaultsTest {
    @Test fun mailCombinesByDefaultAndGroupingOverridesWin() {
        for ((app, category) in listOf("com.google.Gmail" to 0, "com.apple.mobilemail" to -1, "com.example.email" to 6)) {
            val a = mapOf(0 to app, 1 to "Alex", 2 to "First subject", 3 to "Hello")
            val b = a + (1 to "Sam") + (2 to "Other subject")
            val first = AncsConversationRules.resolve(a, AppSettings(), "Mail", category)!!
            val second = AncsConversationRules.resolve(b, AppSettings(), "Mail", category)!!
            assertEquals(first.context, second.context)
            assertNull(AncsConversationRules.resolve(a, AppSettings(grouping = Grouping.SEPARATE), "Mail", category))
            assertNotEquals(first.context, AncsConversationRules.resolve(a, AppSettings(grouping = Grouping.CONVERSATION), "Mail", category)!!.context)
        }
    }

    @Test fun oldMutedMigratesToHiddenAndSilentRemainsIndependent() {
        for (legacy in listOf("MUTED", "SILENT", "NORMAL")) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeInt(0x57414332); writeInt(1)
                writeUTF("com.google.Gmail"); writeUTF("Gmail"); writeUTF(legacy)
                writeUTF("SEPARATE"); writeLong(1); writeUTF("TEXT")
            }
            val app = AppCatalogCodec.read(ByteArrayInputStream(bytes.toByteArray())).single()
            assertEquals(legacy == "MUTED", app.settings.hidden)
            assertEquals(if (legacy == "SILENT") Delivery.SILENT else Delivery.NORMAL, app.settings.delivery)
            assertEquals(Grouping.SEPARATE, app.settings.grouping)
            assertEquals(DisplayStyle.TEXT, app.settings.displayStyle)
            val hiddenSilent = app.copy(settings = app.settings.copy(hidden = true, delivery = Delivery.SILENT))
            val output = ByteArrayOutputStream(); AppCatalogCodec.write(output, listOf(hiddenSilent))
            assertEquals(hiddenSilent, AppCatalogCodec.read(ByteArrayInputStream(output.toByteArray())).single())
        }
    }

    @Test fun resettingSettingsPreservesIdentityAndRestoresAutomaticMailPolicy() {
        val catalog = AppCatalog()
        catalog.observe("com.google.Gmail", 123); catalog.name("com.google.Gmail", "Gmail")
        catalog.settings("com.google.Gmail", AppSettings(Delivery.SILENT, Grouping.SEPARATE, DisplayStyle.TEXT, true))
        catalog.settings("com.google.Gmail", AppSettings())
        assertEquals(SourceApp("com.google.Gmail", "Gmail", AppSettings(), 123), catalog.get("com.google.Gmail"))
    }
}
