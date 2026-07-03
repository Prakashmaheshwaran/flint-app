// NOTE on location: lives in `model/break/` (the fleet scope for these shared types) but keeps
// the shared `core.model` package — `break` is a Kotlin hard keyword; see BreakModels.kt.
package com.flint.peakfocus.core.model

/**
 * An **Open Limit**: cap on how many times per day an app may be opened ("Opens Allowed").
 * After the quota, the block screen appears until the next day. Opal ships this on iOS only
 * and paywalls the no-reset tier; Flint ships it on Android too, free, including [BreakLevel.HARDCORE].
 *
 * [breakLevel] is the limit's protection tier (spec 2.3 applies to Open Limits as well):
 * EASY may be overridden from the block screen, HARDER only after the friction delay,
 * HARDCORE not at all (weekly Emergency Pass excepted). Mirrors [TimeLimit]'s shape.
 */
data class OpenLimit(
    val packageName: String,
    val dailyOpens: Int,
    val breakLevel: BreakLevel = BreakLevel.EASY,
)

/**
 * Per-day open counts (package → opens used) for [epochDay] (local). Pure data; counting and
 * day-rollover transitions live in blocking-engine's `OpenLimitPolicy` so the engine stays the
 * single decision point. Persisted via core-datastore's `FocusStateStore`.
 */
data class OpenCountState(
    val epochDay: Long = DAY_UNINITIALIZED,
    val opensByPackage: Map<String, Int> = emptyMap(),
) {
    /** Opens recorded today for [packageName] (0 if none). */
    fun opensFor(packageName: String): Int = opensByPackage[packageName] ?: 0

    companion object {
        /** Sentinel: no day tracked yet; the first rollover resets to the current day. */
        const val DAY_UNINITIALIZED: Long = Long.MIN_VALUE
    }
}

/**
 * The engine's decision for an Open Limit check — richer than [Verdict] because the block
 * screen needs the limit (and its [BreakLevel]) to offer the right affordances.
 */
sealed interface OpenLimitDecision {
    /** Under (or without) a limit. [remainingOpens] is null when the app has no Open Limit. */
    data class Allowed(val remainingOpens: Int?) : OpenLimitDecision

    /** Quota exhausted for today: block, with the exhausted [limit] for UI/policy decisions. */
    data class Blocked(val limit: OpenLimit, val reason: String) : OpenLimitDecision
}
