package com.flint.peakfocus.core.model

/**
 * Temporary in-memory bridge for the currently-active rule set, shared by both detection paths
 * (AccessibilityService and UsageStats foreground service) so neither couples to the other.
 *
 * The app updates [rules] when rules/sessions change. This will be replaced by a repository-backed
 * (DataStore/Room) source with a schedule resolver — for now it keeps the engine fed.
 */
object ActiveRulesHolder {
    @Volatile
    var rules: List<BlockRule> = emptyList()
}
