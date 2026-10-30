package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TargetFloorIdentityTest {
    // PC-48/PC-49/PC-52/PC-73: reader evidence only; no device safety claim.
    private var now = 1_000L
    private var calls = 0
    private val pending = mutableListOf<() -> Unit>()
    private var sources: Map<String, Set<String>?> = mapOf(
        "home" to setOf("custom.home"), "default-dialer" to setOf("custom.dialer"),
        "system-dialer" to setOf("system.dialer"), "uninstaller" to setOf("custom.uninstaller"),
    )
    private fun gate(background: Boolean = false) = SafetyFloorGate("flint", { calls++; sources }, { now },
        refreshInBackground = if (background) { refresh -> pending += refresh } else null,
        requiredRuntimeSources = setOf("home", "default-dialer", "system-dialer", "uninstaller"))

    @Test fun staticAndSelfRemainKnownWithoutAnyRuntimeOrStorageResolution() {
        val floor = gate()
        assertEquals(FloorIdentity(FloorIdentity.Kind.SELF), floor.readIdentity("flint"))
        assertEquals(FloorIdentity(FloorIdentity.Kind.STATIC_SYSTEM), floor.readIdentity("com.android.settings"))
        assertEquals(0, calls)
        assertFalse(floor.runtimeResolution.value.hasResolvedEverySource)
    }

    @Test fun eachRuntimeSourceHasItsOwnIdentityAndSuccessfulTimestamp() {
        val floor = gate()
        floor.protects("custom.home")
        for ((pkg, kind) in listOf("custom.home" to FloorIdentity.Kind.RUNTIME_HOME,
            "custom.dialer" to FloorIdentity.Kind.RUNTIME_DIALER,
            "system.dialer" to FloorIdentity.Kind.RUNTIME_DIALER,
            "custom.uninstaller" to FloorIdentity.Kind.RUNTIME_UNINSTALLER)) {
            assertEquals(FloorIdentity(kind, now, false), floor.readIdentity(pkg))
            assertTrue(floor.protects(pkg))
        }
        assertNull(floor.readIdentity("ordinary.app"))
        assertEquals(1, calls)
    }

    @Test fun failedSourceRetainsTheFloorAndItsSuccessfulTimeRatherThanTheFailedCheckTime() {
        val floor = gate()
        floor.protects("custom.dialer")
        val first = floor.readIdentity("custom.dialer")!!
        now += 31_000
        sources = sources + ("default-dialer" to null)
        floor.protects("custom.dialer")
        val failed = floor.readIdentity("custom.dialer")!!
        assertEquals(first.resolvedAtElapsedMs, failed.resolvedAtElapsedMs)
        assertEquals(now, floor.runtimeResolution.value.checkedAtElapsedMs)
        assertTrue(failed.degraded)
        assertTrue(floor.protects("custom.dialer"))
        assertEquals(now, floor.readIdentity("custom.home")!!.resolvedAtElapsedMs)
        assertFalse(floor.readIdentity("custom.home")!!.degraded)
    }

    @Test fun successfullyChangedRoleReplacesItsOldIdentity() {
        val floor = gate()
        floor.protects("custom.home")
        assertNotNull(floor.readIdentity("custom.home"))
        now += 31_000
        sources = sources + ("home" to setOf("new.home"))
        floor.protects("custom.home")
        assertNull(floor.readIdentity("custom.home"))
        assertEquals(FloorIdentity(FloorIdentity.Kind.RUNTIME_HOME, now), floor.readIdentity("new.home"))
    }

    @Test fun staleBackgroundIdentityIsDegradedUntilItsQueuedRefreshCompletes() {
        val floor = gate(background = true)
        pending.removeAt(0).invoke() // #188 eagerly schedules the initial background snapshot.
        floor.protects("custom.home")
        assertFalse(floor.readIdentity("custom.home")!!.degraded)
        now += 31_000
        assertTrue(floor.readIdentity("custom.home")!!.degraded)
        assertTrue(pending.isEmpty())
        floor.protects("custom.home")
        assertEquals(1, pending.size)
        pending.removeAt(0).invoke()
        assertEquals(FloorIdentity(FloorIdentity.Kind.RUNTIME_HOME, now), floor.readIdentity("custom.home"))
    }

    @Test fun whollyUnresolvedRuntimeDoesNotInventAnIdentity() {
        sources = emptyMap()
        val floor = gate()
        assertNull(floor.readIdentity("custom.dialer"))
        assertTrue(floor.runtimeResolution.value.degraded)
        assertNull(floor.runtimeResolution.value.checkedAtElapsedMs)
        assertEquals(0, calls)
    }

    @Test fun repeatedDisplayReadsNeverRefreshOrChangeTheLastActualCheck() {
        val floor = gate(background = true)
        pending.removeAt(0).invoke()
        floor.protects("custom.home")
        val checked = floor.runtimeResolution.value.checkedAtElapsedMs
        now += 31_000
        repeat(50) { assertTrue(floor.readIdentity("custom.home")!!.degraded) }
        assertEquals(1, calls)
        assertTrue(pending.isEmpty())
        assertEquals(checked, floor.runtimeResolution.value.checkedAtElapsedMs)
    }

    @Test fun conservativeCandidatesAndUnclassifiedPackagesNeverBecomeProvenFloorIdentities() {
        sources = sources + mapOf("home" to null, "default-dialer" to null,
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to setOf("candidate.home"),
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to setOf("candidate.phone"))
        val floor = gate()
        for (pkg in listOf("candidate.home", "candidate.phone")) {
            assertTrue(floor.protects(pkg))
            assertNull(floor.readIdentity(pkg))
        }
        sources = sources + (RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to null)
        now += 31_000
        assertTrue(floor.protects("unclassified.app"))
        assertNull(floor.readIdentity("unclassified.app"))
        assertTrue(floor.runtimeResolution.value.degraded)
    }

    @Test fun retainedIdentityReadDoesNotWaitForBinderResolutionOrRefreshItsTimestamp() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        var blockResolver = false
        val floor = SafetyFloorGate("flint", {
            if (blockResolver) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            sources
        }, { now }, requiredRuntimeSources = sources.keys)
        try {
            floor.protects("custom.home")
            val retained = floor.readIdentity("custom.home")
            blockResolver = true
            val refreshing = workers.submit<Boolean> { floor.protectsFresh("custom.home", maxAgeMs = 0) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reading = workers.submit<FloorIdentity?> { floor.readIdentity("custom.home") }
            assertEquals(retained, reading.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(refreshing.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
