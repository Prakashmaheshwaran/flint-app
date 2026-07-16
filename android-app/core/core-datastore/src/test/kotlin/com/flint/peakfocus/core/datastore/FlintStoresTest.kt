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
import kotlinx.coroutines.runBlocking
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
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
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
