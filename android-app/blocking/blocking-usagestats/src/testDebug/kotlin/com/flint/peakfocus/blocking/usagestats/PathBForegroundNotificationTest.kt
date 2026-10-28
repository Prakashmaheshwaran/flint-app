package com.flint.peakfocus.blocking.usagestats

import android.app.Notification
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.overlay.PathBNotification
import com.flint.peakfocus.core.model.FallbackMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 34])
class PathBForegroundNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val customizers = mutableListOf<PathBNotification.Customizer>()
    private val starts = mutableListOf<Notification>()
    private val refreshes = mutableListOf<Notification>()
    private val notifications = PathBForegroundNotification(context, { starts += it }, { refreshes += it })

    @After fun cleanup() { customizers.forEach(PathBNotification::unregister) }
    private fun customize(block: (Notification.Builder) -> Unit) {
        val customizer = PathBNotification.Customizer { _, builder -> block(builder) }
        customizers += customizer; PathBNotification.register(customizer)
    }

    @Test fun `initial and changed content use one foreground notification`() {
        var title = "Session A"
        customize { it.setContentTitle(title) }
        notifications.refresh(FallbackMode.ENFORCING)
        assertEquals("Session A", starts.single().extras.getCharSequence(Notification.EXTRA_TITLE))
        repeat(120) { notifications.refresh(FallbackMode.ENFORCING) }
        assertTrue(refreshes.isEmpty())
        title = "Session B"
        notifications.refresh(FallbackMode.ENFORCING)
        assertEquals(1, starts.size)
        assertEquals("Session B", refreshes.single().extras.getCharSequence(Notification.EXTRA_TITLE))
    }

    @Test fun `customizer cannot remove mandatory foreground attributes`() {
        customize {
            it.setOngoing(false).setAutoCancel(true).setSmallIcon(0)
                .setContentTitle(" ").setContentText("")
            if (Build.VERSION.SDK_INT >= 26) it.setChannelId("unrelated").setTimeoutAfter(1)
        }
        notifications.refresh(FallbackMode.STANDBY)
        val n = starts.single()
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
        assertNotNull(n.smallIcon)
        assertFalse(n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank())
        assertEquals("Backup ready while Accessibility is connected.", n.extras.getCharSequence(Notification.EXTRA_TEXT))
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals("flint_enforcement", n.channelId)
            assertEquals(0L, n.timeoutAfter)
        }
    }

    @Test fun `throwing customizer cannot erase fallback copy`() {
        customize { it.setContentTitle(null).setContentText(null); error("broken customizer") }
        notifications.refresh(null)
        assertFalse(starts.single().extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank())
        assertEquals("Checking protection availability.", starts.single().extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun `end or stale refresh removes old chronometer without timing out the service`() {
        var fresh = true
        customize { builder ->
            if (fresh) builder.setWhen(60_000).setShowWhen(true).setUsesChronometer(true)
            else builder.setContentTitle("Protection check needed")
        }
        notifications.refresh(FallbackMode.ENFORCING)
        assertTrue(starts.single().extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        fresh = false
        notifications.refresh(FallbackMode.ENFORCING)
        assertFalse(refreshes.single().extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(refreshes.single().flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 26) assertEquals(0L, refreshes.single().timeoutAfter)
    }

    @Test fun `chronometer jitter coalesces against last posted content not last attempted content`() {
        var end = 60_000L
        customize { it.setWhen(end).setShowWhen(true).setUsesChronometer(true) }
        notifications.refresh(FallbackMode.ENFORCING)
        repeat(9) { end += 100; notifications.refresh(FallbackMode.ENFORCING) }
        assertTrue(refreshes.isEmpty())
        end += 100
        notifications.refresh(FallbackMode.ENFORCING)
        assertEquals(61_000L, refreshes.single().`when`)
    }

    @Test fun `permission return forces repost without another foreground start`() {
        notifications.refresh(FallbackMode.STANDBY)
        notifications.refresh(FallbackMode.STANDBY, force = true)
        assertEquals(1, starts.size)
        assertEquals(1, refreshes.size)
    }

    @Test fun `failed foreground post is never cached as successful`() {
        var calls = 0
        val controller = PathBForegroundNotification(context, {
            calls++
            if (calls == 1) throw SecurityException("foreground denied")
        }, { fail("Should retry initial foreground publication") })
        try { controller.refresh(null); fail("Expected startup failure") } catch (_: SecurityException) { }
        controller.refresh(null)
        assertEquals(2, calls)
    }
}
