package com.flint.peakfocus.glance

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.flint.peakfocus.MainActivity

data class OngoingNotificationPlan(
    val title: String,
    val text: String,
    val countdownEndElapsedMs: Long?,
) {
    companion object {
        fun from(display: GlanceDisplay, nowElapsedMs: Long): OngoingNotificationPlan {
            val fresh = display.refreshAtElapsedMs?.let { it > nowElapsedMs } == true
            if (!fresh) return OngoingNotificationPlan(
                "Flint · Protection check needed", "Open Flint to check protection", null,
            )
            val end = display.countdownEndElapsedMs?.takeIf { it > nowElapsedMs }
            if (display.countdownEndElapsedMs != null && end == null) return OngoingNotificationPlan(
                "Flint · Planned end reached", "Open Flint to check protection", null,
            )
            return OngoingNotificationPlan("Flint · ${display.status}", display.detail, end)
        }
    }
}

/**
 * Content customizer for C's EXISTING Path B notification. Never posts a notification,
 * requests permission, creates a service, changes enforcement, or schedules a completion.
 * C retains its channel flint_enforcement / id 1001; F alone owns focus_sessions / id 1.
 */
object OngoingSessionNotification {
    fun customize(context: Context, builder: Notification.Builder) {
        val elapsed = SystemClock.elapsedRealtime()
        val plan = OngoingNotificationPlan.from(GlanceSurfaceBridge.display.value, elapsed)
        val open = PendingIntent.getActivity(context, 1001,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.setContentTitle(plan.title).setContentText(plan.text)
            .setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(false)
            .setUsesChronometer(false).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setSubText("Planned end · Open Flint to check")
        if (Build.VERSION.SDK_INT >= 24) {
            builder.setChronometerCountDown(false)
            plan.countdownEndElapsedMs?.let { end ->
                // Android converts this wall estimate to the system chronometer's elapsed base.
                // The source deadline remains authoritative elapsedRealtime, including clock jumps.
                builder.setWhen(System.currentTimeMillis() + (end - elapsed))
                    .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            }
        } // API 23 displays the absolute end from the mapper, never an upwards timer.
        // This is C's mandatory FGS notification: never time it out/cancel it at a session
        // deadline. C re-invokes this customizer at boundaries, removing the countdown while
        // preserving notification visibility for the service and independent causes.
    }
}
