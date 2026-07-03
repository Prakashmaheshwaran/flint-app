// NOTE on location: this file lives in the `model/break/` directory (the fleet scope for
// break-level types) but declares the same package as the rest of core-model. `break` is a
// Kotlin hard keyword, so a literal `…model.break` package would force backtick-escaped
// imports on every consumer; Kotlin does not require package == directory, so we keep the
// one shared package all existing model types use.
package com.flint.peakfocus.core.model

/**
 * A pending "let me take a break" request against a HARDER-level block.
 *
 * Opal's "Yes, but make it harder" tier inserts a friction delay between asking for a break
 * and getting it. The request is created at [requestedAtEpochMs] and only becomes effective
 * at [effectiveAtEpochMs]; until then the block stays up. Produced/consumed by the pure
 * `BreakPolicy` in blocking-engine and persisted via core-datastore's `FocusStateStore`.
 */
data class BreakRequestState(
    val requestedAtEpochMs: Long,
    val effectiveAtEpochMs: Long,
) {
    /** True once the friction delay has fully elapsed at [nowEpochMs]. */
    fun isElapsed(nowEpochMs: Long): Boolean = nowEpochMs >= effectiveAtEpochMs
}

/**
 * Per-session break bookkeeping: how many breaks were already taken (drives HARDER's
 * escalating delays) and the not-yet-effective request, if any.
 *
 * Pure data; all transitions live in blocking-engine's `BreakPolicy` so both detection paths
 * and the UI agree on the rules. "Resets only from within Flint": clearing [pending] is an
 * explicit in-app action (`BreakPolicy.cancelPending`) — the block screen never clears it.
 */
data class BreakSessionState(
    val breaksTaken: Int = 0,
    val pending: BreakRequestState? = null,
)

/**
 * The pure decision for a break request. In Opal the HARDCORE tier (and the Emergency Pass
 * that softens it) is paywalled; in Flint all of this is FREE.
 */
sealed interface BreakDecision {
    /** Break granted now. Persist [session] (count incremented, pending cleared). */
    data class Granted(val session: BreakSessionState) : BreakDecision

    /**
     * HARDER friction: the break is not effective yet. Persist [session] and show a countdown
     * until `session.pending.effectiveAtEpochMs`; ask again after it elapses to get [Granted].
     */
    data class Pending(val session: BreakSessionState) : BreakDecision

    /**
     * HARDCORE never grants breaks. The only exit is the weekly Emergency Pass — an explicit,
     * separate consumption via `EmergencyPassPolicy.consume` (never spent implicitly).
     */
    data object DeniedHardcore : BreakDecision
}

/**
 * The weekly Emergency Pass — the controlled escape hatch for HARDCORE blocks (Opal: Pro-only;
 * Flint: free, iOS and Android both).
 *
 * One pass per ISO week (Monday-start). [weekStartEpochDay] is the local epoch-day of the
 * Monday the state belongs to; [usedAtEpochMs] is non-null once this week's pass was spent.
 * The default instance ([WEEK_UNINITIALIZED]) means "never tracked yet" — the pure
 * `EmergencyPassPolicy` (blocking-engine) rolls any stale/uninitialized state into the current
 * week with the pass available. Persisted via core-datastore's `FocusStateStore`.
 */
data class EmergencyPassState(
    val weekStartEpochDay: Long = WEEK_UNINITIALIZED,
    val usedAtEpochMs: Long? = null,
) {
    companion object {
        /** Sentinel: no week tracked yet; any refresh resets to the current week, pass available. */
        const val WEEK_UNINITIALIZED: Long = Long.MIN_VALUE
    }
}
