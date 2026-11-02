package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.engine.RuleClockProjection
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class EditorRuleProjectionTest {
    private val now = AccessClockStamp(1_000_000, 10_000, "boot")
    private val rule = BlockRule("work", "Work", BlockTargets(apps = setOf(AppRef("app"))),
        Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60), BreakLevel.HARDCORE)
    private val cause = EditorCause("occurrence", AnchoredInterval(now, 60_000, now.wallEpochMs + 60_000), "UTC")
    private val state = EditorCommitmentState(causes = mapOf(ruleCauseKey(rule.id) to cause), timeline = now)

    @Test fun `R_18 PC_54 runtime anchor travels with raw schedule but never enters the rule wire format`() {
        val published = editorRulesWithRuntime(listOf(rule), state, now).single()
        assertEquals(rule.schedule, published.schedule)
        assertEquals(cause.interval!!.anchor, published.runtime!!.anchor)
        assertEquals(PrefsCodec.encodeRules(listOf(rule)), PrefsCodec.encodeRules(listOf(published)))
        assertEquals(listOf(rule), StrictPrefsCodec.rules(PrefsCodec.encodeRules(listOf(published))))
        val shifted = now.copy(wallEpochMs = now.wallEpochMs + 90_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        assertEquals(shifted.wallEpochMs + 59_000, RuleClockProjection.project(published, shifted).expiresAtEpochMs)
    }

    @Test fun `R_18 queued template stops at its old boundary without erasing the raw future schedule`() {
        val bound = AnchoredInterval(now, 30_000, now.wallEpochMs + 30_000)
        val queue = QueuedEditorChange(1, EditorChange("Off", upserts = listOf(rule.copy(enabled = false))),
            EditorExpected(listOf(rule), null, null, mapOf(ruleCauseKey(rule.id) to cause.occurrenceId)),
            mapOf(ruleCauseKey(rule.id) to bound))
        val published = editorRulesWithRuntime(listOf(rule), state.copy(queued = listOf(queue)), now).single()
        assertEquals(rule.schedule, published.schedule)
        assertTrue(published.runtime!!.stopAtOccurrenceEnd)
        assertEquals(30_000L, published.runtime!!.remainingAtAnchorMs)
        val reviewNeeded = editorRulesWithRuntime(listOf(rule), state.copy(queued = listOf(queue.copy(needsReview = true))), now).single()
        assertFalse(reviewNeeded.runtime!!.stopAtOccurrenceEnd)
        assertEquals(60_000L, reviewNeeded.runtime!!.remainingAtAnchorMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `R_18 missing same-revision occurrence cannot silently publish raw clock authority`() {
        editorRulesWithRuntime(listOf(rule), EditorCommitmentState(), now)
    }
}
