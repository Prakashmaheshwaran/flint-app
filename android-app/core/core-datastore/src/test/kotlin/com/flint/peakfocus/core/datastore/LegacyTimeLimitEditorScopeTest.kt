package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LegacyTimeLimitEditorScopeTest {
    @Test fun PC_70_literalOldScopeKeepsAllRequestedOutputsWithoutTheNewModelField() {
        val change = EditorChange("Edit limit", deleteIds = linkedSetOf("first", "second"),
            limits = EditorLimitsChange("app", TimeLimit("app", 30, BreakLevel.HARDER), OpenLimit("app", 2, BreakLevel.EASY)))
        val expected = "EditorChange(label=Edit limit, upserts=[], deleteIds=[first, second], " +
            "limits=EditorLimitsChange(packageName=app, time=TimeLimit(packageName=app, dailyMinutes=30, breakLevel=HARDER), " +
            "open=OpenLimit(packageName=app, dailyOpens=2, breakLevel=EASY)), timerExit=false)"
        assertEquals(expected, legacyTimeLimitEditorScope(change))
        assertNotEquals(expected, change.toString())
    }

    @Test fun PC_70_scopeLikeTextInsideLabelsAndRulesIsPreservedVerbatim() {
        val lookalike = "TimeLimit(packageName=app, dailyMinutes=30, breakLevel=HARDER, zoneId=null)"
        val change = EditorChange(lookalike,
            upserts = listOf(BlockRule("r", lookalike, BlockTargets())),
            limits = EditorLimitsChange("app", TimeLimit("app", 30, BreakLevel.HARDER), null))
        val old = legacyTimeLimitEditorScope(change)
        assertTrue(old.startsWith("EditorChange(label=$lookalike, upserts=[BlockRule(id=r, name=$lookalike,"))
        assertTrue(old.contains("limits=EditorLimitsChange(packageName=app, time=TimeLimit(packageName=app, dailyMinutes=30, breakLevel=HARDER), open=null)"))
        // A broad replacement would also rewrite the user's label/rule name into another scope.
        assertNotEquals(change.toString().replace(", zoneId=null", ""), old)
    }

    @Test fun PC_70_nonTimeOperationsKeepTheirExactRepresentation() {
        for (change in listOf(EditorChange("Stop", deleteIds = setOf("timer"), timerExit = true),
            EditorChange("Open only", limits = EditorLimitsChange("app", null, OpenLimit("app", 1))))) {
            assertEquals(change.toString(), legacyTimeLimitEditorScope(change))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun PC_71_aSavedZoneCannotBeDisguisedAsLegacyAuthority() {
        legacyTimeLimitEditorScope(EditorChange("Modern", limits =
            EditorLimitsChange("app", TimeLimit("app", 30, BreakLevel.HARDER, "UTC"), null)))
    }
}
