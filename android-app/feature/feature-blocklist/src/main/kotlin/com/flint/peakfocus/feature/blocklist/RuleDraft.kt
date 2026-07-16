package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.Schedule

/**
 * Day-of-week constants for [Schedule.daysOfWeek], following core-model's documented contract:
 * ISO numbering, 1 = Monday … 7 = Sunday; the empty set means every day.
 *
 * Both enforcement services (Path A's AccessibilityService, Path B's UsageStats poll) read the
 * live weekday from `java.util.Calendar` (1 = Sunday … 7 = Saturday) and convert it to this ISO
 * numbering through the engine's shared `IsoWeekday.fromCalendar` before calling
 * `BlockDecisionEngine.decide`, so a rule's `daysOfWeek` gate here matches what actually fires.
 * That conversion is defined and unit-tested once in blocking-engine — not per caller — because
 * the model KDoc, not any one detector, is the shared contract.
 */
internal object IsoDays {

    val ALL: List<Int> = (1..7).toList()

    val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

    val WEEKEND: Set<Int> = setOf(6, 7)

    /** Short English label for an ISO day, e.g. 1 → "Mon". Unknown values map to "?". */
    fun shortLabel(isoDay: Int): String = when (isoDay) {
        1 -> "Mon"
        2 -> "Tue"
        3 -> "Wed"
        4 -> "Thu"
        5 -> "Fri"
        6 -> "Sat"
        7 -> "Sun"
        else -> "?"
    }
}

/** Valid minutes-of-day range for schedule endpoints (00:00 .. 23:59). */
internal val MINUTE_OF_DAY_RANGE: IntRange = 0..1439

private const val DEFAULT_START_MINUTE = 9 * 60 // 09:00
private const val DEFAULT_END_MINUTE = 17 * 60 // 17:00

/**
 * The editable form state behind the rule editor — a mutable-friendly mirror of [BlockRule].
 * Pure Kotlin so mapping and validation are JVM-testable without Compose.
 *
 * [id] is null for a not-yet-saved rule. [domains] is a list (not a set) to keep the on-screen
 * order stable while editing; [RuleDraft.toRule] converts back to the model's set. When
 * [scheduled] is false the window fields are kept (so toggling the switch doesn't lose input)
 * but ignored by [toRule] — a null-schedule rule is the "always on while enabled" kind.
 */
internal data class RuleDraft(
    val id: String? = null,
    val name: String = "",
    val apps: Set<AppRef> = emptySet(),
    val domains: List<String> = emptyList(),
    val allowListMode: Boolean = false,
    val scheduled: Boolean = false,
    val days: Set<Int> = emptySet(),
    val startMinuteOfDay: Int = DEFAULT_START_MINUTE,
    val endMinuteOfDay: Int = DEFAULT_END_MINUTE,
    val breakLevel: BreakLevel = BreakLevel.EASY,
    val enabled: Boolean = true,
)

/** A fresh draft for a new rule, pre-selecting the user's default break level. */
internal fun newRuleDraft(defaultBreakLevel: BreakLevel): RuleDraft =
    RuleDraft(breakLevel = defaultBreakLevel)

/** Loads an existing rule into an editable draft. */
internal fun ruleDraftOf(rule: BlockRule): RuleDraft = RuleDraft(
    id = rule.id,
    name = rule.name,
    apps = rule.targets.apps,
    domains = rule.targets.domains.map { it.domain }.sorted(),
    allowListMode = rule.targets.allowListMode,
    scheduled = rule.schedule != null,
    days = rule.schedule?.daysOfWeek?.filter { it in 1..7 }?.toSet() ?: emptySet(),
    startMinuteOfDay = rule.schedule?.startMinuteOfDay ?: DEFAULT_START_MINUTE,
    endMinuteOfDay = rule.schedule?.endMinuteOfDay ?: DEFAULT_END_MINUTE,
    breakLevel = rule.breakLevel,
    enabled = rule.enabled,
)

/** Why a draft can't be saved yet. [message] is the user-facing line the editor shows. */
internal enum class RuleDraftError(val message: String) {
    NAME_REQUIRED("Give this rule a name."),
    NO_TARGETS("Pick at least one app or add one website."),
    NO_ALLOWED_APPS("Allow-list mode blocks everything else — choose at least one app to allow."),
    INVALID_TIME("Start and end must be times between 00:00 and 23:59."),
    ZERO_LENGTH_WINDOW("Start and end can't be the same time."),
}

/**
 * All problems with [draft], empty when it is safe to save.
 *
 * Semantics mirror the engine, not just taste: an allow-list rule with zero allowed apps would
 * block every app on the phone (lockout foot-gun), and a window whose start equals its end never
 * matches in `BlockDecisionEngine.scheduleActive` (`start until end` is empty). Overnight windows
 * (end before start, e.g. 22:00–06:00) are valid — the engine wraps them across midnight.
 */
internal fun validate(draft: RuleDraft): List<RuleDraftError> {
    val errors = mutableListOf<RuleDraftError>()
    if (draft.name.isBlank()) errors += RuleDraftError.NAME_REQUIRED
    if (draft.allowListMode) {
        if (draft.apps.isEmpty()) errors += RuleDraftError.NO_ALLOWED_APPS
    } else {
        if (draft.apps.isEmpty() && draft.domains.isEmpty()) errors += RuleDraftError.NO_TARGETS
    }
    if (draft.scheduled) {
        if (draft.startMinuteOfDay !in MINUTE_OF_DAY_RANGE || draft.endMinuteOfDay !in MINUTE_OF_DAY_RANGE) {
            errors += RuleDraftError.INVALID_TIME
        } else if (draft.startMinuteOfDay == draft.endMinuteOfDay) {
            errors += RuleDraftError.ZERO_LENGTH_WINDOW
        }
    }
    return errors
}

/**
 * Converts a (validated) draft back to the persistence model. [id] is the rule's identity:
 * pass the draft's own id when editing, or a freshly generated one for a new rule.
 * Domains are kept even in allow-list mode: the engine ignores them there, but dropping them
 * would silently destroy user input when the mode is toggled back off.
 */
internal fun RuleDraft.toRule(id: String): BlockRule = BlockRule(
    id = id,
    name = name.trim(),
    targets = BlockTargets(
        apps = apps,
        domains = domains.map { DomainRef(it) }.toSet(),
        allowListMode = allowListMode,
    ),
    schedule = if (scheduled) {
        Schedule(
            daysOfWeek = days.filter { it in 1..7 }.toSet(),
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
        )
    } else {
        null
    },
    breakLevel = breakLevel,
    enabled = enabled,
)
