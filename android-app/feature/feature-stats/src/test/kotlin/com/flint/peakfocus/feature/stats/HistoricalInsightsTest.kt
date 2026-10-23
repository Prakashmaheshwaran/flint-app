package com.flint.peakfocus.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class HistoricalInsightsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val firstDay = Calendar.getInstance(utc).apply {
        clear()
        set(2026, Calendar.OCTOBER, 9)
    }.timeInMillis
    private val dayMillis = 86_400_000L
    private val windows = List(3) { index ->
        DayWindow(firstDay + index * dayMillis, firstDay + (index + 1) * dayMillis)
    }

    private fun report(
        totals: Map<String, LongArray> = mapOf(
            "a" to longArrayOf(600, 0, 100),
            "b" to longArrayOf(200, 400, 300),
        ),
        reportWindows: List<DayWindow> = windows,
    ) = buildUsageReport(
        DayBuckets(reportWindows, totals),
        appLabel = { if (it == "a") "Alpha" else null },
        locale = Locale.US,
        timeZone = utc,
    )

    @Test
    fun selectingADayChangesBothRankingAndDenominator() {
        val report = report()
        val today = report.appInsights(UsagePeriod.Today)
        val friday = report.appInsights(UsagePeriod.Day(firstDay))
        assertEquals(listOf("b", "a"), today.topApps.map { it.packageName })
        assertEquals(400L, today.totalMillis)
        assertEquals(listOf("a", "b"), friday.topApps.map { it.packageName })
        assertEquals(800L, friday.totalMillis)
        assertEquals(0.75f, shareOfPeriod(friday.topApps.first().foregroundMillis, friday.totalMillis)!!, 0f)
        assertEquals("Alpha", friday.topApps.first().label)
        assertNull(friday.topApps.last().label) // an uninstalled app still has its package fallback
    }

    @Test
    fun weekRanksSumAcrossEveryDayAndUsesWholeWeekTotal() {
        val insights = report().appInsights(UsagePeriod.Week)
        assertEquals("Last 7 days", insights.label)
        assertEquals(listOf("b", "a"), insights.topApps.map { it.packageName })
        assertEquals(listOf(900L, 700L), insights.topApps.map { it.foregroundMillis })
        assertEquals(1_600L, insights.totalMillis)
        assertEquals(0.5625f, shareOfPeriod(900L, insights.totalMillis)!!, 0f)
    }

    @Test
    fun topEightDoNotChangeThePercentageDenominator() {
        val totals = (0..8).associate { "app$it" to longArrayOf(100, 0, 0) }
        val insights = report(totals).appInsights(UsagePeriod.Day(firstDay))
        assertEquals(8, insights.topApps.size)
        assertEquals(900L, insights.totalMillis)
        assertEquals(100f / 900f, shareOfPeriod(100, insights.totalMillis)!!, 0f)
    }

    @Test
    fun weeklyRankingIncludesAppsMissingFromEveryDailyTopEight() {
        val totals = mutableMapOf("steady" to longArrayOf(90, 90, 90))
        repeat(3) { day ->
            repeat(8) { app ->
                totals["day${day}app$app"] = LongArray(3).apply { this[day] = 100 }
            }
        }
        val report = report(totals)
        assertTrue(report.days.all { day -> day.topApps.none { it.packageName == "steady" } })
        assertEquals("steady", report.topAppsWeek.first().packageName)
        assertEquals(270L, report.topAppsWeek.first().foregroundMillis)
    }

    @Test
    fun selectedDateSurvivesUpdatedUsageAndMovingWindow() {
        val selection = UsagePeriod.Day(firstDay + dayMillis)
        val refreshed = report(mapOf("a" to longArrayOf(0, 800, 50)))
        assertEquals(selection, refreshed.resolvePeriod(selection))
        assertEquals(800L, refreshed.appInsights(selection).totalMillis)
        val tomorrow = report(
            mapOf("a" to longArrayOf(800, 50, 10)),
            windows.map { DayWindow(it.startMillis + dayMillis, it.endMillis + dayMillis) },
        )
        assertEquals(selection, tomorrow.resolvePeriod(selection))
        assertEquals(800L, tomorrow.appInsights(selection).totalMillis)
    }

    @Test
    fun aDateThatFallsOutOfTheWindowReturnsToToday() {
        val report = report()
        val unavailable = UsagePeriod.Day(firstDay - dayMillis)
        assertEquals(UsagePeriod.Today, report.resolvePeriod(unavailable))
        assertEquals(report.appInsights(UsagePeriod.Today), report.appInsights(unavailable))
    }

    @Test
    fun todayAndWeekRemainRelativeAfterRollover() {
        val tomorrow = report(
            mapOf("a" to longArrayOf(800, 50, 10)),
            windows.map { DayWindow(it.startMillis + dayMillis, it.endMillis + dayMillis) },
        )
        assertEquals(UsagePeriod.Today, tomorrow.resolvePeriod(UsagePeriod.Today))
        assertEquals(10L, tomorrow.appInsights(UsagePeriod.Today).totalMillis)
        assertEquals(UsagePeriod.Week, tomorrow.resolvePeriod(UsagePeriod.Week))
        assertEquals(860L, tomorrow.appInsights(UsagePeriod.Week).totalMillis)
    }

    @Test
    fun datesAreActualDatesAndDoNotUseWeekdayAsIdentity() {
        val report = report()
        assertEquals("Fri", report.days.first().label)
        assertEquals("Friday, October 9, 2026", report.days.first().dateLabel)
        assertEquals(firstDay, report.days.first().startMillis)
        assertEquals("Friday, October 9, 2026", report.appInsights(UsagePeriod.Day(firstDay)).label)
    }

    @Test
    fun emptyHistoryHasNoRankingsAndNoFabricatedPercentages() {
        val report = report(emptyMap())
        listOf(UsagePeriod.Today, UsagePeriod.Week, UsagePeriod.Day(firstDay)).forEach { period ->
            val insights = report.appInsights(period)
            assertTrue(insights.topApps.isEmpty())
            assertEquals(0L, insights.totalMillis)
            assertNull(shareOfPeriod(0L, insights.totalMillis))
        }
    }

    @Test
    fun crossMidnightUsageFeedsBothDayRankingsAndOneWeekTotal() {
        val midnight = windows[1].startMillis
        val buckets = UsageAggregator.bucketIntoDays(
            listOf(
                ForegroundEvent("a", midnight - 100, ForegroundTransition.RESUMED),
                ForegroundEvent("a", midnight + 200, ForegroundTransition.PAUSED),
            ),
            windows,
            windows.last().endMillis,
        )
        val report = buildUsageReport(buckets, { null }, Locale.US, utc)
        assertEquals(100L, report.appInsights(UsagePeriod.Day(firstDay)).topApps.single().foregroundMillis)
        assertEquals(200L, report.appInsights(UsagePeriod.Day(midnight)).topApps.single().foregroundMillis)
        assertEquals(300L, report.appInsights(UsagePeriod.Week).topApps.single().foregroundMillis)
    }
}
