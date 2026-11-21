package com.flint.peakfocus.feature.blocklist

import androidx.lifecycle.ViewModelStore
import com.flint.peakfocus.core.datastore.EditorSafety
import com.flint.peakfocus.core.datastore.InMemoryBlockRulesStore
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 3.2: tile/shortcut requests may only pre-fill the Start focus draft, never commit it. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerQuickStartTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val clock = 1_000_000L
    private val app = InstalledApp("example.app", "Example", null)
    private val otherApp = InstalledApp("other.app", "Other", null)
    private val work = AppGroup("g-work", "Work\napps", setOf(AppRef("example.app", "Old label"), AppRef("gone.app", "Gone")))

    @Before fun prepareMain() { Dispatchers.setMain(dispatcher) }

    @After fun clearModels() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun stamp() = AccessClockStamp(clock, dispatcher.scheduler.currentTime, "boot")

    private fun model(
        store: InMemoryBlockRulesStore = InMemoryBlockRulesStore(),
        groups: Map<String, AppGroup> = mapOf(work.id to work),
        apps: () -> List<InstalledApp> = { listOf(app, otherApp) },
        permissions: TimerPermissions = TimerPermissions(accessibilityEnabled = true),
        livePathA: Boolean = true,
        loadGroup: (suspend (String) -> AppGroup?)? = null,
    ): TimerViewModel {
        val vm = TimerViewModel(store, apps, EditorTestStore(store, now = { clock }), TestHarderWaitStore(),
            now = { clock }, newId = { "test-id" }, ioDispatcher = dispatcher,
            protectionClock = ::stamp,
            safety = { EditorSafety(true) },
            protectionStatus = MutableStateFlow(readyTimerStatus(stamp())),
            loadPermissions = { permissions }, livePathA = { livePathA },
            loadGroup = loadGroup ?: { id -> if (id == "failing") throw IOException("unreadable") else groups[id] })
        ViewModelStore().also { it.put("timer", vm); owners += it }
        return vm
    }

    @Test fun `a saved group pre-fills only installed apps and never saves or starts`() = runTest(dispatcher) {
        val store = InMemoryBlockRulesStore()
        val vm = model(store)
        runCurrent()
        vm.selectDuration(45)
        vm.selectBreakLevel(BreakLevel.HARDER)
        vm.prepareQuickStart(FocusQuickStart(1, groupId = work.id))
        runCurrent()
        val state = vm.uiState.value
        assertEquals(setOf(AppRef("example.app", "Example")), state.draft.apps)
        assertEquals("Duration is the user's", 45, state.draft.durationMinutes)
        assertEquals("Break level is the user's", BreakLevel.HARDER, state.draft.breakLevel)
        assertEquals("From your “Work apps” group · 1 not installed. Review, then Begin.", state.draftNote)
        assertTrue("Begin is available but untouched", state.canStart)
        assertTrue("Nothing is saved without Begin", store.rules.first().isEmpty())
        assertTrue(state.sessions.isEmpty())
    }

    @Test fun `plain or missing-group requests leave the draft and explain`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.selectApps(listOf(otherApp))
        vm.prepareQuickStart(FocusQuickStart(1))
        runCurrent()
        assertEquals(setOf(AppRef("other.app", "Other")), vm.uiState.value.draft.apps)
        assertNull(vm.uiState.value.draftNote)

        vm.prepareQuickStart(FocusQuickStart(2, groupId = "deleted"))
        runCurrent()
        assertEquals(setOf(AppRef("other.app", "Other")), vm.uiState.value.draft.apps)
        assertEquals("That group is no longer saved. Choose what rests.", vm.uiState.value.draftNote)

        vm.prepareQuickStart(FocusQuickStart(3, groupId = "failing"))
        runCurrent()
        assertEquals("That group is no longer saved. Choose what rests.", vm.uiState.value.draftNote)
    }

    @Test fun `a group with no installed apps does not clear the draft`() = runTest(dispatcher) {
        val gone = AppGroup("g-gone", "Gone", setOf(AppRef("gone.app", "Gone")))
        val vm = model(groups = mapOf(gone.id to gone))
        runCurrent()
        vm.selectApps(listOf(app))
        vm.prepareQuickStart(FocusQuickStart(1, groupId = gone.id))
        runCurrent()
        assertEquals(setOf(AppRef("example.app", "Example")), vm.uiState.value.draft.apps)
        assertEquals("None of the apps in “Gone” are installed. Choose what rests.", vm.uiState.value.draftNote)
    }

    @Test fun `a running timer is never replaced shortened or ended by a request`() = runTest(dispatcher) {
        val running = BlockRule("session-existing", "Saved timer",
            BlockTargets(apps = setOf(AppRef(otherApp.packageName, otherApp.label))),
            breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = clock + 60_000)
        val store = InMemoryBlockRulesStore(listOf(running))
        val vm = model(store)
        runCurrent()
        val before = vm.uiState.value
        vm.prepareQuickStart(FocusQuickStart(1, groupId = work.id))
        runCurrent()
        assertEquals(listOf(running), store.rules.first())
        assertEquals(before.sessions, vm.uiState.value.sessions)
        assertEquals(before.draft, vm.uiState.value.draft)
        assertNull(vm.uiState.value.draftNote)
    }

    @Test fun `the user's own pick wins over a pre-fill still waiting for installed apps`() = runTest(dispatcher) {
        val group = CompletableDeferred<AppGroup?>()
        val vm = model(loadGroup = { group.await() })
        runCurrent()
        vm.prepareQuickStart(FocusQuickStart(1, groupId = work.id))
        runCurrent()
        vm.selectApps(listOf(otherApp))
        group.complete(work)
        runCurrent()
        assertEquals(setOf(AppRef("other.app", "Other")), vm.uiState.value.draft.apps)
        assertNull(vm.uiState.value.draftNote)
    }

    @Test fun `refusal without a live detector keeps the existing copy and Begin disabled`() = runTest(dispatcher) {
        val store = InMemoryBlockRulesStore()
        val vm = model(store, permissions = TimerPermissions(), livePathA = false)
        runCurrent()
        vm.prepareQuickStart(FocusQuickStart(1, groupId = work.id))
        runCurrent()
        val state = vm.uiState.value
        assertEquals(setOf(AppRef("example.app", "Example")), state.draft.apps)
        assertEquals("Turn on Accessibility or grant Usage Access.", state.startPrerequisite)
        assertFalse("Begin stays disabled", state.canStart && state.startPrerequisite == null)
        vm.start()
        runCurrent()
        assertEquals("Turn on Accessibility or grant Usage Access.", vm.uiState.value.error)
        assertTrue(store.rules.first().isEmpty())
    }
}
