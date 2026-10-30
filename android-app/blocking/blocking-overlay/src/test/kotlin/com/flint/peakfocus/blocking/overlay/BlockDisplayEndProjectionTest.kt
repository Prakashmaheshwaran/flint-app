package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.RuleClockProjection
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import org.junit.Assert.*
import org.junit.Test

class BlockDisplayEndProjectionTest {
    private val minute = 60_000L
    private val now = 100 * 86_400_000L + 9 * 60 * minute
    private val pkg = "com.example.target"
    private val self = "com.flint"
    private fun rule(id: String, tier: BreakLevel, minutes: Int?) = BlockRule(id, id,
        BlockTargets(apps = setOf(AppRef(pkg))), breakLevel = tier,
        schedule = Schedule(startMinuteOfDay = 8 * 60, endMinuteOfDay = 9 * 60 + (minutes ?: 30)),
        expiresAtEpochMs = minutes?.let { now + it * minute })
    private fun selected(rules: List<BlockRule>, at: Long = now, url: String? = null): BlockCause {
        val verdict = BlockDecisionEngine().decide(pkg, url, rules, self, 540, -1, at) as Verdict.Block
        return ruleBlockCause(rules.single { it.id == verdict.ruleId }, at, 0)
    }
    private fun project(rules: List<BlockRule>, at: Long = now, url: String? = null) =
        BlockDisplayEndProjection.forTarget(pkg, url, rules, self, selected(rules, at, url), at, 0)
    private fun remaining(cause: BlockCause, at: Long = now) =
        blockScreenState(pkg, null, cause, BreakSessionState(), at).remainingMillis

    // R-33 / PC-19, PC-25: display end and exit authority are independent, in either order.
    @Test fun mixedTierSchedulesKeepHardcoreAuthorityAndShowTheLongerEasyEndInBothOrders() {
        val hard = rule("hard", BreakLevel.HARDCORE, 5)
        val easy = rule("easy", BreakLevel.EASY, 20)
        for (rules in listOf(listOf(hard, easy), listOf(easy, hard))) {
            val cause = project(rules)
            assertEquals(hard.id, cause.ruleId)
            assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
            assertEquals(now + 5 * minute, cause.endsAtEpochMs)
            assertEquals(20 * minute, remaining(cause))
            assertNotNull(BlockOverlapPolicy.afterExemptions(cause, true, false))
        }
    }

    @Test fun threeContributorsUseTheLastEndWithoutChangingTheStrongestIdentity() {
        val rules = listOf(rule("hard", BreakLevel.HARDCORE, 5),
            rule("harder", BreakLevel.HARDER, 30), rule("easy", BreakLevel.EASY, 20))
        for (items in listOf(rules, rules.reversed(), listOf(rules[1], rules[0], rules[2]))) {
            assertEquals(30 * minute, remaining(project(items)))
            assertEquals("hard", project(items).ruleId)
        }
    }

    @Test fun weakerUnknownOrOngoingEndHidesTheFiniteCountdownInBothOrders() {
        val hard = rule("hard", BreakLevel.HARDCORE, 5)
        val ongoing = rule("ongoing", BreakLevel.EASY, null).copy(schedule = null)
        val stamp = AccessClockStamp(now, 1_000, "boot")
        val unknown = rule("unknown", BreakLevel.EASY, 20).copy(runtime = RuleRuntime("unknown",
            stamp, null, null, "UTC", stamp,
            evaluation = RuleEvaluation(true, null, null)))
        for (other in listOf(ongoing, unknown)) for (rules in listOf(listOf(hard, other), listOf(other, hard))) {
            val cause = project(rules)
            assertEquals("hard", cause.ruleId)
            assertNull(remaining(cause))
        }
    }

    @Test fun legacyQuickBlocklistOutlastsAFiniteScheduleAndSuppressesItsCountdown() {
        val schedule = rule("schedule", BreakLevel.HARDCORE, 20)
        val quickList = BlockRule("quick-blocklist", "Quick blocklist",
            BlockTargets(apps = setOf(AppRef(pkg)))) // Legacy unscheduled selection stays on.
        for (rules in listOf(listOf(quickList, schedule), listOf(schedule, quickList))) {
            val cause = project(rules)
            assertEquals(schedule.id, cause.ruleId)
            assertEquals(now + 20 * minute, cause.endsAtEpochMs)
            assertNull(remaining(cause))
        }
    }

    // R-18 / PC-54, PC-55: already anchored occurrences keep elapsed time through wall edits.
    @Test fun anchoredMixedTierEndsUseTheEnforcementProjectionAcrossForwardAndBackwardClockChanges() {
        val anchor = AccessClockStamp(now, 1_000, "boot")
        val rules = listOf(rule("hard", BreakLevel.HARDCORE, 5), rule("easy", BreakLevel.EASY, 20))
            .map { it.copy(runtime = RuleRuntime(it.id, anchor, it.expiresAtEpochMs!! - now,
                it.expiresAtEpochMs, "UTC", anchor)) }
        for (jump in listOf(86_400_000L, -86_400_000L)) {
            val clock = anchor.copy(wallEpochMs = now + jump + minute, elapsedRealtimeMs = 1_000 + minute)
            val projected = RuleClockProjection.project(rules, clock)
            val cause = project(projected, clock.wallEpochMs)
            assertEquals(clock.wallEpochMs + 4 * minute, cause.endsAtEpochMs)
            assertEquals(19 * minute, remaining(cause, clock.wallEpochMs))
        }
    }

    @Test fun timeAndOpenContributorsCarryTheirLaterEndsWhileHardcoreKeepsItsOwnExpiry() {
        val hard = ruleBlockCause(rule("hard", BreakLevel.HARDCORE, 5), now, 0)
        val time = timeLimitBlockCause(BreakLevel.HARDER, now, 0).copy(endsAtEpochMs = now + 30 * minute)
        val open = openLimitBlockCause(OpenLimit(pkg, 3), now, 0).copy(endsAtEpochMs = now + 20 * minute)
        for (causes in listOf(listOf(hard, time, open), listOf(open, time, hard), listOf(time, hard, open))) {
            val result = BlockOverlapPolicy.strongest(*causes.toTypedArray())!!
            assertEquals(hard.endsAtEpochMs, result.endsAtEpochMs)
            assertEquals(hard.ruleId, result.ruleId)
            assertEquals(30 * minute, remaining(result))
        }
    }

    @Test fun unknownOpenEndSuppressesAPromiseFromAStrongerFiniteRule() {
        val hard = ruleBlockCause(rule("hard", BreakLevel.HARDCORE, 5), now, 0)
        val open = BlockCause(BlockScreenReason.OPEN_LIMIT, BreakLevel.EASY, null)
        assertNull(remaining(BlockOverlapPolicy.strongest(hard, open)!!))
        assertNull(remaining(BlockOverlapPolicy.strongest(open, hard)!!))
    }

    // PC-72: supported domain + browser-app owners compose; Path B has no domain attribution.
    @Test fun domainAndBrowserAppOwnersShareTheCombinedEndWithoutInventingFallbackDomainCoverage() {
        val hard = rule("app", BreakLevel.HARDCORE, 5)
        val domain = rule("site", BreakLevel.EASY, 20).copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        assertEquals(20 * minute, remaining(project(listOf(hard, domain), url = "https://sub.example.com/page")))
        assertEquals(5 * minute, remaining(project(listOf(hard, domain))))
    }

    @Test fun inactiveExpiredAndUnrelatedRulesDoNotExtendTheDisplayedEnd() {
        val hard = rule("hard", BreakLevel.HARDCORE, 5)
        val long = rule("long", BreakLevel.EASY, 30)
        val rows = listOf(hard, long.copy(id = "disabled", enabled = false),
            long.copy(id = "expired", expiresAtEpochMs = now),
            long.copy(id = "unrelated", targets = BlockTargets(apps = setOf(AppRef("other")))),
            long.copy(id = "later", schedule = Schedule(startMinuteOfDay = 600, endMinuteOfDay = 660)))
        assertEquals(5 * minute, remaining(project(rows)))
    }

    @Test fun retainedDisplayProjectionDoesNotKeepARemovedContributorInTheNextReduction() {
        val hard = ruleBlockCause(rule("hard", BreakLevel.HARDCORE, 5), now, 0)
        val easy = ruleBlockCause(rule("easy", BreakLevel.EASY, 20), now, 0)
        val old = BlockOverlapPolicy.strongest(hard, easy)!!
        assertEquals(20 * minute, remaining(old))
        assertEquals(hard, BlockOverlapPolicy.strongest(hard, old))
    }

    @Test fun staticSafetyFloorNeverReceivesAnUnblockPromiseEvenFromABroadRule() {
        val broad = rule("broad", BreakLevel.HARDCORE, 20).copy(targets = BlockTargets(allowListMode = true))
        val cause = ruleBlockCause(broad, now, 0)
        for (floor in listOf(self, "com.android.settings", "com.android.systemui")) {
            val projected = BlockDisplayEndProjection.forTarget(floor, null, listOf(broad), self, cause, now, 0)
            assertNull(remaining(projected))
        }
    }
}
