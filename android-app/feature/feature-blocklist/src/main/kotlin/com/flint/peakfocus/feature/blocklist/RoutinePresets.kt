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
        name = "Work hours",
        schedule = Schedule(
            daysOfWeek = IsoDays.WEEKDAYS,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
        ),
        breakLevel = BreakLevel.HARDER,
        description = "Deep work on weekdays, 9 to 5. Breaks take friction.",
    ),
    RoutinePreset(
        name = "Evenings offline",
        schedule = Schedule(startMinuteOfDay = 20 * 60, endMinuteOfDay = 23 * 60),
        breakLevel = BreakLevel.EASY,
        description = "Wind down every evening, 8 to 11. Easy to break when life happens.",
    ),
    RoutinePreset(
        name = "Social detox",
        // No schedule = the model's "always on while enabled" — cleaner than a 00:00–23:59
        // window, which would leave a dead final minute every day.
        schedule = null,
        breakLevel = BreakLevel.HARDCORE,
        description = "Around the clock, hardcore: no breaks, no early stop — " +
            "only the weekly Emergency Pass.",
    ),
    RoutinePreset(
        name = "Weekend mornings",
        schedule = Schedule(
            daysOfWeek = IsoDays.WEEKEND,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 12 * 60,
        ),
        breakLevel = BreakLevel.EASY,
        description = "Slow Saturday and Sunday mornings, 8 to noon.",
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
