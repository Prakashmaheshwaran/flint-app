package com.flint.peakfocus.blocking.usagestats

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.overlay.PathBNotification
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.blocking.overlay.ProtectionStatus.PathBState
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.model.EnforcementSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [CountingUsageStats::class])
class UsageStatsForegroundServiceTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val pathA = Any()
    private lateinit var controller: ServiceController<UsageStatsForegroundService>
    private lateinit var service: UsageStatsForegroundService
    private val customizers = mutableListOf<PathBNotification.Customizer>()
    private var destroyed = false

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        CountingUsageStats.calls = 0
        CountingUsageStats.fail = false
        ProtectionStatusHub.publish(ProtectionStatus.UNKNOWN)
        ProtectionStatusHub.publishPathBHealth(PathBState.UNKNOWN)
        controller = Robolectric.buildService(UsageStatsForegroundService::class.java)
        service = controller.get()
        service.queryDispatcher = dispatcher
        grantUsage(true)
    }

    @After fun cleanup() {
        customizers.forEach(PathBNotification::unregister)
        EnforcementSignals.detectors.disconnected(pathA)
        if (!destroyed) controller.destroy()
        scheduler.runCurrent()
        ProtectionStatusHub.publish(ProtectionStatus.UNKNOWN)
        ProtectionStatusHub.publishPathBHealth(PathBState.UNKNOWN)
        Dispatchers.resetMain()
    }

    @Test fun `foreground starts before permission and usage observation and keeps the mandatory identity`() {
        customize { it.setContentTitle("Customized focus").setOngoing(false).setTimeoutAfter(1) }
        controller.create()
        val foreground = shadowOf(service)
        assertEquals(1001, foreground.lastForegroundNotificationId)
        val n = foreground.lastForegroundNotification
        assertEquals("Customized focus", n.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("flint_enforcement", n.channelId)
        assertEquals(0L, n.timeoutAfter)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        assertEquals(PathBState.UNKNOWN, health())
        assertEquals(0, CountingUsageStats.calls)
        startLoop()
        assertEquals(PathBState.UNKNOWN, health())
        scheduler.runCurrent()
        assertEquals(PathBState.ENFORCING, health())
        assertTrue(CountingUsageStats.calls > 0)
    }

    @Test fun `failed foreground start cannot advertise live enforcement`() {
        shadowOf(service).setThrowInStartForeground(SecurityException("denied"))
        controller.create()
        assertEquals(PathBState.UNKNOWN, health())
        assertTrue("foreground-start-failed" in ProtectionStatusHub.status.value.detectors.degradedReasons)
        assertEquals(Service.START_NOT_STICKY, startLoop())
        scheduler.runCurrent()
        assertEquals(0, CountingUsageStats.calls)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy(); destroyed = true
        assertEquals(PathBState.OFF, health())
    }

    @Test fun `actual A binding enters standby and unbind wakes enforcing without another service start`() {
        EnforcementSignals.detectors.connected(pathA)
        controller.create(); startLoop(); scheduler.runCurrent()
        assertEquals(PathBState.STANDBY, health())
        assertEquals(0, CountingUsageStats.calls)
        EnforcementSignals.detectors.disconnected(pathA)
        scheduler.runCurrent()
        assertEquals(PathBState.ENFORCING, health())
        assertTrue(CountingUsageStats.calls > 0)
        EnforcementSignals.detectors.connected(pathA)
        scheduler.runCurrent()
        assertEquals(PathBState.STANDBY, health())
        val calls = CountingUsageStats.calls
        PathBNotification.requestRefresh(); scheduler.runCurrent()
        assertEquals(calls, CountingUsageStats.calls)
    }

    @Test fun `end and stale display refreshes remove the chronometer without polling or renewing health`() {
        var fresh = true
        customize { b ->
            if (fresh) b.setContentTitle("Focus in progress").setWhen(60_000).setShowWhen(true).setUsesChronometer(true)
            else b.setContentTitle("Protection check needed")
        }
        controller.create(); startLoop(); scheduler.runCurrent()
        val calls = CountingUsageStats.calls
        ShadowSystemClock.advanceBy(Duration.ofMillis(10_000))
        fresh = false
        PathBNotification.requestRefresh(); scheduler.runCurrent()
        val n = (service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.single { it.id == 1001 }.notification
        assertEquals("Protection check needed", n.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(0L, n.timeoutAfter)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(calls, CountingUsageStats.calls)
        // A display update at +10s must not push the service's original +90s lease to +100s.
        // Policy publishers may independently shorten snapshot validity, so assert the live
        // service state itself at its lease boundary instead of comparing policy deadlines.
        ShadowSystemClock.advanceBy(Duration.ofMillis(80_000))
        assertEquals(PathBState.UNKNOWN, health())
    }

    @Test fun `permission loss removes foreground and clean stop clears service reasons`() {
        controller.create(); startLoop(); scheduler.runCurrent()
        assertEquals(PathBState.ENFORCING, health())
        grantUsage(false)
        startLoop(); scheduler.runCurrent()
        assertEquals(PathBState.OFF, health())
        assertFalse("usage-access-missing" in ProtectionStatusHub.status.value.detectors.degradedReasons)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `failed foreground observation is unknown and a later successful tick recovers`() {
        CountingUsageStats.fail = true
        controller.create(); startLoop(); scheduler.runCurrent()
        assertEquals(PathBState.UNKNOWN, health())
        assertTrue("foreground-observation-unavailable" in ProtectionStatusHub.status.value.detectors.degradedReasons)
        CountingUsageStats.fail = false
        scheduler.advanceTimeBy(1_000); scheduler.runCurrent()
        assertEquals(PathBState.ENFORCING, health())
        assertFalse("foreground-observation-unavailable" in ProtectionStatusHub.status.value.detectors.degradedReasons)
    }

    @Test fun `clean destroy reports off and detaches display refresh subscription`() {
        var customizations = 0
        customize { customizations++ }
        controller.create(); startLoop(); scheduler.runCurrent()
        controller.destroy(); destroyed = true
        assertEquals(PathBState.OFF, health())
        val before = customizations
        PathBNotification.requestRefresh(); scheduler.runCurrent()
        assertEquals(before, customizations)
    }

    @Test fun `stable enforcing ticks do not rebuild the foreground notification`() {
        var customizations = 0
        customize { customizations++ }
        controller.create(); startLoop(); scheduler.runCurrent()
        val before = customizations
        val queries = CountingUsageStats.calls
        repeat(10) { scheduler.advanceTimeBy(1_000); scheduler.runCurrent() }
        assertTrue(CountingUsageStats.calls >= queries + 10)
        assertEquals(before, customizations)
        PathBNotification.requestRefresh(); scheduler.runCurrent()
        assertEquals(before + 1, customizations)
    }

    @Test fun `debug timing distinguishes queries from handoff return without recording package identities`() {
        val usage = service.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        shadowOf(usage).addEvent("com.android.settings", System.currentTimeMillis() - 1,
            UsageEvents.Event.ACTIVITY_RESUMED)
        controller.create(); startLoop(); scheduler.runCurrent()
        val messages = ShadowLog.getLogsForTag("FlintFallbackTiming").map { it.msg }
        assertTrue(messages.any { it.startsWith("usage_events_query_ms=") })
        assertTrue(messages.any { it.startsWith("tick_to_handoff_ms=") })
        assertTrue(messages.none { it.contains("com.android.settings") || it.contains("shield_ms") })
    }

    private fun startLoop(): Int = service.onStartCommand(Intent(service, UsageStatsForegroundService::class.java), 0, 1)
    private fun health() = ProtectionStatusHub.status.value.detectors.pathB
    private fun grantUsage(allowed: Boolean) {
        val ops = service.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), service.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }
    private fun customize(block: (Notification.Builder) -> Unit) {
        val customizer = PathBNotification.Customizer { _, b -> block(b) }
        customizers += customizer
        PathBNotification.register(customizer)
    }
}

@Implements(UsageStatsManager::class)
class CountingUsageStats : ShadowUsageStatsManager() {
    @Implementation override fun queryEvents(beginTime: Long, endTime: Long): UsageEvents {
        calls++
        if (fail) throw IllegalStateException("usage binder unavailable")
        return super.queryEvents(beginTime, endTime)
    }
    companion object {
        var calls = 0
        var fail = false
    }
}
