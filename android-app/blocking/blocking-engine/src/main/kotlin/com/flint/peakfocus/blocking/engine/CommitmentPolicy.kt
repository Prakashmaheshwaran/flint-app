package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakLevel

/**
 * ADR-012 §2: the single action × tier × active/inactive authority every entry point (editor,
 * overview toggle, shield, Timer, automation) asks **at commit time** — UI-disabled controls alone
 * are insufficient. The tier is the affected cause's *current* tier, never the requested one.
 *
 * Plus [classifyEdit], the "strengthen" proof: an edit strengthens only when no previously denied
 * target or time becomes allowed and no exit becomes easier; anything unprovable is weakening.
 */
class CommitmentPolicy {

    enum class Action {
        START_ADDITIONAL,
        REPLACE_DOMINATING,
        REPLACE_WEAKER,
        STOP_EARLY,
        ORDINARY_BREAK,
        ACCEPT_OPEN_CREDIT,
        EDIT_STRENGTHEN,
        EDIT_WEAKEN,
        TOGGLE_OFF,
        DELETE,
        EMERGENCY_PASS,
    }

    enum class Outcome {
        /** Preview, confirm, persist, then show the actual result. */
        ALLOW,

        /** Persisted Harder wait must mature, then an explicit confirmation ([HarderWaitPolicy]). */
        WAIT,

        /** "Protected until [end]. Save for after this occurrence" — no immediate reduction. */
        DEFER,

        /** Show cause, tier, end and pass status; no mutation. */
        DENY,

        /** Nothing running / nothing blocked. */
        NOT_APPLICABLE,
    }

    fun decide(action: Action, tier: BreakLevel, active: Boolean): Outcome {
        if (!active) {
            return when (action) {
                Action.REPLACE_DOMINATING, Action.REPLACE_WEAKER, Action.STOP_EARLY,
                Action.ORDINARY_BREAK, Action.ACCEPT_OPEN_CREDIT -> Outcome.NOT_APPLICABLE
                // Inactive templates are fully editable at every tier (PC-13); the pass is only
                // spendable when *some* personal protection is active (checked by the pass policy).
                else -> Outcome.ALLOW
            }
        }
        return when (action) {
            Action.START_ADDITIONAL, Action.REPLACE_DOMINATING, Action.EDIT_STRENGTHEN,
            Action.ACCEPT_OPEN_CREDIT, Action.EMERGENCY_PASS -> Outcome.ALLOW
            Action.REPLACE_WEAKER -> when (tier) {
                BreakLevel.EASY -> Outcome.ALLOW
                BreakLevel.HARDER -> Outcome.WAIT
                BreakLevel.HARDCORE -> Outcome.DENY
            }
            Action.STOP_EARLY, Action.ORDINARY_BREAK -> when (tier) {
                BreakLevel.EASY -> Outcome.ALLOW
                BreakLevel.HARDER -> Outcome.WAIT
                BreakLevel.HARDCORE -> Outcome.DENY // pass or natural expiry only
            }
            Action.EDIT_WEAKEN, Action.TOGGLE_OFF, Action.DELETE -> when (tier) {
                BreakLevel.EASY -> Outcome.ALLOW
                BreakLevel.HARDER -> Outcome.WAIT
                BreakLevel.HARDCORE -> Outcome.DEFER // future-only change; snapshot stays
            }
        }
    }

    /** What a rule/limit protects, normalized for comparison. Null fields are "not applicable". */
    data class ProtectionShape(
        val name: String,
        val tier: BreakLevel,
        val allowListMode: Boolean,
        /** Block-list mode: blocked apps. Allow-list mode: allowed apps. */
        val apps: Set<String>,
        val domains: Set<String>,
        val window: Window? = null,
        /** Absolute commitment end (sessions), null = none / not a session. */
        val endsAtEpochMs: Long? = null,
        /** Daily Time-Limit minutes or Open-Limit cap; higher = weaker. */
        val budget: Int? = null,
    )

    /** A recurring window: minutes of day, ISO days (empty = every day) and saved IANA zone. */
    data class Window(val startMinute: Int, val endMinute: Int, val days: Set<Int>, val zoneId: String)

    enum class EditKind { COSMETIC, STRENGTHEN, WEAKEN }

    fun classifyEdit(before: ProtectionShape, after: ProtectionShape): EditKind {
        if (before.copy(name = after.name) == after) return EditKind.COSMETIC
        if (before.allowListMode != after.allowListMode) return EditKind.WEAKEN
        if (after.tier.ordinal < before.tier.ordinal) return EditKind.WEAKEN
        val targetsOk = if (after.allowListMode) {
            after.apps.all { it in before.apps } // allowed set may only shrink
        } else {
            after.apps.containsAll(before.apps) && after.domains.containsAll(before.domains)
        }
        if (!targetsOk) return EditKind.WEAKEN
        val endOk = when {
            before.endsAtEpochMs == null -> after.endsAtEpochMs == null
            after.endsAtEpochMs == null -> false // removing a finite end is not provable here
            else -> after.endsAtEpochMs >= before.endsAtEpochMs
        }
        if (!endOk) return EditKind.WEAKEN
        val budgetOk = when {
            before.budget == null -> after.budget == null
            after.budget == null -> false // removing a budget limit is weakening
            else -> after.budget <= before.budget
        }
        if (!budgetOk) return EditKind.WEAKEN
        if (!windowCovers(after.window, before.window)) return EditKind.WEAKEN
        return EditKind.STRENGTHEN
    }

    /** [after] provably covers every minute [before] did: same zone, superset of days, same-day superset span. */
    private fun windowCovers(after: Window?, before: Window?): Boolean {
        if (before == null || after == null) return before == after
        if (after.zoneId != before.zoneId) return false
        val beforeDays = before.days.ifEmpty { (1..7).toSet() }
        val afterDays = after.days.ifEmpty { (1..7).toSet() }
        if (!afterDays.containsAll(beforeDays)) return false
        val bOvernight = before.startMinute > before.endMinute
        val aOvernight = after.startMinute > after.endMinute
        return when {
            !bOvernight && !aOvernight -> after.startMinute <= before.startMinute && after.endMinute >= before.endMinute
            bOvernight && aOvernight -> after.startMinute <= before.startMinute && after.endMinute >= before.endMinute
            else -> false // shape change across midnight: not provable without per-day expansion
        }
    }
}
