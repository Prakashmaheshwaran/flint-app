package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reproduces the 320dp/2x label collision found in the initial emulator pass. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SleepLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextTimeSummaryStacksAndWeekdaysRemainNamedCheckboxes() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                FlintTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        SleepEditorScreen(
                            initial = SleepConfig.DEFAULT,
                            appsState = InstalledAppsUiState.Ready(emptyList()),
                            onSave = {}, onClose = {},
                        )
                    }
                }
            }
        }
        val monday = compose.onNodeWithContentDescription("Monday")
        monday.performScrollTo().assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        val target = monday.fetchSemanticsNode().boundsInRoot
        assertTrue("Weekday touch target must be at least 48dp", target.width >= 48f && target.height >= 48f)

        compose.onNodeWithText("Bed 22:30").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wake 07:00").performScrollTo().assertIsDisplayed()
        val bed = compose.onNodeWithText("Bed 22:30").fetchSemanticsNode().boundsInRoot
        val wake = compose.onNodeWithText("Wake 07:00").fetchSemanticsNode().boundsInRoot
        assertTrue("Bed and wake summaries must not overlap at 320dp/2x", bed.bottom <= wake.top)
    }
}
