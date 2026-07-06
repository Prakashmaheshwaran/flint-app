package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the overview's one-line summaries and the limit-row merge. */
class SummariesTest {

    // ---- Days ----

    @Test
    fun `empty or full day sets read every day`() {
        assertEquals("Every day", daysSummary(emptySet()))
        assertEquals("Every day", daysSummary((1..7).toSet()))
    }

    @Test
    fun `weekday and weekend presets get their names`() {
        assertEquals("Weekdays", daysSummary(setOf(1, 2, 3, 4, 5)))
        assertEquals("Weekends", daysSummary(setOf(6, 7)))
    }

    @Test
    fun `custom days list in iso order regardless of set order`() {
        assertEquals("Mon, Wed, Fri", daysSummary(setOf(5, 1, 3)))
    }

    @Test
    fun `invalid day numbers are ignored`() {
        assertEquals("Mon", daysSummary(setOf(0, 1, 8)))
    }

    @Test
    fun `three or more consecutive days collapse into a range`() {
        assertEquals("Mon–Thu", daysSummary(setOf(1, 2, 3, 4)))
        assertEquals("Tue–Sat", daysSummary(setOf(2, 3, 4, 5, 6)))
        assertEquals("Mon–Sat", daysSummary(setOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `a two-day run stays listed rather than hyphenated`() {
        assertEquals("Mon, Tue", daysSummary(setOf(1, 2)))
    }

    @Test
    fun `a range and a stray day combine`() {
        assertEquals("Mon–Thu, Sat", daysSummary(setOf(1, 2, 3, 4, 6)))
        assertEquals("Wed, Fri–Sun", daysSummary(setOf(3, 5, 6, 7)))
    }

    @Test
    fun `consecutive days are not wrapped across the week boundary`() {
        // Sun(7) then Mon(1) are numerically far apart — no "Sun–Mon".
        assertEquals("Mon, Sun", daysSummary(setOf(7, 1)))
    }

    // ---- Schedule ----

    @Test
    fun `null schedule reads always on`() {
        assertEquals("Always on while enabled", scheduleSummary(null))
    }

    @Test
    fun `daytime window formats plainly`() {
        assertEquals(
            "Weekdays · 09:00–17:00",
            scheduleSummary(Schedule(setOf(1, 2, 3, 4, 5), 540, 1020)),
        )
    }

    @Test
    fun `overnight window is flagged - engine wraps it across midnight`() {
        assertEquals(
            "Every day · 22:00–06:00 (overnight)",
            scheduleSummary(Schedule(emptySet(), 1320, 360)),
        )
    }

    @Test
    fun `equal endpoints are flagged as never active - engine treats the window as empty`() {
        assertTrue(scheduleSummary(Schedule(emptySet(), 600, 600)).contains("never active"))
    }

    // ---- Targets ----

    @Test
    fun `counts apps and sites with plurals`() {
        val targets = BlockTargets(
            apps = setOf(AppRef("a", "A")),
            domains = setOf(DomainRef("x.com"), DomainRef("y.com")),
        )
        assertEquals("1 app · 2 sites", targetsSummary(targets))
    }

    @Test
    fun `apps only and sites only`() {
        assertEquals("2 apps", targetsSummary(BlockTargets(apps = setOf(AppRef("a"), AppRef("b")))))
        assertEquals("1 site", targetsSummary(BlockTargets(domains = setOf(DomainRef("x.com")))))
    }

    @Test
    fun `empty targets are called out`() {
        assertEquals("No targets yet", targetsSummary(BlockTargets()))
    }

    @Test
    fun `allow-list summary states the inversion`() {
        val targets = BlockTargets(apps = setOf(AppRef("a"), AppRef("b")), allowListMode = true)
        assertEquals("Allow-list · everything blocked except 2 apps", targetsSummary(targets))
    }

    // ---- Break levels ----

    @Test
    fun `labels and descriptions are distinct and non-blank across all tiers`() {
        val labels = BreakLevel.entries.map { breakLevelLabel(it) }
        val descriptions = BreakLevel.entries.map { breakLevelDescription(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(descriptions.size, descriptions.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
        assertTrue(descriptions.all { it.isNotBlank() })
    }

    // ---- Durations & limit lines ----

    @Test
    fun `daily minutes format like the block screen durations`() {
        assertEquals("45m", formatDailyMinutes(45))
        assertEquals("1h", formatDailyMinutes(60))
        assertEquals("1h 30m", formatDailyMinutes(90))
        assertEquals("24h", formatDailyMinutes(1440))
        assertEquals("0m", formatDailyMinutes(-5))
    }

    @Test
    fun `limit summaries cover each combination`() {
        assertEquals("No limits", limitSummary(null, null))
        assertEquals("45m/day", limitSummary(TimeLimit("a", 45), null))
        assertEquals(
            "1 open/day (Hardcore)",
            limitSummary(null, OpenLimit("a", 1, BreakLevel.HARDCORE)),
        )
        assertEquals(
            "1h/day · 5 opens/day (Easy)",
            limitSummary(TimeLimit("a", 60), OpenLimit("a", 5, BreakLevel.EASY)),
        )
    }

    // ---- Limit rows ----

    @Test
    fun `rows merge both limit kinds per package`() {
        val rows = buildLimitRows(
            timeLimits = listOf(TimeLimit("z.z", 30)),
            openLimits = listOf(OpenLimit("z.z", 3)),
            labelFor = { null },
        )
        assertEquals(1, rows.size)
        assertEquals(30, rows[0].timeLimit?.dailyMinutes)
        assertEquals(3, rows[0].openLimit?.dailyOpens)
    }

    @Test
    fun `rows sort by display name with package fallback`() {
        val rows = buildLimitRows(
            timeLimits = listOf(TimeLimit("z.z", 30), TimeLimit("a.a", 10)),
            openLimits = listOf(OpenLimit("m.m", 2)),
            labelFor = { pkg ->
                when (pkg) {
                    "z.z" -> "Alpha"
                    "a.a" -> "Zulu"
                    else -> null
                }
            },
        )
        // "Alpha" (z.z) < "m.m" (fallback) < "Zulu" (a.a), case-insensitively.
        assertEquals(listOf("z.z", "m.m", "a.a"), rows.map { it.packageName })
    }

    @Test
    fun `a package with only one limit kind still gets a row`() {
        val rows = buildLimitRows(
            timeLimits = emptyList(),
            openLimits = listOf(OpenLimit("solo.app", 4)),
            labelFor = { null },
        )
        assertEquals(1, rows.size)
        assertEquals("solo.app", rows[0].packageName)
        assertEquals(null, rows[0].timeLimit)
    }

    @Test
    fun `display name falls back to the package for null or blank labels`() {
        assertEquals("a.b", AppLimitRow("a.b", null, null, null).displayName)
        assertEquals("a.b", AppLimitRow("a.b", "  ", null, null).displayName)
        assertEquals("App", AppLimitRow("a.b", "App", null, null).displayName)
    }
}
