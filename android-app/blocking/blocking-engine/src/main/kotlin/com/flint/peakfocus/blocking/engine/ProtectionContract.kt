package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp

/**
 * Shared product constants from the protection contract (ADR-012 §4–§6,
 * `docs/architecture/protection-contract.md`). Cross-platform fixture values, not UI timers.
 */
object ProtectionContract {
    /** §4: one ordinary break. */
    const val ORDINARY_BREAK_MS: Long = 300_000L

    /** §5: one accepted Open-Limit credit (elapsed access, not measured usage). */
    const val OPEN_CREDIT_MS: Long = 300_000L

    /** §4: Harder waits for the 1st, 2nd and 3rd-and-later weakening of one occurrence. */
    val HARDER_WAITS_MS: List<Long> = listOf(60_000L, 300_000L, 900_000L)

    /** §6: one pass per rolling 604,800 s after committed use. */
    const val PASS_COOLDOWN_MS: Long = 604_800_000L

    /** §6: an explicitly confirmed pass starts this much global personal-protection recovery. */
    const val PASS_RECOVERY_MS: Long = 3_600_000L

    /** §1: new finite sessions / Pomodoro parents. */
    const val MAX_SESSION_MS: Long = 24 * 3_600_000L

    /** §1: one recurring/daily occurrence (allows a 25-hour DST day). */
    const val MAX_OCCURRENCE_MS: Long = 26 * 3_600_000L

    /** §9: wall vs elapsed progression beyond this is a clock discontinuity. */
    const val CLOCK_DISCONTINUITY_MS: Long = 5_000L
}

/**
 * The single same-boot clock authority for ADR-012 policies (§9): elapsed time between two stamps
 * only when both share a known boot identity and time did not run backwards; null otherwise, and
 * callers apply their §9 reboot rule. (`AccessDuration` in core-model predates this and is not
 * used by the ADR-012 policies.)
 */
fun trustedElapsedMs(from: AccessClockStamp, to: AccessClockStamp): Long? {
    if (from.bootSessionId.isBlank() || from.bootSessionId != to.bootSessionId) return null
    return (to.elapsedRealtimeMs - from.elapsedRealtimeMs).takeIf { it >= 0L }
}

/**
 * True when wall-clock progression disagrees with elapsed progression by more than
 * [ProtectionContract.CLOCK_DISCONTINUITY_MS] in one boot (a clock edit), or the stamps cannot be
 * compared (reboot): wall-derived estimates are then uncertain (§9). Elapsed-time accounting is
 * unaffected either way.
 */
fun clockDiscontinuity(from: AccessClockStamp, to: AccessClockStamp): Boolean {
    val elapsed = trustedElapsedMs(from, to) ?: return true
    val wall = to.wallEpochMs - from.wallEpochMs
    return kotlin.math.abs(wall - elapsed) > ProtectionContract.CLOCK_DISCONTINUITY_MS
}

/**
 * A materialized interval on the anchored timeline (§9): [remainingAtAnchorMs] as of [anchor].
 * In one boot the remaining time is monotonic (wall-clock edits cannot end it early or extend it);
 * after a reboot it is `min(saved remaining, max(0, wallEnd - nowWall))` — the contract's bounded
 * fallback, which can only shorten, never extend.
 */
data class AnchoredInterval(val anchor: AccessClockStamp, val remainingAtAnchorMs: Long, val wallEndEpochMs: Long) {
    fun remainingMs(now: AccessClockStamp): Long {
        val elapsed = trustedElapsedMs(anchor, now)
        return if (elapsed != null) {
            (remainingAtAnchorMs - elapsed).coerceAtLeast(0L)
        } else {
            minOf(remainingAtAnchorMs, (wallEndEpochMs - now.wallEpochMs).coerceAtLeast(0L))
        }
    }
}

/** §1: a new finite session / Pomodoro parent must be positive and at most 24 h. */
fun isValidNewSessionDuration(durationMs: Long): Boolean =
    durationMs in 1..ProtectionContract.MAX_SESSION_MS

/** §1: one recurring/daily occurrence must be positive and at most 26 elapsed hours. */
fun isValidOccurrenceDuration(durationMs: Long): Boolean =
    durationMs in 1..ProtectionContract.MAX_OCCURRENCE_MS
