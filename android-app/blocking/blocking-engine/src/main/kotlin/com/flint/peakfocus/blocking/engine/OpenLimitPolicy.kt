package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision

/**
 * The result of folding one foreground transition through [OpenLimitPolicy.onForegroundTransition]:
 * the [decision] the detector enforces, the [counts] to persist whenever they differ from the
 * state that went in, and [granted] — the package whose current open is already paid for, which
 * the caller holds and hands back on the next transition.
 */
data class OpenTransition(
    val decision: OpenLimitDecision,
    val counts: OpenCountState,
    val granted: String?,
)

/**
 * Pure decision logic for **Open Limits** — "N opens per day" per app (Opal spec 2.2; iOS-only
 * and partially paywalled there, free on Android here — a deliberate parity win).
 *
 * The engine stays pure: detectors feed it foreground-package transitions and the persisted
 * [OpenCountState] through [onForegroundTransition], which answers (a) does this transition
 * count as an "open", (b) is the app over today's quota, and (c) what to persist. Counts roll
 * over at local midnight (callers derive `epochDay` via [epochDay] with the device's UTC
 * offset) and persist via core-datastore's `FocusStateStore`. Rollover is **forward-only**
 * ([rolledOver]) — a clock set backward never re-grants consumed opens; detected forward
 * jumps/timezone hops are handled by [ClockChangeGuard].
 *
 * Spec nuance, honestly interpreted: Opal counts *intentional* opens ("accidental taps
 * don't"). Mechanically we approximate that in [countsAsOpen]: transitions bounced through
 * system surfaces (notification shade, settings) or through Flint's own shield don't count.
 * Past the quota, [onForegroundTransition] blocks; whether the block screen offers "use
 * anyway" (spending an extra recorded open via [recordOpen]) is up to the limit's `breakLevel`
 * — EASY/HARDER may, HARDCORE must not (Emergency Pass excepted). That wiring is the
 * service/UI's job.
 */
class OpenLimitPolicy {

    /** Local epoch-day for [nowEpochMs]; pass `TimeZone.getDefault().getOffset(now)` on Android. */
    fun epochDay(nowEpochMs: Long, utcOffsetMs: Long = 0L): Long =
        DayMath.epochDay(nowEpochMs, utcOffsetMs)

    /**
     * Fold one foreground transition into the Open-Limit state machine — the single entry point
     * detectors use, so the event-driven and poll-driven paths count and block identically.
     *
     * The quota is checked **once per open**, at the moment of entry, and an open that was
     * allowed stays allowed for as long as the app holds the foreground. [decide] alone cannot
     * express that: recording the Nth open pushes `used` to the quota, so the very next
     * evaluation of the same app — Path B's one-second poll tick, Path A's next window event —
     * would see `used >= dailyOpens` and shield an open the user had just been granted, leaving
     * an "N opens per day" limit worth N-1 usable opens (and a limit of 1 worth none). [granted]
     * is what closes that: it names the app whose current open is already paid for.
     *
     * A granted open survives dips into system surfaces and Flint's own windows (the shade, our
     * own block screen) and ends when a *different real app* takes the foreground — the same
     * "a bounce is not a re-open" rule [countsAsOpen] applies to counting, applied to grants. A
     * [OpenLimitDecision.Blocked] always clears the grant, so a shielded app the user bounces
     * back into is re-decided (and stays blocked), and a "use anyway" break re-blocks once its
     * exemption window ends.
     *
     * [granted] is in-memory, never persisted: a dead process forgets it and re-decides the next
     * transition, so enforcement fails strict — exactly like `BlockExemptions`.
     */
    fun onForegroundTransition(
        previousPackage: String?,
        foregroundPackage: String,
        selfPackage: String,
        state: OpenCountState,
        granted: String?,
        limits: List<OpenLimit>,
        epochDay: Long,
    ): OpenTransition {
        val rolled = rolledOver(state, epochDay)

        // A real entry into the app: decide against the counts *before* this open, then pay for
        // it. Checked ahead of [granted] on purpose — leaving an app and coming back is a new
        // open even while a stale grant still names it.
        if (countsAsOpen(previousPackage, foregroundPackage, selfPackage)) {
            return when (val decision = decide(foregroundPackage, rolled, limits, epochDay)) {
                is OpenLimitDecision.Blocked -> OpenTransition(decision, rolled, granted = null)
                is OpenLimitDecision.Allowed -> OpenTransition(
                    // One fewer remains once this open is on the books.
                    decision = OpenLimitDecision.Allowed(decision.remainingOpens?.minus(1)),
                    counts = recordOpen(rolled, foregroundPackage, epochDay),
                    granted = foregroundPackage,
                )
            }
        }

        // Not an entry, and we are still inside the open we granted: never re-block it.
        if (foregroundPackage == granted) {
            return OpenTransition(remainingFor(foregroundPackage, rolled, limits, epochDay), rolled, granted)
        }

        // Any other surface: re-decide. A grant survives a dip through a system surface or one
        // of Flint's own windows; another real app in front means the granted open is over.
        val kept = if (isCountableApp(foregroundPackage, selfPackage)) null else granted
        val decision = decide(foregroundPackage, rolled, limits, epochDay)
        return OpenTransition(
            decision = decision,
            counts = rolled,
            granted = if (decision is OpenLimitDecision.Blocked) null else kept,
        )
    }

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
        if (!isCountableApp(foregroundPackage, selfPackage)) return false
        if (foregroundPackage == previousPackage) return false
        if (previousPackage == selfPackage) return false
        if (previousPackage != null && previousPackage in SYSTEM_SURFACE_PACKAGES) return false
        return true
    }

    /**
     * True for a foreground package that is an app in its own right — not Flint, not one of the
     * shared [SYSTEM_SURFACE_PACKAGES], not the blank package an empty event carries. Both "is
     * this a new open" and "did the granted open end" hang off the same answer.
     */
    private fun isCountableApp(packageName: String, selfPackage: String): Boolean =
        packageName.isNotBlank() &&
            packageName != selfPackage &&
            packageName !in SYSTEM_SURFACE_PACKAGES

    /**
     * Roll [state] to [epochDay] — **forward only** (anti-bypass, fail closed): same day →
     * unchanged; a later (or first-ever) day → fresh, empty counts; an *earlier* day — the
     * clock was set back past local midnight, or a westward timezone hop re-crossed it —
     * keeps the consumed counts, so already-spent opens are never re-granted. The kept state
     * stays keyed to the later day: when the clock returns to real time that day is simply
     * "today" again, consumption intact. (Accepted cost: a genuine westward traveler waits
     * up to the offset difference longer for the next reset — the conservative side of the
     * trade.) Persist the result whenever it differs.
     */
    fun rolledOver(state: OpenCountState, epochDay: Long): OpenCountState = when {
        state.epochDay == OpenCountState.DAY_UNINITIALIZED -> OpenCountState(epochDay = epochDay)
        epochDay <= state.epochDay -> state
        else -> OpenCountState(epochDay = epochDay)
    }

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
     * Is [foregroundPackage] at or over its Open Limit on [epochDay]? No limit configured →
     * [OpenLimitDecision.Allowed] with null remaining. At/over quota →
     * [OpenLimitDecision.Blocked] carrying the limit (and its breakLevel) for the UI.
     *
     * This is the raw quota predicate over a set of counts — "would a *new* open of this app be
     * over budget", which is not the same question as "should this foreground transition be
     * blocked". Detectors want the latter: go through [onForegroundTransition], which decides
     * once per open and never re-blocks the open it just granted.
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

    /**
     * [OpenLimitDecision.Allowed] for an app whose current open is already granted: the remainder
     * after it, floored at zero (a "use anyway" break can push `used` past the quota).
     */
    private fun remainingFor(
        packageName: String,
        state: OpenCountState,
        limits: List<OpenLimit>,
        epochDay: Long,
    ): OpenLimitDecision.Allowed {
        val limit = limits.firstOrNull { it.packageName == packageName }
            ?: return OpenLimitDecision.Allowed(remainingOpens = null)
        val used = opensUsed(state, packageName, epochDay)
        return OpenLimitDecision.Allowed(remainingOpens = (limit.dailyOpens - used).coerceAtLeast(0))
    }
}
