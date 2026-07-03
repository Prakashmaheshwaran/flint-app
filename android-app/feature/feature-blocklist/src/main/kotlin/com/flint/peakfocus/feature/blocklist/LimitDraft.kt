package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit

/**
 * Editable form state for one app's daily limits — the Time Limit (minutes of foreground use)
 * and the Open Limit (launches per day). Pure Kotlin mirror of what LimitsStore persists,
 * so parsing/validation is JVM-testable.
 *
 * Note the model asymmetry, kept honestly: [OpenLimit] carries its own [BreakLevel];
 * [TimeLimit] does not (core-model doesn't define one yet), so this form offers the level
 * only on the open-limit side rather than pretending it protects the time limit too.
 */
internal data class LimitDraft(
    val packageName: String,
    val timeEnabled: Boolean = false,
    val minutesText: String = "",
    val openEnabled: Boolean = false,
    val opensText: String = "",
    val openBreakLevel: BreakLevel = BreakLevel.EASY,
)

/** Sane bounds: a time budget fits inside one day; opens stay a human-scale count. */
internal val DAILY_MINUTES_RANGE: IntRange = 1..1440
internal val DAILY_OPENS_RANGE: IntRange = 1..500

/** A draft prefilled from what's already persisted for [packageName] (if anything). */
internal fun limitDraftOf(
    packageName: String,
    existingTime: TimeLimit?,
    existingOpen: OpenLimit?,
    defaultBreakLevel: BreakLevel,
): LimitDraft = LimitDraft(
    packageName = packageName,
    timeEnabled = existingTime != null,
    minutesText = existingTime?.dailyMinutes?.toString() ?: "",
    openEnabled = existingOpen != null,
    opensText = existingOpen?.dailyOpens?.toString() ?: "",
    openBreakLevel = existingOpen?.breakLevel ?: defaultBreakLevel,
)

/** Why the limits form can't be saved yet. [message] is the user-facing line. */
internal enum class LimitFormError(val message: String) {
    NOTHING_ENABLED("Turn on a time limit or an open limit — or use Remove to clear both."),
    BAD_MINUTES("Daily minutes must be a whole number from 1 to 1440."),
    BAD_OPENS("Daily opens must be a whole number from 1 to 500."),
}

/** The outcome of validating a [LimitDraft]: the typed limits to persist, or what to fix. */
internal sealed interface LimitFormResult {
    /** Save these; a null side means "clear that limit for this package". */
    data class Valid(val timeLimit: TimeLimit?, val openLimit: OpenLimit?) : LimitFormResult

    data class Invalid(val errors: List<LimitFormError>) : LimitFormResult
}

/**
 * Parses and validates the draft. Disabled sides resolve to null (= clear on save) without
 * looking at their text, so stale digits in a switched-off field never block saving. A draft
 * with both sides disabled is rejected: "save nothing" is the Remove action, not a save.
 */
internal fun LimitDraft.resolve(): LimitFormResult {
    val errors = mutableListOf<LimitFormError>()
    if (!timeEnabled && !openEnabled) errors += LimitFormError.NOTHING_ENABLED

    var timeLimit: TimeLimit? = null
    if (timeEnabled) {
        val minutes = minutesText.trim().toIntOrNull()
        if (minutes == null || minutes !in DAILY_MINUTES_RANGE) {
            errors += LimitFormError.BAD_MINUTES
        } else {
            timeLimit = TimeLimit(packageName = packageName, dailyMinutes = minutes)
        }
    }

    var openLimit: OpenLimit? = null
    if (openEnabled) {
        val opens = opensText.trim().toIntOrNull()
        if (opens == null || opens !in DAILY_OPENS_RANGE) {
            errors += LimitFormError.BAD_OPENS
        } else {
            openLimit = OpenLimit(
                packageName = packageName,
                dailyOpens = opens,
                breakLevel = openBreakLevel,
            )
        }
    }

    return if (errors.isEmpty()) {
        LimitFormResult.Valid(timeLimit = timeLimit, openLimit = openLimit)
    } else {
        LimitFormResult.Invalid(errors)
    }
}
