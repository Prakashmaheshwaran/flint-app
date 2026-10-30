package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SafetyFloorAsyncReevaluationTest {
    @After fun clean() {
        ActiveRulesHolder.publish(SOURCE, emptyList())
        ProtectionStatusHub.resetForTests()
        BlockExemptions.clearAll()
    }

    @Test fun PC_49_PC_53_pathAColdStationaryTargetBlocksAfterResolutionWithoutAnotherWindowEvent() = coldTarget(pathB = false)
    @Test fun PC_49_PC_53_pathBColdStationaryTargetBlocksAfterResolutionWithoutAnotherWindowEvent() = coldTarget(pathB = true)
    @Test fun PC_49_PC_53_pathAMountedShieldStaysAcrossDegradedDeferrals() = coldTarget(pathB = false, degraded = true)
    @Test fun PC_49_PC_53_pathBMountedShieldStaysAcrossDegradedDeferrals() = coldTarget(pathB = true, degraded = true)

    private fun coldTarget(pathB: Boolean, degraded: Boolean = false) = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val target = "com.stationary.target"
        val rule = BlockRule("cold-hardcore", "Existing protection", BlockTargets(allowListMode = true),
            breakLevel = BreakLevel.HARDCORE)
        val now = 1_800_000_000_000L
        val cause = ruleBlockCause(rule, now, 0)
        ActiveRulesHolder.publish(SOURCE, listOf(rule))
        var resolves = 0
        var pause = false
        val deferred = mutableListOf<() -> Unit>()
        val gate = SafetyFloorGate(app.packageName, {
            resolves++
            mapOf("home" to setOf("com.home"), "default-dialer" to if (degraded) null else setOf("com.dialer"),
                "candidate-home" to emptySet(), "candidate-calling" to setOf("com.dialer"))
        }, { testScheduler.currentTime }, refreshInBackground = { work ->
            if (pause) deferred += work else backgroundScope.launch { work() }
            Unit
        },
            requiredRuntimeSources = setOf("home", "default-dialer"))
        assertEquals("construction queues the first check without binder work", 0, resolves)
        val attached = mutableListOf<View>()
        var detaches = 0
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = attached.add(view)
            override fun detach(view: View) { detaches++; attached.remove(view) }
            override fun goHome() = Unit
        }
        val focus = InMemoryFocusStateStore()
        val limits = InMemoryLimitsStore()
        val rules = InMemoryBlockRulesStore(listOf(rule))
        val coordinator = if (pathB) PathBBlockHandoff.create(app, backgroundScope, host,
            focus, limits, rules, gate, nowEpochMs = { now })
        else BlockScreenCoordinator(app, backgroundScope, host, focusStateStore = focus,
            configuredLimitsStore = limits, blockRulesStore = rules, safetyFloorGate = gate, nowEpochMs = { now },
            statusDispatcher = StandardTestDispatcher(testScheduler))
        var reevaluations = 0
        val stop = EnforcementSignals.detectors.subscribe {
            reevaluations++
            coordinator.evaluateBlockCause(null, target, cause, false)?.let { coordinator.showBlocked(target, null, it) }
        }
        try {
            // The only foreground/window event. No later app switch or window callback occurs.
            assertNull(coordinator.evaluateBlockCause(null, target, cause, false))
            assertTrue(attached.isEmpty())
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(reevaluations > 0)
            assertEquals("initial plus one new-mount refresh", 2, resolves)
            assertEquals("stationary target mounts after fresh check", 1, attached.size)
            assertFalse(gate.runtimeResolution.value.checking)
            if (degraded) {
                pause = true
                repeat(3) {
                    advanceTimeBy(SafetyFloorGate.UNRESOLVED_RETRY_MS)
                    runCurrent()
                    repeat(2) {
                        assertNotNull(coordinator.evaluateBlockCause(null, target, cause, false))
                        coordinator.showBlocked(target, null, cause)
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals(1, attached.size)
                        assertEquals(0, detaches)
                    }
                }
                assertEquals("single flight across all polls", 1, deferred.size)
                assertEquals("no per-poll binder work", 2, resolves)
                deferred.removeAt(0).invoke()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(3, resolves)
                assertEquals(1, attached.size)
                assertEquals(0, detaches)
            }
        } finally {
            stop()
            coordinator.hideIfShown()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private companion object { const val SOURCE = "async-safety-test" }
}
