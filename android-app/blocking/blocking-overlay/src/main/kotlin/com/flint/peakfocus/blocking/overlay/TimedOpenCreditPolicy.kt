package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.security.MessageDigest
import java.util.UUID

internal data class TimedCreditView(val remainingCredits: Int, val durationMs: Long, val activeRemainingMs: Long,
    val blockingContributors: List<String>, val requestScope: String)

/** The C4c shared ledger is the only credit/counter authority. Event receipts never grant. */
internal object TimedOpenCreditPolicy {
    private val policy = OpenCreditPolicy()

    fun view(snapshot: ProtectionSnapshot, input: OrdinaryProtectionInput, foreign: List<BlockRule>, now: AccessClockStamp): TimedCreditView? {
        val credits = snapshot.openCredits ?: return null
        val limits = snapshot.opens.filter { it.packageName == input.packageName }
        if (limits.isEmpty()) return null
        val cause = snapshot.editor.causes[openCauseKey(input.packageName)] ?: return null
        if (cause.occurrenceId == "inactive" || cause.interval?.remainingMs(now) == 0L) return null
        val others = OrdinaryProtectionPolicy.frozenScopes(snapshot, input.copy(openBlocked = false), foreign, now) ?: return null
        val names = others.map { scope -> when (scope.counter.rule.source) {
            "time", "legacy-time" -> "Time limit"
            else -> ProtectionStatus.sanitizeDisplayName((snapshot.rules + foreign).firstOrNull { it.id == scope.counter.rule.ruleId }?.name ?: "Another commitment")
        } }.distinct().sorted()
        val reset = policy.remainingToResetMs(credits.reset, now)
        val remaining = OpenCreditLedgerPolicy.remainingCredits(credits, input.packageName, limits.map { it.dailyOpens }) ?: return null
        val live = credits.active[input.packageName]?.grant?.let { minOf(policy.remainingMs(it, now), reset) } ?: 0L
        val raw = "${credits.periodId}|${cause.occurrenceId}|${limits.sortedBy { it.toString() }}"
        val identity = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        return TimedCreditView(remaining, minOf(ProtectionContract.OPEN_CREDIT_MS, reset), live, names, "${credits.periodId}:$identity")
    }

    fun request(original: ProtectionSnapshot, input: OrdinaryProtectionInput, id: GrantRequestId, foreign: List<BlockRule>, onSafetyFloor: Boolean): ProtectionMutation<GrantResult> {
        val snapshot = GlobalRecoveryPolicy.checkpoint(original)
        val focus = snapshot.focus
        fun unchanged(result: GrantResult) = ProtectionMutation(focus, result)
        val ledger = focus.protection ?: return unchanged(GrantResult.Unavailable())
        val credits = snapshot.openCredits ?: return unchanged(GrantResult.Unavailable())
        if (onSafetyFloor || GlobalRecoveryPolicy.remaining(ledger.recovery, snapshot.observedAt) > 0) return unchanged(GrantResult.Denied)
        val stamp = id.issuedAtClock ?: return unchanged(GrantResult.Unavailable())
        if (trustedElapsedMs(stamp, snapshot.observedAt)?.let { it < GlobalRecoveryPolicy.REQUEST_LIFETIME_MS } != true ||
            runCatching { UUID.fromString(id.nonce).toString() == id.nonce }.getOrDefault(false).not()) return unchanged(GrantResult.Denied)
        val view = view(snapshot, input, foreign, snapshot.observedAt) ?: return unchanged(GrantResult.Unavailable())
        if (id.creditScope == null || id.creditScope != view.requestScope) return unchanged(GrantResult.Denied)
        if (ledger.pendingCreditEnds.size >= 128 && input.packageName !in credits.active) return unchanged(GrantResult.Unavailable())
        val operation = "p1-${id.nonce}"
        val mutation = try { OpenCreditLedgerPolicy.acceptOpenCredit(credits, input.packageName, operation,
            snapshot.opens.filter { it.packageName == input.packageName }.map { it.dailyOpens }, view.blockingContributors,
            onSafetyFloor, snapshot.observedAt) } catch (_: IllegalArgumentException) { return unchanged(GrantResult.Unavailable()) }
        // AlreadyActive/Duplicate precede overlap checks in the pure quota policy. They are
        // accounting results, never authority to release through a newly independent cause.
        if (view.blockingContributors.isNotEmpty()) return ProtectionMutation(focus, GrantResult.Denied, openCredits = mutation.next)
        return when (val result = mutation.acceptance) {
            is OpenCreditPolicy.Acceptance.Accepted -> {
                val grant = result.grant
                val event = ProtectionLifecycleEvent("access-started:$operation", operation, ProtectionLifecycleKind.ACCESS_STARTED,
                    emptySet(), snapshot.observedAt, grantId = operation, untilEpochMs = grant.startedAt.wallEpochMs + grant.durationMs, revision = focus.revision + 1)
                val receipt = ProtectionCreditEventReceipt(operation, input.packageName, AccessDuration(grant.startedAt, grant.durationMs))
                val next = GlobalRecoveryPolicy.bound(ledger.copy(pendingCreditEnds = ledger.pendingCreditEnds + (operation to receipt),
                    outbox = ledger.outbox + (event.id to event), previousAccessEnded = false), snapshot.observedAt)
                ProtectionMutation(focus.copy(protection = next), GrantResult.OpenAllowed, openCredits = mutation.next)
            }
            is OpenCreditPolicy.Acceptance.AlreadyActive -> ProtectionMutation(focus, GrantResult.OpenAllowed, openCredits = mutation.next)
            is OpenCreditPolicy.Acceptance.Duplicate -> ProtectionMutation(focus,
                if (result.liveGrant?.let { policy.remainingMs(it, snapshot.observedAt) > 0 } == true) GrantResult.OpenAllowed else GrantResult.Denied,
                openCredits = mutation.next)
            is OpenCreditPolicy.Acceptance.Denied -> ProtectionMutation(focus,
                if (result.reason == OpenCreditPolicy.Denial.UNAVAILABLE) GrantResult.Unavailable() else GrantResult.Denied, openCredits = mutation.next)
        }
    }

    fun closeExpired(snapshot: ProtectionSnapshot): ProtectionSnapshot {
        val ledger = snapshot.focus.protection ?: return snapshot
        val closing = ledger.pendingCreditEnds.values.filter { it.lifetime.remainingMs(snapshot.observedAt)?.let { left -> left > 0 } != true }
        if (closing.isEmpty()) return snapshot
        val events = closing.map { ended(it, snapshot.observedAt, ProtectionBreakEndReason.EXPIRED, snapshot.focus.revision + 1) }
        return snapshot.copy(focus = snapshot.focus.copy(protection = GlobalRecoveryPolicy.bound(ledger.copy(
            pendingCreditEnds = ledger.pendingCreditEnds - closing.map { it.operationId }.toSet(), outbox = ledger.outbox + events.associateBy { it.id },
            previousAccessEnded = ledger.previousAccessEnded || closing.any { trustedElapsedMs(it.lifetime.startedAt, snapshot.observedAt) == null }), snapshot.observedAt)))
    }

    fun ended(receipt: ProtectionCreditEventReceipt, now: AccessClockStamp, reason: ProtectionBreakEndReason, revision: Long): ProtectionLifecycleEvent {
        val start = receipt.lifetime.startedAt
        val elapsed = trustedElapsedMs(start, now)
        val at = if (reason == ProtectionBreakEndReason.EXPIRED && elapsed != null && elapsed >= receipt.lifetime.durationMs)
            AccessClockStamp(start.wallEpochMs + receipt.lifetime.durationMs, start.elapsedRealtimeMs + receipt.lifetime.durationMs, start.bootSessionId) else now
        return ProtectionLifecycleEvent("access-ended:${receipt.operationId}", receipt.operationId, ProtectionLifecycleKind.ACCESS_ENDED,
            emptySet(), at, grantId = receipt.operationId, breakEndReason = reason, revision = revision, timeUncertain = clockDiscontinuity(start, now))
    }

    fun resume(original: ProtectionSnapshot): ProtectionMutation<Boolean> {
        val snapshot = GlobalRecoveryPolicy.checkpoint(original)
        val ledger = snapshot.focus.protection ?: return ProtectionMutation(snapshot.focus, false)
        val events = ledger.pendingCreditEnds.values.map { ended(it, snapshot.observedAt, ProtectionBreakEndReason.RESUMED, snapshot.focus.revision + 1) }
        return ProtectionMutation(snapshot.focus.copy(protection = GlobalRecoveryPolicy.bound(ledger.copy(pendingCreditEnds = emptyMap(),
            outbox = ledger.outbox + events.associateBy { it.id }), snapshot.observedAt)), true, openCredits = snapshot.openCredits?.copy(active = emptyMap()))
    }
}
