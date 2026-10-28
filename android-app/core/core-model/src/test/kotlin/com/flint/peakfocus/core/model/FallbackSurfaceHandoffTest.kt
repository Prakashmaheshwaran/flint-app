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
        handoff.pathAChanged(true)
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

    @Test fun `later lifecycle callback alone is not another Path A transition`() {
        val handoff = FallbackSurfaceHandoff<String>()
        handoff.pathAChanged(true)
        handoff.started("fallback")
        assertTrue(handoff.pathAChanged(true).isEmpty())
        handoff.pathAChanged(false)
        assertEquals(listOf("fallback"), handoff.pathAChanged(true))
    }
}
