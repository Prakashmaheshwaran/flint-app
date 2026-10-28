package com.flint.peakfocus.blocking.resilience

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flowOf
import com.flint.peakfocus.blocking.engine.ClockShift

/**
 * Pure-JVM: exercises the action filters and bounded store snapshot reads. The receiver itself
 * needs the framework and is exercised on a device/emulator, not here; the guard decisions it
 * applies are pure and covered in blocking-engine's ClockChangeGuardTest.
 */
class TimeChangeReceiverTest {

    @Test fun measuredRollbackClipsAllAccessDeadlinesAtomicallyWithoutActivatingOpenReceipts() = runBlocking {
        val store = InMemoryFocusStateStore()
        val receipts = listOf(
            PersistedExemption("break", "app", "cause", ExemptionKind.BREAK, 120_000, "boot"),
            PersistedExemption("pass", "*", "cause", ExemptionKind.EMERGENCY_PASS, 160_000, "boot"),
            PersistedExemption("expired", "app", "cause", ExemptionKind.BREAK, 5_000, "boot"),
            PersistedExemption("open", "app", "open", ExemptionKind.BREAK, 0, "boot"),
        ).associateBy { it.requestId }
        store.transact { FocusMutation(it.copy(exemptions = receipts,
            breakSession = BreakSessionState(2, null),
            openCounts = OpenCountState(0, mapOf("app" to 7)),
            emergencyPass = EmergencyPassState(0, 99)), Unit) }
        val before = store.state.snapshotOrNull()!!
        val first = guardCurrentFocus(store, ClockShift(100_000, 40_000, 0, 0)) as FocusCommit.Committed
        assertEquals(before.revision + 1, first.snapshot.revision)
        assertEquals(60_000L, first.snapshot.exemptions.getValue("break").untilEpochMs)
        assertEquals(100_000L, first.snapshot.exemptions.getValue("pass").untilEpochMs)
        assertEquals(0L, first.snapshot.exemptions.getValue("expired").untilEpochMs)
        assertEquals(0L, first.snapshot.exemptions.getValue("open").untilEpochMs)
        assertEquals(before.breakSession, first.snapshot.breakSession)
        assertEquals(before.openCounts, first.snapshot.openCounts)
        assertEquals(before.emergencyPass, first.snapshot.emergencyPass)
        val second = guardCurrentFocus(store, ClockShift(40_000, 30_000, 0, 0)) as FocusCommit.Committed
        assertEquals(20_000L, second.snapshot.exemptions.getValue("break").untilEpochMs - 30_000)
        assertEquals(60_000L, second.snapshot.exemptions.getValue("pass").untilEpochMs - 30_000)
    }

    @Test fun forwardZoneAndUnmeasuredSignalsNeverExtendExistingAccess() = runBlocking {
        for (shift in listOf(ClockShift(100_000, 200_000, 0, 0),
            ClockShift(100_000, 100_000, 0, 3_600_000), ClockShift.unmeasured(100_000, 0, 0))) {
            val store = InMemoryFocusStateStore()
            val receipt = PersistedExemption("break", "app", "cause", ExemptionKind.BREAK, 120_000, "boot")
            store.transact { FocusMutation(it.copy(exemptions = mapOf("break" to receipt)), Unit) }
            val committed = guardCurrentFocus(store, shift) as FocusCommit.Committed
            assertEquals(receipt, committed.snapshot.exemptions.getValue("break"))
        }
    }

    @Test fun unreadableOrFailingFocusCannotSkipTheIndependentOneShotRuleGuard() = runBlocking {
        val shift = ClockShift(100_000, 200_000, 0, 0)
        for (throws in listOf(false, true)) {
            val focus = object : FocusStateStore by InMemoryFocusStateStore() {
                override suspend fun <R> transact(transform: (FocusSnapshot) -> FocusMutation<R>): FocusCommit<R> {
                    if (throws) throw java.io.IOException("injected")
                    return FocusCommit.Unavailable(StorageFailure.CORRUPT)
                }
            }
            val session = BlockRule("session", "Focus", BlockTargets(), expiresAtEpochMs = 300_000)
            val rules = InMemoryBlockRulesStore(listOf(session))
            withTimeout(500) { applyClockGuards(focus, rules, shift) }
            assertEquals(400_000L, rules.rulesState.snapshotOrNull()!!.single().expiresAtEpochMs)
        }
    }

    @Test fun unavailableRulesCannotSkipTheIndependentFocusGuard() = runBlocking {
        val focus = InMemoryFocusStateStore()
        val receipt = PersistedExemption("break", "app", "cause", ExemptionKind.BREAK, 120_000, "boot")
        focus.transact { FocusMutation(it.copy(exemptions = mapOf("break" to receipt)), Unit) }
        val rules = object : BlockRulesStore by InMemoryBlockRulesStore() {
            override val rulesState = flowOf<StorageState<List<BlockRule>>>(StorageState.Unavailable(StorageFailure.IO))
        }
        withTimeout(500) { applyClockGuards(focus, rules, ClockShift(100_000, 40_000, 0, 0)) }
        assertEquals(60_000L, focus.state.snapshotOrNull()!!.exemptions.getValue("break").untilEpochMs)
    }

    @Test fun guardCannotRefundAGrantCommittedAfterTheWarmUpSnapshot() = runBlocking {
        val store = InMemoryFocusStateStore()
        store.setOpenCounts(OpenCountState(0, mapOf("com.app" to 2)))
        store.setEmergencyPass(EmergencyPassState(0, null))
        store.setBreakSession(BreakSessionState(0, BreakRequestState(1_000, 61_000)))
        val old = clockGuardSnapshot(store, InMemoryBlockRulesStore())!!.first
        val receipt = PersistedExemption("grant", "com.app", "cause", ExemptionKind.EMERGENCY_PASS, 2_000_000_000_000)
        store.transact { FocusMutation(it.copy(
            openCounts = it.openCounts.copy(opensByPackage = mapOf("com.app" to 3)),
            emergencyPass = it.emergencyPass.copy(usedAtEpochMs = 1_700_700_000_000),
            breakSession = BreakSessionState(1, null),
            exemptions = mapOf(receipt.requestId to receipt),
        ), Unit) }
        assertEquals(2, old.openCounts.opensFor("com.app"))
        val guarded = guardCurrentFocus(store, ClockShift(1_700_000_000_000, 1_700_700_000_000, 0, 0)) as FocusCommit.Committed
        assertEquals(3, guarded.snapshot.openCounts.opensFor("com.app"))
        assertEquals(1_700_700_000_000, guarded.snapshot.emergencyPass.usedAtEpochMs)
        assertEquals(BreakSessionState(1, null), guarded.snapshot.breakSession)
        assertEquals(receipt, guarded.snapshot.exemptions[receipt.requestId])
    }

    @Test fun allClockGuardAccountingChangesCommitInOneRevision() = runBlocking {
        val store = InMemoryFocusStateStore()
        store.setOpenCounts(OpenCountState(0, mapOf("com.app" to 2)))
        store.setEmergencyPass(EmergencyPassState(0, 10))
        store.setBreakSession(BreakSessionState(1, BreakRequestState(1_000, 61_000)))
        val before = clockGuardSnapshot(store, InMemoryBlockRulesStore())!!.first
        val shift = ClockShift(1_700_000_000_000, 1_700_700_000_000, 0, 0)
        val guarded = guardCurrentFocus(store, shift) as FocusCommit.Committed
        assertEquals(before.revision + 1, guarded.snapshot.revision)
        assertTrue(guarded.snapshot.openCounts.epochDay > before.openCounts.epochDay)
        assertTrue(guarded.snapshot.emergencyPass.weekStartEpochDay > before.emergencyPass.weekStartEpochDay)
        assertEquals(61_000 + shift.wallDeltaMs, guarded.snapshot.breakSession.pending!!.effectiveAtEpochMs)
        assertEquals(10L, guarded.snapshot.emergencyPass.usedAtEpochMs)
    }

    @Test fun unavailableFocusCompletesWithoutWaitingForAReadyValue() = runBlocking {
        for (reason in StorageFailure.entries) {
            val focus = object : FocusStateStore by InMemoryFocusStateStore() {
                override val state = flowOf<StorageState<FocusSnapshot>>(StorageState.Loading, StorageState.Unavailable(reason))
            }
            assertNull(withTimeout(500) { clockGuardSnapshot(focus, InMemoryBlockRulesStore()) })
        }
    }

    @Test fun unavailableRulesCompleteWithoutWaitingForAReadyValue() = runBlocking {
        for (reason in StorageFailure.entries) {
            val rules = object : BlockRulesStore by InMemoryBlockRulesStore() {
                override val rulesState = flowOf<StorageState<List<BlockRule>>>(StorageState.Loading, StorageState.Unavailable(reason))
            }
            assertNull(withTimeout(500) { clockGuardSnapshot(InMemoryFocusStateStore(), rules) })
        }
    }

    @Test fun completeSnapshotsRemainAvailableAfterInitialLoading() = runBlocking {
        val focus = InMemoryFocusStateStore().also { it.setDefaultBreakLevel(BreakLevel.HARDER) }
        val rule = BlockRule("rule", "Work", BlockTargets())
        val snapshot = withTimeout(500) { clockGuardSnapshot(focus, InMemoryBlockRulesStore(listOf(rule))) }!!
        assertEquals(BreakLevel.HARDER, snapshot.first.defaultBreakLevel)
        assertEquals(listOf(rule), snapshot.second)
    }

    @Test
    fun handlesClockTimezoneAndDateChanges() {
        assertTrue(TimeChangeReceiver.shouldHandle("android.intent.action.TIME_SET"))
        assertTrue(TimeChangeReceiver.shouldHandle("android.intent.action.TIMEZONE_CHANGED"))
        assertTrue(TimeChangeReceiver.shouldHandle("android.intent.action.DATE_CHANGED"))
    }

    @Test
    fun ignoresOtherActionsAndNull() {
        assertFalse(TimeChangeReceiver.shouldHandle("android.intent.action.BOOT_COMPLETED"))
        assertFalse(TimeChangeReceiver.shouldHandle("android.intent.action.TIME_TICK"))
        assertFalse(TimeChangeReceiver.shouldHandle("android.intent.action.LOCALE_CHANGED"))
        assertFalse(TimeChangeReceiver.shouldHandle(""))
        assertFalse(TimeChangeReceiver.shouldHandle(null))
    }

    @Test
    fun onlyManipulationSignalsRekeyPersistedFocusState() {
        assertTrue(TimeChangeReceiver.rekeysFocusState("android.intent.action.TIME_SET"))
        assertTrue(TimeChangeReceiver.rekeysFocusState("android.intent.action.TIMEZONE_CHANGED"))
        // DATE_CHANGED also fires at genuine local midnight (legacy delivery) — re-keying
        // there would deny the honest fresh day, so it only re-arms, never re-keys.
        assertFalse(TimeChangeReceiver.rekeysFocusState("android.intent.action.DATE_CHANGED"))
        assertFalse(TimeChangeReceiver.rekeysFocusState(null))
    }
}
