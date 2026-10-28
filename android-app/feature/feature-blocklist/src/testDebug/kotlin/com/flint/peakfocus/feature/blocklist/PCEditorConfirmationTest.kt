package com.flint.peakfocus.feature.blocklist

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PCEditorConfirmationTest {
    @get:Rule val compose = createComposeRule()
    private fun state(outcome: CommitmentPolicy.Outcome) = EditorCommitmentState(pending = PendingEditorChange(1,
        EditorChange("Turn off Work"), EditorExpected(emptyList(), null, null, emptyMap()), outcome))

    @Test fun `PC-21 matured wait enables explicit confirmation without submitting itself`() {
        val remaining = mutableStateOf<Long?>(60_000)
        var confirmed = 0
        compose.setContent { EditorCommitmentDialog(state(CommitmentPolicy.Outcome.WAIT), remaining.value, false, null, { confirmed++ }, {}) }
        compose.onNodeWithText("Confirm").assertIsNotEnabled()
        compose.onNodeWithText("Available in 1 minutes. Confirm the change when the wait finishes.").assertExists()
        compose.runOnIdle { remaining.value = 0 }
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText("Confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun `PC-11 future-only change has distinct explicit action`() {
        var confirmed = 0
        compose.setContent { EditorCommitmentDialog(state(CommitmentPolicy.Outcome.DEFER), null, false, null, { confirmed++ }, {}) }
        compose.onNodeWithText("Save for after this occurrence").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun `PC-53 missing wait state cannot enable confirmation`() {
        compose.setContent { EditorCommitmentDialog(state(CommitmentPolicy.Outcome.WAIT), null, false, null, {}, {}) }
        compose.onNodeWithText("Confirm").assertIsNotEnabled()
    }

    @Test fun `PC-11 queued future values and stale status are displayed separately`() {
        val queued = QueuedEditorChange(1, EditorChange("Save limits", limits = EditorLimitsChange("app", TimeLimit("app", 60), null)),
            EditorExpected(emptyList(), TimeLimit("app", 30, BreakLevel.HARDCORE), null, emptyMap()),
            mapOf("time:app" to AnchoredInterval(AccessClockStamp(0, 0, "boot"), 60_000, 60_000)), needsReview = true)
        compose.setContent { QueuedEditorChanges(listOf(queued)) }
        compose.onNodeWithText("Future limits: 1h/day (Easy)").assertExists()
        compose.onNodeWithText("Queued change needs review:", substring = true).assertExists()
    }
}
