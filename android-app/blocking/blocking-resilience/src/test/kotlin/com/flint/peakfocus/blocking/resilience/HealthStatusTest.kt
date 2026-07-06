package com.flint.peakfocus.blocking.resilience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM: HealthStatus is a plain data class; the derived properties are the policy under test. */
class HealthStatusTest {

    private fun status(
        a11y: Boolean = false,
        usage: Boolean = false,
        overlay: Boolean = false,
        battery: Boolean = false,
    ) = HealthStatus(
        accessibilityEnabled = a11y,
        usageAccessGranted = usage,
        overlayGranted = overlay,
        batteryExemptionGranted = battery,
    )

    @Test
    fun everythingGrantedIsHealthy() {
        val s = status(a11y = true, usage = true, overlay = true, battery = true)
        assertTrue(s.primaryPathAvailable)
        assertTrue(s.fallbackPathAvailable)
        assertTrue(s.canEnforce)
        assertEquals(HealthLevel.HEALTHY, s.level)
    }

    @Test
    fun accessibilityAloneCanEnforceButIsDegraded() {
        // Path A needs no other grant — but with no fallback and no battery exemption,
        // resilience is reduced, so the level is DEGRADED, not HEALTHY.
        val s = status(a11y = true)
        assertTrue(s.primaryPathAvailable)
        assertFalse(s.fallbackPathAvailable)
        assertTrue(s.canEnforce)
        assertEquals(HealthLevel.DEGRADED, s.level)
    }

    @Test
    fun fallbackPathAloneCanEnforce() {
        // The Advanced-Protection / OEM-revoked-a11y case: usage + overlay keep blocking alive.
        val s = status(usage = true, overlay = true)
        assertFalse(s.primaryPathAvailable)
        assertTrue(s.fallbackPathAvailable)
        assertTrue(s.canEnforce)
        assertEquals(HealthLevel.DEGRADED, s.level)
    }

    @Test
    fun fallbackNeedsBothUsageAndOverlay() {
        assertFalse(status(usage = true).fallbackPathAvailable)
        assertFalse(status(overlay = true).fallbackPathAvailable)
        assertEquals(HealthLevel.BROKEN, status(usage = true).level)
        assertEquals(HealthLevel.BROKEN, status(overlay = true).level)
    }

    @Test
    fun nothingGrantedIsBroken() {
        val s = status()
        assertFalse(s.canEnforce)
        assertEquals(HealthLevel.BROKEN, s.level)
    }

    @Test
    fun batteryExemptionAloneCannotEnforce() {
        val s = status(battery = true)
        assertFalse(s.canEnforce)
        assertEquals(HealthLevel.BROKEN, s.level)
    }

    @Test
    fun missingBatteryExemptionOnlyDegrades() {
        // Everything needed to block is granted; the missing exemption is a kill-risk, not an outage.
        val s = status(a11y = true, usage = true, overlay = true, battery = false)
        assertTrue(s.canEnforce)
        assertEquals(HealthLevel.DEGRADED, s.level)
    }

    @Test
    fun notificationDefaultsMeanNothingIsGated() {
        // Pre-13 devices (and pre-notification construction sites): nothing gated, always visible.
        val s = status(a11y = true, usage = true, overlay = true, battery = true)
        assertFalse(s.notificationsRequired)
        assertTrue(s.serviceNotificationVisible)
        assertEquals(HealthLevel.HEALTHY, s.level)
    }

    @Test
    fun serviceNotificationVisibilityIsGatedOnlyWhereTheGrantExists() {
        val base = status()
        assertTrue(base.copy(notificationsRequired = false, notificationsGranted = false).serviceNotificationVisible)
        assertFalse(base.copy(notificationsRequired = true, notificationsGranted = false).serviceNotificationVisible)
        assertTrue(base.copy(notificationsRequired = true, notificationsGranted = true).serviceNotificationVisible)
    }

    @Test
    fun deniedNotificationsNeverMoveTheLevel() {
        // Visibility, not enforcement: for every grant combination the triage level is
        // identical with and without the 13+ notification grant.
        (0 until 16).forEach { bits ->
            val base = status(
                a11y = bits and 1 != 0,
                usage = bits and 2 != 0,
                overlay = bits and 4 != 0,
                battery = bits and 8 != 0,
            )
            val denied = base.copy(notificationsRequired = true, notificationsGranted = false)
            assertEquals("level for grant combination $bits", base.level, denied.level)
        }
    }
}
