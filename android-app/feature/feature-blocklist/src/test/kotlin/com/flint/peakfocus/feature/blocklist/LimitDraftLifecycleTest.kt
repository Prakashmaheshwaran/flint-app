package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Form-only D6 evidence; durable off/on accounting is tested at the serialized store. */
class LimitDraftLifecycleTest {
    @Test fun `D6 existing mixed tiers ignore a changed default`() {
        BreakLevel.entries.forEach { timeTier ->
            BreakLevel.entries.forEach { openTier ->
                val time = TimeLimit("app", 30, timeTier)
                val open = OpenLimit("app", 5, openTier)
                BreakLevel.entries.forEach { default ->
                    val result = limitDraftOf("app", time, open, default).resolve() as LimitFormResult.Valid
                    assertEquals(time, result.timeLimit)
                    assertEquals(open, result.openLimit)
                }
            }
        }
    }

    @Test fun `D6 editing Time tier preserves Open tier and budget`() {
        val open = OpenLimit("app", 5, BreakLevel.HARDCORE)
        val draft = limitDraftOf("app", TimeLimit("app", 30, BreakLevel.HARDER), open, BreakLevel.EASY)
        val result = draft.copy(timeBreakLevel = BreakLevel.EASY, minutesText = "15").resolve() as LimitFormResult.Valid
        assertEquals(TimeLimit("app", 15, BreakLevel.EASY), result.timeLimit)
        assertEquals(open, result.openLimit)
    }

    @Test fun `D6 editing Open tier preserves Time tier and budget`() {
        val time = TimeLimit("app", 30, BreakLevel.HARDCORE)
        val draft = limitDraftOf("app", time, OpenLimit("app", 5, BreakLevel.HARDER), BreakLevel.EASY)
        val result = draft.copy(openBreakLevel = BreakLevel.EASY, opensText = "3").resolve() as LimitFormResult.Valid
        assertEquals(time, result.timeLimit)
        assertEquals(OpenLimit("app", 3, BreakLevel.EASY), result.openLimit)
    }

    @Test fun `D6 toggling a draft off and back on keeps its distinct tier and digits`() {
        val time = TimeLimit("app", 45, BreakLevel.HARDER)
        val open = OpenLimit("app", 7, BreakLevel.HARDCORE)
        val original = limitDraftOf("app", time, open, BreakLevel.EASY)
        val off = original.copy(timeEnabled = false, openEnabled = false)
        val result = off.copy(timeEnabled = true, openEnabled = true).resolve() as LimitFormResult.Valid
        assertEquals(time, result.timeLimit)
        assertEquals(open, result.openLimit)
    }

    @Test fun `D6 adding missing kind uses default without replacing saved kind tier`() {
        val time = TimeLimit("app", 30, BreakLevel.HARDCORE)
        val result = limitDraftOf("app", time, null, BreakLevel.HARDER)
            .copy(openEnabled = true, opensText = "5").resolve() as LimitFormResult.Valid
        assertEquals(time, result.timeLimit)
        assertEquals(OpenLimit("app", 5, BreakLevel.HARDER), result.openLimit)
    }
}
