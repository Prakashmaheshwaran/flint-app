package com.flint.peakfocus.blocking.usagestats

import com.flint.peakfocus.core.model.ForegroundEvent
import com.flint.peakfocus.core.model.ForegroundUsage
import org.junit.Assert.*
import org.junit.Test

class ContinuousBudgetTest {
    @Test fun `minimum clock value is an ordinary first sample rather than a cache sentinel`() {
        var calls = 0
        val gate = PathBTimeLimitGate({ 1 }, { _, _ -> calls++; 0 }, dayFor = { 0 })
        assertFalse(gate.isSpent("app", Long.MIN_VALUE))
        assertFalse(gate.isSpent("app", Long.MIN_VALUE + 1))
        assertEquals(1, calls)
    }

    @Test fun `stationary open session is requeried exactly at exhaustion`() {
        val events = listOf(ForegroundEvent(0, ForegroundEvent.Kind.RESUMED, "app"))
        val queries = mutableListOf<Long>()
        val gate = PathBTimeLimitGate({ 1 }, { pkg, now ->
            queries += now
            ForegroundUsage.millis(pkg, events, 0, now)
        })
        assertFalse(gate.isSpent("app", 59_000))
        assertFalse(gate.isSpent("app", 59_999))
        assertTrue(gate.isSpent("app", 60_000))
        assertEquals(listOf(59_000L, 60_000L), queries)
    }

    @Test fun `changing a limit invalidates immediately inside query interval`() {
        var limit: Int? = null
        val gate = PathBTimeLimitGate({ limit }, { _, _ -> 60_000 })
        assertFalse(gate.isSpent("app", 0))
        limit = 1
        assertTrue(gate.isSpent("app", 1))
        limit = 2
        assertFalse(gate.isSpent("app", 2))
    }

    @Test fun `even a rewind after the cache timestamp invalidates`() {
        var calls = 0
        val gate = PathBTimeLimitGate({ 1 }, { _, _ -> calls++; 0 })
        gate.isSpent("app", 0)
        gate.isSpent("app", 5_000)
        gate.isSpent("app", 4_000)
        assertEquals(2, calls)
    }

    @Test fun `local day rollover clears a spent verdict before interval expiry`() {
        val gate = PathBTimeLimitGate({ 1 }, { _, now -> if (now < 100_000) 60_000 else 0 }, dayFor = { it / 100_000 })
        assertTrue(gate.isSpent("app", 99_999))
        assertFalse(gate.isSpent("app", 100_000))
    }

    @Test fun `explicit clock signal invalidates without a package switch`() {
        var used = 0L
        val gate = PathBTimeLimitGate({ 1 }, { _, _ -> used })
        assertFalse(gate.isSpent("app", 0))
        used = 60_000
        gate.invalidate()
        assertTrue(gate.isSpent("app", 1))
    }
}
