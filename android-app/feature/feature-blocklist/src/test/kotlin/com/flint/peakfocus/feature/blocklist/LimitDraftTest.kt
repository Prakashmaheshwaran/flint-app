package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the limits form: prefill, parsing, and validation. */
class LimitDraftTest {

    // ---- Prefill ----

    @Test
    fun `prefills from existing limits`() {
        val draft = limitDraftOf(
            packageName = "a.b",
            existingTime = TimeLimit("a.b", 45),
            existingOpen = OpenLimit("a.b", 5, BreakLevel.HARDER),
            defaultBreakLevel = BreakLevel.EASY,
        )
        assertTrue(draft.timeEnabled)
        assertEquals("45", draft.minutesText)
        assertTrue(draft.openEnabled)
        assertEquals("5", draft.opensText)
        assertEquals(BreakLevel.HARDER, draft.openBreakLevel)
    }

    @Test
    fun `prefills empty with the default break level when nothing exists`() {
        val draft = limitDraftOf("a.b", null, null, BreakLevel.HARDCORE)
        assertFalse(draft.timeEnabled)
        assertEquals("", draft.minutesText)
        assertFalse(draft.openEnabled)
        assertEquals("", draft.opensText)
        assertEquals(BreakLevel.HARDCORE, draft.openBreakLevel)
    }

    // ---- Resolution ----

    @Test
    fun `both sides enabled resolve to typed limits`() {
        val result = LimitDraft(
            packageName = "a.b",
            timeEnabled = true,
            minutesText = "45",
            openEnabled = true,
            opensText = "5",
            openBreakLevel = BreakLevel.HARDCORE,
        ).resolve()
        val valid = result as LimitFormResult.Valid
        assertEquals(TimeLimit("a.b", 45), valid.timeLimit)
        assertEquals(OpenLimit("a.b", 5, BreakLevel.HARDCORE), valid.openLimit)
    }

    @Test
    fun `a disabled side resolves to null - clear on save - and its text is never parsed`() {
        val result = LimitDraft(
            packageName = "a.b",
            timeEnabled = true,
            minutesText = "30",
            openEnabled = false,
            opensText = "garbage",
        ).resolve()
        val valid = result as LimitFormResult.Valid
        assertEquals(30, valid.timeLimit?.dailyMinutes)
        assertNull(valid.openLimit)
    }

    @Test
    fun `both sides disabled is not a save`() {
        val result = LimitDraft(packageName = "a.b").resolve()
        assertTrue(result is LimitFormResult.Invalid)
        assertTrue(LimitFormError.NOTHING_ENABLED in (result as LimitFormResult.Invalid).errors)
    }

    @Test
    fun `garbage or out-of-range minutes are rejected`() {
        listOf("", "abc", "0", "-5", "1441", "4.5").forEach { text ->
            val result = LimitDraft("a.b", timeEnabled = true, minutesText = text).resolve()
            assertTrue(
                "expected BAD_MINUTES for \"$text\"",
                result is LimitFormResult.Invalid &&
                    LimitFormError.BAD_MINUTES in result.errors,
            )
        }
    }

    @Test
    fun `garbage or out-of-range opens are rejected`() {
        listOf("", "x", "0", "501").forEach { text ->
            val result = LimitDraft("a.b", openEnabled = true, opensText = text).resolve()
            assertTrue(
                "expected BAD_OPENS for \"$text\"",
                result is LimitFormResult.Invalid &&
                    LimitFormError.BAD_OPENS in result.errors,
            )
        }
    }

    @Test
    fun `bounds are inclusive`() {
        assertTrue(LimitDraft("a.b", timeEnabled = true, minutesText = "1").resolve() is LimitFormResult.Valid)
        assertTrue(LimitDraft("a.b", timeEnabled = true, minutesText = "1440").resolve() is LimitFormResult.Valid)
        assertTrue(LimitDraft("a.b", openEnabled = true, opensText = "1").resolve() is LimitFormResult.Valid)
        assertTrue(LimitDraft("a.b", openEnabled = true, opensText = "500").resolve() is LimitFormResult.Valid)
    }

    @Test
    fun `errors accumulate across both sides`() {
        val result = LimitDraft(
            packageName = "a.b",
            timeEnabled = true,
            minutesText = "no",
            openEnabled = true,
            opensText = "no",
        ).resolve()
        val invalid = result as LimitFormResult.Invalid
        assertTrue(LimitFormError.BAD_MINUTES in invalid.errors)
        assertTrue(LimitFormError.BAD_OPENS in invalid.errors)
    }

    @Test
    fun `whitespace around numbers is tolerated`() {
        val result = LimitDraft("a.b", timeEnabled = true, minutesText = " 45 ").resolve()
        assertTrue(result is LimitFormResult.Valid)
        assertEquals(45, (result as LimitFormResult.Valid).timeLimit?.dailyMinutes)
    }

    @Test
    fun `every error carries a non-blank user-facing message`() {
        LimitFormError.entries.forEach { assertTrue(it.message.isNotBlank()) }
    }
}
