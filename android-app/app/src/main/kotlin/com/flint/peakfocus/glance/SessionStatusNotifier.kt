package com.flint.peakfocus.glance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flint.peakfocus.R

/** One app-owned status surface on Path A. F alone owns completion/end reminders. */
class SessionStatusNotifier(
    context: Context,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val wallNow: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private data class Posted(val session: GlanceOngoingSession, val title: String, val text: String,
                              val healthNotes: List<String>, val deadline: Long)
    private var posted: Posted? = null
    private var needsInitialCleanup = true

    @Synchronized fun update(display: GlanceDisplay) {
        val now = elapsedNow()
        val session = display.session
        val freshUntil = display.refreshAtElapsedMs
        val eligible = display.detector == GlanceDetector.PATH_A_CONFIRMED && session != null &&
            freshUntil != null && freshUntil > now &&
            (session.logicalEndElapsedMs == null || session.logicalEndElapsedMs > now)
        if (!eligible || !permissionGranted()) {
            cancel()
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Focus in progress",
                NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                cancel()
                return
            }
        }
        val until = minOf(freshUntil!!, session!!.logicalEndElapsedMs ?: freshUntil)
        val previous = posted
        if (previous?.session == session && previous.title == display.status && previous.text == display.detail &&
            previous.healthNotes == display.healthNotes &&
            previous.deadline <= until && previous.deadline > now + 15_000) return
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(app)
        builder.setSmallIcon(R.drawable.ic_flint_monochrome).setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setColor(ContextCompat.getColor(app, R.color.flint_spark))
        // API 24/25 have no system notification timeout. Keep an absolute planned end there;
        // the local inexact refresh remains best-effort if the app dies. API 26+ bounds the
        // system countdown by both logical expiry and snapshot freshness, even host-dead.
        val content = if (Build.VERSION.SDK_INT >= 26) display else display.copy(countdownEndElapsedMs = null)
        OngoingSessionNotification.customize(app, builder, content, now, wallNow())
        if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(until - now)
        try {
            manager.notify(TAG, NOTIFICATION_ID, builder.build())
            posted = Posted(session, display.status, display.detail, display.healthNotes, until)
            needsInitialCleanup = false
        } catch (_: SecurityException) {
            // Revocation can race the permission check; enforcement never waits on this.
            cancel()
        }
    }

    private fun permissionGranted(): Boolean = NotificationManagerCompat.from(app).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED)

    private fun cancel() {
        if (posted != null || needsInitialCleanup) {
            manager.cancel(TAG, NOTIFICATION_ID) // Never touches Path B1001 or F's tagged completion1.
        }
        posted = null
        needsInitialCleanup = false
    }

    companion object {
        const val CHANNEL_ID = "flint_ongoing_session"
        const val TAG = "flint.ongoing.session"
        const val NOTIFICATION_ID = 1002
    }
}
