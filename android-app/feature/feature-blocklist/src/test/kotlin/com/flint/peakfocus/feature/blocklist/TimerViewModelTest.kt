package com.flint.peakfocus.feature.blocklist

import androidx.lifecycle.ViewModelStore
import com.flint.peakfocus.core.datastore.BlockRulesStore
import com.flint.peakfocus.core.datastore.InMemoryBlockRulesStore
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private var clock = 1_000_000L
    private val app = InstalledApp("example.app", "Example", null)
    private val otherApp = InstalledApp("other.app", "Other", null)

    @Before fun prepareMain() { Dispatchers.setMain(dispatcher) }

    @After fun clearModels() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun model(store: BlockRulesStore, apps: () -> List<InstalledApp> = { listOf(app, otherApp) }): TimerViewModel {
        val vm = TimerViewModel(store, apps, now = { clock }, newId = { "test-id" }, ioDispatcher = dispatcher)
        ViewModelStore().also { it.put("timer", vm); owners += it }
        return vm
    }

    private fun session(id: String = "session-existing", tier: BreakLevel = BreakLevel.EASY): BlockRule =
        BlockRule(id, "Saved timer", BlockTargets(apps = setOf(AppRef(app.packageName, app.label))),
            breakLevel = tier, expiresAtEpochMs = clock + 60_000)

    @Test fun `start requires loaded rules and a selected app`() = runTest(dispatcher) {
        val waiting = MutableSharedFlow<List<BlockRule>>()
        val store = ControlledStore().apply { customFlow = waiting }
        val vm = model(store)
        vm.start()
        runCurrent()
        assertFalse(vm.uiState.value.rulesLoaded)
        assertFalse(vm.uiState.value.canStart)
        assertEquals(0, store.startCalls)
        waiting.emit(emptyList())
        runCurrent()
        assertTrue(vm.uiState.value.rulesLoaded)
        assertFalse(vm.uiState.value.canStart)
        vm.selectApps(listOf(app))
        assertTrue(vm.uiState.value.canStart)
    }

    @Test fun `start persists a frozen preset and tier without replacing unrelated rules`() = runTest(dispatcher) {
        val work = session("work-rule").copy(expiresAtEpochMs = null)
        val store = ControlledStore(listOf(work))
        val vm = model(store)
        runCurrent()
        vm.selectApps(listOf(app))
        vm.selectPreset("digital-sabbath")
        vm.selectBreakLevel(BreakLevel.HARDER)
        vm.start()
        vm.start()
        runCurrent()
        val saved = vm.uiState.value.sessions.single()
        assertEquals(1, store.startCalls)
        assertEquals("Digital Sabbath", saved.name)
        assertEquals(clock + 2880 * 60_000L, saved.expiresAtEpochMs)
        assertEquals(BreakLevel.HARDER, saved.breakLevel)
        assertEquals(setOf(AppRef(app.packageName, app.label)), saved.targets.apps)
        assertTrue(work in store.rules.first())
        vm.selectApps(listOf(otherApp))
        vm.selectDuration(5)
        vm.selectBreakLevel(BreakLevel.EASY)
        assertEquals(saved, vm.uiState.value.sessions.single())
        assertEquals(BreakLevel.HARDER, vm.uiState.value.draft.breakLevel)
    }

    @Test fun `pending picker edits leave the draft untouched until confirmation`() = runTest(dispatcher) {
        val vm = model(InMemoryBlockRulesStore())
        runCurrent()
        vm.selectApps(listOf(app))
        val initial = vm.uiState.value.draft.apps.map { it.packageName }.toSet()
        val pending = AppPickerSelection(initial)
            .withSelection(listOf(app.packageName), false)
            .withSelection(listOf(otherApp.packageName), true)

        vm.refreshClock()
        assertEquals(initial, vm.uiState.value.draft.apps.map { it.packageName }.toSet())
        val fromDraft = AppPickerSelection(vm.uiState.value.draft.apps.map { it.packageName }.toSet())
        assertEquals(listOf(app), fromDraft.selectedApps(listOf(app, otherApp)))

        vm.selectApps(pending.selectedApps(listOf(app, otherApp)))
        assertEquals(setOf(otherApp.packageName), vm.uiState.value.draft.apps.map { it.packageName }.toSet())
        assertTrue(vm.uiState.value.sessions.isEmpty())

        vm.selectApps(pending.withSelection(listOf(otherApp.packageName), false).selectedApps(listOf(app, otherApp)))
        assertTrue(vm.uiState.value.draft.apps.isEmpty())
        assertFalse(vm.uiState.value.canStart)
    }

    @Test fun `new viewmodel restores stored timer and rejects draft changes and another start`() = runTest(dispatcher) {
        val saved = session(tier = BreakLevel.HARDCORE)
        val store = ControlledStore(listOf(saved))
        val vm = model(store)
        runCurrent()
        assertEquals(listOf(saved), vm.uiState.value.sessions)
        vm.selectApps(listOf(otherApp))
        vm.selectDuration(120)
        vm.start()
        vm.cancelSession(saved.id)
        runCurrent()
        assertEquals(0, store.startCalls)
        assertEquals(0, store.cancelCalls)
        assertEquals(listOf(saved), store.rules.first())
    }

    @Test fun `expiry restores idle at exact deadline without deleting unrelated or active rules`() = runTest(dispatcher) {
        val saved = session()
        val work = session("work")
        val store = ControlledStore(listOf(saved, work))
        val vm = model(store)
        runCurrent()
        clock = saved.expiresAtEpochMs!! - 1
        vm.refreshClock()
        assertEquals(listOf(saved), vm.uiState.value.sessions)
        clock++
        vm.refreshClock()
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertTrue(vm.uiState.value.message!!.startsWith("Timer finished"))
        assertEquals(listOf(saved, work), store.rules.first())
        assertEquals(0, store.cancelCalls)
    }

    @Test fun `visible ticker stops offscreen and resumes with actual elapsed time`() = runTest(dispatcher) {
        val saved = session()
        val vm = model(ControlledStore(listOf(saved)))
        runCurrent()
        vm.setVisible(true)
        clock += 1000
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(clock, vm.uiState.value.nowEpochMs)
        vm.setVisible(false)
        val before = vm.uiState.value.nowEpochMs
        clock = saved.expiresAtEpochMs!! + 1000
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(before, vm.uiState.value.nowEpochMs)
        vm.setVisible(true)
        assertEquals(clock, vm.uiState.value.nowEpochMs)
        assertTrue(vm.uiState.value.sessions.isEmpty())
        vm.setVisible(false)
    }

    @Test fun `cancellation removes only exact selected easy session`() = runTest(dispatcher) {
        val first = session("session-one")
        val second = session("session-two")
        val work = session("work")
        val store = ControlledStore(listOf(first, second, work))
        val vm = model(store)
        runCurrent()
        vm.cancelSession(first.id)
        runCurrent()
        assertEquals(listOf(second, work), store.rules.first())
        assertEquals(listOf(second), vm.uiState.value.sessions)
        assertEquals(1, store.cancelCalls)
    }

    @Test fun `harder and hardcore sessions reject cancellation`() = runTest(dispatcher) {
        val sessions = listOf(session("session-harder", BreakLevel.HARDER), session("session-hardcore", BreakLevel.HARDCORE))
        val store = ControlledStore(sessions)
        val vm = model(store)
        runCurrent()
        sessions.forEach { vm.cancelSession(it.id) }
        runCurrent()
        assertEquals(0, store.cancelCalls)
        assertEquals(sessions, store.rules.first())
    }

    @Test fun `atomic cancellation rejects a tier change after the displayed easy snapshot`() = runTest(dispatcher) {
        val saved = session()
        val store = ControlledStore(listOf(saved))
        val vm = model(store)
        runCurrent()
        store.upsert(saved.copy(breakLevel = BreakLevel.HARDCORE))
        vm.cancelSession(saved.id)
        runCurrent()
        assertEquals(1, store.cancelCalls)
        assertEquals(BreakLevel.HARDCORE, store.rules.first().single().breakLevel)
    }

    @Test fun `failed start preserves selection and supports retry`() = runTest(dispatcher) {
        val store = ControlledStore().apply { failStart = true }
        val vm = model(store)
        runCurrent()
        vm.selectApps(listOf(app))
        vm.start()
        runCurrent()
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertEquals(setOf(AppRef(app.packageName, app.label)), vm.uiState.value.draft.apps)
        store.failStart = false
        vm.start()
        runCurrent()
        assertNull(vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.sessions.size)
    }

    @Test fun `failed cancel does not claim the session ended`() = runTest(dispatcher) {
        val saved = session()
        val store = ControlledStore(listOf(saved)).apply { failCancel = true }
        val vm = model(store)
        runCurrent()
        vm.cancelSession(saved.id)
        runCurrent()
        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.message)
        assertEquals(listOf(saved), vm.uiState.value.sessions)
        store.failCancel = false
        vm.cancelSession(saved.id)
        runCurrent()
        assertTrue(vm.uiState.value.sessions.isEmpty())
    }

    @Test fun `atomic start conflict restores competing session without overwriting it`() = runTest(dispatcher) {
        val existing = session()
        val store = ControlledStore()
        val vm = model(store)
        runCurrent()
        vm.selectApps(listOf(app))
        store.beforeStart = { store.upsert(existing) }
        vm.start()
        runCurrent()
        assertEquals(listOf(existing), store.rules.first())
        assertEquals(listOf(existing), vm.uiState.value.sessions)
        assertNotNull(vm.uiState.value.error)
    }

    @Test fun `busy gate ignores a second start while first write is suspended`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val store = ControlledStore().apply { beforeStart = { gate.await() } }
        val vm = model(store)
        runCurrent()
        vm.selectApps(listOf(app))
        vm.start()
        runCurrent()
        assertTrue(vm.uiState.value.busy)
        vm.start()
        vm.clearApps()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, store.startCalls)
        assertEquals(1, vm.uiState.value.sessions.single().targets.apps.size)
    }

    @Test fun `store read failure is distinct from empty and retries honestly`() = runTest(dispatcher) {
        val saved = session()
        val store = ControlledStore(listOf(saved)).apply { failRead = true }
        val vm = model(store)
        runCurrent()
        assertFalse(vm.uiState.value.rulesLoaded)
        assertNotNull(vm.uiState.value.rulesError)
        assertFalse(vm.uiState.value.canStart)
        store.failRead = false
        vm.retryRules()
        assertFalse(vm.uiState.value.rulesLoaded)
        runCurrent()
        assertTrue(vm.uiState.value.rulesLoaded)
        assertNull(vm.uiState.value.rulesError)
        assertEquals(listOf(saved), vm.uiState.value.sessions)
    }

    @Test fun `app enumeration failure can retry without an invented empty list`() = runTest(dispatcher) {
        var failApps = true
        val vm = model(ControlledStore()) {
            if (failApps) throw IOException("unavailable")
            listOf(app)
        }
        runCurrent()
        assertNotNull(vm.uiState.value.appsError)
        assertTrue(vm.uiState.value.appsState is InstalledAppsUiState.Loading)
        failApps = false
        vm.retryApps()
        runCurrent()
        assertNull(vm.uiState.value.appsError)
        assertEquals(listOf(app), (vm.uiState.value.appsState as InstalledAppsUiState.Ready).apps)
    }

    @Test fun `picker edits preserve targets no longer offered by installed app query`() = runTest(dispatcher) {
        var apps = listOf(app, otherApp)
        val vm = model(ControlledStore()) { apps }
        runCurrent()
        vm.selectApps(apps)
        apps = listOf(app)
        vm.retryApps()
        runCurrent()
        vm.selectApps(emptyList())
        assertEquals(setOf(AppRef(otherApp.packageName, otherApp.label)), vm.uiState.value.draft.apps)
        vm.clearApps()
        assertTrue(vm.uiState.value.draft.apps.isEmpty())
    }

    @Test fun `native end requires full hold finish and callback delays then preserves overlaps`() = runTest(dispatcher) {
        val timer = session("session-timer-native", BreakLevel.HARDCORE)
        val overlap = session("permanent-strict", BreakLevel.HARDCORE)
        val store = ControlledStore(listOf(timer, overlap))
        val vm = model(store)
        runCurrent()
        vm.requestExit(timer.id)
        assertTrue(vm.beginExitHold())
        assertFalse(vm.beginExitHold())
        advanceTimeBy(1999); runCurrent()
        assertEquals(TimerExitPhase.HOLDING, vm.uiState.value.exit?.phase)
        assertEquals(0, store.endCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(TimerExitPhase.FINISHING, vm.uiState.value.exit?.phase)
        vm.releaseExitHold() // Release after the threshold cannot cancel the accepted confirmation.
        vm.dismissExit()
        advanceTimeBy(400); runCurrent()
        assertEquals(TimerExitPhase.SETTLING, vm.uiState.value.exit?.phase)
        advanceTimeBy(399); runCurrent()
        assertEquals(0, store.endCalls)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, store.endCalls)
        assertEquals(listOf(overlap), store.rules.first())
        assertNull(vm.uiState.value.exit)
        assertTrue(vm.uiState.value.message!!.startsWith("Timer ended"))
    }

    @Test fun `short hold release resets and repeated taps never accumulate`() = runTest(dispatcher) {
        val timer = session("session-timer-native")
        val store = ControlledStore(listOf(timer))
        val vm = model(store)
        runCurrent(); vm.requestExit(timer.id)
        repeat(5) {
            assertTrue(vm.beginExitHold())
            advanceTimeBy(1999); runCurrent()
            vm.releaseExitHold()
            assertEquals(0f, vm.uiState.value.exit!!.progress)
            assertEquals(TimerExitPhase.IDLE, vm.uiState.value.exit?.phase)
        }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, store.endCalls)
        assertEquals(listOf(timer), store.rules.first())
    }

    @Test fun `dismissal and lifecycle stop cancel incomplete confirmation`() = runTest(dispatcher) {
        val timer = session("session-timer-native")
        val store = ControlledStore(listOf(timer))
        val vm = model(store)
        runCurrent()
        vm.requestExit(timer.id); vm.beginExitHold()
        advanceTimeBy(1000); runCurrent(); vm.dismissExit()
        advanceTimeBy(3000); runCurrent()
        assertNull(vm.uiState.value.exit)
        vm.requestExit(timer.id); vm.beginExitHold()
        advanceTimeBy(1000); runCurrent(); vm.setVisible(false)
        advanceTimeBy(3000); runCurrent()
        assertNull(vm.uiState.value.exit)
        assertEquals(0, store.endCalls)
    }

    @Test fun `completed hold commits through lifecycle stop without producing a grant`() = runTest(dispatcher) {
        val timer = session("session-timer-native", BreakLevel.HARDER)
        val store = ControlledStore(listOf(timer))
        val vm = model(store)
        runCurrent(); vm.requestExit(timer.id); vm.beginExitHold()
        advanceTimeBy(2000); runCurrent()
        vm.setVisible(false)
        advanceTimeBy(800); runCurrent()
        assertEquals(1, store.endCalls)
        assertTrue(store.rules.first().isEmpty())
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun `stale confirmation cannot end a changed timer`() = runTest(dispatcher) {
        val timer = session("session-timer-native")
        val changed = timer.copy(breakLevel = BreakLevel.HARDCORE, name = "Changed")
        val store = ControlledStore(listOf(timer))
        val vm = model(store)
        runCurrent(); vm.requestExit(timer.id); vm.beginExitHold()
        advanceTimeBy(1000); runCurrent(); store.upsert(changed)
        advanceTimeBy(1800); runCurrent()
        assertEquals(listOf(changed), store.rules.first())
        assertEquals(listOf(changed), vm.uiState.value.sessions)
        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.exit)
    }

    @Test fun `expiry during confirmation does not claim a manual stop`() = runTest(dispatcher) {
        val timer = session("session-timer-native")
        val store = ControlledStore(listOf(timer))
        val vm = model(store)
        runCurrent(); vm.requestExit(timer.id); vm.beginExitHold()
        clock = timer.expiresAtEpochMs!!
        advanceTimeBy(2800); runCurrent()
        assertEquals(listOf(timer), store.rules.first())
        assertTrue(vm.uiState.value.sessions.isEmpty())
        assertFalse(vm.uiState.value.message.orEmpty().startsWith("Timer ended"))
    }

    @Test fun `end write failure retains timer and requires another full confirmation`() = runTest(dispatcher) {
        val timer = session("session-timer-native")
        val store = ControlledStore(listOf(timer)).apply { failEnd = true }
        val vm = model(store)
        runCurrent(); vm.requestExit(timer.id); vm.beginExitHold()
        advanceTimeBy(2800); runCurrent()
        assertEquals(listOf(timer), store.rules.first())
        assertNull(vm.uiState.value.message)
        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.exit)
        assertFalse(vm.uiState.value.busy)
        store.failEnd = false
        assertFalse(vm.beginExitHold())
        vm.requestExit(timer.id); vm.beginExitHold()
        advanceTimeBy(2800); runCurrent()
        assertTrue(store.rules.first().isEmpty())
    }

    @Test fun `suspended end write blocks duplicate commits and other mutations`() = runTest(dispatcher) {
        val timer = session("session-timer-native")
        val gate = CompletableDeferred<Unit>()
        val store = ControlledStore(listOf(timer)).apply { beforeEnd = { gate.await() } }
        val vm = model(store)
        runCurrent(); vm.requestExit(timer.id); vm.beginExitHold()
        advanceTimeBy(2800); runCurrent()
        assertTrue(vm.uiState.value.busy)
        assertEquals(TimerExitPhase.SAVING, vm.uiState.value.exit?.phase)
        assertFalse(vm.beginExitHold())
        vm.requestExit(timer.id); vm.start(); vm.dismissExit()
        assertEquals(1, store.endCalls)
        gate.complete(Unit); runCurrent()
        assertTrue(store.rules.first().isEmpty())
    }

    @Test fun `ordinary and legacy stronger rules never get native end confirmation`() = runTest(dispatcher) {
        val rules = listOf(session("work", BreakLevel.HARDCORE), session("session-legacy", BreakLevel.HARDCORE))
        val store = ControlledStore(rules)
        val vm = model(store)
        runCurrent()
        rules.forEach { vm.requestExit(it.id); assertFalse(vm.beginExitHold()) }
        assertNull(vm.uiState.value.exit)
        assertEquals(0, store.endCalls)
    }

    @Test fun `process recreation forgets an incomplete hold and keeps saved timer`() = runTest(dispatcher) {
        val timer = session("session-timer-native")
        val store = ControlledStore(listOf(timer))
        val first = model(store)
        runCurrent(); first.requestExit(timer.id); first.beginExitHold()
        advanceTimeBy(1000); runCurrent()
        owners.last().clear()
        val restored = model(store)
        advanceTimeBy(5000); runCurrent()
        assertNull(restored.uiState.value.exit)
        assertEquals(listOf(timer), restored.uiState.value.sessions)
        assertEquals(0, store.endCalls)
    }

    private class ControlledStore(initial: List<BlockRule> = emptyList()) : BlockRulesStore {
        private val delegate = InMemoryBlockRulesStore(initial)
        var customFlow: Flow<List<BlockRule>>? = null
        var failRead = false
        var failStart = false
        var failCancel = false
        var startCalls = 0
        var cancelCalls = 0
        var endCalls = 0
        var failEnd = false
        var beforeEnd: suspend () -> Unit = {}
        var beforeStart: suspend () -> Unit = {}
        override val rules: Flow<List<BlockRule>> get() = customFlow ?: flow {
            if (failRead) throw IOException("read failed")
            emitAll(delegate.rules)
        }
        override suspend fun startSessionIfIdle(rule: BlockRule, nowEpochMs: Long): Boolean {
            startCalls++
            beforeStart()
            if (failStart) throw IOException("write failed")
            return delegate.startSessionIfIdle(rule, nowEpochMs)
        }
        override suspend fun upsert(rule: BlockRule) = delegate.upsert(rule)
        override suspend fun cancelEasySession(ruleId: String, nowEpochMs: Long): Boolean {
            cancelCalls++
            if (failCancel) throw IOException("cancel failed")
            return delegate.cancelEasySession(ruleId, nowEpochMs)
        }
        override suspend fun endTimerSession(expected: BlockRule, nowEpochMs: Long): Boolean {
            endCalls++
            beforeEnd()
            if (failEnd) throw IOException("end failed")
            return delegate.endTimerSession(expected, nowEpochMs)
        }
        override suspend fun delete(ruleId: String) = delegate.delete(ruleId)
        override suspend fun setEnabled(ruleId: String, enabled: Boolean) = delegate.setEnabled(ruleId, enabled)
        override suspend fun setEnabled(ruleIds: Set<String>, enabled: Boolean) = delegate.setEnabled(ruleIds, enabled)
        override suspend fun replaceAll(rules: List<BlockRule>) = delegate.replaceAll(rules)
    }
}
