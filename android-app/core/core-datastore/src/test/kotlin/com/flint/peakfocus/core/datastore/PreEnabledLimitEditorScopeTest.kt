package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import org.junit.Assert.assertEquals
import org.junit.Test

class PreEnabledLimitEditorScopeTest {
    @Test fun `PC_70 literal v3 v4 mixed-limit scope preserves saved zone and separate tiers`() {
        val change = EditorChange("Save limits", limits = EditorLimitsChange("app",
            TimeLimit("app", 30, BreakLevel.HARDCORE), OpenLimit("app", 5, BreakLevel.HARDER)))
        assertEquals("EditorChange(label=Save limits, upserts=[], deleteIds=[], limits=EditorLimitsChange(packageName=app, time=TimeLimit(packageName=app, dailyMinutes=30, breakLevel=HARDCORE, zoneId=America/New_York), open=OpenLimit(packageName=app, dailyOpens=5, breakLevel=HARDER)), timerExit=false)",
            preEnabledLimitEditorScope(change, "America/New_York"))
    }

    @Test fun `PC_70 an uncommitted Time draft keeps literal null zone in the old scope`() {
        val change = EditorChange("New", limits = EditorLimitsChange("app", TimeLimit("app", 0), null))
        assertEquals("EditorChange(label=New, upserts=[], deleteIds=[], limits=EditorLimitsChange(packageName=app, time=TimeLimit(packageName=app, dailyMinutes=0, breakLevel=EASY, zoneId=null), open=null), timerExit=false)",
            preEnabledLimitEditorScope(change, null))
    }

    @Test fun `PC_70 lookalike model text in labels is never rewritten`() {
        val label = "OpenLimit(packageName=app, dailyOpens=5, breakLevel=HARDER, enabled=false)\nTimeLimit(zoneId=null, enabled=true)"
        val change = EditorChange(label, limits = EditorLimitsChange("app", null, OpenLimit("app", 5, BreakLevel.HARDER)))
        assertEquals("EditorChange(label=$label, upserts=[], deleteIds=[], limits=EditorLimitsChange(packageName=app, time=null, open=OpenLimit(packageName=app, dailyOpens=5, breakLevel=HARDER)), timerExit=false)",
            preEnabledLimitEditorScope(change, null))
    }

    @Test fun `PC_70 delete scope keeps both absent sides and caller set order`() {
        val change = EditorChange("Remove", deleteIds = linkedSetOf("z", "a"),
            limits = EditorLimitsChange("app", null, null), timerExit = true)
        assertEquals("EditorChange(label=Remove, upserts=[], deleteIds=[z, a], limits=EditorLimitsChange(packageName=app, time=null, open=null), timerExit=true)",
            preEnabledLimitEditorScope(change, null))
    }

    @Test fun `non-limit scope stays exactly unchanged`() {
        val change = EditorChange("Remove TimeLimit(zoneId=null)", deleteIds = linkedSetOf("z", "a"))
        assertEquals(change.toString(), preEnabledLimitEditorScope(change, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a supplied Time zone without a Time draft cannot manufacture a scope`() {
        preEnabledLimitEditorScope(EditorChange("Remove", limits = EditorLimitsChange("app", null, null)), "UTC")
    }
}
