package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.blocking.resilience.HealthLevel
import com.flint.peakfocus.blocking.resilience.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingHealthUiTest {

    private fun status(
        accessibility: Boolean = true,
        usage: Boolean = true,
        overlay: Boolean = true,
        battery: Boolean = true,
    ) = HealthStatus(
        accessibilityEnabled = accessibility,
        usageAccessGranted = usage,
        overlayGranted = overlay,
        batteryExemptionGranted = battery,
    )

    // --- rows ---

    @Test
    fun everyPermissionGetsExactlyOneRowInStableOrder() {
        val kinds = BlockingHealthUi.rows(status()).map { it.kind }
        assertEquals(
            listOf(
                PermissionKind.ACCESSIBILITY,
                PermissionKind.USAGE_ACCESS,
                PermissionKind.OVERLAY,
                PermissionKind.BATTERY_EXEMPTION,
            ),
            kinds,
        )
    }

    @Test
    fun grantedFlagsMirrorTheStatusFacts() {
        val rows = BlockingHealthUi.rows(
            status(accessibility = false, usage = true, overlay = false, battery = true),
        ).associate { it.kind to it.granted }
        assertEquals(false, rows[PermissionKind.ACCESSIBILITY])
        assertEquals(true, rows[PermissionKind.USAGE_ACCESS])
        assertEquals(false, rows[PermissionKind.OVERLAY])
        assertEquals(true, rows[PermissionKind.BATTERY_EXEMPTION])
    }

    @Test
    fun onlyTheBatteryExemptionIsOptionalForEnforcement() {
        BlockingHealthUi.rows(status()).forEach { row ->
            assertEquals(
                "affectsEnforcement for ${row.kind}",
                row.kind != PermissionKind.BATTERY_EXEMPTION,
                row.affectsEnforcement,
            )
        }
    }

    @Test
    fun accessibilityDisclosureMatchesTheOnboardingConsentLanguage() {
        val row = BlockingHealthUi.rows(status())
            .first { it.kind == PermissionKind.ACCESSIBILITY }
        // The same facts the onboarding prominent disclosure states (ADR-007):
        // what is read, what it's used for, and that nothing leaves the device.
        assertTrue(row.disclosure.contains("which app is in the foreground"))
        assertTrue(row.disclosure.contains("web address"))
        assertTrue(row.disclosure.contains("only to enforce the blocks you set up"))
        assertTrue(row.disclosure.contains("stays on your device", ignoreCase = true))
        assertTrue(row.disclosure.contains("never", ignoreCase = true))
    }

    @Test
    fun everyRowHasSubstantialDisclosureAndAnHonestFixLabel() {
        BlockingHealthUi.rows(status(accessibility = false, usage = false, overlay = false, battery = false))
            .forEach { row ->
                assertTrue("disclosure for ${row.kind}", row.disclosure.length > 40)
                assertTrue("fix label for ${row.kind}", row.fixLabel.isNotBlank())
                // Fix actions are user-initiated hand-offs, never claimed as automatic.
                assertFalse(
                    "fix label for ${row.kind} must not promise automation",
                    row.fixLabel.contains("auto", ignoreCase = true),
                )
            }
    }

    @Test
    fun batteryRowSaysRecommendedNotMandatory() {
        val row = BlockingHealthUi.rows(status(battery = false))
            .first { it.kind == PermissionKind.BATTERY_EXEMPTION }
        assertTrue(row.role.contains("Recommended", ignoreCase = true))
        assertTrue(row.disclosure.contains("can still run without", ignoreCase = true))
    }

    // --- banner ---

    @Test
    fun bannerLevelAlwaysMatchesTheStatusLevel() {
        allStatuses().forEach { s ->
            assertEquals(s.level, BlockingHealthUi.banner(s).level)
        }
    }

    @Test
    fun healthyBannerSaysSo() {
        val s = status()
        assertEquals(HealthLevel.HEALTHY, s.level)
        assertTrue(BlockingHealthUi.banner(s).headline.contains("healthy", ignoreCase = true))
    }

    @Test
    fun brokenBannerSaysBlockingIsOff() {
        val s = status(accessibility = false, usage = false, overlay = true, battery = true)
        assertEquals(HealthLevel.BROKEN, s.level)
        assertTrue(BlockingHealthUi.banner(s).headline.contains("off", ignoreCase = true))
    }

    @Test
    fun degradedBecauseOfBatteryOnlyTalksAboutBattery() {
        val s = status(battery = false)
        assertEquals(HealthLevel.DEGRADED, s.level)
        val body = BlockingHealthUi.banner(s).body
        assertTrue(body.contains("battery", ignoreCase = true))
        assertFalse(body.contains("accessibility service is off", ignoreCase = true))
    }

    @Test
    fun degradedOnTheFallbackPathExplainsAccessibilityIsOff() {
        val s = status(accessibility = false)
        assertEquals(HealthLevel.DEGRADED, s.level)
        val body = BlockingHealthUi.banner(s).body
        assertTrue(body.contains("accessibility service is off", ignoreCase = true))
        assertTrue(body.contains("fallback", ignoreCase = true))
    }

    @Test
    fun degradedWithAnIncompleteBackupPathWarnsAboutRevocation() {
        val s = status(overlay = false)
        assertEquals(HealthLevel.DEGRADED, s.level)
        assertTrue(
            BlockingHealthUi.banner(s).body.contains("backup path is incomplete", ignoreCase = true),
        )
    }

    @Test
    fun everyDegradedCombinationExplainsItself() {
        allStatuses()
            .filter { it.level == HealthLevel.DEGRADED }
            .forEach { s ->
                assertTrue(
                    "degraded body for $s must not be blank",
                    BlockingHealthUi.banner(s).body.isNotBlank(),
                )
            }
    }

    /** All 16 grant combinations. */
    private fun allStatuses(): List<HealthStatus> = (0 until 16).map { bits ->
        status(
            accessibility = bits and 1 != 0,
            usage = bits and 2 != 0,
            overlay = bits and 4 != 0,
            battery = bits and 8 != 0,
        )
    }
}
