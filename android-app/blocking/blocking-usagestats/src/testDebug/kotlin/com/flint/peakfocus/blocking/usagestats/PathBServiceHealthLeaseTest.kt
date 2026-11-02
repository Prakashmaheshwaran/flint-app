package com.flint.peakfocus.blocking.usagestats

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.blocking.overlay.ProtectionStatus.PathBState
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.FallbackMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Real Hub lease boundary, with no final service callback and no scheduled expiry job required. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PathBServiceHealthLeaseTest {
    private val owner = Any()
    private val lifecycle = PathBServiceLifecycle(SystemClock::elapsedRealtime,
        publish = ProtectionStatusHub::publishPathBHealth)
    private lateinit var policy: ProtectionStatus

    @Before fun setUp() {
        ProtectionStatusHub.publishPathBHealth(PathBState.UNKNOWN)
        val now = SystemClock.elapsedRealtime()
        policy = ProtectionStatus.UNKNOWN.copy(
            readiness = ProtectionStatus.Readiness.READY,
            observedAt = AccessClockStamp(10_000, now, "test-boot"),
            validUntilElapsedRealtimeMs = now + 120_000,
            detectors = ProtectionStatus.DetectorHealth(true, PathBState.UNKNOWN, setOf("policy-reason")),
        )
        ProtectionStatusHub.publish(policy)
        lifecycle.starting(owner)
        lifecycle.foregroundStarted(owner)
    }

    @After fun cleanup() {
        ProtectionStatusHub.publish(ProtectionStatus.UNKNOWN)
        ProtectionStatusHub.publishPathBHealth(PathBState.UNKNOWN)
    }

    @Test fun `missing final report expires the live lease without changing A or policy time`() {
        lifecycle.mode(owner, FallbackMode.ENFORCING)
        ShadowSystemClock.advanceBy(Duration.ofMillis(89_999))
        assertEquals(PathBState.ENFORCING, ProtectionStatusHub.status.value.detectors.pathB)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        val stale = ProtectionStatusHub.status.value
        assertEquals(PathBState.UNKNOWN, stale.detectors.pathB)
        assertTrue("path-b-health-expired" in stale.detectors.degradedReasons)
        assertTrue("policy-reason" in stale.detectors.degradedReasons)
        assertTrue(stale.detectors.accessibilityConnected)
        assertEquals(policy.observedAt, stale.observedAt)
        assertEquals(policy.lastCheckedEpochMs, stale.lastCheckedEpochMs)
    }

    @Test fun `clean stop cancels the lease and preserves positively stopped status`() {
        lifecycle.mode(owner, FallbackMode.STANDBY)
        lifecycle.stopped(owner)
        ShadowSystemClock.advanceBy(Duration.ofMillis(90_000))
        val stopped = ProtectionStatusHub.status.value
        assertEquals(PathBState.OFF, stopped.detectors.pathB)
        assertEquals(policy.validUntilElapsedRealtimeMs, stopped.validUntilElapsedRealtimeMs)
        assertFalse("path-b-health-expired" in stopped.detectors.degradedReasons)
        assertEquals(policy.observedAt, stopped.observedAt)
    }

    @Test fun `only another successful evaluation renews a bounded service lease`() {
        lifecycle.mode(owner, FallbackMode.STANDBY)
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000))
        lifecycle.mode(owner, FallbackMode.STANDBY)
        ShadowSystemClock.advanceBy(Duration.ofMillis(30_000))
        assertEquals(PathBState.STANDBY, ProtectionStatusHub.status.value.detectors.pathB)
        assertEquals(policy.validUntilElapsedRealtimeMs, ProtectionStatusHub.status.value.validUntilElapsedRealtimeMs)
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000))
        assertEquals(PathBState.UNKNOWN, ProtectionStatusHub.status.value.detectors.pathB)
    }
}
