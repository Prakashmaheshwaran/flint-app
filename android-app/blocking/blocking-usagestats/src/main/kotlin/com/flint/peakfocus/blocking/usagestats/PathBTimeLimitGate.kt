package com.flint.peakfocus.blocking.usagestats

import com.flint.peakfocus.blocking.engine.clockDiscontinuity
import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.blocking.overlay.TrustedTimeLimitBudget
import com.flint.peakfocus.core.model.AccessClockStamp

/** Query throttle only. The saved period returned by the shared store owns quota/reset. */
internal class PathBTimeLimitGate(
    private val limitMinutesFor: (String) -> Int?,
    private val readBudget: suspend (String, Int) -> TrustedTimeLimitBudget,
    private val requeryIntervalMs: Long = DEFAULT_REQUERY_INTERVAL_MS,
) {
    private var cachedPackage: String? = null
    private var cachedAt: AccessClockStamp? = null
    private var lastObserved: AccessClockStamp? = null
    private var cachedBudget: TrustedTimeLimitBudget? = null
    private var cachedLimitMinutes: Int? = null
    private var validForMs = 0L

    init { require(requeryIntervalMs > 0) }

    suspend fun budget(packageName: String, now: AccessClockStamp): TrustedTimeLimitBudget? {
        val minutes = limitMinutesFor(packageName)
        val age = cachedAt?.let { trustedElapsedMs(it, now) }
        val stale = packageName != cachedPackage || minutes != cachedLimitMinutes || age == null ||
            age >= validForMs || lastObserved?.let { clockDiscontinuity(it, now) } == true
        if (stale) {
            cachedBudget = minutes?.let { readBudget(packageName, it) }
            // An Allow cannot outlive either measured foreground quota or the trusted reset.
            // Unknown/failed measurements retry at the normal bounded query cadence.
            validForMs = listOfNotNull(requeryIntervalMs,
                cachedBudget?.remainingMs?.takeIf { it > 0L },
                cachedBudget?.resetRemainingMs(now)?.takeIf { it > 0L }).min()
            cachedLimitMinutes = minutes
            cachedPackage = packageName
            cachedAt = now
        }
        lastObserved = now
        return cachedBudget
    }

    fun invalidate() { cachedPackage = null }

    companion object {
        const val DEFAULT_REQUERY_INTERVAL_MS = 15_000L
    }
}
