package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoordinatorProtectionStatusTest {
    private val owner = Any()
    private val initial = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private val rule = BlockRule("session-timer-v2-one", "Focus", BlockTargets(apps = setOf(AppRef("com.example.app"))),
        breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = initial.wallEpochMs + 4_000)
    private val editor = PublishedEditor(EditorCommitmentState(causes = mapOf("rule:${rule.id}" to
        EditorCause(rule.id, AnchoredInterval(initial, 4_000, initial.wallEpochMs + 4_000), "UTC")), timeline = initial))
    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }

    @After fun clean() {
        EnforcementSignals.detectors.disconnected(owner)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ProtectionStatusHub.resetForTests()
        BlockExemptions.clearAll()
    }

    @Test fun PC_73_coordinatorPublishesLivePolicyAndBindingChangesDoNotRefreshItsObservation() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        var clock = initial
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
        BlockScreenCoordinator(app, backgroundScope, host,
            focusStateStore = InMemoryFocusStateStore(), configuredLimitsStore = InMemoryLimitsStore(),
            blockRulesStore = InMemoryBlockRulesStore(listOf(rule)), editorCommitmentStore = editor,
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) }, { clock.elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")),
            statusClock = { clock }, statusDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS + 1); runCurrent()
        val published = ProtectionStatusHub.status.value
        assertEquals(ProtectionStatus.Readiness.READY, published.readiness)
        assertEquals(rule.id, published.activeCauses.single().ruleId)
        assertEquals(BreakLevel.HARDCORE, published.activeCauses.single().tier)
        assertTrue(published.safetyFloorResolved)
        assertFalse(published.detectors.accessibilityConnected)
        clock = initial.copy(wallEpochMs = initial.wallEpochMs + 500, elapsedRealtimeMs = initial.elapsedRealtimeMs + 500)
        EnforcementSignals.detectors.connected(owner)
        assertTrue(ProtectionStatusHub.status.value.detectors.accessibilityConnected)
        assertEquals(published.observedAt, ProtectionStatusHub.status.value.observedAt)
        ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.STANDBY)
        EnforcementSignals.detectors.disconnected(owner)
        assertFalse(ProtectionStatusHub.status.value.detectors.accessibilityConnected)
        assertEquals(ProtectionStatus.PathBState.STANDBY, ProtectionStatusHub.status.value.detectors.pathB)
        assertEquals(published.lastCheckedEpochMs, ProtectionStatusHub.status.value.lastCheckedEpochMs)
    }

    @Test fun PC_47_PC_73_ownedPolicyTickExpiresCausesAndUnreadableStorageDoesNotPublishReady() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val backing = InMemoryFocusStateStore()
        val states = MutableStateFlow<StorageState<FocusSnapshot>>(StorageState.Ready(FocusSnapshot()))
        val focus = object : FocusStateStore by backing { override val state = states }
        fun clock() = initial.copy(wallEpochMs = initial.wallEpochMs + testScheduler.currentTime,
            elapsedRealtimeMs = initial.elapsedRealtimeMs + testScheduler.currentTime)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
        BlockScreenCoordinator(app, backgroundScope, host,
            focusStateStore = focus, configuredLimitsStore = InMemoryLimitsStore(), blockRulesStore = InMemoryBlockRulesStore(listOf(rule)),
            editorCommitmentStore = editor, statusClock = ::clock, statusDispatcher = StandardTestDispatcher(testScheduler),
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) }, { clock().elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")))
        runCurrent()
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS + 1); runCurrent()
        assertEquals(1, ProtectionStatusHub.status.value.activeCauses.size)
        advanceTimeBy(4_000 + ProtectionStatusProjection.TICK_MS + BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        assertTrue(ProtectionStatusHub.status.value.activeCauses.isEmpty())
        assertTrue(ProtectionStatusHub.status.value.lastCheckedEpochMs!! >= initial.wallEpochMs + 4_000)
        states.value = StorageState.Unavailable(StorageFailure.IO)
        runCurrent()
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        assertEquals(ProtectionStatus.Readiness.UNAVAILABLE, ProtectionStatusHub.status.value.readiness)
        assertEquals(ProtectionStatus.PassStatus.Unavailable, ProtectionStatusHub.status.value.pass)
    }

    @Test fun PC_73_eachOwnedTickReplacesPolicyBeforeTheFreshnessHorizon() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        fun clock() = initial.copy(wallEpochMs = initial.wallEpochMs + testScheduler.currentTime,
            elapsedRealtimeMs = initial.elapsedRealtimeMs + testScheduler.currentTime)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
        BlockScreenCoordinator(app, backgroundScope, host, focusStateStore = InMemoryFocusStateStore(),
            configuredLimitsStore = InMemoryLimitsStore(), blockRulesStore = InMemoryBlockRulesStore(listOf(rule)), editorCommitmentStore = editor,
            statusClock = ::clock, statusDispatcher = StandardTestDispatcher(testScheduler),
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) }, { clock().elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")))
        runCurrent()
        val first = ProtectionStatusHub.status.value.observedAt!!
        advanceTimeBy(ProtectionStatusProjection.TICK_MS + BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        val tick = ProtectionStatusHub.status.value
        assertTrue(tick.observedAt!!.elapsedRealtimeMs > first.elapsedRealtimeMs)
        assertTrue(tick.validUntilElapsedRealtimeMs!! > clock().elapsedRealtimeMs)
    }

    @Test fun PC_47_PC_73_projectionFailurePublishesUnavailableAndTheOwnedLoopCanRecover() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        var fail = true
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
        BlockScreenCoordinator(app, backgroundScope, host, focusStateStore = InMemoryFocusStateStore(),
            configuredLimitsStore = InMemoryLimitsStore(), blockRulesStore = InMemoryBlockRulesStore(listOf(rule)), editorCommitmentStore = editor,
            statusClock = { check(!fail); initial }, statusDispatcher = StandardTestDispatcher(testScheduler),
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) }, { initial.elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")))
        runCurrent()
        assertEquals(ProtectionStatus.Readiness.UNAVAILABLE, ProtectionStatusHub.status.value.readiness)
        assertTrue("policy-check-failed" in ProtectionStatusHub.status.value.detectors.degradedReasons)
        assertNull(ProtectionStatusHub.status.value.observedAt)
        fail = false
        advanceTimeBy(ProtectionStatusProjection.TICK_MS + BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        assertEquals(ProtectionStatus.Readiness.READY, ProtectionStatusHub.status.value.readiness)
        assertEquals(initial, ProtectionStatusHub.status.value.observedAt)
    }

    @Test fun PC_73_multipleStoreInvalidationsCoalesceBeforeProjectionInsteadOfRunningOnEachCollector() = runTest {
        val app: Context = ApplicationProvider.getApplicationContext()
        val focus = InMemoryFocusStateStore()
        val rules = InMemoryBlockRulesStore(listOf(rule))
        var checks = 0
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
        BlockScreenCoordinator(app, backgroundScope, host, focusStateStore = focus,
            configuredLimitsStore = InMemoryLimitsStore(), blockRulesStore = rules, editorCommitmentStore = editor,
            statusClock = { checks++; initial }, statusDispatcher = StandardTestDispatcher(testScheduler),
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) }, { initial.elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")))
        runCurrent()
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        val before = checks
        focus.setDefaultBreakLevel(BreakLevel.HARDER)
        rules.upsert(rule.copy(name = "Updated"))
        val old = (editor.state.value as StorageState.Ready).value
        editor.state.value = StorageState.Ready(old.copy(migrationNotice = true))
        runCurrent()
        assertEquals(before, checks)
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS)
        runCurrent()
        assertEquals(before + 1, checks)
        assertEquals("Updated", ProtectionStatusHub.status.value.activeCauses.single().displayName)
    }

    private class PublishedEditor(initial: EditorCommitmentState) : EditorCommitmentStore {
        override val state = MutableStateFlow<StorageState<EditorCommitmentState>>(StorageState.Ready(initial))
        override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("read-only test")
        override suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean): EditorCommitResult = error("read-only test")
        override suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("read-only test")
        override suspend fun cancel(id: Long) = error("read-only test")
        override suspend fun dismissMigrationNotice() = error("read-only test")
        override suspend fun reconcile(now: AccessClockStamp) = error("read-only test")
        override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = error("read-only test")
        override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): Boolean = error("read-only test")
    }
}
