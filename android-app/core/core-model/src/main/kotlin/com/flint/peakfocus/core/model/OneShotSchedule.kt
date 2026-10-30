package com.flint.peakfocus.core.model

import java.time.ZoneId
import java.time.ZoneOffset

/** A rule-backed absolute pair, resolved once at save; neither a weekly rule nor a Timer. */
data class OneShotSchedule(
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val zoneId: String,
    val startOffsetSeconds: Int,
    val endOffsetSeconds: Int,
) {
    init {
        require(startsAtEpochMs >= 0 && endsAtEpochMs > startsAtEpochMs)
        require(zoneId in ZoneId.getAvailableZoneIds()) { "A saved IANA zone is required" }
        ZoneOffset.ofTotalSeconds(startOffsetSeconds)
        ZoneOffset.ofTotalSeconds(endOffsetSeconds)
    }

    /** Scoped by the owning rule cause; rename/tier/target edits cannot allocate a new pair. */
    val occurrenceId: String get() = "once:$startsAtEpochMs:$endsAtEpochMs"
    val durationMs: Long get() = endsAtEpochMs - startsAtEpochMs
}
