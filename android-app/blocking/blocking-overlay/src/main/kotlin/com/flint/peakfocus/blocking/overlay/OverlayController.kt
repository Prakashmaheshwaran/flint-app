package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Path B enforcement: draws a full-screen cover using TYPE_APPLICATION_OVERLAY (the only legal
 * overlay type since Android 8). Requires the SYSTEM_ALERT_WINDOW grant (canDrawOverlays).
 *
 * The AccessibilityService path uses an *accessibility* overlay window instead and needs no such
 * grant. Overlays are leaky by design (Android 12 "Hide overlay windows", HIDE_OVERLAY_WINDOWS,
 * dismissed by Home) — pair with GLOBAL_ACTION_HOME / a re-trigger loop. See strategy doc §3.
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var current: View? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = current != null

    fun show(content: View) {
        if (current != null || !canShow()) return
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.CENTER }

        wm.addView(content, params)
        current = content
    }

    fun hide() {
        current?.let {
            runCatching { wm.removeView(it) }
            current = null
        }
    }
}
