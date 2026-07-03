package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakDecision
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState

/**
 * Pure decision logic for the three protection tiers (Opal spec 2.3 — all FREE in Flint):
 *
 * - [BreakLevel.EASY] — "Yes, make it easy": a break is granted immediately.
 * - [BreakLevel.HARDER] — "Yes, but make it harder": the first ask arms a friction delay
 *   ([harderDelaysSeconds], escalating with each break already taken); the break is granted
 *   only when asked again after the delay elapses. A pending request survives app switches;
 *   only [cancelPending] clears it, and the UI must expose that **inside Flint only**
 *   ("reset only inside the app" — the block screen never offers it).
 * - [BreakLevel.HARDCORE] — "No way, I'm hardcore": never granted. The sole exit is the
 *   weekly Emergency Pass, consumed *explicitly* via [EmergencyPassPolicy.consume]; this
 *   policy never spends it implicitly.
 *
 * No Android imports, no clock, no I/O — callers pass `nowEpochMs` and persist the returned
 * [BreakSessionState] (core-datastore `FocusStateStore`), so both detection paths and the UI
 * reach identical conclusions.
 *
 * The default delays (1 min → 5 min → 15 min, clamped at the last) are Flint's choice; Opal
 * does not document its exact values, only "extended waits/delays between breaks".
 */
class BreakPolicy(
    private val harderDelaysSeconds: List<Long> = DEFAULT_HARDER_DELAYS_SECONDS,
) {

    init {
        require(harderDelaysSeconds.isNotEmpty()) { "harderDelaysSeconds must not be empty" }
        require(harderDelaysSeconds.all { it >= 0L }) { "harderDelaysSeconds must be non-negative" }
    }

    /**
     * Decide a break request at [nowEpochMs] for a block at [level], given the session's
     * current [session] state. Persist the state carried by the returned [BreakDecision].
     */
    fun requestBreak(
        level: BreakLevel,
        session: BreakSessionState,
        nowEpochMs: Long,
    ): BreakDecision = when (level) {
        BreakLevel.EASY -> BreakDecision.Granted(taken(session))

        BreakLevel.HARDER -> {
            val pending = session.pending
            when {
                pending == null -> {
                    val delayMs = harderDelaySeconds(session.breaksTaken) * 1000L
                    val request = BreakRequestState(
                        requestedAtEpochMs = nowEpochMs,
                        effectiveAtEpochMs = nowEpochMs + delayMs,
                    )
                    BreakDecision.Pending(session.copy(pending = request))
                }
                pending.isElapsed(nowEpochMs) -> BreakDecision.Granted(taken(session))
                else -> BreakDecision.Pending(session)
            }
        }

        BreakLevel.HARDCORE -> BreakDecision.DeniedHardcore
    }

    /**
     * Friction delay (seconds) for the next HARDER break when [breaksTaken] were already
     * taken this session. Escalates through [harderDelaysSeconds]; clamps at the last entry.
     */
    fun harderDelaySeconds(breaksTaken: Int): Long =
        harderDelaysSeconds[breaksTaken.coerceIn(0, harderDelaysSeconds.lastIndex)]

    /**
     * Explicitly abandon a pending HARDER request. This is the "reset" that must only be
     * reachable from within Flint — never wire it to the block screen itself.
     */
    fun cancelPending(session: BreakSessionState): BreakSessionState = session.copy(pending = null)

    private fun taken(session: BreakSessionState): BreakSessionState =
        BreakSessionState(breaksTaken = session.breaksTaken + 1, pending = null)

    companion object {
        /** Flint defaults: 1 min, then 5 min, then 15 min for every later break. */
        val DEFAULT_HARDER_DELAYS_SECONDS: List<Long> = listOf(60L, 300L, 900L)
    }
}
