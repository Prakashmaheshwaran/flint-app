package com.flint.peakfocus.blocking.engine

/**
 * Classifies one Time/Open template mutation before the editor commits it. The ordinary
 * [CommitmentPolicy] remains the tier authority; a retained disabled template is distinct
 * from an absent/deleted template. Quota, usage, credit expiry and reset clocks are not inputs:
 * an enabled daily commitment is active even below budget or during an accepted credit.
 *
 * Call this for each kind in a paired edit, using its own prior tier and saved-zone shape.
 * The editor must combine the outcomes atomically and use its existing trusted boundary for
 * a deferred change. This policy never clears accounting or creates a new daily occurrence.
 */
class DailyLimitLifecyclePolicy(private val commitments: CommitmentPolicy = CommitmentPolicy()) {
    data class Template(val enabled: Boolean, val shape: CommitmentPolicy.ProtectionShape) {
        init {
            require(shape.budget != null && shape.budget >= 0)
            require(!shape.allowListMode && shape.apps.size == 1 && shape.apps.single().isNotBlank())
            require(shape.domains.isEmpty() && shape.window == null && shape.endsAtEpochMs == null)
        }
    }

    enum class ChangeKind { UNCHANGED, START, EDIT, TOGGLE_OFF, DELETE }
    data class Decision(val kind: ChangeKind, val outcome: CommitmentPolicy.Outcome)

    /** [legacyRights] is the stored current-occurrence exception from ADR-012 §12. */
    fun decide(before: Template?, after: Template?, legacyRights: Boolean = false): Decision {
        if (before == after) return Decision(ChangeKind.UNCHANGED, CommitmentPolicy.Outcome.ALLOW)
        val activity = if (legacyRights) CommitmentPolicy.CauseActivity.Inactive
            else CommitmentPolicy.CauseActivity.DailyLimit(before?.enabled == true)
        return when {
            after?.enabled == true && before?.enabled != true -> Decision(ChangeKind.START,
                commitments.decide(CommitmentPolicy.Action.START_ADDITIONAL, after.shape.tier,
                    CommitmentPolicy.CauseActivity.Inactive))
            before == null -> Decision(ChangeKind.EDIT, CommitmentPolicy.Outcome.ALLOW)
            after == null -> Decision(ChangeKind.DELETE,
                commitments.decide(CommitmentPolicy.Action.DELETE, before.shape.tier, activity))
            before.enabled && !after.enabled -> Decision(ChangeKind.TOGGLE_OFF,
                commitments.decide(CommitmentPolicy.Action.TOGGLE_OFF, before.shape.tier, activity))
            else -> Decision(ChangeKind.EDIT, commitments.decideEdit(before.shape, after.shape, activity))
        }
    }
}
