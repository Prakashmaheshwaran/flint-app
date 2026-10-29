package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TimedOpenCreditPolicyTest {
    private val now = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private val input = OrdinaryProtectionInput("a", "self", false, true)
    private fun snapshot(cap: Int = 3, tier: BreakLevel = BreakLevel.HARDCORE, at: AccessClockStamp = now): ProtectionSnapshot {
        val credits = OpenCreditLedgerPolicy.initial(at, "UTC")
        val cause = EditorCause("day", AnchoredInterval(at, credits.reset.remainingToResetMs, at.wallEpochMs + credits.reset.remainingToResetMs), "UTC")
        return ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(at, at)), emptyList(), emptyList(), listOf(OpenLimit("a", cap, tier)),
            EditorCommitmentState(causes = mapOf("open:a" to cause), timeline = at), HarderWaitPolicy.Ledger(), at, credits)
    }
    private fun id(s: ProtectionSnapshot, index: Int = 1) = GrantRequestId(s.observedAt.wallEpochMs,
        "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}", s.observedAt,
        TimedOpenCreditPolicy.view(s, input, emptyList(), s.observedAt)!!.requestScope)
    private fun request(s: ProtectionSnapshot, id: GrantRequestId = id(s), target: OrdinaryProtectionInput = input, floor: Boolean = false) =
        TimedOpenCreditPolicy.request(s, target, id, emptyList(), floor)
    private fun apply(s: ProtectionSnapshot, m: ProtectionMutation<*>): ProtectionSnapshot = s.copy(focus = m.focus, openCredits = m.openCredits ?: s.openCredits)
    private fun at(s: ProtectionSnapshot, elapsed: Long): ProtectionSnapshot {
        val stamp = now.copy(wallEpochMs = now.wallEpochMs + elapsed, elapsedRealtimeMs = now.elapsedRealtimeMs + elapsed)
        val credits = OpenCreditLedgerPolicy.reconcile(s.openCredits!!, stamp).next
        return GlobalRecoveryPolicy.checkpoint(s.copy(observedAt = stamp, openCredits = credits))
    }

    @Test fun PC_28_PC_30_threeExplicitCreditsUseExactlyThreeChargesAndTheLastRemainsLiveUntilExpiry() {
        var s = snapshot()
        for (index in 1..3) {
            val m = request(s, id(s, index)); assertEquals(GrantResult.OpenAllowed, m.result); s = apply(s, m)
            assertEquals(index, s.openCredits!!.counters.getValue("a").used)
            val view = TimedOpenCreditPolicy.view(s, input, emptyList(), s.observedAt)!!
            assertEquals(3 - index, view.remainingCredits); assertEquals(300_000L, view.activeRemainingMs)
            s = at(s, index * 300_000L)
        }
        assertEquals(GrantResult.Denied, request(s, id(s, 4)).result)
        assertEquals(3, s.openCredits!!.counters.getValue("a").used)
        assertEquals(3, s.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.ACCESS_STARTED })
    }

    @Test fun PC_23_PC_29_PC_31_duplicateAndDistinctActiveRequestsKeepTheOriginalDeadlineAndOneEvent() {
        val before = snapshot(cap = 1); val originalId = id(before)
        val s = at(apply(before, request(before, originalId)), 10_000)
        for (retry in listOf(originalId, id(s, 2))) {
            val m = request(s, retry)
            assertEquals(GrantResult.OpenAllowed, m.result)
            assertEquals(s.openCredits!!.active, m.openCredits!!.active)
            assertEquals(1, m.openCredits!!.counters.getValue("a").used)
            assertEquals(1, m.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.ACCESS_STARTED })
        }
        assertEquals(GrantResult.Denied, request(at(s, 300_000), originalId).result)
    }

    @Test fun PC_32_PC_33_newIndependentCauseBlocksEvenWhenQuotaPolicyReturnsAlreadyActiveOrDuplicate() {
        val original = snapshot(); val operation = id(original)
        val accepted = apply(original, request(original, operation))
        val rule = BlockRule("other", "Evening focus", BlockTargets(apps = setOf(AppRef("a"))), breakLevel = BreakLevel.HARDCORE)
        val s = accepted.copy(rules = listOf(rule), editor = accepted.editor.copy(causes = accepted.editor.causes +
            ("rule:other" to EditorCause("other", null, "UTC"))))
        assertEquals(listOf("Evening focus"), TimedOpenCreditPolicy.view(s, input, emptyList(), now)!!.blockingContributors)
        for (retry in listOf(operation, id(s, 2))) {
            val m = request(s, retry)
            assertEquals(GrantResult.Denied, m.result)
            assertEquals(1, m.openCredits!!.counters.getValue("a").used)
            assertEquals(accepted.openCredits!!.active, m.openCredits!!.active)
        }
    }

    @Test fun PC_32_timeLimitOrSessionPresentBeforeAcceptanceNamesTheBlockAndNeverCharges() {
        val original = snapshot()
        val s = original.copy(times = listOf(TimeLimit("a", 5, BreakLevel.EASY)), editor = original.editor.copy(causes = original.editor.causes +
            ("time:a" to EditorCause("day", null, "UTC"))))
        val m = request(s, id(s), input.copy(timeSpent = true))
        assertEquals(GrantResult.Denied, m.result)
        assertTrue(m.openCredits!!.counters.isEmpty())
        assertTrue(m.focus.protection!!.outbox.isEmpty())
        assertEquals(listOf("Time limit"), TimedOpenCreditPolicy.view(s, input.copy(timeSpent = true), emptyList(), now)!!.blockingContributors)
    }

    @Test fun PC_34_PC_36_minimumCapSharesOneCounterAndEditingOrRecreatingTheLimitNeverRefillsIt() {
        var s = snapshot(cap = 5).let { it.copy(opens = it.opens + OpenLimit("a", 2, BreakLevel.EASY)) }
        s = apply(s, request(s)); assertEquals(1, TimedOpenCreditPolicy.view(s, input, emptyList(), now)!!.remainingCredits)
        val live = s.openCredits!!.active
        val lowered = s.copy(opens = listOf(OpenLimit("a", 0, BreakLevel.HARDCORE)))
        assertEquals(live, lowered.openCredits!!.active)
        assertEquals(300_000L, TimedOpenCreditPolicy.view(lowered, input, emptyList(), now)!!.activeRemainingMs)
        s = at(s.copy(opens = emptyList()), 300_000).copy(opens = listOf(OpenLimit("a", 2)))
        assertEquals(1, TimedOpenCreditPolicy.view(s, input, emptyList(), s.observedAt)!!.remainingCredits)
        assertEquals(1, s.openCredits!!.counters.getValue("a").used)
    }

    @Test fun PC_32_PC_33_browserWithUnknownAddressDeniesUnprovableWebsiteOverlapButProvenDisjointTargetCanUseCredit() {
        val domain = BlockRule("web", "Website focus", BlockTargets(domains = setOf(DomainRef("example.com"))))
        val original = snapshot()
        val browser = "com.android.chrome"
        val s = original.copy(opens = listOf(OpenLimit(browser, 3), OpenLimit("a", 3)), editor = original.editor.copy(causes = original.editor.causes +
            ("open:$browser" to original.editor.causes.getValue("open:a")) + ("rule:web" to EditorCause("web", null, "UTC"))), rules = listOf(domain))
        val target = input.copy(packageName = browser)
        val view = TimedOpenCreditPolicy.view(s, target, emptyList(), now)!!
        assertTrue(view.blockingContributors.single().contains("Website focus"))
        val id = id(s).copy(creditScope = view.requestScope)
        val denied = request(s, id, target)
        assertEquals(GrantResult.Denied, denied.result); assertTrue(denied.openCredits!!.counters.isEmpty())
        val disjoint = target.copy(url = "https://elsewhere.example/")
        assertEquals(GrantResult.OpenAllowed, request(s, id, disjoint).result)
        assertEquals(GrantResult.OpenAllowed, request(s).result)
    }

    @Test fun PC_34_exhaustedOverlappingOpenRulesUseTheStrongestTierForOrdinaryExceptions() {
        val s = snapshot().let { it.copy(opens = listOf(OpenLimit("a", 2, BreakLevel.EASY), OpenLimit("a", 5, BreakLevel.HARDCORE))) }
        val scopes = OrdinaryProtectionPolicy.frozenScopes(s, input, emptyList(), now)!!
        assertEquals(GrantResult.Denied, OrdinaryProtectionPolicy.request(s, input,
            "p1-11111111-1111-4111-8111-111111111111", now, emptyList(), scopes).result)
    }

    @Test fun PC_35_midnightClipsAccessClosesBeforeOneResetAndAnOldReviewedActionCannotSpendNewQuota() {
        val midnight = AccessClockStamp(java.time.Instant.parse("2026-10-28T23:58:00Z").toEpochMilli(), 10_000, "boot")
        val original = snapshot(at = midnight); val stale = id(original, 2)
        val accepted = apply(original, request(original))
        assertEquals(120_000L, accepted.openCredits!!.active.getValue("a").grant.durationMs)
        val boundary = midnight.copy(wallEpochMs = midnight.wallEpochMs + 120_000, elapsedRealtimeMs = midnight.elapsedRealtimeMs + 120_000)
        val rolled = OpenCreditLedgerPolicy.reconcile(accepted.openCredits!!, boundary).next
        val nextCause = EditorCause("day-next", AnchoredInterval(boundary, rolled.reset.remainingToResetMs,
            boundary.wallEpochMs + rolled.reset.remainingToResetMs), "UTC")
        val s = GlobalRecoveryPolicy.checkpoint(accepted.copy(openCredits = rolled, observedAt = boundary,
            editor = accepted.editor.copy(causes = mapOf("open:a" to nextCause))))
        assertTrue(rolled.active.isEmpty()); assertEquals(0, rolled.counters.getValue("a").used)
        assertEquals(original.openCredits!!.periodId + 1, rolled.periodId)
        assertEquals(GrantResult.Denied, request(s, stale).result)
        assertEquals(ProtectionBreakEndReason.EXPIRED, s.focus.protection!!.outbox.values.single { it.kind == ProtectionLifecycleKind.ACCESS_ENDED }.breakEndReason)
    }

    @Test fun PC_37_missingStateFloorAndStaleOrWrongReviewedScopeNeverSpend() {
        val s = snapshot()
        assertTrue(request(s.copy(openCredits = null), id(s)).result is GrantResult.Unavailable)
        assertEquals(GrantResult.Denied, request(s, id(s), floor = true).result)
        assertEquals(GrantResult.Denied, request(s, id(s).copy(creditScope = "wrong")).result)
        assertEquals(GrantResult.Denied, request(s, id(s).copy(issuedAtClock = now.copy(bootSessionId = "other"))).result)
        assertTrue(s.openCredits!!.counters.isEmpty())
    }

    @Test fun PC_54_PC_59_wallEditsPreserveElapsedAccessButRebootClosesItWithoutRefundAndWithNotice() {
        val accepted = apply(snapshot(), request(snapshot()))
        val jumped = now.copy(wallEpochMs = now.wallEpochMs + 86_400_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        assertEquals(299_000L, TimedOpenCreditPolicy.view(accepted, input, emptyList(), jumped)!!.activeRemainingMs)
        val reboot = AccessClockStamp(jumped.wallEpochMs, 0, "other")
        val credits = OpenCreditLedgerPolicy.reconcile(accepted.openCredits!!, reboot).next
        val s = GlobalRecoveryPolicy.checkpoint(accepted.copy(openCredits = credits, observedAt = reboot))
        assertTrue(credits.active.isEmpty()); assertEquals(1, credits.counters.getValue("a").used)
        assertTrue(s.focus.protection!!.previousAccessEnded)
        assertEquals(accepted.openCredits!!.reset.remainingToResetMs, credits.reset.remainingToResetMs)
    }

    @Test fun PC_44_PC_63_PC_65_accessEventsStayDistinctFromBreaksAndPairExpiryResumeAndRecoveryWithoutTargets() {
        val accepted = apply(snapshot(), request(snapshot()))
        val start = accepted.focus.protection!!.outbox.values.single()
        assertEquals(ProtectionLifecycleKind.ACCESS_STARTED, start.kind)
        val preview = GlobalRecoveryPolicy.preview(accepted, emptyList(), "p1-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")!!
        val recovery = GlobalRecoveryPolicy.consume(accepted, preview, emptyList())
        for ((s, reason) in listOf(at(accepted, 300_000) to ProtectionBreakEndReason.EXPIRED,
            apply(accepted, TimedOpenCreditPolicy.resume(accepted)) to ProtectionBreakEndReason.RESUMED,
            apply(accepted, recovery) to ProtectionBreakEndReason.RECOVERY)) {
            val end = s.focus.protection!!.outbox.values.single { it.kind == ProtectionLifecycleKind.ACCESS_ENDED }
            assertEquals(start.grantId, end.grantId); assertEquals(reason, end.breakEndReason)
            assertTrue(end.affectedRuleIds.isEmpty()); assertTrue(s.focus.protection!!.pendingCreditEnds.isEmpty())
            assertTrue(s.openCredits!!.active.isEmpty()); assertEquals(1, s.openCredits!!.counters.getValue("a").used)
        }
    }
}
