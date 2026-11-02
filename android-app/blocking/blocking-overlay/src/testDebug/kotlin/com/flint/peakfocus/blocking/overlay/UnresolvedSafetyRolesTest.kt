package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Both coordinator construction paths, real evaluate/revalidate/mount, no OS enforcement claim. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnresolvedSafetyRolesTest {
    private val dialer = "com.unknown.dialer"
    private val home = "com.unknown.home"
    private val control = "com.ordinary.target"
    private val rule = BlockRule("existing-hardcore-allow-list", "Existing protection",
        BlockTargets(apps = setOf(AppRef("com.allowed")), allowListMode = true),
        breakLevel = BreakLevel.HARDCORE)

    @After fun clean() {
        ActiveRulesHolder.publish(SOURCE, emptyList())
        ProtectionStatusHub.resetForTests()
        BlockExemptions.clearAll()
    }

    @Test fun PC_48_PC_50_PC_52_PC_53_pathACoordinatorCannotBlockUnknownSafetyRoles() =
        unresolvedRolesNeverBlock(pathB = false)

    @Test fun PC_48_PC_50_PC_52_PC_53_pathBCoordinatorCannotBlockUnknownSafetyRoles() =
        unresolvedRolesNeverBlock(pathB = true)

    private fun unresolvedRolesNeverBlock(pathB: Boolean) = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val focus = InMemoryFocusStateStore()
        val rules = InMemoryBlockRulesStore(listOf(rule))
        val packages = listOf(dialer, home)
        val limits = InMemoryLimitsStore(initialOpenLimits = packages.map {
            OpenLimit(it, 0, BreakLevel.HARDCORE)
        })
        var sources: Map<String, Set<String>?> = mapOf("home" to null, "default-dialer" to null,
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to setOf(home),
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to setOf(dialer))
        val gate = SafetyFloorGate(app.packageName, { sources }, { testScheduler.currentTime },
            requiredRuntimeSources = setOf("home", "default-dialer"))
        val attached = mutableListOf<View>()
        var homeRedirects = 0
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean = attached.add(view)
            override fun detach(view: View) { attached.remove(view) }
            override fun goHome() { homeRedirects++ }
        }
        val now = 1_800_000_000_000L
        ActiveRulesHolder.publish(SOURCE, listOf(rule))
        val coordinator = if (pathB) PathBBlockHandoff.create(app, backgroundScope, host,
            focus, limits, rules, gate, nowEpochMs = { now })
        else BlockScreenCoordinator(app, backgroundScope, host, focusStateStore = focus,
            configuredLimitsStore = limits, blockRulesStore = rules, safetyFloorGate = gate,
            nowEpochMs = { now }, statusDispatcher = StandardTestDispatcher(testScheduler),
            statusClock = { AccessClockStamp(now, testScheduler.currentTime, "boot") })
        runCurrent()
        val cause = ruleBlockCause(rule, now, 0)
        for (pkg in packages) {
            assertTrue("detector early return $pkg", coordinator.isSafetyFloor(pkg))
            assertNull("rule + spent time + open overlap $pkg",
                coordinator.evaluateBlockCause(null, pkg, cause, timeLimitSpent = true))
            assertNull("Path B Allow handoff $pkg", coordinator.evaluateBlockCause(null, pkg, null, false))
            assertNull("stale displayed cause $pkg", coordinator.currentBlockCause(pkg, cause))
            coordinator.showBlocked(pkg, null, cause)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("no mounted shield $pkg", attached.isEmpty())
        }
        assertEquals(0, homeRedirects)
        assertTrue(focus.state.snapshotOrNull()!!.openCounts.opensByPackage.isEmpty())
        assertEquals(listOf(rule), rules.rules.first())
        assertTrue(gate.runtimeResolution.value.degraded)
        assertNotNull("ordinary target stays blocked during role failure",
            coordinator.evaluateBlockCause(null, control, cause, true))
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        if (!pathB) assertTrue("health reports unresolved safety roles",
            "safety-floor-unresolved" in ProtectionStatusHub.status.value.detectors.degradedReasons)

        sources = mapOf("home" to setOf(home), "default-dialer" to setOf(dialer))
        assertFalse(gate.protectsFresh(control, 0))
        assertFalse(gate.runtimeResolution.value.degraded)
        runCurrent()
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        if (!pathB) assertFalse("recovery clears unresolved-role health",
            "safety-floor-unresolved" in ProtectionStatusHub.status.value.detectors.degradedReasons)
        assertNotNull("ordinary configured cause resumes after recovery",
            coordinator.evaluateBlockCause(null, control, cause, timeLimitSpent = true))
        coordinator.showBlocked(control, null, cause)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, attached.size)
        for (pkg in listOf(dialer, home)) {
            assertTrue(coordinator.isSafetyFloor(pkg))
            assertNull(coordinator.evaluateBlockCause(null, pkg, cause, true))
        }
        // A new default can be unknown even after an earlier complete resolution. The same
        // visible package must lose its shield when the next required lookup fails.
        sources = mapOf("home" to null, "default-dialer" to setOf(dialer),
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to setOf(control),
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to setOf(dialer))
        assertTrue(gate.protectsFresh(control, 0))
        assertNull(coordinator.currentBlockCause(control, cause))
        coordinator.showBlocked(control, null, cause)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(attached.isEmpty())
        assertEquals(0, homeRedirects)
        assertTrue(gate.runtimeResolution.value.degraded)
    }

    @Test fun PC_48_PC_53_totalPackageManagerFailurePublishesDegradedAndRetriesBeforeRecovery() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        var calls = 0
        var failing = true
        val gate = SafetyFloorGate(app.packageName, {
            calls++
            check(!failing)
            mapOf("home" to setOf(home), "default-dialer" to setOf(dialer))
        }, { testScheduler.currentTime }, requiredRuntimeSources = setOf("home", "default-dialer"))
        ActiveRulesHolder.publish(SOURCE, listOf(rule))
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = false
            override fun detach(view: View) = Unit
            override fun goHome() = Unit
        }
        val coordinator = BlockScreenCoordinator(app, backgroundScope, host,
            focusStateStore = InMemoryFocusStateStore(), configuredLimitsStore = InMemoryLimitsStore(),
            blockRulesStore = InMemoryBlockRulesStore(listOf(rule)), safetyFloorGate = gate,
            statusDispatcher = StandardTestDispatcher(testScheduler),
            statusClock = { AccessClockStamp(1_800_000_000_000, testScheduler.currentTime, "boot") })
        runCurrent()
        val cause = ruleBlockCause(rule, System.currentTimeMillis(), 0)
        assertNull(coordinator.evaluateBlockCause(null, dialer, cause, true))
        assertNull("unclassifiable under total PM failure", coordinator.evaluateBlockCause(null, control, cause, true))
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        assertTrue("safety-floor-unresolved" in ProtectionStatusHub.status.value.detectors.degradedReasons)
        assertTrue("safety-floor-refresh-failed" in ProtectionStatusHub.status.value.detectors.degradedReasons)
        val before = calls
        advanceTimeBy(SafetyFloorGate.UNRESOLVED_RETRY_MS)
        runCurrent()
        coordinator.isSafetyFloor(dialer)
        assertTrue(calls > before)
        failing = false
        assertFalse(gate.protectsFresh(control, 0))
        assertNotNull(coordinator.evaluateBlockCause(null, control, cause, true))
        runCurrent()
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        assertFalse("safety-floor-unresolved" in ProtectionStatusHub.status.value.detectors.degradedReasons)
    }

    private companion object { const val SOURCE = "unresolved-safety-test" }
}
