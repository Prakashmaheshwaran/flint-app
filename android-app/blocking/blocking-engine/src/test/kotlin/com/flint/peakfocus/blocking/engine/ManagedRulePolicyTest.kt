package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ManagedRulePolicyTest {
    private val policy = ManagedRulePolicy()
    private val identity = ManagedIdentity("project", "member", 4)
    private val member = ManagedMembership("group", "membership-one", ManagedRole.MEMBER)
    private val app = "com.example.social"
    private val self = "com.flint.peakfocus"
    private val now = epoch("2026-10-12T13:30:00Z") // Monday 09:30 in New York

    private fun epoch(value: String) = Instant.parse(value).toEpochMilli()

    private fun rule(
        id: String = "one",
        schedule: ManagedSchedule = ManagedSchedule.Session(now - 1_000, now + 60_000),
        targets: ManagedTargets = ManagedTargets.Selection(setOf(app), emptySet()),
        zone: String = "America/New_York",
    ) = ManagedRule(id, member.groupId, 2, "Study time", zone, true, ManagedRuleScope.Group,
        schedule, targets, false, now - 60_000, now - 30_000)

    private fun snapshot(vararg rules: ManagedRule) = ManagedSnapshot(identity, revision = 10,
        serverTimeEpochMs = now, memberships = listOf(member), rules = rules.toList(), tombstones = emptyList())

    private fun evaluate(
        rules: ManagedSnapshot = snapshot(rule()),
        at: Long = now,
        foreground: ManagedForeground = ManagedForeground(app),
        currentIdentity: ManagedIdentity = identity,
        clock: ManagedClock = ManagedClock.Confirmed(at),
        safety: Set<String> = emptySet(),
    ) = policy.evaluate(rules, currentIdentity, foreground, self, clock, safety)

    private fun ready(
        rules: ManagedSnapshot = snapshot(rule()),
        at: Long = now,
        foreground: ManagedForeground = ManagedForeground(app),
        safety: Set<String> = emptySet(),
    ): ManagedEvaluation.Ready {
        val result = evaluate(rules, at, foreground, safety = safety)
        assertTrue("Expected Ready, got $result", result is ManagedEvaluation.Ready)
        return result as ManagedEvaluation.Ready
    }

    private fun unavailable(result: ManagedEvaluation, reason: ManagedUnavailableReason) {
        assertEquals(ManagedEvaluation.Unavailable(reason), result)
    }

    @Test fun sessionUsesExactHalfOpenInstantsAndArmsItsStartAndEnd() {
        val rules = snapshot(rule(schedule = ManagedSchedule.Session(now, now + 1_000)))
        assertTrue(ready(rules, now - 1).causes.isEmpty())
        assertEquals(now, ready(rules, now - 1).nextRecheckEpochMs)
        assertEquals(1, ready(rules, now).causes.size)
        assertEquals(now + 1_000, ready(rules, now).nextRecheckEpochMs)
        assertEquals(1, ready(rules, now + 999).causes.size)
        assertTrue(ready(rules, now + 1_000).causes.isEmpty())
        assertNull(ready(rules, now + 1_000).nextRecheckEpochMs)
    }

    @Test fun disabledRulesHaveNoCauseOrTimer() {
        val result = ready(snapshot(rule().copy(enabled = false)))
        assertTrue(result.causes.isEmpty())
        assertNull(result.nextRecheckEpochMs)
    }

    @Test fun allOverlappingRulesSurviveWithExactAuthorityAndOccurrence() {
        val one = rule()
        val two = rule("two", ManagedSchedule.Session(now - 500, now + 30_000)).copy(revision = 3)
        val result = ready(snapshot(one, two))
        assertEquals(listOf("one", "two"), result.causes.map { it.scope.ruleId })
        assertEquals(now + 30_000, result.nextRecheckEpochMs)
        assertTrue(result.causes.all { it.identity == identity })
        assertEquals(RuleAuthority.Managed("project", "member", "group", "membership-one"), result.causes[0].scope.authority)
        assertEquals(ManagedOccurrence(now - 500, now + 30_000), result.causes[1].scope.occurrence)
        assertEquals(3L, result.causes[1].scope.ruleRevision)
    }

    @Test fun anotherGroupAndRejoinedMembershipProduceDifferentAuthority() {
        val first = ready().causes.single()
        val rejoined = ready(snapshot(rule()).copy(memberships = listOf(member.copy(membershipId = "membership-two"))))
            .causes.single()
        assertNotEquals(first.scope.authority, rejoined.scope.authority)
        assertNotEquals(first.accessScope, rejoined.accessScope)
        val other = member.copy(groupId = "other", membershipId = "other-membership")
        val result = ready(snapshot(rule(), rule("two").copy(targetGroupId = "other"))
            .copy(memberships = listOf(member, other)))
        assertEquals(setOf("group", "other"), result.causes.map { it.scope.authority.groupId }.toSet())
    }

    @Test fun changedRevisionAndOccurrenceChangeAccessIdentity() {
        val first = ready().causes.single()
        val edited = ready(snapshot(rule().copy(revision = 3))).causes.single()
        val retimed = ready(snapshot(rule(schedule = ManagedSchedule.Session(now - 2_000, now + 60_000)))).causes.single()
        assertNotEquals(first.accessScope, edited.accessScope)
        assertNotEquals(first.accessScope, retimed.accessScope)
    }

    @Test fun unblocksAllowedNeverAuthorizesPersonalOrdinaryOrEmergencyAccess() {
        val cause = ready(snapshot(rule().copy(unblocksAllowed = true))).causes.single()
        assertTrue(cause.unblocksAllowed)
        assertEquals(OrdinaryAccessPolicy.Forbidden, cause.ordinaryAccessPolicy)
        assertFalse(cause.personalEmergencyPassAllowed)
        val stamp = AccessClockStamp(now, 10_000, "boot")
        val accessPolicy = TemporaryAccessPolicy()
        for (kind in PersonalGrantKind.entries) {
            val grant = TemporaryAccess(1, cause.target, setOf(cause.accessScope), kind, AccessDuration(stamp, 60_000))
            // Even a malformed caller-provided grant and permissive personal policy cannot cover managed authority.
            assertFalse(accessPolicy.covers(grant, cause.target, cause.accessScope,
                OrdinaryAccessPolicy.Allowed(ResistanceMode.STANDARD, 60_000), stamp))
        }
        val request = ValidatedAccessRequest(cause.target, mapOf(cause.accessScope to cause.ordinaryAccessPolicy), 60_000)
        assertEquals(AccessRequestResult.Denied(AccessDenial.MANAGED_AUTHORITY),
            accessPolicy.begin(AccessLedgerV2(), request, stamp).result)
    }

    @Test fun changedAccountProjectOrGenerationCannotReuseSnapshot() {
        for (changed in listOf(identity.copy(accountUid = "other"), identity.copy(projectId = "other"),
            identity.copy(sessionGeneration = identity.sessionGeneration + 1))) {
            unavailable(evaluate(currentIdentity = changed), ManagedUnavailableReason.IDENTITY_MISMATCH)
        }
    }

    @Test fun unconfirmedClockNeverUsesSnapshotServerTimeOrDeviceWallClock() {
        unavailable(evaluate(clock = ManagedClock.Unconfirmed("device rebooted")), ManagedUnavailableReason.UNCONFIRMED_CLOCK)
        unavailable(evaluate(snapshot().copy(serverTimeEpochMs = now), clock = ManagedClock.Unconfirmed("no anchor")),
            ManagedUnavailableReason.UNCONFIRMED_CLOCK)
    }

    @Test fun completeEmptySnapshotIsReadyAndDoesNotRetainOldRules() {
        assertEquals(1, ready().causes.size)
        val replacement = ready(snapshot())
        assertTrue(replacement.causes.isEmpty())
        assertNull(replacement.nextRecheckEpochMs)
    }

    @Test fun memberScopeMustMatchAuthenticatedActorAndMembershipMustBeMember() {
        assertEquals(1, ready(snapshot(rule().copy(scope = ManagedRuleScope.Member(identity.accountUid)))).causes.size)
        unavailable(evaluate(snapshot(rule().copy(scope = ManagedRuleScope.Member("other")))), ManagedUnavailableReason.INVALID_SNAPSHOT)
        unavailable(evaluate(snapshot(rule()).copy(memberships = listOf(member.copy(role = ManagedRole.OWNER)))),
            ManagedUnavailableReason.INVALID_SNAPSHOT)
        unavailable(evaluate(snapshot(rule()).copy(memberships = emptyList())), ManagedUnavailableReason.INVALID_SNAPSHOT)
        assertTrue(ready(snapshot().copy(memberships = listOf(member.copy(role = ManagedRole.OWNER)))).causes.isEmpty())
    }

    @Test fun allAppsPreservesSelfStaticEmergencyAndDynamicSafetySurfaces() {
        val rules = snapshot(rule(targets = ManagedTargets.AllApps))
        val dynamic = setOf("com.vendor.home", "com.vendor.dialer")
        for (pkg in SYSTEM_SURFACE_PACKAGES + dynamic + self + "") {
            val result = ready(rules, foreground = ManagedForeground(pkg), safety = dynamic)
            assertTrue("Shielded safety surface $pkg", result.causes.isEmpty())
            assertNull(result.nextRecheckEpochMs)
        }
        assertEquals(1, ready(rules, foreground = ManagedForeground("com.other.application"), safety = dynamic).causes.size)
    }

    @Test fun explicitlySelectedSafetyPackagesAreStillNeverBlocked() {
        val pkg = "com.android.dialer"
        val rules = snapshot(rule(targets = ManagedTargets.Selection(setOf(pkg), emptySet())))
        assertTrue(ready(rules, foreground = ManagedForeground(pkg)).causes.isEmpty())
        val home = "com.vendor.home"
        assertTrue(ready(snapshot(rule(targets = ManagedTargets.Selection(setOf(home), emptySet()))),
            foreground = ManagedForeground(home), safety = setOf(home)).causes.isEmpty())
    }

    @Test fun explicitAppSelectionDoesNotBorrowAnyPersonalTargets() {
        assertTrue(ready(foreground = ManagedForeground("com.other.application")).causes.isEmpty())
        assertEquals(AccessTarget.App(app), ready().causes.single().target)
    }

    @Test fun domainMatchesExactHostOrSubdomainButNotSuffixLookalike() {
        val rules = snapshot(rule(targets = ManagedTargets.Selection(emptySet(), setOf("example.com"))))
        val browser = "com.example.browser"
        for (host in listOf("example.com", "m.example.com", "WWW.EXAMPLE.COM.")) {
            val result = ready(rules, foreground = ManagedForeground(browser, host))
            assertEquals(1, result.causes.size)
            assertEquals(AccessTarget.Website(browser, host.lowercase().removeSuffix(".")), result.causes.single().target)
            assertFalse(result.domainObservationUnavailable)
        }
        for (host in listOf("notexample.com", "example.com.evil.com")) {
            val result = ready(rules, foreground = ManagedForeground(browser, host))
            assertTrue(result.causes.isEmpty())
            assertFalse(result.domainObservationUnavailable)
        }
    }

    @Test fun selectedSubdomainNeverBroadensToParentOrSibling() {
        val rules = snapshot(rule(targets = ManagedTargets.Selection(emptySet(), setOf("www.example.com"))))
        assertTrue(ready(rules, foreground = ManagedForeground(app, "example.com")).causes.isEmpty())
        assertTrue(ready(rules, foreground = ManagedForeground(app, "m.example.com")).causes.isEmpty())
        assertEquals(1, ready(rules, foreground = ManagedForeground(app, "www.example.com")).causes.size)
    }

    @Test fun unavailableOrMalformedHostDoesNotBlockWholeBrowser() {
        val rules = snapshot(rule(targets = ManagedTargets.Selection(emptySet(), setOf("example.com"))))
        for (host in listOf(null, "search words", "https://example.com/path", "[::1]")) {
            val result = ready(rules, foreground = ManagedForeground(app, host))
            assertTrue(result.causes.isEmpty())
            assertTrue(result.domainObservationUnavailable)
        }
        val appAndDomain = rule(targets = ManagedTargets.Selection(setOf(app), setOf("example.com")))
        assertEquals(AccessTarget.App(app), ready(snapshot(appAndDomain)).causes.single().target)
        assertFalse(ready(snapshot(appAndDomain)).domainObservationUnavailable)
    }

    @Test fun recurringWindowUsesExplicitZoneAndStartWeekday() {
        val recurring = rule(schedule = ManagedSchedule.Recurring(9 * 60, 10 * 60, setOf(1)))
        val result = ready(snapshot(recurring))
        assertEquals(ManagedOccurrence(epoch("2026-10-12T13:00:00Z"), epoch("2026-10-12T14:00:00Z")),
            result.causes.single().scope.occurrence)
        assertEquals(epoch("2026-10-12T14:00:00Z"), result.nextRecheckEpochMs)
        assertTrue(ready(snapshot(recurring), epoch("2026-10-13T13:30:00Z")).causes.isEmpty())
        assertEquals(epoch("2026-10-19T13:00:00Z"), ready(snapshot(recurring), epoch("2026-10-12T14:00:00Z")).nextRecheckEpochMs)
    }

    @Test fun overnightTailBelongsToPreviousStartDayAcrossWeekBoundary() {
        val recurring = rule(schedule = ManagedSchedule.Recurring(22 * 60, 6 * 60, setOf(7)), zone = "UTC")
        val result = ready(snapshot(recurring), epoch("2026-10-12T01:00:00Z"))
        assertEquals(ManagedOccurrence(epoch("2026-10-11T22:00:00Z"), epoch("2026-10-12T06:00:00Z")),
            result.causes.single().scope.occurrence)
        assertTrue(ready(snapshot(recurring), epoch("2026-10-11T01:00:00Z")).causes.isEmpty())
        assertTrue(ready(snapshot(recurring), epoch("2026-10-12T06:00:00Z")).causes.isEmpty())
    }

    @Test fun gapStartClampsToFirstValidInstantRatherThanShiftingMinutes() {
        val recurring = rule(schedule = ManagedSchedule.Recurring(2 * 60 + 30, 4 * 60, setOf(7)))
        val rules = snapshot(recurring)
        assertTrue(ready(rules, epoch("2026-03-08T06:59:59.999Z")).causes.isEmpty())
        val result = ready(rules, epoch("2026-03-08T07:00:00Z"))
        assertEquals(ManagedOccurrence(epoch("2026-03-08T07:00:00Z"), epoch("2026-03-08T08:00:00Z")),
            result.causes.single().scope.occurrence)
    }

    @Test fun gapEndClampsAndBothEndpointsInGapSkipEmptyOccurrence() {
        val gapEnd = rule(schedule = ManagedSchedule.Recurring(1 * 60 + 30, 2 * 60 + 30, setOf(7)))
        assertEquals(epoch("2026-03-08T07:00:00Z"),
            ready(snapshot(gapEnd), epoch("2026-03-08T06:45:00Z")).causes.single().scope.occurrence.endsAtEpochMs)
        assertTrue(ready(snapshot(gapEnd), epoch("2026-03-08T07:00:00Z")).causes.isEmpty())
        val allGap = rule(schedule = ManagedSchedule.Recurring(2 * 60 + 10, 2 * 60 + 50, setOf(7)))
        val result = ready(snapshot(allGap), epoch("2026-03-08T06:00:00Z"))
        assertTrue(result.causes.isEmpty())
        assertEquals(epoch("2026-03-15T06:10:00Z"), result.nextRecheckEpochMs)
    }

    @Test fun repeatedHourUsesEarlierStartAndLaterEndWithoutAnUnblockedGap() {
        val recurring = rule(schedule = ManagedSchedule.Recurring(75, 105, setOf(7)))
        val rules = snapshot(recurring)
        val expected = ManagedOccurrence(epoch("2026-11-01T05:15:00Z"), epoch("2026-11-01T06:45:00Z"))
        for (time in listOf("2026-11-01T05:15:00Z", "2026-11-01T05:55:00Z", "2026-11-01T06:00:00Z", "2026-11-01T06:44:59.999Z")) {
            assertEquals(expected, ready(rules, epoch(time)).causes.single().scope.occurrence)
        }
        assertTrue(ready(rules, expected.startsAtEpochMs - 1).causes.isEmpty())
        assertTrue(ready(rules, expected.endsAtEpochMs).causes.isEmpty())
    }

    @Test fun overnightDurationFollowsDstRatherThanFixedTwentyFourHourArithmetic() {
        val recurring = rule(schedule = ManagedSchedule.Recurring(22 * 60, 6 * 60, setOf(6)))
        val spring = ready(snapshot(recurring), epoch("2026-03-08T07:30:00Z")).causes.single().scope.occurrence
        val fall = ready(snapshot(recurring), epoch("2026-11-01T07:30:00Z")).causes.single().scope.occurrence
        assertEquals(7 * 3_600_000L, spring.endsAtEpochMs - spring.startsAtEpochMs)
        assertEquals(9 * 3_600_000L, fall.endsAtEpochMs - fall.startsAtEpochMs)
    }

    @Test fun skippedLocalDateDoesNotBecomeAnAllDayBlockAndNextWeekStillArms() {
        val recurring = rule(schedule = ManagedSchedule.Recurring(9 * 60, 10 * 60, setOf(5)), zone = "Pacific/Apia")
        val result = ready(snapshot(recurring), epoch("2011-12-29T20:00:00Z"))
        assertTrue(result.causes.isEmpty())
        assertEquals(epoch("2012-01-05T19:00:00Z"), result.nextRecheckEpochMs)
    }

    @Test fun deviceDefaultTimezoneCannotMoveSessionOrRecurringWindow() {
        val original = TimeZone.getDefault()
        try {
            val recurring = snapshot(rule(schedule = ManagedSchedule.Recurring(9 * 60, 10 * 60, setOf(1))))
            val session = snapshot(rule())
            val baselineRecurring = ready(recurring)
            val baselineSession = ready(session)
            for (zone in listOf("UTC", "Asia/Tokyo", "Pacific/Honolulu")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(baselineRecurring, ready(recurring))
                assertEquals(baselineSession, ready(session))
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun malformedOrUnsupportedSnapshotNeverReturnsPartialReadyCauses() {
        unavailable(evaluate(snapshot(rule()).copy(schemaVersion = 2)), ManagedUnavailableReason.UNSUPPORTED_SCHEMA)
        val invalid = listOf(
            rule("bad").copy(zoneId = "not/a/zone"), rule("bad").copy(zoneId = "+01:00"),
            rule("bad").copy(zoneId = "EST"), rule("bad").copy(revision = 11), rule("bad").copy(revision = 0),
            rule("bad").copy(targetGroupId = "missing"), rule("bad").copy(name = " "),
            rule("bad").copy(schedule = ManagedSchedule.Recurring(60, 60, setOf(1))),
            rule("bad").copy(schedule = ManagedSchedule.Recurring(-1, 60, setOf(1))),
            rule("bad").copy(schedule = ManagedSchedule.Recurring(0, 1440, setOf(1))),
            rule("bad").copy(schedule = ManagedSchedule.Recurring(0, 60, emptySet())),
            rule("bad").copy(schedule = ManagedSchedule.Recurring(0, 60, setOf(0))),
            rule("bad").copy(schedule = ManagedSchedule.Session(now, now)),
            rule("bad").copy(schedule = ManagedSchedule.Session(now, now + 7 * 86_400_000L + 1)),
            rule("bad").copy(schedule = ManagedSchedule.Session(Long.MIN_VALUE, Long.MAX_VALUE)),
            rule("bad").copy(targets = ManagedTargets.Selection(emptySet(), emptySet())),
            rule("bad").copy(targets = ManagedTargets.Selection(setOf("undotted"), emptySet())),
            rule("bad").copy(targets = ManagedTargets.Selection(emptySet(), setOf("EXAMPLE.com"))),
            rule("bad").copy(targets = ManagedTargets.Selection(emptySet(), setOf("1.2.3.4"))),
            rule("bad").copy(targets = ManagedTargets.Selection(emptySet(), setOf("example.com."))),
        )
        for (bad in invalid) unavailable(evaluate(snapshot(rule(), bad)), ManagedUnavailableReason.INVALID_SNAPSHOT)
    }

    @Test fun duplicateMembershipRuleAndLiveTombstoneCollisionsAreRejected() {
        unavailable(evaluate(snapshot(rule()).copy(memberships = listOf(member, member))), ManagedUnavailableReason.INVALID_SNAPSHOT)
        unavailable(evaluate(snapshot(rule(), rule())), ManagedUnavailableReason.INVALID_SNAPSHOT)
        val deleted = ManagedTombstone("one", "group", 3, now)
        unavailable(evaluate(snapshot(rule()).copy(tombstones = listOf(deleted))), ManagedUnavailableReason.INVALID_SNAPSHOT)
        unavailable(evaluate(snapshot().copy(tombstones = listOf(deleted, deleted))), ManagedUnavailableReason.INVALID_SNAPSHOT)
        assertTrue(ready(snapshot().copy(tombstones = listOf(deleted))).causes.isEmpty())
    }

    @Test fun repositoryContractCapacityBoundsAreEnforcedDefensively() {
        unavailable(evaluate(snapshot(*(0..10).map { rule("rule-$it") }.toTypedArray())), ManagedUnavailableReason.INVALID_SNAPSHOT)
        val rules = snapshot(rule()).copy(memberships = (0..20).map { member.copy(groupId = "group-$it") })
        unavailable(evaluate(rules), ManagedUnavailableReason.INVALID_SNAPSHOT)
        val targets = ManagedTargets.Selection((0..20).map { "com.example.app$it" }.toSet(), emptySet())
        unavailable(evaluate(snapshot(rule(targets = targets))), ManagedUnavailableReason.INVALID_SNAPSHOT)
    }

    @Test fun extremeRecurringClockReturnsUnavailableWithoutArithmeticCrash() {
        val recurring = snapshot(rule(schedule = ManagedSchedule.Recurring(0, 60, setOf(1, 2, 3, 4, 5, 6, 7)), zone = "UTC"))
        unavailable(evaluate(recurring, Long.MAX_VALUE), ManagedUnavailableReason.INVALID_TIME)
        unavailable(evaluate(recurring, Long.MIN_VALUE), ManagedUnavailableReason.INVALID_TIME)
    }
}
