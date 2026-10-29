package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OrdinaryProtectionPolicyTest {
    private val now = AccessClockStamp(1_800_000_000_000, 1_000, "boot")
    private val id = "p1-11111111-1111-4111-8111-111111111111"
    private val input = OrdinaryProtectionInput("a", "self", false, false)
    private fun snapshot(tier: BreakLevel = BreakLevel.EASY, remaining: Long = 600_000): ProtectionSnapshot {
        val rule = BlockRule("session-timer-v2-one", "One", BlockTargets(apps = setOf(AppRef("a"))), breakLevel = tier, expiresAtEpochMs = now.wallEpochMs + remaining)
        val cause = EditorCause(rule.id, AnchoredInterval(now, remaining, now.wallEpochMs + remaining), "UTC")
        return ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(now, now)), listOf(rule), emptyList(), emptyList(),
            EditorCommitmentState(causes = mapOf("rule:${rule.id}" to cause), timeline = now), HarderWaitPolicy.Ledger(), now)
    }
    private fun accept(s: ProtectionSnapshot): ProtectionSnapshot {
        val m = OrdinaryProtectionPolicy.request(s, input, id, now, emptyList())
        assertTrue(m.result is GrantResult.Granted)
        return s.copy(focus = m.focus, harder = m.harder ?: s.harder)
    }

    @Test fun PC_22_PC_24_ordinaryAccessIsTargetAndFrozenOccurrenceScopedAndClippedToNaturalEnd() {
        val s = accept(snapshot(remaining = 12_000))
        val grant = s.focus.protection!!.ordinaryGrants.values.single()
        assertEquals(AccessTarget.App("a"), grant.target)
        assertEquals(12_000L, grant.lifetime.durationMs)
        assertTrue(OrdinaryProtectionPolicy.covers(s, input, emptyList(), now))
        assertFalse(OrdinaryProtectionPolicy.covers(s, input.copy(packageName = "b"), emptyList(), now))
        assertFalse(OrdinaryProtectionPolicy.covers(s, input, emptyList(), now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 12_000)))
        val nextOccurrence = s.copy(editor = s.editor.copy(causes = s.editor.causes.mapValues { it.value.copy(occurrenceId = "next") }))
        assertFalse(OrdinaryProtectionPolicy.covers(nextOccurrence, input, emptyList(), now))
    }

    @Test fun PC_25_hardcoreContributorDeniesRegardlessOfDisplayedOrDefaultTierAndNewCauseImmediatelyBlocks() {
        assertEquals(GrantResult.Denied, OrdinaryProtectionPolicy.request(snapshot(BreakLevel.HARDCORE), input, id, now, emptyList()).result)
        val s = accept(snapshot())
        val changed = s.copy(rules = s.rules.map { it.copy(breakLevel = BreakLevel.HARDCORE) })
        assertFalse(OrdinaryProtectionPolicy.covers(changed, input, emptyList(), now))
        val retired = OrdinaryProtectionPolicy.retireInvalid(changed, input, emptyList())
        assertTrue(retired.protection!!.ordinaryGrants.isEmpty())
        val end = retired.protection!!.outbox.getValue("break-ended:$id")
        assertEquals(ProtectionBreakEndReason.SUPERSEDED, end.breakEndReason)
        assertEquals(setOf("session-timer-v2-one"), end.affectedRuleIds)
    }

    @Test fun PC_18_PC_19_PC_20_perOccurrenceWaitsMatureWithoutReleasingAndIncrementOnlyOnExplicitFinalCommit() {
        var s = snapshot(BreakLevel.HARDER)
        val first = OrdinaryProtectionPolicy.request(s, input, id, now, emptyList())
        assertTrue(first.result is GrantResult.Pending)
        assertEquals(60_000L, first.harder!!.pending!!.requiredMs)
        assertTrue(first.focus.protection!!.ordinaryGrants.isEmpty())
        s = s.copy(harder = first.harder!!, observedAt = now.copy(wallEpochMs = now.wallEpochMs + 60_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 60_000))
        assertTrue(GlobalRecoveryPolicy.checkpoint(s).focus.protection!!.ordinaryGrants.isEmpty())
        val accepted = OrdinaryProtectionPolicy.request(s, input, id, now, emptyList())
        assertTrue(accepted.result is GrantResult.Granted)
        assertEquals(1, accepted.harder!!.counts.values.single())
        val retry = OrdinaryProtectionPolicy.request(s.copy(focus = accepted.focus, harder = accepted.harder!!), input, id, now, emptyList())
        assertTrue(retry.result is GrantResult.Granted)
        assertNull(retry.harder)
        val occurrence = accepted.harder!!.counts.keys.single()
        val afterResume = OrdinaryProtectionPolicy.resume(s.copy(focus = accepted.focus, harder = accepted.harder!!)).focus
        val next = OrdinaryProtectionPolicy.request(s.copy(focus = afterResume, harder = accepted.harder!!), input,
            "p1-22222222-2222-4222-8222-222222222222", s.observedAt, emptyList())
        assertEquals(300_000L, next.harder!!.pending!!.requiredMs)
        val more = next.harder!!.copy(counts = mapOf(occurrence to 2), pending = null)
        assertEquals(900_000L, OrdinaryProtectionPolicy.request(s.copy(focus = afterResume, harder = more), input,
            "p1-33333333-3333-4333-8333-333333333333", s.observedAt, emptyList()).harder!!.pending!!.requiredMs)
    }

    @Test fun PC_21_rebootCancelsWaitAndDoesNotIncrementOrProveItMatured() {
        val s = snapshot(BreakLevel.HARDER)
        val pending = OrdinaryProtectionPolicy.request(s, input, id, now, emptyList())
        val rebooted = s.copy(harder = pending.harder!!, observedAt = AccessClockStamp(now.wallEpochMs + 60_000, 0, "other"))
        val next = OrdinaryProtectionPolicy.request(rebooted, input, "p1-22222222-2222-4222-8222-222222222222", rebooted.observedAt, emptyList())
        assertTrue(next.result is GrantResult.Pending)
        assertEquals(60_000L, next.harder!!.pending!!.requiredMs)
        assertTrue(next.harder!!.counts.isEmpty())
    }

    @Test fun PC_26_duplicateOrDistinctOperationCannotRenewALiveIntervalAndExpiredRetryCannotRecreateIt() {
        val s = accept(snapshot())
        val later = s.copy(observedAt = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 10_000))
        val same = OrdinaryProtectionPolicy.request(later, input, id, now, emptyList())
        val other = OrdinaryProtectionPolicy.request(later, input, "p1-22222222-2222-4222-8222-222222222222", later.observedAt, emptyList())
        assertEquals(s.focus.protection!!.ordinaryGrants, same.focus.protection!!.ordinaryGrants)
        assertEquals(s.focus.protection!!.ordinaryGrants, other.focus.protection!!.ordinaryGrants)
        assertFalse((other.result as GrantResult.Granted).newlyAccepted)
        val expired = s.copy(observedAt = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 300_000))
        assertEquals(GrantResult.Denied, OrdinaryProtectionPolicy.request(expired, input, id, now, emptyList()).result)
    }

    @Test fun PC_44_PC_63_PC_65_everyEndReasonPairsTheSameGrantIdAndExactRuleIdsWithItsDurableStart() {
        val s = accept(snapshot())
        val start = s.focus.protection!!.outbox.getValue("break-started:$id")
        val expired = GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 300_000))).focus
        val resumed = OrdinaryProtectionPolicy.resume(s).focus
        val superseded = OrdinaryProtectionPolicy.retireInvalid(s.copy(rules = s.rules.map { it.copy(name = "changed") }), input, emptyList())
        val preview = GlobalRecoveryPolicy.preview(s, emptyList())!!
        val recovery = GlobalRecoveryPolicy.consume(s, preview, emptyList()).focus
        for ((focus, reason) in listOf(expired to ProtectionBreakEndReason.EXPIRED, resumed to ProtectionBreakEndReason.RESUMED,
            superseded to ProtectionBreakEndReason.SUPERSEDED, recovery to ProtectionBreakEndReason.RECOVERY)) {
            val end = focus.protection!!.outbox.getValue("break-ended:$id")
            assertEquals(start.grantId, end.grantId)
            assertEquals(start.affectedRuleIds, end.affectedRuleIds)
            assertEquals(reason, end.breakEndReason)
            assertTrue(focus.protection!!.ordinaryGrants.isEmpty())
        }
    }
}
