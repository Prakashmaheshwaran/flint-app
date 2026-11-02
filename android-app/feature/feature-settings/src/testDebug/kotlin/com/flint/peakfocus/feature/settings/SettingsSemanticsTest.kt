package com.flint.peakfocus.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.Oem
import com.flint.peakfocus.core.common.theme.FlintTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** TalkBack sees each grant once, with its subject, and expandable cards announce their state. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsSemanticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun row(granted: Boolean, affectsEnforcement: Boolean = true) = PermissionRowUi(
        kind = PermissionKind.OVERLAY,
        granted = granted,
        affectsEnforcement = affectsEnforcement,
        title = "Display over other apps",
        role = "Shows the block screen",
        disclosure = "Fixture disclosure",
        fixLabel = "Fixture fix",
    )

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    /** The header is one stop: title and role, plus exactly one On/Off state and no "ON"/"OFF" pill text. */
    private fun assertOneLabelledHeader(expectedState: String) {
        val merged = compose.onNodeWithText("Display over other apps", substring = true).assert(state(expectedState))
        val spoken = merged.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        assertEquals(listOf("Display over other apps", "Shows the block screen"), spoken)
        assertEquals(0, compose.onAllNodesWithText(expectedState.uppercase()).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(state(expectedState), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun missingGrantIsOneLabelledStopAndTheFixStaysSeparate() {
        val affectsEnforcement = mutableStateOf(true)
        var fixes = 0
        compose.setContent { FlintTheme { PermissionCard(row(false, affectsEnforcement.value), onFix = { fixes++ }) } }
        listOf(true, false).forEachIndexed { index, required ->
            compose.runOnIdle { affectsEnforcement.value = required }
            assertOneLabelledHeader("Off")
            // Required (filled) and recommended (outlined) fixes are their own actionable stop.
            val fix = compose.onNodeWithText("Fixture fix")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assertHasClickAction()
                .assertIsEnabled()
            fix.performClick()
            compose.runOnIdle { assertEquals(index + 1, fixes) }
        }
    }

    @Test fun grantedRowSaysOn() {
        compose.setContent { FlintTheme { PermissionCard(row(granted = true), onFix = {}) } }
        assertOneLabelledHeader("On")
        assertEquals(0, compose.onAllNodesWithText("Fixture fix").fetchSemanticsNodes().size)
    }

    @Test fun oemGuidanceAnnouncesCollapsedAndExpanded() {
        val guidance = OemGuidanceCatalog.forOem(Oem.SAMSUNG)
        compose.setContent { FlintTheme { OemGuidanceCard(guidance) } }
        val card = compose.onNodeWithText("Keep Flint running on ${guidance.displayName}", substring = true)
        card.assert(state("Collapsed"))
        assertEquals(0, compose.onAllNodesWithText(guidance.steps.first(), substring = true).fetchSemanticsNodes().size)
        card.performClick()
        compose.waitForIdle()
        card.assert(state("Expanded"))
        compose.onNodeWithText(guidance.steps.first(), substring = true).assertExists()
        card.performClick()
        compose.waitForIdle()
        card.assert(state("Collapsed"))
        assertEquals(0, compose.onAllNodesWithText(guidance.steps.first(), substring = true).fetchSemanticsNodes().size)
    }
}
