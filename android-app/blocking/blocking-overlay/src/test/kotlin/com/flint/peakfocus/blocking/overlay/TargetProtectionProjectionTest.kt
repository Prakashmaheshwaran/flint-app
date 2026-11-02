package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TargetProtectionProjectionTest {
    @Test fun siteDoesNotProjectTheBrowsersActiveCreditIntoItsNextBoundary() {
        val end = now.wallEpochMs + 600_000
        val domain = rule("site").copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val stored = snapshot(domain, opens = listOf(OpenLimit(chrome, 3)))
        val accepted = OpenCreditLedgerPolicy.acceptOpenCredit(stored.openCredits!!, chrome, "credit", listOf(3),
            emptyList(), false, now).next
        val observed = environment().copy(browserPackageName = chrome, domainContextKnown = true,
            websiteCoverage = TargetWebsiteCoverage.AVAILABLE, websiteCoverageObservedAtWall = now.wallEpochMs,
            websiteCoverageValidUntilWall = now.wallEpochMs + 900_000, websiteCoverageIsCurrent = true)
        val view = read(stored.copy(openCredits = accepted), TargetRef.Site("example.com"), observed)
        assertEquals(Readiness.Ready, view.readiness)
        assertNull(view.openCredit)
        assertNull(view.timeLimit)
        assertEquals(end, view.nextChangeAtWall)
    }

    private var now = AccessClockStamp(1_800_000_000_000, 1_000, "boot")
    private val pkg = "selected.app"
    private val chrome = "com.android.chrome"
    private fun rule(id: String = "session-timer-v2-one", tier: BreakLevel = BreakLevel.EASY,
        end: Long = now.wallEpochMs + 600_000) = BlockRule(id, id,
        BlockTargets(apps = setOf(AppRef(pkg))), breakLevel = tier, expiresAtEpochMs = end)
    private fun snapshot(vararg rules: BlockRule, times: List<TimeLimit> = emptyList(),
        opens: List<OpenLimit> = emptyList()): ProtectionSnapshot {
        val quota = OpenCreditLedgerPolicy.initial(now, "UTC")
        val period = AnchoredInterval(now, quota.reset.remainingToResetMs, now.wallEpochMs + quota.reset.remainingToResetMs)
        val causes = rules.associate { rule -> ruleCauseKey(rule.id) to EditorCause(
            if (rule.enabled) rule.id else "inactive", rule.expiresAtEpochMs?.let { end ->
                AnchoredInterval(now, (end - now.wallEpochMs).coerceAtLeast(0), end)
            }, "UTC") } + times.associate { timeCauseKey(it.packageName) to EditorCause("time-period", period, "UTC") } +
            opens.associate { openCauseKey(it.packageName) to EditorCause("open-period", period, "UTC") }
        return ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(now, now)), rules.toList(), times, opens,
            EditorCommitmentState(causes = causes, timeline = now), HarderWaitPolicy.Ledger(), now, quota)
    }
    private fun environment() = TargetReadEnvironment(status = ProtectionStatus.UNKNOWN.copy(
        readiness = ProtectionStatus.Readiness.READY, observedAt = now, validUntilElapsedRealtimeMs = now.elapsedRealtimeMs + 2_000,
        safetyFloorResolved = true, detectors = ProtectionStatus.DetectorHealth(true, ProtectionStatus.PathBState.STANDBY, emptySet())))
    private fun read(stored: ProtectionSnapshot, target: TargetRef = TargetRef.App(pkg),
        env: TargetReadEnvironment = environment()) =
        TargetProtectionProjection.read(target, StorageState.Ready(stored), env.copy(publishedRules = env.publishedRules ?:
            runCatching { StorageState.Ready(stored.projectedRulesForRead(now)) }.getOrDefault(StorageState.Loading)),
            now, "flint", ZoneId.of("UTC"))

    // PC-48/PC-52/PC-73: floor identity and actual lookup provenance survive policy failure.
    @Test fun staticSelfAndRetainedRuntimeFloorRemainIndependentOfStorageReadiness() {
        for (state in listOf(StorageState.Loading, StorageState.Unavailable(StorageFailure.CORRUPT))) {
            for (name in listOf("flint", "com.android.settings")) {
                val view = TargetProtectionProjection.read(TargetRef.App(name), state, environment(), now, "flint", ZoneId.of("UTC"))
                assertNotNull(view.floor)
                assertEquals(false, view.blockingNow)
                assertTrue(view.coveringCauses.isEmpty())
            }
            val retained = FloorIdentity(FloorIdentity.Kind.RUNTIME_DIALER, 123, true)
            val view = TargetProtectionProjection.read(TargetRef.App(pkg), state, environment().copy(floor = retained), now, "flint", ZoneId.of("UTC"))
            assertEquals(retained, view.floor)
            assertEquals(false, view.blockingNow)
        }
    }

    @Test fun onlyRequestedMembershipIsReturnedIncludingInactiveTemplatesAndForeignContributors() {
        val selected = rule()
        val disabled = rule("disabled").copy(enabled = false)
        val private = rule("other-private-target").copy(targets = BlockTargets(apps = setOf(AppRef("other.app"))))
        val foreign = rule("legacy")
        val result = read(snapshot(selected, disabled, private), env = environment().copy(foreignRules = listOf(foreign)))
        assertEquals(setOf(selected.id, disabled.id, foreign.id), result.coveringCauses.map { it.ruleId }.toSet())
        assertFalse(result.coveringCauses.single { it.ruleId == disabled.id }.active)
        assertFalse(result.coveringCauses.any { it.name.contains("private") })
    }

    @Test fun storedKindsAndTiersResolveStrongestHeadlineSeparatelyFromLatestEnforcingEnd() {
        val rules = listOf(rule("session-one", BreakLevel.HARDCORE, now.wallEpochMs + 100_000),
            rule("session-timer-v2-one", end = now.wallEpochMs + 900_000),
            rule("schedule-one").copy(schedule = Schedule(emptySet(), 0, 60)),
            rule("sleep-one").copy(schedule = Schedule(emptySet(), 0, 60)))
        val view = read(snapshot(*rules.toTypedArray()))
        assertEquals(setOf(CauseKind.SESSION, CauseKind.TIMER, CauseKind.SCHEDULE, CauseKind.SLEEP), view.coveringCauses.map { it.kind }.toSet())
        assertEquals("session-one", view.headline!!.ruleId)
        assertEquals(now.wallEpochMs + 900_000, view.blockingUntilWall)
        assertEquals(now.wallEpochMs + 100_000, view.nextChangeAtWall)
    }

    // PC-54/PC-55: raw wall expiry cannot replace the R-18 elapsed occurrence.
    @Test fun wallJumpUsesTheSameAnchoredEndAsEnforcementWithoutMutatingTheSnapshot() {
        val stored = snapshot(rule())
        val original = stored.copy()
        now = now.copy(wallEpochMs = now.wallEpochMs + 86_400_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 60_000)
        val result = read(stored)
        assertEquals(true, result.blockingNow)
        assertEquals(now.wallEpochMs + 540_000, result.blockingUntilWall)
        assertTrue(result.coveringCauses.single().timeUncertain)
        assertEquals(original, stored)
    }

    // PC-56/PC-57: saved-zone DST boundaries, including projection before owner publication.
    @Test fun futureScheduleUsesSavedZoneAndDstRatherThanDeviceZoneOrAnInventedOccurrenceId() {
        now = AccessClockStamp(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), 1_000, "boot")
        val rule = rule("schedule-dst").copy(schedule = Schedule(emptySet(), 90, 210), expiresAtEpochMs = null)
        val stored = snapshot(rule).let { it.copy(editor = it.editor.copy(causes = mapOf(ruleCauseKey(rule.id) to
            EditorCause("inactive", AnchoredInterval(now, 0, now.wallEpochMs), "America/New_York")))) }
        val before = read(stored)
        assertEquals(false, before.blockingNow)
        assertEquals(Instant.parse("2026-03-08T06:30:00Z").toEpochMilli(), before.nextChangeAtWall)
        now = now.copy(wallEpochMs = now.wallEpochMs + 105 * 60_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 105 * 60_000)
        val active = read(stored)
        assertEquals(true, active.blockingNow)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), active.blockingUntilWall)
        assertNull(active.coveringCauses.single().occurrenceId)
        assertEquals("inactive", stored.editor.causes.getValue(ruleCauseKey(rule.id)).occurrenceId)
    }

    @Test fun expiredTimersAreInactiveAndMissingOccurrenceAuthorityIsUnavailable() {
        val stored = snapshot(rule())
        now = now.copy(wallEpochMs = now.wallEpochMs + 600_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 600_000)
        assertEquals(false, read(stored).blockingNow)
        val missing = read(stored.copy(editor = EditorCommitmentState()))
        assertEquals(Readiness.Unavailable("rule-occurrence-unavailable"), missing.readiness)
        assertNull(missing.blockingNow)
    }

    // PC-15/PC-37: unknown usage is not zero; a zero budget needs no measurement.
    @Test fun positiveUnmeasuredTimeIsUnknownAndZeroTimeUsesItsStoredTier() {
        val positive = read(snapshot(times = listOf(TimeLimit(pkg, 5, BreakLevel.HARDCORE))))
        assertNull(positive.blockingNow)
        assertNull(positive.timeLimit!!.usedMs)
        val zero = read(snapshot(times = listOf(TimeLimit(pkg, 0, BreakLevel.HARDER))))
        assertEquals(true, zero.blockingNow)
        assertEquals(BreakLevel.HARDER, zero.headline!!.tier)
        val below = read(snapshot(times = listOf(TimeLimit(pkg, 5))), env = environment().copy(measuredUsageMs = 1_000))
        assertEquals(false, below.blockingNow)
        assertTrue(below.coveringCauses.single().active)
    }

    @Test fun unknownTimeContributorPreventsAnExactUntilEvenWithKnownRuleEnforcement() {
        val view = read(snapshot(rule(), times = listOf(TimeLimit(pkg, 1))))
        assertEquals(true, view.blockingNow)
        assertNull(view.blockingUntilWall)
        assertFalse(view.blockingOngoing)
    }

    @Test fun readableOpenCountsAboveALoweredCapDoNotClaimRuntimeCreditAvailability() {
        val stored = snapshot(opens = listOf(OpenLimit(pkg, 3, BreakLevel.HARDCORE))).copy(
            openCredits = OpenCreditLedgerPolicy.initial(now, "UTC", mapOf(pkg to 4)))
        val view = read(stored)
        assertEquals(4, view.openCredit!!.used)
        assertEquals(3, view.openCredit.cap)
        assertTrue(view.openCredit.readable)
        assertFalse(view.openCredit.available)
        assertEquals("open-credit-exhausted", view.openCredit.unavailableReason)
        assertNull(view.openCredit.activeCreditEndsAtWall)
    }

    // PC-28/PC-30/PC-32: pure #178 view, never acceptance or a debit from a detail read.
    @Test fun unusedTimedCreditsAreAvailableWhileTheOpenCauseStillEnforcesBetweenCredits() {
        val stored = snapshot(opens = listOf(OpenLimit(pkg, 3, BreakLevel.HARDCORE)))
        val view = read(stored)
        assertEquals(true, view.blockingNow)
        assertTrue(view.openCredit!!.available)
        assertNull(view.openCredit.unavailableReason)
        assertEquals(0, view.openCredit.used)
        assertEquals(3, view.openCredit.cap)
        assertNull(view.openCredit.activeCreditEndsAtWall)
        assertTrue(stored.openCredits!!.active.isEmpty())
        assertTrue(stored.openCredits!!.counters.isEmpty())
    }

    // PC-30/PC-35/PC-54/PC-55: the last credit remains active, clipped at trusted reset.
    @Test fun activeLastCreditUsesTheClippedResetEndAndBecomesTheNextChangeBoundary() {
        now = AccessClockStamp(Instant.parse("2026-10-30T23:58:00Z").toEpochMilli(), 1_000, "boot")
        val stored = snapshot(opens = listOf(OpenLimit(pkg, 1)))
        val accepted = OpenCreditLedgerPolicy.acceptOpenCredit(stored.openCredits!!, pkg, "credit", listOf(1),
            emptyList(), false, now).next
        val withCredit = stored.copy(openCredits = accepted)
        now = now.copy(wallEpochMs = now.wallEpochMs + 3_601_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        val view = read(withCredit)
        assertEquals(false, view.blockingNow)
        assertEquals(1, view.openCredit!!.used)
        assertFalse(view.openCredit.available)
        assertEquals("open-credit-active", view.openCredit.unavailableReason)
        assertEquals(now.wallEpochMs + 119_000, view.openCredit.activeCreditEndsAtWall)
        assertEquals(view.openCredit.activeCreditEndsAtWall, view.openCredit.periodEndsAtWall)
        assertEquals(view.openCredit.activeCreditEndsAtWall, view.nextChangeAtWall)
        assertEquals(accepted, withCredit.openCredits)
    }

    @Test fun independentRulesAndTimeLimitsDenyCreditsAndUnknownUsageNeverOffersOne() {
        val open = OpenLimit(pkg, 3)
        for (stored in listOf(snapshot(rule(), opens = listOf(open)),
            snapshot(times = listOf(TimeLimit(pkg, 0)), opens = listOf(open)))) {
            val view = read(stored)
            assertEquals(true, view.blockingNow)
            assertFalse(view.openCredit!!.available)
            assertEquals("other-contributor-blocks", view.openCredit.unavailableReason)
            assertEquals(0, view.openCredit.used)
        }
        val unknown = read(snapshot(times = listOf(TimeLimit(pkg, 5)), opens = listOf(open)))
        assertFalse(unknown.openCredit!!.available)
        assertEquals("target-limit-state-unavailable", unknown.openCredit.unavailableReason)
    }

    @Test fun anActiveCreditCannotReleaseANewIndependentHardcoreContributor() {
        val stored = snapshot(rule(tier = BreakLevel.HARDCORE), opens = listOf(OpenLimit(pkg, 3)))
        val quota = OpenCreditLedgerPolicy.acceptOpenCredit(stored.openCredits!!, pkg, "credit", listOf(3),
            emptyList(), false, now).next
        val view = read(stored.copy(openCredits = quota))
        assertEquals(true, view.blockingNow)
        assertEquals(CauseKind.TIMER, view.headline!!.kind)
        assertFalse(view.coveringCauses.single { it.kind == CauseKind.OPEN_LIMIT }.enforcingNow!!)
        assertFalse(view.openCredit!!.available)
        assertEquals("other-contributor-blocks", view.openCredit.unavailableReason)
        assertEquals(now.wallEpochMs + 300_000, view.openCredit.activeCreditEndsAtWall)
    }

    // PC-35/PC-37: pending or unmaterialized quota is unknown, never an inferred refill.
    @Test fun expiredInactiveAndMissingQuotaOccurrencesNeverAdvertiseFreshCredits() {
        now = AccessClockStamp(Instant.parse("2026-10-30T23:59:59Z").toEpochMilli(), 1_000, "boot")
        val stored = snapshot(opens = listOf(OpenLimit(pkg, 3))).copy(
            openCredits = OpenCreditLedgerPolicy.initial(now, "UTC", mapOf(pkg to 2)))
        val key = openCauseKey(pkg)
        val cause = stored.editor.causes.getValue(key)
        val variants = listOf(stored.copy(editor = stored.editor.copy(causes = emptyMap())),
            stored.copy(editor = stored.editor.copy(causes = mapOf(key to cause.copy(occurrenceId = "inactive")))),
            stored.copy(editor = stored.editor.copy(causes = mapOf(key to cause.copy(interval = null)))))
        for (saved in variants) {
            val view = read(saved)
            assertEquals(Readiness.Unavailable("limit-occurrence-unavailable"), view.readiness)
            assertNull(view.blockingNow)
            assertFalse(view.openCredit!!.available)
            assertEquals(2, view.openCredit.used)
        }
        now = now.copy(wallEpochMs = now.wallEpochMs + 1_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        val pending = read(stored)
        assertEquals(Readiness.Unavailable("limit-occurrence-unavailable"), pending.readiness)
        assertNull(pending.blockingNow)
        assertFalse(pending.openCredit!!.available)
        assertEquals(2, pending.openCredit.used)
        assertNull(pending.openCredit.activeCreditEndsAtWall)
        assertEquals(2, stored.openCredits!!.counters.getValue(pkg).used)
    }

    @Test fun coldAndUnavailableStorageNeverOfferCreditsAndRetainAnIndependentFloor() {
        val retained = FloorIdentity(FloorIdentity.Kind.RUNTIME_HOME, 123, true)
        for (delay in listOf(0L, 2_001L)) {
            val at = now.copy(wallEpochMs = now.wallEpochMs + delay, elapsedRealtimeMs = now.elapsedRealtimeMs + delay)
            for (state in listOf(StorageState.Loading, StorageState.Unavailable(StorageFailure.IO))) {
                val unknown = TargetProtectionProjection.read(TargetRef.App(pkg), state, environment(), at, "flint", ZoneId.of("UTC"))
                assertNull(unknown.blockingNow)
                assertNull(unknown.openCredit)
                val floor = TargetProtectionProjection.read(TargetRef.App(pkg), state,
                    environment().copy(floor = retained), at, "flint", ZoneId.of("UTC"))
                assertEquals(retained, floor.floor)
                assertEquals(false, floor.blockingNow)
                assertNull(floor.openCredit)
            }
        }
    }

    @Test fun staleRuntimeAndAnUnattributedBrowserDomainNeverOfferCredits() {
        val stored = snapshot(opens = listOf(OpenLimit(pkg, 3)))
        val stale = read(stored, env = environment().let { it.copy(status = it.status.copy(validUntilElapsedRealtimeMs = now.elapsedRealtimeMs)) })
        assertFalse(stale.openCredit!!.available)
        assertEquals("target-runtime-unconfirmed", stale.openCredit.unavailableReason)
        assertNull(stale.blockingNow)
        val domain = rule("domain").copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val browser = read(snapshot(domain, opens = listOf(OpenLimit(chrome, 3))), TargetRef.App(chrome))
        assertFalse(browser.openCredit!!.available)
        assertEquals("other-contributor-blocks", browser.openCredit.unavailableReason)
    }

    @Test fun siteCoverageReflectsEachBrowserHealthStateWithoutClaimingAllWebsites() {
        val domain = rule().copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val stored = snapshot(domain)
        for (coverage in TargetWebsiteCoverage.entries) {
            val view = read(stored, TargetRef.Site("https://sub.EXAMPLE.com/page"), environment().copy(
                browserPackageName = chrome, websiteCoverage = coverage, domainContextKnown = true,
                websiteCoverageObservedAtWall = now.wallEpochMs,
                websiteCoverageValidUntilWall = now.wallEpochMs + 120_000))
            assertEquals(coverage, view.websiteCoverage)
            if (coverage == TargetWebsiteCoverage.AVAILABLE) assertEquals(true, view.blockingNow)
            else assertNull(view.blockingNow)
            assertNull(view.openCredit)
            assertNull(view.timeLimit)
        }
    }

    @Test fun browserAppRestrictionStillContributesWhenDomainCoverageIsUnconfirmed() {
        val app = rule("browser-app", BreakLevel.HARDCORE).copy(targets = BlockTargets(apps = setOf(AppRef(chrome))))
        val domain = rule("domain").copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val view = read(snapshot(app, domain), TargetRef.Site("example.com"), environment().copy(
            browserPackageName = chrome, websiteCoverage = TargetWebsiteCoverage.UNCONFIRMED))
        assertEquals(true, view.blockingNow)
        assertEquals(app.id, view.headline!!.ruleId)
        assertNull(view.blockingUntilWall)
    }

    @Test fun siteWithoutBrowserCannotInventAnUnprotectedDecisionOrLeakAppInventory() {
        val view = read(snapshot(rule()), TargetRef.Site("example.com"))
        assertNull(view.blockingNow)
        assertTrue(view.coveringCauses.isEmpty())
        assertEquals(TargetWebsiteCoverage.AWAITING_BROWSER, view.websiteCoverage)
    }

    // PC-23/PC-24/PC-26: exact ordinary scope and stronger/new contributors.
    @Test fun ordinaryRecoveryIsTargetScopedAndANewStrongerCauseImmediatelyStands() {
        val stored = snapshot(rule())
        val input = OrdinaryProtectionInput(pkg, "flint", false, false)
        val grant = OrdinaryProtectionPolicy.request(stored, input, "p1-11111111-1111-4111-8111-111111111111", now,
            emptyList(), OrdinaryProtectionPolicy.frozenScopes(stored, input, emptyList(), now)!!)
        val granted = stored.copy(focus = grant.focus)
        val paused = read(granted)
        assertEquals(false, paused.blockingNow)
        assertEquals(now.wallEpochMs + 300_000, paused.recoveryEndsAtWall)
        assertTrue(paused.coveringCauses.single().active)
        assertEquals(false, paused.coveringCauses.single().enforcingNow)
        assertNull(read(granted, TargetRef.App("other.app")).recoveryEndsAtWall)
        val stronger = rule("stronger", BreakLevel.HARDCORE)
        val fresh = snapshot(*listOf(stored.rules.single(), stronger).toTypedArray()).copy(focus = grant.focus)
        assertEquals(true, read(fresh).blockingNow)
        assertEquals(stronger.id, read(fresh).headline!!.ruleId)
        assertNull(read(fresh).recoveryEndsAtWall)
        assertEquals(1, granted.focus.protection!!.outbox.size)
    }

    // PC-41/PC-42/PC-59: global committed recovery, expiry and reboot.
    @Test fun globalRecoveryCoversAnUnknownBrowserButNeverSurvivesReboot() {
        val stored = snapshot(rule())
        val recovery = ProtectionRecovery("pass", now, emptySet(), emptySet(), emptySet(), 300_000)
        val recovering = stored.copy(focus = stored.focus.copy(protection = stored.focus.protection!!.copy(recovery = recovery)))
        val view = read(recovering, TargetRef.Site("example.com"))
        assertEquals(false, view.blockingNow)
        assertEquals(now.wallEpochMs + 300_000, view.recoveryEndsAtWall)
        now = now.copy(bootSessionId = "new", elapsedRealtimeMs = 0)
        assertNull(read(recovering, TargetRef.Site("example.com")).recoveryEndsAtWall)
        assertEquals(recovery, recovering.focus.protection!!.recovery)
    }

    // PC-61/PC-73: live health is required; stale truth cannot become an empty allow.
    @Test fun unavailableRuntimeOrStaleHealthPreservesUnknownEnforcement() {
        val stored = snapshot(rule())
        val stale = environment().copy(status = environment().status.copy(validUntilElapsedRealtimeMs = now.elapsedRealtimeMs))
        assertNull(read(stored, env = stale).blockingNow)
        assertNull(read(stored, env = TargetReadEnvironment()).blockingNow)
        val failed = read(stored, env = environment().copy(unavailableReason = "runtime-observation-unavailable"))
        assertEquals(Readiness.Unavailable("runtime-observation-unavailable"), failed.readiness)
        assertNull(failed.blockingNow)
    }

    @Test fun savedAndRetiredRulesRemainUnknownUntilEnforcementPublicationAgrees() {
        val stored = snapshot(rule())
        assertNull(read(stored, env = environment().copy(publishedRules = StorageState.Ready(emptyList()))).blockingNow)
        assertNull(read(stored, env = environment().copy(publishedRules = StorageState.Loading)).blockingNow)
        assertNull(read(stored, env = environment().copy(publishedRules = StorageState.Unavailable(StorageFailure.IO))).blockingNow)
        val retired = read(snapshot(), env = environment().copy(publishedRules = StorageState.Ready(stored.projectedRulesForRead(now))))
        assertTrue(retired.coveringCauses.isEmpty())
        assertNull(retired.blockingNow)
        assertEquals(true, read(stored).blockingNow)
    }

    @Test fun unknownRuntimeOrAPolicyCheckOlderThanTheReadCannotTurnEmptyStoresIntoUnprotected() {
        val stored = snapshot()
        assertNull(read(stored, env = TargetReadEnvironment()).blockingNow)
        val older = environment().copy(status = environment().status.copy(observedAt = now.copy(
            wallEpochMs = now.wallEpochMs - 1, elapsedRealtimeMs = now.elapsedRealtimeMs - 1)))
        assertNull(read(stored, env = older).blockingNow)
        assertEquals(false, read(stored).blockingNow)
    }

    @Test fun alwaysOnPublicationIgnoresSyntheticReadAnchorsAndRetainsItsKnownOccurrence() {
        val rule = rule("always-on").copy(expiresAtEpochMs = null)
        val stored = snapshot(rule)
        val published = stored.projectedRulesForRead(now)
        now = now.copy(wallEpochMs = now.wallEpochMs + 10_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 10_000)
        val view = read(stored, env = environment().copy(publishedRules = StorageState.Ready(published)))
        assertEquals(true, view.blockingNow)
        assertTrue(view.blockingOngoing)
        assertNull(view.blockingUntilWall)
        assertEquals(rule.id, view.headline!!.occurrenceId)
    }

    @Test fun realAnchoredIdentityDivergenceAndUnpublishedTierWeakeningStillRemainUnknown() {
        val stored = snapshot(rule(tier = BreakLevel.HARDCORE))
        val published = StorageState.Ready(stored.projectedRulesForRead(now))
        val key = ruleCauseKey(stored.rules.single().id)
        val cause = stored.editor.causes.getValue(key)
        val interval = cause.interval!!
        fun withInterval(value: AnchoredInterval) = stored.copy(editor = stored.editor.copy(
            causes = mapOf(key to cause.copy(interval = value))))
        val changed = listOf(
            withInterval(interval.copy(remainingAtAnchorMs = interval.remainingAtAnchorMs + 1_000)),
            withInterval(interval.copy(anchor = interval.anchor.copy(elapsedRealtimeMs = interval.anchor.elapsedRealtimeMs - 1))),
            stored.copy(rules = listOf(stored.rules.single().copy(breakLevel = BreakLevel.EASY))))
        for (saved in changed) {
            val view = read(saved, env = environment().copy(publishedRules = published))
            assertNull(view.blockingNow)
            assertNull(view.coveringCauses.single().enforcingNow)
            assertNull(view.blockingUntilWall)
        }
        assertEquals(true, read(stored, env = environment().copy(publishedRules = published)).blockingNow)
    }

    @Test fun incompleteOrInvalidDomainTimestampsCannotEstablishAvailableCoverage() {
        val domain = rule("domain").copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val stored = snapshot(domain)
        val fresh = environment().copy(websiteCoverage = TargetWebsiteCoverage.AVAILABLE,
            domainContextKnown = true, websiteCoverageObservedAtWall = now.wallEpochMs - 1_000,
            websiteCoverageValidUntilWall = now.wallEpochMs + 119_000, websiteCoverageIsCurrent = true)
        val invalid = listOf(fresh.copy(websiteCoverageObservedAtWall = null),
            fresh.copy(websiteCoverageValidUntilWall = null),
            fresh.copy(websiteCoverageObservedAtWall = now.wallEpochMs + 1),
            fresh.copy(websiteCoverageValidUntilWall = now.wallEpochMs), fresh.copy(domainContextKnown = false))
        for (env in invalid) {
            val view = read(stored, TargetRef.Site("example.com"), env)
            assertEquals(TargetWebsiteCoverage.AWAITING_BROWSER, view.websiteCoverage)
            assertFalse(view.websiteCoverageIsCurrent)
            assertNull(view.blockingNow)
            assertNull(view.coveringCauses.single().enforcingNow)
        }
        assertEquals(true, read(stored, TargetRef.Site("example.com"), fresh).blockingNow)
    }

    @Test fun freshRetainedBrowserContextResolvesOnlyDomainCausesWhileFlintIsForeground() {
        val domain = rule("domain").copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val browserApp = rule("browser-app", BreakLevel.HARDCORE).copy(targets = BlockTargets(apps = setOf(AppRef(chrome))))
        val stored = snapshot(domain, browserApp)
        val observed = now.wallEpochMs - 1_000
        val expires = now.wallEpochMs + 119_000
        val env = environment().copy(websiteCoverage = TargetWebsiteCoverage.AVAILABLE, domainContextKnown = true,
            websiteCoverageObservedAtWall = observed, websiteCoverageValidUntilWall = expires)
        val view = read(stored, TargetRef.Site("example.com"), env)
        assertEquals(true, view.blockingNow)
        assertEquals(domain.id, view.headline!!.ruleId)
        assertEquals(listOf(domain.id), view.coveringCauses.map { it.ruleId })
        assertNull(view.blockingUntilWall) // An unidentified current browser's app scope is unknown.
        assertFalse(view.websiteCoverageIsCurrent)
        assertEquals(observed, view.websiteCoverageObservedAtWall)
        assertEquals(expires, view.nextChangeAtWall)
        now = now.copy(wallEpochMs = expires, elapsedRealtimeMs = now.elapsedRealtimeMs + 119_000)
        val expired = read(stored, TargetRef.Site("example.com"), env.copy(status = environment().status))
        assertNull(expired.blockingNow)
        assertEquals(TargetWebsiteCoverage.AWAITING_BROWSER, expired.websiteCoverage)
    }

    @Test fun noActiveWebsiteRuleIsDistinctFromWaitingForBrowserCoverage() {
        val view = read(snapshot(), TargetRef.Site("example.com"), environment().copy(websiteCoverage = TargetWebsiteCoverage.NOT_REQUIRED))
        assertEquals(TargetWebsiteCoverage.NOT_REQUIRED, view.websiteCoverage)
        assertNull(view.blockingNow) // Browser-app scope still cannot be guessed.
        assertTrue(view.coveringCauses.isEmpty())
    }
}
