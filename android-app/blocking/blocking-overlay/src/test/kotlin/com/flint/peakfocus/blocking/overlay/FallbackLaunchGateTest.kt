package com.flint.peakfocus.blocking.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackLaunchGateTest {

    @Test fun `first block of a package always launches`() {
        assertTrue(fallbackLaunchDue(null, 1_000L))
    }

    @Test fun `repeat tick inside the window is suppressed`() {
        assertFalse(fallbackLaunchDue(1_000L, 1_001L))
        assertFalse(fallbackLaunchDue(1_000L, 1_000L + BlockScreenCoordinator.FALLBACK_ACTIVITY_RETRY_WINDOW_MS - 1))
    }

    @Test fun `next window retries`() {
        assertTrue(fallbackLaunchDue(1_000L, 1_000L + BlockScreenCoordinator.FALLBACK_ACTIVITY_RETRY_WINDOW_MS))
    }
}
