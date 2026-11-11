package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** R-18b capture-absent lastGood: a failed first-open capture is retried in the same process. */
class TimeLimitBootstrapRetryTest {
    private class Disk(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }

    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private val at = AccessClockStamp(base, 10_000, "boot")
    private val legacy = preferencesOf(PrefsKeys.TIME_LIMITS to "app~1~HARDCORE", PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDER")

    private inner class Harness(failingAtOpen: Boolean = true, scope: CoroutineScope? = null,
        initialBackoffMs: Long = 1_000, maxBackoffMs: Long = 60_000, initial: Preferences = legacy) {
        val raw = Disk(initial)
        @Volatile var discoveryFails = failingAtOpen
        @Volatile var zone = "UTC"
        var targets = setOf("legacy-only")
        @Volatile var discoveries = 0
        @Volatile var captures = 0
        @Volatile var nowMs = 0L
        val migration = TimeLimitBootstrapMigration({
            discoveries++
            if (discoveryFails) throw java.io.IOException("clock unavailable") else at
        }, { zone }, { targets })
        val retry = TimeLimitBootstrapRetry(raw, migration, onCaptured = {
            captures++
            DataStoreEditorCommitmentStore(TimeBootstrapRequiredDataStore(raw), clockAtCommit = { at },
                defaultZone = { "UTC" }).reconcile(at)
        }, scope = scope, monotonicMs = { nowMs }, initialBackoffMs = initialBackoffMs, maxBackoffMs = maxBackoffMs)
        val disk = TimeBootstrapRequiredDataStore(raw, retry)
        private val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        val usage = DataStoreTimeLimitUsageStore(disk, { at }, { "UTC" },
            prepareEditor = { prefs, now -> editor.prepare(prefs, now, checkpointQuota = false) })
        val limits = EditorMigratingLimitsStore(disk) { at }

        init { runBlocking { raw.values.value = migration.migrate(raw.values.value) } } // The failed open-time migration.

        suspend fun usageState() = usage.state.first { it !is StorageState.Loading }
        suspend fun limitsState() = limits.timeLimitsState.first { it !is StorageState.Loading }
        suspend fun measurementRefused() {
            try { usage.measurementContext("app", at); fail("Time accounting proceeded without a capture") }
            catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.IO, failure.reason) }
        }
        fun timeKeys() = raw.values.value.asMap().keys.map { it.name }.filter {
            it == TIME_BOOTSTRAP_CONTEXT.name || it == TIME_USAGE_SCHEMA.name || it == TIME_USAGE_STATE.name ||
                it == TIME_USAGE_ARCHIVE.name || it == TIME_USAGE_TARGETS.name || it == TIME_LIMIT_ZONE_SCHEMA.name }
    }

    @Test fun failedFirstOpenCaptureSucceedsInProcessAndTheNextTimeWriteIsMeasured() = runBlocking {
        val h = Harness()
        assertEquals(1, h.discoveries)
        assertEquals(emptyList<String>(), h.timeKeys())
        h.measurementRefused() // Inline retry on the write; discovery still fails.
        assertEquals(2, h.discoveries)
        h.discoveryFails = false
        h.measurementRefused() // Inside the backoff window: no discovery, still fail-closed.
        assertEquals(2, h.discoveries)
        h.nowMs += 1_000
        val ticket = h.usage.measurementContext("app", at) // Same process, no reopen.
        assertEquals(3, h.discoveries)
        assertEquals(1, h.captures)
        assertEquals("UTC", ticket.zoneId)
        val sampled = h.usage.record(ticket, 60_000, at)
        assertTrue(sampled.sampleAccepted)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(sampled.state, 1)) // An exhausted budget is now measured.
        assertEquals(setOf("app", "legacy-only"), readTimeLimitUsage(h.raw.values.value).targets.keys)
        assertTrue(h.usageState() is StorageState.Ready)
        assertEquals(listOf("app"), (h.limitsState() as StorageState.Ready).value.map { it.packageName })
        assertTrue(h.retry.settled)
    }

    @Test fun backoffTimerRecoversWithoutAnyWriterAndInitializesTheLedger() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val h = Harness(scope = scope, initialBackoffMs = 5, maxBackoffMs = 20)
            h.discoveryFails = true
            val clock = scope.launch { while (true) { delay(1); h.nowMs += 1; if (h.discoveries >= 4) h.discoveryFails = false } }
            // Only readers are active: the markerless emission schedules the bounded backoff.
            val ready = withTimeout(10_000) { h.usage.state.first { it is StorageState.Ready } }
            clock.cancel()
            assertTrue(ready is StorageState.Ready)
            assertEquals(1, h.captures)
            assertTrue(h.discoveries >= 4)
            assertEquals(setOf("app", "legacy-only"), readTimeLimitUsage(h.raw.values.value).targets.keys)
            assertTrue(h.limitsState() is StorageState.Ready)
        } finally { scope.cancel() }
    }

    @Test fun persistentDiscoveryFailureStaysFailClosedWithBoundedAttemptsAndNoZeroLedger() = runBlocking {
        val h = Harness()
        repeat(600) { second -> // Ten minutes of writes, one per second.
            h.nowMs += 1_000
            h.measurementRefused()
            try { h.usage.observeForeground(TimeLimitForegroundObservation("owner", null, "visit", "app", at)); fail() }
            catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.IO, failure.reason) }
            h.disk.edit { it[stringPreferencesKey("unrelated")] = "write $second" } // Other stores stay writable.
        }
        // 1, 2, 4 … 32 s, then capped at 60 s: bounded, never one discovery per write.
        assertTrue("discoveries=${h.discoveries}", h.discoveries in 10..20)
        assertEquals(emptyList<String>(), h.timeKeys())
        assertEquals("app~1~HARDCORE", h.raw.values.value[PrefsKeys.TIME_LIMITS])
        assertEquals(0, h.captures)
        assertFalse(h.retry.settled)
        assertEquals(StorageState.Unavailable(StorageFailure.IO), h.usageState())
        assertEquals(StorageState.Unavailable(StorageFailure.IO), h.limitsState())
    }

    @Test fun successIsCapturedOnceAndNeverRemigratesReZonesOrOverwritesUsage() = runBlocking {
        val h = Harness()
        h.discoveryFails = false
        h.nowMs += 1_000
        coroutineScope {
            repeat(8) { launch(Dispatchers.Default) { h.retry.attempt() } } // Writers racing at recovery.
        }
        assertEquals(1, h.captures)
        assertEquals(2, h.discoveries)
        val marker = h.raw.values.value[TIME_BOOTSTRAP_CONTEXT]
        assertEquals(TimeLimitBootstrapContext("UTC", at, setOf("legacy-only")), readTimeLimitBootstrapContext(h.raw.values.value))
        // The capture added only its marker; legacy bytes are archived, not rewritten.
        assertEquals("app~1~HARDCORE", TimeLimitUsageCodec.readArchive(h.raw.values.value[TIME_USAGE_ARCHIVE]!!).first)
        assertEquals("HARDER", TimeLimitUsageCodec.readArchive(h.raw.values.value[TIME_USAGE_ARCHIVE]!!).second)
        val ticket = h.usage.measurementContext("app", at)
        h.usage.record(ticket, 30_000, at)
        val usage = readTimeLimitUsage(h.raw.values.value)
        // Inputs change after success: later attempts, writes and a reopen are all no-ops.
        h.zone = "Pacific/Auckland"; h.targets = setOf("later-target"); h.discoveryFails = true
        repeat(5) { h.nowMs += 120_000; h.retry.attempt(); h.retry.observe(h.raw.values.value) }
        h.disk.edit { it[stringPreferencesKey("unrelated")] = "after" }
        assertEquals(h.raw.values.value, h.migration.migrate(h.raw.values.value))
        assertEquals(2, h.discoveries)
        assertEquals(1, h.captures)
        assertEquals(marker, h.raw.values.value[TIME_BOOTSTRAP_CONTEXT])
        assertEquals(usage.targets, readTimeLimitUsage(h.raw.values.value).targets)
        assertEquals(30_000L, readTimeLimitUsage(h.raw.values.value).targets.getValue("app").usedMs)
        assertEquals("UTC", readTimeLimitUsage(h.raw.values.value).migrationZoneId)
    }

    @Test fun recordedCaptureFailureKeepsItsLatchAndIsNeverRetried() = runBlocking {
        val h = Harness(initial = legacy.toMutablePreferences().apply { this[TIME_BOOTSTRAP_CONTEXT] = "B|1\nU|CORRUPT" }.toPreferences())
        h.discoveryFails = false
        h.nowMs += 1_000
        h.retry.observe(h.raw.values.value)
        assertTrue(h.retry.settled)
        try { h.usage.measurementContext("app", at); fail() }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
        assertEquals(0, h.discoveries)
        assertEquals("B|1\nU|CORRUPT", h.raw.values.value[TIME_BOOTSTRAP_CONTEXT])
    }
}
