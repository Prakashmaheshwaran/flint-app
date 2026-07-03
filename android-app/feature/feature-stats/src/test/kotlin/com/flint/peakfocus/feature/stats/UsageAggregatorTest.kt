package com.flint.peakfocus.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAggregatorTest {

    // Three synthetic 1000ms "days": [0,1000) [1000,2000) [2000,3000).
    private val windows = listOf(
        DayWindow(0L, 1_000L),
        DayWindow(1_000L, 2_000L),
        DayWindow(2_000L, 3_000L),
    )

    private fun resumed(pkg: String, at: Long) =
        ForegroundEvent(pkg, at, ForegroundTransition.RESUMED)

    private fun paused(pkg: String, at: Long) =
        ForegroundEvent(pkg, at, ForegroundTransition.PAUSED)

    @Test
    fun closedIntervalWithinOneDay() {
        val b = UsageAggregator.bucketIntoDays(
            listOf(resumed("a", 100), paused("a", 400)), windows, nowMillis = 3_000,
        )
        assertEquals(300L, b.dayTotalMillis(0))
        assertEquals(0L, b.dayTotalMillis(1))
        assertEquals(0L, b.dayTotalMillis(2))
        assertEquals(300L, b.rangeTotalMillis())
    }

    @Test
    fun intervalSpanningMidnightIsSplitAcrossBothDays() {
        val b = UsageAggregator.bucketIntoDays(
            listOf(resumed("a", 800), paused("a", 1_300)), windows, nowMillis = 3_000,
        )
        assertEquals(200L, b.dayTotalMillis(0))
        assertEquals(300L, b.dayTotalMillis(1))
    }

    @Test
    fun resumeOfAnotherAppImplicitlyClosesTheCurrentOne() {
        val events = listOf(resumed("a", 100), resumed("b", 400), paused("b", 600))
        val b = UsageAggregator.bucketIntoDays(events, windows, nowMillis = 3_000)
        assertEquals(mapOf("a" to 300L, "b" to 200L), b.appTotalsForDay(0))
    }

    @Test
    fun stillOpenIntervalIsClosedAtNow() {
        val b = UsageAggregator.bucketIntoDays(listOf(resumed("a", 2_500)), windows, nowMillis = 2_800)
        assertEquals(300L, b.dayTotalMillis(2))
    }

    @Test
    fun openIntervalIsClampedToRangeEndWhenNowLiesBeyondIt() {
        val b = UsageAggregator.bucketIntoDays(listOf(resumed("a", 2_500)), windows, nowMillis = 9_999)
        assertEquals(500L, b.dayTotalMillis(2))
    }

    @Test
    fun pausedWithoutMatchingResumeIsIgnored() {
        val b = UsageAggregator.bucketIntoDays(listOf(paused("a", 500)), windows, nowMillis = 3_000)
        assertEquals(0L, b.rangeTotalMillis())
        assertTrue(b.appTotalsForRange().isEmpty())
    }

    @Test
    fun duplicateResumeKeepsTheOriginalStart() {
        val events = listOf(resumed("a", 100), resumed("a", 300), paused("a", 500))
        val b = UsageAggregator.bucketIntoDays(events, windows, nowMillis = 3_000)
        assertEquals(400L, b.dayTotalMillis(0))
    }

    @Test
    fun unsortedEventsAreSortedBeforeAggregation() {
        val events = listOf(paused("a", 400), resumed("a", 100))
        val b = UsageAggregator.bucketIntoDays(events, windows, nowMillis = 3_000)
        assertEquals(300L, b.dayTotalMillis(0))
    }

    @Test
    fun intervalStartingBeforeTheRangeIsClampedToRangeStart() {
        val b = UsageAggregator.bucketIntoDays(
            listOf(resumed("a", -500), paused("a", 200)), windows, nowMillis = 3_000,
        )
        assertEquals(200L, b.dayTotalMillis(0))
    }

    @Test
    fun perAppTotalsSumAcrossDays() {
        val events = listOf(
            resumed("a", 800), paused("a", 1_200),   // a: 200 on day0 + 200 on day1
            resumed("b", 2_100), paused("b", 2_200), // b: 100 on day2
        )
        val b = UsageAggregator.bucketIntoDays(events, windows, nowMillis = 3_000)
        assertEquals(mapOf("a" to 400L, "b" to 100L), b.appTotalsForRange())
        assertEquals(500L, b.rangeTotalMillis())
    }

    @Test
    fun interleavedAppsAccumulateSeparately() {
        val events = listOf(
            resumed("a", 0), paused("a", 100),
            resumed("b", 100), paused("b", 250),
            resumed("a", 250), paused("a", 300),
        )
        val b = UsageAggregator.bucketIntoDays(events, windows, nowMillis = 3_000)
        assertEquals(mapOf("a" to 150L, "b" to 150L), b.appTotalsForDay(0))
    }

    @Test
    fun topAppsSortsDescendingAndDropsZeros() {
        val top = UsageAggregator.topApps(mapOf("a" to 100L, "b" to 300L, "c" to 0L), limit = 5)
        assertEquals(listOf("b" to 300L, "a" to 100L), top)
    }

    @Test
    fun topAppsRespectsLimitAndBreaksTiesAlphabetically() {
        val top = UsageAggregator.topApps(mapOf("z" to 100L, "a" to 100L, "m" to 200L), limit = 2)
        assertEquals(listOf("m" to 200L, "a" to 100L), top)
    }

    @Test
    fun emptyEventsYieldAllZeroDays() {
        val b = UsageAggregator.bucketIntoDays(emptyList(), windows, nowMillis = 3_000)
        assertEquals(0L, b.rangeTotalMillis())
        windows.indices.forEach { assertEquals(0L, b.dayTotalMillis(it)) }
    }
}
