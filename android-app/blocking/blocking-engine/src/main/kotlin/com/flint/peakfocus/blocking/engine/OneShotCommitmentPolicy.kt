package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OneShotSchedule

/** One-shot-specific inputs; tier outcomes remain owned by CommitmentPolicy. */
object OneShotCommitmentPolicy {
    private val policy = CommitmentPolicy()

    fun activity(enabled: Boolean, state: OneShotOccurrenceProjection.State,
        now: AccessClockStamp): CommitmentPolicy.CauseActivity =
        CommitmentPolicy.CauseActivity.ScheduledOccurrence(enabled,
            state.interval.takeUnless { state.ended }, now)

    fun action(action: CommitmentPolicy.Action, tier: BreakLevel, enabled: Boolean,
        state: OneShotOccurrenceProjection.State, now: AccessClockStamp): CommitmentPolicy.Outcome =
        policy.decide(action, tier, activity(enabled, state, now))

    fun edit(before: CommitmentPolicy.ProtectionShape, after: CommitmentPolicy.ProtectionShape,
        beforeSchedule: OneShotSchedule, afterSchedule: OneShotSchedule, enabled: Boolean,
        state: OneShotOccurrenceProjection.State, now: AccessClockStamp): CommitmentPolicy.Outcome {
        val activity = activity(enabled, state, now)
        // Even an extension or an apparently equivalent zone change is a changed window.
        if (beforeSchedule != afterSchedule)
            return policy.decide(CommitmentPolicy.Action.TOGGLE_OFF, before.tier, activity)
        return policy.decideEdit(before, after, activity)
    }
}
