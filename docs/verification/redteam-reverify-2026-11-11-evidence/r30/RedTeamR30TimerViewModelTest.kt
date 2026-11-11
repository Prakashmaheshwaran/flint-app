package com.flint.peakfocus.feature.blocklist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Red team R-30, production wiring: the Timer screen's `protectionStatus` is
 * `ProtectionStatusHub.status` in the real factory. This drives a Timer through the real
 * [TimerViewModel] and then publishes the exact status a failed shield mount produces, proving
 * the "Armed" state (and its 10 s health interval) cannot outlive the failure.
 *
 * ADR-012 §8/§11: the commitment keeps its saved end and is reported degraded, never "Armed".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedTeamR30TimerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val base = 1_793_181_600_000L
    private fun clock() = AccessClockStamp(base + dispatcher.scheduler.currentTime,
        10_000 + dispatcher.scheduler.currentTime, "boot")
    private val app = InstalledApp("example.app", "Example", null)
    private val health = MutableStateFlow(readyTimerStatus(clock()))

    private class Disk : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }

    private val disk = Disk()
    private val rules = DataStoreBlockRulesStore(disk)
    private val editor = DataStoreEditorCommitmentStore(disk, clockAtCommit = ::clock, defaultZone = { "UTC" })

    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owners.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun model(): TimerViewModel = TimerViewModel(rules, { listOf(app) }, editor,
        DataStoreHarderWaitStore(disk), now = { clock().wallEpochMs }, newId = { "rt30-attempt" },
        ioDispatcher = dispatcher, protectionClock = ::clock, safety = { EditorSafety(true) },
        protectionStatus = health, loadPermissions = { TimerPermissions(accessibilityEnabled = true) },
        livePathA = { true }, requestEvaluation = {}).also { vm ->
        owners += ViewModelStore().also { it.put("timer", vm) }
    }

    private fun withReason(reason: String, ids: List<String>) = readyTimerStatus(clock(), ids).let {
        it.copy(detectors = it.detectors.copy(degradedReasons = setOf(reason)))
    }

    @Test fun `RedTeam_R30 armed becomes degraded the moment a failing mount is published and returns only on a real mount`() =
        runTest(dispatcher) {
            val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.selectDuration(5)
            vm.start(); runCurrent()
            val saved = rules.rules.first().single()
            health.value = readyTimerStatus(clock(), listOf(saved.id)); runCurrent()
            assertTrue("control: the fixture arms without a mount fault", vm.uiState.value.arming is TimerArmingState.Armed)
            val endBefore = vm.uiState.value.sessions.single().expiresAtEpochMs

            for (reason in listOf(ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED,
                ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY)) {
                health.value = withReason(reason, listOf(saved.id)); runCurrent()
                val degraded = vm.uiState.value.arming as TimerArmingState.Degraded
                assertTrue("$reason: the saved plan must survive", degraded.committed)
                assertEquals("the saved row is unchanged", listOf(saved), rules.rules.first())
                assertEquals("the session is still shown", 1, vm.uiState.value.sessions.size)
                assertEquals("the finite end is neither extended nor truncated",
                    endBefore, vm.uiState.value.sessions.single().expiresAtEpochMs)
            }

            // Only a later successful primary mount (reason gone) restores Armed.
            health.value = readyTimerStatus(clock(), listOf(saved.id)); runCurrent()
            assertTrue(vm.uiState.value.arming is TimerArmingState.Armed)
        }

    @Test fun `RedTeam_R30 a start while the surface is known broken still commits once and never reads armed`() =
        runTest(dispatcher) {
            health.value = withReason(ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED, emptyList())
            val vm = model(); runCurrent(); vm.selectApps(listOf(app)); vm.selectDuration(5)
            assertNull("a new Timer is still offered (refusing it would remove protection)",
                vm.uiState.value.startPrerequisite)
            vm.start(); runCurrent()
            assertEquals(1, rules.rules.first().size)
            val saved = rules.rules.first().single()
            val degraded = vm.uiState.value.arming as TimerArmingState.Degraded
            assertTrue(degraded.committed)
            assertEquals(saved.id, degraded.ruleId)
            assertTrue(vm.uiState.value.sessions.isNotEmpty())
        }
}
