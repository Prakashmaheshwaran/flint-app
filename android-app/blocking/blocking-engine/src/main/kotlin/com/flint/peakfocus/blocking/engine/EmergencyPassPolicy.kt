package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.EmergencyPassState

/**
 * Pure state machine for the weekly Emergency Pass (Opal spec 2.6 — Pro there, FREE here):
 * one pass per ISO week (Monday-start, local wall time via `utcOffsetMs`); spending it is the
 * only way out of a HARDCORE block. This class owns *when* a pass exists; actually lifting
 * the shield/session is the caller's job after [consume] succeeds.
 *
 * No Android imports and no clock: callers pass `nowEpochMs` (+ optional UTC offset, e.g.
 * `TimeZone.getDefault().getOffset(now)` on Android) and persist the returned state via
 * core-datastore's `FocusStateStore`. Consumption is always explicit — see
 * [BreakPolicy]'s HARDCORE handling, which never spends the pass itself.
 */
class EmergencyPassPolicy {

    /** Local epoch-day of [nowEpochMs] (exposed so callers can feed [OpenLimitPolicy] too). */
    fun epochDay(nowEpochMs: Long, utcOffsetMs: Long = 0L): Long =
        DayMath.epochDay(nowEpochMs, utcOffsetMs)

    /** Epoch-day of the Monday starting the current ISO week. */
    fun weekStartEpochDay(nowEpochMs: Long, utcOffsetMs: Long = 0L): Long =
        DayMath.weekStartEpochDay(nowEpochMs, utcOffsetMs)

    /**
     * Roll [state] into the current week: if the stored week is stale (or uninitialized),
     * returns a fresh state for this week with the pass available; otherwise returns [state]
     * unchanged. Persist the result whenever it differs.
     */
    fun refreshed(
        state: EmergencyPassState,
        nowEpochMs: Long,
        utcOffsetMs: Long = 0L,
    ): EmergencyPassState {
        val currentWeek = weekStartEpochDay(nowEpochMs, utcOffsetMs)
        return if (state.weekStartEpochDay == currentWeek) state else EmergencyPassState(currentWeek, null)
    }

    /** True if this week's pass has not been spent yet (after rolling into the current week). */
    fun isAvailable(
        state: EmergencyPassState,
        nowEpochMs: Long,
        utcOffsetMs: Long = 0L,
    ): Boolean = refreshed(state, nowEpochMs, utcOffsetMs).usedAtEpochMs == null

    /**
     * Spend this week's pass. Returns the new state to persist, or **null** if the pass was
     * already used this week (nothing changes; the block stands). One per week, by design.
     */
    fun consume(
        state: EmergencyPassState,
        nowEpochMs: Long,
        utcOffsetMs: Long = 0L,
    ): EmergencyPassState? {
        val current = refreshed(state, nowEpochMs, utcOffsetMs)
        return if (current.usedAtEpochMs != null) null else current.copy(usedAtEpochMs = nowEpochMs)
    }
}
