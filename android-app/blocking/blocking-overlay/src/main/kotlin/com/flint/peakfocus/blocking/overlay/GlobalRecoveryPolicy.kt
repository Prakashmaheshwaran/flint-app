package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** A confirmation freezes all contributors, rather than only the currently shielded package. */
data class RecoveryPreview internal constructor(
    val operationId: String,
    val issuedAt: AccessClockStamp,
    val scopeIdentity: String,
    val affected: List<RecoveryCommitment>,
    val recoveryEndEstimateEpochMs: Long,
    val nextPassEstimateEpochMs: Long,
)

data class RecoveryCommitment(val causeKey: String, val ruleId: String?, val displayName: String, val transient: Boolean)

sealed interface RecoveryResult {
    data class Accepted(val recovery: ProtectionRecovery?, val newlyAccepted: Boolean) : RecoveryResult
    data object StalePreview : RecoveryResult
    data object Denied : RecoveryResult
    data object Unconfirmed : RecoveryResult
    data class Unavailable(val reason: StorageFailure? = null) : RecoveryResult
}

/** Pure pass decisions; the caller commits this mutation before publishing any global release. */
internal object GlobalRecoveryPolicy {
    private val pass = RollingEmergencyPassPolicy()
    const val REQUEST_LIFETIME_MS = 3_600_000L
    const val MAX_EVENTS = 256
    private const val MAX_EVENT_BYTES = 524_288L

    fun passState(ledger: ProtectionLedger) = RollingEmergencyPassPolicy.PassState.Known(
        ledger.cooldown?.let { RollingEmergencyPassPolicy.Cooldown(it.operationId, it.usedAtWallEpochMs, it.checkpoint,
            it.remainingAtCheckpointMs, it.wallUncertain) },
        ledger.recovery?.let { RollingEmergencyPassPolicy.Recovery(it.operationId, it.startedAt,
            RollingEmergencyPassPolicy.RecoveryScope(it.affectedCauseKeys, it.endedTransientIds), it.durationMs) },
    )

    fun remaining(recovery: ProtectionRecovery?, now: AccessClockStamp): Long = recovery?.let {
        pass.recoveryRemainingMs(RollingEmergencyPassPolicy.Recovery(it.operationId, it.startedAt,
            RollingEmergencyPassPolicy.RecoveryScope(it.affectedCauseKeys, it.endedTransientIds), it.durationMs), now)
    } ?: 0L

    fun checkpoint(snapshot: ProtectionSnapshot): ProtectionSnapshot {
        val ledger = snapshot.focus.protection ?: return snapshot
        val now = snapshot.observedAt
        val state = pass.checkpointed(passState(ledger), now) as RollingEmergencyPassPolicy.PassState.Known
        val cooldown = state.cooldown?.let { ProtectionCooldown(it.operationId, it.usedAtWallEpochMs, it.checkpoint,
            it.remainingAtCheckpointMs, it.wallUncertain) }
        val previousRecovery = ledger.recovery
        val recovery = previousRecovery?.takeIf { state.recovery != null }
        val rebootClosed = previousRecovery != null && trustedElapsedMs(previousRecovery.startedAt, now) == null
        val retained = ledger.operations.filterValues { it.operationId == cooldown?.operationId ||
            trustedElapsedMs(it.acceptedAt, now)?.let { age -> age < REQUEST_LIFETIME_MS } == true }
        return snapshot.copy(focus = snapshot.focus.copy(protection = bound(ledger.copy(checkpoint = now,
            cooldown = cooldown, recovery = recovery, operations = retained,
            previousAccessEnded = ledger.previousAccessEnded || rebootClosed), now)))
    }

    fun preview(snapshot: ProtectionSnapshot, foreignRules: List<BlockRule>, operationId: String = "p1-${UUID.randomUUID()}"): RecoveryPreview? {
        val ledger = snapshot.focus.protection ?: return null
        if (pass.eligibility(passState(ledger), snapshot.observedAt) != RollingEmergencyPassPolicy.Eligibility.Available) return null
        val affected = commitments(snapshot, foreignRules) ?: return null
        if (affected.isEmpty()) return null
        return RecoveryPreview(operationId, snapshot.observedAt, identity(snapshot, foreignRules, affected), affected,
            snapshot.observedAt.wallEpochMs + ProtectionContract.PASS_RECOVERY_MS,
            snapshot.observedAt.wallEpochMs + ProtectionContract.PASS_COOLDOWN_MS)
    }

    fun consume(original: ProtectionSnapshot, preview: RecoveryPreview, foreignRules: List<BlockRule>): ProtectionMutation<RecoveryResult> {
        val snapshot = checkpoint(original)
        val current = snapshot.focus
        val ledger = current.protection ?: return ProtectionMutation(current, RecoveryResult.Unavailable())
        fun unchanged(result: RecoveryResult) = ProtectionMutation(current, result)
        val prior = ledger.operations[preview.operationId]
        if (prior != null) return unchanged(if (prior.issuedAt == preview.issuedAt && prior.scopeIdentity == preview.scopeIdentity)
            RecoveryResult.Accepted(ledger.recovery?.takeIf { it.operationId == prior.operationId }, false) else RecoveryResult.Denied)
        if (!validId(preview.operationId) || trustedElapsedMs(preview.issuedAt, snapshot.observedAt)?.let { it < REQUEST_LIFETIME_MS } != true)
            return unchanged(RecoveryResult.Denied)
        val affected = commitments(snapshot, foreignRules) ?: return unchanged(RecoveryResult.Unavailable())
        if (identity(snapshot, foreignRules, affected) != preview.scopeIdentity || affected != preview.affected)
            return unchanged(RecoveryResult.StalePreview)
        val keys = affected.map { it.causeKey }.toSet()
        val transientIds = affected.filter { it.transient }.mapNotNull { it.ruleId }.toSet()
        val consumed = pass.consume(passState(ledger), snapshot.observedAt, preview.operationId,
            RollingEmergencyPassPolicy.RecoveryScope(keys, transientIds))
        if (consumed !is RollingEmergencyPassPolicy.Consumption.Consumed) return unchanged(RecoveryResult.Denied)
        val now = snapshot.observedAt
        val ruleIds = affected.mapNotNull { it.ruleId }.toSet()
        val recovery = ProtectionRecovery(preview.operationId, now, keys, ruleIds, transientIds, ProtectionContract.PASS_RECOVERY_MS)
        val event = ProtectionLifecycleEvent("pass:${preview.operationId}", preview.operationId, ProtectionLifecycleKind.PASS_COMMITTED,
            ruleIds, now, untilEpochMs = now.wallEpochMs + recovery.durationMs, endedTransientIds = transientIds, revision = current.revision + 1)
        val endedBreaks = current.exemptions.values.filter { it.untilEpochMs > now.wallEpochMs && it.kind != ExemptionKind.EMERGENCY_PASS }.map { receipt ->
            ProtectionLifecycleEvent("break-ended:${receipt.requestId}", preview.operationId, ProtectionLifecycleKind.BREAK_ENDED,
                emptySet(), now, grantId = receipt.requestId, breakEndReason = ProtectionBreakEndReason.RECOVERY, revision = current.revision + 1)
        }
        val nextLedger = bound(ledger.copy(cooldown = ProtectionCooldown(preview.operationId, now.wallEpochMs, now, ProtectionContract.PASS_COOLDOWN_MS),
            recovery = recovery, operations = ledger.operations + (preview.operationId to ProtectionPassReceipt(preview.operationId, preview.issuedAt, now, preview.scopeIdentity)),
            outbox = ledger.outbox + (endedBreaks + event).associateBy { it.id }, lastUsedAtEpochMs = now.wallEpochMs), now)
        val endedKeys = transientIds.map(::ruleCauseKey).toSet()
        val terminal = snapshot.editor.causes.filterKeys { it in endedKeys }.map { (key, cause) -> editorOccurrence(key, cause) }.toSet()
        val editor = snapshot.editor.copy(causes = snapshot.editor.causes - endedKeys, pending = null,
            queued = snapshot.editor.queued.map { if (it.change.keys.any { key -> key in endedKeys }) it.copy(needsReview = true) else it })
        val next = current.copy(protection = nextLedger, emergencyPass = current.emergencyPass.copy(usedAtEpochMs = now.wallEpochMs),
            exemptions = emptyMap(), breakSession = current.breakSession.copy(pending = null),
            breaksByCause = current.breaksByCause.mapValues { it.value.copy(pending = null) })
        return ProtectionMutation(next, RecoveryResult.Accepted(recovery, true), rules = snapshot.rules.filterNot { it.id in transientIds },
            editor = editor, harder = HarderWaitPolicy().withoutTerminated(snapshot.harder.copy(pending = null), terminal))
    }

    fun resume(original: ProtectionSnapshot): ProtectionMutation<Boolean> {
        val snapshot = checkpoint(original)
        val ledger = snapshot.focus.protection ?: return ProtectionMutation(snapshot.focus, false)
        return ProtectionMutation(snapshot.focus.copy(protection = ledger.copy(recovery = null)), true)
    }

    /** Counts and event bytes are bounded even when history is absent or never acknowledges. */
    fun bound(ledger: ProtectionLedger, now: AccessClockStamp): ProtectionLedger {
        val fresh = ledger.outbox.values.filter { trustedElapsedMs(it.at, now)?.let { age -> age <= ProtectionContract.PASS_COOLDOWN_MS } != false }
            .sortedWith(compareBy<ProtectionLifecycleEvent> { it.at.wallEpochMs }.thenBy { it.id }).toMutableList()
        fun bytes(event: ProtectionLifecycleEvent) = 3L * (event.id.length + event.operationId.length +
            event.affectedRuleIds.sumOf { it.length } + event.endedTransientIds.sumOf { it.length } + (event.grantId?.length ?: 0)) + 512L
        var size = fresh.sumOf(::bytes)
        while (fresh.isNotEmpty() && (fresh.size > MAX_EVENTS || size > MAX_EVENT_BYTES)) size -= bytes(fresh.removeAt(0))
        val lost = ledger.outbox.size - fresh.size
        return ledger.copy(outbox = fresh.associateBy { it.id }, droppedEvents = if (ledger.droppedEvents > Long.MAX_VALUE - lost) Long.MAX_VALUE else ledger.droppedEvents + lost)
    }

    private fun commitments(snapshot: ProtectionSnapshot, foreignRules: List<BlockRule>): List<RecoveryCommitment>? {
        val all = mutableListOf<RecoveryCommitment>()
        fun add(key: String, id: String?, name: String, transient: Boolean): Boolean {
            val cause = snapshot.editor.causes[key] ?: return false
            if (cause.occurrenceId != "inactive" && cause.interval?.remainingMs(snapshot.observedAt) != 0L)
                all += RecoveryCommitment(key, id, ProtectionStatus.sanitizeDisplayName(name), transient)
            return true
        }
        for (rule in snapshot.rules.filter { it.enabled }) if (!add(ruleCauseKey(rule.id), rule.id, rule.name, rule.id.startsWith(BlockRule.SESSION_ID_PREFIX))) return null
        for (limit in snapshot.times) if (!add(timeCauseKey(limit.packageName), null, "Time limit · ${limit.packageName}", false)) return null
        for (limit in snapshot.opens) if (!add(openCauseKey(limit.packageName), null, "Open limit · ${limit.packageName}", false)) return null
        for (rule in foreignRules.filter { it.enabled && it.id !in snapshot.rules.map { stored -> stored.id } }) {
            // External transient ownership cannot be terminated in this durable edit.
            if (rule.id.startsWith(BlockRule.SESSION_ID_PREFIX)) return null
            if (foreignActive(rule, snapshot.observedAt, snapshot.editor.timeline))
                all += RecoveryCommitment("foreign:${rule.id}", rule.id, ProtectionStatus.sanitizeDisplayName(rule.name), false)
        }
        return all.sortedBy { it.causeKey }
    }

    private fun foreignActive(rule: BlockRule, now: AccessClockStamp, timeline: AccessClockStamp?): Boolean {
        val logicalWall = timeline?.let { t -> trustedElapsedMs(t, now)?.let { t.wallEpochMs + it } } ?: now.wallEpochMs
        if (rule.expiresAtEpochMs?.let { it <= logicalWall } == true) return false
        val schedule = rule.schedule ?: return true
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = logicalWall }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        var day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val overnight = schedule.startMinuteOfDay > schedule.endMinuteOfDay
        if (overnight && minute < schedule.endMinuteOfDay) day = if (day == 1) 7 else day - 1
        return (if (overnight) minute >= schedule.startMinuteOfDay || minute < schedule.endMinuteOfDay else minute in schedule.startMinuteOfDay until schedule.endMinuteOfDay) &&
            (schedule.daysOfWeek.isEmpty() || day in schedule.daysOfWeek)
    }

    private fun identity(snapshot: ProtectionSnapshot, foreign: List<BlockRule>, affected: List<RecoveryCommitment>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pieces = snapshot.rules.sortedBy { it.id }.map { it.toString() } + snapshot.times.sortedBy { it.packageName }.map { it.toString() } +
            snapshot.opens.sortedBy { it.packageName }.map { it.toString() } + foreign.sortedBy { it.id }.map { it.toString() } +
            affected.map { "${it.causeKey}|${snapshot.editor.causes[it.causeKey]?.occurrenceId}|${it.transient}" }
        for (piece in pieces) { digest.update("${piece.length}:".toByteArray()); digest.update(piece.toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validId(id: String) = id.startsWith("p1-") && runCatching { UUID.fromString(id.removePrefix("p1-")).toString() == id.removePrefix("p1-") }.getOrDefault(false)
}
