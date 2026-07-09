package com.flint.peakfocus.feature.onboarding

import com.flint.peakfocus.blocking.resilience.HealthStatus

/**
 * "Is Flint blocking right now, and what is the single next thing to do about it?"
 *
 * The Home status card used to answer the first question from one boolean — is the
 * AccessibilityService on — which is wrong in both directions. Flint enforces perfectly well
 * through the Path B fallback (usage access + overlay) with that service off, so the card told
 * protected users blocking was OFF and pushed them at a permission they did not need; and the
 * service being on says nothing about whether the fallback would survive its revocation.
 *
 * Both questions are answered here from blocking-resilience's [HealthStatus] — the single
 * source of truth for what is granted (strategy doc §6.1: re-verify every grant on each launch).
 *
 * Pure Kotlin, no Android types — JVM-tested in SetupGuidanceTest. Every word the card shows
 * lives here, not in the composable, so the tests can hold it to the ADR-007 standard: never
 * claim protection Flint does not have, and never dress the battery exemption up as mandatory.
 */

/** A grant the user has yet to make, phrased as the action they would take. */
enum class SetupStep(val ctaLabel: String) {
    /**
     * Path A. The prominent-disclosure consent screen must come between this step and
     * Accessibility settings (Play policy, ADR-007) — see the hand-off in MainActivity.
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
 * @property enforcingNow whether Flint can block anything at all right now, by either path —
 *   the headline's only claim to "on"
 * @property remainingSteps every grant still missing, cheapest route to blocking first
 */
data class SetupPlan(
    val enforcingNow: Boolean,
    val headline: String,
    val body: String,
    val remainingSteps: List<SetupStep>,
) {
    /** The one action the card offers; null once every grant is in place. */
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
     * Missing grants, cheapest route to blocking first.
     *
     * Usually that is declaration order: Path A, then the two halves of Path B, then survival.
     * The exception is [overlayAloneRestoresBlocking] — when blocking is off but usage access is
     * already granted, the overlay grant on its own brings Path B up. One tap, and no consent
     * screen on the way, so it leads.
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

    /** Blocking is off, Flint can already see the foreground app, and only the overlay is missing. */
    private fun overlayAloneRestoresBlocking(status: HealthStatus): Boolean =
        !status.canEnforce && status.usageAccessGranted && !status.overlayGranted

    private fun headline(status: HealthStatus): String = when {
        !status.canEnforce -> "Blocking is off"
        !status.accessibilityEnabled -> "Blocking is on (backup path)"
        !status.fallbackPathAvailable || !status.batteryExemptionGranted ->
            "Blocking is on, but it isn't protected"
        else -> "Blocking is on"
    }

    /**
     * The enforcement story, then the survival caveat. Both are stated as facts about the
     * current grants — the card never speculates and never threatens.
     */
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

        return parts.joinToString(" ").ifEmpty {
            "Instant blocking, the backup path, and the battery exemption are all in place."
        }
    }
}
