package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.RuleRuntime

/** Called inside the editor transaction: a rule and its anchor must describe the same revision. */
internal fun editorRulesWithRuntime(rules: List<BlockRule>, state: EditorCommitmentState, now: AccessClockStamp): List<BlockRule> =
    rules.map { rule ->
        val key = ruleCauseKey(rule.id)
        val cause = requireNotNull(state.causes[key]) { "Missing materialized occurrence for $key" }
        val boundary = state.queued.firstOrNull { !it.needsReview && key in it.change.keys }?.boundaries?.get(key)
        val interval = boundary ?: cause.interval
        rule.copy(runtime = RuleRuntime(
            occurrenceId = cause.occurrenceId,
            anchor = interval?.anchor ?: now,
            remainingAtAnchorMs = interval?.remainingAtAnchorMs,
            wallEndEpochMs = interval?.wallEndEpochMs,
            zoneId = cause.zoneId,
            timeline = state.timeline ?: now,
            stopAtOccurrenceEnd = boundary != null,
        ))
    }
