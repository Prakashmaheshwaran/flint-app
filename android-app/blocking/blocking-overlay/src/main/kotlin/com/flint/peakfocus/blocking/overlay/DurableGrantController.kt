package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.BreakPolicy
import com.flint.peakfocus.blocking.engine.EmergencyPassPolicy
import com.flint.peakfocus.blocking.engine.OpenLimitPolicy
import com.flint.peakfocus.core.datastore.FocusCommit
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A surface keeps this identity through retries, including a lost acknowledgement. */
data class GrantRequestId internal constructor(val issuedAtEpochMs: Long, val nonce: String, val issuedAtClock: AccessClockStamp? = null) {
    internal val prefix: String get() = "f1:$issuedAtEpochMs:$nonce:"
    companion object {
        fun issue(nowEpochMs: Long): GrantRequestId = GrantRequestId(nowEpochMs, UUID.randomUUID().toString())
        fun issue(now: AccessClockStamp): GrantRequestId = GrantRequestId(now.wallEpochMs, UUID.randomUUID().toString(), now)
    }
}

/** Policy inputs, deliberately independent of today's package-only host behavior. */
sealed interface GrantScope {
    data class Packages(val names: Set<String>) : GrantScope
    data object Global : GrantScope
}

sealed interface GrantResult {
    data class Granted(
        val receipts: List<PersistedExemption>,
        val newlyAccepted: Boolean,
        val atEpochMs: Long,
    ) : GrantResult
    data class Pending(val session: BreakSessionState) : GrantResult
    data object Denied : GrantResult
    data object OpenAllowed : GrantResult
    /** A bounded attempt lost its acknowledgement; retry only with the same identity. */
    data object Unconfirmed : GrantResult
    data class Unavailable(val reason: StorageFailure? = null) : GrantResult
}

internal data class GrantRequest(
    val id: GrantRequestId,
    val packageName: String,
    val causeKey: String,
    val scope: GrantScope,
    val durationMs: Long,
    val level: BreakLevel,
    val emergency: Boolean = false,
    val spendsOpen: Boolean = false,
    val usesDefaultLevel: Boolean = false,
    val notAfterEpochMs: Long? = null,
)

/**
 * Plain-JVM commit boundary. Reducers read current accounting inside the store transaction;
 * neither the process projection nor a successful result changes before edit returns.
 * Cancellations propagate; a later Ready read restores a commit whose acknowledgement was lost.
 */
internal class DurableGrantController(
    private val store: FocusStateStore,
    scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val utcOffsetMs: (Long) -> Long,
    private val bootSessionId: String,
    private val commitTimeoutMs: Long = COMMIT_TIMEOUT_MS,
) {
    private val mutableState = MutableStateFlow<StorageState<FocusSnapshot>>(StorageState.Loading)
    val state: StateFlow<StorageState<FocusSnapshot>> = mutableState
    private val publicationLock = Any()
    @Volatile var lastGood: FocusSnapshot? = null
        private set
    private val breaks = BreakPolicy()
    private val passes = EmergencyPassPolicy()
    private val opens = OpenLimitPolicy()

    init { scope.launch { store.state.collect(::publish) } }

    private fun publish(next: StorageState<FocusSnapshot>) = synchronized(publicationLock) {
        if (next is StorageState.Ready) {
            if (next.value.revision < (lastGood?.revision ?: -1L)) return@synchronized
            lastGood = next.value
            BlockExemptions.restoreCommitted(next.value.exemptions.values, nowEpochMs(), next.value.revision, bootSessionId)
        }
        mutableState.value = next
    }

    suspend fun request(request: GrantRequest, inputsReady: Boolean): GrantResult {
        if (!inputsReady || state.value !is StorageState.Ready) return unavailable()
        // Freeze caller-owned scope sets before the pure reducer can run/retry.
        val targets = when (val target = request.scope) {
            GrantScope.Global -> listOf(BlockExemptions.GLOBAL_PACKAGE)
            is GrantScope.Packages -> target.names.toSortedSet().toList()
        }
        if (targets.isEmpty() || targets.size > MAX_RECEIPTS || targets.any { !validAtom(it) } ||
            (BlockExemptions.GLOBAL_PACKAGE !in targets && request.packageName !in targets) ||
            !validAtom(request.packageName) || !validAtom(request.causeKey) || request.durationMs <= 0 ||
            (request.scope is GrantScope.Packages && BlockExemptions.GLOBAL_PACKAGE in targets)
        ) return GrantResult.Unavailable()
        val now = nowEpochMs()
        val offset = utcOffsetMs(now)
        return commit { current -> reduce(current, request, targets, now, offset) }
    }

    private fun reduce(
        current: FocusSnapshot, request: GrantRequest, targets: List<String>, now: Long, offset: Long,
    ): FocusMutation<GrantResult> {
        fun unchanged(result: GrantResult) = FocusMutation(current, result)
        val level = if (request.usesDefaultLevel) current.defaultBreakLevel else request.level
        if (!request.emergency && level == BreakLevel.HARDCORE) return unchanged(GrantResult.Denied)
        val kind = when {
            request.emergency -> ExemptionKind.EMERGENCY_PASS
            request.spendsOpen -> ExemptionKind.OPEN_LIMIT
            else -> ExemptionKind.BREAK
        }
        val prior = current.exemptions.values.filter { it.requestId.startsWith(request.id.prefix) }
        if (prior.isNotEmpty()) {
            if (prior.map { it.packageName }.toSet() != targets.toSet() || prior.any {
                it.causeKey != request.causeKey || it.kind != kind || it.untilEpochMs <= now || it.bootSessionId != bootSessionId
            }) return unchanged(GrantResult.Denied)
            return unchanged(GrantResult.Granted(prior, false, now))
        }
        if (!fresh(request.id, now, current.minimumRequestEpochMs)) return unchanged(GrantResult.Unavailable())
        // Different callbacks/surfaces may have different tokens for the same accepted exit.
        // An already active exemption covers this request without spending another credit.
        val covering = current.exemptions.values.filter {
            it.untilEpochMs > now && it.bootSessionId == bootSessionId && (!request.emergency || it.kind == ExemptionKind.EMERGENCY_PASS)
        }
        if (targets.all { target -> covering.any { it.packageName == target || it.packageName == BlockExemptions.GLOBAL_PACKAGE } }) {
            return unchanged(GrantResult.Granted(covering, false, now))
        }
        if (current.revision == Long.MAX_VALUE || now < 0 || request.durationMs > Long.MAX_VALUE - now) {
            return unchanged(GrantResult.Unavailable())
        }
        var next = retained(current, now, bootSessionId)
        if (!withinWireBudget(next)) return unchanged(GrantResult.Unavailable())
        // Reclamation is a durable mutation even when the requested scope still cannot fit.
        if (next.exemptions.size + targets.size > MAX_RECEIPTS) return FocusMutation(next, GrantResult.Unavailable())
        val until = minOf(now + request.durationMs, request.notAfterEpochMs ?: Long.MAX_VALUE)
        if (until <= now) return unchanged(GrantResult.Denied)
        if (request.emergency) {
            val consumed = passes.consume(current.emergencyPass, now, offset) ?: return unchanged(GrantResult.Denied)
            next = next.copy(emergencyPass = consumed)
        } else {
            if (current.breakSession.breaksTaken == Int.MAX_VALUE) return unchanged(GrantResult.Unavailable())
            when (val decision = breaks.requestBreak(level, current.breakSession, now)) {
                BreakDecision.DeniedHardcore -> return unchanged(GrantResult.Denied)
                is BreakDecision.Pending -> return FocusMutation(next.copy(breakSession = decision.session), GrantResult.Pending(decision.session))
                is BreakDecision.Granted -> next = next.copy(breakSession = decision.session)
            }
            if (request.spendsOpen) {
                val day = opens.epochDay(now, offset)
                if (opens.opensUsed(current.openCounts, request.packageName, day) == Int.MAX_VALUE) return unchanged(GrantResult.Unavailable())
                next = next.copy(openCounts = opens.recordOpen(current.openCounts, request.packageName, day))
            }
        }
        val receipts = targets.mapIndexed { index, target ->
            val id = request.id.prefix + index
            PersistedExemption(id, target, request.causeKey, kind, until, bootSessionId)
        }
        next = next.copy(exemptions = next.exemptions + receipts.associateBy { it.requestId })
        if (!withinWireBudget(next)) return unchanged(GrantResult.Unavailable())
        return FocusMutation(next, GrantResult.Granted(receipts, true, now))
    }

    /** Normal opens use an inactive receipt: it deduplicates the debit, never exempts a rule. */
    suspend fun recordOpen(id: GrantRequestId, packageName: String, limit: OpenLimit?): GrantResult {
        if (limit == null) return GrantResult.OpenAllowed // unrestricted apps create no accounting traffic
        if (limit.packageName != packageName) return GrantResult.Unavailable()
        if (state.value !is StorageState.Ready) return unavailable()
        val now = nowEpochMs()
        val day = opens.epochDay(now, utcOffsetMs(now))
        return commit { current ->
            val receiptId = id.prefix + "0"
            val previous = current.exemptions[receiptId]
            when {
                !fresh(id, now, current.minimumRequestEpochMs) || !validAtom(packageName) -> FocusMutation(current, GrantResult.Unavailable())
                previous != null && previous.packageName == packageName && previous.causeKey == OPEN_VISIT_CAUSE &&
                    previous.kind == ExemptionKind.OPEN_LIMIT && previous.untilEpochMs == 0L && previous.bootSessionId == bootSessionId ->
                    FocusMutation(current, GrantResult.OpenAllowed)
                previous != null || current.revision == Long.MAX_VALUE -> FocusMutation(current, GrantResult.Unavailable())
                opens.decide(packageName, current.openCounts, listOfNotNull(limit), day) is OpenLimitDecision.Blocked ->
                    FocusMutation(current, GrantResult.Denied)
                opens.opensUsed(current.openCounts, packageName, day) == Int.MAX_VALUE -> FocusMutation(current, GrantResult.Unavailable())
                else -> {
                    val kept = retained(current, now, bootSessionId)
                    if (!withinWireBudget(kept)) FocusMutation(current, GrantResult.Unavailable())
                    else if (kept.exemptions.size >= MAX_RECEIPTS) FocusMutation(kept, GrantResult.Unavailable()) else {
                        val receipt = PersistedExemption(receiptId, packageName, OPEN_VISIT_CAUSE, ExemptionKind.OPEN_LIMIT, 0L, bootSessionId)
                        val next = kept.copy(
                            openCounts = opens.recordOpen(current.openCounts, packageName, day),
                            exemptions = kept.exemptions + (receiptId to receipt),
                        )
                        if (withinWireBudget(next)) FocusMutation(next, GrantResult.OpenAllowed)
                        else FocusMutation(current, GrantResult.Unavailable())
                    }
                }
            }
        }
    }

    private suspend fun commit(reduce: (FocusSnapshot) -> FocusMutation<GrantResult>): GrantResult {
        val committed = try {
            withTimeoutOrNull(commitTimeoutMs) { store.transact(reduce) }
        } catch (_: IOException) { return GrantResult.Unavailable(StorageFailure.IO) }
        return when (committed) {
            is FocusCommit.Committed -> { publish(StorageState.Ready(committed.snapshot)); committed.result }
            is FocusCommit.Unavailable -> GrantResult.Unavailable(committed.reason)
            null -> GrantResult.Unconfirmed
        }
    }

    private fun unavailable() = GrantResult.Unavailable((state.value as? StorageState.Unavailable)?.reason)

    companion object {
        // Bounded retries outlive the bounded transaction. Older callbacks are rejected even
        // after their inactive receipts are pruned; active deadlines are never pruned early.
        const val RETRY_HORIZON_MS = 60 * 60_000L
        const val MAX_RECEIPTS = 1_024
        const val COMMIT_TIMEOUT_MS = 5_000L
        private const val OPEN_VISIT_CAUSE = "foreground-visit"
        private fun validAtom(value: String) = value.isNotBlank() && value.length <= 2_048
        // Conservative escaping budget also covers valid older records with longer atoms.
        // Refuse before invoking the codec's hard bound, rather than throwing from a UI job.
        private fun withinWireBudget(snapshot: FocusSnapshot): Boolean =
            snapshot.exemptions.values.sumOf { 3L * (it.requestId.length + it.packageName.length + it.causeKey.length + (it.bootSessionId?.length ?: 1)) + 100L } +
                snapshot.breaksByCause.keys.sumOf { 3L * it.length + 256L } < 4_000_000L
        private fun fresh(id: GrantRequestId, now: Long, floor: Long): Boolean =
            id.issuedAtEpochMs >= floor && id.issuedAtEpochMs <= now && now - id.issuedAtEpochMs < RETRY_HORIZON_MS &&
                runCatching { UUID.fromString(id.nonce).toString() == id.nonce }.getOrDefault(false)

        private fun retained(current: FocusSnapshot, now: Long, boot: String): FocusSnapshot {
            val floor = maxOf(current.minimumRequestEpochMs, (now - RETRY_HORIZON_MS + 1).coerceAtLeast(0))
            return current.copy(minimumRequestEpochMs = floor, exemptions = current.exemptions.filterValues { receipt ->
                if (receipt.untilEpochMs > now && receipt.bootSessionId == boot) true else {
                    val fields = receipt.requestId.split(':')
                    val issued = fields.getOrNull(1)?.toLongOrNull()
                    fields.size == 4 && fields[0] == "f1" && issued != null &&
                        issued >= floor
                }
            })
        }
    }
}
