package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import com.flint.peakfocus.feature.blockscreen.BlockScreenState

/**
 * Why enforcement fired, resolved to exactly what the block UI and the break/pass accounting
 * need: the display [reason], the protection tier ([breakLevel]) and, when the end of the
 * block is knowable, the absolute [endsAtEpochMs] the countdown ticks toward.
 *
 * This file is the pure host-side glue between the engine's decisions and
 * `feature-blockscreen`'s [BlockScreenState] — plain Kotlin, no Android imports, unit-tested
 * on the JVM (`BlockCauseTest`). Both detection paths (AccessibilityService events and the
 * UsageStats poll) build their causes through the same factories so the block screen renders
 * identically regardless of which detector fired.
 */
data class BlockCause(
    val reason: BlockScreenReason,
    val breakLevel: BreakLevel,
    /** Absolute end of the block window, or null when open-ended (manual session). */
    val endsAtEpochMs: Long?,
) {
    /**
     * Open Limits count a granted break as spending one more open (see `OpenLimitPolicy`'s
     * "use anyway" note) — time/rule blocks don't touch the open counter.
     */
    val spendsOpenOnBreak: Boolean get() = reason == BlockScreenReason.OPEN_LIMIT
}

/**
 * Cause for a rule verdict ([com.flint.peakfocus.core.model.Verdict.Block]). [rule] is the
 * looked-up rule for the verdict's `ruleId`; a null (rule vanished between decide and lookup)
 * degrades to an EASY manual-session block rather than dropping enforcement.
 *
 * Reason mapping: a scheduled rule → [BlockScreenReason.SCHEDULE]; a manual (schedule-less)
 * rule at [BreakLevel.HARDCORE] is a Deep Focus session → [BlockScreenReason.DEEP_FOCUS];
 * any other manual rule → [BlockScreenReason.MANUAL_SESSION].
 */
fun ruleBlockCause(rule: BlockRule?, nowEpochMs: Long, utcOffsetMs: Long): BlockCause {
    val level = rule?.breakLevel ?: BreakLevel.EASY
    val schedule = rule?.schedule
    val reason = when {
        schedule != null -> BlockScreenReason.SCHEDULE
        level == BreakLevel.HARDCORE -> BlockScreenReason.DEEP_FOCUS
        else -> BlockScreenReason.MANUAL_SESSION
    }
    val endsAt = schedule?.let { nowEpochMs + millisUntilScheduleEnd(it, nowEpochMs, utcOffsetMs) }
    return BlockCause(reason = reason, breakLevel = level, endsAtEpochMs = endsAt)
}

/** Cause for an exhausted Open Limit: blocks until the next local midnight, tier from the limit. */
fun openLimitBlockCause(limit: OpenLimit, nowEpochMs: Long, utcOffsetMs: Long): BlockCause =
    BlockCause(
        reason = BlockScreenReason.OPEN_LIMIT,
        breakLevel = limit.breakLevel,
        endsAtEpochMs = nowEpochMs + millisUntilNextLocalMidnight(nowEpochMs, utcOffsetMs),
    )

/**
 * Cause for a spent daily Time Limit: blocks until the next local midnight. The legacy
 * `TimeLimit`/`LimitStore` carry no per-limit tier, so the store-wide default break level
 * (FocusStateStore.defaultBreakLevel) applies.
 */
fun timeLimitBlockCause(defaultBreakLevel: BreakLevel, nowEpochMs: Long, utcOffsetMs: Long): BlockCause =
    BlockCause(
        reason = BlockScreenReason.TIME_LIMIT,
        breakLevel = defaultBreakLevel,
        endsAtEpochMs = nowEpochMs + millisUntilNextLocalMidnight(nowEpochMs, utcOffsetMs),
    )

/**
 * One render frame of the block screen. The host re-invokes this every second with a fresh
 * [nowEpochMs] — `BlockScreen` itself is stateless and never ticks (its documented contract).
 *
 * [breakSession] feeds the HARDER cooldown: a pending, not-yet-elapsed friction request maps
 * to [BlockScreenState.breakWaitRemainingMillis] (the screen swaps hold-to-request for a wait
 * notice); once elapsed — or at EASY/HARDCORE — it maps to null.
 */
fun blockScreenState(
    packageName: String,
    appLabel: String?,
    cause: BlockCause,
    breakSession: BreakSessionState,
    nowEpochMs: Long,
): BlockScreenState {
    val pending = breakSession.pending
    val breakWait =
        if (cause.breakLevel == BreakLevel.HARDER && pending != null && !pending.isElapsed(nowEpochMs)) {
            pending.effectiveAtEpochMs - nowEpochMs
        } else {
            null
        }
    return BlockScreenState(
        packageName = packageName,
        appLabel = appLabel,
        reason = cause.reason,
        breakLevel = cause.breakLevel,
        remainingMillis = cause.endsAtEpochMs?.let { (it - nowEpochMs).coerceAtLeast(0L) },
        breakWaitRemainingMillis = breakWait,
        // The full friction length drives the screen's determinate wait ring; a degenerate
        // request (effective ≤ requested) sends null and the screen stays text-only.
        breakWaitTotalMillis = if (breakWait != null && pending != null) {
            (pending.effectiveAtEpochMs - pending.requestedAtEpochMs).takeIf { it > 0 }
        } else {
            null
        },
    )
}

internal const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/** Floored modulo (Math.floorMod(long, long) is API 24+; Flint's minSdk is 23). */
private fun floorMod(value: Long, modulus: Long): Long = ((value % modulus) + modulus) % modulus

/**
 * Millis from [nowEpochMs] to the next local midnight, using a fixed [utcOffsetMs]
 * (`TimeZone.getDefault().getOffset(now)` on Android — same convention as the engine's
 * DayMath; a DST jump inside the window shifts the boundary by the DST delta, accepted).
 * Returns a value in (0, [DAY_MS]].
 */
fun millisUntilNextLocalMidnight(nowEpochMs: Long, utcOffsetMs: Long): Long =
    DAY_MS - floorMod(nowEpochMs + utcOffsetMs, DAY_MS)

/**
 * Millis from [nowEpochMs] until [schedule]'s next `endMinuteOfDay` in local wall time —
 * i.e. when the currently-active window ends. Handles overnight windows (22:00–06:00 at
 * 23:00 → seven hours). Only meaningful while the schedule is actually active.
 */
fun millisUntilScheduleEnd(schedule: Schedule, nowEpochMs: Long, utcOffsetMs: Long): Long {
    val msIntoDay = floorMod(nowEpochMs + utcOffsetMs, DAY_MS)
    val endMs = schedule.endMinuteOfDay * 60_000L
    val delta = endMs - msIntoDay
    return if (delta > 0) delta else delta + DAY_MS
}
