package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/**
 * Red team for the R-18b follow-ups: Time-usage state labelling (#250) and the first-open
 * capture retry (#254). Every test pins its own clock/zone; no device or host zone is used.
 */
class TimeLimitBootstrapRetryRedTeamTest {
    private class Disk(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var lostAcknowledgement = false
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also {
                values.value = it
                if (lostAcknowledgement) throw CancellationException("committed without acknowledgement")
            }
        }
    }

    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private val at = AccessClockStamp(base, 10_000, "boot")
    private val hour = 3_600_000L
    private val legacy = preferencesOf(
        PrefsKeys.TIME_LIMITS to "app~0~HARDCORE", PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDCORE")

    /** Production wiring: failed open-time migration, in-process retry, same stores as FlintPreferences. */
    private inner class Harness(
        initial: Preferences = legacy,
        failingAtOpen: Boolean = true,
        onCapturedFailure: Boolean = false,
        private val scope: CoroutineScope? = null,
    ) {
        val raw = Disk(initial)
        @Volatile var discoveryFails = failingAtOpen
        @Volatile var zone = "UTC"
        var targets = setOf("legacy-only")
        @Volatile var discoveries = 0
        @Volatile var captures = 0
        @Volatile var nowMs = 0L
        val migration = TimeLimitBootstrapMigration({
            discoveries++
            if (discoveryFails) throw IOException("clock unavailable") else at
        }, { zone }, { targets })
        val retry = TimeLimitBootstrapRetry(raw, migration, onCaptured = {
            captures++
            if (onCapturedFailure) throw IOException("reconcile write failed")
            DataStoreEditorCommitmentStore(TimeBootstrapRequiredDataStore(raw), clockAtCommit = { at },
                defaultZone = { "UTC" }).reconcile(at)
        }, scope = scope, monotonicMs = { nowMs }, initialBackoffMs = 1_000, maxBackoffMs = 60_000)
        val disk = TimeBootstrapRequiredDataStore(raw, retry)
        private val editor = DataStoreEditorCommitmentStore(disk, clockAtCommit = { at }, defaultZone = { "UTC" })
        val usage = DataStoreTimeLimitUsageStore(disk, { at }, { "UTC" },
            prepareEditor = { prefs, now -> editor.prepare(prefs, now, checkpointQuota = false) })
        val limits = EditorMigratingLimitsStore(disk) { at }

        init { runBlocking { raw.values.value = migration.migrate(raw.values.value) } } // The failed open-time migration.

        suspend fun usageState() = usage.state.first { it !is StorageState.Loading }
        suspend fun limitsState() = limits.timeLimitsState.first { it !is StorageState.Loading }
        fun timeKeys() = raw.values.value.asMap().keys.map { it.name }.filter {
            it == TIME_BOOTSTRAP_CONTEXT.name || it == TIME_USAGE_SCHEMA.name || it == TIME_USAGE_STATE.name ||
                it == TIME_USAGE_ARCHIVE.name || it == TIME_USAGE_TARGETS.name || it == TIME_LIMIT_ZONE_SCHEMA.name }
    }

    /**
     * An established install: editor v3 with normalized Time templates, no capture marker and no
     * Time usage ledger (the state a process killed between the capture write and the first
     * ledger write leaves behind).
     */
    private suspend fun establishedWithoutLedger(): Preferences {
        val seed = Disk(legacy)
        DataStoreEditorCommitmentStore(seed, clockAtCommit = { at }, defaultZone = { "UTC" }).reconcile(at)
        return seed.values.value.toMutablePreferences().also { prefs ->
            listOf(TIME_USAGE_SCHEMA, TIME_USAGE_STATE, TIME_USAGE_ARCHIVE, TIME_USAGE_TARGETS, TIME_LIMIT_ZONE_SCHEMA)
                .forEach { prefs.remove(it) }
        }.toPreferences()
    }

    // ---------------------------------------------------------------- (c) state labelling

    /**
     * (c) No permutation of Time-owned bytes may ever read Ready (Ready-empty would be read as
     * "no Time Limits"). Every proper subset is a broken ledger; only the full set is Ready.
     */
    @Test fun redTeam_StateLabellingNeverPublishesReadyForPartialOrMissingOwnedBytes() = runBlocking {
        val migrated = TimeLimitBootstrapMigration({ at }, { "UTC" }, { setOf("legacy-only") })
            .migrate(legacy)
        val full = Disk(migrated)
        DataStoreTimeLimitUsageStore(full, { at }, { "UTC" }).measurementContext("app", at)
        DataStoreEditorCommitmentStore(full, defaultZone = { "UTC" }).reconcile(at)
        val readyBytes = full.values.value
        assertTrue(DataStoreTimeLimitUsageStore(full).state.first { it !is StorageState.Loading } is StorageState.Ready)
        assertTrue(EditorMigratingLimitsStore(full) { at }.timeLimitsState.first { it !is StorageState.Loading } is StorageState.Ready)
        assertTrue(readTimeLimitUsage(readyBytes).targets.containsKey("app"))

        val owned = listOf(TIME_USAGE_SCHEMA, TIME_USAGE_STATE, TIME_USAGE_ARCHIVE, TIME_USAGE_TARGETS, TIME_LIMIT_ZONE_SCHEMA)
        var checked = 0
        for (mask in 1 until (1 shl owned.size)) {
            val removed = owned.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
            val partial = Disk(readyBytes.toMutablePreferences().also { prefs -> removed.forEach { prefs.remove(it) } }.toPreferences())
            val usageState = DataStoreTimeLimitUsageStore(partial).state.first { it !is StorageState.Loading }
            assertTrue("usage ${removed.map { it.name }} -> $usageState", usageState is StorageState.Unavailable)
            val limitsState = EditorMigratingLimitsStore(partial) { at }.timeLimitsState.first { it !is StorageState.Loading }
            assertTrue("limits ${removed.map { it.name }} -> $limitsState", limitsState is StorageState.Unavailable)
            checked++
        }
        assertEquals(31, checked)
        // An uninitialized ledger is retryable; a pending capture is retryable. Both must stay unavailable.
        assertEquals(StorageState.Unavailable(StorageFailure.IO),
            DataStoreTimeLimitUsageStore(Disk(migrated)).state.first { it !is StorageState.Loading })
        assertEquals(StorageState.Unavailable(StorageFailure.IO),
            DataStoreTimeLimitUsageStore(Disk(legacy)).state.first { it !is StorageState.Loading })
    }

    /**
     * (b) "Marker written but ledger never initialized": the capture is durable yet no ledger
     * exists. It must never read Ready-empty, and the ordinary editor preparation a fresh process
     * runs must initialize it so an already-exhausted (zero-minute) limit is enforced.
     */
    @Test fun redTeam_DurableCaptureWithoutALedgerStaysFailClosedAndTheEditorPreparationInitializesIt() = runBlocking {
        val migrated = TimeLimitBootstrapMigration({ at }, { "UTC" }, { setOf("legacy-only") }).migrate(legacy)
        val initialized = Disk(migrated)
        DataStoreEditorCommitmentStore(initialized, defaultZone = { "UTC" }).reconcile(at)
        val owned = listOf(TIME_USAGE_SCHEMA, TIME_USAGE_STATE, TIME_USAGE_ARCHIVE, TIME_USAGE_TARGETS, TIME_LIMIT_ZONE_SCHEMA)
        val disk = Disk(initialized.values.value.toMutablePreferences().also { prefs ->
            owned.forEach { prefs.remove(it) }
        }.toPreferences())
        val usage = DataStoreTimeLimitUsageStore(disk, { at }, { "UTC" })
        assertEquals(StorageState.Unavailable(StorageFailure.IO),
            usage.state.first { it !is StorageState.Loading })
        assertTrue(EditorMigratingLimitsStore(disk) { at }.timeLimitsState.first { it !is StorageState.Loading }
            is StorageState.Unavailable)
        assertNull(disk.values.value[TIME_USAGE_SCHEMA]) // Reading never initialized it.
        // The production bridge (`ActiveRulesBridge` -> reconcileRules on every DataStore change).
        DataStoreEditorCommitmentStore(disk, clockAtCommit = { at }, defaultZone = { "UTC" }).reconcileRules(at)
        assertTrue(usage.state.first { it !is StorageState.Loading } is StorageState.Ready)
        val limits = EditorMigratingLimitsStore(disk) { at }.timeLimitsState.first { it !is StorageState.Loading }
        assertEquals(0, (limits as StorageState.Ready).value.single { it.packageName == "app" }.dailyMinutes)
        // A zero-minute budget is already exhausted and needs no sample.
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(usage.measurementContext("app", at).committedDay!!, 0))
    }

    // ---------------------------------------------------------------- (b) retry semantics

    /**
     * (b) The retry succeeds but its post-capture editor reconcile fails. The capture must stay
     * exactly once, legacy bytes untouched, the domain fail-closed (never Ready-empty), and the
     * next editor preparation must initialize the ledger without a second capture.
     */
    @Test fun redTeam_FailedPostCaptureReconcileKeepsOneCaptureAndIsInitializedByTheNextEditorPreparation() = runBlocking {
        val harness = Harness(initial = establishedWithoutLedger(), onCapturedFailure = true)
        val beforeCapture = harness.raw.values.value[PrefsKeys.TIME_LIMITS]
        harness.discoveryFails = false
        harness.nowMs += 1_000
        harness.retry.attempt()
        assertEquals(1, harness.captures)
        assertEquals(2, harness.discoveries)
        assertTrue(harness.retry.settled)
        // The marker is durable; nothing else was written by the retry.
        assertEquals(listOf(TIME_BOOTSTRAP_CONTEXT.name), harness.timeKeys())
        assertEquals(TimeLimitBootstrapContext("UTC", at, setOf("legacy-only")),
            readTimeLimitBootstrapContext(harness.raw.values.value))
        assertEquals(StorageState.Unavailable(StorageFailure.IO), harness.usageState())
        // An established editor never self-heals on a read: Time stays unavailable.
        assertTrue(harness.limitsState() is StorageState.Unavailable)
        assertNull(harness.raw.values.value[TIME_USAGE_SCHEMA])
        // No second capture, no re-zone, no legacy rewrite, even after the day rolls.
        val marker = harness.raw.values.value[TIME_BOOTSTRAP_CONTEXT]
        harness.zone = "Pacific/Auckland"
        repeat(6) { harness.nowMs += 120_000; harness.retry.attempt(); harness.retry.observe(harness.raw.values.value) }
        assertEquals(2, harness.discoveries)
        assertEquals(1, harness.captures)
        assertEquals(marker, harness.raw.values.value[TIME_BOOTSTRAP_CONTEXT])
        assertEquals(beforeCapture, harness.raw.values.value[PrefsKeys.TIME_LIMITS])
        assertNull(harness.raw.values.value[TIME_USAGE_SCHEMA])
        // The ordinary editor preparation (the production rules bridge) initializes the ledger.
        DataStoreEditorCommitmentStore(TimeBootstrapRequiredDataStore(harness.raw), clockAtCommit = { at })
            .reconcileRules(at)
        assertTrue(harness.usageState() is StorageState.Ready)
        assertEquals(setOf("app", "legacy-only"), readTimeLimitUsage(harness.raw.values.value).targets.keys)
        assertEquals(0L, readTimeLimitUsage(harness.raw.values.value).targets.getValue("app").usedMs)
        assertEquals("UTC", readTimeLimitUsage(harness.raw.values.value).migrationZoneId)
    }

    /**
     * (b) Concurrency: a successful capture racing ordinary writes must not lose a write, mint a
     * second capture, or re-zone the marker.
     */
    @Test fun redTeam_ConcurrentWritersRaceOneCaptureAndNeverLoseWritesOrReZone() = runBlocking {
        val harness = Harness()
        harness.discoveryFails = false
        harness.nowMs += 1_000
        coroutineScope {
            repeat(16) { index -> launch(Dispatchers.Default) {
                harness.disk.edit { prefs -> prefs[stringPreferencesKey("race-$index")] = "v$index" }
            } }
        }
        assertEquals(1, harness.captures)
        assertEquals(2, harness.discoveries)
        repeat(16) { assertEquals("v$it", harness.raw.values.value[stringPreferencesKey("race-$it")]) }
        val context = harness.usage.measurementContext("app", at)
        assertEquals("UTC", context.zoneId)
        harness.usage.record(context, 30_000, at)
        // Inputs change afterwards: nothing re-captures or re-zones.
        harness.zone = "Pacific/Auckland"; harness.targets = setOf("later")
        repeat(4) { harness.nowMs += 300_000; harness.retry.attempt() }
        assertEquals(2, harness.discoveries)
        assertEquals(1, harness.captures)
        assertEquals("UTC", readTimeLimitBootstrapContext(harness.raw.values.value)!!.zoneId)
        assertEquals(30_000L, readTimeLimitUsage(harness.raw.values.value).targets.getValue("app").usedMs)
        assertEquals(setOf("app", "legacy-only"), readTimeLimitUsage(harness.raw.values.value).targets.keys)
        // The first ledger initialization stamps the captured zone into the canonical template
        // (D5); the untouched legacy bytes stay in the archive.
        assertEquals("app~0~HARDCORE~UTC", harness.raw.values.value[PrefsKeys.TIME_LIMITS])
        assertEquals("app~0~HARDCORE" to "HARDCORE",
            TimeLimitUsageCodec.readArchive(harness.raw.values.value[TIME_USAGE_ARCHIVE]!!))
    }

    /**
     * (d) A capture that lands later in the day must not restart the day at the capture instant:
     * the first OS sample covers the local day, so pre-capture usage is still charged.
     */
    @Test fun redTeam_LateCaptureKeepsTheLocalDayWindowSoEarlierUsageIsStillCharged() = runBlocking {
        val harness = Harness()
        harness.discoveryFails = false
        harness.nowMs += 1_000
        val ticket = harness.usage.measurementContext("app", at)
        assertEquals(DailyUsageTimeline.startOfDay(base, ZoneId.of("UTC")), ticket.windowStartEpochMs)
        assertTrue(ticket.windowStartEpochMs < at.wallEpochMs)
        assertEquals(0L, ticket.committedDay!!.usedMs)
        // A capture alone never reads as "unused": a positive budget stays unmeasured until a sample.
        assertNull(TimeLimitDayAccounting.remainingMs(ticket.committedDay!!, 30))
        val sampled = harness.usage.record(ticket, 45 * 60_000, at)
        assertTrue(sampled.sampleAccepted)
        assertEquals(2_700_000L, sampled.state.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(sampled.state, 30))
        // The retry itself wrote no usage; only the one-time ledger initialization stamped the
        // captured zone into the canonical template. The original bytes stay in the archive.
        assertEquals("app~0~HARDCORE~UTC", harness.raw.values.value[PrefsKeys.TIME_LIMITS])
        assertEquals("app~0~HARDCORE" to "HARDCORE",
            TimeLimitUsageCodec.readArchive(harness.raw.values.value[TIME_USAGE_ARCHIVE]!!))
    }

    /**
     * (b) A capture failure recorded as retryable IO must not become a permanent latch: the same
     * marker blocks the open-time migration of every later process, so Time would stay unmeasured
     * (no shield for an exhausted limit) for the life of the install.
     */
    @Test fun redTeam_RetryableIoCaptureMarkerIsNeverRetriedByAnyLaterProcess() = runBlocking {
        val recordedFailure = legacy.toMutablePreferences().apply {
            this[TIME_BOOTSTRAP_CONTEXT] = "B|1\nU|IO"
        }.toPreferences()
        val harness = Harness(initial = recordedFailure, failingAtOpen = false)
        harness.retry.observe(harness.raw.values.value)
        assertTrue(harness.retry.settled)
        repeat(10) { harness.nowMs += 120_000; harness.retry.attempt() }
        assertEquals(StorageState.Unavailable(StorageFailure.IO), harness.usageState())
        assertEquals("B|1\nU|IO", harness.raw.values.value[TIME_BOOTSTRAP_CONTEXT])
        // A later process sees the same marker and also settles without capturing.
        assertFalse(harness.migration.shouldMigrate(harness.raw.values.value))
        assertTrue("a retryable IO capture must be re-attempted", harness.discoveries >= 1)
    }

    /**
     * (b) A capture whose acknowledgement was lost (process killed / cancelled after the durable
     * write) leaves a marker with no ledger. The retry then settles without initializing it, so
     * the domain is only healed by the next editor write — never by a second capture.
     */
    @Test fun redTeam_LostCaptureAcknowledgementLeavesTheLedgerUninitializedUntilAnEditorWrite() = runBlocking {
        val harness = Harness(initial = establishedWithoutLedger())
        harness.discoveryFails = false
        harness.nowMs += 1_000
        harness.raw.lostAcknowledgement = true
        try { harness.retry.attempt(); fail("cancelled capture acknowledged") } catch (_: CancellationException) { }
        harness.raw.lostAcknowledgement = false
        assertFalse(harness.retry.settled)
        assertNull(harness.raw.values.value[TIME_USAGE_SCHEMA]) // No ledger yet.
        assertEquals(0, harness.captures)
        // The next attempt sees the durable marker, settles, and never initializes the ledger.
        harness.retry.attempt()
        assertTrue(harness.retry.settled)
        assertEquals(0, harness.captures)
        assertEquals(listOf(TIME_BOOTSTRAP_CONTEXT.name), harness.timeKeys())
        assertEquals(StorageState.Unavailable(StorageFailure.IO), harness.usageState())
        assertTrue(harness.limitsState() is StorageState.Unavailable)
        // Only the ordinary editor preparation initializes it, and it must not re-capture.
        val marker = harness.raw.values.value[TIME_BOOTSTRAP_CONTEXT]
        DataStoreEditorCommitmentStore(TimeBootstrapRequiredDataStore(harness.raw), clockAtCommit = { at })
            .reconcileRules(at)
        assertEquals(marker, harness.raw.values.value[TIME_BOOTSTRAP_CONTEXT])
        assertTrue(harness.usageState() is StorageState.Ready)
        assertEquals(0L, readTimeLimitUsage(harness.raw.values.value).targets.getValue("app").usedMs)
    }

    /** A zero-minute limit must be shielded as soon as the in-process capture publishes Ready. */
    @Test fun redTeam_ZeroMinuteLimitBecomesEnforceableOnTheInProcessCapture() = runBlocking {
        val harness = Harness()
        assertTrue(harness.limitsState() is StorageState.Unavailable)
        harness.discoveryFails = false
        harness.nowMs += 1_000
        val ticket = harness.usage.measurementContext("app", at)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(harness.usage.record(ticket, null, at).state, 0))
        val limits = harness.limitsState() as StorageState.Ready
        assertEquals(0, limits.value.single { it.packageName == "app" }.dailyMinutes)
        assertEquals(BreakLevel.HARDCORE, limits.value.single { it.packageName == "app" }.breakLevel)
        assertTrue(harness.retry.settled)
    }
}
