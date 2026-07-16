package com.flint.peakfocus.permissions

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * One place for the brittle special-access checks + Settings hand-off intents. Holding a
 * manifest permission does NOT mean access is granted for these — always verify at runtime
 * (and re-verify each launch; some OEMs reset grants after an OTA).
 */

/** Usage access (PACKAGE_USAGE_STATS). Granted in Settings, verified via AppOpsManager. */
object UsageAccess {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}

/** Draw-over-other-apps (SYSTEM_ALERT_WINDOW). Manual grant; verify with canDrawOverlays(). */
object OverlayPermission {
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}

/** AccessibilityService enable check + hand-off. The consent screen MUST precede this. */
object AccessibilityPermission {
    fun isEnabled(context: Context, serviceClassName: String): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = "${context.packageName}/$serviceClassName"
        return enabled.split(':').any {
            it.equals(component, ignoreCase = true) || it.endsWith(serviceClassName)
        }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

/**
 * POST_NOTIFICATIONS (Android 13+). A runtime permission: declared in the manifest but hidden
 * until granted, so the Path B foreground-service notification is invisible on 13+ without it
 * (the service still runs — this grant affects visibility, never enforcement). Below 13 there
 * is nothing to grant.
 *
 * [sdkInt] is injectable because `Build.VERSION.SDK_INT` reads as 0 in JVM unit tests.
 */
object NotificationPermission {
    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        !isRequired(sdkInt) ||
            context.checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * The app's notification settings page. A Settings hand-off (not the one-shot runtime
     * dialog) keeps the row consistent with every other fix action here — explicit tap,
     * disclosure first (ADR-007) — and still works after the user has hard-denied the dialog.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    // Manifest.permission.POST_NOTIFICATIONS needs compileSdk 33+; spelled out so this file
    // stays readable at a glance.
    private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
}

/** Battery-optimization exemption (OEM survival). */
object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
}
