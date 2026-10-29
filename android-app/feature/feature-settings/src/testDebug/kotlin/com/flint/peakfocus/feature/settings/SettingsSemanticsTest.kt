package com.flint.peakfocus.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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

    private fun row(granted: Boolean) = PermissionRowUi(
        kind = PermissionKind.OVERLAY,
        granted = granted,
        affectsEnforcement = true,
        title = "Display over other apps",
        role = "Shows the block screen",
        disclosure = "Fixture disclosure",
        fixLabel = "Fixture fix",
    )

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test fun missingGrantIsOneLabelledStopAndTheFixStaysSeparate() {
        compose.setContent { FlintTheme { PermissionCard(row(granted = false), onFix = {}) } }
        val merged = compose.onNodeWithText("Display over other apps", substring = true).assert(state("Off"))
        // The pill is visual only: the merged row speaks title and role, never "OFF".
        val spoken = merged.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        assertEquals(listOf("Display over other apps", "Shows the block screen"), spoken)
        assertEquals(0, compose.onAllNodesWithText("OFF").fetchSemanticsNodes().size)
        assertEquals(
            1,
            compose.onAllNodes(state("Off"), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Fixture fix").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun grantedRowSaysOn() {
        compose.setContent { FlintTheme { PermissionCard(row(granted = true), onFix = {}) } }
        compose.onNodeWithText("Display over other apps", substring = true).assert(state("On"))
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
