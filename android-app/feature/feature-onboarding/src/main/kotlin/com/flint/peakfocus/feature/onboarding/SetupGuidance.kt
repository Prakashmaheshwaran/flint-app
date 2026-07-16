package com.flint.peakfocus.feature.onboarding

import com.flint.peakfocus.blocking.resilience.HealthStatus

/**
 * "Is Flint blocking right now, and what is the single next thing to do about it?"
 *
 * The Home status card must answer from [HealthStatus], not from the AccessibilityService
 * grant alone. Path B (usage access + overlay) can enforce with accessibility off, while an
 * enabled accessibility service says nothing about whether the fallback path will survive a
 * later revocation.
 *
 * This mapping is pure Kotlin so its claims and step ordering can be exhaustively JVM-tested.
 * Notification permission is intentionally not a setup step: it controls visibility of Path
 * B's ongoing notification, never enforcement. When that notification is hidden while Path B
 * is actually enforcing, [body] names the condition and points to Flint Settings.
 */

/** A blocking or survival grant the user has yet to make, phrased as an action. */
enum class SetupStep(val ctaLabel: String) {
    /**
     * Path A. The prominent-disclosure consent screen must come between this step and
     * Accessibility settings (Play policy, ADR-007).
     */
    ENABLE_ACCESSIBILITY("Enable accessibility service"),

    /** Path B detection, and the daily time limits. */
    GRANT_USAGE_ACCESS("Grant usage access"),

    /** Path B enforcement — without it Flint cannot put its block screen over an app. */
    GRANT_OVERLAY("Allow display over other apps"),

    /** Survival only: recommended, never required. */
    REQUEST_BATTERY_EXEMPTION("Request battery exemption"),
}

/**
 * What the Home status card renders.
 *
 * @property enforcingNow whether either enforcement path can block right now
 * @property remainingSteps missing blocking and survival grants, cheapest route first
 */
data class SetupPlan(
    val enforcingNow: Boolean,
    val headline: String,
    val body: String,
    val remainingSteps: List<SetupStep>,
) {
    /** The one action the card offers; null once blocking and survival setup is complete. */
    val nextStep: SetupStep? get() = remainingSteps.firstOrNull()
}

object SetupGuidance {

    fun plan(status: HealthStatus): SetupPlan = SetupPlan(
        enforcingNow = status.canEnforce,
        headline = headline(status),
        body = body(status),
        remainingSteps = remainingSteps(status),
    )

    /**
     * Missing blocking/survival grants, cheapest route to blocking first.
     *
     * Usually that is declaration order: Path A, then the two halves of Path B, then
     * survival. When usage access is already granted and blocking is off, however, the
     * overlay alone brings Path B up in one direct Settings hand-off, so it leads.
     */
    private fun remainingSteps(status: HealthStatus): List<SetupStep> {
        val missing = buildList {
            if (!status.accessibilityEnabled) add(SetupStep.ENABLE_ACCESSIBILITY)
            if (!status.usageAccessGranted) add(SetupStep.GRANT_USAGE_ACCESS)
            if (!status.overlayGranted) add(SetupStep.GRANT_OVERLAY)
            if (!status.batteryExemptionGranted) add(SetupStep.REQUEST_BATTERY_EXEMPTION)
        }
        return if (overlayAloneRestoresBlocking(status)) {
            listOf(SetupStep.GRANT_OVERLAY) + (missing - SetupStep.GRANT_OVERLAY)
        } else {
            missing
        }
    }

    /** Blocking is off, Flint can already detect the foreground app, and only overlay is missing. */
    private fun overlayAloneRestoresBlocking(status: HealthStatus): Boolean =
        !status.canEnforce && status.usageAccessGranted && !status.overlayGranted

    private fun headline(status: HealthStatus): String = when {
        !status.canEnforce -> "Blocking is off"
        !status.accessibilityEnabled -> "Blocking is on (backup path)"
        !status.fallbackPathAvailable || !status.batteryExemptionGranted ->
            "Blocking is on, but it isn't protected"
        else -> "Blocking is on"
    }

    /** The enforcement story first, followed by survival and visibility caveats when relevant. */
    private fun body(status: HealthStatus): String {
        val parts = mutableListOf<String>()

        when {
            overlayAloneRestoresBlocking(status) -> parts +=
                "Flint can see which app is in the foreground, but it has no way to show the " +
                    "block screen. Allowing it to display over other apps starts blocking."
            !status.canEnforce -> parts +=
                "Flint has no way to detect or block anything right now. Turning on the " +
                    "accessibility service alone is enough to start blocking."
            !status.accessibilityEnabled -> parts +=
                "Flint is blocking through the usage-stats backup path, which reacts a moment " +
                    "later and cannot block websites. The accessibility service restores " +
                    "instant blocking."
            !status.fallbackPathAvailable -> parts +=
                "The backup path is incomplete, so if the accessibility service is ever turned " +
                    "off, blocking would stop entirely."
        }

        if (!status.batteryExemptionGranted) {
            parts += "Flint is not exempt from battery optimization, so some phones will stop " +
                "it in the background. The exemption is recommended, not required."
        }

        if (!status.accessibilityEnabled && status.fallbackPathAvailable &&
            !status.serviceNotificationVisible
        ) {
            parts += "Android is hiding the backup service's status notification. Blocking " +
                "itself is unaffected; the Notifications row in Flint Settings can restore it."
        }

        return parts.joinToString(" ").ifEmpty {
            "Instant blocking, the backup path, and the battery exemption are all in place."
        }
    }
}
