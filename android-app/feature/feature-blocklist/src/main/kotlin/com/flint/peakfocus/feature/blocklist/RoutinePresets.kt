package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.Schedule

/**
 * Opal-style routine templates (free in Flint): one tap pre-fills a rule draft with a name,
 * schedule, and difficulty. Targets are always the user's to pick — a preset that guessed
 * package names would be dead weight on most phones. Pure data; [draftFrom] is the only glue.
 */
internal data class RoutinePreset(
    val name: String,
    val schedule: Schedule?,
    val breakLevel: BreakLevel,
    val allowListMode: Boolean = false,
    val description: String,
)

internal val ROUTINE_PRESETS: List<RoutinePreset> = listOf(
    RoutinePreset(
        name = "Laser Focus",
        schedule = Schedule(
            daysOfWeek = IsoDays.WEEKDAYS,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 12 * 60,
        ),
        breakLevel = BreakLevel.HARDER,
        description = "Deep-work mornings on weekdays. Breaks take friction.",
    ),
    RoutinePreset(
        name = "Rise and Shine",
        schedule = Schedule(startMinuteOfDay = 6 * 60, endMinuteOfDay = 9 * 60),
        breakLevel = BreakLevel.EASY,
        description = "Own the morning before the feed does, 6 to 9.",
    ),
    RoutinePreset(
        name = "Reading Time",
        schedule = Schedule(startMinuteOfDay = 20 * 60, endMinuteOfDay = 21 * 60),
        breakLevel = BreakLevel.EASY,
        description = "An evening hour of pages, not pixels.",
    ),
    RoutinePreset(
        name = "Gym Time",
        schedule = Schedule(
            daysOfWeek = setOf(1, 3, 5),
            startMinuteOfDay = 17 * 60 + 30,
            endMinuteOfDay = 19 * 60,
        ),
        breakLevel = BreakLevel.HARDER,
        description = "Lift weights, not your phone — Monday, Wednesday, Friday.",
    ),
    RoutinePreset(
        name = "Weekend Limit",
        schedule = Schedule(
            daysOfWeek = IsoDays.WEEKEND,
            startMinuteOfDay = 10 * 60,
            endMinuteOfDay = 18 * 60,
        ),
        breakLevel = BreakLevel.EASY,
        description = "Keep weekend daytime off the timeline.",
    ),
)

/** A draft pre-filled from [preset]; targets stay empty for the user to pick. */
internal fun draftFrom(preset: RoutinePreset): RuleDraft {
    val base = RuleDraft(
        name = preset.name,
        allowListMode = preset.allowListMode,
        breakLevel = preset.breakLevel,
    )
    val schedule = preset.schedule ?: return base
    return base.copy(
        scheduled = true,
        days = schedule.daysOfWeek,
        startMinuteOfDay = schedule.startMinuteOfDay,
        endMinuteOfDay = schedule.endMinuteOfDay,
    )
}
