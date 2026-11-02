package com.flint.peakfocus.blocking.usagestats

import android.app.Notification
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.flint.peakfocus.blocking.overlay.PathBNotification
import com.flint.peakfocus.core.model.FallbackMode

/** One mandatory notification; display refreshes never restart enforcement or renew health. */
internal class PathBForegroundNotification(
    private val context: Context,
    private val startForeground: (Notification) -> Unit,
    private val notify: (Notification) -> Unit,
) {
    private var started = false
    private var lastContent: Content? = null

    /** Startup obligation: no customizer, PendingIntent, preference, color or package lookup. */
    fun startMinimal() {
        if (started) return
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setContentTitle("Flint focus protection").setContentText("Checking protection availability.")
            .setSmallIcon(R.drawable.ic_stat_flint).setWhen(0).setShowWhen(false)
            .setUsesChronometer(false).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(0)
        val notification = builder.build()
        startForeground(notification)
        started = true
        lastContent = Content.from(notification)
    }

    fun refresh(mode: FallbackMode?, force: Boolean = false) {
        val title = "Flint focus protection"
        val text = when (mode) {
            FallbackMode.STANDBY -> "Backup ready while Accessibility is connected."
            FallbackMode.ENFORCING -> "Checking active blocks with Usage Access."
            FallbackMode.OFF -> "Usage Access is unavailable."
            null -> "Checking protection availability."
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setContentTitle(title).setContentText(text)
            .setWhen(0).setShowWhen(false).setUsesChronometer(false)
            .setOnlyAlertOnce(true)
        PathBNotification.apply(context, builder)
        val customized = builder.build()
        if (customized.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank()) builder.setContentTitle(title)
        if (customized.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()) builder.setContentText(text)
        // A content customizer cannot replace the mandatory service channel, icon or visibility
        // lifetime, even if it throws after partially mutating the builder.
        builder.setSmallIcon(R.drawable.ic_stat_flint)
            .setColor(ContextCompat.getColor(context, R.color.flint_spark))
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(CHANNEL_ID).setTimeoutAfter(0)
        val notification = builder.build()
        val content = Content.from(notification)
        if (!started) {
            startForeground(notification) // Caller reasserts specialUse on API34+.
            started = true
            lastContent = content
        } else if (force || !content.sameDisplay(lastContent)) {
            notify(notification)
            lastContent = content
        }
    }

    private data class Content(
        val title: String?, val text: String?, val subtext: String?,
        val showWhen: Boolean, val chronometer: Boolean, val countdown: Boolean,
        // The content intent always opens MainActivity; a fresh token is not a display change.
        val whenMs: Long, val visibility: Int,
    ) {
        /** Chronometers render whole seconds; don't repost for wall/elapsed read jitter. */
        fun sameDisplay(previous: Content?): Boolean = previous != null &&
            copy(whenMs = previous.whenMs) == previous &&
            (!showWhen || whenMs == previous.whenMs ||
                (chronometer && kotlin.math.abs(whenMs.toDouble() - previous.whenMs.toDouble()) < 1_000))

        companion object {
            fun from(n: Notification): Content {
                val e = n.extras
                return Content(e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                    e.getBoolean(Notification.EXTRA_SHOW_WHEN), e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
                    e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN), n.`when`, n.visibility)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "flint_enforcement"
        const val NOTIFICATION_ID = 1001
    }
}
