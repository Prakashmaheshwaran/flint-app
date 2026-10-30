package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ProtectionLifecycleTimingTest {
    private val base = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private val operation = "p1-11111111-1111-4111-8111-111111111111"
    private val input = OrdinaryProtectionInput("a", "self", false, false)
    private fun at(elapsed: Long, jump: Long = 0) = base.copy(
        wallEpochMs = base.wallEpochMs + elapsed + jump, elapsedRealtimeMs = base.elapsedRealtimeMs + elapsed)
    private fun snapshot(credit: Boolean = false): ProtectionSnapshot {
        val rule = BlockRule("session-timer-v2-one", "Focus", BlockTargets(apps = setOf(AppRef("a"))),
            breakLevel = BreakLevel.EASY, expiresAtEpochMs = base.wallEpochMs + 600_000)
        val key = if (credit) openCauseKey("a") else ruleCauseKey(rule.id)
        return ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(base, base)),
            if (credit) emptyList() else listOf(rule), emptyList(), if (credit) listOf(OpenLimit("a", 3)) else emptyList(),
            EditorCommitmentState(causes = mapOf(key to EditorCause("occurrence", AnchoredInterval(base, 600_000,
                base.wallEpochMs + 600_000), "UTC")), timeline = base), HarderWaitPolicy.Ledger(), base,
            OpenCreditLedgerPolicy.initial(base, "UTC"))
    }
    private fun accept(s: ProtectionSnapshot, kind: ProtectionLifecycleKind): ProtectionSnapshot {
        val mutation = when (kind) {
            ProtectionLifecycleKind.PASS_COMMITTED -> GlobalRecoveryPolicy.consume(s,
                GlobalRecoveryPolicy.preview(s, emptyList(), operation)!!, emptyList())
            ProtectionLifecycleKind.BREAK_STARTED -> OrdinaryProtectionPolicy.request(s, input, operation, s.observedAt,
                emptyList(), OrdinaryProtectionPolicy.frozenScopes(s, input, emptyList(), s.observedAt)!!)
            ProtectionLifecycleKind.ACCESS_STARTED -> {
                val target = input.copy(openBlocked = true)
                val view = TimedOpenCreditPolicy.view(s, target, emptyList(), s.observedAt)!!
                TimedOpenCreditPolicy.request(s, target, GrantRequestId(s.observedAt.wallEpochMs,
                    operation.removePrefix("p1-"), s.observedAt, view.requestScope), emptyList(), false)
            }
            else -> error("Not a start")
        }
        return s.copy(focus = mutation.focus, rules = mutation.rules ?: s.rules, editor = mutation.editor ?: s.editor,
            harder = mutation.harder ?: s.harder, openCredits = mutation.openCredits ?: s.openCredits)
    }
    private fun assertEvidence(event: ProtectionLifecycleEvent, logical: Long, observed: AccessClockStamp,
                               uncertain: String? = null, reconciled: Boolean = false) {
        assertEquals(logical, event.at.wallEpochMs)
        assertEquals(observed.wallEpochMs, event.observedAtEpochMs)
        assertEquals(uncertain?.let(ProtectionEventTimeConfidence::Uncertain) ?: ProtectionEventTimeConfidence.Certain, event.timeConfidence)
        assertEquals(uncertain != null, event.timeUncertain)
        assertEquals(reconciled, event.reconciled)
    }
    private fun starts(kind: ProtectionLifecycleKind) {
        for (jump in listOf(-3_600_000L, 0L, 3_600_000L)) {
            val now = at(10_000, jump)
            val s = accept(snapshot(kind == ProtectionLifecycleKind.ACCESS_STARTED).copy(observedAt = now), kind)
            val event = s.focus.protection!!.outbox.values.single { it.kind == kind }
            assertEvidence(event, base.wallEpochMs + 10_000, now, if (jump == 0L) null else "same_boot_drift")
            val duration = if (kind == ProtectionLifecycleKind.PASS_COMMITTED) ProtectionContract.PASS_RECOVERY_MS else ProtectionContract.ORDINARY_BREAK_MS
            assertEquals(event.at.wallEpochMs + duration, event.untilEpochMs)
            // Event correction never rewrites release authority or makes a second spend.
            when (kind) {
                ProtectionLifecycleKind.PASS_COMMITTED -> {
                    assertEquals(now, s.focus.protection!!.recovery!!.startedAt)
                    assertEquals(ProtectionContract.PASS_COOLDOWN_MS, s.focus.protection!!.cooldown!!.remainingAtCheckpointMs)
                }
                ProtectionLifecycleKind.BREAK_STARTED -> assertEquals(now, s.focus.protection!!.ordinaryGrants.getValue(operation).lifetime.startedAt)
                else -> { assertEquals(now, s.openCredits!!.active.getValue("a").grant.startedAt); assertEquals(1, s.openCredits!!.counters.getValue("a").used) }
            }
        }
    }
    @Test fun PC_54_PC_55_passCommittedForwardAndBackwardEditsKeepLogicalAndObservedTime() = starts(ProtectionLifecycleKind.PASS_COMMITTED)
    @Test fun PC_54_PC_55_breakStartedForwardAndBackwardEditsKeepLogicalAndObservedTime() = starts(ProtectionLifecycleKind.BREAK_STARTED)
    @Test fun PC_54_PC_55_accessStartedForwardAndBackwardEditsKeepLogicalAndObservedTime() = starts(ProtectionLifecycleKind.ACCESS_STARTED)

    private fun ends(credit: Boolean) {
        for (jump in listOf(-3_600_000L, 0L, 3_600_000L)) {
            val startKind = if (credit) ProtectionLifecycleKind.ACCESS_STARTED else ProtectionLifecycleKind.BREAK_STARTED
            val accepted = accept(snapshot(credit).copy(observedAt = at(10_000, jump)), startKind)
            // Simulate acknowledged start + process restart: the event itself is not the anchor.
            val s = accepted.copy(focus = accepted.focus.copy(protection = accepted.focus.protection!!.copy(outbox = emptyMap())))
            val observed = at(370_000, jump)
            val ended = GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = observed)).focus.protection!!.outbox.values.single()
            assertEvidence(ended, base.wallEpochMs + 310_000, observed, if (jump == 0L) null else "same_boot_drift", reconciled = true)
            assertEquals(ProtectionBreakEndReason.EXPIRED, ended.breakEndReason)
            assertEquals(operation, ended.grantId)
            assertEquals(if (credit) ProtectionLifecycleKind.ACCESS_ENDED else ProtectionLifecycleKind.BREAK_ENDED, ended.kind)
            val early = s.copy(observedAt = at(20_000, jump))
            val resumed = if (credit) TimedOpenCreditPolicy.resume(early) else OrdinaryProtectionPolicy.resume(early)
            assertEvidence(resumed.focus.protection!!.outbox.values.single(), base.wallEpochMs + 20_000,
                early.observedAt, if (jump == 0L) null else "same_boot_drift")
        }
    }
    @Test fun PC_54_PC_55_breakEndedForwardAndBackwardEditsKeepDeadlineObservationAndResumeDistinct() = ends(false)
    @Test fun PC_54_PC_55_accessEndedForwardAndBackwardEditsKeepDeadlineObservationAndResumeDistinct() = ends(true)

    @Test fun PC_58_PC_59_rebootedEndsCannotBorrowANewBootAnchorOrInventAnExpiry() {
        for (credit in listOf(false, true)) {
            val s = accept(snapshot(credit), if (credit) ProtectionLifecycleKind.ACCESS_STARTED else ProtectionLifecycleKind.BREAK_STARTED)
            val reboot = at(50_000, -3_600_000).copy(bootSessionId = "new-boot", elapsedRealtimeMs = 1_000)
            val after = GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = reboot, editor = s.editor.copy(timeline = reboot)))
            val event = after.focus.protection!!.outbox.values.single { it.kind in setOf(ProtectionLifecycleKind.BREAK_ENDED, ProtectionLifecycleKind.ACCESS_ENDED) }
            assertEvidence(event, reboot.wallEpochMs, reboot, "reboot_without_anchor", reconciled = true)
        }
    }

    @Test fun PC_54_PC_55_confidenceUsesPersistedAnchorAfterCheckpointAndFiveSecondTolerance() {
        for (jump in listOf(-5_001L, -5_000L, 5_000L, 5_001L)) {
            val now = at(10_000, jump)
            val s = GlobalRecoveryPolicy.checkpoint(snapshot().copy(observedAt = now))
            assertEquals(now, s.focus.protection!!.checkpoint)
            val event = accept(s, ProtectionLifecycleKind.BREAK_STARTED).focus.protection!!.outbox.values.single()
            assertEvidence(event, base.wallEpochMs + 10_000, now, if (kotlin.math.abs(jump) > 5_000) "same_boot_drift" else null)
        }
    }

    @Test fun PC_58_unknownBootMissingAnchorAndMonotonicRegressionNeverBecomeCertain() {
        val event = ProtectionLifecycleEvent("pass:x", "x", ProtectionLifecycleKind.PASS_COMMITTED, emptySet(), at(10_000),
            untilEpochMs = at(10_000).wallEpochMs + 3_600_000)
        for (unknown in listOf("", "process-opaque-fallback")) {
            val now = at(10_000).copy(bootSessionId = unknown)
            val s = snapshot().copy(observedAt = now, editor = EditorCommitmentState(timeline = base.copy(bootSessionId = unknown)))
            assertEvidence(ProtectionLifecycleTiming.record(event, s), now.wallEpochMs, now, "unknown_boot")
        }
        val missing = snapshot().copy(focus = FocusSnapshot(), editor = EditorCommitmentState(), observedAt = at(10_000))
        assertEvidence(ProtectionLifecycleTiming.record(event, missing), missing.observedAt.wallEpochMs, missing.observedAt, "missing_anchor")
        val regressed = snapshot().copy(observedAt = base.copy(elapsedRealtimeMs = 0))
        assertEvidence(ProtectionLifecycleTiming.record(event, regressed), base.wallEpochMs, regressed.observedAt, "monotonic_regression")
        val reboot = snapshot().copy(observedAt = at(10_000).copy(bootSessionId = "new"))
        assertEvidence(ProtectionLifecycleTiming.record(event, reboot), reboot.observedAt.wallEpochMs, reboot.observedAt, "reboot_without_anchor")
    }

    @Test fun PC_54_PC_55_supersessionAndPassEndsUseLogicalTimeWithoutClaimingReconciliation() {
        for (jump in listOf(-3_600_000L, 3_600_000L)) {
            val s = accept(snapshot(), ProtectionLifecycleKind.BREAK_STARTED).copy(observedAt = at(20_000, jump))
            val retired = OrdinaryProtectionPolicy.retireInvalid(s.copy(rules = s.rules.map { it.copy(breakLevel = BreakLevel.HARDCORE) }), input, emptyList())
            val superseded = retired.protection!!.outbox.values.single { it.kind == ProtectionLifecycleKind.BREAK_ENDED }
            assertEvidence(superseded, base.wallEpochMs + 20_000, s.observedAt, "same_boot_drift")
            assertEquals(ProtectionBreakEndReason.SUPERSEDED, superseded.breakEndReason)
            val passed = GlobalRecoveryPolicy.consume(s, GlobalRecoveryPolicy.preview(s, emptyList(),
                "p1-22222222-2222-4222-8222-222222222222")!!, emptyList())
            val ended = passed.focus.protection!!.outbox.values.single { it.kind == ProtectionLifecycleKind.BREAK_ENDED }
            assertEvidence(ended, base.wallEpochMs + 20_000, s.observedAt, "same_boot_drift")
            assertEquals(ProtectionBreakEndReason.RECOVERY, ended.breakEndReason)
        }
    }

    @Test fun PC_54_PC_55_initializedAtFallbackDetectsStaleOrUntrustedAnchors() {
        for (jump in listOf(-3_600_000L, 0L, 3_600_000L)) {
            val now = at(10_000, jump)
            val s = snapshot().copy(editor = snapshot().editor.copy(timeline = null), observedAt = now)
            val event = accept(GlobalRecoveryPolicy.checkpoint(s), ProtectionLifecycleKind.BREAK_STARTED).focus.protection!!.outbox.values.single()
            assertEvidence(event, base.wallEpochMs + 10_000, now, if (jump == 0L) null else "same_boot_drift")
        }
        val raw = ProtectionLifecycleEvent("pass:x", "x", ProtectionLifecycleKind.PASS_COMMITTED, emptySet(), base,
            untilEpochMs = base.wallEpochMs + 3_600_000)
        for ((stamp, reason) in listOf(at(10_000).copy(bootSessionId = "new") to "reboot_without_anchor",
            at(10_000).copy(bootSessionId = "process-unknown") to "unknown_boot",
            base.copy(elapsedRealtimeMs = 0) to "monotonic_regression")) {
            val s = snapshot().copy(editor = EditorCommitmentState(), observedAt = stamp)
            assertEvidence(ProtectionLifecycleTiming.record(raw, s), stamp.wallEpochMs, stamp, reason)
        }
    }

    @Test fun PC_54_PC_55_extremeAndInvalidIntervalsRemainBoundedUncertainAuditRecords() {
        val nearLimit = base.copy(wallEpochMs = Long.MAX_VALUE - 10)
        val s = snapshot().copy(editor = EditorCommitmentState(timeline = nearLimit), observedAt = nearLimit)
        val raw = ProtectionLifecycleEvent("break:x", "x", ProtectionLifecycleKind.BREAK_STARTED, emptySet(), base,
            grantId = "x", untilEpochMs = base.wallEpochMs + 300_000)
        for ((event, observed) in listOf(
            raw to s, // logical endpoint addition overflows
            raw.copy(at = base.copy(wallEpochMs = Long.MIN_VALUE), untilEpochMs = Long.MAX_VALUE) to s, // subtraction overflows
            raw.copy(untilEpochMs = base.wallEpochMs - 1) to snapshot(), // negative interval
            raw.copy(untilEpochMs = base.wallEpochMs) to snapshot(), // zero interval
            raw to s.copy(editor = EditorCommitmentState(timeline = nearLimit.copy(wallEpochMs = Long.MAX_VALUE)),
                observedAt = nearLimit.copy(wallEpochMs = Long.MAX_VALUE)),
        )) {
            val result = ProtectionLifecycleTiming.record(event, observed)
            assertEquals(ProtectionEventTimeConfidence.Uncertain("invalid_interval"), result.timeConfidence)
            assertTrue(result.timeUncertain)
            assertEquals(observed.observedAt.wallEpochMs, result.observedAtEpochMs)
            assertTrue(result.at.wallEpochMs >= 0)
            assertTrue(result.untilEpochMs!! > result.at.wallEpochMs)
            assertEquals(event.id, result.id)
        }
    }
}
