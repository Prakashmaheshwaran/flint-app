package com.flint.peakfocus.feature.settings.applock

/**
 * Persisted throttle state.
 *
 * [lockoutStartElapsedMs] is an elapsed-realtime sample on the boot named by [lockoutBootId].
 * A null boot id means "unknown boot" and is treated as a different boot (full restart).
 */
data class ThrottleState(
    val failures: Int = 0,
    val lockoutStartElapsedMs: Long = 0,
    val lockoutMillis: Long = 0,
    val lockoutBootId: String? = null,
)

/**
 * Failed-attempt counters and the derived lockout window.
 *
 * Time basis is always [android.os.SystemClock.elapsedRealtime] plus a boot identity. Wall-clock
 * time is never used for throttling: changing the device clock must not shorten a lockout.
 *
 * A lockout is restarted in full when the boot identity changes (reboot, elapsed clock reset).
 * Restarting can only lengthen protection; it never grants earlier access.
 *
 * Scope: this resists process kills, reboots, wall-clock changes and accidental storage
 * corruption (which reads as a maximal lockout). It is not an integrity boundary. Anyone who can
 * write Flint's private storage (a rooted device) can reset these values, and could equally copy
 * the salted hash and guess a 4–6 digit PIN offline. The PIN keeps casual hands out of private
 * app content; it is not device encryption.
 */
object AppPinThrottle {

    /** Attempts that may fail before a lockout starts. */
    const val FREE_ATTEMPTS = 4

    /** Longest lockout the schedule can produce; also the fail-closed value for corrupt state. */
    const val MAX_LOCKOUT_MILLIS = 3_600_000L

    /** Lockout applied after [consecutiveFailures] consecutive wrong PINs. */
    fun lockoutMillisAfter(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= FREE_ATTEMPTS -> 0L
        consecutiveFailures == 5 -> 30_000L
        consecutiveFailures == 6 -> 60_000L
        consecutiveFailures == 7 -> 120_000L
        consecutiveFailures == 8 -> 300_000L
        consecutiveFailures == 9 -> 900_000L
        consecutiveFailures == 10 -> 1_800_000L
        else -> MAX_LOCKOUT_MILLIS
    }

    /**
     * Milliseconds still to wait for [state], measured on [nowElapsedMs] of boot [bootId].
     *
     * Same boot: the remainder of the window, clamped to `[0, lockoutMillis]`; a clock that appears
     * to move backwards (now < start) yields the full window. Different boot: the controller
     * persists a fresh start on this boot, so this returns the full window as well.
     */
    fun remainingMillis(state: ThrottleState, nowElapsedMs: Long, bootId: String): Long {
        if (state.lockoutMillis <= 0) return 0L
        if (state.lockoutBootId != bootId) return state.lockoutMillis
        val elapsed = nowElapsedMs - state.lockoutStartElapsedMs
        if (elapsed < 0) return state.lockoutMillis
        return (state.lockoutMillis - elapsed).coerceIn(0L, state.lockoutMillis)
    }
}
