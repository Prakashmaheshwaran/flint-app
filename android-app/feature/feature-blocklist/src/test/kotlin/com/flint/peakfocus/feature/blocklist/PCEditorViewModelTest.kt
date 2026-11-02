package com.flint.peakfocus.feature.blocklist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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
class PCEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val at = 1_793_181_600_000L
    private class Disk : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        var fail = false
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(values.value)
            if (fail) throw IOException("full")
            values.value = next
            return next
        }
    }
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owners.forEach { it.clear() }; Dispatchers.resetMain() }
    private fun own(vm: androidx.lifecycle.ViewModel) = ViewModelStore().also { it.put("test", vm); owners += it }

    @Test fun `PC-17 real persisted Harder wait matures without ending and hold commits once`() = runTest(dispatcher) {
        val disk = Disk(); val store = DataStoreEditorCommitmentStore(disk) { "UTC" }
        fun clock() = AccessClockStamp(at + testScheduler.currentTime, testScheduler.currentTime, "boot")
        val timer = timerRule(TimerDraft(apps = setOf(AppRef("app")), durationMinutes = 5, breakLevel = BreakLevel.HARDER), at, "strict")
        store.startTimer(timer, clock())
        val rules = DataStoreBlockRulesStore(disk)
        val vm = TimerViewModel(rules, { emptyList() }, store, DataStoreHarderWaitStore(disk),
            now = { clock().wallEpochMs }, ioDispatcher = dispatcher, protectionClock = ::clock)
        own(vm); runCurrent(); vm.setVisible(true)
        try {
        vm.requestExit(timer.id); runCurrent()
        assertEquals(TimerExitPhase.WAITING, vm.uiState.value.exit?.phase)
        assertFalse(vm.beginExitHold())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(timer), rules.rules.first())
        assertEquals(TimerExitPhase.IDLE, vm.uiState.value.exit?.phase)
        assertTrue(vm.beginExitHold())
        advanceTimeBy(1_999); runCurrent(); assertEquals(listOf(timer), rules.rules.first())
        advanceTimeBy(801); runCurrent()
        assertTrue(rules.rules.first().isEmpty()); assertNull(vm.uiState.value.exit)
        assertFalse(vm.beginExitHold())
        } finally { vm.setVisible(false); owners.last().clear(); runCurrent() }
    }

    @Test fun `PC-68 new Hardcore Timer has no ordinary hold route`() = runTest(dispatcher) {
        val disk = Disk(); val store = DataStoreEditorCommitmentStore(disk)
        val clock = AccessClockStamp(at, 0, "boot")
        val timer = timerRule(TimerDraft(apps = setOf(AppRef("app")), durationMinutes = 5, breakLevel = BreakLevel.HARDCORE), at, "strict")
        store.startTimer(timer, clock)
        val rules = DataStoreBlockRulesStore(disk)
        val vm = TimerViewModel(rules, { emptyList() }, store, DataStoreHarderWaitStore(disk), now = { at }, ioDispatcher = dispatcher, protectionClock = { clock })
        own(vm); runCurrent(); vm.requestExit(timer.id); runCurrent()
        assertFalse(canEndNativeTimer(timer)); assertFalse(vm.beginExitHold()); assertNull(vm.uiState.value.exit)
        assertEquals(listOf(timer), rules.rules.first())
    }

    @Test fun `PC-10 explicit confirmation acknowledges only durable success and failure keeps pending draft`() = runTest(dispatcher) {
        val disk = Disk(); val store = DataStoreEditorCommitmentStore(disk) { "UTC" }
        val clock = AccessClockStamp(at, 0, "boot")
        val vm = BlocklistViewModel(DataStoreBlockRulesStore(disk), DataStoreLimitsStore(disk), DataStoreFocusStateStore(disk), InMemoryGroupsStore(),
            { emptyList() }, store, DataStoreHarderWaitStore(disk), dispatcher, { "new" }, clock = { clock }, safety = { EditorSafety(true) })
        own(vm); runCurrent()
        var acknowledged = 0
        val draft = RuleDraft(name = "Kept draft", apps = setOf(AppRef("app")), scheduled = true)
        vm.saveRule(draft) { acknowledged++ }; runCurrent()
        assertEquals(0, acknowledged)
        val pending = (store.state.first { it is StorageState.Ready } as StorageState.Ready).value.pending!!
        disk.fail = true; vm.confirmChange(pending.id); runCurrent()
        assertEquals(0, acknowledged); assertNotNull(vm.editorSave.value.error)
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
        disk.fail = false; vm.confirmChange(pending.id); runCurrent()
        assertEquals(1, acknowledged); assertEquals("Kept draft", DataStoreBlockRulesStore(disk).rules.first().single().name)
    }

    @Test fun `PC-53 picker load error is distinct from empty and retries`() = runTest(dispatcher) {
        val disk = Disk(); var fail = true
        val vm = BlocklistViewModel(DataStoreBlockRulesStore(disk), DataStoreLimitsStore(disk), DataStoreFocusStateStore(disk), InMemoryGroupsStore(),
            { if (fail) throw IOException("package manager") else emptyList() }, DataStoreEditorCommitmentStore(disk), DataStoreHarderWaitStore(disk), dispatcher,
            clock = { AccessClockStamp(at, 0, "boot") })
        own(vm); runCurrent(); assertTrue(vm.installedApps.value is InstalledAppsUiState.Error)
        fail = false; vm.retryInstalledApps(); runCurrent()
        assertEquals(InstalledAppsUiState.Ready(emptyList()), vm.installedApps.value)
    }
}
