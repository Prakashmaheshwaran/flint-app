package com.flint.peakfocus.blocking.usagestats

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.blocking.overlay.TrustedTimeLimitBudget
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.DailyUsageTimeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class BudgetGateFixture(
        var limitMinutes: Int? = null, var foregroundMillis: Long = 0L,
        private val wallStart: Long = 1_704_067_200_000L,
    ) {
        var limitQueries = 0
        var usageQueries = 0
        var now = AccessClockStamp(wallStart, 0L, "boot")
        private val days = mutableMapOf<String, TimeLimitDayState>()

        fun gate(requeryIntervalMs: Long = 15_000L) = PathBTimeLimitGate(
            limitMinutesFor = { limitQueries++; limitMinutes },
            readBudget = { pkg, minutes ->
                val old = days[pkg] ?: TimeLimitDayAccounting.initial(now, "UTC")
                val sample = if (minutes == 0) null else {
                    usageQueries++
                    TimeLimitUsageSample(DailyUsageTimeline.startOfDay(now.wallEpochMs, java.time.ZoneId.of("UTC")), foregroundMillis, now)
                }
                val day = TimeLimitDayAccounting.record(old, old.periodId, sample, now).state
                days[pkg] = day
                TrustedTimeLimitBudget(pkg, minutes, day, TimeLimitDayAccounting.remainingMs(day, minutes),
                    TrustedTimeLimitBudget.Measurement.OBSERVED)
            },
            requeryIntervalMs = requeryIntervalMs,
        )

        suspend fun spent(gate: PathBTimeLimitGate, at: Long, pkg: String = "com.app",
            wall: Long = wallStart + at, boot: String = "boot"): Boolean {
            now = AccessClockStamp(wall, at, boot)
            return gate.budget(pkg, now)?.spent == true
        }
    }

class PathBTimeLimitGateTest {
    @Test
    fun `no limit avoids a usage query`() = runBlocking {
        val stores = BudgetGateFixture(limitMinutes = null, foregroundMillis = Long.MAX_VALUE)
        assertFalse(stores.spent(stores.gate(), 0L))
        assertEquals(0, stores.usageQueries)
    }

    @Test
    fun `budget boundary is spent`() = runBlocking {
        val stores = BudgetGateFixture(limitMinutes = 30, foregroundMillis = 30 * 60_000L)
        assertTrue(stores.spent(stores.gate(), 0L))
    }

    @Test
    fun `under budget is allowed`() = runBlocking {
        val stores = BudgetGateFixture(limitMinutes = 30, foregroundMillis = 30 * 60_000L - 1)
        assertFalse(stores.spent(stores.gate(), 0L))
    }

    @Test
    fun `one-second polling reuses the cache until its boundary`() = runBlocking {
        val stores = BudgetGateFixture(limitMinutes = 30)
        val gate = stores.gate()
        (0L until 15_000L step 1_000L).forEach { assertFalse(stores.spent(gate, it)) }
        assertEquals(1, stores.usageQueries)
        stores.foregroundMillis = 31 * 60_000L
        assertTrue(stores.spent(gate, 15_000L))
        assertEquals(2, stores.usageQueries)
    }

    @Test
    fun `package changes requery immediately`() = runBlocking {
        val stores = BudgetGateFixture(limitMinutes = 30, foregroundMillis = 31 * 60_000L)
        val gate = stores.gate()
        assertTrue(stores.spent(gate, 0L, "com.a"))
        stores.limitMinutes = null
        assertFalse(stores.spent(gate, 1L, "com.b"))
        assertEquals(2, stores.limitQueries)
    }

    @Test
    fun `backwards clock movement drops the cache`() = runBlocking {
        val stores = BudgetGateFixture(limitMinutes = 30)
        val gate = stores.gate()
        assertFalse(stores.spent(gate, 100_000L))
        stores.foregroundMillis = 31 * 60_000L
        assertTrue(stores.spent(gate, 101_000L, wall = stores.now.wallEpochMs - 50_000L))
        assertEquals(2, stores.usageQueries)
    }
}
