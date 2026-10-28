package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the DataStore-backed stores against an in-process fake [DataStore] — exercises
 * the real codec + key wiring without Android or disk. (The `preferencesDataStore` file plumbing
 * itself is framework code we deliberately don't re-test here.)
 */
class FlintStoresTest {

    /** Minimal faithful fake: serial [updateData] against an in-memory [Preferences]. */
    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(state.value)
            state.value = next
            next
        }
    }

    private val ruleA = BlockRule(
        id = "a",
        name = "Mornings",
        targets = BlockTargets(apps = setOf(AppRef("com.instagram.android", "Instagram"))),
        schedule = Schedule(daysOfWeek = setOf(1, 2, 3, 4, 5), startMinuteOfDay = 480, endMinuteOfDay = 720),
        breakLevel = BreakLevel.HARDER,
    )
    private val ruleB = BlockRule(
        id = "b",
        name = "Always",
        targets = BlockTargets(apps = setOf(AppRef("com.tiktok.android"))),
        breakLevel = BreakLevel.HARDCORE,
    )

    // MARK: BlockRulesStore (DataStore-backed)

    @Test
    fun strictSessionReadPropagatesIoFailureInsteadOfInventingAnEmptyStore() = runBlocking {
        val failed = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("storage unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("storage unavailable")
        }
        val store = DataStoreBlockRulesStore(failed)
        assertEquals(com.flint.peakfocus.core.model.StorageState.Unavailable(com.flint.peakfocus.core.model.StorageFailure.IO),
            store.rulesState.first { it is com.flint.peakfocus.core.model.StorageState.Unavailable })
        try {
            store.rulesOrError.first()
            throw AssertionError("Read failure was hidden")
        } catch (_: IOException) { }
        try {
            store.startSessionIfIdle(ruleB.copy(id = "session-new", expiresAtEpochMs = 20_000L), 10_000L)
            throw AssertionError("Failed persistence reported success")
        } catch (_: IOException) { }
    }

    @Test
    fun rulesStartEmptyAndUpsertAppends() {
        runBlocking {
            val store = DataStoreBlockRulesStore(FakePreferencesDataStore())
            assertEquals(emptyList<BlockRule>(), store.rules.first())
            store.upsert(ruleA)
            store.upsert(ruleB)
            assertEquals(listOf(ruleA, ruleB), store.rules.first())
        }
    }

    @Test
    fun upsertReplacesTheRuleWithTheSameId() {
        runBlocking {
            val store = DataStoreBlockRulesStore(FakePreferencesDataStore())
            store.upsert(ruleA)
            store.upsert(ruleB)
            val renamed = ruleA.copy(name = "Renamed", breakLevel = BreakLevel.HARDCORE)
            store.upsert(renamed)
            assertEquals(listOf(ruleB, renamed), store.rules.first())
        }
    }

    @Test
    fun deleteAndSetEnabledTouchOnlyTheirRule() {
        runBlocking {
            val store = DataStoreBlockRulesStore(FakePreferencesDataStore())
            store.replaceAll(listOf(ruleA, ruleB))
            store.setEnabled("a", enabled = false)
            assertEquals(listOf(ruleA.copy(enabled = false), ruleB), store.rules.first())
            store.delete("a")
            assertEquals(listOf(ruleB), store.rules.first())
            store.delete("missing") // no-op, no throw
            assertEquals(listOf(ruleB), store.rules.first())
        }
    }

    @Test
    fun batchSetEnabledFlipsAllNamedRulesInOneWriteAndIgnoresMissingIds() {
        runBlocking {
            val store = DataStoreBlockRulesStore(FakePreferencesDataStore())
            store.replaceAll(listOf(ruleA, ruleB))
            store.setEnabled(setOf("a", "b", "missing"), enabled = false)
            assertEquals(
                listOf(ruleA.copy(enabled = false), ruleB.copy(enabled = false)),
                store.rules.first(),
            )
            // The in-memory double honors the same contract (previews/tests swap it in).
            val inMemory = InMemoryBlockRulesStore(listOf(ruleA, ruleB))
            inMemory.setEnabled(setOf("b"), enabled = false)
            assertEquals(listOf(ruleA, ruleB.copy(enabled = false)), inMemory.rules.first())
        }
    }

    @Test
    fun rulesSurviveAcrossStoreInstancesOnTheSameDataStore() {
        runBlocking {
            val dataStore = FakePreferencesDataStore()
            DataStoreBlockRulesStore(dataStore).upsert(ruleA)
            // A fresh store over the same DataStore sees the persisted encoding, not memory.
            assertEquals(listOf(ruleA), DataStoreBlockRulesStore(dataStore).rules.first())
        }
    }

    // MARK: GroupsStore (DataStore-backed + in-memory contract)

    @Test
    fun sessionStartIsAtomicAcrossConcurrentWriters() = runBlocking {
        val data = FakePreferencesDataStore()
        val stores = listOf(DataStoreBlockRulesStore(data), InMemoryBlockRulesStore())
        for (store in stores) {
            store.upsert(ruleA)
            val results = (1..40).map { index ->
                async(Dispatchers.Default) {
                    store.startSessionIfIdle(
                        ruleB.copy(id = "session-$index", expiresAtEpochMs = 20_000L),
                        nowEpochMs = 10_000L,
                    )
                }
            }.awaitAll()
            assertEquals(1, results.count { it })
            assertEquals(2, store.rules.first().size)
            assertEquals(ruleA, store.rules.first().first { it.id == ruleA.id })
        }
    }

    @Test
    fun expiredAndDisabledSessionsDoNotPreventStartOrGetDeleted() = runBlocking {
        for (store in listOf(DataStoreBlockRulesStore(FakePreferencesDataStore()), InMemoryBlockRulesStore())) {
            val expired = ruleB.copy(id = "session-expired", expiresAtEpochMs = 10_000L)
            val disabled = ruleB.copy(id = "session-disabled", enabled = false)
            val next = ruleB.copy(id = "session-next", expiresAtEpochMs = 11_000L)
            store.replaceAll(listOf(ruleA, expired, disabled))
            assertTrue(store.startSessionIfIdle(next, 10_000L))
            assertEquals(listOf(ruleA, expired, disabled, next), store.rules.first())
            store.delete(next.id)
            assertEquals(listOf(ruleA, expired, disabled), store.rules.first())
            assertFalse(store.startSessionIfIdle(next.copy(id = disabled.id), 10_000L))
        }
    }

    @Test
    fun activeLegacySessionPreventsTimerEvenWithoutDeadline() = runBlocking {
        for (store in listOf(DataStoreBlockRulesStore(FakePreferencesDataStore()), InMemoryBlockRulesStore())) {
            val existing = ruleB.copy(id = "session-legacy")
            store.upsert(existing)
            assertFalse(store.startSessionIfIdle(ruleB.copy(id = "session-new", expiresAtEpochMs = 20_000L), 10_000L))
            assertEquals(listOf(existing), store.rules.first())
        }
    }

    @Test
    fun cancellationChecksLatestStrengthAndPreservesUnrelatedRules() = runBlocking {
        for (store in listOf(DataStoreBlockRulesStore(FakePreferencesDataStore()), InMemoryBlockRulesStore())) {
            val session = ruleB.copy(id = "session-cancel", breakLevel = BreakLevel.EASY, expiresAtEpochMs = 20_000L)
            store.replaceAll(listOf(ruleA, session))
            store.upsert(session.copy(breakLevel = BreakLevel.HARDCORE))
            assertFalse(store.cancelEasySession(session.id, 10_000L))
            assertEquals(BreakLevel.HARDCORE, store.rules.first().last().breakLevel)
            store.upsert(session)
            val results = (1..20).map { async(Dispatchers.Default) { store.cancelEasySession(session.id, 10_000L) } }.awaitAll()
            assertEquals(1, results.count { it })
            assertEquals(listOf(ruleA), store.rules.first())
            assertFalse(store.cancelEasySession(ruleA.id, 10_000L))
            store.upsert(session)
            assertFalse(store.cancelEasySession(session.id, 20_000L))
            store.replaceAll(listOf(session, session.copy(breakLevel = BreakLevel.HARDCORE)))
            assertFalse(store.cancelEasySession(session.id, 10_000L))
            assertEquals(2, store.rules.first().size)
        }
    }

    @Test
    fun invalidSessionStartCannotAlterStoredRules() = runBlocking {
        val store = DataStoreBlockRulesStore(FakePreferencesDataStore())
        store.upsert(ruleA)
        val valid = ruleB.copy(id = "session-new", expiresAtEpochMs = 20_000L)
        for (invalid in listOf(valid.copy(id = "normal"), valid.copy(enabled = false),
            valid.copy(expiresAtEpochMs = null), valid.copy(expiresAtEpochMs = 10_000L),
            valid.copy(schedule = ruleA.schedule))) {
            try {
                store.startSessionIfIdle(invalid, 10_000L)
                throw AssertionError("Invalid session was accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(ruleA), store.rules.first())
        }
    }

    @Test
    fun groupsUpsertReplaceDeleteAndSurviveAcrossInstances() {
        runBlocking {
            val dataStore = FakePreferencesDataStore()
            val store = DataStoreGroupsStore(dataStore)
            assertEquals(emptyList<AppGroup>(), store.groups.first())

            val social = AppGroup("g-1", "Social", apps = setOf(AppRef("com.instagram.android")))
            val news = AppGroup("g-2", "News", domains = setOf(DomainRef("news.ycombinator.com")))
            store.upsert(social)
            store.upsert(news)
            assertEquals(listOf(social, news), store.groups.first())

            val renamed = social.copy(name = "Distractions")
            store.upsert(renamed)
            assertEquals(listOf(news, renamed), store.groups.first())

            store.delete("g-2")
            store.delete("missing") // no-op, no throw
            assertEquals(listOf(renamed), store.groups.first())

            // A fresh store over the same DataStore sees the persisted encoding, not memory.
            assertEquals(listOf(renamed), DataStoreGroupsStore(dataStore).groups.first())
        }
    }

    @Test
    fun nativeTimerEndIsAtomicForAllTiersAndPreservesOtherRules() = runBlocking {
        for (store in listOf(DataStoreBlockRulesStore(FakePreferencesDataStore()), InMemoryBlockRulesStore())) {
            for (tier in BreakLevel.entries) {
                val timer = ruleB.copy(id = "session-timer-native", breakLevel = tier, expiresAtEpochMs = 20_000)
                val otherTimer = timer.copy(id = "session-timer-other")
                store.replaceAll(listOf(ruleA, otherTimer, timer))
                val results = (1..20).map { async(Dispatchers.Default) { store.endTimerSession(timer, 10_000) } }.awaitAll()
                assertEquals(1, results.count { it })
                assertEquals(listOf(ruleA, otherTimer), store.rules.first())
            }
        }
    }

    @Test
    fun nativeTimerEndRejectsEveryChangedSnapshotAndAmbiguousIdentity() = runBlocking {
        for (store in listOf(DataStoreBlockRulesStore(FakePreferencesDataStore()), InMemoryBlockRulesStore())) {
            val timer = ruleB.copy(id = "session-timer-native", expiresAtEpochMs = 20_000)
            val changes = listOf(
                timer.copy(name = "Renamed"), timer.copy(breakLevel = BreakLevel.EASY),
                timer.copy(targets = ruleA.targets), timer.copy(expiresAtEpochMs = 30_000),
                timer.copy(enabled = false), timer.copy(schedule = ruleA.schedule),
            )
            for (changed in changes) {
                store.replaceAll(listOf(ruleA, changed))
                assertFalse(store.endTimerSession(timer, 10_000))
                assertEquals(listOf(ruleA, changed), store.rules.first())
            }
            store.replaceAll(listOf(timer, timer))
            assertFalse(store.endTimerSession(timer, 10_000))
            assertEquals(2, store.rules.first().size)
        }
    }

    @Test
    fun nativeTimerEndRejectsLegacyOrdinaryInactiveAndScheduledRules() = runBlocking {
        for (store in listOf(DataStoreBlockRulesStore(FakePreferencesDataStore()), InMemoryBlockRulesStore())) {
            val timer = ruleB.copy(id = "session-timer-native", expiresAtEpochMs = 20_000)
            val invalid = listOf(
                timer.copy(id = "session-legacy"), timer.copy(id = "work"),
                timer.copy(id = NATIVE_TIMER_ID_PREFIX), timer.copy(enabled = false),
                timer.copy(schedule = ruleA.schedule), timer.copy(expiresAtEpochMs = 10_000),
            )
            for (rule in invalid) {
                store.replaceAll(listOf(rule))
                assertFalse(store.endTimerSession(rule, 10_000))
                assertEquals(listOf(rule), store.rules.first())
            }
            store.replaceAll(listOf(timer))
            assertFalse(store.endTimerSession(timer, -1))
            assertEquals(listOf(timer), store.rules.first())
        }
    }

    @Test
    fun nativeTimerEndPersistsAcrossStoreInstances() = runBlocking {
        val data = FakePreferencesDataStore()
        val timer = ruleB.copy(id = "session-timer-native", expiresAtEpochMs = 20_000)
        val first = DataStoreBlockRulesStore(data)
        first.replaceAll(listOf(ruleA, timer))
        assertTrue(first.endTimerSession(timer, 10_000))
        assertEquals(listOf(ruleA), DataStoreBlockRulesStore(data).rules.first())
    }

    @Test
    fun inMemoryGroupsStoreMatchesContract() {
        runBlocking {
            val store = InMemoryGroupsStore()
            val group = AppGroup("g", "Focus", apps = setOf(AppRef("com.x")))
            store.upsert(group)
            assertEquals(listOf(group), store.groups.first())
            store.delete("g")
            assertEquals(emptyList<AppGroup>(), store.groups.first())
        }
    }

    // MARK: LimitsStore (DataStore-backed)

    @Test
    fun timeAndOpenLimitsSetReplaceAndClearIndependently() {
        runBlocking {
            val store = DataStoreLimitsStore(FakePreferencesDataStore())
            assertEquals(emptyList<TimeLimit>(), store.timeLimits.first())
            assertEquals(emptyList<OpenLimit>(), store.openLimits.first())

            store.setTimeLimit(TimeLimit("com.instagram.android", 45))
            store.setOpenLimit(OpenLimit("com.instagram.android", 5, BreakLevel.HARDCORE))
            store.setTimeLimit(TimeLimit("com.instagram.android", 30)) // replaces, not duplicates
            assertEquals(listOf(TimeLimit("com.instagram.android", 30)), store.timeLimits.first())
            assertEquals(
                listOf(OpenLimit("com.instagram.android", 5, BreakLevel.HARDCORE)),
                store.openLimits.first(),
            )

            store.clearTimeLimit("com.instagram.android")
            assertEquals(emptyList<TimeLimit>(), store.timeLimits.first())
            // clearing a time limit must not touch the open limit
            assertEquals(1, store.openLimits.first().size)
            store.clearOpenLimit("com.instagram.android")
            assertEquals(emptyList<OpenLimit>(), store.openLimits.first())
        }
    }

    // MARK: FocusStateStore (DataStore-backed)

    @Test
    fun focusStateDefaultsAreSaneOnAnEmptyStore() {
        runBlocking {
            val store = DataStoreFocusStateStore(FakePreferencesDataStore())
            assertEquals(BreakLevel.EASY, store.defaultBreakLevel.first())
            assertEquals(EmergencyPassState(), store.emergencyPass.first())
            assertEquals(BreakSessionState(), store.breakSession.first())
            assertEquals(OpenCountState(), store.openCounts.first())
        }
    }

    @Test
    fun focusStateRoundTripsEveryField() {
        runBlocking {
            val store = DataStoreFocusStateStore(FakePreferencesDataStore())

            store.setDefaultBreakLevel(BreakLevel.HARDCORE)
            assertEquals(BreakLevel.HARDCORE, store.defaultBreakLevel.first())

            val spent = EmergencyPassState(weekStartEpochDay = 20633L, usedAtEpochMs = 1_780_000_000_000L)
            store.setEmergencyPass(spent)
            assertEquals(spent, store.emergencyPass.first())

            val session = BreakSessionState(breaksTaken = 2, pending = BreakRequestState(10L, 70L))
            store.setBreakSession(session)
            assertEquals(session, store.breakSession.first())

            val counts = OpenCountState(epochDay = 20637L, opensByPackage = mapOf("com.x" to 3, "com.y" to 1))
            store.setOpenCounts(counts)
            assertEquals(counts, store.openCounts.first())
        }
    }

    @Test
    fun clearingTheUsedPassRemovesTheUnderlyingKey() {
        runBlocking {
            val store = DataStoreFocusStateStore(FakePreferencesDataStore())
            store.setEmergencyPass(EmergencyPassState(20633L, usedAtEpochMs = 42L))
            assertEquals(42L, store.emergencyPass.first().usedAtEpochMs)
            // next-week refresh persists a state with usedAt = null → key must actually clear
            store.setEmergencyPass(EmergencyPassState(20640L, usedAtEpochMs = null))
            val current = store.emergencyPass.first()
            assertEquals(20640L, current.weekStartEpochDay)
            assertNull(current.usedAtEpochMs)
        }
    }

    // MARK: in-memory doubles behave like the real ones

    @Test
    fun inMemoryBlockRulesStoreMatchesContract() {
        runBlocking {
            val store = InMemoryBlockRulesStore()
            store.upsert(ruleA)
            store.upsert(ruleA.copy(name = "Renamed"))
            store.upsert(ruleB)
            assertEquals(listOf(ruleA.copy(name = "Renamed"), ruleB), store.rules.first())
            store.setEnabled("b", enabled = false)
            assertFalse(store.rules.first().single { it.id == "b" }.enabled)
            store.delete("b")
            store.replaceAll(emptyList())
            assertEquals(emptyList<BlockRule>(), store.rules.first())
        }
    }

    @Test
    fun inMemoryLimitsStoreMatchesContract() {
        runBlocking {
            val store = InMemoryLimitsStore()
            store.setTimeLimit(TimeLimit("com.x", 10))
            store.setTimeLimit(TimeLimit("com.x", 20))
            store.setOpenLimit(OpenLimit("com.x", 3))
            assertEquals(listOf(TimeLimit("com.x", 20)), store.timeLimits.first())
            store.clearTimeLimit("com.x")
            assertTrue(store.timeLimits.first().isEmpty())
            assertEquals(listOf(OpenLimit("com.x", 3)), store.openLimits.first())
        }
    }

    @Test
    fun inMemoryFocusStateStoreMatchesContract() {
        runBlocking {
            val store = InMemoryFocusStateStore()
            assertEquals(BreakLevel.EASY, store.defaultBreakLevel.first())
            store.setDefaultBreakLevel(BreakLevel.HARDER)
            store.setEmergencyPass(EmergencyPassState(20633L, 1L))
            store.setBreakSession(BreakSessionState(breaksTaken = 1))
            store.setOpenCounts(OpenCountState(20637L, mapOf("com.x" to 2)))
            assertEquals(BreakLevel.HARDER, store.defaultBreakLevel.first())
            assertEquals(EmergencyPassState(20633L, 1L), store.emergencyPass.first())
            assertEquals(BreakSessionState(breaksTaken = 1), store.breakSession.first())
            assertEquals(2, store.openCounts.first().opensFor("com.x"))
        }
    }
}
