package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.blocking.resilience.HealthLevel
import com.flint.peakfocus.blocking.resilience.HealthStatus

/**
 * Pure mapping from blocking-resilience's [HealthStatus] to what the settings screen renders.
 * No Android types — JVM-tested in BlockingHealthUiTest.
 *
 * Every word of user-facing grant copy lives here (not in the composables) so tests can hold it
 * to the ADR-007 standard: disclosure before any hand-off, consistent with the onboarding
 * consent screen; fix actions phrased as user-initiated choices; "recommended" never dressed up
 * as "mandatory".
 */

/** The four grants blocking depends on — one settings row each. */
enum class PermissionKind { ACCESSIBILITY, USAGE_ACCESS, OVERLAY, BATTERY_EXEMPTION }

/**
 * One "keep blocking alive" row.
 *
 * @property affectsEnforcement true if losing this grant can reduce or stop blocking itself;
 *   false for the battery exemption, which only lowers the odds of the OS killing Flint
 * @property role short always-visible line: what the grant does for the user
 * @property disclosure what the grant lets Flint read/do and where that data goes — consistent
 *   with the onboarding prominent disclosure (ADR-007); shown before the fix button
 * @property fixLabel label of the fix button; the Settings hand-off happens only on that tap
 */
data class PermissionRowUi(
    val kind: PermissionKind,
    val granted: Boolean,
    val affectsEnforcement: Boolean,
    val title: String,
    val role: String,
    val disclosure: String,
    val fixLabel: String,
)

/** The triage banner above the rows: level plus an honest explanation of the state. */
data class BannerUi(
    val level: HealthLevel,
    val headline: String,
    val body: String,
)

object BlockingHealthUi {

    /** Stable order: detection paths first (primary, then fallback pieces), then survival. */
    fun rows(status: HealthStatus): List<PermissionRowUi> = listOf(
        PermissionRowUi(
            kind = PermissionKind.ACCESSIBILITY,
            granted = status.accessibilityEnabled,
            affectsEnforcement = true,
            title = "Accessibility service",
            role = "Instant app and website blocking",
            disclosure = "Flint's accessibility service detects which app is in the foreground " +
                "and — for website blocking — reads the web address shown in your browser. " +
                "Flint uses this only to enforce the blocks you set up. Everything stays on " +
                "your device; it is never sent off your device, shared, or sold.",
            fixLabel = "Open Accessibility settings",
        ),
        PermissionRowUi(
            kind = PermissionKind.USAGE_ACCESS,
            granted = status.usageAccessGranted,
            affectsEnforcement = true,
            title = "Usage access",
            role = "Backup detection and daily time limits",
            disclosure = "Usage access lets Flint read which apps were in the foreground and " +
                "for how long — nothing else. It powers daily time limits and keeps blocking " +
                "working if the accessibility service is ever turned off. This data stays on " +
                "your device.",
            fixLabel = "Open Usage access settings",
        ),
        PermissionRowUi(
            kind = PermissionKind.OVERLAY,
            granted = status.overlayGranted,
            affectsEnforcement = true,
            title = "Display over other apps",
            role = "Shows the block screen over a blocked app",
            disclosure = "Flint draws its block screen over an app the moment a block applies. " +
                "The overlay shows only Flint's own block screen — it displays nothing else " +
                "and reads nothing from other apps.",
            fixLabel = "Open overlay settings",
        ),
        PermissionRowUi(
            kind = PermissionKind.BATTERY_EXEMPTION,
            granted = status.batteryExemptionGranted,
            affectsEnforcement = false,
            title = "Battery optimization exemption",
            role = "Recommended — keeps blocking alive in the background",
            disclosure = "Without this exemption, Android's battery manager (and especially " +
                "manufacturer power managers) may quietly stop Flint in the background, and " +
                "blocking lapses with it. Blocking can still run without the exemption — this " +
                "only makes it more reliable. Android will ask you to confirm.",
            fixLabel = "Request battery exemption",
        ),
    )

    fun banner(status: HealthStatus): BannerUi = when (status.level) {
        HealthLevel.HEALTHY -> BannerUi(
            level = HealthLevel.HEALTHY,
            headline = "Blocking is healthy",
            body = "Both detection paths, the block screen, and the battery exemption are all " +
                "in place. If blocking still stops on your phone, work through the " +
                "manufacturer steps below.",
        )
        HealthLevel.DEGRADED -> BannerUi(
            level = HealthLevel.DEGRADED,
            headline = "Blocking works, but it isn't protected",
            body = degradedBody(status),
        )
        HealthLevel.BROKEN -> BannerUi(
            level = HealthLevel.BROKEN,
            headline = "Blocking is off",
            body = "Flint currently has no way to detect or block anything. Re-grant access " +
                "below — enabling the accessibility service alone is enough to restore " +
                "blocking.",
        )
    }

    /** Names exactly what is missing, built from the status facts — JVM-tested per case. */
    private fun degradedBody(status: HealthStatus): String {
        val parts = mutableListOf<String>()
        if (!status.accessibilityEnabled) {
            parts += "The accessibility service is off, so Flint is blocking through the " +
                "slower usage-stats fallback."
        }
        if (status.accessibilityEnabled && !status.fallbackPathAvailable) {
            parts += "The backup path is incomplete — if the accessibility service is ever " +
                "revoked, blocking would stop entirely."
        }
        if (!status.batteryExemptionGranted) {
            parts += "Flint is not exempt from battery optimization, so some phones will kill " +
                "blocking in the background."
        }
        return parts.joinToString(" ")
    }
}
