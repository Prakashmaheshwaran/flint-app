package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.Schedule

/**
 * Sleep Mode + Morning Assist (spec 3.3, free in Flint — blocking only, no audio content by
 * design), materialized as **allow-list rules on the schedules engine** — the same approach
 * the iOS build ships, so no new enforcement path exists to drift. Two fixed-id rules:
 *
 *  - [SLEEP_WINDOW_RULE_ID] — bedtime → wake on the chosen nights. Overnight windows
 *    (bed > wake) ride the engine's start-day semantics: the post-midnight tail belongs to
 *    the *night's* day, exactly like any overnight schedule.
 *  - [SLEEP_MORNING_RULE_ID] — wake → morning-end on the mornings *after* each night
 *    (ISO day + 1, Sunday wrapping to Monday), present only while Morning Assist is on.
 *
 * The config is *parsed back* from those rules ([sleepConfigFrom]) rather than stored twice —
 * the rules are the single persisted truth. On/off is [SleepConfig.enabled] alone; the assist
 * level maps to the break tier (Wind Down = EASY, Full Assist = HARDER — deliberately never
 * HARDCORE: sleep is a routine, not a vow, and the block screen's break flow stays reachable).
 */
internal const val SLEEP_RULE_PREFIX = "sleep-"
internal const val SLEEP_WINDOW_RULE_ID = "sleep-window"
internal const val SLEEP_MORNING_RULE_ID = "sleep-morning"

/** How firmly the sleep window holds. On/off lives in [SleepConfig.enabled], not here. */
internal enum class SleepAssist { WIND_DOWN, FULL_ASSIST }

internal data class SleepConfig(
    val enabled: Boolean = false,
    /** ISO start-days: 1 = the night of Monday *evening*. Empty = every night. */
    val nights: Set<Int> = IsoDays.WEEKDAYS,
    val bedMinute: Int = 22 * 60 + 30,
    val wakeMinute: Int = 7 * 60,
    val assist: SleepAssist = SleepAssist.WIND_DOWN,
    /** Apps that stay reachable overnight (allow-list targets). Empty = everything blocks. */
    val allowedApps: Set<AppRef> = emptySet(),
    val morningEnabled: Boolean = false,
    val morningEndMinute: Int = 8 * 60,
) {
    companion object {
        val DEFAULT = SleepConfig()
    }
}

private fun SleepAssist.toBreakLevel(): BreakLevel = when (this) {
    SleepAssist.WIND_DOWN -> BreakLevel.EASY
    SleepAssist.FULL_ASSIST -> BreakLevel.HARDER
}

/** The morning after each night: ISO day + 1, Sunday (7) wrapping to Monday (1). */
internal fun morningsAfter(nights: Set<Int>): Set<Int> = nights.map { (it % 7) + 1 }.toSet()

/** Inverse of [morningsAfter]: the night whose morning is each ISO day. */
internal fun nightsBefore(days: Set<Int>): Set<Int> = days.map { ((it + 5) % 7) + 1 }.toSet()

/**
 * Materialize onto the engine. Always returns the window rule (disabled config included —
 * the settings survive as a disabled rule, nothing is lost); the morning rule only while
 * Morning Assist is on, so [BlocklistViewModel.saveSleep] must delete it otherwise.
 */
internal fun SleepConfig.toRules(): List<BlockRule> {
    val targets = BlockTargets(apps = allowedApps, allowListMode = true)
    val tier = assist.toBreakLevel()
    // A post-midnight bedtime (bed < wake, e.g. 00:30 → 07:00) is a *same-day* window to the
    // engine, and that whole window falls on the morning AFTER the chosen night — its day
    // gate must shift forward one day, or "Friday night" would block Friday's own small
    // hours (Thursday night) and detach Morning Assist by 24h. Overnight windows (bed >
    // wake) keep the night's day per the engine's start-day spillover semantics.
    val windowDays = if (bedMinute < wakeMinute) morningsAfter(nights) else nights
    val window = BlockRule(
        id = SLEEP_WINDOW_RULE_ID,
        name = "Sleep",
        targets = targets,
        schedule = Schedule(
            daysOfWeek = windowDays,
            startMinuteOfDay = bedMinute,
            endMinuteOfDay = wakeMinute,
        ),
        breakLevel = tier,
        enabled = enabled,
    )
    if (!morningEnabled) return listOf(window)
    val morning = BlockRule(
        id = SLEEP_MORNING_RULE_ID,
        name = "Morning assist",
        targets = targets,
        schedule = Schedule(
            daysOfWeek = morningsAfter(nights),
            startMinuteOfDay = wakeMinute,
            endMinuteOfDay = morningEndMinute,
        ),
        breakLevel = tier,
        enabled = enabled,
    )
    return listOf(window, morning)
}

/**
 * Parse the config back from the materialized rules, or null when Sleep Mode has never been
 * set up (no window rule). Total inverse of [toRules]: `sleepConfigFrom(c.toRules()) == c`.
 *
 * A window rule that is not in allow-list mode is treated as *not a sleep config* (null):
 * only a corrupted store or a foreign writer can produce that shape, and parsing it anyway
 * would render a healthy-looking card over inverted enforcement (a block-list of exactly the
 * user's "allowed overnight" apps). Null makes the card honest ("not set up") and the next
 * save rebuilds clean rules.
 */
internal fun sleepConfigFrom(rules: List<BlockRule>): SleepConfig? {
    val window = rules.firstOrNull { it.id == SLEEP_WINDOW_RULE_ID } ?: return null
    if (!window.targets.allowListMode) return null
    val schedule = window.schedule ?: return null
    val morning = rules.firstOrNull { it.id == SLEEP_MORNING_RULE_ID }
    return SleepConfig(
        enabled = window.enabled,
        // Post-midnight windows were day-shifted at materialization; shift back to nights.
        nights = if (schedule.startMinuteOfDay < schedule.endMinuteOfDay) {
            nightsBefore(schedule.daysOfWeek)
        } else {
            schedule.daysOfWeek
        },
        bedMinute = schedule.startMinuteOfDay,
        wakeMinute = schedule.endMinuteOfDay,
        assist = if (window.breakLevel == BreakLevel.HARDER) {
            SleepAssist.FULL_ASSIST
        } else {
            SleepAssist.WIND_DOWN
        },
        allowedApps = window.targets.apps,
        morningEnabled = morning != null,
        morningEndMinute = morning?.schedule?.endMinuteOfDay
            ?: SleepConfig.DEFAULT.morningEndMinute,
    )
}

/** Why a sleep config can't be saved yet. [message] is the user-facing line. */
internal enum class SleepConfigError(val message: String) {
    INVALID_TIME("Bedtime, wake and morning-end must be times between 00:00 and 23:59."),
    ZERO_LENGTH_WINDOW("Bedtime and wake time can't be the same."),
    MORNING_NOT_AFTER_WAKE("Morning assist must end after the wake time."),
}

/**
 * All problems with [config], empty when safe to save. A bed == wake window never matches in
 * the engine (`start until end` is empty); the morning window must be a same-day slice after
 * wake — letting it wrap overnight would silently re-block the whole day.
 */
internal fun validate(config: SleepConfig): List<SleepConfigError> {
    val errors = mutableListOf<SleepConfigError>()
    val times = buildList {
        add(config.bedMinute)
        add(config.wakeMinute)
        if (config.morningEnabled) add(config.morningEndMinute)
    }
    if (times.any { it !in MINUTE_OF_DAY_RANGE }) errors += SleepConfigError.INVALID_TIME
    if (config.bedMinute == config.wakeMinute) errors += SleepConfigError.ZERO_LENGTH_WINDOW
    if (config.morningEnabled &&
        config.morningEndMinute in MINUTE_OF_DAY_RANGE &&
        config.morningEndMinute <= config.wakeMinute
    ) {
        errors += SleepConfigError.MORNING_NOT_AFTER_WAKE
    }
    return errors
}

/** One-line overview summary: "Weeknights · 22:30 – 07:00 · morning to 08:00". */
internal fun sleepSummary(config: SleepConfig): String {
    val nights = when {
        config.nights.isEmpty() || config.nights.size == 7 -> "Every night"
        config.nights == IsoDays.WEEKDAYS -> "Weeknights"
        config.nights == IsoDays.WEEKEND -> "Weekend nights"
        else -> config.nights.sorted().joinToString(" ") { IsoDays.shortLabel(it) }
    }
    val window = "${formatMinuteOfDay(config.bedMinute)} – ${formatMinuteOfDay(config.wakeMinute)}"
    val morning = if (config.morningEnabled) {
        " · morning to ${formatMinuteOfDay(config.morningEndMinute)}"
    } else {
        ""
    }
    return "$nights · $window$morning"
}
