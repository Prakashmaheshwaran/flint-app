package com.flint.peakfocus.blocking.overlay

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Production render seam and fallback Intent round trip; no emulator/device claim. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BlockDisplayEndCoordinatorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = 1_800_000_000_000L
    private val pkg = "com.example.target"
    private fun rule(id: String, tier: BreakLevel, duration: Long?) = BlockRule(id, id,
        BlockTargets(apps = setOf(AppRef(pkg))), breakLevel = tier, expiresAtEpochMs = duration?.let { now + it })
    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }
    private fun coordinator(rules: List<BlockRule>, pathB: Boolean): BlockScreenCoordinator {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, rules)
        val focus = InMemoryFocusStateStore()
        val limits = InMemoryLimitsStore()
        val store = InMemoryBlockRulesStore(rules)
        val safety = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet())
        return if (pathB) PathBBlockHandoff.create(app, scope, host, focus, limits, store, safety, { now })
        else BlockScreenCoordinator(app, scope, host, nowEpochMs = { now }, focusStateStore = focus,
            configuredLimitsStore = limits, blockRulesStore = store, safetyFloorGate = safety)
    }
    @After fun clean() {
        scope.cancel()
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        BlockExemptions.clearAll()
        OpenVisits.process.leave()
    }

    @Test fun bothDetectorCoordinatorsRenderTheLatestMixedTierRuleEndAndRetainHardcoreRights() {
        val hard = rule("hard", BreakLevel.HARDCORE, 300_000)
        val easy = rule("easy", BreakLevel.EASY, 1_200_000)
        for (pathB in listOf(false, true)) for (rules in listOf(listOf(hard, easy), listOf(easy, hard))) {
            val c = coordinator(rules, pathB)
            val cause = c.evaluateBlockCause(null, pkg, ruleBlockCause(hard, now, 0), false)!!
            val state = c.screenState(pkg, null, cause, now)
            assertEquals(1_200_000L, state.remainingMillis)
            assertEquals(BreakLevel.HARDCORE, state.breakLevel)
            assertEquals(BlockScreenReason.DEEP_FOCUS, state.reason)
            assertEquals(now + 300_000, cause.endsAtEpochMs)
            assertEquals(GrantResult.Denied, runBlocking { c.requestBreak(pkg, cause, GrantRequestId.issue(now)) })
        }
    }

    @Test fun retainedSupportedBrowserUrlComposesDomainAndAppRuleEndsAtTheSharedRenderSeam() {
        val hard = rule("browser", BreakLevel.HARDCORE, 300_000)
        val site = rule("site", BreakLevel.EASY, 1_200_000)
            .copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        val c = coordinator(listOf(hard, site), false)
        val cause = c.evaluateBlockCause(null, pkg, ruleBlockCause(hard, now, 0), false, "https://example.com/page")!!
        assertEquals(1_200_000L, c.screenState(pkg, null, cause, now).remainingMillis)
    }

    @Test fun fallbackIntentRetainsTheCombinedFiniteLimitEndIndependentlyOfTheAuthorityExpiry() {
        val hard = ruleBlockCause(rule("hard", BreakLevel.HARDCORE, 300_000), now, 0)
        val time = timeLimitBlockCause(BreakLevel.EASY, now, 0)
        val cause = BlockOverlapPolicy.strongest(hard, time)!!
        val restored = roundTrip(cause)
        assertEquals(cause, restored)
        val c = coordinator(emptyList(), true)
        assertEquals(c.screenState(pkg, null, cause, now), c.screenState(pkg, null, restored, now))
        assertEquals(time.endsAtEpochMs!! - now, c.screenState(pkg, null, restored, now).remainingMillis)
        assertEquals(hard.endsAtEpochMs, restored.endsAtEpochMs)
    }

    @Test fun fallbackIntentRetainsAnUnknownCombinedEndAndLegacyIntentsKeepTheirOwnEnd() {
        val hard = ruleBlockCause(rule("hard", BreakLevel.HARDCORE, 300_000), now, 0)
        val unknown = BlockOverlapPolicy.strongest(hard, BlockCause(BlockScreenReason.OPEN_LIMIT, BreakLevel.EASY, null))!!
        val c = coordinator(emptyList(), true)
        assertNull(c.screenState(pkg, null, roundTrip(unknown), now).remainingMillis)
        assertEquals(hard, roundTrip(hard))
    }

    private fun roundTrip(cause: BlockCause): BlockCause {
        BlockActivity.launch(app, pkg, "Target", cause)
        val intent = shadowOf(app).nextStartedActivity
        val activity = Robolectric.buildActivity(BlockActivity::class.java).get()
        // Exercise the real launch parser without starting an unrelated Compose/ticker loop.
        val target = BlockActivity::class.java.getDeclaredMethod("toBlockedTarget", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)!!
        return target.javaClass.getDeclaredField("cause").apply { isAccessible = true }.get(target) as BlockCause
    }
}
