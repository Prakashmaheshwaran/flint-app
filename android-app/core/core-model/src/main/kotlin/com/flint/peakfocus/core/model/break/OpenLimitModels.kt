// NOTE on location: lives in `model/break/` (the fleet scope for these shared types) but keeps
// the shared `core.model` package — `break` is a Kotlin hard keyword; see BreakModels.kt.
package com.flint.peakfocus.core.model

/**
 * An **Open Limit**: daily cap on explicit five-minute access credits. The app stays
 * shielded between intervals, including before its first credit. Foreground events do
 * not charge. The historical property name [dailyOpens] is retained for wire compatibility.
 *
 * [breakLevel] is the limit's protection tier (spec 2.3 applies to Open Limits as well):
 * EASY may be overridden from the block screen, HARDER only after the friction delay,
 * HARDCORE permits no ordinary exception after exhaustion. Positive quota credits need
 * no Harder wait at any tier; the distinct Emergency Pass follows its rolling cooldown.
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
    /** Configuration has not loaded; absence of a limit has not been established. */
    data object Unavailable : OpenLimitDecision

    /** Under (or without) a limit. [remainingOpens] is null when the app has no Open Limit. */
    data class Allowed(val remainingOpens: Int?) : OpenLimitDecision

    /** Quota exhausted for today: block, with the exhausted [limit] for UI/policy decisions. */
    data class Blocked(val limit: OpenLimit, val reason: String) : OpenLimitDecision
}
