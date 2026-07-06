package com.flint.peakfocus.blocking.usagestats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM: the gate's dependencies are injected functions, so the decision + caching policy
 * — the part that turns [DailyLimitTracker] into actual Path B enforcement — tests without
 * Android.
 */
class PathBTimeLimitGateTest {

    private class CountingStores(
        var limitMinutes: Int? = null,
        var foregroundMillis: Long = 0L,
    ) {
        var limitQueries = 0
        var usageQueries = 0

        fun gate(requeryIntervalMs: Long = 15_000L) = PathBTimeLimitGate(
            limitMinutesFor = { limitQueries++; limitMinutes },
            foregroundMillisToday = { _, _ -> usageQueries++; foregroundMillis },
            requeryIntervalMs = requeryIntervalMs,
        )
    }

    @Test
    fun `no limit configured means never spent`() {
        val stores = CountingStores(limitMinutes = null, foregroundMillis = Long.MAX_VALUE)
        assertFalse(stores.gate().isSpent("com.app", nowEpochMs = 0L))
    }

    @Test
    fun `spent exactly at the budget boundary`() {
        val stores = CountingStores(limitMinutes = 30, foregroundMillis = 30 * 60_000L)
        assertTrue(stores.gate().isSpent("com.app", nowEpochMs = 0L))
    }

    @Test
    fun `under budget is not spent`() {
        val stores = CountingStores(limitMinutes = 30, foregroundMillis = 30 * 60_000L - 1)
        assertFalse(stores.gate().isSpent("com.app", nowEpochMs = 0L))
    }

    @Test
    fun `caches within the requery interval - a 1s poll loop must not hammer UsageStats`() {
        val stores = CountingStores(limitMinutes = 30, foregroundMillis = 0L)
        val gate = stores.gate(requeryIntervalMs = 15_000L)
        (0L until 15_000L step 1_000L).forEach { now ->
            assertFalse(gate.isSpent("com.app", now))
        }
        assertEquals(1, stores.usageQueries)

        // Budget fills up meanwhile; the interval boundary re-queries and sees it.
        stores.foregroundMillis = 31 * 60_000L
        assertTrue(gate.isSpent("com.app", 15_000L))
        assertEquals(2, stores.usageQueries)
    }

    @Test
    fun `package change re-queries immediately`() {
        val stores = CountingStores(limitMinutes = 30, foregroundMillis = 31 * 60_000L)
        val gate = stores.gate()
        assertTrue(gate.isSpent("com.a", 0L))
        stores.limitMinutes = null
        assertFalse(gate.isSpent("com.b", 1L)) // fresh verdict for the new package, not com.a's
        assertEquals(2, stores.limitQueries)
        assertEquals(1, stores.usageQueries) // com.b has no limit → usage never consulted
    }

    @Test
    fun `clock moving backwards drops the cache window`() {
        val stores = CountingStores(limitMinutes = 30, foregroundMillis = 0L)
        val gate = stores.gate()
        assertFalse(gate.isSpent("com.app", 100_000L))
        stores.foregroundMillis = 31 * 60_000L
        // now < cachedAt: the cache window can't be trusted — re-query rather than serve stale.
        assertTrue(gate.isSpent("com.app", 50_000L))
        assertEquals(2, stores.usageQueries)
    }
}
