package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyFloorGateTest {

    private val self = "com.flint.peakfocus"
    private var now = 1_000L
    private var resolveCalls = 0
    private var sources: Map<String, Set<String>?> = mapOf(
        HOME to setOf("com.custom.home"),
        DIALER to setOf("com.custom.dialer"),
    )
    private var throwing = false
    private val queued = mutableListOf<() -> Unit>()

    private fun gate(background: Boolean = false, refresh: Long = 30_000L, fresh: Long = 2_000L) = SafetyFloorGate(
        selfPackage = self,
        resolveRuntime = {
            resolveCalls++
            if (throwing) error("binder died")
            mapOf(RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to setOf("com.new.home"),
                RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to setOf("com.new.dialer")) + sources
        },
        nowElapsedMs = { now },
        refreshInBackground = if (background) { task -> queued += task } else null,
        refreshIntervalMs = refresh,
        freshIntervalMs = fresh,
        requiredRuntimeSources = setOf(HOME, DIALER),
    )

    @Test fun `static floor never needs the resolver`() {
        val g = gate()
        assertTrue(g.protects("com.android.dialer"))
        assertTrue(g.protects(self))
        assertTrue(g.protectsFresh("com.android.settings", maxAgeMs = 0L))
        assertEquals(0, resolveCalls)
    }

    @Test fun `runtime surfaces are cached for the refresh interval`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        assertTrue(g.protects("com.custom.home"))
        assertFalse(g.protects("com.instagram.android"))
        now += 29_000L
        assertTrue(g.protects("com.custom.dialer"))
        assertEquals(1, resolveCalls)
        now += 1_000L
        g.protects("com.instagram.android")
        assertEquals(2, resolveCalls)
    }

    @Test fun `a block decision re-resolves stale runtime data and a mount forces it`() {
        val g = gate()
        assertFalse(g.protects("com.truecaller"))
        sources = sources + (DIALER to setOf("com.truecaller")) // just made Truecaller default
        now += 2_500L
        assertFalse("cached path is still stale", g.protects("com.truecaller"))
        assertTrue("about-to-block path re-resolves", g.protectsFresh("com.truecaller"))
        assertEquals(2, resolveCalls)
        sources = sources + (HOME to setOf("com.nova"))
        now += 10L
        assertFalse(g.protectsFresh("com.nova")) // inside the fresh window
        assertTrue("mount forces a resolve", g.protectsFresh("com.nova", maxAgeMs = 0L))
    }

    @Test fun `a failed source keeps its last packages while other sources update`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        sources = mapOf(HOME to setOf("com.new.home"), DIALER to null) // telecom lookup failed
        now += 31_000L
        assertTrue("dialer retained", g.protects("com.custom.dialer"))
        assertTrue("home updated", g.protects("com.new.home"))
        assertFalse("old home replaced", g.protects("com.custom.home"))
    }

    @Test fun `a source missing from a result is retained`() {
        val g = gate()
        g.protects("x")
        sources = mapOf(HOME to setOf("com.custom.home"))
        now += 31_000L
        g.protects("x")
        assertTrue(g.protects("com.custom.dialer"))
    }

    @Test fun `a throwing resolver keeps everything and is not retried on every event`() {
        val g = gate()
        assertTrue(g.protects("com.custom.dialer"))
        throwing = true
        now += 31_000L
        assertTrue(g.protects("com.custom.dialer"))
        assertTrue(g.protectsFresh("com.custom.dialer"))
        val calls = resolveCalls
        g.protects("com.custom.dialer")
        assertEquals(calls, resolveCalls)
    }

    @Test fun `stale cached reads refresh in the background, one at a time`() {
        val g = gate(background = true)
        assertTrue("cold start stays safe until background resolution", g.protects("com.custom.dialer"))
        assertEquals(0, resolveCalls)
        queued.removeAt(0).invoke()
        assertEquals(1, resolveCalls)
        sources = sources + (DIALER to setOf("com.truecaller"))
        now += 31_000L
        assertFalse("answers from cache, no inline binder call", g.protects("com.truecaller"))
        g.protects("com.truecaller")
        assertEquals(1, resolveCalls)
        assertEquals("only one refresh in flight", 1, queued.size)
        queued.removeAt(0).invoke()
        assertTrue(g.protects("com.truecaller"))
        assertEquals(2, resolveCalls)
    }

    @Test fun `a visible shield asks for a background refresh by age`() {
        val g = gate(background = true)
        g.protects("x")
        queued.removeAt(0).invoke()
        now += 4_000L
        g.refreshSoonIfOlderThan(5_000L)
        assertTrue(queued.isEmpty())
        now += 1_000L
        g.refreshSoonIfOlderThan(5_000L)
        assertEquals(1, queued.size)
    }

    @Test fun `elapsed clock going backwards forces a refresh`() {
        val g = gate()
        g.protects("x")
        now -= 10_000L
        g.protects("x")
        assertEquals(2, resolveCalls)
    }

    @Test fun PC_52_aPartialOrMissingRuntimeSourceCannotClaimCompleteSafetyResolution() {
        sources = mapOf(HOME to emptySet(), DIALER to null)
        val g = gate()
        assertFalse(g.runtimeResolution.value.hasResolvedEverySource)
        g.protects("example")
        assertEquals(setOf(HOME), g.runtimeResolution.value.resolvedSources)
        assertEquals(setOf(DIALER), g.runtimeResolution.value.failedSources)
        assertTrue(g.runtimeResolution.value.degraded)
        sources = mapOf(HOME to emptySet(), DIALER to setOf("com.custom.dialer"))
        now += 31_000
        g.protects("example")
        assertTrue(g.runtimeResolution.value.hasResolvedEverySource)
        assertFalse(g.runtimeResolution.value.degraded)
        sources = mapOf(HOME to emptySet())
        now += 31_000
        assertTrue(g.protects("com.custom.dialer"))
        assertTrue(g.runtimeResolution.value.hasResolvedEverySource)
        assertTrue(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_52_anEntirelyFailedResolutionIsDegradedEvenWithStaticFloorProtection() {
        val g = gate()
        throwing = true
        assertTrue(g.protects("com.android.settings"))
        assertFalse(g.runtimeResolution.value.hasResolvedEverySource)
        g.refreshSoonIfOlderThan(0)
        assertTrue(g.runtimeResolution.value.degraded)
        assertEquals(setOf(HOME, DIALER), g.runtimeResolution.value.failedSources)
    }

    @Test fun PC_48_PC_50_PC_53_coldMissingRolesAllowUnknownCallingAndHomePackages() {
        for (unresolved in listOf(emptyMap(), mapOf(HOME to null, DIALER to null),
            mapOf(HOME to setOf("com.custom.home")))) {
            sources = unresolved
            val g = gate()
            for (pkg in listOf("com.new.dialer", "com.new.home")) {
                val missing = pkg == "com.new.dialer" || unresolved[HOME] == null
                assertEquals("cached safety fallback on $pkg", missing, g.protects(pkg))
                assertEquals("fresh safety fallback on $pkg", missing, g.protectsFresh(pkg, 0))
            }
            assertFalse("ordinary apps retain enforcement", g.protectsFresh("ordinary.control", 0))
            assertTrue(g.runtimeResolution.value.degraded)
        }
    }

    @Test fun PC_49_failedLookupCannotTreatAnUnknownNewRoleAsAnOrdinaryTarget() {
        val g = gate()
        assertFalse(g.protectsFresh("com.new.dialer", 0))
        sources = mapOf(HOME to setOf("com.custom.home"), DIALER to null)
        assertTrue(g.protectsFresh("com.new.dialer", 0))
        assertTrue(g.protects("com.custom.dialer"))
        assertTrue(g.runtimeResolution.value.hasResolvedEverySource)
        assertTrue(g.runtimeResolution.value.degraded)
        sources = mapOf(HOME to setOf("com.new.home"), DIALER to setOf("com.new.dialer"))
        assertFalse(g.protectsFresh("ordinary.control", 0))
        assertTrue(g.protects("com.new.dialer"))
        assertTrue(g.protects("com.new.home"))
        assertFalse(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_48_PC_53_coldThrowingResolverAllowsUnknownPackagesUntilSuccessfulRecovery() {
        val g = gate()
        throwing = true
        assertTrue(g.protects("com.unknown.dialer"))
        assertTrue(g.protectsFresh("com.unknown.home"))
        val calls = resolveCalls
        repeat(5) { assertTrue(g.protects("ordinary.control")) }
        assertEquals("failure still respects retry interval", calls, resolveCalls)
        throwing = false
        now += 31_000
        assertFalse(g.protectsFresh("ordinary.control"))
        assertFalse(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_49_backgroundFailureAndRecoveryChangeSafetyFallbackWithProvenance() {
        val g = gate(background = true)
        assertTrue(g.protects("ordinary.control"))
        queued.removeAt(0).invoke()
        assertFalse(g.protects("ordinary.control"))
        sources = mapOf(HOME to null, DIALER to setOf("com.custom.dialer"))
        now += 31_000
        g.refreshSoonIfOlderThan(0)
        queued.removeAt(0).invoke()
        assertTrue(g.protects("com.new.home"))
        assertTrue(g.runtimeResolution.value.degraded)
        sources = mapOf(HOME to setOf("com.new.home"), DIALER to setOf("com.custom.dialer"))
        g.refreshSoonIfOlderThan(0)
        queued.removeAt(0).invoke()
        assertTrue(g.protects("com.new.home"))
        assertFalse(g.protects("ordinary.control"))
        assertFalse(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_49_PC_53_failedCandidateDiscoveryAllowsOnlyUnclassifiedPackagesAndKnownSafety() {
        sources = mapOf(HOME to null, DIALER to null,
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to null,
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to null,
            RuntimeSafetySurfaces.SOURCE_ORDINARY_LAUNCHERS to setOf("ordinary.control"))
        val g = gate()
        assertFalse(g.protects("ordinary.control"))
        assertTrue(g.protects("unclassified.home"))
        assertTrue(g.protects("com.android.settings"))
        sources = emptyMap()
        throwing = true
        now += SafetyFloorGate.UNRESOLVED_RETRY_MS
        assertTrue("total failure invalidates old ordinary proof", g.protects("ordinary.control"))
        assertTrue(g.runtimeResolution.value.degraded)
        val beforeRetry = resolveCalls
        now += SafetyFloorGate.UNRESOLVED_RETRY_MS
        assertTrue(g.protects("unclassified.dialer"))
        assertEquals(beforeRetry + 1, resolveCalls)
    }

    @Test fun PC_53_degradedCachedReadsOnlyQueueASingleBackgroundRetry() {
        sources = mapOf(HOME to null, DIALER to null)
        val g = gate(background = true)
        queued.removeAt(0).invoke()
        val calls = resolveCalls
        now += SafetyFloorGate.UNRESOLVED_RETRY_MS
        repeat(5) { assertFalse(g.protects("ordinary.control")) }
        assertEquals("no resolver call on the calling thread", calls, resolveCalls)
        assertEquals(1, queued.size)
        queued.removeAt(0).invoke()
        assertEquals(calls + 1, resolveCalls)
    }

    @Test fun PC_53_cachedProtectsReadDoesNotWaitForAnInFlightBinderResolution() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var block = false
        val g = SafetyFloorGate(self, {
            if (block) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            mapOf(HOME to setOf("home"), DIALER to setOf("dialer"))
        }, { now }, refreshInBackground = { work -> if (block) { pool.submit { work() }; Unit } else work() },
            requiredRuntimeSources = setOf(HOME, DIALER))
        try {
            assertFalse(g.protects("ordinary.control"))
            block = true
            now += 31_000
            assertFalse(g.protects("ordinary.control"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(pool.submit<Boolean> { g.protects("ordinary.control") }.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun PC_49_uninstallerOrSystemDialerFailureCannotExemptUnrelatedCandidateApps() {
        val g = SafetyFloorGate(self, {
            mapOf(HOME to setOf("home"), DIALER to setOf("dialer"),
                "system-dialer" to null, "uninstaller" to null,
                RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to setOf("candidate.home"),
                RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to setOf("candidate.dialer"))
        }, { now }, requiredRuntimeSources = setOf(HOME, DIALER, "system-dialer", "uninstaller"))
        assertFalse(g.protectsFresh("candidate.home", 0))
        assertFalse(g.protectsFresh("candidate.dialer", 0))
        assertFalse(g.protects("ordinary.control"))
        assertTrue(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_53_successfulEmptyDiscoveryBlocksOrdinaryAppsWithoutClaimingResolvedRoles() {
        sources = mapOf(HOME to null, DIALER to null,
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to emptySet(),
            RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to emptySet())
        val g = gate()
        assertFalse(g.protects("ordinary.control"))
        assertTrue(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_49_aSuccessfulHomeDiscoveryPrunesOldCandidatesDespiteUnrelatedCallingDiscoveryFailure() {
        sources = mapOf(HOME to null, DIALER to setOf("dialer"))
        val g = gate()
        assertTrue(g.protects("com.new.home"))
        sources = sources + (RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME to emptySet()) +
            (RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING to null)
        assertFalse(g.protectsFresh("com.new.home", 0))
        assertTrue(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_53_degradedFreshChecksStayBackgroundAndOnlyNewMountForcesOneRead() {
        sources = mapOf(HOME to null, DIALER to null)
        val g = gate(background = true)
        queued.removeAt(0).invoke()
        val initialCalls = resolveCalls
        now += SafetyFloorGate.UNRESOLVED_RETRY_MS
        assertFalse("cached block remains during async refresh", g.protectsFresh("ordinary.control"))
        assertEquals(initialCalls, resolveCalls)
        queued.removeAt(0).invoke()
        assertFalse(g.protectsFresh("ordinary.control"))
        assertFalse("new mount performs its existing fresh check", g.protectsFresh("ordinary.control", 0))
        assertEquals(initialCalls + 2, resolveCalls)
        assertTrue(queued.isEmpty())
    }

    @Test fun PC_49_newMountResolvesARoleChangeInsideTheFreshWindow() {
        val g = gate(background = true)
        queued.removeAt(0).invoke()
        assertFalse(g.protectsFresh("new.dialer"))
        sources = sources + (DIALER to setOf("new.dialer"))
        now += 1
        assertFalse("cached check still sees the old role", g.protectsFresh("new.dialer"))
        assertTrue("NEW mount sees the new dialer", g.protectsFresh("new.dialer", 0))
        assertEquals(2, resolveCalls)
    }

    @Test fun PC_73_routinePendingRefreshDoesNotInventUnresolvedRoleHealth() {
        val g = gate(background = true)
        queued.removeAt(0).invoke()
        assertFalse(g.runtimeResolution.value.degraded)
        now += 31_000
        assertFalse(g.protects("ordinary.control"))
        assertTrue(g.runtimeResolution.value.checking)
        assertFalse(g.runtimeResolution.value.degraded)
        queued.removeAt(0).invoke()
        assertFalse(g.runtimeResolution.value.checking)
        assertFalse(g.runtimeResolution.value.degraded)
    }

    @Test fun PC_53_anExecutorThatNeverRunsCannotWedgeFutureResolution() {
        val g = gate(background = true)
        assertEquals(1, queued.size)
        now += SafetyFloorGate.SUBMISSION_TIMEOUT_MS
        g.protects("ordinary.control")
        assertEquals(2, queued.size)
        queued.removeAt(0).invoke() // Expired submission cannot overwrite a newer snapshot.
        assertEquals(0, resolveCalls)
        queued.removeAt(0).invoke()
        assertEquals(1, resolveCalls)
        assertFalse(g.protects("ordinary.control"))
        assertFalse(g.runtimeResolution.value.checking)
    }

    @Test fun PC_53_executorRejectionClearsSingleFlightAndAllowsARetry() {
        var reject = true
        val work = mutableListOf<() -> Unit>()
        val g = SafetyFloorGate(self, { sources }, { now }, refreshInBackground = {
            if (reject) error("executor rejected") else work += it
        }, requiredRuntimeSources = setOf(HOME, DIALER))
        assertFalse(g.runtimeResolution.value.checking)
        reject = false
        g.protects("ordinary.control")
        work.removeAt(0).invoke()
        assertFalse(g.protects("ordinary.control"))
        assertFalse(g.runtimeResolution.value.checking)
    }

    private companion object {
        const val HOME = "home"
        const val DIALER = "default-dialer"
    }
}
