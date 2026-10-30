package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import java.time.Instant
import java.time.ZoneId

/** An OS measurement window is evidence of usage, never authority to refill a Time Limit. */
data class TimeLimitUsageSample(val windowStartEpochMs: Long, val totalMs: Long, val observedAt: AccessClockStamp)

/** Durable per-target day, including the sample baseline retained across a trusted rollover. */
data class TimeLimitDayState(
    val zoneId: String,
    val periodId: Long,
    val reset: OpenCreditPolicy.ResetClock,
    val logicalCheckpoint: AccessClockStamp,
    val observedAt: AccessClockStamp,
    val usedMs: Long = 0,
    val lastSample: TimeLimitUsageSample? = null,
    val timeUncertain: Boolean = false,
    /** Upgrade found an anchored old period whose earlier OS usage cannot be reconstructed. */
    val accountingUnknownUntilReset: Boolean = false,
    /** Independent floors; null preserves source compatibility for pre-publication fixtures. */
    val osUsedMs: Long? = null,
    val observedUsedMs: Long = 0,
)

data class TimeLimitDayResult(val state: TimeLimitDayState, val rolled: Boolean, val sampleAccepted: Boolean = false)

/** Pure shared authority; callers must serialize and persist the returned state before enforcing it. */
object TimeLimitDayAccounting {
    private val resetPolicy = OpenCreditPolicy()

    fun initial(now: AccessClockStamp, zoneId: String): TimeLimitDayState {
        requireStamp(now)
        val zone = OpenCreditLedgerPolicy.quotaZone(zoneId)
        return TimeLimitDayState(zoneId, Instant.ofEpochMilli(now.wallEpochMs).atZone(zone).toLocalDate().toEpochDay(),
            OpenCreditPolicy.ResetClock(now, nextMidnight(now.wallEpochMs, zone)), now, now).also(::requireValid)
    }

    /** Same boot: elapsed proof only. Reboot: resume the entire saved reset remainder, never mint quota. */
    fun reconcile(current: TimeLimitDayState, now: AccessClockStamp,
        zoneForNextPeriod: String = current.zoneId): TimeLimitDayResult {
        requireValid(current); requireStamp(now)
        val nextZone = OpenCreditLedgerPolicy.quotaZone(zoneForNextPeriod)
        val elapsed = trustedElapsedMs(current.observedAt, now)
        val logicalWall = if (elapsed == null) now.wallEpochMs else Math.addExact(current.logicalCheckpoint.wallEpochMs, elapsed)
        require(logicalWall <= OpenCreditLedgerPolicy.MAX_WALL_MS)
        val remaining = resetPolicy.remainingToResetMs(current.reset, now)
        val rolled = remaining == 0L
        val next = current.copy(
            zoneId = if (rolled) zoneForNextPeriod else current.zoneId,
            periodId = if (rolled) Math.addExact(current.periodId, 1) else current.periodId,
            reset = OpenCreditPolicy.ResetClock(now, if (rolled) nextMidnight(logicalWall, nextZone) else remaining),
            logicalCheckpoint = now.copy(wallEpochMs = logicalWall), observedAt = now,
            usedMs = if (rolled) 0 else current.usedMs,
            osUsedMs = if (rolled) 0 else current.osUsedMs,
            observedUsedMs = if (rolled) 0 else current.observedUsedMs,
            // Missing history is a coverage warning. Clock/boot changes never disable a
            // known balance or stop subsequently observed foreground time from spending it.
            accountingUnknownUntilReset = !rolled && current.accountingUnknownUntilReset,
            timeUncertain = (!rolled && current.timeUncertain) || elapsed == null || clockDiscontinuity(current.observedAt, now) ||
                kotlin.math.abs(logicalWall - now.wallEpochMs) > ProtectionContract.CLOCK_DISCONTINUITY_MS,
        )
        requireValid(next)
        return TimeLimitDayResult(next, rolled)
    }

    /**
     * A delayed response cannot charge/refund another trusted period. New raw OS windows and
     * clock discontinuities begin additive sample segments instead of replacing consumed usage.
     * A missing measurement is not zero and never poisons the persisted floor with MAX_VALUE.
     */
    fun record(current: TimeLimitDayState, requestedPeriodId: Long, sample: TimeLimitUsageSample?,
        now: AccessClockStamp, zoneForNextPeriod: String = current.zoneId): TimeLimitDayResult {
        val reconciled = reconcile(current, now, zoneForNextPeriod)
        val next = reconciled.state
        if (sample == null || requestedPeriodId != next.periodId) return reconciled
        requireSample(sample)
        if (trustedElapsedMs(sample.observedAt, now) == null) return reconciled
        val previous = next.lastSample
        if (previous != null && previous.observedAt.bootSessionId == sample.observedAt.bootSessionId &&
            trustedElapsedMs(previous.observedAt, sample.observedAt) == null) return reconciled
        // Exact retries (including a duplicate after rollover) cannot consume twice.
        if (sample == previous) return reconciled.copy(sampleAccepted = true)
        val newSegment = previous == null || previous.windowStartEpochMs != sample.windowStartEpochMs ||
            previous.observedAt.bootSessionId != sample.observedAt.bootSessionId ||
            clockDiscontinuity(previous.observedAt, sample.observedAt)
        val oldOs = next.osUsedMs ?: next.usedMs
        // A changed raw window can overlap the old one after a clock change/reboot. Its
        // first total is another floor, not an additive charge for the same historic use.
        val os = if (newSegment) maxOf(oldOs, sample.totalMs)
            else saturatingAdd(oldOs, (sample.totalMs - previous!!.totalMs).coerceAtLeast(0))
        val baseline = if (newSegment) sample else sample.copy(totalMs = maxOf(sample.totalMs, previous!!.totalMs))
        val committed = next.copy(usedMs = maxOf(os, next.observedUsedMs), osUsedMs = os, lastSample = baseline)
        requireValid(committed)
        return TimeLimitDayResult(committed, reconciled.rolled, true)
    }

    /** Null is unmeasured positive budget, not known unused quota. Evidenced exhaustion stays zero. */
    fun remainingMs(state: TimeLimitDayState, dailyMinutes: Int): Long? {
        // Existing imported budgets accept every nonnegative Int; the editor separately
        // bounds new drafts. Accounting must not make an old valid record unreadable.
        require(dailyMinutes >= 0)
        val remaining = (dailyMinutes * 60_000L - state.usedMs).coerceAtLeast(0)
        return when {
            remaining == 0L -> 0L
            state.lastSample == null && state.observedUsedMs == 0L -> null
            else -> remaining
        }
    }

    /** A serialized, continuously observed foreground span supplies independent elapsed proof. */
    fun observe(current: TimeLimitDayState, elapsedMs: Long): TimeLimitDayState {
        requireValid(current); require(elapsedMs >= 0)
        val os = current.osUsedMs ?: current.usedMs
        val observed = saturatingAdd(current.observedUsedMs, elapsedMs)
        return current.copy(usedMs = maxOf(os, observed), osUsedMs = os, observedUsedMs = observed).also(::requireValid)
    }

    /** Align the elapsed floor at an OS observation, without adding the two channels. */
    fun alignObserved(current: TimeLimitDayState, observedAfterSampleMs: Long): TimeLimitDayState {
        requireValid(current); require(observedAfterSampleMs >= 0)
        val os = current.osUsedMs ?: current.usedMs
        val observed = maxOf(current.observedUsedMs, saturatingAdd(os, observedAfterSampleMs))
        return current.copy(usedMs = maxOf(os, observed), osUsedMs = os, observedUsedMs = observed).also(::requireValid)
    }

    fun requireValid(state: TimeLimitDayState) {
        OpenCreditLedgerPolicy.quotaZone(state.zoneId)
        require(state.periodId >= 0 && state.usedMs >= 0 && state.observedUsedMs >= 0)
        state.osUsedMs?.let { require(it >= 0 && state.usedMs == maxOf(it, state.observedUsedMs)) }
        requireStamp(state.observedAt); requireStamp(state.logicalCheckpoint); requireStamp(state.reset.checkpoint)
        require(state.reset.checkpoint == state.observedAt)
        require(state.logicalCheckpoint.elapsedRealtimeMs == state.observedAt.elapsedRealtimeMs &&
            state.logicalCheckpoint.bootSessionId == state.observedAt.bootSessionId)
        require(state.reset.remainingToResetMs in 1..ProtectionContract.MAX_OCCURRENCE_MS)
        state.lastSample?.let(::requireSample)
    }

    private fun saturatingAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

    private fun nextMidnight(wallMs: Long, zone: ZoneId): Long {
        val local = Instant.ofEpochMilli(wallMs).atZone(zone)
        val remaining = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - wallMs
        require(remaining in 1..ProtectionContract.MAX_OCCURRENCE_MS)
        return remaining
    }

    private fun requireSample(sample: TimeLimitUsageSample) {
        require(sample.windowStartEpochMs in 0..OpenCreditLedgerPolicy.MAX_WALL_MS && sample.totalMs >= 0)
        requireStamp(sample.observedAt)
        require(sample.windowStartEpochMs <= sample.observedAt.wallEpochMs)
    }

    private fun requireStamp(stamp: AccessClockStamp) {
        require(stamp.wallEpochMs in 0..OpenCreditLedgerPolicy.MAX_WALL_MS && stamp.elapsedRealtimeMs >= 0)
        require(stamp.bootSessionId.isNotBlank() && stamp.bootSessionId.length <= OpenCreditLedgerPolicy.MAX_ATOM)
    }
}
