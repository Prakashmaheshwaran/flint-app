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
    private fun request(s: ProtectionSnapshot, input: OrdinaryProtectionInput, id: String, issuedAt: AccessClockStamp,
        foreign: List<BlockRule>): ProtectionMutation<GrantResult> = OrdinaryProtectionPolicy.request(s, input, id, issuedAt, foreign,
            OrdinaryProtectionPolicy.frozenScopes(s, input, foreign, s.observedAt).orEmpty())

    private fun accept(s: ProtectionSnapshot): ProtectionSnapshot {
        val m = request(s, input, id, now, emptyList())
        assertTrue(m.result is GrantResult.Granted)
        return s.copy(focus = m.focus, harder = m.harder ?: s.harder)
    }

    @Test fun PC_56_savedZoneCaptureKeepsTheExactPreZoneTimeBreakUntilItsOwnFiniteEnd() {
        val timeInput = input.copy(timeSpent = true)
        val time = TimeLimit("a", 0, BreakLevel.EASY)
        val cause = EditorCause("old-time-period", AnchoredInterval(now, 600_000, now.wallEpochMs + 600_000), "UTC")
        val original = snapshot().copy(rules = emptyList(), times = listOf(time), editor = EditorCommitmentState(
            causes = mapOf(timeCauseKey("a") to cause)))
        val scopes = OrdinaryProtectionPolicy.frozenScopes(original, timeInput, emptyList(), now)!!
        val oldDefinition = "TimeLimit(packageName=a, dailyMinutes=0, breakLevel=EASY)"
        val oldHash = java.security.MessageDigest.getInstance("SHA-256").digest(oldDefinition.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(oldHash, scopes.single().definitionRevision)
        val accepted = OrdinaryProtectionPolicy.request(original, timeInput, id, now, emptyList(), scopes)
        val migrated = original.copy(focus = accepted.focus, times = listOf(time.copy(zoneId = "Asia/Tokyo")))
        assertTrue(OrdinaryProtectionPolicy.covers(migrated, timeInput, emptyList(), now))
        val next = migrated.copy(editor = migrated.editor.copy(causes = mapOf(timeCauseKey("a") to cause.copy(occurrenceId = "next"))))
        assertFalse(OrdinaryProtectionPolicy.covers(next, timeInput, emptyList(), now))
    }

    @Test fun PC_60_legacyOnlyTimeGrantUsesTheTrustedTimePeriodAndEndInsteadOfTheOpenPeriod() {
        val timeInput = input.copy(timeSpent = true, legacyTimeEndEpochMs = now.wallEpochMs + 12_000, timePeriodId = 47)
        val original = snapshot().copy(rules = emptyList(), times = emptyList(), editor = EditorCommitmentState(),
            openCredits = OpenCreditLedgerPolicy.initial(now, "UTC", periodId = 99))
        val scopes = OrdinaryProtectionPolicy.frozenScopes(original, timeInput, emptyList(), now)!!
        assertEquals("legacy-time-47", scopes.single().counter.periodId)
        val accepted = OrdinaryProtectionPolicy.request(original, timeInput, id, now, emptyList(), scopes)
        val current = original.copy(focus = accepted.focus)
        assertEquals(12_000L, current.focus.protection!!.ordinaryGrants.values.single().lifetime.durationMs)
        assertTrue(OrdinaryProtectionPolicy.covers(current, timeInput, emptyList(), now))
        assertFalse(OrdinaryProtectionPolicy.covers(current, timeInput.copy(timePeriodId = 48), emptyList(), now))
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
        assertEquals(GrantResult.Denied, request(snapshot(BreakLevel.HARDCORE), input, id, now, emptyList()).result)
        val s = accept(snapshot())
        val changed = s.copy(rules = s.rules.map { it.copy(breakLevel = BreakLevel.HARDCORE) })
        assertFalse(OrdinaryProtectionPolicy.covers(changed, input, emptyList(), now))
        val retired = OrdinaryProtectionPolicy.retireInvalid(changed, input, emptyList())
        assertTrue(retired.protection!!.ordinaryGrants.isEmpty())
        val end = retired.protection!!.outbox.getValue("break-ended:$id")
        assertEquals(ProtectionBreakEndReason.SUPERSEDED, end.breakEndReason)
        assertEquals(setOf("session-timer-v2-one"), end.affectedRuleIds)
    }

    @Test fun PC_25_contributorArrivingBetweenClickAndCommitCannotJoinTheFrozenRequest() {
        val original = snapshot()
        val frozen = OrdinaryProtectionPolicy.frozenScopes(original, input, emptyList(), now)!!
        val added = original.rules.single().copy(id = "session-timer-v2-two", name = "Two")
        val changed = original.copy(rules = original.rules + added, editor = original.editor.copy(causes =
            original.editor.causes + ("rule:${added.id}" to original.editor.causes.values.single().copy(occurrenceId = added.id))))
        val denied = OrdinaryProtectionPolicy.request(changed, input, id, now, emptyList(), frozen)
        assertEquals(GrantResult.Denied, denied.result)
        assertTrue(denied.focus.protection!!.ordinaryGrants.isEmpty())
        assertTrue(denied.focus.protection!!.operations.isEmpty())
        assertTrue(denied.focus.protection!!.outbox.isEmpty())
    }

    @Test fun PC_55_displayOnlyWallExpiryRewriteKeepsTheSameFrozenElapsedScopeAndGrant() {
        val s = accept(snapshot())
        val at = now.copy(wallEpochMs = now.wallEpochMs + 86_400_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        val changed = s.copy(observedAt = at, rules = s.rules.map { it.copy(expiresAtEpochMs = at.wallEpochMs + 599_000) },
            editor = s.editor.copy(causes = s.editor.causes.mapValues { it.value.copy(interval = AnchoredInterval(at, 599_000, at.wallEpochMs + 599_000)) }))
        assertTrue(OrdinaryProtectionPolicy.covers(changed, input, emptyList(), at))
        assertEquals(s.focus, OrdinaryProtectionPolicy.retireInvalid(changed, input, emptyList()))
    }

    @Test fun PC_18_PC_19_PC_20_perOccurrenceWaitsMatureWithoutReleasingAndIncrementOnlyOnExplicitFinalCommit() {
        var s = snapshot(BreakLevel.HARDER)
        val first = request(s, input, id, now, emptyList())
        assertTrue(first.result is GrantResult.Pending)
        assertEquals(60_000L, first.harder!!.pending!!.requiredMs)
        assertTrue(first.focus.protection!!.ordinaryGrants.isEmpty())
        s = s.copy(harder = first.harder!!, observedAt = now.copy(wallEpochMs = now.wallEpochMs + 60_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 60_000))
        assertTrue(GlobalRecoveryPolicy.checkpoint(s).focus.protection!!.ordinaryGrants.isEmpty())
        val accepted = request(s, input, id, now, emptyList())
        assertTrue(accepted.result is GrantResult.Granted)
        assertEquals(1, accepted.harder!!.counts.values.single())
        val retry = request(s.copy(focus = accepted.focus, harder = accepted.harder!!), input, id, now, emptyList())
        assertTrue(retry.result is GrantResult.Granted)
        assertNull(retry.harder)
        val occurrence = accepted.harder!!.counts.keys.single()
        val afterResume = OrdinaryProtectionPolicy.resume(s.copy(focus = accepted.focus, harder = accepted.harder!!)).focus
        val next = request(s.copy(focus = afterResume, harder = accepted.harder!!), input,
            "p1-22222222-2222-4222-8222-222222222222", s.observedAt, emptyList())
        assertEquals(300_000L, next.harder!!.pending!!.requiredMs)
        val more = next.harder!!.copy(counts = mapOf(occurrence to 2), pending = null)
        assertEquals(900_000L, request(s.copy(focus = afterResume, harder = more), input,
            "p1-33333333-3333-4333-8333-333333333333", s.observedAt, emptyList()).harder!!.pending!!.requiredMs)
    }

    @Test fun PC_21_rebootCancelsWaitAndDoesNotIncrementOrProveItMatured() {
        val s = snapshot(BreakLevel.HARDER)
        val pending = request(s, input, id, now, emptyList())
        val rebooted = s.copy(harder = pending.harder!!, observedAt = AccessClockStamp(now.wallEpochMs + 60_000, 0, "other"))
        val next = request(rebooted, input, "p1-22222222-2222-4222-8222-222222222222", rebooted.observedAt, emptyList())
        assertTrue(next.result is GrantResult.Pending)
        assertEquals(60_000L, next.harder!!.pending!!.requiredMs)
        assertTrue(next.harder!!.counts.isEmpty())
    }

    @Test fun PC_26_duplicateOrDistinctOperationCannotRenewALiveIntervalAndExpiredRetryCannotRecreateIt() {
        val s = accept(snapshot())
        val later = s.copy(observedAt = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 10_000))
        val same = request(later, input, id, now, emptyList())
        val other = request(later, input, "p1-22222222-2222-4222-8222-222222222222", later.observedAt, emptyList())
        assertEquals(s.focus.protection!!.ordinaryGrants, same.focus.protection!!.ordinaryGrants)
        assertEquals(s.focus.protection!!.ordinaryGrants, other.focus.protection!!.ordinaryGrants)
        assertFalse((other.result as GrantResult.Granted).newlyAccepted)
        val expired = s.copy(observedAt = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 300_000))
        assertEquals(GrantResult.Denied, request(expired, input, id, now, emptyList()).result)
    }

    @Test fun PC_44_PC_63_PC_65_everyEndReasonPairsTheSameGrantIdAndExactRuleIdsWithItsDurableStart() {
        val s = accept(snapshot())
        val start = s.focus.protection!!.outbox.getValue("break-started:$id")
        val expired = GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 300_000))).focus
        val resumed = OrdinaryProtectionPolicy.resume(s).focus
        val superseded = OrdinaryProtectionPolicy.retireInvalid(s.copy(rules = s.rules.map { it.copy(name = "changed") }), input, emptyList())
        val preview = GlobalRecoveryPolicy.preview(s, emptyList(), "p1-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")!!
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
