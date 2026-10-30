package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreen
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import com.flint.peakfocus.feature.blockscreen.BlockScreenState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.junit.Assert.*
import java.util.TimeZone
import kotlinx.coroutines.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BlockDisplayEndRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun productionMixedOpenLimitAndRuleRenderAndAnnounceUnblocksWhileKeepingTheOpenLimitReason() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val now = 1_800_000_000_000L
        val pkg = "com.example.target"
        val offset = TimeZone.getDefault().getOffset(now).toLong()
        val limit = OpenLimit(pkg, 0, BreakLevel.HARDCORE)
        val reset = openLimitBlockCause(limit, now, offset).endsAtEpochMs!!
        val rule = BlockRule("schedule", "Longer schedule", BlockTargets(apps = setOf(AppRef(pkg))),
            breakLevel = BreakLevel.EASY, expiresAtEpochMs = reset + 1_200_000)
        val limits = InMemoryLimitsStore().also { it.setOpenLimit(limit) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
            val coordinator = PathBBlockHandoff.create(app, scope, object : BlockScreenCoordinator.SurfaceHost {
                override fun attach(view: View) = false
                override fun detach(view: View) = Unit
                override fun goHome() = Unit
            }, InMemoryFocusStateStore(), limits, InMemoryBlockRulesStore(listOf(rule)),
                SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet()), { now })
            val cause = coordinator.evaluateBlockCause(null, pkg, ruleBlockCause(rule, now, offset), false)!!
            val state = coordinator.screenState(pkg, null, cause, now)
            assertEquals(BlockScreenReason.OPEN_LIMIT, state.reason)
            assertEquals(BreakLevel.HARDCORE, state.breakLevel)
            assertTrue(state.combinedEnd)
            assertEquals(reset + 1_200_000 - now, state.remainingMillis)
            compose.setContent {
                FlintTheme {
                    BlockScreen(state, onDismiss = {}, onOpenFlint = {}, onBreakRequested = {}, onEmergencyPassRequested = {})
                }
            }
            compose.onNodeWithText("Unblocks in", substring = true, useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("Unblocks in", substring = true, useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Resets in", substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("Resets in", substring = true, useUnmergedTree = true).assertDoesNotExist()
        } finally {
            scope.cancel()
            ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
            BlockExemptions.clearAll()
            OpenVisits.process.leave()
        }
    }

    @Test fun unknownEndNeverRendersOrAnnouncesAnImmediateUnblockDuringTheExitAnimation() {
        val state = mutableStateOf(BlockScreenState("target", reason = BlockScreenReason.SCHEDULE,
            breakLevel = BreakLevel.HARDCORE, remainingMillis = 1_200_000))
        compose.setContent {
            FlintTheme {
                BlockScreen(state.value, onDismiss = {}, onOpenFlint = {}, onBreakRequested = {}, onEmergencyPassRequested = {})
            }
        }
        compose.onNodeWithText("Unblocks in 20m 0s", useUnmergedTree = true).assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(remainingMillis = null) }
        // Sample the transition's frames: autoAdvance would otherwise hide this regression.
        repeat(24) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Unblocks in 0s", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("Unblocks in 0 seconds", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Unblocks in 20m 0s", useUnmergedTree = true).assertDoesNotExist()
    }
}
