package com.flint.peakfocus.blocking.engine

/**
 * The single, unit-tested bridge from a platform day-of-week to the engine's ISO numbering.
 *
 * `Schedule.daysOfWeek` (core-model) and the `weekday` parameter of [BlockDecisionEngine.decide]
 * are ISO-numbered: 1 = Monday … 7 = Sunday. Android hands callers `java.util.Calendar`'s
 * `DAY_OF_WEEK`, which is 1 = Sunday … 7 = Saturday. Every enforcement path that reads the live
 * clock — Path A's `FlintAccessibilityService` and Path B's `UsageStatsForegroundService` — must
 * convert before asking the engine, or a "Weekdays" (Mon–Fri) rule blocks Sunday and skips Friday.
 *
 * This conversion previously lived inline in both services as `((day + 5) % 7) + 1`: duplicated,
 * private, and untested. It is enforcement-critical schedule math, so it belongs in one pure,
 * tested place — the same module that owns the ISO contract it feeds — where the two detection
 * paths can't drift.
 *
 * Deliberately takes a plain Int (the *value* of `Calendar.DAY_OF_WEEK`), never a `Calendar`, so
 * blocking-engine stays free of `java.util`/`java.time` — the same purity discipline as [DayMath].
 * Note the arithmetic coincides with [DayMath.previousIsoDay] but means something different
 * (previous-ISO-day, not platform→ISO); they are kept separate on purpose.
 */
object IsoWeekday {

    /**
     * ISO value meaning "weekday unknown". [BlockDecisionEngine] treats `weekday <= 0` as
     * "don't gate on the day" — it blocks on the time window alone, the fail-safe direction for
     * a focus blocker when the calendar can't be read.
     */
    const val UNKNOWN: Int = -1

    /**
     * Convert a `java.util.Calendar.DAY_OF_WEEK` value (1 = Sunday … 7 = Saturday) to the engine's
     * ISO numbering (1 = Monday … 7 = Sunday).
     *
     * Any value outside 1..7 — which the platform contract never produces, but which we refuse to
     * silently mis-map — returns [UNKNOWN], so a garbage reading disables the day gate (fail toward
     * blocking) instead of pinning the rule to the wrong day.
     */
    fun fromCalendar(calendarDayOfWeek: Int): Int =
        if (calendarDayOfWeek in 1..7) ((calendarDayOfWeek + 5) % 7) + 1 else UNKNOWN
}
