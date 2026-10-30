package com.flint.peakfocus.blocking.overlay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class TrustedTimeLimitReaderTest {
    private class Disk : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        var fail = false
        private val mutex = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            if (fail) throw IOException("synthetic disk failure")
            transform(values.value).also { values.value = it }
        }
    }
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private var now = AccessClockStamp(base, 1_000, "boot")
    private var zone = "UTC"
    private fun store(disk: Disk) = DataStoreTimeLimitUsageStore(disk, { now }, { zone })
    private fun reader(disk: Disk, query: (String, Long, Long) -> Long?) = store(disk).let { store ->
        TrustedTimeLimitReader({ pkg, at -> store.measurementContext(pkg, at) }, store::record, query, { now })
    }

    @Test fun PC_54_PC_56_bothReadersRetainExhaustionThroughRawMidnightTravelAndRecreation() = runBlocking {
        val disk = Disk()
        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit("app", 1, BreakLevel.HARDER, "UTC"))
        val pathA = reader(disk) { _, _, _ -> 60_000 }
        val first = pathA.read("app", 1)
        assertTrue(first.spent)
        now = AccessClockStamp(base + 172_800_000, 2_000, "boot")
        zone = "Pacific/Auckland"
        val pathB = reader(disk) { _, _, _ -> 0 }
        val second = pathB.read("app", 1)
        assertTrue(second.spent)
        assertEquals(first.day!!.periodId, second.day!!.periodId)
        assertEquals("UTC", second.day!!.zoneId)
        assertEquals(3_599_000L, second.resetRemainingMs(now))
        assertTrue(reader(disk) { _, _, _ -> null }.read("app", 1).spent)
    }

    @Test fun PC_60_missingAccessFailedQueriesAndRealZeroStayDistinctWithoutArtificialExhaustion() = runBlocking {
        val disk = Disk()
        var sample: Long? = null
        val read = reader(disk) { _, _, _ -> sample }
        val missing = read.read("app", 1)
        assertNull(missing.remainingMs)
        assertEquals(TrustedTimeLimitBudget.Measurement.ACCESS_MISSING, missing.measurement)
        sample = Long.MAX_VALUE
        assertNull(read.read("app", 1).remainingMs)
        sample = 0
        assertEquals(60_000L, read.read("app", 1).remainingMs)
        sample = null
        assertEquals(60_000L, read.read("app", 1).remainingMs) // Retain known balance with a warning.
        sample = 60_000
        assertTrue(read.read("app", 1).spent)
        sample = Long.MAX_VALUE
        val failure = read.read("app", 1)
        assertTrue(failure.spent)
        assertEquals(60_000L, failure.day!!.usedMs)
        assertEquals(TrustedTimeLimitBudget.Measurement.QUERY_FAILED, failure.measurement)
    }

    @Test fun PC_60_queryExceptionDoesNotMasqueradeAsStorageFailureOrSpendAMaxSentinel() = runBlocking {
        val result = reader(Disk()) { _, _, _ -> throw IllegalStateException("synthetic OS failure") }.read("app", 1)
        assertEquals(TrustedTimeLimitBudget.Measurement.QUERY_FAILED, result.measurement)
        assertNull(result.remainingMs)
        assertEquals(0L, result.day!!.usedMs)
    }

    @Test fun PC_60_failedCommitCannotRefundLastAcknowledgedExhaustion() = runBlocking {
        val disk = Disk()
        val read = reader(disk) { _, _, _ -> 60_000 }
        assertTrue(read.read("app", 1).spent)
        disk.fail = true
        val failed = read.read("app", 1)
        assertTrue(failed.spent)
        assertEquals(TrustedTimeLimitBudget.Measurement.STORAGE_UNAVAILABLE, failed.measurement)
        // A new process without any acknowledged floor cannot invent an exhausted budget.
        assertNull(reader(disk) { _, _, _ -> 60_000 }.read("app", 1).remainingMs)
    }

    @Test fun PC_54_PC_60_queryWhoseTrustedPeriodEndsBeforeCommitCannotChargeTheNewPeriod() = runBlocking {
        val disk = Disk()
        val read = reader(disk) { _, _, _ ->
            now = now.copy(wallEpochMs = base + 3_600_000, elapsedRealtimeMs = 3_601_000)
            60_000
        }
        val result = read.read("app", 1)
        assertFalse(result.spent)
        assertNull(result.remainingMs)
        assertEquals(0L, result.day!!.usedMs)
        assertEquals(TrustedTimeLimitBudget.Measurement.QUERY_FAILED, result.measurement)
    }

    @Test fun PC_60_recreatedReaderRetainsLoadedExhaustionWhenOnlyItsSampleCommitFails() = runBlocking {
        val disk = Disk()
        assertTrue(reader(disk) { _, _, _ -> 60_000 }.read("app", 1).spent)
        val recreated = reader(disk) { _, _, _ -> disk.fail = true; 0 }
        val result = recreated.read("app", 1)
        assertTrue(result.spent)
        assertEquals(TrustedTimeLimitBudget.Measurement.STORAGE_UNAVAILABLE, result.measurement)
        assertEquals(60_000L, result.day!!.usedMs)
    }

    @Test fun PC_60_zeroBudgetHasACommittedResetWithoutAnOsQuery() = runBlocking {
        val result = reader(Disk()) { _, _, _ -> error("zero needs no usage access") }.read("app", 0)
        assertTrue(result.spent)
        assertEquals(3_600_000L, result.resetRemainingMs(now))
        assertEquals(TrustedTimeLimitBudget.Measurement.NOT_NEEDED, result.measurement)
    }

    @Test fun PC_60_acceptedSampleHasMeasurementEvidenceWhileEarlierHistoryMetadataRemainsUncertain() = runBlocking {
        var day = TimeLimitDayAccounting.initial(now, "UTC").copy(accountingUnknownUntilReset = true)
        var total = 0L
        val read = TrustedTimeLimitReader(
            { pkg, at -> TimeLimitMeasurementContext(pkg, day.periodId, day.zoneId, base - 23 * 3_600_000, at) },
            { ticket, sample, at ->
                val result = TimeLimitDayAccounting.record(day, ticket.periodId,
                    TimeLimitUsageSample(ticket.windowStartEpochMs, sample!!, ticket.observedAt), at)
                day = result.state
                TimeLimitUsageObservation(day, result.sampleAccepted)
            }, { _, _, _ -> total }, { now })
        val unknown = read.read("app", 1)
        assertEquals(60_000L, unknown.remainingMs)
        assertEquals(TrustedTimeLimitBudget.Measurement.OBSERVED, unknown.measurement)
        assertTrue(unknown.day!!.accountingUnknownUntilReset)
        total = 60_000
        assertTrue(read.read("app", 1).spent)
    }

    @Test fun cancellationNeverReturnsAnAcknowledgedFallback() = runBlocking {
        try {
            reader(Disk()) { _, _, _ -> throw CancellationException("cancelled detector") }.read("app", 1)
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
