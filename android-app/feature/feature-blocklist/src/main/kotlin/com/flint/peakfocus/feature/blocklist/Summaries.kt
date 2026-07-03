package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit

/**
 * Pure formatting for the overview cards: one honest line each for a rule's schedule, its
 * targets, and an app's daily limits. Kept out of the composables so the wording — which
 * encodes real engine semantics (overnight wrap, the never-active equal-endpoints window,
 * allow-list inversion) — is JVM-testable.
 */

/** "Every day", "Weekdays", "Weekends", or "Mon, Wed, Fri". Empty/full sets mean every day. */
internal fun daysSummary(days: Set<Int>): String {
    val valid = days.filter { it in 1..7 }.distinct().sorted()
    return when {
        valid.isEmpty() || valid.size == 7 -> "Every day"
        valid.toSet() == IsoDays.WEEKDAYS -> "Weekdays"
        valid.toSet() == IsoDays.WEEKEND -> "Weekends"
        else -> valid.joinToString(", ") { IsoDays.shortLabel(it) }
    }
}

/**
 * One line describing when a rule bites. Mirrors `BlockDecisionEngine.scheduleActive`:
 * null schedule = active whenever the rule is enabled; end at/before start wraps overnight;
 * equal endpoints never match (flagged rather than hidden — such a rule silently does nothing).
 */
internal fun scheduleSummary(schedule: Schedule?): String {
    schedule ?: return "Always on while enabled"
    val start = formatMinuteOfDay(schedule.startMinuteOfDay)
    val end = formatMinuteOfDay(schedule.endMinuteOfDay)
    val window = when {
        schedule.startMinuteOfDay == schedule.endMinuteOfDay -> "$start–$end (never active)"
        schedule.startMinuteOfDay > schedule.endMinuteOfDay -> "$start–$end (overnight)"
        else -> "$start–$end"
    }
    return "${daysSummary(schedule.daysOfWeek)} · $window"
}

/** One line describing what a rule targets, including the allow-list inversion. */
internal fun targetsSummary(targets: BlockTargets): String {
    val apps = targets.apps.size
    val sites = targets.domains.size
    if (targets.allowListMode) {
        return "Allow-list · everything blocked except ${countOf(apps, "app")}"
    }
    if (apps == 0 && sites == 0) return "No targets yet"
    val parts = mutableListOf<String>()
    if (apps > 0) parts += countOf(apps, "app")
    if (sites > 0) parts += countOf(sites, "site")
    return parts.joinToString(" · ")
}

private fun countOf(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

/** Product naming for the break tiers (spec 2.3) — all three free in Flint. */
internal fun breakLevelLabel(level: BreakLevel): String = when (level) {
    BreakLevel.EASY -> "Easy"
    BreakLevel.HARDER -> "Harder"
    BreakLevel.HARDCORE -> "Hardcore"
}

/** One-line explanation of what each tier means, shown under the selector. */
internal fun breakLevelDescription(level: BreakLevel): String = when (level) {
    BreakLevel.EASY -> "Take a break or stop the block whenever you like."
    BreakLevel.HARDER -> "Breaks arrive after a growing wait, and only from inside Flint."
    BreakLevel.HARDCORE -> "No breaks, no early stop — one Emergency Pass per week."
}

/** "45m", "1h", "1h 30m" — same duration style the block screen uses. */
internal fun formatDailyMinutes(minutes: Int): String {
    val total = minutes.coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

/** One line for an app's limits: "1h 30m/day · 5 opens/day (Hardcore)". */
internal fun limitSummary(timeLimit: TimeLimit?, openLimit: OpenLimit?): String {
    val parts = mutableListOf<String>()
    if (timeLimit != null) parts += "${formatDailyMinutes(timeLimit.dailyMinutes)}/day"
    if (openLimit != null) {
        val noun = if (openLimit.dailyOpens == 1) "open" else "opens"
        parts += "${openLimit.dailyOpens} $noun/day (${breakLevelLabel(openLimit.breakLevel)})"
    }
    return if (parts.isEmpty()) "No limits" else parts.joinToString(" · ")
}

/** One app's limits merged for the overview list. [label] falls back to the package name. */
internal data class AppLimitRow(
    val packageName: String,
    val label: String?,
    val timeLimit: TimeLimit?,
    val openLimit: OpenLimit?,
) {
    val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: packageName
}

/**
 * Joins the two per-package limit lists (one [TimeLimit] and/or one [OpenLimit] each) into
 * display rows, sorted by display name. [labelFor] resolves a package to its launcher label
 * and may return null (uninstalled app, list still loading) — the row then shows the package.
 */
internal fun buildLimitRows(
    timeLimits: List<TimeLimit>,
    openLimits: List<OpenLimit>,
    labelFor: (String) -> String?,
): List<AppLimitRow> {
    val timeByPackage = timeLimits.associateBy { it.packageName }
    val openByPackage = openLimits.associateBy { it.packageName }
    return (timeByPackage.keys + openByPackage.keys)
        .map { pkg ->
            AppLimitRow(
                packageName = pkg,
                label = labelFor(pkg),
                timeLimit = timeByPackage[pkg],
                openLimit = openByPackage[pkg],
            )
        }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { row: AppLimitRow -> row.displayName }
                .thenBy { it.packageName },
        )
}
