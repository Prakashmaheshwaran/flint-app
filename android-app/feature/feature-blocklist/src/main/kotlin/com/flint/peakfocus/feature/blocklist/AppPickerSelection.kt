package com.flint.peakfocus.feature.blocklist

/** Pending package choices, independent of the search results and the caller's saved draft. */
internal data class AppPickerSelection(val packageNames: Set<String>) {
    fun withSelection(packages: Collection<String>, selected: Boolean): AppPickerSelection =
        copy(packageNames = if (selected) packageNames + packages else packageNames - packages.toSet())

    /** Resolve against the full current app list, never the filtered search results. */
    fun selectedApps(apps: List<InstalledApp>): List<InstalledApp> =
        apps.filter { it.packageName in packageNames }

    /** Unavailable apps are preserved by callers; they aren't editable in this picker. */
    fun hasChanges(initiallySelected: Set<String>, apps: List<InstalledApp>): Boolean =
        apps.any { (it.packageName in packageNames) != (it.packageName in initiallySelected) }
}
