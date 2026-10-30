package com.flint.peakfocus.blocking.usagestats

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContinuousBudgetTest {
    @Test fun `zero budget is spent without querying usage`() = runBlocking {
        val f = BudgetGateFixture(0)
        val gate = f.gate()
        assertTrue(f.spent(gate, 0))
        f.limitMinutes = null
        assertFalse(f.spent(gate, 1))
        assertEquals(0, f.usageQueries)
    }

    @Test fun `zero elapsed time is an ordinary first sample rather than a cache sentinel`() = runBlocking {
        val f = BudgetGateFixture(1)
        val gate = f.gate()
        assertFalse(f.spent(gate, 0))
        assertFalse(f.spent(gate, 1))
        assertEquals(1, f.usageQueries)
    }

    @Test fun `stationary open session is requeried exactly at exhaustion`() = runBlocking {
        val f = BudgetGateFixture(1, 59_000)
        val gate = f.gate()
        assertFalse(f.spent(gate, 59_000))
        f.foregroundMillis = 59_999
        assertFalse(f.spent(gate, 59_999))
        f.foregroundMillis = 60_000
        assertTrue(f.spent(gate, 60_000))
        assertEquals(2, f.usageQueries)
    }

    @Test fun `changing a limit invalidates immediately inside query interval`() = runBlocking {
        val f = BudgetGateFixture(null, 60_000)
        val gate = f.gate()
        assertFalse(f.spent(gate, 0))
        f.limitMinutes = 1
        assertTrue(f.spent(gate, 1))
        f.limitMinutes = 2
        assertFalse(f.spent(gate, 2))
    }

    @Test fun `boot change invalidates cached allow even before the query interval`() = runBlocking {
        val f = BudgetGateFixture(1)
        val gate = f.gate()
        f.spent(gate, 0)
        f.spent(gate, 5_000)
        f.spent(gate, 1, boot = "new-boot")
        assertEquals(2, f.usageQueries)
    }

    @Test fun `raw day jump remeasures but cannot clear an exhausted trusted period`() = runBlocking {
        val f = BudgetGateFixture(1, 60_000)
        val gate = f.gate()
        assertTrue(f.spent(gate, 0))
        f.foregroundMillis = 0
        assertTrue(f.spent(gate, 1_000, wall = f.now.wallEpochMs + 86_400_000))
        assertEquals(2, f.usageQueries)
    }

    @Test fun `explicit clock signal invalidates without a package switch`() = runBlocking {
        val f = BudgetGateFixture(1)
        val gate = f.gate()
        assertFalse(f.spent(gate, 0))
        f.foregroundMillis = 60_000
        gate.invalidate()
        assertTrue(f.spent(gate, 1))
    }
}
