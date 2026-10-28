package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakLevel

/**
 * ADR-012 §2: the single action × tier × active/inactive authority every entry point (editor,
 * overview toggle, shield, Timer, automation) asks **at commit time** — UI-disabled controls alone
 * are insufficient. The tier is the affected cause's *current* tier, never the requested one.
 *
 * Two things are deliberately not caller labels, because a wrong label would weaken an active
 * Hardcore commitment immediately:
 *  - **activity** is derived from a [CauseActivity] built from the materialized cause (a daily
 *    Time Limit below quota, or an Open Limit between credits, is still active — §2);
 *  - **strengthen vs weaken** and **dominating replacement** are derived from before/after
 *    [ProtectionShape]s by [classifyEdit] ([decideEdit], [decideReplacement]); anything unprovable
 *    is weakening.
 *
 * OS-owned actions (uninstall Flint, disable Accessibility / revoke access / recovery Settings,
 * clock or time-zone changes) always return [Outcome.OS_OWNED]: Flint never gates them (§7, §10).
 */
class CommitmentPolicy {

    /** What the affected cause is doing now; [isActive] is derived, never passed in. */
    sealed interface CauseActivity {
        val isActive: Boolean

        /** Manual/Timer/Block Now session: active until its (finite) end once accepted. */
        data class Session(val started: Boolean, val endsAtEpochMs: Long?, val nowEpochMs: Long) : CauseActivity {
            override val isActive: Boolean get() = started && (endsAtEpochMs == null || nowEpochMs < endsAtEpochMs)
        }

        /** Recurring/one-shot/Sleep occurrence: active from its start through its resolved end. */
        data class ScheduledOccurrence(val enabled: Boolean, val inOccurrence: Boolean) : CauseActivity {
            override val isActive: Boolean get() = enabled && inOccurrence
        }

        /** Time / Open Limit: an enabled limit is an active daily commitment, exhausted or not. */
        data class DailyLimit(val enabled: Boolean) : CauseActivity {
            override val isActive: Boolean get() = enabled
        }

        data object Inactive : CauseActivity {
            override val isActive: Boolean get() = false
        }
    }

    /** Actions decided by tier alone. Edits and replacements go through [decideEdit] / [decideReplacement]. */
    enum class Action {
        START_ADDITIONAL,
        STOP_EARLY,
        ORDINARY_BREAK,
        ACCEPT_OPEN_CREDIT,
        TOGGLE_OFF,
        DELETE,
        EMERGENCY_PASS,
        UNINSTALL_FLINT,
        REVOKE_OS_PERMISSION,
        CHANGE_CLOCK_OR_ZONE,
    }

    enum class Outcome {
        /** Preview, confirm, persist, then show the actual result. */
        ALLOW,

        /** Allowed after the explicit finite-end Hardcore disclosure (§2, Start row). */
        ALLOW_AFTER_DISCLOSURE,

        /** Persisted Harder wait must mature, then an explicit confirmation ([HarderWaitPolicy]). */
        WAIT,

        /** "Protected until [end]. Save for after this occurrence" — no immediate reduction. */
        DEFER,

        /** Show cause, tier, end and pass status; no mutation. */
        DENY,

        /** Nothing running / nothing blocked. */
        NOT_APPLICABLE,

        /** OS-owned control: never gated by Flint; show truthful consequences only. */
        OS_OWNED,
    }

    fun decide(action: Action, tier: BreakLevel, activity: CauseActivity): Outcome {
        when (action) {
            Action.UNINSTALL_FLINT, Action.REVOKE_OS_PERMISSION, Action.CHANGE_CLOCK_OR_ZONE -> return Outcome.OS_OWNED
            else -> Unit
        }
        if (!activity.isActive) {
            return when (action) {
                Action.STOP_EARLY, Action.ORDINARY_BREAK, Action.ACCEPT_OPEN_CREDIT -> Outcome.NOT_APPLICABLE
                Action.START_ADDITIONAL ->
                    if (tier == BreakLevel.HARDCORE) Outcome.ALLOW_AFTER_DISCLOSURE else Outcome.ALLOW
                // Inactive templates are fully editable at every tier (PC-13); a pass is only
                // spendable while *some* protection is active (RollingEmergencyPassPolicy decides).
                else -> Outcome.ALLOW
            }
        }
        return when (action) {
            Action.START_ADDITIONAL, Action.ACCEPT_OPEN_CREDIT, Action.EMERGENCY_PASS -> Outcome.ALLOW
            Action.STOP_EARLY, Action.ORDINARY_BREAK -> byTier(tier, hardcore = Outcome.DENY)
            Action.TOGGLE_OFF, Action.DELETE -> byTier(tier, hardcore = Outcome.DEFER)
            Action.UNINSTALL_FLINT, Action.REVOKE_OS_PERMISSION, Action.CHANGE_CLOCK_OR_ZONE -> Outcome.OS_OWNED
        }
    }

    /** An edit of a cause's template/occurrence; strengthen vs weaken is proven, not labelled. */
    fun decideEdit(before: ProtectionShape, after: ProtectionShape, activity: CauseActivity): Outcome {
        if (!activity.isActive) return Outcome.ALLOW
        return when (classifyEdit(before, after)) {
            EditKind.COSMETIC, EditKind.STRENGTHEN -> Outcome.ALLOW // raising a tier still needs explicit UI confirmation
            EditKind.WEAKEN -> byTier(before.tier, hardcore = Outcome.DEFER)
        }
    }

    /**
     * Replacing a running session (§2/§3): allowed when [replacement] provably dominates [current]
     * (every target, at least the tier, at least the remaining interval); otherwise Easy allows an
     * explicit exit, Harder waits, Hardcore denies (start independently or defer instead).
     */
    fun decideReplacement(current: ProtectionShape, replacement: ProtectionShape, activity: CauseActivity): Outcome {
        if (!activity.isActive) return Outcome.NOT_APPLICABLE
        return when (classifyEdit(current, replacement)) {
            EditKind.COSMETIC, EditKind.STRENGTHEN -> Outcome.ALLOW
            EditKind.WEAKEN -> byTier(current.tier, hardcore = Outcome.DENY)
        }
    }

    private fun byTier(tier: BreakLevel, hardcore: Outcome): Outcome = when (tier) {
        BreakLevel.EASY -> Outcome.ALLOW
        BreakLevel.HARDER -> Outcome.WAIT
        BreakLevel.HARDCORE -> hardcore
    }

    /** What a rule/limit/session protects, normalized for comparison. */
    data class ProtectionShape(
        val name: String,
        val tier: BreakLevel,
        val allowListMode: Boolean,
        /** Block-list mode: blocked apps. Allow-list mode: allowed apps. */
        val apps: Set<String>,
        /** Block-list mode: blocked domains. Allow-list mode: must stay identical (§2). */
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
            // Allowed set may only shrink; allowed/ignored destinations may not change at all.
            after.apps.all { it in before.apps } && after.domains == before.domains
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

    /** [after] provably covers every minute [before] did: same zone, superset of days, same-shape superset span. */
    private fun windowCovers(after: Window?, before: Window?): Boolean {
        if (before == null || after == null) return before == after
        if (after.zoneId != before.zoneId) return false
        val beforeDays = before.days.ifEmpty { (1..7).toSet() }
        val afterDays = after.days.ifEmpty { (1..7).toSet() }
        if (!afterDays.containsAll(beforeDays)) return false
        val bOvernight = before.startMinute > before.endMinute
        val aOvernight = after.startMinute > after.endMinute
        if (bOvernight != aOvernight) return false // shape change across midnight: not provable without per-day expansion
        return after.startMinute <= before.startMinute && after.endMinute >= before.endMinute
    }
}
