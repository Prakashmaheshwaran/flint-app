package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class OpenCreditStoreTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var fail = false
        var beforeCommit: suspend () -> Unit = {}
        var loseAcknowledgement = false
        var writes = 0
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            val next = transform(values.value)
            beforeCommit()
            if (fail) throw IOException("synthetic disk failure")
            values.value = next
            writes++
            if (loseAcknowledgement) throw CancellationException("committed without acknowledgement")
            next
        }
    }
    private val base = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(base + wall, 1_000 + ms, boot)
    private val receipt = stringPreferencesKey("test_c3_receipt")
    private fun day() = OpenCreditLedgerPolicy.epochDay(base, "UTC")
    private fun legacy(counts: String = "app~4\nremoved~2147483647", epochDay: Long = day()) = preferencesOf(
        PrefsKeys.OPEN_LIMITS to "app~3~HARDCORE", PrefsKeys.OPEN_COUNTS to counts,
        PrefsKeys.OPEN_COUNTS_DAY to epochDay, PrefsKeys.BLOCK_RULES to "r|Keep|true|EASY|false|app~|||-1|-1",
        PrefsKeys.APP_GROUPS to "g|Saved|app~|", PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDER",
    )
    private suspend fun initialize(disk: DataStore<Preferences>, now: AccessClockStamp = at(), zone: String = "UTC"): OpenCreditLedger {
        var result: OpenCreditLedger? = null
        disk.edit { result = prepareOpenCredits(it, now, zone) }
        return checkNotNull(result)
    }
    private suspend fun use(disk: DataStore<Preferences>, id: String = "p1-request", now: AccessClockStamp = at()): OpenCreditMutation {
        var result: OpenCreditMutation? = null
        disk.edit { prefs ->
            val current = prepareOpenCredits(prefs, now, "UTC")
            val next = OpenCreditLedgerPolicy.acceptOpenCredit(current, "app", id, listOf(3), emptyList(), false, now)
            prefs.putOpenCredits(next.next)
            if (next.acceptance is OpenCreditPolicy.Acceptance.Accepted) prefs[receipt] = id
            result = next
        }
        // A grant transaction must publish only here, after durable acknowledgement.
        return checkNotNull(result)
    }

    @Test fun fixedOffsetQuotaAndAcceptedCreditRoundTripWithoutChangingItsMidnight() = runBlocking {
        val disk = Disk(preferencesOf(PrefsKeys.OPEN_LIMITS to "app~3~HARDCORE"))
        val first = initialize(disk, zone = "GMT+05:30")
        assertEquals(23_400_000L, first.reset.remainingToResetMs)
        val accepted = use(disk)
        assertTrue(accepted.acceptance is OpenCreditPolicy.Acceptance.Accepted)
        val reopened = readOpenCredits(disk.values.value)
        assertEquals(accepted.next, reopened)
        assertEquals("GMT+05:30", reopened.quotaZone)
        assertEquals(23_400_000L, reopened.reset.remainingToResetMs)
    }

    @Test fun `PC-70 literal import preserves package counts day original bytes preferences and over-cap values`() = runBlocking {
        val disk = Disk(legacy())
        val original = disk.values.value.asMap().toMap()
        val first = initialize(disk)
        assertEquals(day(), first.periodId)
        assertEquals(4, first.counters.getValue("app").used)
        assertEquals(Int.MAX_VALUE, first.counters.getValue("removed").used)
        assertEquals("UTC", first.quotaZone)
        assertEquals(original, disk.values.value.asMap().filterKeys { it in original })
        assertEquals("app~4\nremoved~2147483647" to day(), OpenCreditCodec.readArchive(disk.values.value[OPEN_CREDIT_ARCHIVE]!!))
        val bytes = disk.values.value.asMap().toMap()
        assertEquals(first, initialize(disk))
        assertEquals(bytes, disk.values.value.asMap())
        assertEquals(1L, disk.values.value[OPEN_CREDIT_SCHEMA])
    }

    @Test fun `PC-70 previous-day migration retains spend until observed trusted reset`() = runBlocking {
        val disk = Disk(legacy(epochDay = day() - 10))
        val first = initialize(disk)
        assertTrue(first.timeUncertain)
        assertEquals(day() - 10, first.periodId)
        assertEquals(4, first.counters.getValue("app").used)
        initialize(disk, at(1_000))
        assertEquals(4, readOpenCredits(disk.values.value).counters.getValue("app").used)
        val next = initialize(disk, at(12 * 3_600_000L))
        assertEquals(first.periodId + 1, next.periodId)
        assertEquals(0, next.counters.getValue("app").used)
        assertEquals(0, next.counters.getValue("removed").used)
    }

    @Test fun `PC-37 PC-71 missing counts day or initialized critical key never becomes fresh quota`() = runBlocking {
        for (key in listOf(PrefsKeys.OPEN_COUNTS, PrefsKeys.OPEN_COUNTS_DAY)) {
            val raw = legacy().toMutablePreferences().also { it.remove(key) }
            val disk = Disk(raw)
            assertUnavailable(disk)
        }
        val valid = Disk(); initialize(valid)
        for (key in listOf(OPEN_CREDIT_COUNTS, OPEN_CREDIT_STATE, OPEN_CREDIT_SCHEMA, OPEN_CREDIT_ARCHIVE, QUOTA_ZONE)) {
            val disk = Disk(valid.values.value.toMutablePreferences().also { it.remove(key) })
            assertUnavailable(disk)
        }
    }

    @Test fun `PC-70 only a validated unused legacy domain initializes explicit zero once`() = runBlocking {
        val disk = Disk(preferencesOf(PrefsKeys.BLOCK_RULES to "r|Keep|true|EASY|false|app~|||-1|-1"))
        val first = initialize(disk)
        assertTrue(first.counters.isEmpty())
        assertEquals("O|1", disk.values.value[OPEN_CREDIT_COUNTS])
        assertEquals(null to null, OpenCreditCodec.readArchive(disk.values.value[OPEN_CREDIT_ARCHIVE]!!))
        disk.edit { it.remove(OPEN_CREDIT_COUNTS) }
        assertUnavailable(disk)
        val uncertain = Disk(preferencesOf(PrefsKeys.FOCUS_LEDGER to FocusLedgerCodec.encode(FocusLedgerCodec.Ledger(
            exemptions = mapOf("old" to PersistedExemption("old", "app", "open:app", ExemptionKind.OPEN_LIMIT, 0))))))
        assertUnavailable(uncertain)
    }

    @Test fun `PC-70 configured but never-used legacy Open limits import zero once with both count keys absent`() = runBlocking {
        val disk = Disk(preferencesOf(PrefsKeys.OPEN_LIMITS to "app~3~HARDCORE"))
        val migrated = initialize(disk)
        assertTrue(migrated.counters.isEmpty())
        assertEquals(3, OpenCreditLedgerPolicy.remainingCredits(migrated, "app", listOf(3)))
        assertTrue(use(disk).acceptance is OpenCreditPolicy.Acceptance.Accepted)
        assertEquals(1, readOpenCredits(disk.values.value).counters.getValue("app").used)
        disk.edit { it.remove(OPEN_CREDIT_COUNTS) }
        assertUnavailable(disk)
    }

    @Test fun `PC-56 read-driven checkpoints stay quiet while a real policy entry persists elapsed progress`() = runBlocking {
        val disk = Disk(); val initial = initialize(disk)
        val bytes = disk.values.value.asMap().toMap()
        repeat(8) { index -> disk.edit { prefs ->
            val prepared = prepareOpenCredits(prefs, at((index + 1) * 1_000L), "UTC", checkpoint = false)
            assertEquals(initial.reset.remainingToResetMs - (index + 1) * 1_000L, prepared.reset.remainingToResetMs)
        } }
        assertEquals(bytes, disk.values.value.asMap())
        initialize(disk, at(9_000))
        assertEquals(at(9_000), readOpenCredits(disk.values.value).observedAt)
        assertEquals(initial.reset.remainingToResetMs - 9_000, readOpenCredits(disk.values.value).reset.remainingToResetMs)
    }

    @Test fun `PC-70 unprovable legacy Open release ends with notice and retains spent state and raw receipt`() = runBlocking {
        val oldReceipt = FocusLedgerCodec.encode(FocusLedgerCodec.Ledger(exemptions = mapOf("old" to
            PersistedExemption("old", "app", "open:app", ExemptionKind.OPEN_LIMIT, 0))))
        val prefs = legacy(counts = "app~2").toMutablePreferences().also { it[PrefsKeys.FOCUS_LEDGER] = oldReceipt }
        val disk = Disk(prefs)
        val migrated = initialize(disk)
        assertEquals(2, migrated.counters.getValue("app").used)
        assertTrue(migrated.active.isEmpty()); assertTrue(migrated.accessEndedAfterRestart)
        assertEquals(oldReceipt, disk.values.value[PrefsKeys.FOCUS_LEDGER])
    }

    @Test fun `PC-71 malformed future duplicate and partial legacy import leaves original readable`() = runBlocking {
        val fixtures = listOf(
            legacy(counts = "app~4\nbroken"), legacy(counts = "app~4\napp~5"),
            legacy(epochDay = day() + 1), legacy(epochDay = -1),
            legacy().toMutablePreferences().also { it[PrefsKeys.OPEN_LIMITS] = "app~3~FUTURE" },
            legacy().toMutablePreferences().also { it[longPreferencesKey("open_counts_v1")] = 3L },
            preferencesOf(OPEN_CREDIT_STATE to "S|1"),
        )
        fixtures.forEach { assertUnavailable(Disk(it)) }
    }

    @Test fun `PC-71 unknown ledger version malformed counters duplicate identities and wrong types fail closed`() = runBlocking {
        val initialized = Disk(); use(initialized)
        val raw = initialized.values.value
        val fixtures = listOf(
            raw.toMutablePreferences().also { it[OPEN_CREDIT_SCHEMA] = 2L },
            raw.toMutablePreferences().also { it[OPEN_CREDIT_STATE] = it[OPEN_CREDIT_STATE]!!.replace("S|1", "S|2") },
            raw.toMutablePreferences().also { it[OPEN_CREDIT_COUNTS] = it[OPEN_CREDIT_COUNTS]!! + "\nC|app|1" },
            raw.toMutablePreferences().also { it[OPEN_CREDIT_COUNTS] = it[OPEN_CREDIT_COUNTS]!! + "\nI|app|p1-request" },
            raw.toMutablePreferences().also { it[OPEN_CREDIT_COUNTS] = "O|1\nC|app|-1" },
            raw.toMutablePreferences().also { it[OPEN_CREDIT_COUNTS] = "O|1\nC|app|1\nC|other|1\nI|app|same\nP|other|same" },
            raw.toMutablePreferences().also { it[longPreferencesKey("open_credit_counts_v1")] = 3L },
            raw.toMutablePreferences().also { it[QUOTA_ZONE] = "No/Such_Zone" },
        )
        fixtures.forEach { assertUnavailable(Disk(it)) }
    }

    @Test fun `PC-23 PC-31 process restart restores exact interval and duplicate does not charge or extend`() = runBlocking {
        val disk = Disk()
        val first = use(disk).next.active.getValue("app")
        val reopened = Disk(disk.values.value)
        assertEquals(first, readOpenCredits(reopened.values.value).active.getValue("app"))
        val retried = use(reopened, now = at(100_000))
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("p1-request", first.grant), retried.acceptance)
        assertEquals(first, retried.next.active.getValue("app"))
        assertEquals(1, retried.next.counters.getValue("app").used)
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("p1-request", null), use(reopened, now = at(300_001)).acceptance)
    }

    @Test fun `PC-29 two concurrent surfaces commit one credit and one receipt`() = runBlocking {
        val disk = Disk()
        val outcomes = coroutineScope { listOf("p1-a", "p1-b").map { id -> async { use(disk, id).acceptance } }.awaitAll() }
        assertEquals(1, outcomes.count { it is OpenCreditPolicy.Acceptance.Accepted })
        assertEquals(1, outcomes.count { it is OpenCreditPolicy.Acceptance.AlreadyActive })
        val stored = readOpenCredits(disk.values.value)
        assertEquals(1, stored.counters.getValue("app").used)
        assertEquals(stored.active.getValue("app").grant.operationId, disk.values.value[receipt])
    }

    @Test fun `PC-70 migration and credit write failure keep original bytes and cannot acknowledge`() = runBlocking {
        for (configured in listOf(false, true)) {
            val disk = Disk(if (configured) legacy(counts = "app~1") else emptyPreferences())
            val original = disk.values.value.asMap().toMap()
            disk.fail = true
            try { use(disk); fail("failed write acknowledged") } catch (_: IOException) { }
            assertEquals(original, disk.values.value.asMap())
            assertEquals(0, disk.writes)
            disk.fail = false
            val paid = use(disk)
            assertTrue(paid.acceptance is OpenCreditPolicy.Acceptance.Accepted)
            assertEquals(if (configured) 2 else 1, paid.next.counters.getValue("app").used)
        }
    }

    @Test fun `PC-70 crash checkpoint before commit rolls back marker counts and consuming receipt together`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val disk = Disk(legacy(counts = "app~1"))
        val original = disk.values.value.asMap().toMap()
        disk.beforeCommit = { reached.complete(Unit); awaitCancellation() }
        val job = launch { use(disk); fail("cancelled transaction acknowledged") }
        reached.await(); job.cancelAndJoin()
        assertEquals(original, disk.values.value.asMap())
        assertNull(disk.values.value[OPEN_CREDIT_SCHEMA]); assertNull(disk.values.value[receipt])
        disk.beforeCommit = {}
        assertEquals(2, use(disk).next.counters.getValue("app").used)
    }

    @Test fun `PC-70 crash checkpoint after commit lost ack restores spend and receipt without another debit`() = runBlocking {
        val disk = Disk().also { it.loseAcknowledgement = true }
        try { use(disk); fail("lost acknowledgement swallowed") } catch (_: CancellationException) { }
        assertEquals("p1-request", disk.values.value[receipt])
        assertEquals(1, readOpenCredits(disk.values.value).counters.getValue("app").used)
        disk.loseAcknowledgement = false
        assertTrue(use(disk).acceptance is OpenCreditPolicy.Acceptance.Duplicate)
        assertEquals(1, readOpenCredits(disk.values.value).counters.getValue("app").used)
    }

    @Test fun `PC-70 real Preferences DataStore reopen retains import credit and receipt with no replay debit`() = runBlocking {
        val directory = createTempDirectory("flint-open-credit").toFile()
        val file = File(directory, "ledger.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            store.edit { prefs -> legacy(counts = "app~1").asMap().forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is String -> prefs[key as Preferences.Key<String>] = value
                    is Long -> prefs[key as Preferences.Key<Long>] = value
                }
            } }
            val paid = use(store).next.active.getValue("app")
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val reopened = store.data.first()
            assertEquals("app~1", reopened[PrefsKeys.OPEN_COUNTS])
            assertEquals("p1-request", reopened[receipt])
            assertEquals(paid, readOpenCredits(reopened).active.getValue("app"))
            assertTrue(use(store, now = at(10_000)).acceptance is OpenCreditPolicy.Acceptance.Duplicate)
            assertEquals(2, readOpenCredits(store.data.first()).counters.getValue("app").used)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun `PC-56 first limit quota zone is stable across later device zones and missing zone cannot heal`() = runBlocking {
        val disk = Disk()
        disk.edit { assertEquals("America/New_York", prepareQuotaZone(it, "America/New_York")) }
        assertEquals("America/New_York", initialize(disk, zone = "Asia/Tokyo").quotaZone)
        assertEquals("America/New_York", initialize(disk, zone = "Europe/London").quotaZone)
        disk.edit { it.remove(QUOTA_ZONE) }
        assertUnavailable(disk)
    }

    @Test fun `PC-71 codec rejects mismatched active interval and oversized rows`() = runBlocking {
        val disk = Disk(); use(disk)
        val raw = disk.values.value.toMutablePreferences()
        raw[OPEN_CREDIT_COUNTS] = raw[OPEN_CREDIT_COUNTS]!!.replace("|300000|300000|", "|300000|299999|")
        assertUnavailable(Disk(raw))
        raw[OPEN_CREDIT_COUNTS] = "O|1\n" + "C|app|0\n".repeat(10_001)
        assertUnavailable(Disk(raw))
    }

    private suspend fun assertUnavailable(disk: Disk) {
        val original = disk.values.value.asMap().toMap()
        val writes = disk.writes
        try { initialize(disk); fail("unavailable state became quota") }
        catch (_: StorageUnavailableException) { }
        assertEquals(original, disk.values.value.asMap())
        assertEquals(writes, disk.writes)
    }
}
