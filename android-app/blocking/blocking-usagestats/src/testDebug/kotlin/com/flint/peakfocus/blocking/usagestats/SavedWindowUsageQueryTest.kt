package com.flint.peakfocus.blocking.usagestats

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.data.UsageQuery
import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsageStatsManager

/** Public Android adapter evidence only; persisted period/high-water authority is tested separately. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 34], shadows = [SavedWindowUsageStats::class])
class SavedWindowUsageQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val now = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private val pkg = "com.example.limited"
    private val originalZone = TimeZone.getDefault()

    @Before fun before() {
        SavedWindowUsageStats.aggregate = emptyList()
        SavedWindowUsageStats.windows.clear()
        SavedWindowUsageStats.failEvents = false
        SavedWindowUsageStats.failAggregate = false
        UsageQuery.invalidate()
    }

    @After fun after() { TimeZone.setDefault(originalZone); UsageQuery.invalidate() }

    @Test fun PC_56_explicitSavedWindowDoesNotFollowDeviceTravel() {
        val start = now - 23 * 3_600_000L
        SavedWindowUsageStats.aggregate = listOf(bucket(60_000))
        for (zone in listOf("UTC", "Pacific/Auckland", "America/New_York")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertEquals(60_000L, UsageQuery.foregroundMillisForWindow(manager, pkg, start, now))
        }
        assertEquals(listOf(start to now, start to now, start to now), SavedWindowUsageStats.windows)
    }

    @Test fun PC_54_explicitWindowClipsEventsWithoutImportingAnotherWindowsVolatileFloor() {
        shadowOf(manager).addEvent(pkg, now - 6_000, UsageEvents.Event.ACTIVITY_RESUMED)
        shadowOf(manager).addEvent(pkg, now - 1_000, UsageEvents.Event.ACTIVITY_PAUSED)
        assertEquals(5_000L, UsageQuery.foregroundMillisForWindow(manager, pkg, now - 10_000, now))
        assertEquals(2_000L, UsageQuery.foregroundMillisForWindow(manager, pkg, now - 3_000, now))
    }

    @Test fun PC_61_unavailableSourcesStayDistinctFromMeasuredZeroAndRecover() {
        SavedWindowUsageStats.failEvents = true
        SavedWindowUsageStats.aggregate = listOf(bucket(12_000))
        assertEquals(12_000L, UsageQuery.foregroundMillisForWindow(manager, pkg, now - 60_000, now))
        SavedWindowUsageStats.failAggregate = true
        assertEquals(Long.MAX_VALUE, UsageQuery.foregroundMillisForWindow(manager, pkg, now - 60_000, now))
        SavedWindowUsageStats.failEvents = false
        SavedWindowUsageStats.failAggregate = false
        SavedWindowUsageStats.aggregate = emptyList()
        assertEquals(0L, UsageQuery.foregroundMillisForWindow(manager, pkg, now - 60_000, now))
    }

    @Test fun PC_61_PC_77_onlyConfirmedUsageAccessPermitsMeasurementAndRevocationSkipsQueries() {
        grant(false)
        assertNull(UsageQuery.foregroundMillisForWindowIfGranted(context, pkg, now - 60_000, now))
        assertTrue(SavedWindowUsageStats.windows.isEmpty())
        grant(true)
        SavedWindowUsageStats.aggregate = listOf(bucket(500))
        assertEquals(500L, UsageQuery.foregroundMillisForWindowIfGranted(context, pkg, now - 60_000, now))
        SavedWindowUsageStats.failEvents = true
        SavedWindowUsageStats.failAggregate = true
        assertEquals(Long.MAX_VALUE, UsageQuery.foregroundMillisForWindowIfGranted(context, pkg, now - 60_000, now))
        val count = SavedWindowUsageStats.windows.size
        grant(false)
        assertNull(UsageQuery.foregroundMillisForWindowIfGranted(context, pkg, now - 60_000, now))
        assertEquals(count, SavedWindowUsageStats.windows.size)
    }

    @Test fun PC_71_oversizedAggregateCannotOverflowIntoAnUnusedBudget() {
        SavedWindowUsageStats.aggregate = listOf(bucket(Long.MAX_VALUE - 1), bucket(2))
        assertEquals(Long.MAX_VALUE, UsageQuery.foregroundMillisForWindow(manager, pkg, now - 60_000, now))
    }

    private fun bucket(used: Long): UsageStats = ShadowUsageStatsManager.UsageStatsBuilder.newBuilder()
        .setPackageName(pkg).setTotalTimeInForeground(used).build()

    private fun grant(allowed: Boolean) {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
        UsageQuery.invalidate()
    }
}

@Implements(UsageStatsManager::class)
class SavedWindowUsageStats : ShadowUsageStatsManager() {
    @Implementation override fun queryEvents(beginTime: Long, endTime: Long): UsageEvents {
        if (failEvents) error("events unavailable")
        return super.queryEvents(beginTime, endTime)
    }

    @Implementation override fun queryUsageStats(intervalType: Int, beginTime: Long, endTime: Long): MutableList<UsageStats> {
        windows += beginTime to endTime
        if (failAggregate) error("aggregate unavailable")
        return aggregate.toMutableList()
    }

    companion object {
        var aggregate: List<UsageStats> = emptyList()
        val windows = mutableListOf<Pair<Long, Long>>()
        var failEvents = false
        var failAggregate = false
    }
}
