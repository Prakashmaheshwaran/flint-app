package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.security.MessageDigest
import java.util.UUID

internal data class OrdinaryProtectionInput(
    val packageName: String,
    val selfPackage: String,
    val timeSpent: Boolean,
    val openBlocked: Boolean,
    val url: String? = null,
    val legacyTimeEndEpochMs: Long? = null,
)

internal object OrdinaryProtectionPolicy {
    private data class Cause(val scope: AccessCauseScope, val tier: BreakLevel, val remaining: Long, val legacyRights: Boolean)
    private val harder = HarderWaitPolicy()

    fun frozenScopes(snapshot: ProtectionSnapshot, input: OrdinaryProtectionInput, foreign: List<BlockRule>, now: AccessClockStamp): Set<AccessCauseScope>? =
        causes(snapshot.copy(observedAt = now), input, foreign)?.map { it.scope }?.toSet()

    fun covers(snapshot: ProtectionSnapshot, input: OrdinaryProtectionInput, foreign: List<BlockRule>, now: AccessClockStamp): Boolean {
        val grant = snapshot.focus.protection?.ordinaryGrants?.values?.firstOrNull { it.target == AccessTarget.App(input.packageName) } ?: return false
        if (grant.lifetime.remainingMs(now)?.let { it > 0 } != true) return false
        val live = causes(snapshot.copy(observedAt = now), input, foreign) ?: return false
        return live.isNotEmpty() && live.none { it.tier == BreakLevel.HARDCORE } && live.map { it.scope }.toSet() == grant.scopes
    }

    fun request(original: ProtectionSnapshot, input: OrdinaryProtectionInput, id: String, issuedAt: AccessClockStamp,
        foreign: List<BlockRule>, expectedScopes: Set<AccessCauseScope>): ProtectionMutation<GrantResult> {
        val snapshot = GlobalRecoveryPolicy.checkpoint(original)
        val current = snapshot.focus
        val ledger = current.protection ?: return ProtectionMutation(current, GrantResult.Unavailable())
        fun unchanged(result: GrantResult) = ProtectionMutation(current, result)
        if (GlobalRecoveryPolicy.remaining(ledger.recovery, snapshot.observedAt) > 0) return unchanged(GrantResult.Denied)
        val causes = causes(snapshot, input, foreign) ?: return unchanged(GrantResult.Unavailable())
        if (causes.isEmpty() || causes.any { it.tier == BreakLevel.HARDCORE }) return unchanged(GrantResult.Denied)
        val scopes = causes.map { it.scope }.toSet()
        // A contributor arriving after the explicit click never joins that request.
        if (scopes != expectedScopes) return unchanged(GrantResult.Denied)
        if (scopes.size > AccessLedgerValidation.MAX_SCOPES) return unchanged(GrantResult.Unavailable())
        val scopeId = hash("ordinary|${input.packageName}|${scopes.sortedBy { it.toString() }}")
        val prior = ledger.operations[id]
        if (prior != null && (prior.issuedAt != issuedAt || prior.scopeIdentity != scopeId)) return unchanged(GrantResult.Denied)
        val live = ledger.ordinaryGrants.values.firstOrNull { it.target == AccessTarget.App(input.packageName) }
        if (live != null) {
            if (live.scopes == scopes) return unchanged(granted(live, false, snapshot.observedAt))
            // A stronger/new contributor stands immediately. Retire the old grant durably;
            // a separate explicit request starts any new friction/confirmation.
            val event = GlobalRecoveryPolicy.ordinaryEnded(live, snapshot.observedAt, ProtectionBreakEndReason.SUPERSEDED, current.revision + 1)
            val next = GlobalRecoveryPolicy.bound(ledger.copy(ordinaryGrants = ledger.ordinaryGrants - live.operationId,
                outbox = ledger.outbox + (event.id to event)), snapshot.observedAt)
            return ProtectionMutation(current.copy(protection = next), GrantResult.Denied)
        }
        if (prior != null) return unchanged(GrantResult.Denied)
        if (ledger.operations.size >= 2_000) return unchanged(GrantResult.Unavailable())
        if (!validId(id) || trustedElapsedMs(issuedAt, snapshot.observedAt)?.let { it < GlobalRecoveryPolicy.REQUEST_LIFETIME_MS } != true)
            return unchanged(GrantResult.Unavailable())
        var waits = snapshot.harder
        val hardCauses = causes.filter { it.tier == BreakLevel.HARDER && !it.legacyRights }
        if (hardCauses.isNotEmpty()) {
            val op = HarderWaitPolicy.Operation(HarderWaitPolicy.OperationKind.BREAK,
                hardCauses.associate { HarderWaitPolicy.Occurrence("${it.scope.counter.rule.source}:${it.scope.counter.rule.ruleId}", it.scope.counter.periodId) to it.scope.definitionRevision },
                setOf(input.packageName))
            when (val commit = harder.commit(waits, op, snapshot.observedAt)) {
                is HarderWaitPolicy.Commit.Committed -> waits = commit.ledger
                is HarderWaitPolicy.Commit.NotYet -> return ProtectionMutation(current, pending(snapshot.observedAt, waits.pending!!, commit.remainingMs))
                HarderWaitPolicy.Commit.Stale -> {
                    val waiting = harder.request(waits, op, snapshot.observedAt)
                    return ProtectionMutation(current, pending(snapshot.observedAt, waiting.pending, waiting.remainingMs), harder = waiting.ledger)
                }
            }
        }
        val duration = minOf(ProtectionContract.ORDINARY_BREAK_MS, causes.minOf { it.remaining })
        if (duration <= 0 || ledger.ordinaryGrants.size >= 128) return unchanged(GrantResult.Unavailable())
        val now = snapshot.observedAt
        val rules = scopes.filter { it.counter.rule.source == "rule" || it.counter.rule.source == "foreign" }.map { it.counter.rule.ruleId }.toSet()
        val grant = ProtectionOrdinaryGrant(id, AccessTarget.App(input.packageName), AccessDuration(now, duration), scopes, rules)
        val event = ProtectionLifecycleEvent("break-started:$id", id, ProtectionLifecycleKind.BREAK_STARTED, rules, now,
            grantId = id, untilEpochMs = now.wallEpochMs + duration, revision = current.revision + 1)
        val next = GlobalRecoveryPolicy.bound(ledger.copy(ordinaryGrants = ledger.ordinaryGrants + (id to grant),
            operations = ledger.operations + (id to ProtectionPassReceipt(id, issuedAt, now, scopeId)), outbox = ledger.outbox + (event.id to event), previousAccessEnded = false), now)
        return ProtectionMutation(current.copy(protection = next), granted(grant, true, now), harder = waits)
    }

    fun resume(original: ProtectionSnapshot, packageName: String? = null): ProtectionMutation<Boolean> {
        val snapshot = GlobalRecoveryPolicy.checkpoint(original)
        val ledger = snapshot.focus.protection ?: return ProtectionMutation(snapshot.focus, false)
        val ended = ledger.ordinaryGrants.values.filter { packageName == null || it.target == AccessTarget.App(packageName) }
        val events = ended.map { GlobalRecoveryPolicy.ordinaryEnded(it, snapshot.observedAt, ProtectionBreakEndReason.RESUMED, snapshot.focus.revision + 1) }
        val next = GlobalRecoveryPolicy.bound(ledger.copy(ordinaryGrants = ledger.ordinaryGrants - ended.map { it.operationId }.toSet(),
            outbox = ledger.outbox + events.associateBy { it.id }), snapshot.observedAt)
        return ProtectionMutation(snapshot.focus.copy(protection = next), true, harder = snapshot.harder.copy(pending = null))
    }

    fun retireInvalid(snapshot: ProtectionSnapshot, input: OrdinaryProtectionInput, foreign: List<BlockRule>): FocusSnapshot {
        val ledger = snapshot.focus.protection ?: return snapshot.focus
        val grant = ledger.ordinaryGrants.values.firstOrNull { it.target == AccessTarget.App(input.packageName) } ?: return snapshot.focus
        val current = causes(snapshot, input, foreign) ?: return snapshot.focus
        if (current.none { it.tier == BreakLevel.HARDCORE } && current.map { it.scope }.toSet() == grant.scopes) return snapshot.focus
        val event = GlobalRecoveryPolicy.ordinaryEnded(grant, snapshot.observedAt, ProtectionBreakEndReason.SUPERSEDED, snapshot.focus.revision + 1)
        return snapshot.focus.copy(protection = GlobalRecoveryPolicy.bound(ledger.copy(ordinaryGrants = ledger.ordinaryGrants - grant.operationId,
            outbox = ledger.outbox + (event.id to event)), snapshot.observedAt))
    }

    private fun causes(snapshot: ProtectionSnapshot, input: OrdinaryProtectionInput, foreign: List<BlockRule>): List<Cause>? {
        val result = mutableListOf<Cause>()
        fun add(source: String, id: String, tier: BreakLevel, definition: String, cause: EditorCause?): Boolean {
            cause ?: return false
            if (cause.occurrenceId == "inactive" || cause.interval?.remainingMs(snapshot.observedAt) == 0L) return true
            val counter = AccessCounterKey(AccessRuleKey(RuleAuthority.Personal, source, id), cause.occurrenceId)
            result += Cause(AccessCauseScope(counter, hash(definition)), tier, cause.interval?.remainingMs(snapshot.observedAt) ?: Long.MAX_VALUE, cause.legacyRights)
            return true
        }
        for (rule in snapshot.rules.filter { it.enabled && targets(it, input) }) {
            val cause = snapshot.editor.causes[ruleCauseKey(rule.id)]
            val endIdentity = cause?.interval?.let { "${it.anchor.bootSessionId}:${it.anchor.elapsedRealtimeMs + it.remainingAtAnchorMs}" }
                ?: rule.expiresAtEpochMs?.toString().orEmpty()
            // R-18 may rewrite a finite rule's wall display end after a clock change.
            // Its anchored elapsed end/occurrence remains the same authority.
            if (!add("rule", rule.id, rule.breakLevel, "${rule.copy(expiresAtEpochMs = null)}|$endIdentity", cause)) return null
        }
        for (rule in foreign.filter { it.enabled && it.id !in snapshot.rules.map { r -> r.id } && targets(it, input) }) {
            if (!GlobalRecoveryPolicy.foreignActive(rule, snapshot.observedAt, snapshot.editor.timeline)) continue
            // Legacy source retains its existing rights; its finite release still uses the
            // common monotonic grant clock and a frozen definition identity.
            val cause = EditorCause(rule.id, rule.expiresAtEpochMs?.let { end ->
                AnchoredInterval(snapshot.observedAt, (end - snapshot.observedAt.wallEpochMs).coerceAtLeast(0), end)
            }, "UTC", legacyRights = true)
            if (!add("foreign", rule.id, rule.breakLevel, rule.toString(), cause)) return null
        }
        if (input.timeSpent) {
            val time = snapshot.times.firstOrNull { it.packageName == input.packageName }
            if (time != null) {
                if (!add("time", time.packageName, time.breakLevel, time.toString(), snapshot.editor.causes[timeCauseKey(time.packageName)])) return null
            } else {
                val quota = snapshot.openCredits
                val left = quota?.let { OpenCreditPolicy().remainingToResetMs(it.reset, snapshot.observedAt) }
                    ?: input.legacyTimeEndEpochMs?.let { (it - snapshot.observedAt.wallEpochMs).coerceAtLeast(0) } ?: return null
                val end = snapshot.observedAt.wallEpochMs + left
                val cause = EditorCause("legacy-time-${quota?.periodId ?: input.legacyTimeEndEpochMs}", AnchoredInterval(snapshot.observedAt,
                    left, end), quota?.quotaZone ?: "UTC", legacyRights = true)
                if (!add("legacy-time", input.packageName, snapshot.focus.defaultBreakLevel, "legacy-time:${snapshot.focus.defaultBreakLevel}", cause)) return null
            }
        }
        if (input.openBlocked) {
            val open = snapshot.opens.firstOrNull { it.packageName == input.packageName } ?: return null
            if (!add("open", open.packageName, open.breakLevel, open.toString(), snapshot.editor.causes[openCauseKey(open.packageName)])) return null
        }
        return result
    }

    private fun targets(rule: BlockRule, input: OrdinaryProtectionInput): Boolean = BlockDecisionEngine().decide(input.packageName,
        input.url, listOf(rule.copy(schedule = null, expiresAtEpochMs = null)), input.selfPackage, 0, 1, 0) is Verdict.Block

    private fun granted(grant: ProtectionOrdinaryGrant, newly: Boolean, now: AccessClockStamp) = GrantResult.Granted(listOf(
        PersistedExemption(grant.operationId, (grant.target as AccessTarget.App).packageName, hash(grant.scopes.toString()), ExemptionKind.BREAK,
            grant.lifetime.startedAt.wallEpochMs + grant.lifetime.durationMs, grant.lifetime.startedAt.bootSessionId)), newly, now.wallEpochMs)

    private fun pending(now: AccessClockStamp, pending: HarderWaitPolicy.PendingWait, remaining: Long): GrantResult.Pending =
        GrantResult.Pending(BreakSessionState(pending = BreakRequestState(now.wallEpochMs - (pending.requiredMs - remaining), now.wallEpochMs + remaining)))

    private fun hash(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun validId(id: String) = id.startsWith("p1-") && runCatching { UUID.fromString(id.removePrefix("p1-")).toString() == id.removePrefix("p1-") }.getOrDefault(false)
}
