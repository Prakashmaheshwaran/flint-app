package com.flint.peakfocus.core.data

import android.content.Context

/**
 * Per-app daily Time Limits (package → minutes). When today's foreground time for the app reaches
 * the budget, it blocks until tomorrow. Free in Flint. Read synchronously by the AccessibilityService.
 */
class LimitStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("flint_limits", Context.MODE_PRIVATE)

    /** Daily budget in minutes for [packageName], or null if no limit is set. */
    fun limitMinutes(packageName: String): Int? {
        val value = prefs.getInt(packageName, -1)
        return if (value >= 0) value else null
    }

    fun setLimit(packageName: String, minutes: Int) {
        prefs.edit().putInt(packageName, minutes).apply()
    }

    fun clear(packageName: String) {
        prefs.edit().remove(packageName).apply()
    }

    fun all(): Map<String, Int> =
        prefs.all.mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap()
}
