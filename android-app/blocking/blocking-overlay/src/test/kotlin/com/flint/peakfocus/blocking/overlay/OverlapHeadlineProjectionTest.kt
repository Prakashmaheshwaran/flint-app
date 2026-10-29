package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.RuleClockProjection
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Engine headline -> shared cause -> block-screen countdown; neither detector has a special path. */
class OverlapHeadlineProjectionTest {
    private val hour = 3_600_000L
    private val midnight = 100 * 24 * hour
    private val now = midnight + 9 * hour
    private val pkg = "com.target"
    private fun rule(id: String, end: Long?, level: BreakLevel = BreakLevel.HARDCORE) =
        BlockRule(id, id, BlockTargets(apps = setOf(AppRef(pkg))), breakLevel = level, expiresAtEpochMs = end)
    private fun screen(cause: BlockCause, at: Long = now) = blockScreenState(pkg, "Target", cause, BreakSessionState(), at)
    private fun selected(rules: List<BlockRule>, at: Long = now, minute: Int = 540): BlockCause {
        val verdict = BlockDecisionEngine().decide(pkg, null, rules, "com.flint", minute, 1, at) as Verdict.Block
        return ruleBlockCause(rules.single { it.id == verdict.ruleId }, at, 0)
    }

    @Test fun R_33_PC_19_PC_25_countdownShowsTwelveWhileTenOclockContributorEnds() {
        for (level in BreakLevel.entries) {
            val short = rule("ten", midnight + 10 * hour, level)
            val long = rule("twelve", midnight + 12 * hour, level)
            for (rules in listOf(listOf(short, long), listOf(long, short))) {
                assertEquals(3 * hour, screen(selected(rules)).remainingMillis)
                val later = midnight + 10 * hour
                assertEquals(2 * hour, screen(selected(rules, later, 600), later).remainingMillis)
                assertEquals("twelve", selected(rules).ruleId)
            }
        }
    }

    @Test fun R_33_ruleTimeAndOpenCausesChooseLatestEndWithoutDependingOnArgumentOrder() {
        for (level in BreakLevel.entries) {
            val causes = listOf(
                BlockCause(BlockScreenReason.SCHEDULE, level, now + hour, "rule"),
                BlockCause(BlockScreenReason.TIME_LIMIT, level, now + 3 * hour),
                BlockCause(BlockScreenReason.OPEN_LIMIT, level, now + 2 * hour),
            )
            for (order in permutations(causes)) {
                val result = BlockOverlapPolicy.strongest(null, *order.toTypedArray())!!
                assertEquals(BlockScreenReason.TIME_LIMIT, result.reason)
                assertEquals(3 * hour, screen(result).remainingMillis)
            }
        }
        assertNull(BlockOverlapPolicy.strongest(null, null))
    }

    @Test fun R_33_ongoingSameTierHidesCountdownButWeakerOngoingCannotDowngradeHardcore() {
        val ongoing = BlockCause(BlockScreenReason.SCHEDULE, BreakLevel.HARDCORE, null, "ongoing")
        val finite = BlockCause(BlockScreenReason.TIME_LIMIT, BreakLevel.HARDCORE, now + hour)
        for (order in permutations(listOf(ongoing, finite))) {
            val result = BlockOverlapPolicy.strongest(*order.toTypedArray())!!
            assertEquals(ongoing, result)
            assertNull(screen(result).remainingMillis)
            assertEquals(result, BlockOverlapPolicy.afterExemptions(result, true, false))
            assertNull(BlockOverlapPolicy.afterExemptions(result, true, true))
        }
        for (order in permutations(listOf(ongoing.copy(breakLevel = BreakLevel.EASY), finite))) {
            assertEquals(finite, BlockOverlapPolicy.strongest(*order.toTypedArray()))
        }
    }

    @Test fun R_33_stableTiesPreserveCausePriorityAndOpenBreakAccounting() {
        for (end in listOf(null, now + hour)) {
            val a = BlockCause(BlockScreenReason.SCHEDULE, BreakLevel.HARDER, end, "a")
            val z = a.copy(ruleId = "z")
            for (order in permutations(listOf(a, z))) assertEquals(a, BlockOverlapPolicy.strongest(*order.toTypedArray()))
            val time = BlockCause(BlockScreenReason.TIME_LIMIT, BreakLevel.HARDER, end)
            val open = BlockCause(BlockScreenReason.OPEN_LIMIT, BreakLevel.HARDER, end)
            for (order in permutations(listOf(z, time, open))) {
                val cause = BlockOverlapPolicy.strongest(*order.toTypedArray())!!
                assertEquals(time, cause)
                assertFalse(cause.spendsOpenOnBreak)
            }
            for (order in permutations(listOf(z, open))) {
                val cause = BlockOverlapPolicy.strongest(*order.toTypedArray())!!
                assertEquals(z, cause)
                assertFalse(cause.spendsOpenOnBreak)
            }
            assertTrue(BlockOverlapPolicy.strongest(open)!!.spendsOpenOnBreak)
        }
    }

    @Test fun R_33_legacyScheduledHeadlineAndScreenResolveTheSameEnd() {
        val schedule = rule("schedule", null).copy(schedule = Schedule(startMinuteOfDay = 8 * 60, endMinuteOfDay = 12 * 60))
        val timer = rule("timer", midnight + 11 * hour)
        for (order in permutations(listOf(schedule, timer))) {
            val cause = selected(order)
            assertEquals("schedule", cause.ruleId)
            assertEquals(BlockScreenReason.SCHEDULE, cause.reason)
            assertEquals(3 * hour, screen(cause).remainingMillis)
        }
        val offset = 5 * hour + 30 * 60_000
        val local = ruleBlockCause(schedule, midnight + 4 * hour + 17_321, offset)
        assertEquals(midnight + 6 * hour + 30 * 60_000, local.endsAtEpochMs)
    }

    @Test fun R_33_anchoredDstEndAndOngoingRuntimeRemainAuthoritativeInTheScreen() {
        // The repeated 01:45 end in New York is the later instant, 06:45 UTC.
        val at = Instant.parse("2026-11-01T05:20:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-01T06:45:00Z").toEpochMilli()
        val clock = AccessClockStamp(at, 1_000, "boot")
        val raw = rule("dst", null).copy(schedule = Schedule(startMinuteOfDay = 75, endMinuteOfDay = 105),
            runtime = RuleRuntime("dst", clock, end - at, end, "America/New_York", clock))
        val projected = RuleClockProjection.project(raw, clock)
        val cause = ruleBlockCause(projected, at, -4 * hour)
        assertEquals(end, cause.endsAtEpochMs)
        assertEquals(85 * 60_000L, screen(cause, at).remainingMillis)
        val ongoing = projected.copy(runtime = projected.runtime!!.copy(evaluation = RuleEvaluation(true, null, null)))
        assertNull(screen(ruleBlockCause(ongoing, at, -4 * hour), at).remainingMillis)
    }

    private fun <T> permutations(values: List<T>): List<List<T>> = if (values.isEmpty()) listOf(emptyList())
        else values.indices.flatMap { i -> permutations(values.filterIndexed { index, _ -> index != i }).map { listOf(values[i]) + it } }
}
