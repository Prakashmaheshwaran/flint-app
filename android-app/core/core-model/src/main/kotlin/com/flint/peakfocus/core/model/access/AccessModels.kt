package com.flint.peakfocus.core.model

/** V2 resistance is independent of the legacy EASY/HARDER/HARDCORE contract. */
enum class ResistanceMode { STANDARD, INCREASING }

sealed interface RuleAuthority {
    data object Personal : RuleAuthority

    /** Reserved namespace. Personal access operations cannot issue managed grants. */
    data class Managed(
        val projectId: String,
        val accountUid: String,
        val groupId: String,
        val membershipId: String,
    ) : RuleAuthority
}

data class AccessRuleKey(val authority: RuleAuthority, val source: String, val ruleId: String)

/** periodId identifies an occurrence/accounting period, not an arbitrary current date. */
data class AccessCounterKey(val rule: AccessRuleKey, val periodId: String)

/** Revisions invalidate grants without resetting a period's successful-unblock count. */
data class AccessCauseScope(val counter: AccessCounterKey, val definitionRevision: String)

sealed interface AccessTarget {
    data class App(val packageName: String) : AccessTarget
    data class Website(val browserPackageName: String, val normalizedHost: String) : AccessTarget
}

sealed interface OrdinaryAccessPolicy {
    data object Forbidden : OrdinaryAccessPolicy
    data class Allowed(val resistance: ResistanceMode, val maximumDurationMs: Long) : OrdinaryAccessPolicy
}

/** Wall time is for display/audit; elapsedRealtime and boot identity enforce durations. */
data class AccessClockStamp(
    val wallEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val bootSessionId: String,
)

data class AccessDuration(val startedAt: AccessClockStamp, val durationMs: Long) {
    fun hasTrustedClock(now: AccessClockStamp): Boolean =
        now.bootSessionId == startedAt.bootSessionId && now.elapsedRealtimeMs >= startedAt.elapsedRealtimeMs

    fun remainingMs(now: AccessClockStamp): Long? =
        if (!hasTrustedClock(now)) null
        else (durationMs - (now.elapsedRealtimeMs - startedAt.elapsedRealtimeMs)).coerceAtLeast(0L)
}

data class PendingAccess(
    val sequence: Long,
    val target: AccessTarget,
    val scopes: Set<AccessCauseScope>,
    val requestedDurationMs: Long,
    val wait: AccessDuration,
)

/** EMERGENCY_PASS is reserved; this foundation has no emergency writer or authorization. */
enum class PersonalGrantKind { ORDINARY_BREAK, EMERGENCY_PASS }

data class TemporaryAccess(
    val sequence: Long,
    val target: AccessTarget,
    val scopes: Set<AccessCauseScope>,
    val kind: PersonalGrantKind,
    val lifetime: AccessDuration,
)

data class AccessLedgerV2(
    val revision: Long = 0,
    val nextSequence: Long = 1,
    val ordinaryUnblocks: Map<AccessCounterKey, Long> = emptyMap(),
    val pending: Map<Long, PendingAccess> = emptyMap(),
    val grants: Map<Long, TemporaryAccess> = emptyMap(),
)

/** Shared by engine and storage so the pure engine has no persistence dependency. */
data class AccessMutation<R>(val next: AccessLedgerV2, val result: R)

/**
 * The caller must resolve ALL applicable causes from a ready enforcement snapshot. This is
 * not an authorization token: completion rechecks this snapshot, and enforcement must match
 * each live cause against a grant before selecting its strongest remaining display cause.
 */
data class ValidatedAccessRequest(
    val target: AccessTarget,
    val policies: Map<AccessCauseScope, OrdinaryAccessPolicy>,
    val requestedDurationMs: Long,
)

enum class AccessDenial { FORBIDDEN, MANAGED_AUTHORITY, INVALID_REQUEST, CAPACITY }

sealed interface AccessRequestResult {
    data class Pending(val request: PendingAccess) : AccessRequestResult
    data class Granted(val grant: TemporaryAccess) : AccessRequestResult
    data class AlreadyGranted(val grant: TemporaryAccess) : AccessRequestResult
    data class Denied(val reason: AccessDenial) : AccessRequestResult
    data object StaleRequest : AccessRequestResult
}

/** Structural validation shared by the strict codec, store, and pure policy. */
object AccessLedgerValidation {
    const val MAX_ENTRIES = 10_000
    const val MAX_SCOPES = 1_000
    const val MAX_ATOM_LENGTH = 2_048
    const val MAX_WAIT_MS = 120_000L

    fun requireValid(ledger: AccessLedgerV2) {
        require(ledger.revision >= 0 && ledger.nextSequence > 0)
        require(ledger.ordinaryUnblocks.size + ledger.pending.size + ledger.grants.size <= MAX_ENTRIES)
        ledger.ordinaryUnblocks.forEach { (key, count) -> requireCounter(key); require(count >= 0) }
        require(ledger.pending.keys.intersect(ledger.grants.keys).isEmpty())
        ledger.pending.forEach { (id, request) ->
            require(id == request.sequence && id > 0 && id < ledger.nextSequence)
            requireTarget(request.target)
            requireScopes(request.scopes)
            require(request.requestedDurationMs > 0)
            requireDuration(request.wait)
            require(request.wait.durationMs <= MAX_WAIT_MS)
        }
        require(ledger.pending.values.map { it.target to it.scopes }.distinct().size == ledger.pending.size)
        ledger.grants.forEach { (id, grant) ->
            require(id == grant.sequence && id > 0 && id < ledger.nextSequence)
            requireTarget(grant.target)
            requireScopes(grant.scopes)
            requireDuration(grant.lifetime)
        }
    }

    fun requireClock(clock: AccessClockStamp) {
        require(clock.wallEpochMs >= 0 && clock.elapsedRealtimeMs >= 0)
        requireAtom(clock.bootSessionId)
    }

    fun requireTarget(target: AccessTarget) {
        when (target) {
            is AccessTarget.App -> requireAtom(target.packageName)
            is AccessTarget.Website -> {
                requireAtom(target.browserPackageName)
                requireAtom(target.normalizedHost)
                require(target.normalizedHost == target.normalizedHost.lowercase())
                require(target.normalizedHost.none { it.isWhitespace() || it in "/:?#" })
            }
        }
    }

    fun requireScopes(scopes: Set<AccessCauseScope>) {
        require(scopes.isNotEmpty() && scopes.size <= MAX_SCOPES)
        require(scopes.map { it.counter }.distinct().size == scopes.size)
        scopes.forEach { scope ->
            requireCounter(scope.counter)
            requireAtom(scope.definitionRevision)
            require(scope.counter.rule.authority == RuleAuthority.Personal)
        }
    }

    fun requireCounter(counter: AccessCounterKey) {
        requireAtom(counter.rule.source)
        requireAtom(counter.rule.ruleId)
        requireAtom(counter.periodId)
        when (val authority = counter.rule.authority) {
            RuleAuthority.Personal -> Unit
            is RuleAuthority.Managed -> {
                requireAtom(authority.projectId)
                requireAtom(authority.accountUid)
                requireAtom(authority.groupId)
                requireAtom(authority.membershipId)
            }
        }
    }

    private fun requireDuration(duration: AccessDuration) {
        requireClock(duration.startedAt)
        require(duration.durationMs > 0)
    }

    private fun requireAtom(value: String) = require(value.isNotBlank() && value.length <= MAX_ATOM_LENGTH)
}
