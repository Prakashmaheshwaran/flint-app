package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.Schedule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the draft ↔ [BlockRule] mapping and the save validation the rule editor
 * gates on. The Compose layer is a thin projection of [RuleDraft]; the logic lives here.
 */
class RuleDraftTest {

    private fun validDraft() = RuleDraft(
        name = "Social",
        apps = setOf(AppRef("com.instagram.android", "Instagram")),
    )

    // ---- Mapping: rule → draft → rule ----

    @Test
    fun `scheduled rule round-trips exactly`() {
        val rule = BlockRule(
            id = "r1",
            name = "Work hours",
            targets = BlockTargets(
                apps = setOf(AppRef("a.b.c", "ABC"), AppRef("x.y.z", null)),
                domains = setOf(DomainRef("youtube.com"), DomainRef("reddit.com")),
                allowListMode = false,
            ),
            schedule = Schedule(
                daysOfWeek = setOf(1, 2, 3, 4, 5),
                startMinuteOfDay = 540,
                endMinuteOfDay = 1020,
            ),
            breakLevel = BreakLevel.HARDER,
            enabled = false,
        )
        assertEquals(rule, ruleDraftOf(rule).toRule(id = rule.id))
    }

    @Test
    fun `manual rule keeps its null schedule through the round-trip`() {
        val rule = BlockRule(
            id = "r2",
            name = "Manual",
            targets = BlockTargets(apps = setOf(AppRef("x.y", "X"))),
            schedule = null,
            breakLevel = BreakLevel.EASY,
            enabled = true,
        )
        assertEquals(rule, ruleDraftOf(rule).toRule(id = rule.id))
    }

    @Test
    fun `allow-list rule round-trips with mode intact`() {
        val rule = BlockRule(
            id = "r3",
            name = "Brick phone",
            targets = BlockTargets(
                apps = setOf(AppRef("org.thoughtcrime.securesms", "Signal")),
                allowListMode = true,
            ),
            breakLevel = BreakLevel.HARDCORE,
        )
        assertEquals(rule, ruleDraftOf(rule).toRule(id = rule.id))
    }

    @Test
    fun `new draft starts enabled unscheduled with the given default break level`() {
        val draft = newRuleDraft(BreakLevel.HARDCORE)
        assertNull(draft.id)
        assertEquals(BreakLevel.HARDCORE, draft.breakLevel)
        assertTrue(draft.enabled)
        assertFalse(draft.scheduled)
        assertTrue(draft.apps.isEmpty())
        assertTrue(draft.domains.isEmpty())
    }

    @Test
    fun `toRule trims the name`() {
        assertEquals("Social", validDraft().copy(name = "  Social  ").toRule(id = "x").name)
    }

    @Test
    fun `toRule drops out-of-range day numbers`() {
        val rule = validDraft().copy(scheduled = true, days = setOf(0, 1, 7, 8)).toRule(id = "x")
        assertEquals(setOf(1, 7), rule.schedule?.daysOfWeek)
    }

    @Test
    fun `unscheduled draft produces a null schedule regardless of window fields`() {
        val rule = validDraft()
            .copy(scheduled = false, startMinuteOfDay = -1, endMinuteOfDay = -1)
            .toRule(id = "x")
        assertNull(rule.schedule)
    }

    @Test
    fun `domains survive toRule even in allow-list mode`() {
        val rule = validDraft()
            .copy(allowListMode = true, domains = listOf("news.com"))
            .toRule(id = "x")
        assertEquals(setOf(DomainRef("news.com")), rule.targets.domains)
        assertTrue(rule.targets.allowListMode)
    }

    // ---- Validation ----

    @Test
    fun `valid draft has no errors`() {
        assertTrue(validate(validDraft()).isEmpty())
    }

    @Test
    fun `blank name is rejected`() {
        assertTrue(RuleDraftError.NAME_REQUIRED in validate(validDraft().copy(name = "   ")))
    }

    @Test
    fun `block mode with no targets is rejected`() {
        assertTrue(RuleDraftError.NO_TARGETS in validate(RuleDraft(name = "Empty")))
    }

    @Test
    fun `domains alone satisfy block mode`() {
        assertTrue(validate(RuleDraft(name = "Sites", domains = listOf("youtube.com"))).isEmpty())
    }

    @Test
    fun `allow-list without apps is rejected even when domains exist`() {
        val errors = validate(
            RuleDraft(name = "Brick", allowListMode = true, domains = listOf("a.com")),
        )
        assertTrue(RuleDraftError.NO_ALLOWED_APPS in errors)
        assertFalse(RuleDraftError.NO_TARGETS in errors)
    }

    @Test
    fun `allow-list with at least one app is valid`() {
        assertTrue(validate(validDraft().copy(allowListMode = true)).isEmpty())
    }

    @Test
    fun `equal start and end are rejected when scheduled - the engine never matches them`() {
        val errors = validate(
            validDraft().copy(scheduled = true, startMinuteOfDay = 600, endMinuteOfDay = 600),
        )
        assertTrue(RuleDraftError.ZERO_LENGTH_WINDOW in errors)
    }

    @Test
    fun `overnight window is valid - the engine wraps it across midnight`() {
        val errors = validate(
            validDraft().copy(scheduled = true, startMinuteOfDay = 1320, endMinuteOfDay = 360),
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `out-of-range minutes are rejected when scheduled`() {
        val errors = validate(
            validDraft().copy(scheduled = true, startMinuteOfDay = -1, endMinuteOfDay = 600),
        )
        assertTrue(RuleDraftError.INVALID_TIME in errors)
    }

    @Test
    fun `window problems are ignored when not scheduled`() {
        val errors = validate(
            validDraft().copy(scheduled = false, startMinuteOfDay = -1, endMinuteOfDay = -1),
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `multiple problems are all reported`() {
        val errors = validate(
            RuleDraft(name = " ", scheduled = true, startMinuteOfDay = 300, endMinuteOfDay = 300),
        )
        assertTrue(RuleDraftError.NAME_REQUIRED in errors)
        assertTrue(RuleDraftError.NO_TARGETS in errors)
        assertTrue(RuleDraftError.ZERO_LENGTH_WINDOW in errors)
    }

    @Test
    fun `every error carries a non-blank user-facing message`() {
        RuleDraftError.entries.forEach { assertTrue(it.message.isNotBlank()) }
    }

    // ---- One-time windows ----

    private val oneTime = validDraft().copy(
        scheduled = true,
        oneTime = true,
        date = LocalDate.parse("2026-11-20"),
        days = setOf(1, 2, 3),
    )
    private val earlyFriday = LocalDateTime.of(2026, 11, 20, 8, 0)
    private val utc = ZoneId.of("UTC")

    @Test
    fun `one time draft persists a dated window with no weekday gate`() {
        val schedule = oneTime.toRule(id = "x").schedule!!
        assertEquals(LocalDate.parse("2026-11-20"), schedule.date)
        assertTrue(schedule.daysOfWeek.isEmpty())
        assertEquals(9 * 60, schedule.startMinuteOfDay)
        assertEquals(17 * 60, schedule.endMinuteOfDay)
    }

    @Test
    fun `dated rule loads back into a one time draft`() {
        val rule = oneTime.toRule(id = "x")
        val draft = ruleDraftOf(rule)
        assertTrue(draft.scheduled)
        assertTrue(draft.oneTime)
        assertEquals(LocalDate.parse("2026-11-20"), draft.date)
        assertEquals(rule, draft.toRule(id = rule.id))
        assertFalse(ruleDraftOf(validDraft().copy(scheduled = true, days = setOf(1)).toRule(id = "y")).oneTime)
    }

    @Test
    fun `one time draft needs a date`() {
        val errors = validate(oneTime.copy(date = null), earlyFriday, utc)
        assertTrue(errors.contains(RuleDraftError.DATE_REQUIRED))
    }

    @Test
    fun `one time start must not already be in the past`() {
        assertTrue(validate(oneTime, earlyFriday, utc).isEmpty())
        // 60 s grace covers the composer's second of skew.
        assertTrue(validate(oneTime, LocalDateTime.of(2026, 11, 20, 9, 0, 30), utc).isEmpty())
        assertTrue(validate(oneTime, LocalDateTime.of(2026, 11, 20, 9, 1), utc).isEmpty())
        assertTrue(validate(oneTime, LocalDateTime.of(2026, 11, 20, 9, 2), utc).contains(RuleDraftError.START_IN_PAST))
        assertTrue(validate(oneTime.copy(date = LocalDate.parse("2026-11-19")), earlyFriday, utc)
            .contains(RuleDraftError.START_IN_PAST))
    }

    @Test
    fun `an active or finished one time rule keeps its past start when edited`() {
        // Saved 09:00-17:00 on Nov 20; it is now 10:00 that day (active) and later (finished).
        val saved = oneTime.copy(id = "saved")
        val active = LocalDateTime.of(2026, 11, 20, 10, 0)
        val finished = LocalDateTime.of(2026, 11, 22, 10, 0)
        for (now in listOf(active, finished)) {
            // Extending the end, renaming or adding a target is an ordinary edit; the commit-time
            // tier gate decides its rights, so the pre-save gate must not refuse it.
            assertTrue(validate(saved.copy(endMinuteOfDay = 18 * 60), now, utc, original = saved).isEmpty())
            assertTrue(validate(saved.copy(name = "Renamed"), now, utc, original = saved).isEmpty())
            // Moving the start (or the date) is a new window and must still be in the future.
            assertTrue(validate(saved.copy(startMinuteOfDay = 9 * 60 + 30), now, utc, original = saved)
                .contains(RuleDraftError.START_IN_PAST))
            assertTrue(validate(saved.copy(date = LocalDate.parse("2026-11-19")), now, utc, original = saved)
                .contains(RuleDraftError.START_IN_PAST))
        }
        // A finished rule moved to a future date is a fresh pending window.
        assertTrue(validate(saved.copy(date = LocalDate.parse("2026-11-30")), finished, utc, original = saved).isEmpty())
        // Without a saved baseline (a new rule) the same past window is refused.
        assertTrue(validate(saved.copy(endMinuteOfDay = 18 * 60), active, utc).contains(RuleDraftError.START_IN_PAST))
    }

    @Test
    fun `one time overnight window is valid and ends the next day`() {
        val overnight = oneTime.copy(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        assertTrue(validate(overnight, earlyFriday, utc).isEmpty())
        // Its start is still the dated evening, so the window exists and is not zero length.
        val schedule = overnight.toRule(id = "x").schedule!!
        assertEquals(LocalDate.parse("2026-11-20"), schedule.date)
    }

    @Test
    fun `one time window keeps the shared time checks`() {
        assertTrue(validate(oneTime.copy(startMinuteOfDay = 600, endMinuteOfDay = 600), earlyFriday, utc)
            .contains(RuleDraftError.ZERO_LENGTH_WINDOW))
        assertTrue(validate(oneTime.copy(startMinuteOfDay = -1), earlyFriday, utc)
            .contains(RuleDraftError.INVALID_TIME))
    }

    @Test
    fun `weekly draft never gains a date`() {
        assertNull(oneTime.copy(oneTime = false).toRule(id = "x").schedule?.date)
        assertNull(validDraft().toRule(id = "x").schedule)
    }
}
