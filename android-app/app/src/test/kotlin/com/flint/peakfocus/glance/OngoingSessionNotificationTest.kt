package com.flint.peakfocus.glance

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import com.flint.peakfocus.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OngoingSessionNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val active = GlanceDisplay("Protection active at last check", "Focus · Hardcore · Until 20:00",
        "No upcoming session", "0m", null, "Checked 19:55", 400_000, 160_000)

    private fun notification(display: GlanceDisplay = active, now: Long = 100_000): Notification {
        val builder = Notification.Builder(context, "flint_enforcement").setOngoing(true)
        OngoingSessionNotification.customize(context, builder, display, now, 1_800_000_000_000)
        return builder.build()
    }

    @Test fun systemCountdownUsesLogicalEndAndPreservesExistingFgsContract() {
        val result = notification()
        assertTrue(result.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(result.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(1_800_000_300_000, result.`when`)
        assertEquals("flint_enforcement", result.channelId)
        assertTrue(result.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(result.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(0L, result.timeoutAfter) // Never hide C's compulsory FGS notification.
    }

    @Test fun onlyIntentOpensFlintWithoutAWeakeningActionOrPayload() {
        val result = notification()
        assertTrue(result.actions.isNullOrEmpty())
        val intent = shadowOf(result.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.action)
        assertNull(intent.data)
        assertNull(intent.extras)
        assertTrue(shadowOf(result.contentIntent).isImmutable)
    }

    @Test fun exactLateStaleAndNonActiveRebuildsClearSystemChronometer() {
        listOf(
            active.copy(refreshAtElapsedMs = 500_000) to 400_000L,
            active.copy(refreshAtElapsedMs = 500_000) to 450_000L,
            active to 160_000L,
            active.copy(countdownEndElapsedMs = null) to 100_000L,
        ).forEach { (display, now) ->
            val result = notification(display, now)
            assertFalse(result.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertFalse(result.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertEquals(0L, result.timeoutAfter)
            assertTrue(result.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Open Flint to check", result.extras.getString(Notification.EXTRA_SUB_TEXT))
        }
    }

    @Test fun deniedPostNotificationsDoesNotPreventContentOrPostAnotherAlert() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertNotNull(notification().contentIntent)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
