package com.flint.peakfocus.glance

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SessionStatusNotifierTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var now = 100_000L
    private lateinit var notifier: SessionStatusNotifier
    private val active = GlanceDisplay("Protection active at last check", "Focus · Hardcore · Until 20:00",
        "No upcoming session", "0m", null, "Checked 19:55", 400_000, 160_000,
        detector = GlanceDetector.PATH_A_CONFIRMED, session = GlanceOngoingSession("fixture-session", 400_000))

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        notifier = SessionStatusNotifier(context, { now }, { 1_800_000_000_000 })
    }

    @Test fun confirmedPathAPostsOneQuietStatusWithSystemExpiryAndOpenOnlyAction() {
        notifier.update(active)
        val posted = manager.activeNotifications.single()
        assertEquals(SessionStatusNotifier.TAG, posted.tag)
        assertEquals(1002, posted.id)
        assertEquals("flint_ongoing_session", posted.notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(posted.notification.channelId).importance)
        assertEquals(60_000L, posted.notification.timeoutAfter) // Source freshness precedes logical end.
        assertTrue(posted.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(posted.notification.actions.isNullOrEmpty())
        assertNotNull(posted.notification.contentIntent)
    }

    @Test fun denialAndRevocationPostNothingAndCancelOnlyOwnStatus() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.update(active)
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.update(active)
        assertEquals(1, manager.activeNotifications.size)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.update(active)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun repeatedReadDoesNotRepostAndFreshnessIsRenewedOnlyNearSystemExpiry() {
        notifier.update(active)
        val original = shadowOf(manager).allNotifications.single()
        notifier.update(active.copy(refreshAtElapsedMs = 170_000))
        assertSame(original, shadowOf(manager).allNotifications.single())
        now = 150_000
        notifier.update(active.copy(refreshAtElapsedMs = 210_000))
        assertNotSame(original, shadowOf(manager).allNotifications.single())
        assertEquals(60_000L, manager.activeNotifications.single().notification.timeoutAfter)
    }

    @Test fun pathBAndUnknownSuppressPathAWhilePreservingFgsAndFCompletion() {
        val other = Notification.Builder(context, "other").setContentTitle("Fixture").build()
        manager.notify("completed-session", 1, other)
        notifier.update(active)
        manager.notify(1001, other) // Simulate C confirming foreground notification delivery.
        notifier.update(active.copy(detector = GlanceDetector.PATH_B_FOREGROUND))
        assertEquals(setOf(1, 1001), manager.activeNotifications.map { it.id }.toSet())
        notifier.update(active.copy(detector = GlanceDetector.UNKNOWN))
        assertEquals(setOf(1, 1001), manager.activeNotifications.map { it.id }.toSet())
        manager.cancel(1001) // C has positively stopped its FGS before publishing live Path A.
        notifier.update(active)
        assertEquals(setOf(1, 1002), manager.activeNotifications.map { it.id }.toSet())
    }

    @Test fun staleExactEndLateEndAndNonSessionStatesCancelOngoingWithoutAlert() {
        for (ended in listOf(
            active.copy(refreshAtElapsedMs = now),
            active.copy(session = GlanceOngoingSession("fixture-session", now)),
            active.copy(session = GlanceOngoingSession("fixture-session", now - 1)),
            active.copy(session = null),
        )) {
            notifier.update(active)
            notifier.update(ended)
            assertTrue(manager.activeNotifications.isEmpty())
        }
    }

    @Test fun blockedStatusChannelRemainsBlockedWithoutRequiredPermissionFlow() {
        manager.createNotificationChannel(NotificationChannel(SessionStatusNotifier.CHANNEL_ID,
            "Focus in progress", NotificationManager.IMPORTANCE_NONE))
        notifier.update(active)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
