package com.flint.peakfocus.feature.blocklist

import androidx.lifecycle.ViewModelStore
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSaveTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val draft = RuleDraft(name = "My draft", apps = setOf(AppRef("app")), breakLevel = BreakLevel.HARDER)

    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owners.forEach { it.clear() }; Dispatchers.resetMain() }

    private class Rules(private val delegate: InMemoryBlockRulesStore = InMemoryBlockRulesStore()) : BlockRulesStore by delegate {
        var fail = false
        var before: suspend () -> Unit = {}
        var singleWrites = 0
        var batchWrites = 0
        override suspend fun upsert(rule: BlockRule) {
            singleWrites++; before()
            if (fail) throw IOException("write")
            delegate.upsert(rule)
        }
        override suspend fun updateRules(upserts: List<BlockRule>, deleteIds: Set<String>) {
            batchWrites++; before()
            if (fail) throw IOException("write")
            delegate.updateRules(upserts, deleteIds)
        }
    }

    private class Limits(private val delegate: InMemoryLimitsStore = InMemoryLimitsStore()) : LimitsStore by delegate {
        var fail = false
        var before: suspend () -> Unit = {}
        var writes = 0
        override suspend fun setLimits(packageName: String, time: TimeLimit?, open: OpenLimit?) {
            writes++; before()
            if (fail) throw IOException("write")
            delegate.setLimits(packageName, time, open)
        }
    }

    private fun model(rules: BlockRulesStore = Rules(), limits: LimitsStore = Limits(),
        groups: GroupsStore = InMemoryGroupsStore()): BlocklistViewModel {
        val vm = BlocklistViewModel(rules, limits, InMemoryFocusStateStore(), groups, { emptyList() },
            EditorTestStore(rules, limits), TestHarderWaitStore(), dispatcher, { "stable-id" },
            clock = { AccessClockStamp(1_000_000, dispatcher.scheduler.currentTime, "boot") })
        ViewModelStore().also { it.put("editor", vm); owners += it }
        return vm
    }

    @Test fun ruleSuccessIsAcknowledgedOnlyAfterDurableWriteReturns() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val rules = Rules().apply { before = { gate.await() } }
        val vm = model(rules)
        var acknowledgements = 0
        vm.saveRule(draft) { acknowledgements++ }
        runCurrent()
        assertTrue(vm.editorSave.value.busy)
        assertEquals(0, acknowledgements)
        assertTrue(rules.rules.first().isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(1, acknowledgements)
        assertEquals(draft.toRule("stable-id"), rules.rules.first().single())
        assertEquals(EditorSaveState(result = "Saved."), vm.editorSave.value)
    }

    @Test fun failedRuleKeepsDraftOpenAndSameSelectionCanBeRetried() = runTest(dispatcher) {
        val rules = Rules().apply { fail = true }
        val vm = model(rules)
        var closed = false
        vm.saveRule(draft) { closed = true }
        runCurrent()
        assertFalse(closed)
        assertNotNull(vm.editorSave.value.error)
        assertFalse(vm.editorSave.value.busy)
        assertTrue(rules.rules.first().isEmpty())
        rules.fail = false
        vm.saveRule(draft) { closed = true }
        runCurrent()
        assertTrue(closed)
        assertNull(vm.editorSave.value.error)
        assertEquals(draft.toRule("stable-id"), rules.rules.first().single())
    }

    @Test fun sleepSaveUsesOneAtomicBatchAndFailureKeepsBothOldRules() = runTest(dispatcher) {
        val initial = SleepConfig.DEFAULT.copy(morningEnabled = true)
        val rules = Rules(InMemoryBlockRulesStore(initial.toRules())).apply { fail = true }
        val vm = model(rules)
        val changed = initial.copy(morningEnabled = false, bedMinute = initial.bedMinute - 30)
        var acknowledgements = 0
        vm.saveSleep(changed) { acknowledgements++ }; runCurrent()
        assertEquals(0, acknowledgements)
        assertEquals(initial.toRules(), rules.rules.first())
        assertEquals(1, rules.batchWrites)
        assertEquals(0, rules.singleWrites)
        rules.fail = false
        vm.saveSleep(changed) { acknowledgements++ }; runCurrent()
        assertEquals(1, acknowledgements)
        assertEquals(changed.toRules(), rules.rules.first())
    }

    @Test fun limitSaveAcknowledgesBothKindsTogetherAndRetriesTheFrozenValues() = runTest(dispatcher) {
        val limits = Limits().apply { fail = true }
        val vm = model(limits = limits)
        var closed = false
        val time = TimeLimit("app", 15)
        val open = OpenLimit("app", 3, BreakLevel.HARDCORE)
        vm.saveLimits("app", time, open) { closed = true }; runCurrent()
        assertFalse(closed)
        assertTrue(limits.timeLimits.first().isEmpty())
        assertTrue(limits.openLimits.first().isEmpty())
        assertNotNull(vm.editorSave.value.error)
        limits.fail = false
        vm.saveLimits("app", time, open) { closed = true }; runCurrent()
        assertTrue(closed)
        assertEquals(listOf(time), limits.timeLimits.first())
        assertEquals(listOf(open), limits.openLimits.first())
        assertEquals(2, limits.writes)
    }

    @Test fun failedLimitRemovalCannotCloseOrRemoveOnlyOneKind() = runTest(dispatcher) {
        val time = TimeLimit("app", 15)
        val open = OpenLimit("app", 3)
        val limits = Limits(InMemoryLimitsStore(listOf(time), listOf(open))).apply { fail = true }
        val vm = model(limits = limits)
        var closed = false
        vm.clearLimits("app") { closed = true }; runCurrent()
        assertFalse(closed)
        assertEquals(listOf(time), limits.timeLimits.first())
        assertEquals(listOf(open), limits.openLimits.first())
        limits.fail = false
        vm.clearLimits("app") { closed = true }; runCurrent()
        assertTrue(closed)
        assertTrue(limits.timeLimits.first().isEmpty() && limits.openLimits.first().isEmpty())
    }

    @Test fun duplicateSaveCallbacksWhileSuspendedProduceOneWriteAndOneAcknowledgement() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val rules = Rules().apply { before = { gate.await() } }
        val vm = model(rules)
        var acknowledgements = 0
        vm.saveRule(draft) { acknowledgements++ }; runCurrent()
        repeat(10) { vm.saveRule(draft) { acknowledgements++ } }
        assertEquals(1, rules.singleWrites)
        gate.complete(Unit); runCurrent()
        assertEquals(1, acknowledgements)
        assertEquals(1, rules.rules.first().size)
    }

    @Test fun cancellationBeforeCommitNeverAcknowledgesOrFabricatesSuccess() = runTest(dispatcher) {
        val rules = Rules().apply { before = { awaitCancellation() } }
        val vm = model(rules)
        var acknowledged = false
        vm.saveRule(draft) { acknowledged = true }; runCurrent()
        owners.last().clear(); runCurrent()
        assertFalse(acknowledged)
        assertTrue(rules.rules.first().isEmpty())
        assertFalse(vm.editorSave.value.busy)
    }

    @Test fun invalidDraftsNeverAcknowledgeOrWrite() = runTest(dispatcher) {
        val rules = Rules()
        val vm = model(rules)
        var acknowledged = false
        vm.saveRule(RuleDraft()) { acknowledged = true }
        vm.saveSleep(SleepConfig.DEFAULT.copy(bedMinute = -1)) { acknowledged = true }
        runCurrent()
        assertFalse(acknowledged)
        assertEquals(0, rules.singleWrites + rules.batchWrites)
    }

    @Test fun groupNameAndTargetsAreAcknowledgedOnlyOnSuccessfulSave() = runTest(dispatcher) {
        var fail = true
        val stored = InMemoryGroupsStore()
        val groups = object : GroupsStore by stored {
            override suspend fun upsert(group: AppGroup) {
                if (fail) throw IOException("write")
                stored.upsert(group)
            }
        }
        val vm = model(groups = groups)
        var acknowledged = false
        vm.saveGroup("My group", draft.apps, listOf("example.com")) { acknowledged = true }; runCurrent()
        assertFalse(acknowledged)
        assertTrue(stored.groups.first().isEmpty())
        fail = false
        vm.saveGroup("My group", draft.apps, listOf("example.com")) { acknowledged = true }; runCurrent()
        assertTrue(acknowledged)
        assertEquals(draft.apps, stored.groups.first().single().apps)
    }

    @Test fun unreadableRulesAndLimitsAreSurfacedAsUnavailableRatherThanEmpty() = runTest(dispatcher) {
        val rules = object : BlockRulesStore by InMemoryBlockRulesStore() {
            override val rulesState = flowOf<StorageState<List<BlockRule>>>(StorageState.Unavailable(StorageFailure.CORRUPT))
        }
        val limits = object : LimitsStore by InMemoryLimitsStore() {
            override val openLimitsState = flowOf<StorageState<List<OpenLimit>>>(StorageState.Unavailable(StorageFailure.IO))
        }
        val vm = model(rules, limits)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rules.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.limits.collect() }
        runCurrent()
        assertEquals(RulesUiState.Unavailable(StorageFailure.CORRUPT), vm.rules.value)
        assertEquals(LimitsUiState.Unavailable(StorageFailure.IO), vm.limits.value)
    }
}
