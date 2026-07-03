package com.flint.peakfocus.blocking.resilience

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM: `ApplicationExitInfo.REASON_*` are compile-time int constants, so no Android
 * framework is touched at runtime.
 */
class ExitCategoryMappingTest {

    @Test
    fun userInitiatedKillsMapToUserKill() {
        assertEquals(ExitCategory.USER_KILL, ExitCategory.fromReason(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(ExitCategory.USER_KILL, ExitCategory.fromReason(ApplicationExitInfo.REASON_USER_STOPPED))
    }

    @Test
    fun lowMemoryKillMapsToMemoryPressure() {
        assertEquals(ExitCategory.MEMORY_PRESSURE, ExitCategory.fromReason(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test
    fun crashesMapToCrash() {
        assertEquals(ExitCategory.CRASH, ExitCategory.fromReason(ApplicationExitInfo.REASON_CRASH))
        assertEquals(ExitCategory.CRASH, ExitCategory.fromReason(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(ExitCategory.CRASH, ExitCategory.fromReason(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
    }

    @Test
    fun anrMapsToAnr() {
        assertEquals(ExitCategory.ANR, ExitCategory.fromReason(ApplicationExitInfo.REASON_ANR))
    }

    @Test
    fun permissionChangeMapsToPermissionChange() {
        assertEquals(ExitCategory.PERMISSION_CHANGE, ExitCategory.fromReason(ApplicationExitInfo.REASON_PERMISSION_CHANGE))
    }

    @Test
    fun packageChangesMapToPackageChange() {
        assertEquals(ExitCategory.PACKAGE_CHANGE, ExitCategory.fromReason(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE))
        assertEquals(ExitCategory.PACKAGE_CHANGE, ExitCategory.fromReason(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun systemSideKillsMapToOemOrSystemKill() {
        // REASON_SIGNALED / REASON_OTHER are how OEM power-manager kills usually surface
        // (strategy doc §6.2) — these buckets drive the "whitelist Flint" suggestion.
        assertEquals(ExitCategory.OEM_OR_SYSTEM_KILL, ExitCategory.fromReason(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(ExitCategory.OEM_OR_SYSTEM_KILL, ExitCategory.fromReason(ApplicationExitInfo.REASON_OTHER))
        assertEquals(ExitCategory.OEM_OR_SYSTEM_KILL, ExitCategory.fromReason(ApplicationExitInfo.REASON_DEPENDENCY_DIED))
        assertEquals(ExitCategory.OEM_OR_SYSTEM_KILL, ExitCategory.fromReason(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertEquals(ExitCategory.OEM_OR_SYSTEM_KILL, ExitCategory.fromReason(ApplicationExitInfo.REASON_FREEZER))
    }

    @Test
    fun selfExitMapsToSelfExit() {
        assertEquals(ExitCategory.SELF_EXIT, ExitCategory.fromReason(ApplicationExitInfo.REASON_EXIT_SELF))
    }

    @Test
    fun unknownAndFutureCodesMapToUnknown() {
        assertEquals(ExitCategory.UNKNOWN, ExitCategory.fromReason(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals(ExitCategory.UNKNOWN, ExitCategory.fromReason(999)) // a code newer than this mapping
        assertEquals(ExitCategory.UNKNOWN, ExitCategory.fromReason(-1))
    }
}
