package com.flint.peakfocus.core.model

import org.junit.Assert.*
import org.junit.Test

class FallbackSurfaceHandoffTest {
    @Test fun `Path A connect with visible fallback dismisses once`() {
        val handoff = FallbackSurfaceHandoff<String>()
        handoff.started("fallback")
        handoff.started("fallback") // started and resumed callbacks refer to one surface
        assertEquals(listOf("fallback"), handoff.pathAChanged(true))
        assertTrue(handoff.pathAChanged(true).isEmpty())
    }

    @Test fun `disconnect never dismisses fallback`() {
        val handoff = FallbackSurfaceHandoff<String>()
        handoff.started("fallback")
        assertTrue(handoff.pathAChanged(false).isEmpty())
        assertTrue(handoff.pathAChanged(false).isEmpty())
    }

    @Test fun `no started BlockActivity is a no op`() {
        val handoff = FallbackSurfaceHandoff<String>()
        assertTrue(handoff.pathAChanged(true).isEmpty())
    }

    @Test fun `stopped or destroyed fallback is excluded from takeover`() {
        val handoff = FallbackSurfaceHandoff<String>()
        handoff.started("fallback")
        handoff.stopped("fallback")
        assertTrue(handoff.pathAChanged(true).isEmpty())
    }

    @Test fun `a fallback starting after Path A is live is dismissed immediately`() {
        val handoff = FallbackSurfaceHandoff<String>()
        handoff.pathAChanged(true)
        assertTrue(handoff.started("late fallback"))
        assertTrue(handoff.pathAChanged(true).isEmpty())
        handoff.pathAChanged(false)
        assertFalse(handoff.started("fallback"))
        assertEquals(listOf("fallback"), handoff.pathAChanged(true))
    }

    @Test fun `fresh lifecycle state can detect a primary connected before adapter tracking`() {
        assertTrue(FallbackSurfaceHandoff<String>().started("late fallback", primaryLive = true))
    }

    @Test fun `a late start does not consume the takeover of already visible fallbacks`() {
        val handoff = FallbackSurfaceHandoff<String>()
        handoff.started("existing")
        assertTrue(handoff.started("late", primaryLive = true))
        assertEquals(listOf("existing"), handoff.pathAChanged(true))
    }
}
