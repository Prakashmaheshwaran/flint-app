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

/**
 * Pure-JVM: exercises the action filters and bounded store snapshot reads. The receiver itself
 * needs the framework and is exercised on a device/emulator, not here; the guard decisions it
 * applies are pure and covered in blocking-engine's ClockChangeGuardTest.
 */
class TimeChangeReceiverTest {

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
