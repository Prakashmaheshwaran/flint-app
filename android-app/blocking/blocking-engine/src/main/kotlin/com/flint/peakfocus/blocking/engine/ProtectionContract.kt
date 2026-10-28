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
 * Elapsed time between two stamps when it is trustworthy (§9): same boot only, never negative.
 * Null across a reboot or with an unknown boot identity — callers apply their §9 reboot rule.
 */
internal fun trustedElapsedMs(from: AccessClockStamp, to: AccessClockStamp): Long? {
    if (from.bootSessionId.isBlank() || from.bootSessionId != to.bootSessionId) return null
    return (to.elapsedRealtimeMs - from.elapsedRealtimeMs).takeIf { it >= 0L }
}
