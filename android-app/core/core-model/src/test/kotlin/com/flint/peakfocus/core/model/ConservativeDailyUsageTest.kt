package com.flint.peakfocus.core.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ConservativeDailyUsageTest {
    private val utc = ZoneId.of("UTC")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun `ongoing events and aggregate buckets independently supply the larger total`() {
        val usage = ConservativeDailyUsage()
        assertEquals(90_000L, usage.observe("app", 1_000, utc, 90_000, 60_000))
        assertEquals(120_000L, usage.observe("app", 2_000, utc, 90_000, 120_000))
    }

    @Test fun `truncated or failed event history cannot erase aggregate or observed usage`() {
        val usage = ConservativeDailyUsage()
        assertEquals(60_000L, usage.observe("app", 1_000, utc, 0, 60_000))
        assertEquals(90_000L, usage.observe("app", 2_000, utc, null, 90_000))
        assertEquals(90_000L, usage.observe("app", 3_000, utc, 0, 0))
        assertEquals(100_000L, usage.observe("app", 4_000, utc, 100_000, null))
    }

    @Test fun `two query failures fail closed but do not poison successful recovery`() {
        val usage = ConservativeDailyUsage()
        assertEquals(Long.MAX_VALUE, usage.observe("app", 1_000, utc, null, null))
        assertEquals(30_000L, usage.observe("app", 2_000, utc, 30_000, 0))
    }

    @Test fun `zone changes across dates preserve quota until the next midnight in the new zone`() {
        val usage = ConservativeDailyUsage()
        val now = at("2026-10-28T15:00:00Z")
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(600_000L, usage.observe("app", now, utc, 600_000, 0))
        assertEquals(600_000L, usage.observe("app", now, tokyo, 0, 0))
        assertEquals(600_000L, usage.observe("app", now + 23 * 3_600_000L, tokyo, 0, 0))
        assertEquals(0L, usage.observe("app", now + 24 * 3_600_000L, tokyo, 0, 0))
    }

    @Test fun `clock rewind and another packages measurement never lower a known total`() {
        val usage = ConservativeDailyUsage()
        usage.observe("app", 10_000, utc, 9_000, 0)
        assertEquals(1L, usage.observe("other", 5_000, utc, 1, 0))
        assertEquals(9_000L, usage.observe("app", 5_000, utc, 4_000, 0))
        assertEquals(0L, usage.observe("app", 86_400_000, utc, 0, 0))
    }
}
