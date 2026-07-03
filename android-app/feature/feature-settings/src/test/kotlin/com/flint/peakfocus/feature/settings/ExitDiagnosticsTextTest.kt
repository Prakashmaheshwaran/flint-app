package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.blocking.resilience.ExitCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitDiagnosticsTextTest {

    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val now = 1_750_000_000_000L

    // --- category copy ---

    @Test
    fun everyCategoryHasAnExplanation() {
        ExitCategory.entries.forEach { category ->
            val explanation = ExitDiagnosticsText.explain(category)
            assertTrue("headline for $category", explanation.headline.isNotBlank())
            assertTrue("detail for $category", explanation.detail.isNotBlank())
        }
    }

    @Test
    fun headlinesAreDistinctSoTheListIsScannable() {
        val headlines = ExitCategory.entries.map { ExitDiagnosticsText.explain(it).headline }
        assertEquals(headlines.size, headlines.distinct().size)
    }

    @Test
    fun oemKillPointsAtTheBatteryFix() {
        val detail = ExitDiagnosticsText.explain(ExitCategory.OEM_OR_SYSTEM_KILL).detail
        assertTrue(detail.contains("battery", ignoreCase = true))
    }

    @Test
    fun permissionChangePointsBackAtTheAccessList() {
        val detail = ExitDiagnosticsText.explain(ExitCategory.PERMISSION_CHANGE).detail
        assertTrue(detail.contains("access", ignoreCase = true))
    }

    @Test
    fun crashCopyOwnsUpToBeingAFlintBug() {
        val detail = ExitDiagnosticsText.explain(ExitCategory.CRASH).detail
        assertTrue(detail.contains("bug", ignoreCase = true))
    }

    // --- relative age ---

    @Test
    fun freshTimestampsReadJustNow() {
        assertEquals("just now", ExitDiagnosticsText.relativeAge(now, now))
        assertEquals("just now", ExitDiagnosticsText.relativeAge(now - 59_999L, now))
    }

    @Test
    fun futureTimestampsReadJustNowNotNonsense() {
        // Clock changes can leave records "from the future"; never render a negative age.
        assertEquals("just now", ExitDiagnosticsText.relativeAge(now + hour, now))
    }

    @Test
    fun minutesBucket() {
        assertEquals("1 min ago", ExitDiagnosticsText.relativeAge(now - minute, now))
        assertEquals("59 min ago", ExitDiagnosticsText.relativeAge(now - 59 * minute, now))
    }

    @Test
    fun hoursBucket() {
        assertEquals("1 hr ago", ExitDiagnosticsText.relativeAge(now - hour, now))
        assertEquals("23 hr ago", ExitDiagnosticsText.relativeAge(now - day + 1, now))
    }

    @Test
    fun daysBucketWithSingularDay() {
        assertEquals("1 day ago", ExitDiagnosticsText.relativeAge(now - day, now))
        assertEquals("1 day ago", ExitDiagnosticsText.relativeAge(now - 2 * day + 1, now))
        assertEquals("2 days ago", ExitDiagnosticsText.relativeAge(now - 2 * day, now))
        assertEquals("40 days ago", ExitDiagnosticsText.relativeAge(now - 40 * day, now))
    }
}
