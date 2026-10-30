package com.flint.peakfocus.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
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

class StorageReadinessTest {
    private val rule = BlockRule("work", "Work", BlockTargets(apps = setOf(AppRef("app"))))
    private val receipt = PersistedExemption("request-1", "app", "rule:work:occurrence-1", ExemptionKind.BREAK, 301_000)

    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var failWrite = false
        var beforeCommit: suspend () -> Unit = {}
        var cancelAfterCommit = false
        var writes = 0
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            val next = transform(values.value)
            beforeCommit()
            if (failWrite) throw IOException("synthetic write failure")
            values.value = next
            writes++
            if (cancelAfterCommit) throw CancellationException("lost acknowledgement")
            next
        }
    }

    private fun allStates(disk: DataStore<Preferences>): List<Flow<StorageState<*>>> = listOf(
        DataStoreBlockRulesStore(disk).rulesState,
        DataStoreGroupsStore(disk).groupsState,
        DataStoreLimitsStore(disk).timeLimitsState,
        DataStoreLimitsStore(disk).openLimitsState,
        DataStoreFocusStateStore(disk).state,
    )

    private suspend fun snapshot(store: FocusStateStore): FocusSnapshot =
        (store.state.first { it is StorageState.Ready } as StorageState.Ready).value

    private fun grant(current: FocusSnapshot): FocusMutation<Boolean> =
        if (receipt.requestId in current.exemptions) FocusMutation(current, false)
        else FocusMutation(current.copy(
            breakSession = current.breakSession.copy(breaksTaken = current.breakSession.breaksTaken + 1),
            exemptions = current.exemptions + (receipt.requestId to receipt),
        ), true)

    @Test fun successfulAbsentKeysAreReadyOnlyAfterLoadingAndDoNotWriteDefaults() = runBlocking {
        val disk = Disk()
        allStates(disk).forEach { states ->
            val values = states.take(2).toList()
            assertEquals(StorageState.Loading, values.first())
            assertTrue(values.last() is StorageState.Ready)
        }
        assertEquals(FocusSnapshot(), snapshot(DataStoreFocusStateStore(disk)))
        DataStoreFocusStateStore(disk).transact { FocusMutation(it, Unit) }
        assertTrue(disk.values.value.asMap().isEmpty())
    }

    @Test fun readIoAndFileCorruptionAreUnavailableAcrossAllLiveStores() = runBlocking {
        for ((error, reason) in listOf(IOException("read") to StorageFailure.IO,
            CorruptionException("corrupt file") to StorageFailure.CORRUPT)) {
            val disk = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow { throw error }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw error
            }
            allStates(disk).forEach { states ->
                assertEquals(listOf(StorageState.Loading, StorageState.Unavailable(reason)), states.take(2).toList())
            }
            var called = false
            assertEquals(FocusCommit.Unavailable(reason), DataStoreFocusStateStore(disk).transact {
                called = true; FocusMutation(it, Unit)
            })
            assertFalse(called)
        }
    }

    @Test fun malformedRowsAndWrongTypesAreNeverDestroyedByAnotherMutation() = runBlocking {
        val payloads = listOf(
            Pair(PrefsKeys.BLOCK_RULES, "work|Work|true|HARDER|false|app~|||-1|-1\nbroken"),
            Pair(PrefsKeys.APP_GROUPS, "group|Group|app~|\nbroken"),
            Pair(PrefsKeys.TIME_LIMITS, "app~15\nbroken"),
            Pair(PrefsKeys.OPEN_LIMITS, "app~3~EASY\nbroken"),
            Pair(PrefsKeys.OPEN_COUNTS, "app~4\nbroken"),
            Pair(PrefsKeys.BREAK_SESSION, "1~bad~2000"),
        )
        for ((key, payload) in payloads) {
            val disk = Disk(mutablePreferencesOf(key to payload))
            val original = disk.values.value.asMap().toMap()
            try {
                when (key) {
                    PrefsKeys.BLOCK_RULES -> DataStoreBlockRulesStore(disk).upsert(rule)
                    PrefsKeys.APP_GROUPS -> DataStoreGroupsStore(disk).upsert(AppGroup("new", "New"))
                    PrefsKeys.TIME_LIMITS -> DataStoreLimitsStore(disk).setTimeLimit(TimeLimit("other", 30))
                    PrefsKeys.OPEN_LIMITS -> DataStoreLimitsStore(disk).setOpenLimit(OpenLimit("other", 3))
                    else -> DataStoreFocusStateStore(disk).setDefaultBreakLevel(BreakLevel.HARDER)
                }
                fail("Malformed value was overwritten: ${key.name}")
            } catch (cause: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, cause.reason) }
            assertEquals(original, disk.values.value.asMap())
            assertEquals(0, disk.writes)
        }
        val wrong = Disk(mutablePreferencesOf(longPreferencesKey("block_rules_v1") to 123L))
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), DataStoreBlockRulesStore(wrong).rulesState.drop(1).first())
        try { DataStoreBlockRulesStore(wrong).replaceAll(emptyList()); fail("Wrong type overwritten") }
        catch (_: StorageUnavailableException) { }
        assertEquals(123L, wrong.values.value[longPreferencesKey("block_rules_v1")])
    }

    @Test fun futureEnumsAndFieldsAreUnavailableRatherThanEasyOrDropped() = runBlocking {
        for (payload in listOf("r|Work|true|FUTURE|false|app~|||-1|-1|-1",
            "r|Work|true|EASY|false|app~|||-1|-1|-1|future")) {
            val disk = Disk(mutablePreferencesOf(PrefsKeys.BLOCK_RULES to payload))
            assertEquals(StorageState.Unavailable(StorageFailure.UNSUPPORTED_VERSION), DataStoreBlockRulesStore(disk).rulesState.drop(1).first())
            try { DataStoreBlockRulesStore(disk).upsert(rule); fail("Future record overwritten") }
            catch (cause: StorageUnavailableException) { assertEquals(StorageFailure.UNSUPPORTED_VERSION, cause.reason) }
            assertEquals(payload, disk.values.value[PrefsKeys.BLOCK_RULES])
        }
    }

    @Test fun v1LiteralFixturesDecodeUnchangedIncludingTenAndElevenFieldRules() = runBlocking {
        val disk = Disk(mutablePreferencesOf(
            PrefsKeys.BLOCK_RULES to "r10|Work%7C%0A|true|HARDER|false|app~A%2CB|example.com|1,2|480|600\n" +
                "session-timer-v1|Session|true|HARDCORE|true|app~|site.test||-1|-1|987654321",
            PrefsKeys.APP_GROUPS to "g|Name%7E|app~Label|example.com",
            PrefsKeys.TIME_LIMITS to "app~15", PrefsKeys.OPEN_LIMITS to "app~3~HARDCORE",
            PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDER", PrefsKeys.BREAK_SESSION to "7~1000~901000",
            PrefsKeys.EMERGENCY_PASS_WEEK_START to 20633L, PrefsKeys.EMERGENCY_PASS_USED_AT to 1234567L,
            PrefsKeys.OPEN_COUNTS_DAY to 20637L, PrefsKeys.OPEN_COUNTS to "app~4\nother~8",
        ))
        val rules = DataStoreBlockRulesStore(disk).rules.first()
        assertEquals("Work|\n", rules.first().name)
        assertEquals(Schedule(setOf(1, 2), 480, 600), rules.first().schedule)
        assertEquals("A,B", rules.first().targets.apps.single().label)
        assertEquals(987654321L, rules.last().expiresAtEpochMs)
        assertEquals(BreakLevel.HARDCORE, rules.last().breakLevel)
        assertEquals("Name~", DataStoreGroupsStore(disk).groups.first().single().name)
        // ADR-012 imports this valid tierless row using its old global Harder tier once.
        assertEquals(listOf(TimeLimit("app", 15, BreakLevel.HARDER)), DataStoreLimitsStore(disk).timeLimits.first())
        assertEquals(listOf(OpenLimit("app", 3, BreakLevel.HARDCORE)), DataStoreLimitsStore(disk).openLimits.first())
        val original = disk.values.value.asMap().toMap()
        val store = DataStoreFocusStateStore(disk)
        val focus = snapshot(store)
        assertEquals(BreakSessionState(7, BreakRequestState(1000, 901000)), focus.breakSession)
        assertEquals(EmergencyPassState(20633, 1234567), focus.emergencyPass)
        assertEquals(OpenCountState(20637, mapOf("app" to 4, "other" to 8)), focus.openCounts)
        store.transact { FocusMutation(it.copy(exemptions = mapOf(receipt.requestId to receipt)), Unit) }
        assertEquals(original, disk.values.value.asMap().filterKeys { it != PrefsKeys.FOCUS_LEDGER })
    }

    @Test fun sleepPairAndLimitsPairEachCommitOnceAndWriteFailuresKeepBothOldHalves() = runBlocking {
        val disk = Disk()
        val rules = DataStoreBlockRulesStore(disk)
        val morning = rule.copy(id = "sleep-morning")
        rules.updateRules(listOf(rule, morning))
        assertEquals(1, disk.writes)
        disk.failWrite = true
        try { rules.updateRules(listOf(rule.copy(name = "New")), setOf(morning.id)); fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals(listOf(rule, morning), rules.rules.first())
        disk.failWrite = false
        rules.updateRules(listOf(rule.copy(name = "New")), setOf(morning.id))
        assertEquals(listOf(rule.copy(name = "New")), rules.rules.first())
        val limits = DataStoreLimitsStore(disk)
        val before = disk.writes
        limits.setLimits("app", TimeLimit("app", 15), OpenLimit("app", 3))
        assertEquals(before + 1, disk.writes)
        disk.failWrite = true
        try { limits.setLimits("app", TimeLimit("app", 99), null); fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals(listOf(TimeLimit("app", 15)), limits.timeLimits.first())
        assertEquals(listOf(OpenLimit("app", 3)), limits.openLimits.first())
    }

    @Test fun failedGrantWriteCannotSpendCreditOrReturnAnAcknowledgement() = runBlocking {
        val disk = Disk().apply { failWrite = true }
        val store = DataStoreFocusStateStore(disk)
        assertEquals(FocusCommit.Unavailable(StorageFailure.IO), store.transact(::grant))
        assertEquals(FocusSnapshot(), snapshot(store))
        disk.failWrite = false
        assertTrue((store.transact(::grant) as FocusCommit.Committed).result)
        val restored = snapshot(DataStoreFocusStateStore(disk))
        assertEquals(1, restored.breakSession.breaksTaken)
        assertEquals(receipt, restored.exemptions[receipt.requestId])
    }

    @Test fun cancellationMidCommitLeavesNoPartialSpendAndPropagates() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val disk = Disk().apply { beforeCommit = { reached.complete(Unit); awaitCancellation() } }
        val store = DataStoreFocusStateStore(disk)
        val job = launch { store.transact(::grant); fail("Cancelled commit acknowledged") }
        reached.await()
        job.cancelAndJoin()
        assertEquals(FocusSnapshot(), snapshot(DataStoreFocusStateStore(disk)))
        assertEquals(0, disk.writes)
    }

    @Test fun lostAcknowledgementAfterCommitRestoresReceiptAndRetryDoesNotSpendAgain() = runBlocking {
        val disk = Disk().apply { cancelAfterCommit = true }
        try { DataStoreFocusStateStore(disk).transact(::grant); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        disk.cancelAfterCommit = false
        val reopened = DataStoreFocusStateStore(disk)
        val replay = reopened.transact(::grant) as FocusCommit.Committed
        assertFalse(replay.result)
        assertEquals(1, replay.snapshot.breakSession.breaksTaken)
        assertEquals(receipt, replay.snapshot.exemptions[receipt.requestId])
    }

    @Test fun duplicateConcurrentCallbacksConsumeExactlyOneCredit() = runBlocking {
        for (store in listOf(DataStoreFocusStateStore(Disk()), InMemoryFocusStateStore())) {
            val results = (1..64).map { async(Dispatchers.Default) { store.transact(::grant) } }.awaitAll()
            assertEquals(1, results.count { (it as FocusCommit.Committed).result })
            val current = snapshot(store)
            assertEquals(1, current.breakSession.breaksTaken)
            assertEquals(mapOf(receipt.requestId to receipt), current.exemptions)
        }
    }

    @Test fun actualDataStoreReopenRestoresConsumedPassAndRemainingExemptionTogether() = runBlocking {
        val dir = createTempDirectory("flint-focus-").toFile()
        val file = File(dir, "focus.preferences_pb")
        var owner = SupervisorJob()
        try {
            fun open() = DataStoreFocusStateStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + owner), produceFile = { file }))
            val emergency = receipt.copy(kind = ExemptionKind.EMERGENCY_PASS, untilEpochMs = 1_801_000, bootSessionId = "boot-one")
            open().transact { FocusMutation(it.copy(emergencyPass = EmergencyPassState(20633, 1000),
                exemptions = mapOf(emergency.requestId to emergency), minimumRequestEpochMs = 123), Unit) }
            owner.cancelAndJoin()
            owner = SupervisorJob()
            val restored = snapshot(open())
            assertEquals(EmergencyPassState(20633, 1000), restored.emergencyPass)
            assertEquals(emergency, restored.exemptions[emergency.requestId])
            assertEquals(123L, restored.minimumRequestEpochMs)
        } finally { owner.cancelAndJoin(); dir.deleteRecursively() }
    }

    @Test fun corruptAndFutureReceiptLedgersRefuseAllFocusWritesVerbatim() = runBlocking {
        for ((payload, reason) in listOf("broken" to StorageFailure.CORRUPT,
            "H|99" to StorageFailure.UNSUPPORTED_VERSION,
            "H|1\nE|id|app|cause|FUTURE|1000" to StorageFailure.UNSUPPORTED_VERSION,
            "H|1\nE|id|app|cause|BREAK|bad" to StorageFailure.CORRUPT)) {
            val disk = Disk(mutablePreferencesOf(PrefsKeys.FOCUS_LEDGER to payload))
            val store = DataStoreFocusStateStore(disk)
            assertEquals(StorageState.Unavailable(reason), store.state.drop(1).first())
            var called = false
            assertEquals(FocusCommit.Unavailable(reason), store.transact { called = true; grant(it) })
            assertFalse(called)
            assertEquals(payload, disk.values.value[PrefsKeys.FOCUS_LEDGER])
            assertEquals(0, disk.writes)
        }
    }

    @Test fun receiptV1LiteralSupportsGlobalScopeEscapedIdsAndPerCauseWaits() {
        val literal = "H|1\nE|request%7C1|*|cause%0A1|EMERGENCY_PASS|1801000\nB|cause%0A1|2%7E1000%7E301000"
        val decoded = FocusLedgerCodec.decode(literal)
        assertEquals(PersistedExemption("request|1", "*", "cause\n1", ExemptionKind.EMERGENCY_PASS, 1801000),
            decoded.exemptions["request|1"])
        assertEquals(BreakSessionState(2, BreakRequestState(1000, 301000)), decoded.breaksByCause["cause\n1"])
        assertEquals(0L, decoded.revision)
        assertEquals(0L, decoded.minimumRequestEpochMs)
        assertEquals(literal.replaceFirst("H|1\n", "H|2|0|0\n").replace("1801000\nB", "1801000|-\nB"), FocusLedgerCodec.encode(decoded))
    }

    @Test fun v1ReceiptHeaderMigrationAddsWatermarkWithoutRewritingLegacyAccounting() = runBlocking {
        val legacy = mutablePreferencesOf(
            PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDER",
            PrefsKeys.BREAK_SESSION to "7~1000~901000",
            PrefsKeys.EMERGENCY_PASS_WEEK_START to 20633L,
            PrefsKeys.EMERGENCY_PASS_USED_AT to 1234567L,
            PrefsKeys.OPEN_COUNTS_DAY to 20637L,
            PrefsKeys.OPEN_COUNTS to "app~4\nother~8",
        )
        for (header in listOf("H|1", "H|1|9")) {
            val literal = "$header\nE|old-id|app|cause|BREAK|301000"
            val disk = Disk(legacy.toMutablePreferences().apply { this[PrefsKeys.FOCUS_LEDGER] = literal })
            val store = DataStoreFocusStateStore(disk)
            val old = snapshot(store)
            assertEquals(0L, old.minimumRequestEpochMs)
            assertEquals(literal, disk.values.value[PrefsKeys.FOCUS_LEDGER])
            store.transact { FocusMutation(it.copy(minimumRequestEpochMs = 123_456), Unit) }
            val reopened = snapshot(DataStoreFocusStateStore(disk))
            assertEquals(old.copy(revision = old.revision + 1, minimumRequestEpochMs = 123_456), reopened)
            assertEquals("H|2|${old.revision + 1}|123456\nE|old-id|app|cause|BREAK|301000|-",
                disk.values.value[PrefsKeys.FOCUS_LEDGER])
            assertEquals(legacy.asMap(), disk.values.value.asMap().filterKeys { it != PrefsKeys.FOCUS_LEDGER })
        }
    }

    @Test fun version2BootFieldRoundTripsWhileLegacyMissingBootRemainsUnproven() {
        val literal = "H|2|7|123\nE|id|app|cause|BREAK|301000|boot%7C1"
        assertEquals("boot|1", FocusLedgerCodec.decode(literal).exemptions.getValue("id").bootSessionId)
        assertEquals(literal, FocusLedgerCodec.encode(FocusLedgerCodec.decode(literal)))
        for (header in listOf("H|1", "H|1|7", "H|2|7|123")) {
            assertNull(FocusLedgerCodec.decode("$header\nE|old|app|cause|BREAK|301000").exemptions.getValue("old").bootSessionId)
        }
    }

    @Test fun version2RetentionWatermarkRejectsMalformedOrFuturePayloadsVerbatim() = runBlocking {
        for ((payload, reason) in listOf(
            "H|2" to StorageFailure.CORRUPT,
            "H|2|1" to StorageFailure.CORRUPT,
            "H|2|1|-1" to StorageFailure.CORRUPT,
            "H|2|1|bad" to StorageFailure.CORRUPT,
            "H|2|1|0\nE|id|app|cause|BREAK|123|" to StorageFailure.CORRUPT,
            "H|2|1|0\nE|id|app|cause|BREAK|123|%ZZ" to StorageFailure.CORRUPT,
            "H|2|1|0|future" to StorageFailure.UNSUPPORTED_VERSION,
            "H|4|1|0" to StorageFailure.UNSUPPORTED_VERSION,
        )) {
            val disk = Disk(mutablePreferencesOf(PrefsKeys.FOCUS_LEDGER to payload))
            val store = DataStoreFocusStateStore(disk)
            assertEquals(StorageState.Unavailable(reason), store.state.drop(1).first())
            var called = false
            assertEquals(FocusCommit.Unavailable(reason), store.transact { called = true; grant(it) })
            assertFalse(called)
            assertEquals(payload, disk.values.value[PrefsKeys.FOCUS_LEDGER])
            assertEquals(0, disk.writes)
        }
    }

    @Test fun retentionWatermarkNeverRewindsInEitherStore() = runBlocking {
        val disk = Disk()
        for (store in listOf(DataStoreFocusStateStore(disk), InMemoryFocusStateStore())) {
            store.transact { FocusMutation(it.copy(minimumRequestEpochMs = 123_456), Unit) }
            val before = snapshot(store)
            val bytes = disk.values.value.asMap().toMap()
            try {
                store.transact { FocusMutation(it.copy(minimumRequestEpochMs = 123_455), Unit) }
                fail("Retention watermark rewind accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, snapshot(store))
            assertEquals(bytes, disk.values.value.asMap())
        }
    }

    @Test fun recoveryFromCorruptValuePublishesReadyOnlyWhenBackingDataIsActuallyRepaired() = runBlocking {
        val disk = Disk(mutablePreferencesOf(PrefsKeys.BLOCK_RULES to "broken"))
        val states = mutableListOf<StorageState<List<BlockRule>>>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { DataStoreBlockRulesStore(disk).rulesState.take(3).toList(states) }
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), states.last())
        disk.values.value = mutablePreferencesOf(PrefsKeys.BLOCK_RULES to PrefsCodec.encodeRules(listOf(rule)))
        collector.join()
        assertEquals(listOf(StorageState.Loading, StorageState.Unavailable(StorageFailure.CORRUPT), StorageState.Ready(listOf(rule))), states)
    }

    @Test fun expiredSweepRechecksTheCurrentDeadlineAndPreservesRenewedRules() = runBlocking {
        for (store in listOf(DataStoreBlockRulesStore(Disk()), InMemoryBlockRulesStore())) {
            val expired = rule.copy(id = "session-old", expiresAtEpochMs = 999)
            val renewed = rule.copy(id = "session-renewed", expiresAtEpochMs = 5000)
            store.replaceAll(listOf(rule, expired, renewed))
            store.pruneExpired(1000)
            assertEquals(listOf(rule, renewed), store.rules.first())
        }
    }

    @Test fun everyFocusChangeAdvancesOneDurableRevisionAndNoOpRetriesDoNotAdvanceIt() = runBlocking {
        val disk = Disk()
        val store = DataStoreFocusStateStore(disk)
        store.setDefaultBreakLevel(BreakLevel.HARDER)
        assertEquals(1L, snapshot(store).revision)
        store.setOpenCounts(OpenCountState(20_000, mapOf("app" to 1)))
        assertEquals(2L, snapshot(store).revision)
        val granted = store.transact(::grant) as FocusCommit.Committed
        assertEquals(3L, granted.snapshot.revision)
        assertEquals(3L, (store.transact(::grant) as FocusCommit.Committed).snapshot.revision)
        assertEquals(3L, snapshot(DataStoreFocusStateStore(disk)).revision)
    }

    @Test fun callerCannotRewindFocusRevisionAndFailedWritesDoNotAdvanceIt() = runBlocking {
        val disk = Disk()
        val store = DataStoreFocusStateStore(disk)
        store.transact(::grant)
        val before = disk.values.value.asMap().toMap()
        try { store.transact { FocusMutation(it.copy(revision = 0), Unit) }; fail("Revision rewind accepted") }
        catch (_: IllegalArgumentException) { }
        disk.failWrite = true
        assertEquals(FocusCommit.Unavailable(StorageFailure.IO), store.transact { FocusMutation(it.copy(defaultBreakLevel = BreakLevel.HARDER), Unit) })
        assertEquals(before, disk.values.value.asMap())
        assertEquals(1L, snapshot(store).revision)
    }

    @Test fun boundedSnapshotsFinishOnUnavailableCompletedOrNeverReadyFlows() = runBlocking {
        for (reason in StorageFailure.entries) {
            val state = flowOf<StorageState<Int>>(StorageState.Loading, StorageState.Unavailable(reason))
            assertNull(withTimeout(500) { state.snapshotOrNull() })
        }
        assertNull(emptyFlow<StorageState<Int>>().snapshotOrNull())
        assertNull(flow<StorageState<Int>> { emit(StorageState.Loading); awaitCancellation() }.snapshotOrNull(timeoutMs = 1))
        assertEquals(42, flowOf<StorageState<Int>>(StorageState.Loading, StorageState.Ready(42)).snapshotOrNull())
    }

    @Test fun fileCorruptionEmitsUnavailableOnceWithoutPollingOrRetry() = runBlocking {
        var reads = 0
        val disk = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { reads++; throw CorruptionException("bad file") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("No writes")
        }
        allStates(disk).forEach { states ->
            assertEquals(listOf(StorageState.Loading, StorageState.Unavailable(StorageFailure.CORRUPT)),
                withTimeout(500) { states.toList() })
        }
        assertEquals(5, reads)
    }

    @Test fun currentEncoderAndStrictValidatorAgreeOnHostileAtomsAndV1Literal() {
        val hostile = BlockRule("id|~%,\n", "Name|~%,\n", BlockTargets(apps = setOf(AppRef("app", null)), allowListMode = true),
            schedule = Schedule(emptySet(), 0, 1439), breakLevel = BreakLevel.HARDER)
        val literal = "id%7C%7E%25%2C%0A|Name%7C%7E%25%2C%0A|true|HARDER|true|app~|||0|1439|-1"
        assertEquals(literal, PrefsCodec.encodeRules(listOf(hostile)))
        assertEquals(listOf(hostile), StrictPrefsCodec.rules(PrefsCodec.encodeRules(listOf(hostile))))
        assertEquals(literal, PrefsCodec.encodeRules(StrictPrefsCodec.rules(literal)))
    }

    @Test fun blankAndTruncatedReceiptRowsAreCorruptRatherThanFutureVersions() {
        for (payload in listOf("H|1|0\n", "H|1|0\nE", "H|1|0\nB", "H|1|")) {
            val cause = assertThrows(StorageUnavailableException::class.java) { FocusLedgerCodec.decode(payload) }
            assertEquals(StorageFailure.CORRUPT, cause.reason)
        }
    }
}
