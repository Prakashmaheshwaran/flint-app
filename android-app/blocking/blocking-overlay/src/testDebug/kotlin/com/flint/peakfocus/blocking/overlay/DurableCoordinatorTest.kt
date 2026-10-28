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
    private val now = 1_800_000_000_000L
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
        limits: LimitsStore = InMemoryLimitsStore(), onChanged: () -> Unit = {},
    ): BlockScreenCoordinator {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, rules)
        return BlockScreenCoordinator(app, scope, object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = false
            override fun detach(view: View) = Unit
            override fun goHome() = Unit
        }, nowEpochMs = { now }, onEnforcementChanged = onChanged,
            safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }),
            focusStateStore = focus, configuredLimitsStore = limits, blockRulesStore = InMemoryBlockRulesStore(rules))
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

    @Test fun aFailedPassKeepsTheShieldAndShowsFailureWithoutCallingHistory() = runBlocking {
        val focus = PausedFocus().apply { fail = true }
        val coordinator = coordinator(focus, rules = listOf(rule(BreakLevel.HARDCORE)))
        val cause = ruleBlockCause(rule(BreakLevel.HARDCORE), now, 0)
        val pending = async { coordinator.requestEmergencyPass(pkg, cause, GrantRequestId.issue(now)) }
        focus.started.await()
        focus.release.complete(Unit)
        assertTrue(pending.await() is GrantResult.Unavailable)
        assertEquals(GrantStatus.FAILED, coordinator.screenState(pkg, null, cause, now).grantStatus)
        assertFalse(coordinator.canDismiss(pkg, cause))
        assertTrue(events.isEmpty())
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
        assertNotEquals(id, requests.id(true, now))
        assertNotEquals(id, requests.id(false, now + DurableGrantController.RETRY_HORIZON_MS))
    }
}
