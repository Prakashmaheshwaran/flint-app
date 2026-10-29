package com.flint.peakfocus.feature.blocklist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimerArmingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val base = 1_793_181_600_000L
    private var wallShift = 0L
    private fun clock() = AccessClockStamp(base + dispatcher.scheduler.currentTime + wallShift, 10_000 + dispatcher.scheduler.currentTime, "boot")
    private val app = InstalledApp("example.app", "Example", null)
    private var permissions = TimerPermissions(accessibilityEnabled = true)
    private var liveA = true
    private var applyFails = false
    private var requests = 0
    private var ids = 0
    private val health = MutableStateFlow(readyTimerStatus(clock()))
    private class Disk : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        var fail = false
        var writes = 0
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            val next = transform(values.value)
            if (fail) throw IOException("write failed")
            if (next != values.value) writes++
            values.value = next
            next
        }
    }
    private val disk = Disk()
    private val rules = DataStoreBlockRulesStore(disk)
    private val editor = DataStoreEditorCommitmentStore(disk, clockAtCommit = ::clock, defaultZone = { "UTC" })
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owners.forEach { it.clear() }; Dispatchers.resetMain() }
    private fun model(): TimerViewModel = TimerViewModel(rules, { listOf(app) }, editor, DataStoreHarderWaitStore(disk),
        now = { clock().wallEpochMs }, newId = { "attempt-${++ids}" }, ioDispatcher = dispatcher,
        protectionClock = ::clock, safety = { EditorSafety(true) }, protectionStatus = health,
        loadPermissions = { permissions }, livePathA = { liveA }, requestEvaluation = {
            requests++
            if (applyFails) throw IOException("detector unavailable")
        }).also { vm -> owners += ViewModelStore().also { it.put("timer", vm) } }

    @Test fun `PC-61 no permissions declines before any Timer write and retains the draft`() = runTest(dispatcher) {
        permissions = TimerPermissions(); liveA = false
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.selectDuration(5)
        val draft = vm.uiState.value.draft
        vm.start(); runCurrent()
        assertEquals(0, disk.writes); assertTrue(rules.rules.first().isEmpty())
        assertEquals(draft, vm.uiState.value.draft)
        assertFalse((vm.uiState.value.arming as TimerArmingState.Degraded).committed)
        assertTrue(vm.uiState.value.error!!.startsWith("Not armed.")); assertEquals(0, requests)
    }

    @Test fun `PC-61 granted permission without a live service cannot save a Timer`() = runTest(dispatcher) {
        liveA = false
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.start(); runCurrent()
        assertEquals(0, disk.writes); assertEquals(0, ids)
        assertTrue(vm.uiState.value.error!!.contains("service is not connected"))
    }

    @Test fun `PC-01 persisted success is Applying until a fresh exact-ID backend observation`() = runTest(dispatcher) {
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.selectDuration(5); vm.start(); runCurrent()
        val saved = rules.rules.first().single()
        assertEquals(TimerArmingState.Applying(saved.id), vm.uiState.value.arming)
        assertEquals(1, requests)
        advanceTimeBy(1); health.value = readyTimerStatus(clock(), listOf("other")); runCurrent()
        assertTrue(vm.uiState.value.arming is TimerArmingState.Applying)
        health.value = readyTimerStatus(clock(), listOf(saved.id)); runCurrent()
        assertEquals(TimerArmingState.Armed(saved.id, clock()), vm.uiState.value.arming)
        assertEquals(listOf(saved), rules.rules.first())
    }

    @Test fun `PC-61 Path B alone saves and arms without Accessibility or overlay permission`() = runTest(dispatcher) {
        permissions = TimerPermissions(usageAccess = true); liveA = false
        health.value = readyTimerStatus(clock()).copy(detectors = ProtectionStatus.DetectorHealth(false, ProtectionStatus.PathBState.ENFORCING, emptySet()))
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.start(); runCurrent()
        val saved = rules.rules.first().single()
        health.value = readyTimerStatus(clock(), listOf(saved.id)).copy(detectors = health.value.detectors)
        runCurrent()
        assertTrue(vm.uiState.value.arming is TimerArmingState.Armed)
    }

    @Test fun `PC-65 storage failure leaves the draft and old rules while retry creates only one Timer`() = runTest(dispatcher) {
        val old = BlockRule("other", "Other", BlockTargets(apps = setOf(AppRef("other"))))
        rules.upsert(old)
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); disk.fail = true; vm.start(); runCurrent()
        assertEquals(listOf(old), rules.rules.first())
        assertFalse((vm.uiState.value.arming as TimerArmingState.Degraded).committed)
        assertEquals(setOf(AppRef(app.packageName, app.label)), vm.uiState.value.draft.apps)
        disk.fail = false; vm.start(); runCurrent()
        assertEquals(2, rules.rules.first().size); assertTrue(old in rules.rules.first())
        assertEquals(1, vm.uiState.value.sessions.size)
    }

    @Test fun `PC-65 committed apply failure reports recovery and retry uses the same ID and deadline`() = runTest(dispatcher) {
        applyFails = true
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.selectDuration(5); vm.start(); runCurrent()
        val saved = rules.rules.first().single(); val writes = disk.writes
        assertTrue((vm.uiState.value.arming as TimerArmingState.Degraded).committed)
        advanceTimeBy(100); applyFails = false; health.value = readyTimerStatus(clock()); vm.retryArming(); runCurrent()
        assertEquals(1, ids); assertEquals(2, requests); assertEquals(writes, disk.writes)
        assertEquals(listOf(saved), rules.rules.first())
        assertTrue(vm.uiState.value.arming is TimerArmingState.Applying)
        health.value = readyTimerStatus(clock(), listOf(saved.id)); runCurrent()
        assertTrue(vm.uiState.value.arming is TimerArmingState.Armed)
    }

    @Test fun `PC-65 missing acknowledgement becomes degraded without erasing or extending the plan`() = runTest(dispatcher) {
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.selectDuration(5); vm.start(); runCurrent()
        val saved = rules.rules.first().single()
        advanceTimeBy(TIMER_ARM_CONFIRMATION_MS); health.value = readyTimerStatus(clock()); runCurrent()
        assertTrue(vm.uiState.value.arming is TimerArmingState.Degraded)
        assertEquals(listOf(saved), rules.rules.first()); assertEquals(1, ids)
    }

    @Test fun `PC-77 lost permission degrades Armed and restored permission retries the same finite Timer`() = runTest(dispatcher) {
        val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.selectDuration(5); vm.start(); runCurrent()
        val saved = rules.rules.first().single(); health.value = readyTimerStatus(clock(), listOf(saved.id)); runCurrent()
        assertTrue(vm.uiState.value.arming is TimerArmingState.Armed)
        permissions = TimerPermissions(); liveA = false; vm.refreshHealth(); runCurrent()
        assertTrue(vm.uiState.value.arming is TimerArmingState.Degraded)
        assertEquals(listOf(saved), rules.rules.first())
        permissions = TimerPermissions(accessibilityEnabled = true); liveA = true
        vm.retryArming(); runCurrent()
        assertTrue(vm.uiState.value.arming is TimerArmingState.Armed)
        assertEquals(listOf(saved), rules.rules.first()); assertEquals(1, ids)
    }

    @Test fun `PC-54 restored Timer stays visible across wall jumps using its stored elapsed anchor`() = runTest(dispatcher) {
        val rule = timerRule(TimerDraft(setOf(AppRef(app.packageName)), durationMinutes = 5), clock().wallEpochMs, "restored")
        assertTrue(editor.startTimer(rule, clock(), EditorSafety(true)))
        wallShift = 86_400_000
        val vm = model(); runCurrent()
        val displayed = vm.uiState.value.sessions.single()
        assertEquals(rule.id, displayed.id); assertEquals(300_000L, displayed.expiresAtEpochMs!! - clock().wallEpochMs)
        assertTrue(vm.uiState.value.arming is TimerArmingState.Degraded)
        assertFalse(vm.uiState.value.canStart)
        assertEquals(listOf(rule), rules.rules.first())
    }
}
