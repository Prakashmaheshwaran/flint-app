package com.flint.peakfocus.core.model

/**
 * Difficulty of breaking or stopping a block early.
 *
 * In Opal, [HARDCORE] (Deep Focus) is paywalled. In Flint it is FREE — it is the whole point.
 */
enum class BreakLevel {
    /** "Make it easy" — break/cancel freely. */
    EASY,

    /** "Make it harder" — friction delays before a break; reset only inside Flint. */
    HARDER,

    /** "No way, I'm hardcore" — cannot break/stop early; one free weekly emergency pass. */
    HARDCORE,
}

/**
 * A reference to a blockable app. On Android we can name packages directly via [PackageManager]
 * (unlike iOS, where Screen Time hands back opaque tokens).
 */
data class AppRef(
    val packageName: String,
    val label: String? = null,
)

/** A reference to a blockable website/domain (matched against the browser URL). */
data class DomainRef(val domain: String)

/** What a block targets. */
data class BlockTargets(
    val apps: Set<AppRef> = emptySet(),
    val domains: Set<DomainRef> = emptySet(),
    /** Allow-list ("brick phone") mode: block everything EXCEPT [apps]. Free in Flint. */
    val allowListMode: Boolean = false,
)

/**
 * A schedule window. [daysOfWeek] uses ISO numbering (1 = Monday … 7 = Sunday);
 * empty means every day.
 */
data class Schedule(
    val daysOfWeek: Set<Int> = emptySet(),
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
)

/**
 * A configured block rule: what to block, when, and how hard to break.
 * [schedule] = null means a manual "Block Now" rule. Flint imposes no count cap and no
 * 24h-advance cap (both are Opal paywalls).
 */
data class BlockRule(
    val id: String,
    val name: String,
    val targets: BlockTargets,
    val schedule: Schedule? = null,
    val breakLevel: BreakLevel = BreakLevel.EASY,
    val enabled: Boolean = true,
)

/** A live session derived from a rule (manual or schedule-triggered). */
data class ActiveSession(
    val ruleId: String,
    val startedAtEpochMs: Long,
    /** null = open-ended until manually stopped. */
    val endsAtEpochMs: Long? = null,
    val breakLevel: BreakLevel,
)

/** The decision the engine returns for the current foreground context. */
sealed interface Verdict {
    data object Allow : Verdict
    data class Block(val ruleId: String, val reason: String) : Verdict
}
