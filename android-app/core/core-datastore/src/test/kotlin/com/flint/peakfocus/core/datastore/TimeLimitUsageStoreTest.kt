package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class TimeLimitUsageStoreTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var fail = false
        var lostAcknowledgement = false
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            val next = transform(values.value)
            if (fail) throw IOException("synthetic full disk")
            values.value = next
            if (lostAcknowledgement) throw CancellationException("committed without acknowledgement")
            next
        }
    }
    private val hour = 3_600_000L
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(base + wall, 10_000 + ms, boot)
    private fun legacy() = preferencesOf(PrefsKeys.TIME_LIMITS to "app~1~HARDCORE\nother~30~HARDER",
        PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDCORE", PrefsKeys.APP_GROUPS to "unchanged groups")
    private fun store(disk: Disk, zone: () -> String = { "UTC" }) = DataStoreTimeLimitUsageStore(disk, deviceZone = zone)
    private suspend fun spent(disk: Disk): TimeLimitMeasurementContext {
        val store = store(disk)
        val context = store.measurementContext("app", at())
        assertTrue(store.record(context, 60_000, at()).sampleAccepted)
        return context
    }

    @Test fun PC_56_PC_70_oneMigrationCapturesEveryLegacyZoneAndPreservesOriginalBytes() = runBlocking {
        val disk = Disk(legacy())
        val original = disk.values.value.asMap().toMap()
        var zone = "Asia/Tokyo"
        val first = store(disk) { zone }
        val context = first.measurementContext("app", at())
        assertEquals("Asia/Tokyo", context.zoneId)
        assertEquals(setOf("Asia/Tokyo"), readTimeLimitUsage(disk.values.value).targets.values.map { it.zoneId }.toSet())
        assertEquals(original.filterKeys { it != PrefsKeys.TIME_LIMITS },
            disk.values.value.asMap().filterKeys { it in original && it != PrefsKeys.TIME_LIMITS })
        assertEquals(setOf("Asia/Tokyo"), StrictPrefsCodec.timeLimits(disk.values.value[PrefsKeys.TIME_LIMITS]!!).map { it.zoneId }.toSet())
        assertEquals("app~1~HARDCORE\nother~30~HARDER" to "HARDCORE",
            TimeLimitUsageCodec.readArchive(disk.values.value[TIME_USAGE_ARCHIVE]!!))
        zone = "Pacific/Auckland"
        assertEquals("Asia/Tokyo", first.measurementContext("other", at()).zoneId)
        assertEquals("Asia/Tokyo", store(disk) { zone }.measurementContext("app", at()).zoneId)
        assertEquals(1L, disk.values.value[TIME_USAGE_SCHEMA])
    }

    @Test fun PC_54_exhaustionSurvivesWallMidnightAndStoreRecreationAcrossBothReaders() = runBlocking {
        val disk = Disk(legacy()); val old = spent(disk)
        val jumped = at(1_000, 48 * hour)
        val second = store(disk)
        val context = second.measurementContext("app", jumped)
        assertEquals(old.periodId, context.periodId)
        val result = second.record(context, 0, jumped)
        assertEquals(60_000L, result.state.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(result.state, 1))
        val again = store(disk).measurementContext("app", jumped)
        assertEquals(context, again)
    }

    @Test fun PC_54_PC_55_lowerUnavailableAndDuplicateReportsCannotRefundOrInventQuota() = runBlocking {
        val disk = Disk(legacy()); val context = spent(disk)
        val reader = store(disk)
        for (sample in listOf(60_000L, 0L, null, Long.MAX_VALUE)) {
            val result = reader.record(context, sample, at())
            assertEquals(60_000L, result.state.usedMs)
            assertEquals(sample != null && sample != Long.MAX_VALUE, result.sampleAccepted)
        }
    }

    @Test fun PC_54_competingDetectorReportsSerializeWithoutDoubleCounting() = runBlocking {
        val disk = Disk(legacy())
        val a = store(disk); val b = store(disk)
        val one = a.measurementContext("app", at())
        val two = b.measurementContext("app", at())
        coroutineScope {
            awaitAll(async { a.record(one, 90_000, at()) }, async { b.record(two, 60_000, at()) })
        }
        assertEquals(90_000L, readTimeLimitUsage(disk.values.value).targets.getValue("app").usedMs)
    }

    @Test fun PC_36_PC_56_offDeleteRecreateRetainsTargetUsageAndOriginalSavedZone() = runBlocking {
        val disk = Disk(legacy()); spent(disk)
        DataStoreLimitsStore(disk).clearTimeLimit("app")
        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit("app", 1, BreakLevel.HARDCORE, "UTC"))
        val reader = store(disk) { "Pacific/Auckland" }
        val context = reader.measurementContext("app", at(1_000), savedZoneForNewTarget = "Pacific/Auckland")
        assertEquals("UTC", context.zoneId)
        assertEquals(60_000L, reader.record(context, 60_000, at(1_000)).state.usedMs)
    }

    @Test fun PC_60_rebootCannotRefillFromAChangedWallDate() = runBlocking {
        val disk = Disk(legacy()); val old = spent(disk)
        val reboot = at(5, 7 * 24 * hour, "new-boot")
        val reader = store(disk)
        val context = reader.measurementContext("app", reboot)
        assertEquals(old.periodId, context.periodId)
        val result = reader.record(context, 0, reboot)
        assertEquals(60_000L, result.state.usedMs)
        assertEquals(hour, result.state.reset.remainingToResetMs)
        assertTrue(result.state.timeUncertain)
    }

    @Test fun PC_54_delayedMeasurementCannotChargeTheNextTrustedPeriod() = runBlocking {
        val disk = Disk(legacy()); val context = spent(disk)
        val end = at(hour, 48 * hour)
        val reader = store(disk)
        val result = reader.record(context, 120_000, end)
        assertFalse(result.sampleAccepted)
        assertEquals(context.periodId + 1, result.state.periodId)
        assertEquals(0L, result.state.usedMs)
    }

    @Test fun PC_37_PC_70_missingEstablishedKeysOrPartialInitializationNeverMintFreshState() = runBlocking {
        for (key in listOf(TIME_USAGE_SCHEMA, TIME_USAGE_STATE, TIME_USAGE_ARCHIVE, TIME_LIMIT_ZONE_SCHEMA)) {
            val valid = Disk(legacy()); spent(valid)
            val broken = valid.values.value.toMutablePreferences().also { it.remove(key) }
            val disk = Disk(broken)
            expectUnavailable(disk)
            assertEquals(broken, disk.values.value)
        }
    }

    @Test fun PC_37_PC_71_futureDuplicateMissingAndMalformedRowsStayUnavailable() = runBlocking {
        val valid = Disk(legacy()); spent(valid)
        val encoded = valid.values.value[TIME_USAGE_STATE]!!
        val row = encoded.lines().first { it.startsWith("R|") }
        val sample = encoded.lines().first { it.startsWith("S|") }
        for (broken in listOf(encoded.replaceFirst("T|1", "T|2"), encoded + "\n" + row,
            encoded.lines().filterNot { it == sample }.joinToString("\n"),
            encoded.lines().filterNot { it == row }.joinToString("\n"), "T|1\nM|UTC|0\nR|bad")) {
            val raw = valid.values.value.toMutablePreferences().also { it[TIME_USAGE_STATE] = broken }
            val disk = Disk(raw)
            expectUnavailable(disk)
            assertEquals(raw, disk.values.value)
        }
    }

    @Test fun PC_70_failedFirstMigrationPreservesBytesAndRetryCapturesOneZone() = runBlocking {
        val disk = Disk(legacy()); val original = disk.values.value
        disk.fail = true
        try { store(disk).measurementContext("app", at()); fail("failed write accepted") } catch (_: IOException) { }
        assertEquals(original, disk.values.value)
        disk.fail = false
        val reader = store(disk) { "America/New_York" }
        assertEquals("America/New_York", reader.measurementContext("app", at()).zoneId)
        assertEquals(original[PrefsKeys.TIME_LIMITS], TimeLimitUsageCodec.readArchive(disk.values.value[TIME_USAGE_ARCHIVE]!!).first)
    }

    @Test fun PC_70_lostAcknowledgementRetainsCommittedUsageAndRetryCannotSpendTwice() = runBlocking {
        val disk = Disk(legacy()); val reader = store(disk)
        val context = reader.measurementContext("app", at())
        disk.lostAcknowledgement = true
        try { reader.record(context, 60_000, at()); fail("lost acknowledgement returned") } catch (_: CancellationException) { }
        disk.lostAcknowledgement = false
        val retried = store(disk).record(context, 60_000, at())
        assertEquals(60_000L, retried.state.usedMs)
    }

    @Test fun PC_56_PC_71_deletingAnEstablishedSavedZoneCannotRecaptureTheTravelZone() = runBlocking {
        val disk = Disk(legacy()); spent(disk)
        val broken = disk.values.value.toMutablePreferences().also {
            it[PrefsKeys.TIME_LIMITS] = "app~1~HARDCORE\nother~30~HARDER"
        }
        disk.values.value = broken
        try { store(disk) { "Pacific/Auckland" }.measurementContext("app", at()); fail("missing zone recaptured") }
        catch (_: StorageUnavailableException) { }
        assertEquals(broken, disk.values.value)
    }

    @Test fun PC_56_D5_explicitImportedAndNewTemplateZonesArePreserved() = runBlocking {
        val disk = Disk(preferencesOf(PrefsKeys.TIME_LIMITS to "app~1~HARDCORE~America/New_York"))
        val reader = store(disk)
        assertEquals("America/New_York", reader.measurementContext("app", at()).zoneId)
        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit("new", 1, BreakLevel.HARDER, "Asia/Tokyo"))
        assertEquals("Asia/Tokyo", reader.measurementContext("new", at()).zoneId)
    }

    @Test fun PC_54_PC_56_templateZoneChangesAtTrustedRolloverAndOldTicketsStayStale() = runBlocking {
        val disk = Disk(legacy()); val old = spent(disk)
        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit("app", 1, BreakLevel.HARDCORE, "Asia/Tokyo"))
        val reader = store(disk)
        val pending = reader.measurementContext("app", at(1_000))
        assertEquals("UTC", pending.zoneId)
        assertEquals(old.periodId, pending.periodId)
        assertEquals(60_000L, readTimeLimitUsage(disk.values.value).targets.getValue("app").usedMs)
        val next = reader.measurementContext("app", at(hour))
        assertEquals("Asia/Tokyo", next.zoneId)
        assertEquals(old.periodId + 1, next.periodId)
        val stale = reader.record(pending, 120_000, at(hour))
        assertFalse(stale.sampleAccepted)
        assertEquals(0L, stale.state.usedMs)
        assertEquals(15 * hour, stale.state.reset.remainingToResetMs)
        assertEquals("Asia/Tokyo", store(disk) { "Pacific/Auckland" }.measurementContext("app", at(hour)).zoneId)
        assertTrue(reader.record(next, 1_000, at(hour)).sampleAccepted)
    }

    @Test fun PC_56_samePeriodTicketWithADifferentZoneCannotChargeOrDisableCurrentState() = runBlocking {
        val disk = Disk(legacy()); val context = spent(disk)
        val result = store(disk).record(context.copy(zoneId = "Asia/Tokyo"), 120_000, at(1_000))
        assertFalse(result.sampleAccepted)
        assertEquals(60_000L, result.state.usedMs)
        assertEquals("UTC", result.state.zoneId)
    }

    private suspend fun expectUnavailable(disk: Disk) {
        try { store(disk).measurementContext("app", at()); fail("unavailable state accepted") }
        catch (_: StorageUnavailableException) { }
    }
}
