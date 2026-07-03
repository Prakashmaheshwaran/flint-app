package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.Schedule
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
}
