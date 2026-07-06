package com.flint.peakfocus

/**
 * Case-insensitive filter for the Home tab's "Block these apps" list. Matches the visible
 * label or the package name (the row shows both, so both should be searchable — typing
 * "musically" finds TikTok). Blank input keeps the full list; input is trimmed so a stray
 * space never empties the screen. Pure — JVM-tested in AppSearchTest.
 */
internal fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val q = query.trim()
    if (q.isEmpty()) return apps
    return apps.filter {
        it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
    }
}
