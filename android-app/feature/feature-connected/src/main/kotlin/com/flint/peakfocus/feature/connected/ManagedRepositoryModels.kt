package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.model.*

data class ManagedRuleInput(
    val name: String,
    val zoneId: String,
    val enabled: Boolean,
    val scope: ManagedRuleScope,
    val schedule: ManagedSchedule,
    val targets: ManagedTargets,
    val unblocksAllowed: Boolean,
)

/** Authoring results carry their local request identity; they are never member authority. */
data class ManagedRulePage(val identity: ManagedIdentity, val revision: Long, val rules: List<ManagedRule>, val tombstones: List<ManagedTombstone>)
data class ManagedRuleResult(val identity: ManagedIdentity, val revision: Long, val rule: ManagedRule)
data class ManagedDeleteResult(val identity: ManagedIdentity, val revision: Long, val tombstone: ManagedTombstone)

sealed interface ManagedAuthorityState {
    data object SignedOut : ManagedAuthorityState
    data class Loading(val identity: ManagedIdentity?, val cached: ManagedSnapshot? = null) : ManagedAuthorityState
    data class Ready(val snapshot: ManagedSnapshot, val clock: ManagedClock) : ManagedAuthorityState
    data class Stale(val snapshot: ManagedSnapshot, val clock: ManagedClock, val message: String) : ManagedAuthorityState
    data class Unavailable(val identity: ManagedIdentity?, val message: String) : ManagedAuthorityState
}

sealed interface ManagedRefreshResult {
    data class Accepted(val state: ManagedAuthorityState.Ready) : ManagedRefreshResult
    data class Retained(val state: ManagedAuthorityState) : ManagedRefreshResult
    data object SignedOut : ManagedRefreshResult
}

enum class ManagedFailureKind {
    IDENTITY_CHANGED, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, REVISION_CONFLICT,
    CAPACITY, VALIDATION, PROTOCOL, STORAGE, NETWORK, AMBIGUOUS_MUTATION, RATE_LIMITED,
}

internal class ManagedRequestFailure(
    val kind: ManagedFailureKind,
    message: String,
    val status: Int? = null,
    val code: String? = null,
) : ConnectedFailure(message)
