package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import java.time.Instant
import java.time.ZoneId

/** One installation quota period, shared by every target and both limit kinds (§5/§9). */
data class OpenCreditLedger(
    val quotaZone: String,
    val periodId: Long,
    val reset: OpenCreditPolicy.ResetClock,
    /** Wall timeline advances by elapsed time, never by a same-boot wall-clock edit. */
    val logicalCheckpoint: AccessClockStamp,
    val observedAt: AccessClockStamp,
    val timeUncertain: Boolean = false,
    val counters: Map<String, OpenCreditPolicy.Counter> = emptyMap(),
    val active: Map<String, OpenCreditAccess> = emptyMap(),
    val accessEndedAfterRestart: Boolean = false,
)

data class OpenCreditAccess(val grant: OpenCreditPolicy.Grant, val interval: AnchoredInterval)

data class OpenCreditReconciliation(
    val next: OpenCreditLedger,
    val periodChanged: Boolean,
    val endedOperationIds: Set<String>,
)

data class OpenCreditMutation(
    val next: OpenCreditLedger,
    val acceptance: OpenCreditPolicy.Acceptance,
    val periodChanged: Boolean,
    val endedOperationIds: Set<String>,
)

/** Pure transitions; the caller persists the result with its receipt in one serialized edit. */
object OpenCreditLedgerPolicy {
    private val policy = OpenCreditPolicy()
    const val MAX_ROWS = 9_999 // Plus the wire header: at most 10000 lines.
    const val MAX_ATOM = 2_048
    const val MAX_WALL_MS = 253_402_300_799_999L

    fun quotaZone(value: String): ZoneId {
        require(value in ZoneId.getAvailableZoneIds()) { "Unknown quota zone" }
        return ZoneId.of(value)
    }

    fun epochDay(wallMs: Long, zone: String): Long =
        Instant.ofEpochMilli(wallMs).atZone(quotaZone(zone)).toLocalDate().toEpochDay()

    fun initial(now: AccessClockStamp, zone: String, counts: Map<String, Int> = emptyMap(),
                periodId: Long = epochDay(now.wallEpochMs, zone)): OpenCreditLedger {
        requireStamp(now)
        val reset = OpenCreditPolicy.ResetClock(now, nextMidnightMs(now.wallEpochMs, zone))
        return OpenCreditLedger(zone, periodId, reset, now, now, counters = counts.mapValues { (target, used) ->
            OpenCreditPolicy.Counter(target, periodId, used, reset)
        }).also(::requireValid)
    }

    /** At most one rollover, even after many missed wall midnights. */
    fun reconcile(value: OpenCreditLedger, now: AccessClockStamp): OpenCreditReconciliation {
        requireValid(value); requireStamp(now)
        val elapsed = trustedElapsedMs(value.observedAt, now)
        val wallProgress = now.wallEpochMs - value.observedAt.wallEpochMs
        val uncertain = elapsed == null || elapsed > MAX_WALL_MS ||
            kotlin.math.abs(wallProgress - elapsed) > ProtectionContract.CLOCK_DISCONTINUITY_MS
        val logicalWall = if (elapsed == null) now.wallEpochMs else
            Math.addExact(value.logicalCheckpoint.wallEpochMs, elapsed).also { require(it <= MAX_WALL_MS) }
        val logical = now.copy(wallEpochMs = logicalWall)
        val remaining = policy.remainingToResetMs(value.reset, now)
        val nextReset = if (remaining == 0L) nextMidnightMs(logicalWall, value.quotaZone) else 0L
        val rolled = remaining == 0L && nextReset > 0L && value.periodId < Long.MAX_VALUE
        val period = if (rolled) value.periodId + 1 else value.periodId
        val reset = OpenCreditPolicy.ResetClock(now, if (rolled) nextReset else remaining)
        val counters = value.counters.mapValues { (_, counter) ->
            if (rolled) policy.rolled(counter, now, nextReset)
            else counter.copy(reset = reset)
        }
        // An ordinary credit never uses AnchoredInterval's finite-restriction reboot fallback.
        val active = if (rolled || elapsed == null) emptyMap() else value.active.filterValues {
            policy.remainingMs(it.grant, now) > 0L && remaining > 0L
        }
        val ended = value.active.values.filter { it.grant.target !in active }.map { it.grant.operationId }.toSet()
        val next = value.copy(periodId = period, reset = reset, logicalCheckpoint = logical, observedAt = now,
            timeUncertain = value.timeUncertain || uncertain, counters = counters, active = active,
            accessEndedAfterRestart = value.accessEndedAfterRestart || (elapsed == null && value.active.isNotEmpty()))
        requireValid(next)
        return OpenCreditReconciliation(next, rolled, ended)
    }

    fun acceptOpenCredit(
        ledger: OpenCreditLedger,
        target: String,
        operationId: String,
        caps: List<Int>,
        otherBlockingCauses: List<String>,
        onSafetyFloor: Boolean,
        now: AccessClockStamp,
    ): OpenCreditMutation {
        requireAtom(target); requireAtom(operationId)
        require(caps.all { it >= 0 })
        val reconciled = reconcile(ledger, now)
        var next = reconciled.next
        val rebound = next.counters.any { (owner, counter) -> owner != target &&
            (operationId in counter.consumedOperationIds || operationId in counter.previousPeriodOperationIds) }
        val counter = next.counters[target] ?: OpenCreditPolicy.Counter(target, next.periodId, 0, next.reset)
        val result = if (rebound || now.wallEpochMs > MAX_WALL_MS - ProtectionContract.OPEN_CREDIT_MS) {
            OpenCreditPolicy.Acceptance.Denied(OpenCreditPolicy.Denial.UNAVAILABLE)
        } else policy.accept(target, operationId, caps, counter, next.active[target]?.grant,
            otherBlockingCauses, onSafetyFloor, now,
            // reconcile already applied this boundary. A zero/unknown boundary cannot roll again.
            msUntilNextQuotaMidnight = 0L)
        if (result is OpenCreditPolicy.Acceptance.Accepted) {
            val grant = result.grant
            val access = OpenCreditAccess(grant, AnchoredInterval(grant.startedAt, grant.durationMs,
                grant.startedAt.wallEpochMs + grant.durationMs))
            next = next.copy(counters = next.counters + (target to result.counter), active = next.active + (target to access))
            requireValid(next)
        }
        return OpenCreditMutation(next, result, reconciled.periodChanged, reconciled.endedOperationIds)
    }

    /** A display query never spends, creates a target record, or invents a missing ledger. */
    fun remainingCredits(value: OpenCreditLedger, target: String, caps: List<Int>): Int? =
        caps.minOrNull()?.let { (it.toLong() - (value.counters[target]?.used ?: 0)).coerceAtLeast(0).toInt() }

    fun requireValid(value: OpenCreditLedger) {
        quotaZone(value.quotaZone)
        require(value.periodId >= 0)
        requireStamp(value.observedAt); requireStamp(value.logicalCheckpoint); requireStamp(value.reset.checkpoint)
        require(value.reset.checkpoint == value.observedAt)
        require(value.logicalCheckpoint.elapsedRealtimeMs == value.observedAt.elapsedRealtimeMs &&
            value.logicalCheckpoint.bootSessionId == value.observedAt.bootSessionId)
        require(value.reset.remainingToResetMs in 0..ProtectionContract.MAX_OCCURRENCE_MS)
        require(value.counters.size + value.active.size <= MAX_ROWS)
        val ids = mutableSetOf<String>()
        var rows = 0
        value.counters.forEach { (target, counter) ->
            requireAtom(target)
            require(counter.target == target && counter.periodId == value.periodId && counter.reset == value.reset && counter.used >= 0)
            require(counter.consumedOperationIds.size <= counter.used)
            (counter.consumedOperationIds + counter.previousPeriodOperationIds).forEach { id -> requireAtom(id); require(ids.add(id)) }
            require(counter.consumedOperationIds.intersect(counter.previousPeriodOperationIds).isEmpty())
            rows += 1 + counter.consumedOperationIds.size + counter.previousPeriodOperationIds.size
            require(rows <= MAX_ROWS)
        }
        value.active.forEach { (target, access) ->
            val grant = access.grant
            val counter = value.counters[target]
            require(grant.target == target && counter != null && grant.operationId in counter.consumedOperationIds)
            requireStamp(grant.startedAt); requireAtom(grant.operationId)
            require(grant.durationMs in 1..ProtectionContract.OPEN_CREDIT_MS)
            require(grant.startedAt.wallEpochMs <= MAX_WALL_MS - grant.durationMs)
            require(access.interval == AnchoredInterval(grant.startedAt, grant.durationMs, grant.startedAt.wallEpochMs + grant.durationMs))
            require(trustedElapsedMs(grant.startedAt, value.observedAt) != null)
            require(policy.remainingMs(grant, value.observedAt) <= value.reset.remainingToResetMs)
        }
        require(rows + value.active.size <= MAX_ROWS)
    }

    private fun nextMidnightMs(wallMs: Long, zone: String): Long {
        val zoned = Instant.ofEpochMilli(wallMs).atZone(quotaZone(zone))
        val end = zoned.toLocalDate().plusDays(1).atStartOfDay(zoned.zone).toInstant().toEpochMilli()
        return (end - wallMs).takeIf { it in 1..ProtectionContract.MAX_OCCURRENCE_MS } ?: 0L
    }

    private fun requireStamp(value: AccessClockStamp) {
        require(value.wallEpochMs in 0..MAX_WALL_MS && value.elapsedRealtimeMs >= 0)
        requireAtom(value.bootSessionId)
    }

    private fun requireAtom(value: String) { require(value.isNotBlank() && value.length <= MAX_ATOM) }
}
