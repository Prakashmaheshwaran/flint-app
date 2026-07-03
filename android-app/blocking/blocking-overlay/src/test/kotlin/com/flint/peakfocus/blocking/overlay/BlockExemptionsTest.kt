package com.flint.peakfocus.blocking.overlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests for the break/pass exemption ledger both detection paths consult. */
class BlockExemptionsTest {

    @Before
    fun setUp() = BlockExemptions.clearAll()

    @After
    fun tearDown() = BlockExemptions.clearAll()

    @Test
    fun `nothing is exempt by default`() {
        assertFalse(BlockExemptions.isExempt("com.x", nowEpochMs = 0L))
        assertNull(BlockExemptions.exemptUntil("com.x", nowEpochMs = 0L))
    }

    @Test
    fun `a grant exempts until - and not at - its end`() {
        BlockExemptions.grant("com.x", untilEpochMs = 1_000L)
        assertTrue(BlockExemptions.isExempt("com.x", nowEpochMs = 999L))
        assertFalse(BlockExemptions.isExempt("com.x", nowEpochMs = 1_000L)) // end is exclusive
        assertFalse(BlockExemptions.isExempt("com.x", nowEpochMs = 2_000L))
    }

    @Test
    fun `grants are per package`() {
        BlockExemptions.grant("com.x", untilEpochMs = 1_000L)
        assertFalse(BlockExemptions.isExempt("com.y", nowEpochMs = 0L))
    }

    @Test
    fun `the global grant covers every package`() {
        BlockExemptions.grant(BlockExemptions.GLOBAL_PACKAGE, untilEpochMs = 1_000L)
        assertTrue(BlockExemptions.isExempt("com.anything", nowEpochMs = 500L))
        assertFalse(BlockExemptions.isExempt("com.anything", nowEpochMs = 1_500L))
    }

    @Test
    fun `overlapping grants keep the later end`() {
        BlockExemptions.grant("com.x", untilEpochMs = 5_000L)
        BlockExemptions.grant("com.x", untilEpochMs = 2_000L) // shorter re-grant must not shrink it
        assertEquals(5_000L, BlockExemptions.exemptUntil("com.x", nowEpochMs = 0L))
        BlockExemptions.grant("com.x", untilEpochMs = 9_000L) // extending works
        assertEquals(9_000L, BlockExemptions.exemptUntil("com.x", nowEpochMs = 0L))
    }

    @Test
    fun `expired grants read as absent`() {
        BlockExemptions.grant("com.x", untilEpochMs = 1_000L)
        assertNull(BlockExemptions.exemptUntil("com.x", nowEpochMs = 1_000L))
        // And a fresh grant after expiry works normally.
        BlockExemptions.grant("com.x", untilEpochMs = 3_000L)
        assertTrue(BlockExemptions.isExempt("com.x", nowEpochMs = 2_000L))
    }
}
