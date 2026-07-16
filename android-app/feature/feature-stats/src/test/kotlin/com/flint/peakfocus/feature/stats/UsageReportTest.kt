package com.flint.peakfocus.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageReportTest {

    private fun day(millis: Long, isToday: Boolean = false) =
        DaySummary(label = "Mon", totalMillis = millis, isToday = isToday)

    @Test
    fun dailyAverageDividesWeekTotalByDayCount() {
        val report = UsageReport(
            days = List(7) { day(0L, isToday = it == 6) },
            todayTotalMillis = 0L,
            weekTotalMillis = 7 * 3_600_000L,
            topAppsToday = emptyList(),
        )
        assertEquals(3_600_000L, report.dailyAverageMillis)
    }

    @Test
    fun dailyAverageOfEmptyDaysIsZero() {
        val report = UsageReport(
            days = emptyList(),
            todayTotalMillis = 0L,
            weekTotalMillis = 0L,
            topAppsToday = emptyList(),
        )
        assertEquals(0L, report.dailyAverageMillis)
    }

    private fun report(
        days: List<DaySummary>,
        todayTotalMillis: Long = days.lastOrNull { it.isToday }?.totalMillis ?: 0L,
    ) = UsageReport(
        days = days,
        todayTotalMillis = todayTotalMillis,
        weekTotalMillis = days.sumOf { it.totalMillis },
        topAppsToday = emptyList(),
    )

    @Test
    fun typicalDayAveragesCompletedDaysOnlyAndExcludesPartialToday() {
        // Six completed days at 2h each, plus a light partial today. The typical day is 2h and
        // is NOT dragged down by today, unlike the whole-week dailyAverage.
        val twoHours = 2 * 3_600_000L
        val days = List(6) { day(twoHours, isToday = false) } + day(6_000L, isToday = true)
        val r = report(days)
        assertEquals(twoHours, r.typicalDayMillis)
        // Sanity: the naive 7-day average is pulled below the typical day by the partial today.
        assertTrue(r.dailyAverageMillis < r.typicalDayMillis)
    }

    @Test
    fun typicalDayCountsZeroUsageCompletedDays() {
        // One 4h day and one idle day → typical is the mean of the two, i.e. 2h.
        val days = listOf(
            day(4 * 3_600_000L, isToday = false),
            day(0L, isToday = false),
            day(0L, isToday = true),
        )
        assertEquals(2 * 3_600_000L, report(days).typicalDayMillis)
    }

    @Test
    fun typicalDayIsZeroWhenNoDayHasCompleted() {
        val r = report(listOf(day(3_600_000L, isToday = true)))
        assertEquals(0L, r.typicalDayMillis)
        assertEquals(emptyList<DaySummary>(), r.completedDays)
    }

    @Test
    fun busiestDayPicksTheHeaviestDay() {
        val days = listOf(
            day(1 * 3_600_000L, isToday = false),
            day(5 * 3_600_000L, isToday = false),
            day(2 * 3_600_000L, isToday = true),
        )
        assertEquals(5 * 3_600_000L, report(days).busiestDay?.totalMillis)
    }

    @Test
    fun busiestDayBreaksTiesTowardTheEarlierDay() {
        val earlier = DaySummary(label = "Mon", totalMillis = 3_600_000L, isToday = false)
        val later = DaySummary(label = "Tue", totalMillis = 3_600_000L, isToday = false)
        val r = report(listOf(earlier, later, day(0L, isToday = true)))
        assertEquals("Mon", r.busiestDay?.label)
    }

    @Test
    fun busiestDayIsNullWhenNothingRecorded() {
        val days = List(7) { day(0L, isToday = it == 6) }
        assertEquals(null, report(days).busiestDay)
    }

    @Test
    fun todayShareOfTypicalIsTodayOverTypicalDay() {
        // Typical completed day = 4h; today so far = 1h → 0.25.
        val days = List(3) { day(4 * 3_600_000L, isToday = false) } + day(1 * 3_600_000L, isToday = true)
        assertEquals(0.25f, report(days).todayShareOfTypical!!, 0.0001f)
    }

    @Test
    fun todayShareOfTypicalIsNullWithoutABaseline() {
        val r = report(listOf(day(3_600_000L, isToday = true)))
        assertEquals(null, r.todayShareOfTypical)
    }

    @Test
    fun shareOfTodayIsPackageTimeOverTodayTotal() {
        // 15m of a 60m day → 0.25, i.e. "25% of today".
        assertEquals(0.25f, shareOfToday(15 * 60_000L, 60 * 60_000L)!!, 0.0001f)
    }

    @Test
    fun shareOfTodayIsNullWhenTodayHasNoTotal() {
        // Same honesty rule as todayShareOfTypical: no baseline → no caption, never a fake 0%.
        assertEquals(null, shareOfToday(5 * 60_000L, 0L))
    }

    @Test
    fun shareOfTodayClampsBucketingNoiseToOne() {
        // Event bucketing can let one package nose past the day total; the caption caps at 100%.
        assertEquals(1f, shareOfToday(61 * 60_000L, 60 * 60_000L)!!, 0f)
    }

    @Test
    fun shareOfTodayFloorsNegativeMillisAtZero() {
        assertEquals(0f, shareOfToday(-1L, 60_000L)!!, 0f)
    }
}
