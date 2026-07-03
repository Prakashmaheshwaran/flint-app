package com.flint.peakfocus.core.common

import android.os.Build

/** Device OEM, used to deep-link the user to the right auto-start / protected-app screen. */
enum class Oem { SAMSUNG, XIAOMI, OPPO, VIVO, HUAWEI, ONEPLUS, REALME, OTHER }

/**
 * OEM detection for the battery/auto-start survival flow (see the resilience module and the
 * Android strategy doc §6). Several OEMs aggressively kill background services and silently
 * disable AccessibilityService on reboot/idle; there is no single API to fix this, so we
 * detect the OEM and guide the user to the relevant settings screen.
 */
object OemUtil {
    val current: Oem by lazy {
        when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> Oem.SAMSUNG
            "xiaomi", "redmi", "poco" -> Oem.XIAOMI
            "oppo" -> Oem.OPPO
            "vivo" -> Oem.VIVO
            "huawei", "honor" -> Oem.HUAWEI
            "oneplus" -> Oem.ONEPLUS
            "realme" -> Oem.REALME
            else -> Oem.OTHER
        }
    }

    /** Known to aggressively kill background work (dontkillmyapp.com data set). */
    val isAggressivePowerManager: Boolean
        get() = current != Oem.OTHER
}
