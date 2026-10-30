package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fixture-only rendering at 320dp / 2x; real data arrives through lane C's reader. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDetailLargeTextTest {
    @get:Rule val compose = createComposeRule()

    private fun narrow(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            FlintTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { content() } }
        }
    }

    @Test fun detailShowsResolvedHeadlineRowsAndHintAtLargeText() {
        val cause = AppDetailCause("k", "o", "r", "Deep work", AppDetailKind.SCHEDULE, BreakLevel.HARDCORE,
            active = true, enforcingNow = true, endsAtWall = null, ongoing = true)
        val snapshot = AppDetailSnapshot(
            AppDetailReadiness.READY,
            AppDetailResolvedState.Blocked("Deep work", BreakLevel.HARDCORE, 0, null, ongoing = true),
            listOf(cause), credits = AppDetailCredits(true, 2, 3),
        )
        narrow { AppDetailContent("Video app", appDetailPresentation(snapshot), nowMs = 0L, onBack = {}) }
        compose.onNodeWithText("Video app").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Blocked now by Deep work (Hardcore), with no set end.").assertExists()
        compose.onNodeWithText("Schedule · Hardcore", substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open credits used today: 2 of 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("To change what covers this, edit the rule in Rules.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test fun unavailableShowsNoRowsOrCounters() {
        narrow {
            AppDetailContent("Video app", appDetailPresentation(AppDetailSnapshot(AppDetailReadiness.UNAVAILABLE, null)), 0L) {}
        }
        compose.onNodeWithText("Protection state is unavailable right now, so Flint won't guess what covers this.").assertExists()
        compose.onAllNodesWithText("Open credits used today", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Covering it", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun listOpensATargetAndExplainsAllowListRules() {
        var opened: PickedTarget? = null
        val target = PickedTarget(AppDetailTarget.Site("news.example"), "news.example")
        narrow { AppsAndSitesList(PickedTargetsState.Ready(listOf(target), hasAllowListRules = true), { opened = it }, {}) }
        compose.onNodeWithText("news.example").performScrollTo().performClick()
        compose.runOnIdle { check(opened == target) }
        compose.onNodeWithText("Sleep and allow-list rules", substring = true).performScrollTo().assertIsDisplayed()
    }
}
