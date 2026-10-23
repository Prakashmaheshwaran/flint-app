package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessCauseScope
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AccessDenial
import com.flint.peakfocus.core.model.AccessDuration
import com.flint.peakfocus.core.model.AccessLedgerV2
import com.flint.peakfocus.core.model.AccessLedgerValidation
import com.flint.peakfocus.core.model.AccessMutation
import com.flint.peakfocus.core.model.AccessRequestResult
import com.flint.peakfocus.core.model.AccessTarget
import com.flint.peakfocus.core.model.OrdinaryAccessPolicy
import com.flint.peakfocus.core.model.PendingAccess
import com.flint.peakfocus.core.model.PersonalGrantKind
import com.flint.peakfocus.core.model.ResistanceMode
import com.flint.peakfocus.core.model.RuleAuthority
import com.flint.peakfocus.core.model.TemporaryAccess
import com.flint.peakfocus.core.model.ValidatedAccessRequest

/**
 * Pure v2 personal access transitions. Run the entire transition inside AccessStateStore's
 * transaction and honor only its committed result. This does not replace legacy BreakPolicy.
 * Caller-supplied cause snapshots must be complete and ready; every live cause must still be
 * checked with [covers] after commit and on each detection event. No managed/emergency writer.
 */
class TemporaryAccessPolicy {
    fun resistanceDelayMs(mode: ResistanceMode, successfulUnblocks: Long): Long = when (mode) {
        ResistanceMode.STANDARD -> 5_000L
        ResistanceMode.INCREASING -> (successfulUnblocks.coerceIn(0L, 5L) + 1L) * 20_000L
    }

    fun begin(
        ledger: AccessLedgerV2,
        request: ValidatedAccessRequest,
        now: AccessClockStamp,
    ): AccessMutation<AccessRequestResult> {
        validate(ledger, now)
        requestDenial(request)?.let { return AccessMutation(ledger, AccessRequestResult.Denied(it)) }
        if (ledger.revision == Long.MAX_VALUE) return denied(ledger, AccessDenial.CAPACITY)
        val current = normalized(ledger, now)
        current.grants.values.firstOrNull {
            it.target == request.target && it.scopes == request.policies.keys &&
                it.kind == PersonalGrantKind.ORDINARY_BREAK
        }?.let { return finish(ledger, current, AccessRequestResult.AlreadyGranted(it)) }
        current.pending.values.firstOrNull {
            it.target == request.target && it.scopes == request.policies.keys
        }?.let {
            return finish(ledger, current, if (it.requestedDurationMs == request.requestedDurationMs)
                AccessRequestResult.Pending(it) else AccessRequestResult.StaleRequest)
        }
        if (current.nextSequence == Long.MAX_VALUE || entryCount(current) >= AccessLedgerValidation.MAX_ENTRIES) {
            return finish(ledger, current, AccessRequestResult.Denied(AccessDenial.CAPACITY))
        }
        val pending = PendingAccess(
            sequence = current.nextSequence,
            target = request.target,
            scopes = request.policies.keys.toSet(),
            requestedDurationMs = request.requestedDurationMs,
            wait = AccessDuration(now, requiredWait(current, request)),
        )
        return finish(ledger, current.copy(
            nextSequence = current.nextSequence + 1,
            pending = current.pending + (pending.sequence to pending),
        ), AccessRequestResult.Pending(pending))
    }

    fun complete(
        ledger: AccessLedgerV2,
        sequence: Long,
        current: ValidatedAccessRequest,
        now: AccessClockStamp,
    ): AccessMutation<AccessRequestResult> {
        validate(ledger, now)
        requestDenial(current)?.let { return AccessMutation(ledger, AccessRequestResult.Denied(it)) }
        if (ledger.revision == Long.MAX_VALUE) return denied(ledger, AccessDenial.CAPACITY)
        val reconciled = normalized(ledger, now)
        reconciled.grants[sequence]?.let { grant ->
            val result = if (grant.target == current.target && grant.scopes == current.policies.keys &&
                grant.kind == PersonalGrantKind.ORDINARY_BREAK
            ) AccessRequestResult.AlreadyGranted(grant) else AccessRequestResult.StaleRequest
            return finish(ledger, reconciled, result)
        }
        var pending = reconciled.pending[sequence]
            ?: return finish(ledger, reconciled, AccessRequestResult.StaleRequest)
        if (pending.target != current.target || pending.scopes != current.policies.keys ||
            pending.requestedDurationMs != current.requestedDurationMs
        ) return finish(ledger, reconciled, AccessRequestResult.StaleRequest)

        // Another request may have spent a shared rule's counter during this wait. Never let
        // parallel targets use an old, shorter Increasing wait for their next successful break.
        val required = requiredWait(reconciled, current)
        if (required > pending.wait.durationMs) pending = pending.copy(wait = pending.wait.copy(durationMs = required))
        if (pending.wait.remainingMs(now) != 0L) {
            return finish(ledger, reconciled.copy(pending = reconciled.pending + (sequence to pending)),
                AccessRequestResult.Pending(pending))
        }
        if (pending.scopes.any { reconciled.ordinaryUnblocks[it.counter] == Long.MAX_VALUE }) {
            return finish(ledger, reconciled, AccessRequestResult.Denied(AccessDenial.CAPACITY))
        }
        val counts = reconciled.ordinaryUnblocks.toMutableMap()
        pending.scopes.forEach { counts[it.counter] = (counts[it.counter] ?: 0) + 1 }
        val grant = TemporaryAccess(sequence, pending.target, pending.scopes, PersonalGrantKind.ORDINARY_BREAK,
            AccessDuration(now, pending.requestedDurationMs))
        val next = reconciled.copy(
            ordinaryUnblocks = counts.toMap(),
            pending = reconciled.pending - sequence,
            grants = reconciled.grants + (sequence to grant),
        )
        return if (entryCount(next) > AccessLedgerValidation.MAX_ENTRIES) {
            finish(ledger, reconciled, AccessRequestResult.Denied(AccessDenial.CAPACITY))
        } else finish(ledger, next, AccessRequestResult.Granted(grant))
    }

    fun cancel(ledger: AccessLedgerV2, sequence: Long): AccessMutation<Boolean> {
        AccessLedgerValidation.requireValid(ledger)
        if (sequence !in ledger.pending || ledger.revision == Long.MAX_VALUE) return AccessMutation(ledger, false)
        return finish(ledger, ledger.copy(pending = ledger.pending - sequence), true)
    }

    /** Same boot restores elapsed duration. New/unknown boot expires grants and restarts waits. */
    fun reconcileClock(ledger: AccessLedgerV2, now: AccessClockStamp): AccessMutation<Unit> {
        validate(ledger, now)
        return if (ledger.revision == Long.MAX_VALUE) AccessMutation(ledger, Unit)
        else finish(ledger, normalized(ledger, now), Unit)
    }

    /** Ordinary personal grants never cover strict, managed, revised, or unrelated causes. */
    fun covers(
        grant: TemporaryAccess,
        target: AccessTarget,
        scope: AccessCauseScope,
        policy: OrdinaryAccessPolicy,
        now: AccessClockStamp,
    ): Boolean = grant.kind == PersonalGrantKind.ORDINARY_BREAK &&
        policy is OrdinaryAccessPolicy.Allowed && scope.counter.rule.authority == RuleAuthority.Personal &&
        grant.lifetime.durationMs <= policy.maximumDurationMs &&
        grant.target == target && scope in grant.scopes && (grant.lifetime.remainingMs(now) ?: 0L) > 0L

    private fun requestDenial(request: ValidatedAccessRequest): AccessDenial? {
        if (request.policies.keys.any { it.counter.rule.authority != RuleAuthority.Personal }) {
            return AccessDenial.MANAGED_AUTHORITY
        }
        if (request.policies.values.any { it == OrdinaryAccessPolicy.Forbidden }) return AccessDenial.FORBIDDEN
        return try {
            AccessLedgerValidation.requireTarget(request.target)
            AccessLedgerValidation.requireScopes(request.policies.keys)
            require(request.requestedDurationMs > 0)
            request.policies.values.forEach {
                require(it is OrdinaryAccessPolicy.Allowed && request.requestedDurationMs <= it.maximumDurationMs)
            }
            null
        } catch (_: IllegalArgumentException) { AccessDenial.INVALID_REQUEST }
    }

    private fun requiredWait(ledger: AccessLedgerV2, request: ValidatedAccessRequest): Long {
        val count = request.policies.keys.maxOf { ledger.ordinaryUnblocks[it.counter] ?: 0L }
        return request.policies.values.maxOf { resistanceDelayMs((it as OrdinaryAccessPolicy.Allowed).resistance, count) }
    }

    private fun normalized(ledger: AccessLedgerV2, now: AccessClockStamp): AccessLedgerV2 = ledger.copy(
        grants = ledger.grants.filterValues { (it.lifetime.remainingMs(now) ?: 0L) > 0L },
        pending = ledger.pending.mapValues { (_, pending) ->
            if (pending.wait.hasTrustedClock(now)) pending else pending.copy(wait = pending.wait.copy(startedAt = now))
        },
    )

    private fun validate(ledger: AccessLedgerV2, now: AccessClockStamp) {
        AccessLedgerValidation.requireValid(ledger)
        AccessLedgerValidation.requireClock(now)
    }

    private fun entryCount(ledger: AccessLedgerV2): Int =
        ledger.ordinaryUnblocks.size + ledger.pending.size + ledger.grants.size

    private fun denied(ledger: AccessLedgerV2, reason: AccessDenial): AccessMutation<AccessRequestResult> =
        AccessMutation(ledger, AccessRequestResult.Denied(reason))

    private fun <R> finish(before: AccessLedgerV2, after: AccessLedgerV2, result: R): AccessMutation<R> =
        AccessMutation(if (before == after) before else after.copy(revision = before.revision + 1), result)
}
