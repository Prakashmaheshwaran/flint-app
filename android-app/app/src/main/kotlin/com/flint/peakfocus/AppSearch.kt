package com.flint.peakfocus

/** Case-insensitive Home-app filter over the visible label and package name. */
internal fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return apps
    return apps.filter {
        it.label.contains(normalized, ignoreCase = true) ||
            it.packageName.contains(normalized, ignoreCase = true)
    }
}
