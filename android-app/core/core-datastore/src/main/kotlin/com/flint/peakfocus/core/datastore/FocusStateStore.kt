package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState
import kotlinx.coroutines.flow.Flow

/**
 * Observable persistence for the focus-discipline state machines: the default break level,
 * the weekly Emergency Pass, the HARDER-tier break session (pending friction request), and
 * today's per-app open counts. Obtain via [FlintPreferences.focusState] or inject an
 * [InMemoryFocusStateStore] in tests/previews.
 *
 * This store persists state only — every transition (spend a pass, arm a friction delay,
 * roll a day/week over) is computed by the pure policies in blocking-engine
 * (`BreakPolicy`, `EmergencyPassPolicy`, `OpenLimitPolicy`); callers persist what those
 * return. Keeping decisions out of the persistence layer is what keeps them testable and
 * identical across both detection paths.
 */
interface FocusStateStore {

    /** Break level preselected for new rules/limits; defaults to [BreakLevel.EASY]. */
    val defaultBreakLevel: Flow<BreakLevel>

    /** Weekly Emergency Pass state; defaults to an uninitialized (available) state. */
    val emergencyPass: Flow<EmergencyPassState>

    /** HARDER-tier break bookkeeping (breaks taken + pending friction request). */
    val breakSession: Flow<BreakSessionState>

    /** Today's per-app open counts for Open Limits. */
    val openCounts: Flow<OpenCountState>

    suspend fun setDefaultBreakLevel(level: BreakLevel)

    suspend fun setEmergencyPass(state: EmergencyPassState)

    suspend fun setBreakSession(state: BreakSessionState)

    suspend fun setOpenCounts(state: OpenCountState)
}
