package com.flint.peakfocus.feature.blockscreen

import com.flint.peakfocus.core.model.BreakLevel

/**
 * Why the block screen is being shown. A UI-level mirror of the enforcement triggers
 * (engine verdicts, daily time/open limits, Deep Focus) — defined here so this module
 * stays a pure-UI dependency for its hosts, with no service-layer imports.
 */
enum class BlockScreenReason {
    /** A manual "Block Now" focus session is running. */
    MANUAL_SESSION,

    /** A recurring/scheduled block window is active. */
    SCHEDULE,

    /** The app's daily time budget is used up. */
    TIME_LIMIT,

    /** The app is shielded between timed credits, or its daily credit quota is used up. */
    OPEN_LIMIT,

    /** A hardcore Deep Focus session is running. */
    DEEP_FOCUS,
}

/** Host persistence status; only READY/FAILED may offer a new tier-dependent request. */
enum class GrantStatus { READY, LOADING, SAVING, UNAVAILABLE, FAILED, RETRY_OPEN, RESETTING }

/**
 * Everything [BlockScreen] needs to render, supplied by the host (accessibility overlay or
 * full-screen Activity — wired by A-SERVICE-INTEGRATION).
 *
 * The screen is stateless: the countdowns ([remainingMillis], [breakWaitRemainingMillis]) are
 * plain display values. The HOST owns the clock and re-renders with fresh state to tick them;
 * no timers run inside this module.
 */
data class BlockScreenState(
    /** Package of the blocked app. Display fallback when [appLabel] is missing. */
    val packageName: String,
    /** Human-readable app label (e.g. "Instagram"); falls back to [packageName] when null/blank. */
    val appLabel: String? = null,
    val reason: BlockScreenReason,
    val breakLevel: BreakLevel,
    /** Time until the block lifts, or null when open-ended/unknown (hides the countdown pill). */
    val remainingMillis: Long? = null,
    /**
     * HARDER only: host-driven cooldown before the next break may be requested. While > 0 the
     * hold-to-request control is replaced by a wait notice. Ignored at EASY (breaks are free)
     * and HARDCORE (no breaks at all).
     */
    val breakWaitRemainingMillis: Long? = null,
    /** Full HARDER cooldown length; enables determinate progress when positive. */
    val breakWaitTotalMillis: Long? = null,
    /**
     * HARDCORE only: whether this week's Emergency Pass is still unspent (host-supplied from
     * its pass accounting). False renders the CTA as an inert "already used" notice instead
     * of a button whose tap would silently no-op.
     */
    val emergencyPassAvailable: Boolean = true,
    val grantStatus: GrantStatus = GrantStatus.READY,
    val openCreditsRemaining: Int? = null,
    val openCreditDurationMillis: Long? = null,
    val openCreditActiveRemainingMillis: Long? = null,
    val openCreditBlockingContributors: List<String> = emptyList(),
    /** Opaque reviewed-action token. The host revalidates it in the accepting transaction. */
    val openCreditRequestScope: String? = null,
)

/** How long the HARDER-level control must be held before `onBreakRequested` fires. */
internal const val BREAK_HOLD_MILLIS = 1_500L

/** The break affordance the screen offers, derived from break level + host wait state. */
internal sealed interface BreakAffordance {
    data object Saving : BreakAffordance
    data object Unavailable : BreakAffordance
    data object RetryOpen : BreakAffordance
    data object QuotaPending : BreakAffordance
    data class UseCredit(val remaining: Int, val durationMillis: Long) : BreakAffordance
    data class CreditBlocked(val contributors: List<String>) : BreakAffordance
    /** EASY: a plain "Take a break" button. */
    data object TakeABreak : BreakAffordance

    /** HARDER, no pending cooldown: press-and-hold friction before the request fires. */
    data class HoldToRequest(val holdMillis: Long) : BreakAffordance

    /** HARDER, host says a cooldown is pending: no control, just a wait notice. */
    data class WaitBeforeBreak(
        val remainingMillis: Long,
        val progress: Float? = null,
    ) : BreakAffordance

    /** HARDCORE: no bypass affordance — only the Emergency Pass CTA, and only while this
     *  week's pass is unspent ([passAvailable] false shows an inert "already used" notice). */
    data class EmergencyPassOnly(val passAvailable: Boolean) : BreakAffordance
}

/** Fully resolved copy + affordance for one render of the screen. Pure and unit-tested. */
internal data class BlockScreenContent(
    val displayName: String,
    val headline: String,
    val reasonLine: String,
    val badge: BlockScreenBadge,
    /** Reason-aware countdown text, or null when the block has no known end. */
    val countdownLabel: String?,
    val encouragement: String,
    val breakAffordance: BreakAffordance,
)

/**
 * The cause badge above the countdown (rendered uppercase by the badge component).
 * [emphasized] is the spark-filled treatment, reserved for the unbreakable HARDCORE tier.
 */
internal data class BlockScreenBadge(
    val text: String,
    val emphasized: Boolean,
)

internal fun blockScreenContent(state: BlockScreenState): BlockScreenContent {
    val name = displayNameFor(state)
    return BlockScreenContent(
        displayName = name,
        headline = "$name is resting.",
        reasonLine = (if (state.grantStatus == GrantStatus.RESETTING)
            "Access credits are updating. This app stays protected."
            else if (state.reason == BlockScreenReason.OPEN_LIMIT && (state.openCreditsRemaining ?: 0) > 0)
            "This app stays protected between 5-minute access credits." else reasonLineFor(state.reason)) +
            (if ((state.openCreditActiveRemainingMillis ?: 0) > 0) " Credit access ends in ${formatDuration(state.openCreditActiveRemainingMillis!!)}; ${state.openCreditsRemaining ?: 0} credits remain." else "") +
            if (state.openCreditBlockingContributors.isNotEmpty()) " Also blocked by ${state.openCreditBlockingContributors.joinToString(", ")}." else "",
        badge = badgeFor(state.reason, state.breakLevel),
        countdownLabel = countdownLabelFor(state.reason, state.remainingMillis),
        encouragement = encouragementFor(state.packageName),
        breakAffordance = when (state.grantStatus) {
            GrantStatus.LOADING, GrantStatus.SAVING -> BreakAffordance.Saving
            GrantStatus.UNAVAILABLE -> BreakAffordance.Unavailable
            GrantStatus.RETRY_OPEN -> BreakAffordance.RetryOpen
            GrantStatus.RESETTING -> BreakAffordance.QuotaPending
            GrantStatus.READY, GrantStatus.FAILED -> when {
                (state.openCreditsRemaining ?: 0) > 0 && (state.openCreditActiveRemainingMillis ?: 0) == 0L ->
                    if (state.openCreditBlockingContributors.isNotEmpty()) BreakAffordance.CreditBlocked(state.openCreditBlockingContributors)
                    else BreakAffordance.UseCredit(state.openCreditsRemaining!!, state.openCreditDurationMillis ?: 0)
                else -> breakAffordanceFor(
            state.reason,
            state.breakLevel,
            state.breakWaitRemainingMillis,
            state.breakWaitTotalMillis,
            state.emergencyPassAvailable,
            )
            }
        },
    )
}

internal fun displayNameFor(state: BlockScreenState): String =
    state.appLabel?.takeIf { it.isNotBlank() } ?: state.packageName

internal fun reasonLineFor(reason: BlockScreenReason): String = when (reason) {
    BlockScreenReason.MANUAL_SESSION -> "A focus session you started is running."
    BlockScreenReason.SCHEDULE -> "This hour belongs to one of your schedules."
    BlockScreenReason.TIME_LIMIT -> "You’ve used today’s time for this app."
    BlockScreenReason.OPEN_LIMIT -> "You’ve used today’s access credits for this app."
    BlockScreenReason.DEEP_FOCUS -> "Deep Focus is on until the session ends."
}

internal fun badgeFor(reason: BlockScreenReason, level: BreakLevel): BlockScreenBadge =
    BlockScreenBadge(
        text = when (reason) {
            BlockScreenReason.MANUAL_SESSION -> "Focus session"
            BlockScreenReason.SCHEDULE -> "Schedule"
            BlockScreenReason.TIME_LIMIT -> "Time limit"
            BlockScreenReason.OPEN_LIMIT -> "Open limit"
            BlockScreenReason.DEEP_FOCUS -> "Deep Focus"
        },
        emphasized = level == BreakLevel.HARDCORE,
    )

/**
 * Frames daily budgets as resetting at midnight and timed blocks as unblocking when they end.
 * Open-ended blocks have no countdown label.
 */
internal fun countdownLabelFor(reason: BlockScreenReason, remainingMillis: Long?): String? {
    if (remainingMillis == null) return null
    return "${countdownVerbFor(reason)} in ${formatDuration(remainingMillis)}"
}

/** One exhaustive reason classifier shared by visual and TalkBack countdown copy. */
internal fun countdownVerbFor(reason: BlockScreenReason): String = when (reason) {
    BlockScreenReason.TIME_LIMIT, BlockScreenReason.OPEN_LIMIT -> "Resets"
    BlockScreenReason.MANUAL_SESSION,
    BlockScreenReason.SCHEDULE,
    BlockScreenReason.DEEP_FOCUS,
    -> "Unblocks"
}

internal fun breakAffordanceFor(
    reason: BlockScreenReason,
    level: BreakLevel,
    breakWaitRemainingMillis: Long?,
    breakWaitTotalMillis: Long? = null,
    emergencyPassAvailable: Boolean = true,
): BreakAffordance = when {
    level == BreakLevel.EASY -> BreakAffordance.TakeABreak
    level == BreakLevel.HARDER ->
        if (breakWaitRemainingMillis != null && breakWaitRemainingMillis > 0) {
            BreakAffordance.WaitBeforeBreak(
                remainingMillis = breakWaitRemainingMillis,
                progress = waitProgress(breakWaitTotalMillis, breakWaitRemainingMillis),
            )
        } else {
            BreakAffordance.HoldToRequest(BREAK_HOLD_MILLIS)
        }
    else -> BreakAffordance.EmergencyPassOnly(passAvailable = emergencyPassAvailable)
}

/** Elapsed fraction of a HARDER cooldown, clamped against host clock skew. */
internal fun waitProgress(totalMillis: Long?, remainingMillis: Long): Float? {
    if (totalMillis == null || totalMillis <= 0L) return null
    return ((totalMillis - remainingMillis).toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)
}

private val ENCOURAGEMENTS = listOf(
    "One breath. Then back to what matters.",
    "It will still be there when you’re done.",
    "Future you is glad you stayed.",
    "You set this block for a reason.",
    "Small friction now, real focus later.",
)

/** Deterministic per app, so the copy doesn't shuffle on every re-render of the same block. */
internal fun encouragementFor(packageName: String): String =
    ENCOURAGEMENTS[packageName.hashCode().mod(ENCOURAGEMENTS.size)]

/** Formats a countdown as "1h 24m" / "4m 32s" / "42s". Negative values clamp to "0s". */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** Word-based duration for screen readers, matching [formatDuration]'s unit choices. */
internal fun formatDurationSpoken(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    fun unit(value: Long, singular: String) = "$value $singular" + if (value == 1L) "" else "s"
    return when {
        hours > 0 -> "${unit(hours, "hour")} ${unit(minutes, "minute")}"
        minutes > 0 -> "${unit(minutes, "minute")} ${unit(seconds, "second")}"
        else -> unit(seconds, "second")
    }
}

internal const val HOLD_BREAK_A11Y_LABEL = "Request a break"
internal const val HOLD_BREAK_A11Y_DESCRIPTION =
    "Request a break. Hold to fill, or double-tap to request."

internal fun waitNoticeA11y(remainingMillis: Long): String =
    "Break available in ${formatDurationSpoken(remainingMillis)}"

internal fun countdownA11y(reason: BlockScreenReason, remainingMillis: Long): String {
    return "${countdownVerbFor(reason)} in ${formatDurationSpoken(remainingMillis)}"
}
