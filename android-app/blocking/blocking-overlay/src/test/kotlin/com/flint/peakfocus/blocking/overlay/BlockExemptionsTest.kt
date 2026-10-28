package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.PersistedExemption
import com.flint.peakfocus.core.model.ExemptionKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests for the break/pass exemption ledger both detection paths consult. */
class BlockExemptionsTest {

    @Test fun delayedOlderReadCannotEraseANewerAcknowledgedGrant() {
        val old = PersistedExemption("old", "com.old", "cause", ExemptionKind.BREAK, 2_000)
        val granted = PersistedExemption("new", "com.new", "cause", ExemptionKind.EMERGENCY_PASS, 5_000)
        BlockExemptions.restoreCommitted(listOf(old, granted), 1_000, revision = 2)
        BlockExemptions.restoreCommitted(listOf(old), 1_000, revision = 1)
        assertTrue(BlockExemptions.isExempt("com.new", 1_000, BreakLevel.HARDCORE))
        assertEquals(5_000L, BlockExemptions.exemptUntil("com.new", 1_000))
        BlockExemptions.restoreCommitted(emptyList(), 1_000, revision = 3)
        assertFalse(BlockExemptions.isExempt("com.new", 1_000, BreakLevel.HARDCORE))
    }

    @Test fun restoredReceiptsRespectPackageKindAndExactDeadline() {
        val ordinary = PersistedExemption("one", "com.x", "rule:one", ExemptionKind.BREAK, 2_000)
        val pass = PersistedExemption("two", "com.y", "rule:two", ExemptionKind.EMERGENCY_PASS, 3_000)
        BlockExemptions.restoreCommitted(listOf(ordinary, pass), 1_000, revision = 0)
        assertTrue(BlockExemptions.isExempt("com.x", 1_999, BreakLevel.HARDER))
        assertFalse(BlockExemptions.isExempt("com.x", 1_999, BreakLevel.HARDCORE))
        assertFalse(BlockExemptions.isExempt("com.x", 2_000))
        assertTrue(BlockExemptions.isExempt("com.y", 2_999, BreakLevel.HARDCORE))
        assertFalse(BlockExemptions.isExempt("com.y", 3_000, BreakLevel.HARDCORE))
        assertFalse(BlockExemptions.isExempt("com.other", 1_000))
    }

    @Test fun globalReceiptsRestoreAndExpiredReceiptsStayInactive() {
        BlockExemptions.restoreCommitted(listOf(
            PersistedExemption("one", "*", "cause", ExemptionKind.EMERGENCY_PASS, 2_000),
            PersistedExemption("old", "com.old", "cause", ExemptionKind.BREAK, 999),
        ), 1_000, revision = 0)
        assertTrue(BlockExemptions.isExempt("com.any", 1_999, BreakLevel.HARDCORE))
        assertEquals(2_000L, BlockExemptions.exemptUntil("com.any", 1_000))
        assertFalse(BlockExemptions.isExempt("com.any", 2_000))
        assertNull(BlockExemptions.exemptUntil("com.old", 2_000))
    }

    @Test fun replacementDropsRemovedReceiptsAndKeepsActiveOverlaps() {
        val one = PersistedExemption("one", "com.x", "first", ExemptionKind.OPEN_LIMIT, 2_000)
        val two = one.copy(requestId = "two", causeKey = "second", untilEpochMs = 4_000)
        BlockExemptions.restoreCommitted(listOf(one, two), 1_000, revision = 0)
        assertEquals(4_000L, BlockExemptions.exemptUntil("com.x", 1_000))
        BlockExemptions.restoreCommitted(listOf(one), 1_000, revision = 1)
        assertEquals(2_000L, BlockExemptions.exemptUntil("com.x", 1_000))
        BlockExemptions.restoreCommitted(emptyList(), 1_000, revision = 2)
        assertFalse(BlockExemptions.isExempt("com.x", 1_000))
    }

    @Test fun processProjectionCanBeRebuiltWithoutConsumingAnotherCredit() {
        val saved = listOf(PersistedExemption("id", "com.x", "cause", ExemptionKind.EMERGENCY_PASS, 9_000))
        BlockExemptions.restoreCommitted(saved, 1_000, revision = 4)
        BlockExemptions.clearAll()
        assertFalse(BlockExemptions.isExempt("com.x", 2_000))
        BlockExemptions.restoreCommitted(saved, 2_000, revision = 4)
        assertTrue(BlockExemptions.isExempt("com.x", 2_000, BreakLevel.HARDCORE))
        assertFalse(BlockExemptions.isExempt("com.x", 9_000, BreakLevel.HARDCORE))
    }

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

    @Test
    fun `ordinary global grant cannot stand down hardcore`() {
        BlockExemptions.grant(BlockExemptions.GLOBAL_PACKAGE, 2_000L)
        assertTrue(BlockExemptions.isExempt("com.x", 1_000L, BreakLevel.HARDER))
        assertFalse(BlockExemptions.isExempt("com.x", 1_000L, BreakLevel.HARDCORE))
    }

    @Test
    fun `emergency capability expires independently of overlapping ordinary access`() {
        BlockExemptions.grant("com.x", 5_000L)
        BlockExemptions.grantEmergency("com.x", 2_000L)
        assertTrue(BlockExemptions.isExempt("com.x", 1_999L, BreakLevel.HARDCORE))
        assertFalse(BlockExemptions.isExempt("com.x", 2_000L, BreakLevel.HARDCORE))
        assertTrue(BlockExemptions.isExempt("com.x", 2_000L, BreakLevel.EASY))
        assertFalse(BlockExemptions.isExempt("com.y", 1_999L, BreakLevel.HARDCORE))
    }
}
