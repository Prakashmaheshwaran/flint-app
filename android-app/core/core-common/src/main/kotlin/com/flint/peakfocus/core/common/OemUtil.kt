package com.flint.peakfocus.core.common

import android.os.Build

/** Device OEM, used to deep-link the user to the right auto-start / protected-app screen. */
enum class Oem { SAMSUNG, XIAOMI, OPPO, VIVO, HUAWEI, ONEPLUS, REALME, MOTOROLA, NOTHING, OTHER }

/**
 * OEM detection for the battery/auto-start survival flow (see the resilience module and the
 * Android strategy doc §6). Several OEMs aggressively kill background services and silently
 * disable AccessibilityService on reboot/idle; there is no single API to fix this, so we
 * detect the OEM and guide the user to the relevant settings screen.
 */
object OemUtil {
    val current: Oem by lazy { fromManufacturer(Build.MANUFACTURER) }

    /**
     * Pure `Build.MANUFACTURER` → [Oem] mapping, extracted so the sub-brand aliasing
     * (Redmi/POCO → Xiaomi, Honor → Huawei) is unit-testable off-device. Case- and
     * whitespace-insensitive; null, blank, or unrecognized values fall back to [Oem.OTHER].
     */
    fun fromManufacturer(manufacturer: String?): Oem = when (manufacturer?.trim()?.lowercase()) {
        "samsung" -> Oem.SAMSUNG
        "xiaomi", "redmi", "poco" -> Oem.XIAOMI
        "oppo" -> Oem.OPPO
        "vivo" -> Oem.VIVO
        "huawei", "honor" -> Oem.HUAWEI
        "oneplus" -> Oem.ONEPLUS
        "realme" -> Oem.REALME
        "motorola" -> Oem.MOTOROLA
        "nothing" -> Oem.NOTHING
        else -> Oem.OTHER
    }

    /**
     * OEMs whose stock power manager aggressively kills background work (dontkillmyapp.com data
     * set). Near-stock skins (Motorola, Nothing) usually respect the battery exemption, so they
     * aren't flagged here even though Flint still offers them tailored guidance.
     */
    private val AGGRESSIVE_POWER_MANAGERS = setOf(
        Oem.SAMSUNG, Oem.XIAOMI, Oem.OPPO, Oem.VIVO, Oem.HUAWEI, Oem.ONEPLUS, Oem.REALME,
    )

    val isAggressivePowerManager: Boolean
        get() = current in AGGRESSIVE_POWER_MANAGERS
}
