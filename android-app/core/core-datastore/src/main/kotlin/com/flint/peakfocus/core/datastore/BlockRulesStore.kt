package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BlockRule
import kotlinx.coroutines.flow.Flow

/**
 * Observable persistence for [BlockRule]s — blocklists, allow-list mode, schedules, and each
 * rule's break level, all in one typed rule. This is the surface feature-blocklist (and any
 * other UI module) writes through; obtain one via [FlintPreferences.blockRules] or inject an
 * [InMemoryBlockRulesStore] in tests/previews.
 *
 * DataStore is async — this store is for UI/config. The AccessibilityService's synchronous
 * hot path keeps reading core-data (`BlocklistStore` → `ActiveRulesHolder`); a later
 * integration task bridges the two. Do not call these from `onAccessibilityEvent`.
 */
interface BlockRulesStore {

    /** All configured rules, re-emitted on every change. */
    val rules: Flow<List<BlockRule>>

    /** Insert [rule], or replace the existing rule with the same id. */
    suspend fun upsert(rule: BlockRule)

    /** Remove the rule with [ruleId] (no-op if absent). */
    suspend fun delete(ruleId: String)

    /** Flip a single rule on/off without touching the rest of it. */
    suspend fun setEnabled(ruleId: String, enabled: Boolean)

    /** Replace the whole rule set (bulk import/migration from the legacy BlocklistStore). */
    suspend fun replaceAll(rules: List<BlockRule>)
}
