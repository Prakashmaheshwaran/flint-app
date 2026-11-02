package com.flint.peakfocus.feature.blocklist

/** One app or site the user explicitly picked; its coverage is read separately, never here. */
internal data class PickedTarget(val target: AppDetailTarget, val label: String)

internal sealed interface PickedTargetsState {
    data object Loading : PickedTargetsState
    data object Unavailable : PickedTargetsState
    data class Ready(
        val targets: List<PickedTarget>,
        /** Allow-list rules name the apps they let through, not the ones they block. */
        val hasAllowListRules: Boolean,
    ) : PickedTargetsState
}

/**
 * "Apps and sites": every app and site picked in a block rule (including a running session),
 * a time limit or an Open Limit. This is an inventory of the user's own picks; whether and how
 * each one is covered comes only from the per-target reader.
 */
internal fun pickedTargets(
    rules: RulesUiState,
    limits: LimitsUiState,
    labelFor: (String) -> String?,
): PickedTargetsState {
    if (rules is RulesUiState.Unavailable || limits is LimitsUiState.Unavailable) return PickedTargetsState.Unavailable
    if (rules !is RulesUiState.Ready || limits !is LimitsUiState.Ready) return PickedTargetsState.Loading
    val apps = linkedMapOf<String, String?>()
    val sites = linkedSetOf<String>()
    rules.rules.filterNot { it.targets.allowListMode }.forEach { rule ->
        rule.targets.apps.forEach { apps.putIfAbsent(it.packageName, it.label) }
        rule.targets.domains.forEach { sites += it.domain.trim().lowercase() }
    }
    limits.rows.forEach { apps.putIfAbsent(it.packageName, it.label) }
    val appTargets = apps.map { (pkg, stored) ->
        val label = labelFor(pkg)?.takeIf { it.isNotBlank() } ?: stored?.takeIf { it.isNotBlank() } ?: pkg
        PickedTarget(AppDetailTarget.App(pkg), label)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: PickedTarget -> it.label }
        .thenBy { (it.target as AppDetailTarget.App).packageName })
    val siteTargets = sites.filter { it.isNotEmpty() }.sorted()
        .map { PickedTarget(AppDetailTarget.Site(it), it) }
    return PickedTargetsState.Ready(
        targets = appTargets + siteTargets,
        hasAllowListRules = rules.rules.any { it.targets.allowListMode },
    )
}
