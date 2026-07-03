package com.flint.peakfocus.blocking.resilience

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM: exercises only the companion's action filter (string logic). The receiver itself
 * needs the framework and is exercised on a device/emulator, not here.
 */
class BootReceiverTest {

    @Test
    fun handlesStandardBootBroadcast() {
        assertTrue(BootReceiver.shouldHandle("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun handlesOemQuickbootVariants() {
        assertTrue(BootReceiver.shouldHandle("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(BootReceiver.shouldHandle("com.htc.intent.action.QUICKBOOT_POWERON"))
    }

    @Test
    fun ignoresOtherActionsAndNull() {
        assertFalse(BootReceiver.shouldHandle("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(BootReceiver.shouldHandle("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(BootReceiver.shouldHandle(""))
        assertFalse(BootReceiver.shouldHandle(null))
    }
}
