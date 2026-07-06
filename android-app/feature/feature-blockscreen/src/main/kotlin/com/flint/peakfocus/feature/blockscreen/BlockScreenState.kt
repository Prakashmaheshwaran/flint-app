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

    /** The app's daily open quota is used up. */
    OPEN_LIMIT,

    /** A hardcore Deep Focus session is running. */
    DEEP_FOCUS,
}

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
    /**
     * HARDER only: the full length of that cooldown, so the wait notice can show determinate
     * progress. Null (or ≤ 0) hides the ring and keeps the text-only notice — older hosts
     * that never learned this field render exactly as before.
     */
    val breakWaitTotalMillis: Long? = null,
)

/** How long the HARDER-level control must be held before `onBreakRequested` fires. */
internal const val BREAK_HOLD_MILLIS = 1_500L

/** The break affordance the screen offers, derived from break level + host wait state. */
internal sealed interface BreakAffordance {
    /** EASY: a plain "Take a break" button. */
    data object TakeABreak : BreakAffordance

    /** HARDER, no pending cooldown: press-and-hold friction before the request fires. */
    data class HoldToRequest(val holdMillis: Long) : BreakAffordance

    /**
     * HARDER, host says a cooldown is pending: no control, just a wait notice.
     * [progress] is the elapsed fraction of the full wait (0f..1f) for a determinate ring,
     * or null when the host didn't supply the total (text-only notice).
     */
    data class WaitBeforeBreak(
        val remainingMillis: Long,
        val progress: Float? = null,
    ) : BreakAffordance

    /** HARDCORE: no bypass affordance — only the Emergency Pass CTA. */
    data object EmergencyPassOnly : BreakAffordance
}

/** Fully resolved copy + affordance for one render of the screen. Pure and unit-tested. */
internal data class BlockScreenContent(
    val displayName: String,
    val headline: String,
    val reasonLine: String,
    val encouragement: String,
    val breakAffordance: BreakAffordance,
)

internal fun blockScreenContent(state: BlockScreenState): BlockScreenContent {
    val name = displayNameFor(state)
    return BlockScreenContent(
        displayName = name,
        headline = "$name can wait",
        reasonLine = reasonLineFor(state.reason),
        encouragement = encouragementFor(state.packageName),
        breakAffordance = breakAffordanceFor(
            state.breakLevel,
            state.breakWaitRemainingMillis,
            state.breakWaitTotalMillis,
        ),
    )
}

internal fun displayNameFor(state: BlockScreenState): String =
    state.appLabel?.takeIf { it.isNotBlank() } ?: state.packageName

internal fun reasonLineFor(reason: BlockScreenReason): String = when (reason) {
    BlockScreenReason.MANUAL_SESSION -> "A focus session you started is running."
    BlockScreenReason.SCHEDULE -> "This hour belongs to one of your schedules."
    BlockScreenReason.TIME_LIMIT -> "You’ve used today’s time for this app."
    BlockScreenReason.OPEN_LIMIT -> "You’ve used today’s opens for this app."
    BlockScreenReason.DEEP_FOCUS -> "Deep Focus is on until the session ends."
}

internal fun breakAffordanceFor(
    level: BreakLevel,
    breakWaitRemainingMillis: Long?,
    breakWaitTotalMillis: Long? = null,
): BreakAffordance = when (level) {
    BreakLevel.EASY -> BreakAffordance.TakeABreak
    BreakLevel.HARDER ->
        if (breakWaitRemainingMillis != null && breakWaitRemainingMillis > 0) {
            BreakAffordance.WaitBeforeBreak(
                remainingMillis = breakWaitRemainingMillis,
                progress = waitProgress(breakWaitTotalMillis, breakWaitRemainingMillis),
            )
        } else {
            BreakAffordance.HoldToRequest(BREAK_HOLD_MILLIS)
        }
    BreakLevel.HARDCORE -> BreakAffordance.EmergencyPassOnly
}

/**
 * Elapsed fraction of the HARDER wait for the determinate ring: 0f at the moment the wait
 * started, 1f when it's over. Null (no ring) when the host didn't supply a usable total.
 * Clamped so host clock skew (remaining > total, negative remaining) can't paint an
 * out-of-range ring.
 */
internal fun waitProgress(totalMillis: Long?, remainingMillis: Long): Float? {
    if (totalMillis == null || totalMillis <= 0) return null
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

/**
 * The same countdown in words for screen readers — TalkBack reads "4m 32s" as a letter salad.
 * Mirrors [formatDuration]'s unit choices exactly so eyes and ears get the same information.
 */
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

// ---- Accessibility copy (pure, so tests can hold it to the same honesty bar as visible copy) ----

/**
 * TalkBack action + description for the HARDER hold-to-request control. The visual control is
 * a press-and-hold with no click semantics, which a screen reader cannot operate; the semantic
 * click action is the accessible equivalent. The hold friction is deterrence, not security —
 * for a screen-reader user, navigating to the control and double-tapping is already a
 * deliberate act, so the action fires the same request the completed hold would.
 */
internal const val HOLD_BREAK_A11Y_LABEL = "Request a break"
internal const val HOLD_BREAK_A11Y_DESCRIPTION =
    "Request a break. Hold to fill, or double-tap to request."

/** Spoken form of the HARDER wait notice. */
internal fun waitNoticeA11y(remainingMillis: Long): String =
    "Break available in ${formatDurationSpoken(remainingMillis)}"

/** Spoken form of the "Unblocks in …" countdown pill. */
internal fun unblockCountdownA11y(remainingMillis: Long): String =
    "Unblocks in ${formatDurationSpoken(remainingMillis)}"
