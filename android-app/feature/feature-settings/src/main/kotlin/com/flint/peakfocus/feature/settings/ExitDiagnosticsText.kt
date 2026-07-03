package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.blocking.resilience.ExitCategory

/**
 * Plain-language rendering for the "Why did blocking stop?" row: maps blocking-resilience's
 * [ExitCategory] buckets to honest user copy and formats how long ago each death happened.
 * Pure Kotlin — JVM-tested in ExitDiagnosticsTextTest.
 *
 * These are on-device readings of Android's own exit records (ADR-002: nothing is collected,
 * stored elsewhere, or sent anywhere — the OS already keeps this history).
 */
data class ExitExplanation(val headline: String, val detail: String)

object ExitDiagnosticsText {

    fun explain(category: ExitCategory): ExitExplanation = when (category) {
        ExitCategory.USER_KILL -> ExitExplanation(
            headline = "Force-stopped",
            detail = "Flint was force-stopped — from App info, or by a task manager's " +
                "\"clean up\". Android keeps everything (including blocking) off after a " +
                "force stop until Flint is opened again.",
        )
        ExitCategory.MEMORY_PRESSURE -> ExitExplanation(
            headline = "Killed to free memory",
            detail = "Android reclaimed memory and Flint's process went with it. Occasional " +
                "kills are normal; frequent ones usually come alongside aggressive battery " +
                "management — see the manufacturer steps on this screen.",
        )
        ExitCategory.CRASH -> ExitExplanation(
            headline = "Flint crashed",
            detail = "This one is a bug in Flint, not your settings or your phone. Blocking " +
                "re-arms the next time Flint runs.",
        )
        ExitCategory.ANR -> ExitExplanation(
            headline = "Stopped for not responding",
            detail = "The system ended Flint because it stopped responding. Like a crash, " +
                "this is a Flint problem rather than a settings problem.",
        )
        ExitCategory.PERMISSION_CHANGE -> ExitExplanation(
            headline = "An access grant changed",
            detail = "Android stopped Flint because a permission or special access changed. " +
                "Check the access list above — something may need re-enabling.",
        )
        ExitCategory.PACKAGE_CHANGE -> ExitExplanation(
            headline = "Flint was updated or disabled",
            detail = "The app was updated, disabled, or changed state, and Android restarted " +
                "its processes. Normal after an update.",
        )
        ExitCategory.OEM_OR_SYSTEM_KILL -> ExitExplanation(
            headline = "Stopped by the system — likely battery management",
            detail = "This is the classic manufacturer power-manager kill. The battery " +
                "exemption and the manufacturer steps on this screen are the fix.",
        )
        ExitCategory.SELF_EXIT -> ExitExplanation(
            headline = "Exited normally",
            detail = "Flint shut down in an orderly way. Nothing to fix.",
        )
        ExitCategory.UNKNOWN -> ExitExplanation(
            headline = "Unknown reason",
            detail = "Android did not record a reason (or recorded one this version of Flint " +
                "doesn't know yet). If blocking keeps stopping, work through the access list " +
                "and manufacturer steps on this screen.",
        )
    }

    /**
     * "just now" / "5 min ago" / "3 hr ago" / "2 days ago". Future timestamps (clock changes)
     * read as "just now" rather than something nonsensical.
     */
    fun relativeAge(timestampMillis: Long, nowMillis: Long): String {
        val delta = nowMillis - timestampMillis
        return when {
            delta < MINUTE -> "just now"
            delta < HOUR -> "${delta / MINUTE} min ago"
            delta < DAY -> "${delta / HOUR} hr ago"
            delta < 2 * DAY -> "1 day ago"
            else -> "${delta / DAY} days ago"
        }
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
}
