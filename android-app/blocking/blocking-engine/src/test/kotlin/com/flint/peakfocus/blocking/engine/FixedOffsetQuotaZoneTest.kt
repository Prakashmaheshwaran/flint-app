package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FixedOffsetQuotaZoneTest {
    @Test fun canonicalFixedOffsetHasItsOwnMidnightAndNamedZonesStayUnchanged() {
        val at = AccessClockStamp(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), 1_000, "boot")
        assertEquals("GMT+05:30", OpenCreditLedgerPolicy.quotaZone("GMT+05:30").id)
        assertEquals(23_400_000L, OpenCreditLedgerPolicy.initial(at, "GMT+05:30").reset.remainingToResetMs)
        for (zone in listOf("UTC", "America/New_York", "Asia/Kolkata", "Etc/GMT-5"))
            assertEquals(zone, OpenCreditLedgerPolicy.quotaZone(zone).id)
    }

    @Test fun nonCanonicalOffsetsAndGarbageCannotBecomeQuotaZones() {
        for (zone in listOf("GMT+5:30", "+05:30", "UTC+05:30", "GMT+00:00", "GMT-00:00", "GMT+18:01", "invalid")) {
            try { OpenCreditLedgerPolicy.quotaZone(zone); fail(zone) } catch (_: IllegalArgumentException) { }
        }
    }
}
