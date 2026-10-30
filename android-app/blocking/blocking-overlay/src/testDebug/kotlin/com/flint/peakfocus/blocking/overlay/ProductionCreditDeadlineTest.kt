package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.GrantStatus
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
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        override val data: Flow<Preferences> = values
        private val lock = Mutex()
        var beforeUpdate: suspend () -> Unit = {}
        var updates = 0
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            beforeUpdate()
            transform(values.value).also { values.value = it; updates++ }
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

    @Test fun PC_28_PC_47_PC_76_firstOpenLimitAddedWhileIdleMaterializesWithoutAMinuteOrRetry() = runTest {
        val now = AccessClockStamp(java.time.Instant.parse("2026-10-29T12:00:00Z").toEpochMilli(), 10_000, "boot")
        val disk = Disk()
        val limits = DataStoreLimitsStore(disk)
        val transactions = DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = false
            override fun detach(view: View) = Unit
            override fun goHome() = Unit
        }
        val c = BlockScreenCoordinator(app, backgroundScope, host, nowEpochMs = { now.wallEpochMs },
            safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { now.elapsedRealtimeMs }, requiredRuntimeSources = emptySet()),
            focusStateStore = DataStoreFocusStateStore(disk), configuredLimitsStore = limits,
            blockRulesStore = DataStoreBlockRulesStore(disk),
            editorCommitmentStore = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" }),
            statusClock = { now }, statusDispatcher = StandardTestDispatcher(testScheduler), protectionTransactionStore = transactions)
        runCurrent()
        assertNull(c.currentBlockCause(pkg))
        limits.setOpenLimit(OpenLimit(pkg, 1, BreakLevel.HARDCORE))
        runCurrent() // no clock advance and no explicit retry
        assertEquals(0L, testScheduler.currentTime)
        val cause = c.currentBlockCause(pkg)!!
        val ui = c.screenState(pkg, null, cause, now.wallEpochMs)
        assertEquals(GrantStatus.READY, ui.grantStatus)
        assertEquals(1, ui.openCreditsRemaining)
        assertNotNull(ui.openCreditRequestScope)
        val snapshot = (transactions.state.first { it !is StorageState.Loading } as StorageState.Ready).value
        assertNotNull(snapshot.editor.causes[openCauseKey(pkg)])
        assertTrue(snapshot.openCredits!!.counters.values.all { it.used == 0 })
        assertEquals(GrantResult.OpenAllowed, c.requestBreak(pkg, cause, GrantRequestId.issue(now, ui.openCreditRequestScope)))
    }

    @Test fun PC_28_PC_41_PC_47_PC_69_mainUpgradeWithUntouchedPassOpenAndFocusDomainsRemainsUsable() = upgrade(savedTime = true)
    @Test fun PC_28_PC_41_PC_47_PC_69_mainUpgradeWithoutDailyLimitsWritesEveryRequiredDefaultAtomically() = upgrade(savedTime = false)

    private fun upgrade(savedTime: Boolean) = runTest {
        val now = AccessClockStamp(java.time.Instant.parse("2026-10-29T12:00:00Z").toEpochMilli(), 10_000, "boot")
        // Literal origin/main PrefsKeys + PrefsCodec wire layout: only authored domains
        // exist. A running Timer and saved Time limit do not prove pass/Open/focus use.
        val timerId = "session-timer-upgrade"
        val raw = mutablePreferencesOf(
            stringPreferencesKey("block_rules_v1") to "$timerId|Running timer|true|HARDCORE|false|com.example.focus~Focus|||-1|-1|${now.wallEpochMs + 3_600_000}",
        )
        if (savedTime) {
            raw[stringPreferencesKey("time_limits_v1")] = "com.example.time~30"
            raw[stringPreferencesKey("default_break_level_v1")] = "EASY"
        }
        val disk = Disk(raw)
        val focus = DataStoreFocusStateStore(disk)
        val old = focus.state.first { it !is StorageState.Loading }
        assertTrue(old is StorageState.Ready)
        assertNull((old as StorageState.Ready).value.emergencyPass.usedAtEpochMs)
        assertEquals(OpenCountState(), old.value.openCounts)
        val transactions = DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }
        val initialized = transactions.transact { ProtectionMutation(it.focus, Unit) }
        assertTrue(initialized.toString(), initialized is ProtectionCommit.Committed)
        assertEquals("Schema and all required defaults publish in one edit", 1, disk.updates)
        for (key in listOf("block_rules_v1", "time_limits_v1", "open_limits_v1", "default_break_level_v1"))
            assertNotNull(key, disk.values.value[stringPreferencesKey(key)])
        if (!savedTime) assertEquals("", disk.values.value[stringPreferencesKey("time_limits_v1")])
        assertTrue("Fresh reader accepts the complete migrated domain", DataStoreFocusStateStore(disk).state.first { it !is StorageState.Loading } is StorageState.Ready)
        val first = (initialized as ProtectionCommit.Committed).snapshot
        assertNull(first.focus.protection!!.lastUsedAtEpochMs)
        assertTrue(first.openCredits!!.counters.isEmpty())
        assertTrue(first.rules.any { it.id == timerId && it.expiresAtEpochMs!! > now.wallEpochMs })
        for (key in listOf("protection_schema_v1", "open_credit_schema_v1"))
            assertEquals(key, 1L, disk.values.value[longPreferencesKey(key)])
        assertEquals(3L, disk.values.value[longPreferencesKey("editor_commitments_version")])
        assertNotNull(disk.values.value[stringPreferencesKey("focus_exemptions_v1")])
        assertEquals("", disk.values.value[stringPreferencesKey("open_limits_v1")])
        val migrated = disk.values.value
        transactions.transact { ProtectionMutation(it.focus, Unit) }
        assertEquals("The second preparation cannot refill or reimport", migrated, disk.values.value)
        val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" }, clockAtCommit = { now })
        val change = EditorChange("New Open limit", limits = EditorLimitsChange(pkg, null, OpenLimit(pkg, 1, BreakLevel.HARDCORE)))
        val preview = editor.request(change, now, EditorSafety(true))
        val saved = if (preview is EditorCommitResult.Preview) editor.confirm(preview.pending.id, now, EditorSafety(true)) else preview
        assertEquals(EditorCommitResult.Applied(), saved)
        val controller = GlobalRecoveryController(transactions, backgroundScope, { now }, { emptyList() }, {}, StandardTestDispatcher(testScheduler))
        runCurrent()
        val input = OrdinaryProtectionInput(pkg, app.packageName, false, true)
        val creditScope = TimedOpenCreditPolicy.view(controller.lastGood!!, input, emptyList(), now)!!.requestScope
        assertEquals(GrantResult.OpenAllowed, controller.requestCredit(input, GrantRequestId.issue(now, creditScope)) { false })
        val pass = controller.preview()!!
        assertTrue(pass.affected.isNotEmpty())
        assertTrue(controller.confirm(pass) is RecoveryResult.Accepted)
        assertTrue(controller.resumeNow())
        val current = (transactions.state.first { it !is StorageState.Loading } as StorageState.Ready).value
        assertEquals(now.wallEpochMs, current.focus.emergencyPass.usedAtEpochMs)
        assertEquals(1, current.openCredits!!.counters.getValue(pkg).used)
        assertTrue(current.rules.none { it.id == timerId })
        assertTrue(focus.state.first { it !is StorageState.Loading } is StorageState.Ready)
    }

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
            assertEquals(GrantStatus.RESETTING, c.screenState(pkg, null, cause, clock().wallEpochMs).grantStatus)
            assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(clock())) is GrantResult.Unavailable)
            // This token was explicitly scoped to the old quota. Pending materialization
            // rejects it so a SurfaceRequests owner discards it rather than retrying forever.
            val oldScope = TimedOpenCreditPolicy.view(initialized.snapshot,
                OrdinaryProtectionInput(pkg, app.packageName, false, true), emptyList(), start)!!.requestScope
            val stale = GrantRequestId.issue(start, oldScope)
            assertEquals(GrantResult.Denied, c.requestBreak(pkg, cause, stale))
            val requests = BlockScreenCoordinator.SurfaceRequests().apply { breakId = stale }
            requests.recordResult(false, GrantResult.Denied)
            assertNull(requests.breakId)
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
