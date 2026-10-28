package com.flint.peakfocus.blocking.overlay

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PathBNotificationTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val title = PathBNotification.Customizer { _, b -> b.setContentTitle("Focus until 15:00") }
    private val broken = PathBNotification.Customizer { _, _ -> error("customizer bug") }

    @After fun tearDown() {
        PathBNotification.unregister(title)
        PathBNotification.unregister(broken)
    }

    @Test fun `customizers apply to the builder and a throwing one is skipped`() {
        PathBNotification.register(broken)
        PathBNotification.register(title)
        PathBNotification.register(title) // idempotent
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(app).setContentTitle("Flint is keeping you focused")
        assertEquals(1, PathBNotification.apply(app, builder))
        assertEquals("Focus until 15:00", builder.build().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
