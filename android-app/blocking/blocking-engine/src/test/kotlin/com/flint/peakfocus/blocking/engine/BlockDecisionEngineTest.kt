package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockDecisionEngineTest {

    private val engine = BlockDecisionEngine()
    private val self = "com.flint.peakfocus"

    private fun rule(
        apps: Set<String> = emptySet(),
        domains: Set<String> = emptySet(),
        allowList: Boolean = false,
    ) = BlockRule(
        id = "r1",
        name = "test",
        targets = BlockTargets(
            apps = apps.map { AppRef(it) }.toSet(),
            domains = domains.map { DomainRef(it) }.toSet(),
            allowListMode = allowList,
        ),
    )

    @Test
    fun blocksListedApp() {
        val v = engine.decide("com.instagram.android", null, listOf(rule(apps = setOf("com.instagram.android"))), self)
        assertTrue(v is Verdict.Block)
    }

    @Test
    fun allowsUnlistedApp() {
        val v = engine.decide("com.spotify.music", null, listOf(rule(apps = setOf("com.instagram.android"))), self)
        assertEquals(Verdict.Allow, v)
    }

    @Test
    fun neverBlocksSelf() {
        val v = engine.decide(self, null, listOf(rule(allowList = true, apps = emptySet())), self)
        assertEquals(Verdict.Allow, v)
    }

    @Test
    fun allowListBlocksEverythingExceptAllowed() {
        val rules = listOf(rule(allowList = true, apps = setOf("com.android.dialer")))
        assertTrue(engine.decide("com.instagram.android", null, rules, self) is Verdict.Block)
        assertEquals(Verdict.Allow, engine.decide("com.android.dialer", null, rules, self))
    }

    @Test
    fun blocksMatchingDomainAndSubdomain() {
        val rules = listOf(rule(domains = setOf("reddit.com")))
        assertTrue(engine.decide("com.android.chrome", "https://www.reddit.com/r/all", rules, self) is Verdict.Block)
        assertTrue(engine.decide("com.android.chrome", "https://old.reddit.com/", rules, self) is Verdict.Block)
        assertEquals(Verdict.Allow, engine.decide("com.android.chrome", "https://example.com", rules, self))
    }

    @Test
    fun disabledRuleDoesNotBlock() {
        val r = rule(apps = setOf("com.instagram.android")).copy(enabled = false)
        assertEquals(Verdict.Allow, engine.decide("com.instagram.android", null, listOf(r), self))
    }

    @Test
    fun hostExtractionHandlesSchemesAndPaths() {
        assertEquals("reddit.com", engine.extractHost("https://www.reddit.com/r/all"))
        assertEquals("reddit.com", engine.extractHost("reddit.com"))
        assertEquals("sub.reddit.com", engine.extractHost("http://sub.reddit.com:8080/x?y=1"))
    }

    @Test
    fun scheduleGatingDayAndWindow() {
        // ISO numbering (1=Mon…7=Sun), per core-model's Schedule contract.
        val workHours = Schedule(daysOfWeek = setOf(1, 2, 3, 4, 5), startMinuteOfDay = 540, endMinuteOfDay = 1020) // Mon–Fri 9–17
        assertTrue(engine.scheduleActive(workHours, 600, 2))   // 10:00 Tue
        assertFalse(engine.scheduleActive(workHours, 600, 7))  // 10:00 Sun (off-day)
        assertFalse(engine.scheduleActive(workHours, 1100, 2)) // 18:20 Tue (after window)
        assertTrue(engine.scheduleActive(null, 600, 2))        // no schedule = always
        assertTrue(engine.scheduleActive(workHours, -1, 2))    // no time supplied = active
    }

    @Test
    fun scheduleGatingOvernightWindow() {
        val night = Schedule(startMinuteOfDay = 1320, endMinuteOfDay = 360) // 22:00–06:00
        assertTrue(engine.scheduleActive(night, 1380, -1)) // 23:00
        assertTrue(engine.scheduleActive(night, 120, -1))  // 02:00
        assertFalse(engine.scheduleActive(night, 720, -1)) // 12:00
    }

    @Test
    fun overnightTailIsGatedOnThePreviousDay() {
        // Mon 22:00–06:00, Mondays only. The post-midnight tail is Monday's window spilling
        // into Tuesday — it must block Tue 00:30 and must NOT block Mon 00:30 (that morning
        // belongs to Sunday's window, which isn't scheduled).
        val monNight = Schedule(daysOfWeek = setOf(1), startMinuteOfDay = 1320, endMinuteOfDay = 360)
        assertTrue(engine.scheduleActive(monNight, 1380, 1))  // Mon 23:00 — head, scheduled day
        assertTrue(engine.scheduleActive(monNight, 30, 2))    // Tue 00:30 — Monday's tail
        assertFalse(engine.scheduleActive(monNight, 30, 1))   // Mon 00:30 — Sunday's tail, off-day
        assertFalse(engine.scheduleActive(monNight, 390, 2))  // Tue 06:30 — past the tail
        assertFalse(engine.scheduleActive(monNight, 1380, 2)) // Tue 23:00 — head on an off-day
        assertTrue(engine.scheduleActive(monNight, 30, -1))   // unknown weekday → time-only gate
    }

    @Test
    fun overnightTailWrapsAcrossTheIsoWeekBoundary() {
        // Sun 23:00–01:00, Sundays only: Monday 00:30's previous ISO day wraps 1 → 7. Pure
        // minutes+ISO-day arithmetic — no Calendar/TimeZone involved, so timezone-agnostic.
        val sunNight = Schedule(daysOfWeek = setOf(7), startMinuteOfDay = 1380, endMinuteOfDay = 60)
        assertTrue(engine.scheduleActive(sunNight, 30, 1))  // Mon 00:30 — Sunday's tail
        assertFalse(engine.scheduleActive(sunNight, 30, 7)) // Sun 00:30 — Saturday's tail, off-day
    }

    @Test
    fun decideBlocksTheMorningTailOfAnOvernightRule() {
        val rule = BlockRule(
            id = "night", name = "sleep",
            targets = BlockTargets(apps = setOf(AppRef("com.x"))),
            schedule = Schedule(daysOfWeek = setOf(1), startMinuteOfDay = 1320, endMinuteOfDay = 360),
        )
        assertTrue(engine.decide("com.x", null, listOf(rule), self, 30, 2) is Verdict.Block) // Tue 00:30
        assertEquals(Verdict.Allow, engine.decide("com.x", null, listOf(rule), self, 30, 1)) // Mon 00:30
    }

    @Test
    fun decideRespectsSchedule() {
        val rule = BlockRule(
            id = "r", name = "work",
            targets = BlockTargets(apps = setOf(AppRef("com.x"))),
            schedule = Schedule(startMinuteOfDay = 540, endMinuteOfDay = 1020),
        )
        assertTrue(engine.decide("com.x", null, listOf(rule), self, 600, 3) is Verdict.Block) // inside
        assertEquals(Verdict.Allow, engine.decide("com.x", null, listOf(rule), self, 1100, 3)) // outside
    }
}
