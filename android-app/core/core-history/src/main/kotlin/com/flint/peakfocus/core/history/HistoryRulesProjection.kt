package com.flint.peakfocus.core.history

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.StorageState
import java.util.Locale

/** A failed strict decode is never an authoritative empty rule set. */
fun historyRulesSnapshot(state: StorageState<List<BlockRule>>): HistoryRulesSnapshot = when (state) {
    StorageState.Loading -> HistoryRulesSnapshot.Loading
    is StorageState.Unavailable -> HistoryRulesSnapshot.Unavailable(state.reason.name)
    is StorageState.Ready -> {
        val sessions = state.value.filter {
            it.id.startsWith("session-") && it.enabled && it.schedule == null
        }
        if (sessions.any { rule -> rule.runtime?.let { it.evaluation == null } == true }) HistoryRulesSnapshot.Loading
        else {
            val inactive = sessions.filter { it.commitmentActive == false }.mapTo(mutableSetOf()) { it.id }
            HistoryRulesSnapshot.Ready(sessions.filterNot { it.id in inactive }.map {
                // The display estimate combines a fresh wall/elapsed sample and can
                // jitter by a millisecond. Persist the same committed logical endpoint
                // the owner uses for its terminal, rather than rescheduling every tick.
                val plannedEnd = it.runtime?.wallEndEpochMs ?: it.expiresAtEpochMs
                HistoryRule(it.id, it.name, it.breakLevel.name.lowercase(Locale.ROOT),
                    plannedEnd, it.commitmentActive,
                    rounds = it.pomodoro?.rounds,
                    plannedRests = plannedRests(it, plannedEnd))
            }, inactive)
        }
    }
}

/**
 * F01: a planned rest is a phase of an active commitment, not an end. The engine's
 * `evaluation.active` is *enforcement* activity, so it is false during a rest; the parent is
 * still live until its chain finishes.
 */
private val BlockRule.commitmentActive: Boolean?
    get() = runtime?.evaluation?.let { it.active || it.phase != null }

/**
 * Every planned rest whose start this read has already seen. A rest that started proves the
 * rounds before it ended, so the reconciler writes those events even if it missed the boundary
 * itself. Wall instants are derived from the committed chain end, never from an observation.
 * A chain with no current phase (finished) has every rest behind it.
 */
private fun plannedRests(rule: BlockRule, plannedEnd: Long?): List<PlannedRest> {
    val plan = rule.pomodoro ?: return emptyList()
    val end = plannedEnd ?: return emptyList()
    val chainStart = end - plan.totalMs
    val observedPhaseStart = rule.runtime?.evaluation?.phase?.startMs
    return plan.phases.filter { phase ->
        !phase.isFocus && (observedPhaseStart == null || phase.startMs <= observedPhaseStart)
    }.map { phase ->
        PlannedRest(
            round = phase.round,
            grant = "${rule.id}.rest${phase.round}",
            startsAt = chainStart + phase.startMs,
            endsAt = chainStart + phase.endMs,
        )
    }
}

/** One planned rest of a Pomodoro parent, as history may record it (§7). */
data class PlannedRest(
    /** 1-based focus round this rest follows; also its `<ruleId>.rest<k>` index. */
    val round: Int,
    val grant: String,
    /** Logical rest start as a wall estimate. */
    val startsAt: Long,
    /** Logical rest end as a wall estimate. */
    val endsAt: Long,
)

/** Kind of a planned-rest pair. Ordinary breaks keep their existing `ordinary` kind. */
const val PLANNED_BREAK_KIND = "planned"
