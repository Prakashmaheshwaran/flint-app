package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
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

    /** Same records, but storage read failures must propagate instead of becoming empty state. */
    val rulesOrError: Flow<List<BlockRule>> get() = rules

    /** Insert [rule], or replace the existing rule with the same id. */
    suspend fun upsert(rule: BlockRule)

    /**
     * Atomically insert a finite session only if no enabled, unexpired session exists.
     * Returns false on an active-session or id conflict; unrelated rules are preserved.
     * [rule] must be an enabled session with a future deadline and no recurring schedule.
     */
    suspend fun startSessionIfIdle(rule: BlockRule, nowEpochMs: Long): Boolean

    /** Delete only this active EASY session, checking its current tier in the same transaction. */
    suspend fun cancelEasySession(ruleId: String, nowEpochMs: Long): Boolean

    /**
     * End exactly the native Timer shown in a completed confirmation, at any break level.
     * Equality is checked inside the delete transaction, so a changed/replaced Timer needs
     * a new confirmation. Ordinary rules and legacy sessions do not use this operation.
     */
    suspend fun endTimerSession(expected: BlockRule, nowEpochMs: Long): Boolean

    /** Remove the rule with [ruleId] (no-op if absent). */
    suspend fun delete(ruleId: String)

    /** Flip a single rule on/off without touching the rest of it. */
    suspend fun setEnabled(ruleId: String, enabled: Boolean)

    /**
     * Flip several rules in ONE transaction — rule pairs that must move together (the two
     * sleep rules) would otherwise desync if the process dies between separate writes.
     * Missing ids are no-ops, same as [setEnabled].
     */
    suspend fun setEnabled(ruleIds: Set<String>, enabled: Boolean)

    /** Replace the whole rule set (bulk import/migration from the legacy BlocklistStore). */
    suspend fun replaceAll(rules: List<BlockRule>)
}

internal fun requireFiniteSession(rule: BlockRule, nowEpochMs: Long) {
    require(rule.id.startsWith(BlockRule.SESSION_ID_PREFIX)) { "A session id is required" }
    require(rule.enabled && rule.schedule == null) { "Sessions must be enabled and non-recurring" }
    require(rule.expiresAtEpochMs?.let { it > nowEpochMs } == true) { "A future deadline is required" }
}

internal fun List<BlockRule>.canStartSession(id: String, nowEpochMs: Long): Boolean = none {
    it.id == id || (it.id.startsWith(BlockRule.SESSION_ID_PREFIX) && it.enabled && !it.isExpired(nowEpochMs))
}

internal fun List<BlockRule>.canCancelEasySession(id: String, nowEpochMs: Long): Boolean =
    singleOrNull { it.id == id }?.let {
    it.id.startsWith(BlockRule.SESSION_ID_PREFIX) && it.enabled &&
        !it.isExpired(nowEpochMs) && it.breakLevel == BreakLevel.EASY
} == true

/** Stable namespace written only by the dedicated Timer creator. */
const val NATIVE_TIMER_ID_PREFIX = "session-timer-"

internal fun List<BlockRule>.canEndTimerSession(expected: BlockRule, nowEpochMs: Long): Boolean =
    nowEpochMs >= 0 && expected.id.startsWith(NATIVE_TIMER_ID_PREFIX) &&
        expected.id.length > NATIVE_TIMER_ID_PREFIX.length &&
        expected.enabled && expected.schedule == null && !expected.isExpired(nowEpochMs) &&
        singleOrNull { it.id == expected.id } == expected
