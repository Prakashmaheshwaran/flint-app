package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision

/**
 * Pure decision logic for **Open Limits** — "N opens per day" per app (Opal spec 2.2; iOS-only
 * and partially paywalled there, free on Android here — a deliberate parity win).
 *
 * The engine stays pure: detectors feed it foreground-package transitions and the persisted
 * [OpenCountState]; it answers (a) does this transition count as an "open", and (b) is the
 * app over today's quota. Counts roll over at local midnight (callers derive `epochDay` via
 * [epochDay] with the device's UTC offset) and persist via core-datastore's `FocusStateStore`.
 *
 * Spec nuance, honestly interpreted: Opal counts *intentional* opens ("accidental taps
 * don't"). Mechanically we approximate that in [countsAsOpen]: transitions bounced through
 * system surfaces (notification shade, settings) or through Flint's own shield don't count.
 * Past the quota, [decide] blocks; whether the block screen offers "use anyway" (spending an
 * extra recorded open via [recordOpen]) is up to the limit's `breakLevel` — EASY/HARDER may,
 * HARDCORE must not (Emergency Pass excepted). That wiring is the service/UI's job.
 */
class OpenLimitPolicy {

    /** Local epoch-day for [nowEpochMs]; pass `TimeZone.getDefault().getOffset(now)` on Android. */
    fun epochDay(nowEpochMs: Long, utcOffsetMs: Long = 0L): Long =
        DayMath.epochDay(nowEpochMs, utcOffsetMs)

    /**
     * Whether a foreground change to [foregroundPackage] counts as a new "open" of it.
     * Not an open: staying in the same app, any system surface, Flint itself, or returning
     * from Flint / a system surface (shade dips and our own shield are not re-opens).
     * A null [previousPackage] (first event after service start) does count.
     */
    fun countsAsOpen(
        previousPackage: String?,
        foregroundPackage: String,
        selfPackage: String,
    ): Boolean {
        if (foregroundPackage.isBlank()) return false
        if (foregroundPackage == selfPackage) return false
        if (foregroundPackage in SYSTEM_SURFACE_PACKAGES) return false
        if (foregroundPackage == previousPackage) return false
        if (previousPackage == selfPackage) return false
        if (previousPackage != null && previousPackage in SYSTEM_SURFACE_PACKAGES) return false
        return true
    }

    /**
     * Roll [state] to [epochDay]: same day → unchanged; new (or uninitialized) day → fresh,
     * empty counts. Persist the result whenever it differs.
     */
    fun rolledOver(state: OpenCountState, epochDay: Long): OpenCountState =
        if (state.epochDay == epochDay) state else OpenCountState(epochDay = epochDay)

    /** Record one open of [packageName] on [epochDay] (rolling the day over first). */
    fun recordOpen(state: OpenCountState, packageName: String, epochDay: Long): OpenCountState {
        val today = rolledOver(state, epochDay)
        val next = today.opensFor(packageName) + 1
        return today.copy(opensByPackage = today.opensByPackage + (packageName to next))
    }

    /** Opens of [packageName] used so far on [epochDay] (0 after a day rollover). */
    fun opensUsed(state: OpenCountState, packageName: String, epochDay: Long): Int =
        rolledOver(state, epochDay).opensFor(packageName)

    /**
     * Is [foregroundPackage] within its Open Limit on [epochDay]? No limit configured →
     * [OpenLimitDecision.Allowed] with null remaining. At/over quota →
     * [OpenLimitDecision.Blocked] carrying the limit (and its breakLevel) for the UI.
     */
    fun decide(
        foregroundPackage: String,
        state: OpenCountState,
        limits: List<OpenLimit>,
        epochDay: Long,
    ): OpenLimitDecision {
        val limit = limits.firstOrNull { it.packageName == foregroundPackage }
            ?: return OpenLimitDecision.Allowed(remainingOpens = null)
        val used = opensUsed(state, foregroundPackage, epochDay)
        return if (used >= limit.dailyOpens) {
            OpenLimitDecision.Blocked(
                limit = limit,
                reason = "open limit reached: $used/${limit.dailyOpens} opens of $foregroundPackage today",
            )
        } else {
            OpenLimitDecision.Allowed(remainingOpens = limit.dailyOpens - used)
        }
    }
}
