package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TemporaryAccessPolicyTest {
    private val policy = TemporaryAccessPolicy()
    private val now = AccessClockStamp(1_000_000L, 10_000L, "boot-1")
    private val target = AccessTarget.App("com.example.app")
    private val scope = scope("one")
    private fun scope(id: String) = AccessCauseScope(
        AccessCounterKey(AccessRuleKey(RuleAuthority.Personal, "datastore-rule", id), "occurrence-1"), "revision-1",
    )
    private fun request(
        mode: ResistanceMode = ResistanceMode.STANDARD,
        scopes: Set<AccessCauseScope> = setOf(scope),
        target: AccessTarget = this.target,
    ) = ValidatedAccessRequest(target, scopes.associateWith { OrdinaryAccessPolicy.Allowed(mode, 300_000L) }, 60_000L)
    private fun after(ms: Long) = now.copy(wallEpochMs = now.wallEpochMs + ms, elapsedRealtimeMs = now.elapsedRealtimeMs + ms)
    private fun armed(request: ValidatedAccessRequest = request(), ledger: AccessLedgerV2 = AccessLedgerV2()) =
        policy.begin(ledger, request, now).next
    private fun granted(request: ValidatedAccessRequest = request()) =
        policy.complete(armed(request), 1L, request, after(120_000L)).next

    @Test fun exactResistanceScheduleAndOverflowBoundaries() {
        for (count in listOf(-1L, 0L, 1L, Long.MAX_VALUE)) {
            assertEquals(5_000L, policy.resistanceDelayMs(ResistanceMode.STANDARD, count))
        }
        assertEquals(listOf(20_000L, 40_000L, 60_000L, 80_000L, 100_000L, 120_000L, 120_000L),
            (0L..6L).map { policy.resistanceDelayMs(ResistanceMode.INCREASING, it) })
        assertEquals(20_000L, policy.resistanceDelayMs(ResistanceMode.INCREASING, Long.MIN_VALUE))
        assertEquals(120_000L, policy.resistanceDelayMs(ResistanceMode.INCREASING, Long.MAX_VALUE))
    }

    @Test fun standardWaitBeginsWithoutSpendingAndCompletesAtExactBoundary() {
        val begin = policy.begin(AccessLedgerV2(), request(), now)
        assertTrue(begin.result is AccessRequestResult.Pending)
        assertEquals(1L, begin.next.revision)
        assertEquals(2L, begin.next.nextSequence)
        assertTrue(begin.next.ordinaryUnblocks.isEmpty())
        assertTrue(policy.complete(begin.next, 1, request(), after(4_999)).result is AccessRequestResult.Pending)
        val done = policy.complete(begin.next, 1, request(), after(5_000))
        assertTrue(done.result is AccessRequestResult.Granted)
        assertEquals(1L, done.next.ordinaryUnblocks[scope.counter])
        assertTrue(done.next.pending.isEmpty())
        assertEquals(after(5_000), done.next.grants.getValue(1).lifetime.startedAt)
    }

    @Test fun duplicateBeginAndCompletionAreIdempotent() {
        val pending = armed()
        assertEquals(pending, policy.begin(pending, request(), after(1)).next)
        val done = policy.complete(pending, 1, request(), after(5_000))
        val duplicate = policy.complete(done.next, 1, request(), after(5_001))
        assertTrue(duplicate.result is AccessRequestResult.AlreadyGranted)
        assertEquals(done.next, duplicate.next)
        assertTrue(policy.begin(done.next, request(), after(5_002)).result is AccessRequestResult.AlreadyGranted)
    }

    @Test fun expiredOrCancelledSequenceNeverGrantsAgain() {
        val pending = armed()
        val cancelled = policy.cancel(pending, 1)
        assertTrue(cancelled.result)
        assertTrue(cancelled.next.ordinaryUnblocks.isEmpty())
        assertEquals(AccessRequestResult.StaleRequest, policy.complete(cancelled.next, 1, request(), after(5_000)).result)
        assertFalse(policy.cancel(cancelled.next, 1).result)
        val done = policy.complete(pending, 1, request(), after(5_000)).next
        val expired = policy.complete(done, 1, request(), after(65_000))
        assertEquals(AccessRequestResult.StaleRequest, expired.result)
        assertEquals(1L, expired.next.ordinaryUnblocks[scope.counter])
        assertTrue(expired.next.grants.isEmpty())
    }

    @Test fun overlappingRulesUseMaximumCountAndDelayAndSpendEachCounterOnce() {
        val other = scope("two")
        val current = AccessLedgerV2(ordinaryUnblocks = mapOf(scope.counter to 1, other.counter to 4))
        val request = request(ResistanceMode.INCREASING, setOf(scope, other)).copy(policies = mapOf(
            scope to OrdinaryAccessPolicy.Allowed(ResistanceMode.STANDARD, 300_000),
            other to OrdinaryAccessPolicy.Allowed(ResistanceMode.INCREASING, 300_000),
        ))
        val pending = armed(request, current)
        assertEquals(100_000L, pending.pending.getValue(1).wait.durationMs)
        val done = policy.complete(pending, 1, request, after(100_000)).next
        assertEquals(2L, done.ordinaryUnblocks[scope.counter])
        assertEquals(5L, done.ordinaryUnblocks[other.counter])
    }

    @Test fun anotherTargetCannotSpendSharedCounterWithAnOldShortWait() {
        val first = request(ResistanceMode.INCREASING)
        val second = request(ResistanceMode.INCREASING, target = AccessTarget.App("com.example.other"))
        val both = policy.begin(armed(first), second, now).next
        val once = policy.complete(both, 1, first, after(20_000)).next
        val secondEarly = policy.complete(once, 2, second, after(20_000))
        assertTrue(secondEarly.result is AccessRequestResult.Pending)
        assertEquals(40_000L, secondEarly.next.pending.getValue(2).wait.durationMs)
        assertEquals(2L, policy.complete(secondEarly.next, 2, second, after(40_000)).next.ordinaryUnblocks[scope.counter])
    }

    @Test fun strictAndManagedCausesDenyWithoutSpendingOrWaiting() {
        val forbidden = request().copy(policies = mapOf(scope to OrdinaryAccessPolicy.Forbidden))
        assertEquals(AccessRequestResult.Denied(AccessDenial.FORBIDDEN),
            policy.begin(AccessLedgerV2(), forbidden, now).result)
        assertEquals(AccessRequestResult.Denied(AccessDenial.FORBIDDEN),
            policy.complete(armed(), 1, forbidden, after(5_000)).result)
        val managed = scope.copy(counter = scope.counter.copy(rule = scope.counter.rule.copy(
            authority = RuleAuthority.Managed("project", "user", "group", "membership"))))
        val mixed = request(scopes = setOf(scope, managed))
        assertEquals(AccessRequestResult.Denied(AccessDenial.MANAGED_AUTHORITY),
            policy.begin(AccessLedgerV2(), mixed, now).result)
        assertEquals(AccessRequestResult.Denied(AccessDenial.MANAGED_AUTHORITY),
            policy.complete(armed(), 1, mixed, after(5_000)).result)
    }

    @Test fun changedRevisionAddedCauseChangedTargetAndDurationRejectStaleAction() {
        val pending = armed()
        val changed = listOf(
            request(scopes = setOf(scope.copy(definitionRevision = "revision-2"))),
            request(scopes = setOf(scope, scope("new"))),
            request(target = AccessTarget.App("different")),
            request().copy(requestedDurationMs = 1_000),
        )
        changed.forEach {
            assertEquals(AccessRequestResult.StaleRequest, policy.complete(pending, 1, it, after(120_000)).result)
        }
        assertTrue(pending.ordinaryUnblocks.isEmpty())
    }

    @Test fun invalidOrOverlongRequestIsDeniedAndDoesNotMintState() {
        val invalid = listOf(request().copy(requestedDurationMs = 0), request().copy(requestedDurationMs = 300_001),
            request().copy(policies = emptyMap()), request().copy(target = AccessTarget.Website("browser", "HTTPS://bad")))
        invalid.forEach {
            val result = policy.begin(AccessLedgerV2(), it, now)
            assertEquals(AccessLedgerV2(), result.next)
            assertEquals(AccessRequestResult.Denied(AccessDenial.INVALID_REQUEST), result.result)
        }
    }

    @Test fun processRestartOnSameBootKeepsExactPendingAndGrantDeadlines() {
        val pending = armed()
        assertEquals(pending, TemporaryAccessPolicy().reconcileClock(pending, after(1_000)).next)
        val done = policy.complete(pending, 1, request(), after(5_000)).next
        val restored = TemporaryAccessPolicy().reconcileClock(done, after(20_000)).next
        assertEquals(done, restored)
        assertEquals(45_000L, restored.grants.getValue(1).lifetime.remainingMs(after(20_000)))
    }

    @Test fun wallAndTimezoneChangesDoNotChangeMonotonicDuration() {
        val pending = armed()
        val jumpedForward = after(4_999).copy(wallEpochMs = Long.MAX_VALUE)
        assertTrue(policy.complete(pending, 1, request(), jumpedForward).result is AccessRequestResult.Pending)
        val jumpedBack = after(5_000).copy(wallEpochMs = 1)
        assertTrue(policy.complete(pending, 1, request(), jumpedBack).result is AccessRequestResult.Granted)
        val grant = granted().grants.getValue(1)
        val stillActive = after(120_001).copy(wallEpochMs = 1)
        assertEquals(59_999L, grant.lifetime.remainingMs(stillActive))
    }

    @Test fun rebootEvenWithLargerElapsedValueRevokesGrantsAndRestartsPendingWithoutResettingCounts() {
        val first = request()
        val other = request(target = AccessTarget.App("another"))
        val done = policy.complete(armed(first), 1, first, after(5_000)).next
        val withPending = policy.begin(done, other, after(6_000)).next
        val newBoot = after(8_000).copy(bootSessionId = "boot-2")
        val reconciled = policy.reconcileClock(withPending, newBoot).next
        assertTrue(reconciled.grants.isEmpty())
        assertEquals(1L, reconciled.ordinaryUnblocks[scope.counter])
        assertEquals(newBoot, reconciled.pending.getValue(2).wait.startedAt)
        assertEquals(5_000L, reconciled.pending.getValue(2).wait.remainingMs(newBoot))
        assertTrue(policy.complete(reconciled, 2, other, newBoot).result is AccessRequestResult.Pending)
    }

    @Test fun elapsedClockRegressionAlsoRevokesAndRestartsConservatively() {
        val regressed = now.copy(elapsedRealtimeMs = 1)
        assertEquals(regressed, policy.reconcileClock(armed(), regressed).next.pending.getValue(1).wait.startedAt)
        assertTrue(policy.reconcileClock(granted(), regressed).next.grants.isEmpty())
    }

    @Test fun matchingIsExactAndNeverCoversStrictManagedOrReservedEmergency() {
        val website = AccessTarget.Website("browser", "example.com")
        val grant = granted(request(target = website)).grants.getValue(1)
        val allowed = OrdinaryAccessPolicy.Allowed(ResistanceMode.STANDARD, 300_000)
        val check = after(120_001)
        assertTrue(policy.covers(grant, website, scope, allowed, check))
        assertFalse(policy.covers(grant, AccessTarget.App("browser"), scope, allowed, check))
        assertFalse(policy.covers(grant, website.copy(normalizedHost = "other.example"), scope, allowed, check))
        assertFalse(policy.covers(grant, website, scope.copy(definitionRevision = "changed"), allowed, check))
        assertFalse(policy.covers(grant, website, scope, OrdinaryAccessPolicy.Forbidden, check))
        assertFalse(policy.covers(grant, website, scope, allowed.copy(maximumDurationMs = 1_000), check))
        assertFalse(policy.covers(grant.copy(kind = PersonalGrantKind.EMERGENCY_PASS), website, scope, allowed, check))
        assertFalse(policy.covers(grant, website, scope, allowed, after(180_000)))
        val managed = scope.copy(counter = scope.counter.copy(rule = scope.counter.rule.copy(
            authority = RuleAuthority.Managed("p", "u", "g", "m"))))
        assertFalse(policy.covers(grant.copy(scopes = setOf(managed)), website, managed, allowed, check))
    }

    @Test fun sequenceRevisionAndCountOverflowNeverWrap() {
        assertEquals(AccessRequestResult.Denied(AccessDenial.CAPACITY),
            policy.begin(AccessLedgerV2(nextSequence = Long.MAX_VALUE), request(), now).result)
        assertEquals(AccessRequestResult.Denied(AccessDenial.CAPACITY),
            policy.begin(AccessLedgerV2(revision = Long.MAX_VALUE), request(), now).result)
        val pending = armed(ledger = AccessLedgerV2(ordinaryUnblocks = mapOf(scope.counter to Long.MAX_VALUE)))
        val done = policy.complete(pending, 1, request(), after(120_000))
        assertEquals(AccessRequestResult.Denied(AccessDenial.CAPACITY), done.result)
        assertTrue(done.next.grants.isEmpty())
        assertEquals(Long.MAX_VALUE, done.next.ordinaryUnblocks[scope.counter])
    }
}
