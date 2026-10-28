package com.flint.peakfocus.glance

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GlanceSurfaceDeliveryTest {
    @Test fun liveHeartbeatsCoalesceWritesAndVisibleSafetyChangesRefreshImmediately() = runTest {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        var now = 100_000L
        var renders = 0
        var schedules = 0
        var posts = 0
        val notifier = SessionStatusNotifier(app, { now }, { 1_800_000_000_000 })
        var lastNotification: android.app.Notification? = null
        val delivery = GlanceSurfaceDelivery(
            notify = { state ->
                notifier.update(state)
                val content = shadowOf(manager).allNotifications.singleOrNull()
                if (content != null && content !== lastNotification) posts++
                lastNotification = content
            },
            schedule = { schedules++ }, render = { renders++ }, nowElapsed = { now },
        )
        val active = GlanceDisplay("Protection active at last check", "Focus · Harder · Until 20:00",
            "No upcoming session", "5m", null, "Checked 19:55", 700_000, 160_000,
            detector = GlanceDetector.PATH_A_CONFIRMED, session = GlanceOngoingSession("fixture", 700_000))
        repeat(120) { second ->
            now = 100_000L + second * 1_000
            delivery.publish(active.copy(refreshAtElapsedMs = now + 60_000))
        }
        assertEquals(1, renders) // No visible change despite 120 coordinator checks.
        assertEquals(2, schedules) // Retain earlier freshness alarms; do not move them each second.
        assertTrue("Quiet timeout renewal must stay bounded", posts in 2..3)
        val degraded = active.copy(status = "Protection degraded", detail = "Open Flint to review coverage",
            countdownEndElapsedMs = null, session = null, refreshAtElapsedMs = now + 60_000)
        delivery.publish(degraded)
        assertEquals(2, renders)
        assertTrue(manager.activeNotifications.isEmpty())
        delivery.publish(degraded.copy(status = "Emergency Pass recovery"))
        assertEquals(3, renders)
        delivery.publish(degraded.copy(status = "Planned end reached"))
        assertEquals(4, renders)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
