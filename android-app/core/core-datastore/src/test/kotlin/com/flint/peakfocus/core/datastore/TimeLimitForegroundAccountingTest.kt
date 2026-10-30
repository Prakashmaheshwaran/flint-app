package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.AccessClockStamp
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class TimeLimitForegroundAccountingTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val mutex = Mutex()
        var fail = false
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(values.value)
            if (fail) throw IOException("synthetic disk failure")
            values.value = next
            next
        }
    }
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(base + wall, 10_000 + ms, boot)
    private fun store(disk: Disk) = DataStoreTimeLimitUsageStore(disk, deviceZone = { "UTC" })
    private fun observation(now: AccessClockStamp, owner: String = "A", previous: String? = "visit", visit: String? = "visit",
        pkg: String? = "app", close: Boolean = false) = TimeLimitForegroundObservation(owner, previous, visit, pkg, now, close)
    private fun day(disk: Disk) = TimeLimitUsageCodec.decode(disk.values.value[TIME_USAGE_STATE]!!).targets.getValue("app")
    private suspend fun seed(disk: Disk): DataStoreTimeLimitUsageStore {
        val store = store(disk)
        val ticket = store.measurementContext("app", at())
        store.record(ticket, 25 * 60_000, at())
        store.observeForeground(observation(at(), previous = null))
        return store
    }

    @Test fun PC_54_PC_55_PC_56_bothPathsRetainFiveMinutesAcrossBackwardAndForwardMidnightThenBlock() = runBlocking {
        for (owner in listOf("A", "B")) {
            val disk = Disk(); val store = store(disk)
            val first = store.measurementContext("app", at())
            store.record(first, 25 * 60_000, at())
            store.observeForeground(observation(at(), owner, previous = null))
            var stamp = at(0, -23 * 3_600_000)
            var ticket = store.measurementContext("app", stamp)
            store.record(ticket, 0, stamp)
            assertEquals(5 * 60_000L, TimeLimitDayAccounting.remainingMs(day(disk), 30))
            stamp = at(0, 2 * 3_600_000)
            ticket = store.measurementContext("app", stamp)
            store.record(ticket, 0, stamp)
            assertEquals(first.periodId, day(disk).periodId)
            assertEquals(5 * 60_000L, TimeLimitDayAccounting.remainingMs(day(disk), 30))
            for (ms in 15_000L..300_000L step 15_000L) {
                stamp = at(ms, 2 * 3_600_000 + ms)
                store.observeForeground(observation(stamp, owner))
                ticket = store.measurementContext("app", stamp)
                store.record(ticket, ms, stamp)
            }
            assertEquals(30 * 60_000L, day(disk).usedMs)
            assertEquals(0L, TimeLimitDayAccounting.remainingMs(day(disk), 30))
        }
    }

    @Test fun PC_60_rebootAndUnknownBootResumeBalanceWithoutChargingDowntime() = runBlocking {
        for (boot in listOf("new-boot", "process-unknown")) {
            val disk = Disk(); seed(disk)
            val reboot = at(5, 7 * 86_400_000L, boot)
            val next = store(Disk(disk.values.value))
            next.observeForeground(observation(reboot, "new-process", previous = null))
            val ticket = next.measurementContext("app", reboot)
            val retained = next.record(ticket, 25 * 60_000, reboot).state
            assertEquals(5 * 60_000L, TimeLimitDayAccounting.remainingMs(retained, 30))
            assertEquals(3_600_000L, retained.reset.remainingToResetMs)
        }
    }

    @Test fun PC_48_floorAndScreenOffCloseTheSpanAndNeverChargeTheGap() = runBlocking {
        val disk = Disk(); val store = seed(disk)
        store.observeForeground(observation(at(10_000), visit = null, pkg = null))
        val floor = day(disk).usedMs
        assertEquals(25 * 60_000 + 10_000L, floor)
        store.observeForeground(observation(at(1_000_000), previous = null, visit = "next"))
        assertEquals(floor, day(disk).usedMs)
        store.observeForeground(observation(at(1_010_000), previous = "next", visit = "next"))
        assertEquals(floor + 10_000, day(disk).usedMs)
    }

    @Test fun PC_48_failedFloorWriteCannotBridgeTheFloorOnReturn() = runBlocking {
        val disk = Disk(); val store = seed(disk)
        disk.fail = true
        try { store.observeForeground(observation(at(10_000), visit = null, pkg = null)); fail() } catch (_: IOException) { }
        disk.fail = false
        store.observeForeground(observation(at(20_000), previous = null, visit = "new"))
        assertEquals(25 * 60_000L, day(disk).usedMs)
    }

    @Test fun PC_54_inactiveDetectorAndOldDisconnectCannotChargeOrReplaceCurrentSpan() = runBlocking {
        val disk = Disk(); val store = seed(disk)
        store.observeForeground(observation(at(15_000), "B", previous = null)) { false }
        assertEquals(25 * 60_000L, day(disk).usedMs)
        store.observeForeground(observation(at(15_000), "B", previous = null))
        store.observeForeground(observation(at(16_000), "A", visit = null, pkg = null, close = true))
        assertEquals("B", TimeLimitUsageCodec.decode(disk.values.value[TIME_USAGE_STATE]!!).foreground!!.ownerId)
        store.observeForeground(observation(at(25_000), "B"))
        assertEquals(25 * 60_000 + 10_000L, day(disk).usedMs)
    }

    @Test fun PC_60_killAfterObservationRetainsDurableFloorAndDoesNotChargeDeadProcessTime() = runBlocking {
        val disk = Disk(); val store = seed(disk)
        store.observeForeground(observation(at(15_000)))
        val persisted = disk.values.value
        val restarted = Disk(persisted)
        val next = store(restarted)
        next.observeForeground(observation(at(25_000), "process-2", previous = null))
        assertEquals(25 * 60_000 + 15_000L, day(restarted).usedMs)
        next.observeForeground(observation(at(30_000), "process-2"))
        assertEquals(25 * 60_000 + 20_000L, day(restarted).usedMs)
    }

    @Test fun PC_54_osAndElapsedFloorsCountTheSameIntervalOnce() = runBlocking {
        val disk = Disk(); val store = seed(disk)
        store.observeForeground(observation(at(15_000)))
        val ticket = store.measurementContext("app", at(15_000))
        store.record(ticket, 25 * 60_000 + 15_000, at(15_000))
        assertEquals(25 * 60_000 + 15_000L, day(disk).usedMs)
        store.observeForeground(observation(at(30_000)))
        assertEquals(25 * 60_000 + 30_000L, day(disk).usedMs)
    }

    @Test fun PC_48_queuedFloorBeforeANewerOsCommitNeverRewindsTheResetOrChargesFloorTime() = runBlocking {
        val disk = Disk(); val store = seed(disk)
        store.observeForeground(observation(at(10_000)))
        val ticket = store.measurementContext("app", at(10_000))
        store.record(ticket, 25 * 60_000 + 10_000, at(20_000))
        store.observeForeground(observation(at(15_000), visit = null, pkg = null))
        assertEquals(25 * 60_000 + 15_000L, day(disk).usedMs)
        assertEquals(3_580_000L, day(disk).reset.remainingToResetMs)
        assertEquals(at(20_000), day(disk).reset.checkpoint)
        assertFalse(day(disk).timeUncertain)
        store.observeForeground(observation(at(25_000), previous = null, visit = "after-floor"))
        assertEquals(25 * 60_000 + 15_000L, day(disk).usedMs)
    }

    @Test fun PC_48_retiredOwnerCanClearItsOldSpanWithoutChargingTimeAfterItLostOwnership() = runBlocking {
        val disk = Disk(); val store = seed(disk)
        store.observeForeground(observation(at(15_000), visit = null, pkg = null, close = true)) { false }
        assertEquals(25 * 60_000L, day(disk).usedMs)
        assertNull(TimeLimitUsageCodec.decode(disk.values.value[TIME_USAGE_STATE]!!).foreground)
    }
}
