package com.flint.peakfocus.blocking.resilience

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM: exercises only the companion's action filters (string logic). The receiver itself
 * needs the framework and is exercised on a device/emulator, not here; the guard decisions it
 * applies are pure and covered in blocking-engine's ClockChangeGuardTest.
 */
class TimeChangeReceiverTest {

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
