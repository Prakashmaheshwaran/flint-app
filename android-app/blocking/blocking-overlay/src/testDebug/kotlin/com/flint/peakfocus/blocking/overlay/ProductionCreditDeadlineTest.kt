package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Real preference readers, editor/quota preparation and both detector coordinator entries. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProductionCreditDeadlineTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "com.example.limited"
    private class Disk : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = values
        private val lock = Mutex()
        var beforeUpdate: suspend () -> Unit = {}
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            beforeUpdate()
            transform(values.value).also { values.value = it }
        }
    }
    private fun handoff(name: String, value: Any?) = PathBBlockHandoff::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(null, value)
    @After fun clean() {
        handoff("coordinator", null); handoff("lastForegroundPackage", null)
        BlockExemptions.clearAll(); OpenVisits.process.leave(); ProtectionStatusHub.resetForTests()
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
    }

    @Test fun PC_28_PC_76_bothProductionConstructorsAlwaysOwnTheAtomicRecoveryBackend() = runTest {
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = false
            override fun detach(view: View) = Unit
            override fun goHome() = Unit
        }
        // Path A uses this default construction; Path B's production factory also leaves
        // focusStateStore unset. Neither can select the constructor-injected v1 fixture.
        val a = BlockScreenCoordinator(app, backgroundScope, host)
        val b = PathBBlockHandoff.create(app, backgroundScope, host)
        for (c in listOf(a, b)) {
            assertNotNull(c.recoveryState)
            assertNull(BlockScreenCoordinator::class.java.getDeclaredField("legacyGrants").apply { isAccessible = true }.get(c))
        }
    }

    @Test fun PC_22_PC_28_PC_30_PC_31_PC_76_pathAHardcoreCreditReblocksStationaryForegroundAt300Seconds() =
        deadline(pathB = false, midnight = false)
    @Test fun PC_22_PC_28_PC_30_PC_31_PC_76_pathBHardcoreCreditReblocksStationaryForegroundAt300Seconds() =
        deadline(pathB = true, midnight = false)
    @Test fun PC_28_PC_31_PC_35_PC_76_pathACreditAt2358ClipsToTrustedMidnightAndResetsOnce() =
        deadline(pathB = false, midnight = true)
    @Test fun PC_28_PC_31_PC_35_PC_76_pathBCreditAt2358ClipsToTrustedMidnightAndResetsOnce() =
        deadline(pathB = true, midnight = true)

    @Test fun PC_28_PC_35_PC_76_pathAShieldSurvivesMidnightUntilQuotaMaterializes() = rollover(pathB = false, coldStart = false)
    @Test fun PC_28_PC_35_PC_76_pathBShieldSurvivesMidnightUntilQuotaMaterializes() = rollover(pathB = true, coldStart = false)
    @Test fun PC_28_PC_35_PC_76_pathAPostMidnightColdStartKeepsPendingQuotaShielded() = rollover(pathB = false, coldStart = true)
    @Test fun PC_28_PC_35_PC_76_pathBPostMidnightColdStartKeepsPendingQuotaShielded() = rollover(pathB = true, coldStart = true)

    private fun rollover(pathB: Boolean, coldStart: Boolean) = runTest {
        val start = AccessClockStamp(java.time.Instant.parse("2026-10-29T23:59:00Z").toEpochMilli(), 10_000, "boot")
        var offset = 0L
        fun clock() = start.copy(wallEpochMs = start.wallEpochMs + offset + testScheduler.currentTime,
            elapsedRealtimeMs = start.elapsedRealtimeMs + offset + testScheduler.currentTime)
        val disk = Disk()
        val limits = DataStoreLimitsStore(disk)
        limits.setOpenLimit(OpenLimit(pkg, 1, BreakLevel.HARDCORE))
        val focus = DataStoreFocusStateStore(disk)
        focus.setOpenCounts(OpenCountState(start.wallEpochMs / 86_400_000L, mapOf(pkg to 1)))
        val transactions = DataStoreProtectionTransactionStore(disk, ::clock) { "UTC" }
        val initialized = transactions.transact { ProtectionMutation(it.focus, Unit) } as ProtectionCommit.Committed
        val oldPeriod = initialized.snapshot.openCredits!!.periodId
        val oldOccurrence = initialized.snapshot.editor.causes.getValue(openCauseKey(pkg)).occurrenceId
        val release = CompletableDeferred<Unit>()
        disk.beforeUpdate = { if (clock().elapsedRealtimeMs >= start.elapsedRealtimeMs + 60_000) release.await() }
        if (coldStart) offset = 60_001 // durable period/occurrence still point to yesterday
        var mounted = false
        var detaches = 0
        var evaluations = 0
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean { mounted = true; return true }
            override fun detach(view: View) { mounted = false; detaches++ }
            override fun goHome() = Unit
        }
        var evaluate: (() -> Unit)? = null
        val c = BlockScreenCoordinator(app, backgroundScope, host,
            onEnforcementChanged = { evaluate?.invoke() }, nowEpochMs = { clock().wallEpochMs },
            safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { clock().elapsedRealtimeMs }, requiredRuntimeSources = emptySet()),
            focusStateStore = focus, configuredLimitsStore = limits, blockRulesStore = DataStoreBlockRulesStore(disk),
            editorCommitmentStore = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" }),
            statusClock = ::clock, statusDispatcher = StandardTestDispatcher(testScheduler), protectionTransactionStore = transactions)
        handoff("coordinator", c)
        evaluate = {
            evaluations++
            if (pathB) PathBBlockHandoff.onForegroundPolled(app, pkg, Verdict.Allow)
            else c.evaluateBlockCause(pkg, pkg, null, false)?.let { c.showBlocked(pkg, null, it) } ?: c.hideIfShown()
        }
        suspend fun snapshot() = (transactions.state.first { it !is StorageState.Loading } as StorageState.Ready).value
        try {
            runCurrent()
            assertTrue("Initial automatic evaluation keeps the configured limit shielded", mounted)
            val beforeBoundaryDetaches = detaches
            if (!coldStart) { advanceTimeBy(60_000); runCurrent() }
            // No explicit evaluation between the clock boundary and these assertions.
            assertTrue("Boundary wake cannot drop the shield while materialization waits", mounted)
            assertEquals(beforeBoundaryDetaches, detaches)
            assertEquals(oldPeriod, snapshot().openCredits!!.periodId)
            assertEquals(1, snapshot().openCredits!!.counters.getValue(pkg).used)
            val cause = c.currentBlockCause(pkg)!!
            assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(clock())) is GrantResult.Unavailable)
            assertFalse(c.canDismiss(pkg, cause))
            val beforeMaterialization = evaluations
            release.complete(Unit); runCurrent()
            assertTrue("Occurrence publication must wake stationary detectors again", evaluations > beforeMaterialization)
            assertTrue("New unspent quota still requires an explicit credit", mounted)
            assertEquals(beforeBoundaryDetaches, detaches)
            val current = snapshot()
            assertNotEquals(oldPeriod, current.openCredits!!.periodId)
            assertNotEquals(oldOccurrence, current.editor.causes.getValue(openCauseKey(pkg)).occurrenceId)
            assertEquals(0, current.openCredits!!.counters.getValue(pkg).used)
            repeat(3) { c.retryProtectionState(); runCurrent() }
            assertEquals(current.openCredits!!.periodId, snapshot().openCredits!!.periodId)
            assertEquals(0, snapshot().openCredits!!.counters.getValue(pkg).used)
            assertTrue(snapshot().focus.protection!!.outbox.isEmpty())
            val scope = c.screenState(pkg, null, c.currentBlockCause(pkg)!!, clock().wallEpochMs).openCreditRequestScope
            assertNotNull(scope)
            assertEquals(GrantResult.OpenAllowed, c.requestBreak(pkg, cause, GrantRequestId.issue(clock(), scope)))
            runCurrent()
            assertFalse(mounted)
            assertEquals(1, snapshot().openCredits!!.counters.getValue(pkg).used)
        } finally { release.complete(Unit); evaluate = null; c.hideIfShown(); clean() }
    }

    private fun deadline(pathB: Boolean, midnight: Boolean) = runTest {
        val start = AccessClockStamp(java.time.Instant.parse(if (midnight) "2026-10-29T23:58:00Z" else "2026-10-29T12:00:00Z").toEpochMilli(), 10_000, "boot")
        fun clock() = start.copy(wallEpochMs = start.wallEpochMs + testScheduler.currentTime,
            elapsedRealtimeMs = start.elapsedRealtimeMs + testScheduler.currentTime)
        val disk = Disk()
        val limits = DataStoreLimitsStore(disk)
        limits.setOpenLimit(OpenLimit(pkg, 1, BreakLevel.HARDCORE))
        val focus = DataStoreFocusStateStore(disk)
        focus.setOpenCounts(OpenCountState(start.wallEpochMs / 86_400_000L, mapOf(pkg to 0)))
        // An old unbounded visit receipt and paid process visit must never authorize access.
        val legacy = PersistedExemption("legacy-open", pkg, "open-visit", ExemptionKind.OPEN_LIMIT, 0, "boot")
        focus.transact { FocusMutation(it.copy(exemptions = mapOf(legacy.requestId to legacy)), Unit) }
        val transactions = DataStoreProtectionTransactionStore(disk, ::clock) { "UTC" }
        val initialized = transactions.transact { ProtectionMutation(it.focus, Unit) }
        assertTrue(initialized.toString(), initialized is ProtectionCommit.Committed)
        val paid = OpenVisits.process.observe(pkg, true, true, start.wallEpochMs)!!
        OpenVisits.process.acknowledge(paid)
        var mounted = false
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean { mounted = true; return true }
            override fun detach(view: View) { mounted = false }
            override fun goHome() = Unit
        }
        var evaluate: (() -> Unit)? = null
        val c = BlockScreenCoordinator(app, backgroundScope, host,
            onEnforcementChanged = { evaluate?.invoke() }, nowEpochMs = { clock().wallEpochMs },
            safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { clock().elapsedRealtimeMs }, requiredRuntimeSources = emptySet()),
            focusStateStore = focus, configuredLimitsStore = limits, blockRulesStore = DataStoreBlockRulesStore(disk),
            editorCommitmentStore = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" }),
            statusClock = ::clock, statusDispatcher = StandardTestDispatcher(testScheduler), protectionTransactionStore = transactions)
        runCurrent()
        handoff("coordinator", c)
        evaluate = {
            if (pathB) PathBBlockHandoff.onForegroundPolled(app, pkg, Verdict.Allow)
            else c.evaluateBlockCause(pkg, pkg, null, false)?.let { c.showBlocked(pkg, null, it) } ?: c.hideIfShown()
        }
        suspend fun snapshot() = (transactions.state.first { it !is StorageState.Loading } as StorageState.Ready).value
        try {
            val before = snapshot()
            evaluate.invoke(); runCurrent()
            assertTrue("Entry is shielded without charging even with a legacy paid visit", mounted)
            assertEquals(before.focus.openCounts, snapshot().focus.openCounts)
            assertEquals(before.openCredits!!.counters, snapshot().openCredits!!.counters)
            assertEquals(0, snapshot().openCredits!!.counters.getValue(pkg).used)
            val cause = c.currentBlockCause(pkg)!!
            val id = GrantRequestId.issue(clock(), c.screenState(pkg, null, cause, clock().wallEpochMs).openCreditRequestScope)
            assertNotNull(id.creditScope)
            assertEquals(GrantResult.OpenAllowed, c.requestBreak(pkg, cause, id)); runCurrent()
            assertFalse(mounted)
            val accepted = snapshot()
            val grant = accepted.openCredits!!.active.getValue(pkg).grant
            val duration = if (midnight) 120_000L else 300_000L
            assertEquals(duration, grant.durationMs)
            assertEquals(1, accepted.openCredits!!.counters.getValue(pkg).used)
            assertEquals(setOf(legacy.requestId), accepted.focus.exemptions.keys) // no new until=0 receipt
            advanceTimeBy(duration - 1); runCurrent()
            assertFalse("Automatic grant publication keeps access open before expiry", mounted)
            evaluate.invoke()
            assertFalse(mounted)
            advanceTimeBy(1); runCurrent() // expiry wake, no new foreground transition
            assertTrue("Stationary foreground re-shields at the trusted deadline", mounted)
            val expired = snapshot()
            assertTrue(expired.openCredits!!.active.isEmpty())
            assertEquals(before.focus.openCounts, expired.focus.openCounts)
            assertEquals(setOf(legacy.requestId), expired.focus.exemptions.keys)
            assertEquals(1, expired.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.ACCESS_STARTED })
            assertEquals(1, expired.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.ACCESS_ENDED })
            if (midnight) {
                assertNotEquals(accepted.openCredits!!.periodId, expired.openCredits!!.periodId)
                assertEquals(0, expired.openCredits!!.counters.getValue(pkg).used)
                val period = expired.openCredits!!.periodId
                repeat(3) { evaluate.invoke(); c.retryProtectionState(); runCurrent() }
                assertEquals(period, snapshot().openCredits!!.periodId)
                assertEquals(0, snapshot().openCredits!!.counters.getValue(pkg).used)
            } else assertEquals(1, expired.openCredits!!.counters.getValue(pkg).used)
        } finally { evaluate = null; c.hideIfShown(); clean() }
    }
}
