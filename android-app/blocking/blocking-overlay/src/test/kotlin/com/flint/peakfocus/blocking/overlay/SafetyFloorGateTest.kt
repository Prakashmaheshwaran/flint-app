package com.flint.peakfocus.blocking.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyFloorGateTest {

    private val self = "com.flint.peakfocus"
    private var now = 1_000L
    private var resolveCalls = 0
    private var sources: Map<String, Set<String>?> = mapOf(
        HOME to setOf("com.custom.home"),
        DIALER to setOf("com.custom.dialer"),
    )
    private var throwing = false
    private val queued = mutableListOf<() -> Unit>()

    private fun gate(background: Boolean = false, refresh: Long = 30_000L, fresh: Long = 2_000L) = SafetyFloorGate(
        selfPackage = self,
        resolveRuntime = {
            resolveCalls++
            if (throwing) error("binder died")
            sources
        },
        nowElapsedMs = { now },
        refreshInBackground = if (background) { task -> queued += task } else null,
        refreshIntervalMs = refresh,
        freshIntervalMs = fresh,
    )

    @Test fun `static floor never needs the resolver`() {
        val g = gate()
        assertTrue(g.protects("com.android.dialer"))
        assertTrue(g.protects(self))
        assertTrue(g.protectsFresh("com.android.settings", maxAgeMs = 0L))
        assertEquals(0, resolveCalls)
    }

    @Test fun `runtime surfaces are cached for the refresh interval`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        assertTrue(g.protects("com.custom.home"))
        assertFalse(g.protects("com.instagram.android"))
        now += 29_000L
        assertTrue(g.protects("com.custom.dialer"))
        assertEquals(1, resolveCalls)
        now += 1_000L
        g.protects("com.instagram.android")
        assertEquals(2, resolveCalls)
    }

    @Test fun `a block decision re-resolves stale runtime data and a mount forces it`() {
        val g = gate()
        assertFalse(g.protects("com.truecaller"))
        sources = sources + (DIALER to setOf("com.truecaller")) // just made Truecaller default
        now += 2_500L
        assertFalse("cached path is still stale", g.protects("com.truecaller"))
        assertTrue("about-to-block path re-resolves", g.protectsFresh("com.truecaller"))
        assertEquals(2, resolveCalls)
        sources = sources + (HOME to setOf("com.nova"))
        now += 10L
        assertFalse(g.protectsFresh("com.nova")) // inside the fresh window
        assertTrue("mount forces a resolve", g.protectsFresh("com.nova", maxAgeMs = 0L))
    }

    @Test fun `a failed source keeps its last packages while other sources update`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        sources = mapOf(HOME to setOf("com.new.home"), DIALER to null) // telecom lookup failed
        now += 31_000L
        assertTrue("dialer retained", g.protects("com.custom.dialer"))
        assertTrue("home updated", g.protects("com.new.home"))
        assertFalse("old home replaced", g.protects("com.custom.home"))
    }

    @Test fun `a source missing from a result is retained`() {
        val g = gate()
        g.protects("x")
        sources = mapOf(HOME to setOf("com.custom.home"))
        now += 31_000L
        g.protects("x")
        assertTrue(g.protects("com.custom.dialer"))
    }

    @Test fun `a throwing resolver keeps everything and is not retried on every event`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        throwing = true
        now += 31_000L
        assertTrue(g.protects("com.custom.dialer"))
        assertTrue(g.protectsFresh("com.custom.dialer"))
        val calls = resolveCalls
        g.protects("com.custom.dialer")
        assertEquals(calls, resolveCalls)
    }

    @Test fun `stale cached reads refresh in the background, one at a time`() {
        val g = gate(background = true)
        assertTrue("cold start resolves inline", g.protects("com.custom.dialer"))
        assertEquals(1, resolveCalls)
        sources = sources + (DIALER to setOf("com.truecaller"))
        now += 31_000L
        assertFalse("answers from cache, no inline binder call", g.protects("com.truecaller"))
        g.protects("com.truecaller")
        assertEquals(1, resolveCalls)
        assertEquals("only one refresh in flight", 1, queued.size)
        queued.removeAt(0).invoke()
        assertTrue(g.protects("com.truecaller"))
        assertEquals(2, resolveCalls)
    }

    @Test fun `a visible shield asks for a background refresh by age`() {
        val g = gate(background = true)
        g.protects("x")
        now += 4_000L
        g.refreshSoonIfOlderThan(5_000L)
        assertTrue(queued.isEmpty())
        now += 1_000L
        g.refreshSoonIfOlderThan(5_000L)
        assertEquals(1, queued.size)
    }

    @Test fun `elapsed clock going backwards forces a refresh`() {
        val g = gate()
        g.protects("x")
        now -= 10_000L
        g.protects("x")
        assertEquals(2, resolveCalls)
    }

    private companion object {
        const val HOME = "home"
        const val DIALER = "default-dialer"
    }
}
