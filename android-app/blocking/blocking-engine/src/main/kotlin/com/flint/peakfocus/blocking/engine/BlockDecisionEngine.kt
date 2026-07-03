package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.Verdict

/**
 * The pure decision core of Flint's blocker.
 *
 * Given the current foreground context (package + optional browser URL) and the set of rules
 * that are currently active, it returns a [Verdict]. It has **no Android dependencies**: the
 * AccessibilityService path and the UsageStatsManager poll path both call [decide], so the
 * verdict is identical regardless of which detector fired. This is what keeps enforcement
 * consistent as the app degrades from path A to path B.
 */
class BlockDecisionEngine {

    /**
     * @param foregroundPackage package currently in the foreground.
     * @param foregroundUrl browser URL if known (AccessibilityService node read), else null.
     * @param activeRules rules whose schedule is currently active (or manual Block Now sessions).
     * @param selfPackage Flint's own package — we never block ourselves (escape hatch lives in app).
     */
    fun decide(
        foregroundPackage: String,
        foregroundUrl: String?,
        activeRules: List<BlockRule>,
        selfPackage: String,
        nowMinutesOfDay: Int = -1,
        weekday: Int = -1,
    ): Verdict {
        if (foregroundPackage.isBlank()) return Verdict.Allow
        if (foregroundPackage == selfPackage) return Verdict.Allow
        if (foregroundPackage in SYSTEM_ALLOWLIST) return Verdict.Allow

        for (rule in activeRules) {
            if (!rule.enabled) continue
            if (!scheduleActive(rule.schedule, nowMinutesOfDay, weekday)) continue
            val targets = rule.targets

            if (targets.allowListMode) {
                val allowed = targets.apps.any { it.packageName == foregroundPackage }
                if (!allowed) {
                    return Verdict.Block(rule.id, "allow-list mode: $foregroundPackage is not allowed")
                }
                continue
            }

            if (targets.apps.any { it.packageName == foregroundPackage }) {
                return Verdict.Block(rule.id, "blocked app: $foregroundPackage")
            }
            if (foregroundUrl != null && targets.domains.any { domainMatches(foregroundUrl, it.domain) }) {
                return Verdict.Block(rule.id, "blocked website: $foregroundUrl")
            }
        }
        return Verdict.Allow
    }

    /** True if [url]'s host equals [domain] or is a subdomain of it. */
    internal fun domainMatches(url: String, domain: String): Boolean {
        val host = extractHost(url) ?: return false
        val d = domain.trim().removePrefix("www.").lowercase()
        if (d.isEmpty()) return false
        return host == d || host.endsWith(".$d")
    }

    /** Best-effort host extraction without java.net.URI (keeps this module pure/portable). */
    internal fun extractHost(url: String): String? {
        val afterScheme = if ("://" in url) url.substringAfter("://") else url
        val host = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")
            .lowercase()
            .trim()
        return host.ifBlank { null }
    }

    /**
     * Whether a rule's schedule is active now. A null schedule = always active. A negative
     * [nowMinutesOfDay] means "no time supplied" → treat as active (the always-on blocklist case).
     * Handles overnight windows (start > end, e.g. 22:00–06:00). [weekday] uses ISO numbering
     * (1=Mon…7=Sun) — the same contract as `Schedule.daysOfWeek` in core-model; callers holding
     * a `Calendar.DAY_OF_WEEK` (1=Sun…7=Sat) must convert via `((day + 5) % 7) + 1`. Empty
     * daysOfWeek = every day.
     */
    internal fun scheduleActive(schedule: Schedule?, nowMinutesOfDay: Int, weekday: Int): Boolean {
        schedule ?: return true
        if (nowMinutesOfDay < 0) return true
        if (schedule.daysOfWeek.isNotEmpty() && weekday > 0 && weekday !in schedule.daysOfWeek) {
            return false
        }
        val start = schedule.startMinuteOfDay
        val end = schedule.endMinuteOfDay
        return if (start <= end) {
            nowMinutesOfDay in start until end
        } else {
            nowMinutesOfDay >= start || nowMinutesOfDay < end // overnight
        }
    }

    private companion object {
        /** Never shield core system surfaces (mirrors iOS's silently-ignored system apps). */
        val SYSTEM_ALLOWLIST = SYSTEM_SURFACE_PACKAGES
    }
}
