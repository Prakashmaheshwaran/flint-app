package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.core.common.Oem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemGuidanceTest {

    private val brandedOems = listOf(
        Oem.XIAOMI, Oem.HUAWEI, Oem.SAMSUNG, Oem.ONEPLUS, Oem.OPPO, Oem.VIVO, Oem.REALME,
    )

    @Test
    fun everyOemHasCompleteGuidance() {
        Oem.entries.forEach { oem ->
            val guidance = OemGuidanceCatalog.forOem(oem)
            assertEquals("keyed to the asked OEM", oem, guidance.oem)
            assertTrue("displayName for $oem", guidance.displayName.isNotBlank())
            assertTrue("summary for $oem", guidance.summary.isNotBlank())
            assertTrue("at least two steps for $oem", guidance.steps.size >= 2)
            guidance.steps.forEach { step ->
                assertTrue("blank step for $oem", step.isNotBlank())
            }
        }
    }

    @Test
    fun requiredManufacturersAreNamedInTheirGuidance() {
        // The task-level minimum set: Xiaomi, Huawei, Samsung, OnePlus, Oppo, Vivo.
        val required = mapOf(
            Oem.XIAOMI to "xiaomi",
            Oem.HUAWEI to "huawei",
            Oem.SAMSUNG to "samsung",
            Oem.ONEPLUS to "oneplus",
            Oem.OPPO to "oppo",
            Oem.VIVO to "vivo",
        )
        required.forEach { (oem, brand) ->
            assertTrue(
                "guidance for $oem must name the brand",
                OemGuidanceCatalog.forOem(oem).displayName.lowercase().contains(brand),
            )
        }
    }

    @Test
    fun brandedGuidanceIsSpecificNotShared() {
        val stepSets = brandedOems.map { OemGuidanceCatalog.forOem(it).steps }
        assertEquals("each brand gets its own steps", stepSets.size, stepSets.distinct().size)
        val genericSteps = OemGuidanceCatalog.forOem(Oem.OTHER).steps
        brandedOems.forEach { oem ->
            assertFalse(
                "$oem must not reuse the generic fallback steps",
                OemGuidanceCatalog.forOem(oem).steps == genericSteps,
            )
        }
    }

    @Test
    fun xiaomiGuidanceCoversAutostart() {
        assertTrue(
            OemGuidanceCatalog.forOem(Oem.XIAOMI).steps
                .any { it.contains("Autostart", ignoreCase = true) },
        )
    }

    @Test
    fun huaweiGuidanceCoversAppLaunch() {
        assertTrue(
            OemGuidanceCatalog.forOem(Oem.HUAWEI).steps
                .any { it.contains("App launch", ignoreCase = true) },
        )
    }

    @Test
    fun samsungGuidanceCoversSleepingApps() {
        assertTrue(
            OemGuidanceCatalog.forOem(Oem.SAMSUNG).steps
                .any { it.contains("sleeping", ignoreCase = true) },
        )
    }

    @Test
    fun fallbackGuidanceIsGenericAndUnbranded() {
        val other = OemGuidanceCatalog.forOem(Oem.OTHER)
        listOf("xiaomi", "huawei", "samsung", "oneplus", "oppo", "vivo", "realme")
            .forEach { brand ->
                assertFalse(
                    "generic guidance must not name $brand",
                    other.displayName.lowercase().contains(brand) ||
                        other.summary.lowercase().contains(brand),
                )
            }
        // The generic path still points at the one universal lever: the battery exemption.
        assertTrue(other.steps.any { it.contains("battery", ignoreCase = true) })
    }
}
