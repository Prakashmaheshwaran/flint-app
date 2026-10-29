package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OverlapHeadlineTest {
    private val hour = 3_600_000L
    private val day = 100 * 24 * hour
    private val now = day + 9 * hour + 30 * 60_000 + 45_123
    private val pkg = "com.target"
    private val engine = BlockDecisionEngine()
    private fun rule(id: String, end: Long?, level: BreakLevel = BreakLevel.HARDCORE) =
        BlockRule(id, id, BlockTargets(apps = setOf(AppRef(pkg))), breakLevel = level, expiresAtEpochMs = end)
    private fun decide(rules: List<BlockRule>, at: Long = now, minute: Int = 570, url: String? = null): Verdict =
        engine.decide(pkg, url, rules, "com.flint", minute, 1, at)
    private fun selected(rules: List<BlockRule>, at: Long = now, minute: Int = 570, url: String? = null) =
        (decide(rules, at, minute, url) as Verdict.Block).ruleId

    @Test fun R_33_PC_19_PC_25_equalTierLatestEndIsIndependentOfEveryOrder() {
        for (level in BreakLevel.entries) {
            val rules = listOf(rule("short", day + 10 * hour, level),
                rule("middle", day + 11 * hour, level), rule("last", day + 12 * hour, level))
            for (order in permutations(rules)) {
                assertEquals("last", selected(order))
                assertEquals("last", selected(order, day + 10 * hour, 600))
                assertEquals(Verdict.Allow, decide(order, day + 12 * hour, 720))
                assertEquals(3, order.size) // Selecting the headline never removes contributors.
            }
        }
    }

    @Test fun R_33_openEndedOutranksFiniteAtEqualTierButNeverAHigherTier() {
        for (level in BreakLevel.entries) {
            val ongoing = rule("ongoing", null, level)
            val finite = rule("finite", Long.MAX_VALUE, level)
            for (order in permutations(listOf(ongoing, finite))) assertEquals("ongoing", selected(order))
        }
        for (order in permutations(listOf(rule("ongoing", null, BreakLevel.EASY),
            rule("strict", day + 10 * hour, BreakLevel.HARDCORE)))) assertEquals("strict", selected(order))
    }

    @Test fun R_33_equalEndsUseStableIdForFiniteAndOngoingRules() {
        for (end in listOf(null, day + 12 * hour)) for (level in BreakLevel.entries) {
            val rules = listOf("z", "a", "m").map { rule(it, end, level) }
            for (order in permutations(rules)) assertEquals("a", selected(order))
        }
    }

    @Test fun R_33_legacySchedulesCompareTheirWindowEndIncludingOvernightAndMinuteRemainder() {
        val short = rule("short", null).copy(schedule = Schedule(startMinuteOfDay = 480, endMinuteOfDay = 600))
        val long = short.copy(id = "long", schedule = Schedule(startMinuteOfDay = 480, endMinuteOfDay = 720))
        for (order in permutations(listOf(short, long, rule("timer", day + 11 * hour)))) {
            assertEquals("long", selected(order))
        }
        assertEquals(day + 12 * hour, ruleWindowEnd(long, now, 570))
        val night = short.copy(id = "night", schedule = Schedule(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60))
        assertEquals(day + 30 * hour, ruleWindowEnd(night, day + 23 * hour + 45_123, 1380))
        assertEquals(day + 6 * hour, ruleWindowEnd(night, day + 2 * hour + 45_123, 120))
        for (order in permutations(listOf(night, rule("timer", day + 29 * hour)))) {
            assertEquals("night", selected(order, day + 23 * hour, 1380))
        }
        assertNull(ruleWindowEnd(long, now, -1)) // An absent local clock cannot invent an end.
    }

    @Test fun R_33_anchoredEndWinsOverRawWallExpiryAfterClockJump() {
        val start = AccessClockStamp(now, 1_000, "boot")
        val jumped = start.copy(wallEpochMs = day + 18 * hour, elapsedRealtimeMs = 61_000)
        fun anchored(id: String, remaining: Long) = rule(id, now + remaining).copy(runtime = RuleRuntime(
            id, start, remaining, now + remaining, "UTC", start))
        val projected = RuleClockProjection.project(listOf(anchored("short", hour), anchored("long", 3 * hour)), jumped)
        for (order in permutations(projected)) assertEquals("long", selected(order, jumped.wallEpochMs, 1080))
        assertEquals(jumped.wallEpochMs + 3 * hour - 60_000, ruleWindowEnd(projected.last(), jumped.wallEpochMs, 1080))
        val ongoing = projected.first().copy(runtime = projected.first().runtime!!.copy(
            evaluation = RuleEvaluation(true, null, null)))
        assertNull(ruleWindowEnd(ongoing, jumped.wallEpochMs, 1080))
        assertEquals("short", selected(listOf(projected.last(), ongoing), jumped.wallEpochMs, 1080))
    }

    @Test fun R_33_PC_72_domainAndAllowListContributorsUseTheSameOrderAndMatchingFilter() {
        val app = rule("app", day + 10 * hour)
        val site = rule("site", day + 12 * hour).copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val unrelated = site.copy(id = "unrelated", expiresAtEpochMs = day + 20 * hour,
            targets = BlockTargets(domains = setOf(DomainRef("different.com"))))
        for (order in permutations(listOf(app, site, unrelated))) {
            assertEquals("site", selected(order, url = "https://sub.example.com/path"))
            assertEquals("app", selected(order)) // Path B cannot invent a website match.
        }
        val allowList = site.copy(id = "allow-list", targets = BlockTargets(allowListMode = true))
        for (order in permutations(listOf(app, allowList))) assertEquals("allow-list", selected(order))
        assertEquals("app", selected(listOf(app, site.copy(enabled = false)), url = "https://example.com"))
    }

    private fun <T> permutations(values: List<T>): List<List<T>> = if (values.isEmpty()) listOf(emptyList())
        else values.indices.flatMap { i -> permutations(values.filterIndexed { index, _ -> index != i }).map { listOf(values[i]) + it } }
}
