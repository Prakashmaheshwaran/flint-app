package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState

/**
 * A detected wall-clock / timezone change: "the clock read [oldEpochMs] a moment ago and reads
 * [newEpochMs] now" (spec 2.4/2.5 — anti-bypass time-change guards). Pure data; the Android
 * side (blocking-resilience's `TimeChangeReceiver`) measures it, [ClockChangeGuard] judges it.
 *
 * [oldEpochMs] is the *projected true* time at the moment of measurement — the last anchored
 * wall time plus the monotonic real time elapsed since (elapsedRealtime keeps counting through
 * deep sleep and never follows the wall clock). So [wallDeltaMs] is exactly the jump a user
 * (or NITZ sync) injected, not time that genuinely passed.
 */
data class ClockShift(
    val oldEpochMs: Long,
    val newEpochMs: Long,
    val oldUtcOffsetMs: Long,
    val newUtcOffsetMs: Long,
) {
    /**
     * The injected wall-clock jump (positive = set forward). 0 for a pure timezone hop —
     * epoch millis are UTC and do not move when the zone does — and 0 when the old time
     * could not be measured (no anchor yet, or the anchor predates a reboot).
     */
    val wallDeltaMs: Long get() = newEpochMs - oldEpochMs

    companion object {
        /**
         * Measure a shift against a persisted anchor: `old = anchorWall + (elapsed since the
         * anchor)`. A negative elapsed delta means the anchor predates a reboot
         * (elapsedRealtime restarts at 0) — the jump is unmeasurable, so we fall back to
         * [unmeasured] rather than inventing one (fail toward "no wall jump"; the day/week
         * re-keying below never needed the old time anyway).
         */
        fun fromAnchor(
            anchorWallEpochMs: Long,
            anchorElapsedRealtimeMs: Long,
            nowWallEpochMs: Long,
            nowElapsedRealtimeMs: Long,
            oldUtcOffsetMs: Long,
            newUtcOffsetMs: Long,
        ): ClockShift {
            val elapsed = nowElapsedRealtimeMs - anchorElapsedRealtimeMs
            if (elapsed < 0) return unmeasured(nowWallEpochMs, oldUtcOffsetMs, newUtcOffsetMs)
            return ClockShift(
                oldEpochMs = anchorWallEpochMs + elapsed,
                newEpochMs = nowWallEpochMs,
                oldUtcOffsetMs = oldUtcOffsetMs,
                newUtcOffsetMs = newUtcOffsetMs,
            )
        }

        /** A shift whose wall jump is unknown (no usable anchor): only the zone/day key moved. */
        fun unmeasured(
            nowWallEpochMs: Long,
            oldUtcOffsetMs: Long,
            newUtcOffsetMs: Long,
        ): ClockShift = ClockShift(nowWallEpochMs, nowWallEpochMs, oldUtcOffsetMs, newUtcOffsetMs)
    }
}

/**
 * Fail-closed policy for detected clock/date/timezone changes — the pure half of the
 * time-change guard (Opal spec 2.4 "prevent clock change", 2.5 anti-bypass; both FREE here).
 *
 * Threat model: schedules, Time/Open Limits and the weekly Emergency Pass are all keyed to
 * wall time, so moving the clock or hopping timezones is the cheapest bypass. The guard is
 * two layers, both pure:
 *
 *  1. **Always-on backward guard** — lives *inside* [OpenLimitPolicy.rolledOver] and
 *     [EmergencyPassPolicy.refreshed] (day/week keys only roll forward), so every evaluation
 *     on both detection paths is protected even when no broadcast was delivered (process
 *     dead, OEM stripped the receiver, clock changed while powered off).
 *  2. **Shift-driven re-keying** — this class, applied by `TimeChangeReceiver` when the OS
 *     reports TIME_SET / TIMEZONE_CHANGED. A change that *advances* the local day/week
 *     carries consumed state into the new key instead of resetting it: set the clock forward
 *     past midnight (or hop east across it) and today's spent opens are still spent.
 *
 * Real day rollovers stay honest because genuine midnight passage fires no TIME_SET /
 * TIMEZONE_CHANGED broadcast — only set clocks and moved zones land here, so carrying state
 * forward never denies a legitimate fresh day. The conservative cost of a *genuine* traveler
 * or a cheat-then-revert round trip (a reset arriving up to one period late) falls on the
 * clock-mover, never on the block.
 *
 * No Android imports, no clock, no I/O — callers pass the measured [ClockShift] and persist
 * whatever comes back (core-datastore's `FocusStateStore`), same contract as the other
 * policies here.
 *
 * Honest limits (documented, not hidden): schedule windows and Path-A Time Limits read the OS
 * wall clock / UsageStats daily buckets directly, so a changed clock still moves them — the
 * receiver forces immediate re-evaluation but does not veto the OS clock. And a clock set
 * back during an *already granted* break stretches that break's exemption window; HARDCORE is
 * unaffected (it never grants breaks).
 */
class ClockChangeGuard {

    /**
     * Conservative Open-Limit counts after [shift]: if the (new-zone) local day moved
     * *forward*, re-key to it and **keep** the consumed counts — a set-forward clock or an
     * eastward hop never grants a fresh day. Backward or same-day changes return [state]
     * untouched ([OpenLimitPolicy.rolledOver]'s forward-only rule already refuses to reset
     * on those at evaluation time). Persist the result whenever it differs.
     */
    fun guardedOpenCounts(state: OpenCountState, shift: ClockShift): OpenCountState {
        if (state.epochDay == OpenCountState.DAY_UNINITIALIZED) return state
        val newDay = DayMath.epochDay(shift.newEpochMs, shift.newUtcOffsetMs)
        return if (newDay > state.epochDay) state.copy(epochDay = newDay) else state
    }

    /**
     * Conservative Emergency-Pass state after [shift]: an ISO week that moved *forward* is
     * re-keyed with [EmergencyPassState.usedAtEpochMs] carried along — jumping the clock a
     * week ahead does not mint a fresh pass (an unspent pass simply stays available under
     * the new key). Backward or same-week changes return [state] untouched
     * ([EmergencyPassPolicy.refreshed]'s forward-only rule covers those).
     */
    fun guardedEmergencyPass(state: EmergencyPassState, shift: ClockShift): EmergencyPassState {
        if (state.weekStartEpochDay == EmergencyPassState.WEEK_UNINITIALIZED) return state
        val newWeek = DayMath.weekStartEpochDay(shift.newEpochMs, shift.newUtcOffsetMs)
        return if (newWeek > state.weekStartEpochDay) state.copy(weekStartEpochDay = newWeek) else state
    }

    /**
     * Keep a pending HARDER friction wait the same *real* length across [shift]: the request
     * timestamps move with the injected [ClockShift.wallDeltaMs], so setting the clock
     * forward cannot fast-forward the wait and setting it back does not stretch it. A pure
     * timezone hop (delta 0) and an unmeasured shift are no-ops — epoch-based timestamps do
     * not move with the zone, and we never invent a jump we could not measure.
     */
    fun guardedBreakSession(session: BreakSessionState, shift: ClockShift): BreakSessionState {
        val pending = session.pending ?: return session
        val delta = shift.wallDeltaMs
        if (delta == 0L) return session
        return session.copy(
            pending = BreakRequestState(
                requestedAtEpochMs = pending.requestedAtEpochMs + delta,
                effectiveAtEpochMs = pending.effectiveAtEpochMs + delta,
            ),
        )
    }
}
