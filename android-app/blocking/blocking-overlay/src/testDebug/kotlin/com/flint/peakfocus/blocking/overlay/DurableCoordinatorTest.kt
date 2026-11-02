package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DurableCoordinatorTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_800_000_000_000L
    private val pkg = "com.example.limited"
    private val events = mutableListOf<String>()
    private val listener = object : BlockEventHooks.Listener {
        override fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) { events += "break" }
        override fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) { events += "pass" }
    }
    @Before fun before() { BlockExemptions.clearAll(); OpenVisits.process.leave(); BlockEventHooks.register(listener) }
    @After fun after() {
        scope.cancel()
        BlockEventHooks.unregister(listener)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        BlockExemptions.clearAll()
        OpenVisits.process.leave()
    }
    private fun rule(level: BreakLevel) = BlockRule("rule", "Focus", BlockTargets(apps = setOf(AppRef(pkg))), breakLevel = level)
    private fun coordinator(
        focus: FocusStateStore, rules: List<BlockRule> = listOf(rule(BreakLevel.EASY)),
        limits: LimitsStore = InMemoryLimitsStore(), onChanged: () -> Unit = {}, mount: Boolean = false,
        configuredRules: BlockRulesStore = InMemoryBlockRulesStore(rules),
    ): BlockScreenCoordinator {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, rules)
        return BlockScreenCoordinator(app, scope, object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = mount
            override fun detach(view: View) = Unit
            override fun goHome() = Unit
        }, nowEpochMs = { now }, onEnforcementChanged = onChanged,
            safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet()),
            focusStateStore = focus, configuredLimitsStore = limits, blockRulesStore = configuredRules)
    }
    private class PausedFocus(val backing: InMemoryFocusStateStore = InMemoryFocusStateStore()) : FocusStateStore by backing {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fail = false
        override suspend fun <R> transact(transform: (FocusSnapshot) -> FocusMutation<R>): FocusCommit<R> {
            val before = backing.state.filterIsInstance<StorageState.Ready<FocusSnapshot>>().first().value
            val proposal = transform(before)
            started.complete(Unit)
            release.await()
            if (fail) return FocusCommit.Unavailable(StorageFailure.IO)
            return backing.transact { check(it == before); proposal }
        }
    }

    @Test fun PC_15_PC_25_PathAStoredHardcoreTimeLimitCannotBorrowEasyDefaultRights() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val limits = InMemoryLimitsStore()
        limits.setTimeLimit(TimeLimit(pkg, 0, BreakLevel.HARDCORE))
        val c = coordinator(focus, rules = emptyList(), limits = limits)
        val cause = c.evaluateBlockCause(null, pkg, null, timeLimitSpent = true)!!
        assertEquals(BreakLevel.EASY, c.currentDefaultBreakLevel())
        assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
        assertEquals(BreakLevel.HARDCORE, c.screenState(pkg, null, cause, now).breakLevel)
        assertEquals(GrantResult.Denied, c.requestBreak(pkg, cause.copy(breakLevel = BreakLevel.EASY), GrantRequestId.issue(now)))
        assertTrue(focus.state.snapshotOrNull()!!.exemptions.isEmpty())
    }

    @Test fun PC_15_PC_25_PathBStoredHardcoreTimeLimitCannotBorrowEasyDefaultRights() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val limits = InMemoryLimitsStore()
        limits.setTimeLimit(TimeLimit(pkg, 0, BreakLevel.HARDCORE))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        val c = PathBBlockHandoff.create(app, scope,
            surfaceHost = object : BlockScreenCoordinator.SurfaceHost {
                override fun attach(view: View) = false
                override fun detach(view: View) = Unit
                override fun goHome() = Unit
            }, focusStateStore = focus, limitsStore = limits, rulesStore = InMemoryBlockRulesStore(),
            safetyFloor = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet()), nowEpochMs = { now })
        val cause = c.evaluateBlockCause(null, pkg, null, timeLimitSpent = true)!!
        assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
        assertEquals(BreakLevel.HARDCORE, c.currentBlockCause(pkg, cause)!!.breakLevel)
        assertEquals(BreakLevel.HARDCORE, c.screenState(pkg, null, cause, now).breakLevel)
        assertEquals(GrantResult.Denied, c.requestBreak(pkg, cause, GrantRequestId.issue(now)))
        assertTrue(focus.state.snapshotOrNull()!!.exemptions.isEmpty())
    }

    @Test fun PC_15_savedTimeLimitTierAlsoControlsHarderWaitAndRevalidatesAStaleEasySurface() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val limits = InMemoryLimitsStore()
        limits.setTimeLimit(TimeLimit(pkg, 0, BreakLevel.HARDER))
        val c = coordinator(focus, rules = emptyList(), limits = limits)
        val cause = c.evaluateBlockCause(null, pkg, null, timeLimitSpent = true)!!
        assertEquals(BreakLevel.HARDER, cause.breakLevel)
        assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Pending)
        limits.setTimeLimit(TimeLimit(pkg, 0, BreakLevel.HARDCORE))
        assertEquals(GrantResult.Denied, c.requestBreak(pkg, cause.copy(breakLevel = BreakLevel.EASY), GrantRequestId.issue(now)))
        assertTrue(focus.state.snapshotOrNull()!!.exemptions.isEmpty())
    }

    @Test fun PC_15_savedEasyTimeLimitDoesNotAcquireHardcoreRightsFromTheGlobalDefault() = runBlocking {
        val focus = InMemoryFocusStateStore()
        focus.setDefaultBreakLevel(BreakLevel.HARDCORE)
        val limits = InMemoryLimitsStore()
        limits.setTimeLimit(TimeLimit(pkg, 0, BreakLevel.EASY))
        val c = coordinator(focus, rules = emptyList(), limits = limits)
        val cause = c.evaluateBlockCause(null, pkg, null, timeLimitSpent = true)!!
        assertEquals(BreakLevel.EASY, cause.breakLevel)
        assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Granted)
        assertEquals(BreakLevel.HARDCORE, c.currentDefaultBreakLevel())
    }

    @Test fun PC_15_PC_25_loadingOrUnreadableStoredTimeTierCannotBorrowAnOrdinaryExemption() = runBlocking {
        for (state in listOf(StorageState.Loading, StorageState.Unavailable(StorageFailure.IO))) {
            BlockExemptions.clearAll()
            val focus = InMemoryFocusStateStore()
            val configured = InMemoryLimitsStore(initialTimeLimits = listOf(TimeLimit(pkg, 0, BreakLevel.HARDCORE)))
            val limits = object : LimitsStore by configured {
                override val timeLimitsState = flowOf<StorageState<List<TimeLimit>>>(state)
            }
            val c = coordinator(focus, rules = emptyList(), limits = limits)
            BlockExemptions.grant(pkg, now + 60_000)
            val cause = c.evaluateBlockCause(null, pkg, null, timeLimitSpent = true)!!
            assertEquals(BreakLevel.HARDCORE, c.currentTimeLimitBreakLevel(pkg))
            assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
            assertFalse(c.isExempt(pkg, cause))
            assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Unavailable)
            assertTrue(focus.state.snapshotOrNull()!!.exemptions.isEmpty())
        }
    }

    @Test fun acceptedGrantHooksOnlyFireAfterCommitAndNeverForAnIdempotentRetry() = runBlocking {
        val focus = PausedFocus()
        val coordinator = coordinator(focus)
        val cause = ruleBlockCause(rule(BreakLevel.EASY), now, 0)
        val id = GrantRequestId.issue(now)
        val result = async { coordinator.requestBreak(pkg, cause, id) }
        focus.started.await()
        assertEquals(GrantStatus.SAVING, coordinator.screenState(pkg, null, cause, now).grantStatus)
        assertTrue(events.isEmpty())
        assertFalse(coordinator.isExempt(pkg, cause))
        focus.release.complete(Unit)
        assertTrue(result.await() is GrantResult.Granted)
        assertEquals(listOf("break"), events)
        assertTrue(coordinator.canDismiss(pkg, cause))
        assertTrue(coordinator.requestBreak(pkg, cause, id) is GrantResult.Granted)
        assertEquals(listOf("break"), events)
    }

    @Test fun PC_22_breakStartUsesTheDurableReceiptIdentityOnlyAfterAcknowledgement() = runBlocking {
        val ids = mutableListOf<String>()
        val observer = object : BlockEventHooks.Listener {
            override fun onBreakGranted(ruleId: String?, grantId: String, untilEpochMs: Long, atEpochMs: Long) { ids += grantId }
        }
        BlockEventHooks.register(observer)
        try {
            val focus = PausedFocus()
            val c = coordinator(focus)
            val cause = ruleBlockCause(rule(BreakLevel.EASY), now, 0)
            val id = GrantRequestId.issue(now)
            val pending = async { c.requestBreak(pkg, cause, id) }
            focus.started.await()
            assertTrue(ids.isEmpty())
            focus.release.complete(Unit)
            assertTrue(pending.await() is GrantResult.Granted)
            assertEquals(focus.backing.state.snapshotOrNull()!!.exemptions.keys.toList(), ids)
            c.requestBreak(pkg, cause, id)
            assertEquals(1, ids.size)
        } finally { BlockEventHooks.unregister(observer) }
    }

    @Test fun PC_43_PC_45_legacyPackageCallbackWithoutAFullPreviewCannotSpendOrEmitAcceptedHistory() = runBlocking {
        val committed = mutableListOf<List<Any?>>()
        var oldCalls = 0
        val observer = object : BlockEventHooks.Listener {
            override fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) { oldCalls++ }
            override fun onEmergencyPassCommitted(operationId: String, affectedRuleIds: Set<String>, recoveryEndEpochMs: Long, atEpochMs: Long) {
                committed += listOf(operationId, affectedRuleIds, recoveryEndEpochMs, atEpochMs)
            }
        }
        BlockEventHooks.register(observer)
        try {
            val focus = PausedFocus()
            val c = coordinator(focus, rules = listOf(rule(BreakLevel.HARDCORE)))
            val cause = ruleBlockCause(rule(BreakLevel.HARDCORE), now, 0)
            val id = GrantRequestId.issue(now)
            assertTrue(c.requestEmergencyPass(pkg, cause, id) is GrantResult.Unavailable)
            assertTrue(committed.isEmpty())
            assertTrue(events.isEmpty())
            assertFalse(focus.started.isCompleted)
            assertTrue(focus.backing.state.snapshotOrNull()!!.exemptions.isEmpty())
            c.requestEmergencyPass(pkg, cause, id)
            assertEquals(0, committed.size)
            assertEquals(0, oldCalls)
        } finally { BlockEventHooks.unregister(observer) }
    }

    @Test fun PC_47_aPassWithoutTheAtomicScopeBackendKeepsTheShieldAndOffersUnavailableState() = runBlocking {
        val focus = PausedFocus().apply { fail = true }
        val coordinator = coordinator(focus, rules = listOf(rule(BreakLevel.HARDCORE)))
        val cause = ruleBlockCause(rule(BreakLevel.HARDCORE), now, 0)
        assertTrue(coordinator.requestEmergencyPass(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Unavailable)
        assertFalse(focus.started.isCompleted)
        assertEquals(GrantStatus.UNAVAILABLE, coordinator.screenState(pkg, null, cause, now).grantStatus)
        assertFalse(coordinator.canDismiss(pkg, cause))
        assertTrue(events.isEmpty())
    }

    @Test fun loadedRulesStillEnforceWhileFocusIsUnavailableAndNoGrantCanSpend() = runBlocking {
        val backing = InMemoryFocusStateStore()
        val focus = object : FocusStateStore by backing {
            override val state = MutableStateFlow<StorageState<FocusSnapshot>>(StorageState.Unavailable(StorageFailure.CORRUPT))
        }
        val rule = rule(BreakLevel.HARDCORE)
        val c = coordinator(focus, rules = listOf(rule))
        val cause = ruleBlockCause(rule, now, 0)
        assertEquals(cause, c.evaluateBlockCause(null, pkg, cause, false))
        assertEquals(GrantStatus.UNAVAILABLE, c.screenState(pkg, null, cause, now).grantStatus)
        assertTrue(c.requestEmergencyPass(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Unavailable)
        assertFalse(c.canDismiss(pkg, cause))
        assertNull(backing.state.snapshotOrNull()!!.emergencyPass.usedAtEpochMs)
        assertTrue(events.isEmpty())
    }

    @Test fun unrestrictedForegroundSwitchesNeverQueueACounterCommit() = runBlocking {
        val focus = PausedFocus()
        val c = coordinator(focus, rules = emptyList())
        repeat(32) { index ->
            assertTrue(c.onForegroundChanged("com.example.${index - 1}", "com.example.$index") is OpenLimitDecision.Allowed)
        }
        assertFalse(focus.started.isCompleted)
        assertTrue(focus.backing.state.snapshotOrNull()!!.exemptions.isEmpty())
    }

    @Test fun corruptUnrelatedTimeLimitsDoNotDisableAReadyRuleBreak() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val limits = object : LimitsStore by InMemoryLimitsStore() {
            override val timeLimitsState = flowOf<StorageState<List<TimeLimit>>>(StorageState.Unavailable(StorageFailure.CORRUPT))
        }
        val c = coordinator(focus, limits = limits)
        val cause = ruleBlockCause(rule(BreakLevel.EASY), now, 0)
        assertEquals(GrantStatus.READY, c.screenState(pkg, null, cause, now).grantStatus)
        assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Granted)
        assertEquals(1, focus.state.snapshotOrNull()!!.breakSession.breaksTaken)
    }

    @Test fun anUnavailableCauseSourceStillRefusesItsOrdinaryBreak() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val rules = object : BlockRulesStore by InMemoryBlockRulesStore() {
            override val rulesState = flowOf<StorageState<List<BlockRule>>>(StorageState.Unavailable(StorageFailure.CORRUPT))
        }
        val c = coordinator(focus, configuredRules = rules)
        val cause = ruleBlockCause(rule(BreakLevel.EASY), now, 0)
        assertEquals(GrantStatus.UNAVAILABLE, c.screenState(pkg, null, cause, now).grantStatus)
        assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Unavailable)
        assertEquals(0, focus.state.snapshotOrNull()!!.breakSession.breaksTaken)
    }

    @Test fun aReadyOpenLimitCanGrantWithoutAnUnrelatedRulesRead() = runBlocking {
        val focus = InMemoryFocusStateStore().also { it.setOpenCounts(OpenCountState(now / 86_400_000, mapOf(pkg to 1))) }
        val limits = InMemoryLimitsStore().also { it.setOpenLimit(OpenLimit(pkg, 1)) }
        val rules = object : BlockRulesStore by InMemoryBlockRulesStore() {
            override val rulesState = flowOf<StorageState<List<BlockRule>>>(StorageState.Unavailable(StorageFailure.CORRUPT))
        }
        val c = coordinator(focus, rules = emptyList(), limits = limits, configuredRules = rules)
        ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, StorageState.Unavailable(StorageFailure.CORRUPT))
        val cause = openLimitBlockCause(OpenLimit(pkg, 1), now, 0)
        assertEquals(GrantStatus.READY, c.screenState(pkg, null, cause, now).grantStatus)
        assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Granted)
        assertEquals(2, focus.state.snapshotOrNull()!!.openCounts.opensFor(pkg))
    }

    @Test fun PC_47_aReadyLegacyPassLedgerCannotStandInForAnUnreadableFullRecoveryScope() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val rules = object : BlockRulesStore by InMemoryBlockRulesStore() {
            override val rulesState = flowOf<StorageState<List<BlockRule>>>(StorageState.Unavailable(StorageFailure.CORRUPT))
        }
        val limits = object : LimitsStore by InMemoryLimitsStore() {
            override val timeLimitsState = flowOf<StorageState<List<TimeLimit>>>(StorageState.Unavailable(StorageFailure.CORRUPT))
            override val openLimitsState = flowOf<StorageState<List<OpenLimit>>>(StorageState.Unavailable(StorageFailure.CORRUPT))
        }
        val c = coordinator(focus, rules = listOf(rule(BreakLevel.HARDCORE)), limits = limits, configuredRules = rules)
        ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, StorageState.Unavailable(StorageFailure.CORRUPT))
        val cause = ruleBlockCause(rule(BreakLevel.HARDCORE), now, 0)
        assertEquals(GrantStatus.UNAVAILABLE, c.screenState(pkg, null, cause, now).grantStatus)
        assertTrue(c.requestEmergencyPass(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Unavailable)
        assertFalse(c.canDismiss(pkg, cause))
        assertNull(focus.state.snapshotOrNull()!!.emergencyPass.usedAtEpochMs)
    }

    @Test fun aFailedSaveDoesNotLabelANewlyMountedSurfaceAsFailed() = runBlocking {
        val focus = PausedFocus().apply { fail = true; release.complete(Unit) }
        val c = coordinator(focus, mount = true)
        val cause = ruleBlockCause(rule(BreakLevel.EASY), now, 0)
        c.showBlocked(pkg, null, cause)
        assertTrue(c.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Unavailable)
        assertEquals(GrantStatus.FAILED, c.screenState(pkg, null, cause, now).grantStatus)
        c.hideIfShown()
        c.showBlocked(pkg, null, cause)
        assertEquals(GrantStatus.READY, c.screenState(pkg, null, cause, now).grantStatus)
        c.hideIfShown()
    }

    @Test fun readinessRecoveryReevaluatesButOwnCreditWritesDoNotCreateAConfigLoop() = runBlocking {
        val backing = InMemoryFocusStateStore()
        val states = MutableStateFlow<StorageState<FocusSnapshot>>(StorageState.Loading)
        val focus = object : FocusStateStore by backing { override val state = states }
        var changes = 0
        val coordinator = coordinator(focus, onChanged = { changes++ })
        val cause = ruleBlockCause(rule(BreakLevel.EASY), now, 0)
        assertTrue(coordinator.requestBreak(pkg, cause, GrantRequestId.issue(now)) is GrantResult.Unavailable)
        assertFalse(coordinator.isEmergencyPassAvailable())
        val loaded = backing.state.filterIsInstance<StorageState.Ready<FocusSnapshot>>().first().value
        val initial = changes
        states.value = StorageState.Ready(loaded)
        assertEquals(initial + 1, changes)
        coordinator.requestBreak(pkg, cause, GrantRequestId.issue(now))
        assertEquals(initial + 1, changes)
        states.value = StorageState.Unavailable(StorageFailure.IO)
        val unavailable = changes
        states.value = StorageState.Ready(backing.state.filterIsInstance<StorageState.Ready<FocusSnapshot>>().first().value)
        assertEquals(unavailable + 1, changes)
    }

    @Test fun theLastAllowedOpenStaysShieldedUntilCommitThenRemainsUsable() = runBlocking {
        val focus = PausedFocus()
        val limits = InMemoryLimitsStore().also { it.setOpenLimit(OpenLimit(pkg, 1)) }
        var changes = 0
        val coordinator = coordinator(focus, rules = emptyList(), limits = limits, onChanged = { changes++ })
        val initial = changes
        assertTrue(coordinator.onForegroundChanged(null, pkg) is OpenLimitDecision.Blocked)
        focus.started.await()
        val cause = openLimitBlockCause(OpenLimit(pkg, 1), now, 0)
        assertNotNull(coordinator.currentBlockCause(pkg, cause))
        assertFalse(coordinator.canDismiss(pkg, cause))
        focus.release.complete(Unit)
        yield()
        assertTrue(coordinator.onForegroundChanged(pkg, pkg) is OpenLimitDecision.Allowed)
        assertTrue(coordinator.canDismiss(pkg, cause))
        assertEquals(initial + 1, changes)
        assertEquals(1, focus.backing.openCounts.first().opensFor(pkg))
        assertTrue(events.isEmpty())
    }

    @Test fun floorSurfacesCannotSpendCreditsOrCountersThroughAnyRequestEntryPoint() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val coordinator = coordinator(focus)
        val floor = "com.android.settings"
        val cause = BlockCause(BlockScreenReason.OPEN_LIMIT, BreakLevel.EASY, null)
        assertEquals(GrantResult.Denied, coordinator.requestBreak(floor, cause, GrantRequestId.issue(now)))
        assertEquals(GrantResult.Denied, coordinator.requestEmergencyPass(floor, cause, GrantRequestId.issue(now)))
        assertTrue(coordinator.onForegroundChanged(null, floor) is OpenLimitDecision.Allowed)
        assertEquals(0, focus.breakSession.first().breaksTaken)
        assertTrue(focus.openCounts.first().opensByPackage.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun aDisplayedOpenCauseIsNotDiscardedFromAnInitialEmptyLoadingSnapshot() {
        val states = MutableStateFlow<StorageState<List<OpenLimit>>>(StorageState.Loading)
        val limits = object : LimitsStore by InMemoryLimitsStore() { override val openLimitsState = states }
        val coordinator = coordinator(InMemoryFocusStateStore(), rules = emptyList(), limits = limits)
        val cause = openLimitBlockCause(OpenLimit(pkg, 1), now, 0)
        assertNotNull(coordinator.currentBlockCause(pkg, cause))
        states.value = StorageState.Ready(emptyList())
        assertNull(coordinator.currentBlockCause(pkg, cause))
    }

    @Test fun aSurfaceKeepsItsIdentityForRetriesAndRefreshesOnlyAfterTheRetryHorizon() {
        val requests = BlockScreenCoordinator.SurfaceRequests()
        val id = requests.id(false, now)
        assertSame(id, requests.id(false, now + 1))
        requests.recordResult(false, GrantResult.Unconfirmed)
        assertSame(id, requests.id(false, now + 2))
        requests.recordResult(false, GrantResult.Unavailable(StorageFailure.IO))
        assertSame(id, requests.id(false, now + 3))
        assertNotEquals(id, requests.id(true, now))
        assertNotEquals(id, requests.id(false, now + DurableGrantController.RETRY_HORIZON_MS))
    }

    @Test fun aConfirmedCauseChangeRejectionDoesNotRetainAnUnusableIdentity() {
        val requests = BlockScreenCoordinator.SurfaceRequests()
        val old = requests.id(false, now)
        requests.recordResult(false, GrantResult.Denied)
        assertNotEquals(old, requests.id(false, now + 1))
        val uncertain = requests.id(false, now + 2)
        requests.recordResult(false, GrantResult.Unconfirmed)
        assertEquals(uncertain, requests.id(false, now + 3))
    }

    @Test fun PC_31_uncertainOrdinaryRequestCannotReuseItsIdentityForAnExplicitCreditOrNewScope() {
        for (failure in listOf(GrantResult.Unconfirmed, GrantResult.Unavailable(StorageFailure.IO))) {
            val requests = BlockScreenCoordinator.SurfaceRequests()
            val clock = AccessClockStamp(now, 1_000, "boot")
            val ordinary = requests.id(false, now, clock)
            requests.recordResult(false, failure)
            assertSame(ordinary, requests.id(false, now, clock))
            val credit = requests.id(false, now, clock, "period:open-occurrence")
            assertNotEquals(ordinary.nonce, credit.nonce)
            assertEquals("period:open-occurrence", credit.creditScope)
            requests.recordResult(false, failure)
            assertSame(credit, requests.id(false, now, clock, "period:open-occurrence"))
            assertNotEquals(credit.nonce, requests.id(true, now, clock, "period:open-occurrence").nonce)
            assertNotEquals(credit.nonce, requests.id(false, now, clock, "next-period:open-occurrence").nonce)
            assertNull(requests.id(false, now, clock).creditScope)
        }
    }

    @Test fun aConfirmedGrantKeptBehindAStrongerCauseCannotWedgeTheNextExplicitBreak() = runBlocking {
        val focus = PausedFocus()
        val coordinator = coordinator(focus, mount = true)
        val easy = rule(BreakLevel.EASY)
        val cause = ruleBlockCause(easy, now, 0)
        coordinator.showBlocked(pkg, null, cause)
        fun field(name: String) = BlockScreenCoordinator::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(coordinator)
        val requests = field("surfaceRequests") as BlockScreenCoordinator.SurfaceRequests
        val callback = BlockScreenCoordinator::class.java.getDeclaredMethod("handleGrantRequested",
            String::class.java, BlockScreenCoordinator.SurfaceRequests::class.java, java.lang.Boolean.TYPE, String::class.java)
            .apply { isAccessible = true }
        callback.invoke(coordinator, pkg, requests, false, null)
        focus.started.await()
        val acceptedId = requests.breakId!!
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule(BreakLevel.HARDCORE)))
        focus.release.complete(Unit)
        yield()
        assertEquals(pkg, field("visiblePackage"))
        assertFalse(coordinator.canDismiss(pkg, cause))
        assertEquals(1, focus.backing.breakSession.first().breaksTaken)
        assertNull(requests.breakId)
        now += BlockScreenCoordinator.DEFAULT_BREAK_DURATION_MS + 1
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(easy))
        assertNotEquals(acceptedId, requests.id(false, now))
        callback.invoke(coordinator, pkg, requests, false, null)
        yield()
        assertEquals(2, focus.backing.breakSession.first().breaksTaken)
        assertNull(field("visiblePackage"))
    }
}
