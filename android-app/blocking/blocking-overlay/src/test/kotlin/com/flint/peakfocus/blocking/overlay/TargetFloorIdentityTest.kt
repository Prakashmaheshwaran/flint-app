package com.flint.peakfocus.blocking.overlay

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
        floor.protects("custom.home")
        val checked = floor.runtimeResolution.value.checkedAtElapsedMs
        now += 31_000
        repeat(50) { assertTrue(floor.readIdentity("custom.home")!!.degraded) }
        assertEquals(1, calls)
        assertTrue(pending.isEmpty())
        assertEquals(checked, floor.runtimeResolution.value.checkedAtElapsedMs)
    }
}
