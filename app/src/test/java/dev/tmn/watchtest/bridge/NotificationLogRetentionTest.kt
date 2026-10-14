package dev.tmn.watchtest.bridge

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationLogRetentionTest {
    @Test fun retainsFourteenDaysAcrossMonthBoundary() {
        assertEquals(Instant.parse("2026-09-29T20:00:00Z").toEpochMilli(),
            NotificationLogRetention.cutoff(Instant.parse("2026-10-13T20:00:00Z").toEpochMilli()))
    }
}
