package com.flint.peakfocus.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for the `Build.MANUFACTURER` → [Oem] mapping that decides which
 * battery/auto-start guidance a device sees. `OemUtil.current` reads Android's `Build`, so the
 * mapping itself is exercised through the extracted, device-free [OemUtil.fromManufacturer].
 */
class OemUtilTest {

    @Test
    fun `maps each known manufacturer to its OEM`() {
        assertEquals(Oem.SAMSUNG, OemUtil.fromManufacturer("samsung"))
        assertEquals(Oem.XIAOMI, OemUtil.fromManufacturer("xiaomi"))
        assertEquals(Oem.OPPO, OemUtil.fromManufacturer("oppo"))
        assertEquals(Oem.VIVO, OemUtil.fromManufacturer("vivo"))
        assertEquals(Oem.HUAWEI, OemUtil.fromManufacturer("huawei"))
        assertEquals(Oem.ONEPLUS, OemUtil.fromManufacturer("oneplus"))
        assertEquals(Oem.REALME, OemUtil.fromManufacturer("realme"))
        assertEquals(Oem.MOTOROLA, OemUtil.fromManufacturer("motorola"))
        assertEquals(Oem.NOTHING, OemUtil.fromManufacturer("nothing"))
    }

    @Test
    fun `sub-brands fold into their parent OEM`() {
        assertEquals(Oem.XIAOMI, OemUtil.fromManufacturer("Redmi"))
        assertEquals(Oem.XIAOMI, OemUtil.fromManufacturer("POCO"))
        assertEquals(Oem.HUAWEI, OemUtil.fromManufacturer("Honor"))
    }

    @Test
    fun `matching ignores case and surrounding whitespace`() {
        assertEquals(Oem.SAMSUNG, OemUtil.fromManufacturer("SAMSUNG"))
        assertEquals(Oem.XIAOMI, OemUtil.fromManufacturer("  Xiaomi  "))
        assertEquals(Oem.MOTOROLA, OemUtil.fromManufacturer("Motorola"))
    }

    @Test
    fun `null blank and unrecognized manufacturers fall back to OTHER`() {
        assertEquals(Oem.OTHER, OemUtil.fromManufacturer(null))
        assertEquals(Oem.OTHER, OemUtil.fromManufacturer(""))
        assertEquals(Oem.OTHER, OemUtil.fromManufacturer("   "))
        assertEquals(Oem.OTHER, OemUtil.fromManufacturer("google"))
        assertEquals(Oem.OTHER, OemUtil.fromManufacturer("fairphone"))
    }
}
