package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AppGroup
import kotlinx.coroutines.flow.Flow

/**
 * Observable persistence for [AppGroup]s — named reusable target selections the rule editor
 * applies in one tap. Obtain via [FlintPreferences.groups] or inject an [InMemoryGroupsStore]
 * in tests/previews.
 *
 * Async, UI/config-side only (see [BlockRulesStore] for the hot-path note); enforcement never
 * reads groups — applying one copies its targets into the rule.
 */
interface GroupsStore {

    /** All saved groups, re-emitted on every change. */
    val groups: Flow<List<AppGroup>>

    /** Insert [group], or replace the existing group with the same id. */
    suspend fun upsert(group: AppGroup)

    /** Remove the group with [groupId] (no-op if absent). */
    suspend fun delete(groupId: String)
}
