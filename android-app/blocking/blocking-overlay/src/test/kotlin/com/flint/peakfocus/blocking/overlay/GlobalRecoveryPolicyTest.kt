package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class GlobalRecoveryPolicyTest {
    private val now = AccessClockStamp(1_800_000_000_000, 100_000, "boot")
    private val session = BlockRule("session-timer-v2-one", "Focus", BlockTargets(apps = setOf(AppRef("a"))),
        breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = now.wallEpochMs + 600_000)
    private val schedule = session.copy(id = "work", name = "Work", schedule = Schedule(startMinuteOfDay = 0, endMinuteOfDay = 1_439), expiresAtEpochMs = null)
    private val interval = AnchoredInterval(now, 600_000, now.wallEpochMs + 600_000)
    private fun snapshot(at: AccessClockStamp = now): ProtectionSnapshot {
        val causes = mapOf(ruleCauseKey(session.id) to EditorCause(session.id, interval, "UTC"),
            ruleCauseKey(schedule.id) to EditorCause("occurrence", interval, "UTC"),
            timeCauseKey("b") to EditorCause("day", interval, "UTC"), openCauseKey("c") to EditorCause("day", interval, "UTC"))
        return ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(now, now), openCounts = OpenCountState(4, mapOf("c" to 7))),
            listOf(session, schedule), listOf(TimeLimit("b", 30)), listOf(OpenLimit("c", 2)),
            EditorCommitmentState(causes = causes, timeline = now), HarderWaitPolicy.Ledger(), at)
    }
    private fun consumed(s: ProtectionSnapshot = snapshot()): ProtectionSnapshot {
        val p = GlobalRecoveryPolicy.preview(s, emptyList())!!
        val m = GlobalRecoveryPolicy.consume(s, p, emptyList())
        assertTrue(m.result is RecoveryResult.Accepted)
        return s.copy(focus = m.focus, rules = m.rules!!, editor = m.editor!!, harder = m.harder!!)
    }

    @Test fun PC_39_PC_40_rollingCooldownCrossesMondayAndOnlyElapsedTimeRenewsIt() {
        val accepted = consumed()
        val pass = RollingEmergencyPassPolicy()
        val state = GlobalRecoveryPolicy.passState(accepted.focus.protection!!)
        assertTrue(pass.eligibility(state, now.copy(wallEpochMs = now.wallEpochMs + 50 * 86_400_000L)) is RollingEmergencyPassPolicy.Eligibility.CoolingDown)
        assertTrue(pass.eligibility(state, now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 604_799_000)) is RollingEmergencyPassPolicy.Eligibility.CoolingDown)
        assertEquals(RollingEmergencyPassPolicy.Eligibility.Available, pass.eligibility(state, now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 604_800_000)))
    }

    @Test fun PC_41_allKindsIncludingUnspentLimitsArePreviewedTransientEndsAndCountersNeverReset() {
        val s = snapshot()
        val preview = GlobalRecoveryPolicy.preview(s, emptyList())!!
        assertEquals(setOf("rule:${session.id}", "rule:work", "time:b", "open:c"), preview.affected.map { it.causeKey }.toSet())
        val m = GlobalRecoveryPolicy.consume(s, preview, emptyList())
        val recovery = m.focus.protection!!.recovery!!
        assertEquals(3_600_000L, recovery.durationMs)
        assertEquals(setOf(session.id), recovery.endedTransientIds)
        assertEquals(listOf(schedule), m.rules)
        assertEquals(s.focus.openCounts, m.focus.openCounts)
        val event = m.focus.protection!!.outbox.values.single()
        assertEquals(recovery.affectedRuleIds, event.affectedRuleIds)
        assertEquals(setOf(session.id), event.endedTransientIds)
        assertEquals(ProtectionLifecycleKind.PASS_COMMITTED, event.kind)
    }

    @Test fun PC_44_PC_45_retrySameOperationReturnsOriginalRecoveryAndDifferentOperationCannotExtendIt() {
        val s = snapshot(); val preview = GlobalRecoveryPolicy.preview(s, emptyList())!!
        val mutation = GlobalRecoveryPolicy.consume(s, preview, emptyList())
        val after = s.copy(focus = mutation.focus, rules = mutation.rules!!, editor = mutation.editor!!)
        val retry = GlobalRecoveryPolicy.consume(after.copy(observedAt = now.copy(elapsedRealtimeMs = 110_000)), preview, emptyList())
        assertEquals(RecoveryResult.Accepted(mutation.focus.protection!!.recovery, false), retry.result)
        assertEquals(1, retry.focus.protection!!.outbox.size)
        val other = preview.copy(operationId = "p1-11111111-1111-4111-8111-111111111111")
        assertFalse(GlobalRecoveryPolicy.consume(after, other, emptyList()).result is RecoveryResult.Accepted)
        assertEquals(mutation.focus.protection!!.recovery, after.focus.protection!!.recovery)
    }

    @Test fun PC_43_PC_47_scopeChangesOrMissingOccurrenceCannotSpendFromAStaleConfirmation() {
        val s = snapshot(); val p = GlobalRecoveryPolicy.preview(s, emptyList())!!
        val changed = s.copy(times = listOf(TimeLimit("b", 1, BreakLevel.HARDCORE)))
        assertEquals(RecoveryResult.StalePreview, GlobalRecoveryPolicy.consume(changed, p, emptyList()).result)
        assertNull(changed.focus.emergencyPass.usedAtEpochMs)
        val missing = s.copy(editor = s.editor.copy(causes = s.editor.causes - "time:b"))
        assertNull(GlobalRecoveryPolicy.preview(missing, emptyList()))
        assertTrue(GlobalRecoveryPolicy.consume(missing, p, emptyList()).result is RecoveryResult.Unavailable)
    }

    @Test fun PC_55_PC_59_PC_60_rebootEndsAccessButResumesCheckpointedCooldownWithoutPoweredOffCredit() {
        var s = consumed()
        s = GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = now.copy(wallEpochMs = now.wallEpochMs + 120_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 120_000)))
        val saved = s.focus.protection!!.cooldown!!.remainingAtCheckpointMs
        val reboot = GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = AccessClockStamp(now.wallEpochMs + 100 * 86_400_000L, 1_000, "other")))
        assertNull(reboot.focus.protection!!.recovery)
        assertTrue(reboot.focus.protection!!.previousAccessEnded)
        assertEquals(saved, reboot.focus.protection!!.cooldown!!.remainingAtCheckpointMs)
        assertTrue(reboot.focus.protection!!.cooldown!!.wallUncertain)
        assertEquals(s.focus.emergencyPass.usedAtEpochMs, reboot.focus.emergencyPass.usedAtEpochMs)
    }

    @Test fun PC_42_PC_44_sameBootRestartAndWallEditsKeepExactRecoveryRemainingAndExpiry() {
        val s = consumed(); val recovery = s.focus.protection!!.recovery!!
        val changedWall = now.copy(wallEpochMs = 0, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        assertEquals(3_599_000L, GlobalRecoveryPolicy.remaining(recovery, changedWall))
        assertEquals(0L, GlobalRecoveryPolicy.remaining(recovery, changedWall.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 3_600_000)))
        assertEquals(recovery, GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = changedWall)).focus.protection!!.recovery)
    }

    @Test fun PC_45_resumeNowNeverRefundsOrChangesCounters() {
        val s = consumed(); val next = GlobalRecoveryPolicy.resume(s).focus
        assertNull(next.protection!!.recovery)
        assertEquals(s.focus.protection!!.cooldown, next.protection!!.cooldown)
        assertEquals(s.focus.openCounts, next.openCounts)
        assertEquals(s.focus.emergencyPass, next.emergencyPass)
    }

    @Test fun PC_83_exactChosenTemplatesDisableOrDeleteAndUnchosenCausesAndAllCountersSurviveResume() {
        for (delete in listOf(false, true)) {
            val s = consumed()
            val preview = GlobalRecoveryPolicy.previewEnd(s, setOf("rule:work", "open:c"), delete)!!
            val next = GlobalRecoveryPolicy.endAndResume(s, preview)
            assertTrue(next.result)
            assertNull(next.focus.protection!!.recovery)
            assertEquals(s.focus.protection!!.cooldown, next.focus.protection!!.cooldown)
            assertEquals(s.focus.openCounts, next.focus.openCounts)
            assertEquals(s.times, next.times)
            assertTrue(next.opens!!.isEmpty())
            if (delete) assertTrue(next.rules!!.isEmpty()) else assertFalse(next.rules!!.single().enabled)
        }
    }

    @Test fun PC_83_staleEndSelectionCannotDisableANewRevisionOrEndAnExpiredRecovery() {
        val s = consumed(); val p = GlobalRecoveryPolicy.previewEnd(s, setOf("rule:work"), false)!!
        val changed = s.copy(rules = listOf(schedule.copy(name = "New")))
        assertFalse(GlobalRecoveryPolicy.endAndResume(changed, p).result)
        val ended = s.copy(observedAt = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 3_600_000))
        assertFalse(GlobalRecoveryPolicy.endAndResume(ended, p).result)
    }

    @Test fun PC_41_pendingWaitsCancelAndQueuedDraftTouchingEndedTransientNeedsReview() {
        val s = snapshot()
        val queued = QueuedEditorChange(1, EditorChange("edit", upserts = listOf(session, schedule)),
            EditorExpected(listOf(session, schedule), null, null, emptyMap()), mapOf("rule:${session.id}" to interval))
        val count = HarderWaitPolicy.Occurrence("rule:work", "occurrence")
        val pending = HarderWaitPolicy.PendingWait(HarderWaitPolicy.Operation(HarderWaitPolicy.OperationKind.BREAK, mapOf(count to "revision")), now, 60_000)
        val withWait = s.copy(editor = s.editor.copy(queued = listOf(queued)), harder = HarderWaitPolicy.Ledger(mapOf(count to 2), pending))
        val m = GlobalRecoveryPolicy.consume(withWait, GlobalRecoveryPolicy.preview(withWait, emptyList())!!, emptyList())
        assertNull(m.harder!!.pending)
        assertEquals(2, m.harder!!.counts[count])
        assertTrue(m.editor!!.queued.single().needsReview)
        assertEquals(queued.change, m.editor!!.queued.single().change)
    }

    @Test fun PC_47_foreignTransientCannotPretendItsTerminationIsAtomicButLegacyRuleIsInScope() {
        val s = snapshot()
        assertNull(GlobalRecoveryPolicy.preview(s, listOf(session.copy(id = "session-external"))))
        val p = GlobalRecoveryPolicy.preview(s, listOf(session.copy(id = "blocklist", expiresAtEpochMs = null)))!!
        assertTrue(p.affected.any { it.ruleId == "blocklist" && !it.transient })
    }

    @Test fun PC_45_staleOperationCannotBecomeFreshFromWallRollback() {
        val s = snapshot(); val p = GlobalRecoveryPolicy.preview(s, emptyList())!!
        val old = s.copy(observedAt = now.copy(wallEpochMs = 0, elapsedRealtimeMs = now.elapsedRealtimeMs + 3_600_000))
        assertEquals(RecoveryResult.Denied, GlobalRecoveryPolicy.consume(old, p, emptyList()).result)
    }

    @Test fun PC_63_PC_65_missingOrUnacknowledgingConsumerCannotGrowOutboxWithoutBoundAndLossIsExplicit() {
        val events = (0..300).associate { i ->
            "event-$i" to ProtectionLifecycleEvent("event-$i", "op", ProtectionLifecycleKind.PASS_COMMITTED,
                emptySet(), now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs - i), untilEpochMs = now.wallEpochMs + 1)
        }
        val bounded = GlobalRecoveryPolicy.bound(ProtectionLedger(now, now, outbox = events), now)
        assertEquals(GlobalRecoveryPolicy.MAX_EVENTS, bounded.outbox.size)
        assertEquals(45L, bounded.droppedEvents)
        val aged = GlobalRecoveryPolicy.bound(bounded, now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + ProtectionContract.PASS_COOLDOWN_MS + 1))
        assertTrue(aged.outbox.isEmpty())
        assertEquals(301L, aged.droppedEvents)
    }
}
