package com.flint.peakfocus.core.model

/** Runtime publication identity. No authentication credential belongs in this model. */
data class ManagedIdentity(
    val projectId: String,
    val accountUid: String,
    val sessionGeneration: Long,
)

enum class ManagedRole { OWNER, MEMBER }

data class ManagedMembership(
    val groupId: String,
    val membershipId: String,
    val role: ManagedRole,
)

sealed interface ManagedRuleScope {
    data object Group : ManagedRuleScope
    data class Member(val userId: String) : ManagedRuleScope
}

sealed interface ManagedTargets {
    data object AllApps : ManagedTargets
    data class Selection(val packages: Set<String>, val domains: Set<String>) : ManagedTargets
}

sealed interface ManagedSchedule {
    /** Absolute instants; changing the device timezone must not shift a session. */
    data class Session(val startsAtEpochMs: Long, val endsAtEpochMs: Long) : ManagedSchedule

    /** ISO weekdays identify the local START date in the rule's zone, including overnight rules. */
    data class Recurring(
        val startMinute: Int,
        val endMinute: Int,
        val isoWeekdays: Set<Int>,
    ) : ManagedSchedule
}

data class ManagedRule(
    val id: String,
    val targetGroupId: String,
    val revision: Long,
    val name: String,
    val zoneId: String,
    val enabled: Boolean,
    val scope: ManagedRuleScope,
    val schedule: ManagedSchedule,
    val targets: ManagedTargets,
    /** Stored server policy only: no server grant route or local bypass is implied. */
    val unblocksAllowed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class ManagedTombstone(
    val id: String,
    val targetGroupId: String,
    val revision: Long,
    val deletedAtEpochMs: Long,
)

/**
 * Complete account snapshot accepted by the repository. Collections must be immutable copies.
 * Replacement, revision ordering, authentication/session fencing, and wire validation belong to
 * that repository. Managed rules must never be published to the editable personal rule store.
 */
data class ManagedSnapshot(
    val identity: ManagedIdentity,
    val schemaVersion: Int = 1,
    val revision: Long,
    val serverTimeEpochMs: Long,
    val memberships: List<ManagedMembership>,
    val rules: List<ManagedRule>,
    val tombstones: List<ManagedTombstone>,
)

/** The host owns server/elapsed-time anchoring; the evaluator never substitutes device wall time. */
sealed interface ManagedClock {
    data class Confirmed(val nowEpochMs: Long) : ManagedClock
    data class Unconfirmed(val reason: String) : ManagedClock
}

/** A half-open interval [startsAtEpochMs, endsAtEpochMs), resolved in a rule's explicit zone. */
data class ManagedOccurrence(val startsAtEpochMs: Long, val endsAtEpochMs: Long)

/** Exact authority and occurrence identity reserved for later all-cause arbitration/server grants. */
data class ManagedCauseScope(
    val authority: RuleAuthority.Managed,
    val ruleId: String,
    val ruleRevision: Long,
    val occurrence: ManagedOccurrence,
)
