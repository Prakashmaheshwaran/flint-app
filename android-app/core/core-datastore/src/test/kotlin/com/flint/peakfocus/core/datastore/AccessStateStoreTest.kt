package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.core.model.*
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class AccessStateStoreTest {
    private val counter = AccessCounterKey(AccessRuleKey(RuleAuthority.Personal, "rules", "one"), "period")
    private val scope = AccessCauseScope(counter, "revision")
    private val clock = AccessClockStamp(1_000_000, 10_000, "boot-1")
    private val pending = PendingAccess(1, AccessTarget.App("app"), setOf(scope), 60_000, AccessDuration(clock, 5_000))
    private val grant = TemporaryAccess(1, pending.target, pending.scopes, PersonalGrantKind.ORDINARY_BREAK,
        AccessDuration(clock.copy(elapsedRealtimeMs = 15_000), 60_000))
    private val pendingLedger = AccessLedgerV2(1, 2, pending = mapOf(1L to pending))

    /** Concurrency tests use a truly serialized fake, not independent read/set assignments. */
    private class SerialDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val mutex = Mutex()
        var failBeforeCommit = false
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            val result = transform(values.value)
            yield()
            if (failBeforeCommit) throw IOException("Synthetic disk failure")
            values.value = result
            result
        }
    }

    @Test fun readyEmptyFollowsLoadingWithoutWritingDefaults() = runBlocking {
        val disk = SerialDataStore()
        val store = DataStoreAccessStateStore(disk)
        assertEquals(listOf(AccessStoreState.Loading, AccessStoreState.Ready(AccessLedgerV2())), store.state.take(2).toList())
        assertTrue(disk.values.value.asMap().isEmpty())
        val result = store.transact { AccessMutation(it, Unit) }
        assertTrue(result is AccessCommit.Committed)
        assertTrue(disk.values.value.asMap().isEmpty())
    }

    @Test fun allLegacyPreferencesArePreservedByteForByte() = runBlocking {
        val original = mutablePreferencesOf(
            stringPreferencesKey("block_rules_v1") to
                "r10|Work%7C%0A|true|HARDER|false|app~A%2CB|example.com|1,2|480|600\n" +
                "r11|Session|true|HARDCORE|true|app~|site.test||-1|-1|987654321\nmalformed|retain-me",
            stringPreferencesKey("app_groups_v1") to "g|Name%7E|app~Label|example.com",
            stringPreferencesKey("time_limits_v1") to "app~15\nmalformed",
            stringPreferencesKey("open_limits_v1") to "app~3~HARDCORE",
            stringPreferencesKey("default_break_level_v1") to "HARDER",
            stringPreferencesKey("break_session_v1") to "7~1000~901000",
            longPreferencesKey("emergency_pass_week_start_v1") to 20633L,
            longPreferencesKey("emergency_pass_used_at_v1") to 1_234_567L,
            longPreferencesKey("open_counts_day_v1") to 20637L,
            stringPreferencesKey("open_counts_v1") to "app~4\nother~8",
            stringPreferencesKey("unrelated_future_key") to "do not change %25|~\n",
        )
        val disk = SerialDataStore(original)
        val before = disk.values.value.asMap().toMap()
        val store = DataStoreAccessStateStore(disk)
        store.transact { AccessMutation(pendingLedger, Unit) }
        assertEquals(before, disk.values.value.asMap().filterKeys { it != TEMPORARY_ACCESS_V2 })
        assertEquals(PrefsCodec.decodeRules(original[stringPreferencesKey("block_rules_v1")]!!),
            DataStoreBlockRulesStore(disk).rules.first())
        assertEquals(BreakSessionState(7, BreakRequestState(1000, 901000)), DataStoreFocusStateStore(disk).breakSession.first())
        assertEquals(OpenCountState(20637, mapOf("app" to 4, "other" to 8)), DataStoreFocusStateStore(disk).openCounts.first())
        assertEquals(listOf(OpenLimit("app", 3, BreakLevel.HARDCORE)), DataStoreLimitsStore(disk).openLimits.first())
        assertEquals(listOf(TimeLimit("app", 15)), DataStoreLimitsStore(disk).timeLimits.first())
    }

    @Test fun corruptOrFuturePayloadNeverBecomesEmptyOrGetsOverwritten() = runBlocking {
        for ((payload, reason) in listOf("bad" to AccessStoreFailure.CORRUPT, "H|3|0|1" to AccessStoreFailure.UNSUPPORTED_VERSION)) {
            val disk = SerialDataStore(mutablePreferencesOf(TEMPORARY_ACCESS_V2 to payload))
            val store = DataStoreAccessStateStore(disk)
            assertEquals(AccessStoreState.Unavailable(reason), store.state.drop(1).first())
            var invoked = false
            assertEquals(AccessCommit.Unavailable(reason), store.transact { invoked = true; AccessMutation(it, Unit) })
            assertFalse(invoked)
            assertEquals(payload, disk.values.value[TEMPORARY_ACCESS_V2])
        }
    }

    @Test fun readFailureIsUnavailableAndNeverReadyEmpty() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("Synthetic read failure") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException()
        }
        val store = DataStoreAccessStateStore(broken)
        assertEquals(listOf(AccessStoreState.Loading, AccessStoreState.Unavailable(AccessStoreFailure.IO)), store.state.toList())
        assertEquals(AccessCommit.Unavailable(AccessStoreFailure.IO), store.transact { AccessMutation(it, Unit) })
    }

    @Test fun wrongPreferenceTypeAndFileCorruptionAreUnavailable() = runBlocking {
        val wrongType = SerialDataStore(mutablePreferencesOf(longPreferencesKey("temporary_access_v2") to 123L))
        val store = DataStoreAccessStateStore(wrongType)
        assertEquals(AccessStoreState.Unavailable(AccessStoreFailure.CORRUPT), store.state.drop(1).first())
        assertEquals(AccessCommit.Unavailable(AccessStoreFailure.CORRUPT), store.transact { AccessMutation(it, Unit) })
        assertEquals(123L, wrongType.values.value[longPreferencesKey("temporary_access_v2")])
        val corrupt = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw CorruptionException("Synthetic corruption") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw CorruptionException("Synthetic corruption")
        }
        assertEquals(AccessStoreState.Unavailable(AccessStoreFailure.CORRUPT), DataStoreAccessStateStore(corrupt).state.drop(1).first())
        assertEquals(AccessCommit.Unavailable(AccessStoreFailure.CORRUPT), DataStoreAccessStateStore(corrupt).transact { AccessMutation(it, Unit) })
    }

    @Test fun cancellationPropagatesWithoutBeingMisreportedAsEmptyOrCommitted() = runBlocking {
        val cancelled = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw CancellationException("Cancelled read") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw CancellationException("Cancelled write")
        }
        val store = DataStoreAccessStateStore(cancelled)
        try { store.state.toList(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        try { store.transact { AccessMutation(it, Unit) }; fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun failedWriteCannotReturnGrantOrPartiallySpendCounter() = runBlocking {
        val disk = SerialDataStore(mutablePreferencesOf(TEMPORARY_ACCESS_V2 to AccessLedgerCodec.encode(pendingLedger)))
        val store = DataStoreAccessStateStore(disk)
        disk.failBeforeCommit = true
        val result = store.transact(::completeFixture)
        assertEquals(AccessCommit.Unavailable(AccessStoreFailure.IO), result)
        assertEquals(pendingLedger, AccessLedgerCodec.decode(disk.values.value[TEMPORARY_ACCESS_V2]))
        disk.failBeforeCommit = false
        assertEquals(AccessRequestResult.Granted(grant), (store.transact(::completeFixture) as AccessCommit.Committed).result)
    }

    @Test fun concurrentCompletionsAtomicallyCreateOneGrantAndOneDebit() = runBlocking {
        for (store in listOf<AccessStateStore>(
            DataStoreAccessStateStore(SerialDataStore(mutablePreferencesOf(TEMPORARY_ACCESS_V2 to AccessLedgerCodec.encode(pendingLedger)))),
            InMemoryAccessStateStore(pendingLedger),
        )) {
            val results = (1..64).map {
                async(Dispatchers.Default) { store.transact(::completeFixture) }
            }.awaitAll().map { (it as AccessCommit.Committed).result }
            assertEquals(1, results.count { it is AccessRequestResult.Granted })
            assertEquals(63, results.count { it is AccessRequestResult.AlreadyGranted })
            val ledger = (store.state.filterIsInstance<AccessStoreState.Ready>().first()).ledger
            assertEquals(1L, ledger.ordinaryUnblocks[counter])
            assertEquals(1, ledger.grants.size)
            assertTrue(ledger.pending.isEmpty())
        }
    }

    @Test fun concurrentIndependentUpdatesDoNotLoseIncrements() = runBlocking {
        for (store in listOf(DataStoreAccessStateStore(SerialDataStore()), InMemoryAccessStateStore())) {
            (1..100).map {
                async(Dispatchers.Default) {
                    store.transact { state ->
                        AccessMutation(state.copy(revision = state.revision + 1,
                            ordinaryUnblocks = state.ordinaryUnblocks + (counter to ((state.ordinaryUnblocks[counter] ?: 0L) + 1))), Unit)
                    }
                }
            }.awaitAll()
            val ledger = store.state.filterIsInstance<AccessStoreState.Ready>().first().ledger
            assertEquals(100L, ledger.ordinaryUnblocks[counter])
            assertEquals(100L, ledger.revision)
        }
    }

    @Test fun actualDataStoreReopenRestoresCommittedGrantAndItsCounterTogether() = runBlocking {
        val directory = createTempDirectory(prefix = "flint-access-").toFile()
        val file = File(directory, "access.preferences_pb")
        var owner = SupervisorJob()
        try {
            val first = DataStoreAccessStateStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + owner), produceFile = { file }))
            first.transact { AccessMutation(pendingLedger, Unit) }
            first.transact(::completeFixture) // Simulate dying before consuming the returned UI result.
            owner.cancelAndJoin()
            owner = SupervisorJob()
            val reopened = DataStoreAccessStateStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + owner), produceFile = { file }))
            val restored = reopened.state.filterIsInstance<AccessStoreState.Ready>().first().ledger
            assertEquals(grant, restored.grants[1L])
            assertEquals(1L, restored.ordinaryUnblocks[counter])
            assertEquals(AccessRequestResult.AlreadyGranted(grant),
                (reopened.transact(::completeFixture) as AccessCommit.Committed).result)
        } finally {
            owner.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun invalidRevisionAndSequenceRewindCannotOverwriteState() = runBlocking {
        val store = InMemoryAccessStateStore(pendingLedger)
        try {
            store.transact { AccessMutation(it.copy(pending = emptyMap()), Unit) }
            fail("Expected revision validation")
        } catch (_: IllegalArgumentException) { }
        try {
            store.transact { AccessMutation(it.copy(revision = 2, pending = emptyMap(), nextSequence = 1), Unit) }
            fail("Expected sequence validation")
        } catch (_: IllegalArgumentException) { }
        assertEquals(pendingLedger, store.state.filterIsInstance<AccessStoreState.Ready>().first().ledger)
    }

    @Test fun mutatingReturnedMapDoesNotMutateTheInMemoryStore() = runBlocking {
        val store = InMemoryAccessStateStore()
        val callerMap = mutableMapOf(counter to 1L)
        store.transact { AccessMutation(it.copy(revision = 1, ordinaryUnblocks = callerMap), Unit) }
        callerMap[counter] = 999L
        assertEquals(1L, store.state.filterIsInstance<AccessStoreState.Ready>().first().ledger.ordinaryUnblocks[counter])
    }

    /** Storage-only fixture; the engine suite independently tests the real access reducer. */
    private fun completeFixture(state: AccessLedgerV2): AccessMutation<AccessRequestResult> {
        state.grants[1L]?.let { return AccessMutation(state, AccessRequestResult.AlreadyGranted(it)) }
        require(1L in state.pending)
        return AccessMutation(state.copy(revision = state.revision + 1, pending = emptyMap(),
            ordinaryUnblocks = state.ordinaryUnblocks + (counter to 1L), grants = mapOf(1L to grant)),
            AccessRequestResult.Granted(grant))
    }
}
