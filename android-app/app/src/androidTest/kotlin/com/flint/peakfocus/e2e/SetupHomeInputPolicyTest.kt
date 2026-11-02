package com.flint.peakfocus.e2e

import org.junit.Assert.*
import org.junit.Test

class SetupHomeInputPolicyTest {
    @Test fun rejectedInputsBackOffAndStopAfterThreeAttempts() {
        val policy = SetupHomeInputPolicy()
        assertTrue(policy.shouldAttempt(0, 30_000, false))
        assertEquals(1_000L, policy.recordResult(false, 100))
        assertFalse(policy.shouldAttempt(1_099, 30_000, false))
        assertTrue(policy.shouldAttempt(1_100, 30_000, false))
        assertEquals(2_000L, policy.recordResult(false, 1_100))
        assertFalse(policy.shouldAttempt(3_099, 30_000, false))
        assertTrue(policy.shouldAttempt(3_100, 30_000, false))
        assertEquals(0L, policy.recordResult(false, 3_100))
        assertEquals(3, policy.attempts)
        assertFalse(policy.shouldAttempt(4_100, 30_000, false))
        policy.afterAnr()
        assertFalse(policy.shouldAttempt(5_100, 30_000, false))
        assertThrows(IllegalStateException::class.java) { policy.recordResult(false, 5_100) }
    }

    @Test fun observedLauncherSuppressesRetryEvenWhenInputReportedFailure() {
        val policy = SetupHomeInputPolicy()
        assertFalse(policy.shouldAttempt(0, 30_000, true))
        policy.recordResult(false, 0)
        assertFalse(policy.shouldAttempt(1_000, 30_000, true))
    }

    @Test fun acceptedInputIsNotRepeatedUnlessAnAnrInterruptsSetup() {
        val policy = SetupHomeInputPolicy()
        assertEquals(0L, policy.recordResult(true, 0))
        assertFalse(policy.shouldAttempt(1_000, 30_000, false))
        policy.afterAnr()
        assertEquals(1, policy.attempts)
        assertTrue(policy.shouldAttempt(1_000, 30_000, false))
        policy.recordResult(true, 1_000)
        assertFalse(policy.shouldAttempt(2_000, 30_000, false))
    }

    @Test fun deadlinePreventsInputIncludingWhenBackoffWouldOutlastIt() {
        val policy = SetupHomeInputPolicy()
        assertFalse(policy.shouldAttempt(30_000, 30_000, false))
        assertFalse(policy.shouldAttempt(30_001, 30_000, false))
        policy.recordResult(false, 29_500)
        assertFalse(policy.shouldAttempt(29_999, 30_000, false))
        assertFalse(policy.shouldAttempt(30_500, 30_000, false))
    }
}
