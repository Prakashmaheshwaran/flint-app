package com.flint.peakfocus.blocking.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyFloorGateTest {

    private val self = "com.flint.peakfocus"
    private var now = 1_000L
    private var resolveCalls = 0
    private var runtime: Set<String> = setOf("com.custom.dialer")
    private var failing = false

    private fun gate(refresh: Long = 30_000L, fresh: Long = 2_000L) = SafetyFloorGate(
        selfPackage = self,
        resolveRuntime = {
            resolveCalls++
            if (failing) error("binder died")
            runtime
        },
        nowElapsedMs = { now },
        refreshIntervalMs = refresh,
        freshIntervalMs = fresh,
    )

    @Test fun `static floor never needs the resolver`() {
        val g = gate()
        assertTrue(g.protects("com.android.dialer"))
        assertTrue(g.protects(self))
        assertTrue(g.protectsFresh("com.android.settings"))
        assertEquals(0, resolveCalls)
    }

    @Test fun `runtime surfaces are cached for the refresh interval`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        assertFalse(g.protects("com.instagram.android"))
        now += 29_000L
        assertTrue(g.protects("com.custom.dialer"))
        assertEquals(1, resolveCalls)
        now += 1_000L
        g.protects("com.instagram.android")
        assertEquals(2, resolveCalls)
    }

    @Test fun `a block decision re-resolves stale runtime data`() {
        val g = gate()
        assertFalse(g.protects("com.truecaller"))
        runtime = setOf("com.truecaller") // user just made Truecaller the default dialer
        now += 2_500L
        assertFalse("cached path still stale", g.protects("com.truecaller"))
        assertTrue("about-to-block path re-resolves", g.protectsFresh("com.truecaller"))
        assertEquals(2, resolveCalls)
    }

    @Test fun `fresh check within the fresh interval does not re-resolve`() {
        val g = gate()
        g.protects("x")
        now += 1_000L
        g.protectsFresh("x")
        assertEquals(1, resolveCalls)
    }

    @Test fun `resolver failure keeps the last known runtime set`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        failing = true
        now += 31_000L
        assertTrue(g.protects("com.custom.dialer"))
        assertTrue(g.protectsFresh("com.custom.dialer"))
        // Failure is stamped: not retried on every event.
        val calls = resolveCalls
        g.protects("com.custom.dialer")
        assertEquals(calls, resolveCalls)
    }

    @Test fun `elapsed clock going backwards forces a refresh`() {
        val g = gate()
        g.protects("x")
        now -= 10_000L
        g.protects("x")
        assertEquals(2, resolveCalls)
    }
}
