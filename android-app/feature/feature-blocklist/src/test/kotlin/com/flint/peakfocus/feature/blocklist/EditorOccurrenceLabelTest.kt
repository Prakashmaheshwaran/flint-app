package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.AccessClockStamp
import org.junit.Assert.*
import org.junit.Test

class EditorOccurrenceLabelTest {
    private val stamp = AccessClockStamp(1_000, 100, "boot")
    private val state = EditorCommitmentState(
        pending = PendingEditorChange(1, EditorChange("Delete", deleteIds = setOf("work")),
            EditorExpected(emptyList(), null, null, mapOf("rule:work" to "occurrence")), CommitmentPolicy.Outcome.DEFER),
        causes = mapOf("rule:work" to EditorCause("occurrence", AnchoredInterval(stamp, 60_000, 61_000), "UTC")),
    )

    @Test fun `PC-21 displayed end uses remaining anchored time instead of a persisted wall estimate`() {
        assertEquals(64_000L, editorCurrentOccurrenceEnd(state, AccessClockStamp(5_000, 1_100, "boot")))
    }

    @Test fun `PC-21 stale ended or replaced occurrence has no current-end label`() {
        assertNull(editorCurrentOccurrenceEnd(state, AccessClockStamp(100_000, 100_000, "boot")))
        val replaced = state.copy(causes = state.causes.mapValues { it.value.copy(occurrenceId = "next") })
        assertNull(editorCurrentOccurrenceEnd(replaced, stamp))
    }
}
