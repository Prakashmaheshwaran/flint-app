package com.flint.peakfocus.blocking.resilience

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.flint.peakfocus.permissions.BatteryOptimization
import com.flint.peakfocus.permissions.OverlayPermission
import com.flint.peakfocus.permissions.UsageAccess

/**
 * "Is blocking healthy right now?" — the single source of truth (strategy doc §6.1: re-verify
 * every grant on each launch; some OEMs silently revoke them on reboot/idle/OTA).
 *
 * This module only OBSERVES. Re-enable prompts, Settings hand-offs, and the consent flow that
 * must precede the accessibility toggle are feature-settings' / feature-onboarding's job
 * (ADR-007); the raw Settings intents live in permissions-special.
 */

/** Overall triage level for [HealthStatus] — what feature-settings keys its prompts off. */
enum class HealthLevel {
    /** Both paths available and battery-exempt: nothing to prompt for. */
    HEALTHY,

    /** Blocking works right now, but a grant is missing (reduced coverage or elevated kill risk). */
    DEGRADED,

    /** Neither detection/enforcement path can run — blocking is OFF until the user re-grants. */
    BROKEN,
}

/**
 * Snapshot of every grant blocking depends on. The four booleans are the facts; the derived
 * properties are the policy (pure logic, JVM-tested in HealthStatusTest).
 *
 * @property accessibilityEnabled Flint's AccessibilityService is enabled (path A: instant
 *   detection, URL reads, a11y overlay — needs no other grant)
 * @property usageAccessGranted Usage access (PACKAGE_USAGE_STATS) is granted (path B detection
 *   + daily time budgets)
 * @property overlayGranted SYSTEM_ALERT_WINDOW is granted (path B enforcement overlay)
 * @property batteryExemptionGranted Flint is exempt from battery optimizations (not required to
 *   enforce, but without it OEM power managers are far more likely to kill enforcement)
 */
data class HealthStatus(
    val accessibilityEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val overlayGranted: Boolean,
    val batteryExemptionGranted: Boolean,
) {
    /** Path A (AccessibilityService) is available — sufficient on its own. */
    val primaryPathAvailable: Boolean get() = accessibilityEnabled

    /** Path B (UsageStats poll + overlay) is available — needs usage access AND the overlay grant. */
    val fallbackPathAvailable: Boolean get() = usageAccessGranted && overlayGranted

    /** Can Flint enforce at all right now, via either path? */
    val canEnforce: Boolean get() = primaryPathAvailable || fallbackPathAvailable

    /** Triage: BROKEN = can't enforce; DEGRADED = enforcing but a grant is missing; else HEALTHY. */
    val level: HealthLevel
        get() = when {
            !canEnforce -> HealthLevel.BROKEN
            accessibilityEnabled && usageAccessGranted && overlayGranted && batteryExemptionGranted ->
                HealthLevel.HEALTHY
            else -> HealthLevel.DEGRADED
        }
}

/**
 * Checks the four grants and returns a [HealthStatus]. Call on every app launch (and after
 * returning from any Settings hand-off) — grants observed healthy once can be revoked behind
 * Flint's back at any time.
 */
object PermissionHealthChecker {

    fun check(context: Context): HealthStatus = HealthStatus(
        accessibilityEnabled = isFlintAccessibilityServiceEnabled(context),
        usageAccessGranted = UsageAccess.isGranted(context),
        overlayGranted = OverlayPermission.isGranted(context),
        batteryExemptionGranted = BatteryOptimization.isIgnoring(context),
    )

    /**
     * True if any accessibility service belonging to Flint's package is enabled. Matching by
     * package (not class name) keeps this module decoupled from the concrete service class in
     * blocking-accessibility and is rename-proof; Flint ships exactly one a11y service.
     *
     * This reads the user's grant from Settings — the same signal the OS uses to rebind the
     * service on boot. OEMs that silently disable the service on reboot/idle clear this entry,
     * which is exactly the revocation this checker exists to catch.
     */
    private fun isFlintAccessibilityServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.packageName == context.packageName
        }
    }
}
