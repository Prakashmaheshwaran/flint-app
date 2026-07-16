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
     * @param nowEpochMs wall-clock instant for one-shot session expiry ([BlockRule.expiresAtEpochMs]);
     *   negative = "no clock supplied" → expiry not checked (same sentinel style as the others).
     */
    fun decide(
        foregroundPackage: String,
        foregroundUrl: String?,
        activeRules: List<BlockRule>,
        selfPackage: String,
        nowMinutesOfDay: Int = -1,
        weekday: Int = -1,
        nowEpochMs: Long = -1L,
    ): Verdict {
        if (foregroundPackage.isBlank()) return Verdict.Allow
        if (foregroundPackage == selfPackage) return Verdict.Allow
        if (foregroundPackage in SYSTEM_ALLOWLIST) return Verdict.Allow

        // Canonicalize once: this runs on every foreground change, against every domain of
        // every active rule, and the address bar does not change between those comparisons.
        val foregroundHost = foregroundUrl?.let(HostCanonicalizer::canonicalize)

        // Strictest match wins, not first match: overlapping rules are normal (the legacy
        // quick blocklist projects the same targets a Block Now session freezes), and taking
        // the first would let an EASY rule shadow a HARDCORE session — its free break would
        // then stand down *all* enforcement for the exemption window. Ties break toward a
        // rule with a session expiry so the block screen counts down to the real end.
        var strictest: BlockRule? = null
        var strictestReason = ""
        for (rule in activeRules) {
            if (!rule.enabled) continue
            if (rule.isExpired(nowEpochMs)) continue // one-shot session whose timer ran out
            if (!scheduleActive(rule.schedule, nowMinutesOfDay, weekday)) continue
            val targets = rule.targets

            val reason = when {
                targets.allowListMode ->
                    if (targets.apps.none { it.packageName == foregroundPackage }) {
                        "allow-list mode: $foregroundPackage is not allowed"
                    } else {
                        null
                    }
                targets.apps.any { it.packageName == foregroundPackage } ->
                    "blocked app: $foregroundPackage"
                foregroundHost != null && targets.domains.any { hostMatches(foregroundHost, it.domain) } ->
                    "blocked website: $foregroundUrl"
                else -> null
            } ?: continue

            if (stricterThan(rule, strictest)) {
                strictest = rule
                strictestReason = reason
            }
        }
        return strictest?.let { Verdict.Block(it.id, strictestReason) } ?: Verdict.Allow
    }

    /** Rule ordering for overlapping blocks: higher tier wins; at equal tier, a timed session
     *  (defined expiry) beats an open-ended rule so countdowns render the real end. */
    private fun stricterThan(candidate: BlockRule, incumbent: BlockRule?): Boolean {
        incumbent ?: return true
        if (candidate.breakLevel != incumbent.breakLevel) {
            return candidate.breakLevel.ordinal > incumbent.breakLevel.ordinal
        }
        return candidate.expiresAtEpochMs != null && incumbent.expiresAtEpochMs == null
    }

    /** True if [url]'s host equals [domain] or is a subdomain of it. */
    internal fun domainMatches(url: String, domain: String): Boolean {
        val host = extractHost(url) ?: return false
        return hostMatches(host, domain)
    }

    /**
     * Equality-or-subdomain, with [host] already canonical.
     *
     * [domain] is canonicalized on each call rather than at save time because rules reach us
     * from DataStore and from older builds that never ran the website field's `normalizeDomain`
     * — a rule stored as `WWW.Reddit.com` still has to shield Reddit.
     */
    private fun hostMatches(host: String, domain: String): Boolean {
        val d = HostCanonicalizer.canonicalize(domain) ?: return false
        return host == d || host.endsWith(".$d")
    }

    /** Host of a browser address bar, in the same shape rule domains are stored in. */
    internal fun extractHost(url: String): String? = HostCanonicalizer.canonicalize(url)

    /**
     * Whether a rule's schedule is active now. A null schedule = always active. A negative
     * [nowMinutesOfDay] means "no time supplied" → treat as active (the always-on blocklist case).
     * Handles overnight windows (start > end, e.g. 22:00–06:00): the pre-midnight head belongs
     * to the current day, but the post-midnight tail is the *previous* day's window spilling
     * over — so its day-of-week gate tests the previous ISO day ("Mon 22:00–06:00, Mondays"
     * blocks Tue 00:30, not Mon 00:30). [weekday] uses ISO numbering (1=Mon…7=Sun) — the same
     * contract as `Schedule.daysOfWeek` in core-model; callers holding a `Calendar.DAY_OF_WEEK`
     * (1=Sun…7=Sat) must convert via [IsoWeekday.fromCalendar]. Empty daysOfWeek = every day.
     */
    internal fun scheduleActive(schedule: Schedule?, nowMinutesOfDay: Int, weekday: Int): Boolean {
        schedule ?: return true
        if (nowMinutesOfDay < 0) return true
        val start = schedule.startMinuteOfDay
        val end = schedule.endMinuteOfDay
        if (start <= end) {
            return dayAllowed(schedule, weekday) && nowMinutesOfDay in start until end
        }
        return when { // overnight
            nowMinutesOfDay >= start -> dayAllowed(schedule, weekday) // head: today's window
            nowMinutesOfDay < end -> dayAllowed(schedule, previousWeekday(weekday)) // tail: yesterday's
            else -> false
        }
    }

    /** Day-of-week gate: empty [Schedule.daysOfWeek] = every day; [weekday] <= 0 = unknown → don't gate. */
    private fun dayAllowed(schedule: Schedule, weekday: Int): Boolean =
        schedule.daysOfWeek.isEmpty() || weekday <= 0 || weekday in schedule.daysOfWeek

    /** Previous ISO day, preserving the "unknown" sentinel (<= 0) unchanged. */
    private fun previousWeekday(weekday: Int): Int =
        if (weekday <= 0) weekday else DayMath.previousIsoDay(weekday)

    private companion object {
        /** Never shield core system surfaces (mirrors iOS's silently-ignored system apps). */
        val SYSTEM_ALLOWLIST = SYSTEM_SURFACE_PACKAGES
    }
}
