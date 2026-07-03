package com.flint.peakfocus.blocking.resilience

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why did Flint last die? Local-only diagnostics over [ApplicationExitInfo] (strategy doc §6.2).
 *
 * Read-only: this cannot prevent kills, only explain them after the fact. feature-settings uses
 * the summary to tell the user *why* enforcement lapsed (e.g. an OEM power manager killed the
 * process → suggest the battery-exemption / auto-start fix). Everything stays on-device —
 * no telemetry, nothing leaves the phone (ADR-002).
 */

/**
 * Human-meaningful buckets over the raw `ApplicationExitInfo.REASON_*` codes. feature-settings
 * maps these to user-facing copy; the raw code rides along in [ExitDiagnostic] for detail views.
 */
enum class ExitCategory {
    /** Force-stop or a user/profile stop — the "you (or the OS UI) killed Flint" case. */
    USER_KILL,

    /** The low-memory killer took the process. */
    MEMORY_PRESSURE,

    /** Java/native crash or failed initialization — a Flint bug, not an external kill. */
    CRASH,

    /** The process was killed for not responding. */
    ANR,

    /** A permission/app-op changed and the system killed the process — pairs with [PermissionHealthChecker]. */
    PERMISSION_CHANGE,

    /** The package was updated, disabled, or otherwise changed state. */
    PACKAGE_CHANGE,

    /**
     * Killed by a signal, a dead dependency, resource pressure, the freezer, or "other" — the
     * generic system-side kills. OEM power managers usually surface here (REASON_SIGNALED /
     * REASON_OTHER), so repeated exits in this bucket are the cue to suggest OEM whitelisting.
     */
    OEM_OR_SYSTEM_KILL,

    /** The app exited on its own (`System.exit` / finished normally). */
    SELF_EXIT,

    /** REASON_UNKNOWN or a code newer than this mapping. */
    UNKNOWN,
    ;

    companion object {
        /**
         * Pure mapping from a raw `ApplicationExitInfo.REASON_*` code (the constants are
         * compile-time ints, so this is JVM-testable and safe to load on any API level).
         */
        fun fromReason(reason: Int): ExitCategory = when (reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            -> USER_KILL

            ApplicationExitInfo.REASON_LOW_MEMORY -> MEMORY_PRESSURE

            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            -> CRASH

            ApplicationExitInfo.REASON_ANR -> ANR

            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> PERMISSION_CHANGE

            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            -> PACKAGE_CHANGE

            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_OTHER,
            -> OEM_OR_SYSTEM_KILL

            ApplicationExitInfo.REASON_EXIT_SELF -> SELF_EXIT

            else -> UNKNOWN // REASON_UNKNOWN and anything newer than this mapping
        }
    }
}

/**
 * One summarized process death, newest-first from [ExitReasonReporter.lastExits]. Plain data —
 * safe to hold in UI state and render in feature-settings.
 *
 * @property category the human-meaningful bucket (see [ExitCategory])
 * @property reasonCode the raw `ApplicationExitInfo.REASON_*` code behind [category]
 * @property timestampMillis when the process died (epoch millis)
 * @property description the system's free-text detail, if any (device-local diagnostics only)
 */
data class ExitDiagnostic(
    val category: ExitCategory,
    val reasonCode: Int,
    val timestampMillis: Long,
    val description: String?,
)

/**
 * On-demand reader of the OS exit-reason history for Flint's own package. No permission needed
 * for one's own records. Below API 30 the API doesn't exist and this reports an empty history —
 * callers should treat "no data" as "nothing to show", not as "healthy".
 */
object ExitReasonReporter {

    /**
     * The most recent process deaths, newest first (OS order preserved), at most [maxCount].
     * Empty below API 30 or if the system has no records yet.
     */
    fun lastExits(context: Context, maxCount: Int = DEFAULT_MAX_EXITS): List<ExitDiagnostic> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return runCatching {
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, /* pid = */ 0, maxCount)
                .map { info ->
                    ExitDiagnostic(
                        category = ExitCategory.fromReason(info.reason),
                        reasonCode = info.reason,
                        timestampMillis = info.timestamp,
                        description = info.description,
                    )
                }
        }.getOrDefault(emptyList())
    }

    /** Convenience for the common settings-UI question: the single most recent death, if any. */
    fun lastExit(context: Context): ExitDiagnostic? = lastExits(context, maxCount = 1).firstOrNull()

    private const val DEFAULT_MAX_EXITS = 5
}
