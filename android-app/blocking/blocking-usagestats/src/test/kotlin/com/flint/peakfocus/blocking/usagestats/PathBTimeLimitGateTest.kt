package com.flint.peakfocus.blocking.usagestats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathBTimeLimitGateTest {
    private class Stores(
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
    fun `no limit avoids a usage query`() {
        val stores = Stores(limitMinutes = null, foregroundMillis = Long.MAX_VALUE)
        assertFalse(stores.gate().isSpent("com.app", 0L))
        assertEquals(0, stores.usageQueries)
    }

    @Test
    fun `budget boundary is spent`() {
        val stores = Stores(limitMinutes = 30, foregroundMillis = 30 * 60_000L)
        assertTrue(stores.gate().isSpent("com.app", 0L))
    }

    @Test
    fun `under budget is allowed`() {
        val stores = Stores(limitMinutes = 30, foregroundMillis = 30 * 60_000L - 1)
        assertFalse(stores.gate().isSpent("com.app", 0L))
    }

    @Test
    fun `one-second polling reuses the cache until its boundary`() {
        val stores = Stores(limitMinutes = 30)
        val gate = stores.gate()
        (0L until 15_000L step 1_000L).forEach { assertFalse(gate.isSpent("com.app", it)) }
        assertEquals(1, stores.usageQueries)
        stores.foregroundMillis = 31 * 60_000L
        assertTrue(gate.isSpent("com.app", 15_000L))
        assertEquals(2, stores.usageQueries)
    }

    @Test
    fun `package changes requery immediately`() {
        val stores = Stores(limitMinutes = 30, foregroundMillis = 31 * 60_000L)
        val gate = stores.gate()
        assertTrue(gate.isSpent("com.a", 0L))
        stores.limitMinutes = null
        assertFalse(gate.isSpent("com.b", 1L))
        assertEquals(2, stores.limitQueries)
    }

    @Test
    fun `backwards clock movement drops the cache`() {
        val stores = Stores(limitMinutes = 30)
        val gate = stores.gate()
        assertFalse(gate.isSpent("com.app", 100_000L))
        stores.foregroundMillis = 31 * 60_000L
        assertTrue(gate.isSpent("com.app", 50_000L))
        assertEquals(2, stores.usageQueries)
    }
}
