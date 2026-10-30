package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.blocking.resilience.HealthLevel
import com.flint.peakfocus.blocking.resilience.HealthStatus
import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.WebCoverage

/**
 * Pure mapping from blocking-resilience's [HealthStatus] to what the settings screen renders.
 * No Android types — JVM-tested in BlockingHealthUiTest.
 *
 * Every word of user-facing grant copy lives here (not in the composables) so tests can hold it
 * to the ADR-007 standard: disclosure before any hand-off, consistent with the onboarding
 * consent screen; fix actions phrased as user-initiated choices; "recommended" never dressed up
 * as "mandatory".
 */

/**
 * The grants blocking depends on — one settings row each. NOTIFICATIONS is the odd one out:
 * it never affects enforcement (the Path B service runs either way) and its row only exists
 * on Android 13+, where the grant gates whether that service's notification is visible.
 */
enum class PermissionKind { ACCESSIBILITY, USAGE_ACCESS, OVERLAY, BATTERY_EXEMPTION, NOTIFICATIONS }

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

    /**
     * Stable order: detection paths first (primary, then fallback pieces), then survival,
     * then visibility (the 13+-only notifications row appends last — it is the only row whose
     * presence depends on the device).
     */
    fun rows(status: HealthStatus): List<PermissionRowUi> = listOfNotNull(
        PermissionRowUi(
            kind = PermissionKind.ACCESSIBILITY,
            granted = status.accessibilityEnabled,
            affectsEnforcement = true,
            title = "Accessibility service",
            role = "Instant app and website blocking",
            disclosure = "Flint's accessibility service detects which app is in the foreground " +
                "and reads web addresses from known browser address bars for website blocking. " +
                "While a website rule is active, it re-reads supported browsers' address bars " +
                "when a page changes. Android also sends page-change signals from other apps; " +
                "Flint ignores those without reading their content. " +
                "Address-bar URLs stay in memory; reading stops if Accessibility is off. " +
                "App and screen class names identify the foreground app. " +
                "Flint uses this only to enforce the blocks you set up. Everything stays on " +
                "your device; it is never sent off your device, shared, or sold. Review the " +
                "full disclosure before choosing " +
                "whether to open Android Settings.",
            fixLabel = "Review Accessibility access",
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
        if (status.notificationsRequired) {
            PermissionRowUi(
                kind = PermissionKind.NOTIFICATIONS,
                granted = status.notificationsGranted,
                affectsEnforcement = false,
                title = "Notifications",
                role = "Recommended — shows when backup blocking is running",
                disclosure = "When the accessibility service is off, Flint blocks through a " +
                    "background service that shows an ongoing notification. Without the " +
                    "notification permission that status stays hidden — blocking itself is " +
                    "unaffected. Flint sends no other notifications.",
                fixLabel = "Open notification settings",
            )
        } else {
            null
        },
    )

    fun banner(status: HealthStatus): BannerUi {
        val banner = when (status.level) {
            HealthLevel.HEALTHY -> BannerUi(
                level = HealthLevel.HEALTHY,
                headline = "App blocking is healthy",
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
        return banner.copy(body = banner.body + "\n\n" + websiteCoverage(status))
    }

    /** Additive health information; no new permission action or alternate enable route. */
    fun websiteCoverage(status: HealthStatus): String {
        val observation = when (val coverage = status.websiteCoverage) {
            WebCoverage.NotRequired -> "No website block is active now."
            WebCoverage.AwaitingBrowser -> "Website coverage is awaiting confirmation from a supported browser."
            WebCoverage.Paused -> "Website blocks paused: website rules need Accessibility."
            is WebCoverage.Available -> "Last browser check: address bar readable in ${coverage.browser.displayName}."
            is WebCoverage.Degraded -> "Last browser check: website blocking can't read ${coverage.browser.displayName}'s address bar."
            is WebCoverage.Unconfirmed -> "Last browser check: Flint hasn't been able to confirm it can read ${coverage.browser.displayName}'s " +
                "address bar; website rules may not apply there."
            is WebCoverage.Unsupported -> "Last website check: website blocking unavailable in ${coverage.displayName}: " +
                if (coverage.isBrowser) "unsupported browser." else "unsupported link handler."
        }
        return observation + "\nKnown browsers (not verified on this device): " +
            BrowserAddressBars.supportedBrowsers.joinToString { it.displayName } +
            ". Browser updates, hidden address bars and private pages can reduce coverage; " +
            "support depends on a readable address bar. Other browsers and the usage-access backup " +
            "path do not filter websites. Allow-list mode uses the app list and ignores websites."
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
        if (!status.accessibilityEnabled && status.fallbackPathAvailable &&
            !status.serviceNotificationVisible
        ) {
            parts += "Android is hiding the backup service's status notification, so there is " +
                "no visible sign that blocking is running."
        }
        return parts.joinToString(" ")
    }
}
