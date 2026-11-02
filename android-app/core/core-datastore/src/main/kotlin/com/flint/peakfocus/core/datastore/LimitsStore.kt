package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.flow.Flow

/**
 * Observable persistence for per-app daily limits: [TimeLimit]s (minutes of foreground time)
 * and [OpenLimit]s (number of opens) — one limit of each kind per package. Obtain via
 * [FlintPreferences.limits] or inject an [InMemoryLimitsStore] in tests/previews.
 *
 * Async, UI/config-side only (see [BlockRulesStore] for the hot-path note). Both limits are
 * free in Flint, including the hardcore no-reset tier Opal paywalls.
 */
interface LimitsStore {

    val timeLimitsState: Flow<StorageState<List<TimeLimit>>> get() = timeLimits.withReadiness()
    val openLimitsState: Flow<StorageState<List<OpenLimit>>> get() = openLimits.withReadiness()

    /** Both sides of the editor save or clear succeed together. */
    suspend fun setLimits(packageName: String, time: TimeLimit?, open: OpenLimit?) {
        error("Atomic limit updates are not supported by this store")
    }

    /** All Time Limits, re-emitted on every change. */
    val timeLimits: Flow<List<TimeLimit>>

    /** All Open Limits, re-emitted on every change. */
    val openLimits: Flow<List<OpenLimit>>

    /** Set (or replace) the Time Limit for [limit]'s package. */
    suspend fun setTimeLimit(limit: TimeLimit)

    /** Remove the Time Limit for [packageName] (no-op if absent). */
    suspend fun clearTimeLimit(packageName: String)

    /** Set (or replace) the Open Limit for [limit]'s package. */
    suspend fun setOpenLimit(limit: OpenLimit)

    /** Remove the Open Limit for [packageName] (no-op if absent). */
    suspend fun clearOpenLimit(packageName: String)
}
