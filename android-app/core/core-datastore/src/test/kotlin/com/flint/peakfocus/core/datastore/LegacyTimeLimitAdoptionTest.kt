package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.StorageFailure
import org.junit.Assert.*
import org.junit.Test

/** Pure v5 adoption plan only; marker/reader retirement needs the later transaction fixtures. */
class LegacyTimeLimitAdoptionTest {
    private val zones = mapOf("app" to "America/New_York", "other" to "Asia/Kolkata")
    private fun row(minutes: Int = 30, tier: BreakLevel = BreakLevel.HARDER, zone: String = "America/New_York") =
        AdoptedTimeLimit("app", minutes, tier, zone)
    private fun unavailable(block: () -> Unit) {
        try { block(); fail("Invalid legacy adoption accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
    }

    @Test fun `PC_70 legacy only retains budget saved capture zone and explicit old tier`() {
        val plan = planLegacyTimeLimitAdoption(emptyList(), mapOf("app" to 45), "HARDCORE", zones)
        assertEquals(listOf(row(45, BreakLevel.HARDCORE)), plan.limits)
        assertEquals(setOf("app"), plan.newTargets)
        assertTrue(plan.mergedTargets.isEmpty())
        assertFalse(plan.defaultTierNeedsReview)
    }

    @Test fun `PC_70 duplicate keeps minimum budget and stricter tier independently`() {
        val canonical = row(30, BreakLevel.HARDCORE)
        val plan = planLegacyTimeLimitAdoption(listOf(canonical), mapOf("app" to 15), "EASY", zones)
        assertEquals(listOf(canonical.copy(dailyMinutes = 15)), plan.limits)
        assertEquals(setOf("app"), plan.mergedTargets)
        assertTrue(plan.newTargets.isEmpty())
        val reverse = planLegacyTimeLimitAdoption(listOf(row(15, BreakLevel.EASY)), mapOf("app" to 30), "HARDCORE", zones)
        assertEquals(listOf(row(15, BreakLevel.HARDCORE)), reverse.limits)
    }

    @Test fun `PC_70 absent old default is explicit review flagged Easy while malformed is unavailable`() {
        val absent = planLegacyTimeLimitAdoption(emptyList(), mapOf("app" to 30), null, zones)
        assertEquals(BreakLevel.EASY, absent.limits.single().breakLevel)
        assertTrue(absent.defaultTierNeedsReview)
        for (raw in listOf("", "easy", "FUTURE_TIER", " HARDCORE")) {
            unavailable { planLegacyTimeLimitAdoption(emptyList(), mapOf("app" to 30), raw, zones) }
        }
    }

    @Test fun `PC_56 new legacy target cannot fall back to the current device zone`() {
        unavailable { planLegacyTimeLimitAdoption(emptyList(), mapOf("missing" to 30), "EASY", zones) }
        unavailable { planLegacyTimeLimitAdoption(emptyList(), mapOf("app" to 30), "EASY", mapOf("app" to "bad/zone")) }
    }

    @Test fun `duplicate keeps actual canonical saved zone even if old capture differs`() {
        val canonical = row(zone = "Europe/London")
        assertEquals("Europe/London", planLegacyTimeLimitAdoption(listOf(canonical), mapOf("app" to 15), "EASY", zones)
            .limits.single().zoneId)
    }

    @Test fun `PC_70 zero and large valid legacy budgets are not clipped to new editor bounds`() {
        val plan = planLegacyTimeLimitAdoption(emptyList(), mapOf("app" to 0, "other" to Int.MAX_VALUE), "HARDER", zones)
        assertEquals(listOf(0, Int.MAX_VALUE), plan.limits.map { it.dailyMinutes })
        unavailable { planLegacyTimeLimitAdoption(emptyList(), mapOf("app" to -1), "EASY", zones) }
    }

    @Test fun `PC_70 adoption values are stable on repeated planning and preserve source maps`() {
        val canonical = listOf(row(15))
        val legacy = linkedMapOf("other" to 10, "app" to 30)
        val original = legacy.toMap()
        val first = planLegacyTimeLimitAdoption(canonical, legacy, "HARDCORE", zones)
        val second = planLegacyTimeLimitAdoption(first.limits, legacy, "HARDCORE", zones)
        assertEquals(first.limits, second.limits)
        assertEquals(original, legacy)
        assertEquals(listOf("app", "other"), first.limits.map { it.packageName })
        // Store migration must additionally skip archive/provenance rewrites after its marker.
    }

    @Test fun `PC_71 duplicate canonical records or invalid values remain unavailable`() {
        unavailable { planLegacyTimeLimitAdoption(listOf(row(), row()), emptyMap(), "EASY", zones) }
        unavailable { planLegacyTimeLimitAdoption(listOf(row(-1)), emptyMap(), "EASY", zones) }
        unavailable { planLegacyTimeLimitAdoption(emptyList(), mapOf(" " to 5), "EASY", mapOf(" " to "UTC")) }
    }

    @Test fun `unused legacy domain has no artificial default review or adoption`() {
        val canonical = listOf(row())
        assertEquals(LegacyTimeLimitAdoption(canonical, emptySet(), emptySet(), false),
            planLegacyTimeLimitAdoption(canonical, emptyMap(), null, emptyMap()))
    }
}
