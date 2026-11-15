package com.flint.peakfocus.feature.settings.applock

import org.junit.Assert.assertEquals
import org.junit.Test

/** The lockout schedule and its boot/elapsed-time semantics. */
class AppPinThrottleTest {

    @Test fun `escalation table starts after four free attempts`() {
        assertEquals(0L, AppPinThrottle.lockoutMillisAfter(0))
        assertEquals(0L, AppPinThrottle.lockoutMillisAfter(1))
        assertEquals(0L, AppPinThrottle.lockoutMillisAfter(2))
        assertEquals(0L, AppPinThrottle.lockoutMillisAfter(3))
        assertEquals(0L, AppPinThrottle.lockoutMillisAfter(4))
        assertEquals(30_000L, AppPinThrottle.lockoutMillisAfter(5))
        assertEquals(60_000L, AppPinThrottle.lockoutMillisAfter(6))
        assertEquals(120_000L, AppPinThrottle.lockoutMillisAfter(7))
        assertEquals(300_000L, AppPinThrottle.lockoutMillisAfter(8))
        assertEquals(900_000L, AppPinThrottle.lockoutMillisAfter(9))
        assertEquals(1_800_000L, AppPinThrottle.lockoutMillisAfter(10))
        assertEquals(3_600_000L, AppPinThrottle.lockoutMillisAfter(11))
        assertEquals(3_600_000L, AppPinThrottle.lockoutMillisAfter(25))
        assertEquals(3_600_000L, AppPinThrottle.lockoutMillisAfter(Int.MAX_VALUE))
    }

    @Test fun `no lockout means no wait`() {
        assertEquals(0L, AppPinThrottle.remainingMillis(ThrottleState(), nowElapsedMs = 5, bootId = "boot-a"))
        assertEquals(
            0L,
            AppPinThrottle.remainingMillis(
                ThrottleState(failures = 3, lockoutStartElapsedMs = 1_000, lockoutMillis = 0, lockoutBootId = "boot-a"),
                nowElapsedMs = 1_000,
                bootId = "boot-a",
            ),
        )
    }

    @Test fun `same boot counts down and clamps at zero`() {
        val state = ThrottleState(
            failures = 6,
            lockoutStartElapsedMs = 10_000,
            lockoutMillis = 60_000,
            lockoutBootId = "boot-a",
        )
        assertEquals(60_000L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 10_000, bootId = "boot-a"))
        assertEquals(45_000L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 25_000, bootId = "boot-a"))
        assertEquals(1L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 69_999, bootId = "boot-a"))
        assertEquals(0L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 70_000, bootId = "boot-a"))
        assertEquals(0L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 1_000_000, bootId = "boot-a"))
    }

    @Test fun `an elapsed clock that moves backwards keeps the full window`() {
        val state = ThrottleState(
            failures = 5,
            lockoutStartElapsedMs = 10_000,
            lockoutMillis = 30_000,
            lockoutBootId = "boot-a",
        )
        assertEquals(30_000L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 9_999, bootId = "boot-a"))
        assertEquals(30_000L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 0, bootId = "boot-a"))
    }

    @Test fun `a different boot restarts the full window`() {
        val state = ThrottleState(
            failures = 7,
            lockoutStartElapsedMs = 10_000,
            lockoutMillis = 120_000,
            lockoutBootId = "boot-a",
        )
        assertEquals(120_000L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 0, bootId = "boot-b"))
        assertEquals(120_000L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 500_000, bootId = "boot-b"))
        assertEquals(
            120_000L,
            AppPinThrottle.remainingMillis(state, nowElapsedMs = 0, bootId = "wall-12345"),
        )
    }

    @Test fun `an unknown boot id is treated as a different boot`() {
        val state = ThrottleState(
            failures = 5,
            lockoutStartElapsedMs = 10_000,
            lockoutMillis = 30_000,
            lockoutBootId = null,
        )
        assertEquals(30_000L, AppPinThrottle.remainingMillis(state, nowElapsedMs = 10_000, bootId = "boot-a"))
    }
}
