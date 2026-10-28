package com.flint.peakfocus.blocking.overlay

import android.app.Notification
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Additive content seam for Path B's existing ongoing foreground-service notification (lane K):
 * registered customizers may set title/text/chronometer/when/contentIntent on the builder Flint is
 * about to post. Flint keeps its own non-empty fallback title/text, the same channel and
 * notification id, and re-applies [customizers][register] when the service refreshes the
 * notification (its tick/boundary path). The ongoing flag and FGS type are re-asserted by the
 * service after customization, so a customizer cannot make the notification dismissible or change
 * the service type. A throwing customizer is skipped.
 */
object PathBNotification {

    fun interface Customizer {
        fun customize(context: Context, builder: Notification.Builder)
    }

    private val customizers = CopyOnWriteArrayList<Customizer>()

    fun register(customizer: Customizer) {
        customizers.addIfAbsent(customizer)
    }

    fun unregister(customizer: Customizer) {
        customizers.remove(customizer)
    }

    /** Service-only: apply every customizer to [builder]; returns how many succeeded. */
    fun apply(context: Context, builder: Notification.Builder): Int =
        customizers.count { runCatching { it.customize(context, builder) }.isSuccess }
}
