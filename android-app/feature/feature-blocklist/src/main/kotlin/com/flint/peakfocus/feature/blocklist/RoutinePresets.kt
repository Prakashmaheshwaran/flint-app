package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel

/**
 * The free preset routine library — the Opal-style template list the feature inventory tracks
 * under §1.4/§1.10 ("Laser Focus", "Rise and Shine" 6–9 AM, Reading Time, Gym Time, Weekend
 * Limit). In Opal these ship inside a paid tier; in Flint the whole list is free.
 *
 * A preset prefills the *shape* of a routine — name, days, time window, break level — and
 * nothing else. Apps and sites are deliberately left empty: no catalog can know which apps
 * derail this user, and prefilled guesses would ship rules that block things the user never
 * chose. The editor's own [validate] gate (NO_TARGETS) walks the user to the picker before
 * anything can be saved, so a preset is never more than a prefilled draft.
 *
 * Break levels are curated per routine but never [BreakLevel.HARDCORE]: a one-tap template
 * must not arm a cannot-stop-early block the user didn't consciously choose — the same
 * lockout-foot-gun posture as [validate]'s NO_ALLOWED_APPS rule. Raising it is one tap in
 * the editor's break-level row.
 */
internal data class RoutinePreset(
    val id: String,
    val name: String,
    /** One user-facing line under the name on the overview card. */
    val tagline: String,
    /** ISO days per core-model's contract (1 = Monday … 7 = Sunday); empty = every day. */
    val days: Set<Int>,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val breakLevel: BreakLevel,
)

internal object RoutinePresets {

    /** The catalog, in presentation order for the overview list. */
    val ALL: List<RoutinePreset> = listOf(
        RoutinePreset(
            id = "laser-focus",
            name = "Laser Focus",
            tagline = "Deep-work mornings on weekdays.",
            days = IsoDays.WEEKDAYS,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 12 * 60,
            breakLevel = BreakLevel.HARDER,
        ),
        RoutinePreset(
            id = "rise-and-shine",
            name = "Rise and Shine",
            // The inventory documents Opal's 6–9 AM window; kept verbatim.
            tagline = "Own the morning before the feed does.",
            days = emptySet(),
            startMinuteOfDay = 6 * 60,
            endMinuteOfDay = 9 * 60,
            breakLevel = BreakLevel.EASY,
        ),
        RoutinePreset(
            id = "reading-time",
            name = "Reading Time",
            tagline = "An evening hour of pages, not pixels.",
            days = emptySet(),
            startMinuteOfDay = 20 * 60,
            endMinuteOfDay = 21 * 60,
            breakLevel = BreakLevel.EASY,
        ),
        RoutinePreset(
            id = "gym-time",
            name = "Gym Time",
            tagline = "Lift weights, not your phone — Mon, Wed, Fri.",
            days = setOf(1, 3, 5),
            startMinuteOfDay = 17 * 60 + 30,
            endMinuteOfDay = 19 * 60,
            breakLevel = BreakLevel.HARDER,
        ),
        RoutinePreset(
            id = "weekend-limit",
            name = "Weekend Limit",
            tagline = "Keep weekend daytime off the timeline.",
            days = IsoDays.WEEKEND,
            startMinuteOfDay = 10 * 60,
            endMinuteOfDay = 18 * 60,
            breakLevel = BreakLevel.EASY,
        ),
    )

    /** The preset with [id], or null — callers fall back to a blank draft rather than crash. */
    fun byId(id: String): RoutinePreset? = ALL.firstOrNull { it.id == id }
}

/**
 * A new-rule draft prefilled from [preset]: always scheduled (that is what a routine is),
 * never an existing rule ([RuleDraft.id] = null), targets empty by design — see
 * [RoutinePresets]. The preset's curated break level wins over the user's default-for-new-rules
 * because the level is part of the template's meaning ("Laser Focus" *is* the harder one);
 * it stays fully editable before saving.
 */
internal fun presetDraft(preset: RoutinePreset): RuleDraft = RuleDraft(
    name = preset.name,
    scheduled = true,
    days = preset.days,
    startMinuteOfDay = preset.startMinuteOfDay,
    endMinuteOfDay = preset.endMinuteOfDay,
    breakLevel = preset.breakLevel,
)
