package com.flint.peakfocus.feature.stats

import org.junit.Assert.assertEquals
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
}
