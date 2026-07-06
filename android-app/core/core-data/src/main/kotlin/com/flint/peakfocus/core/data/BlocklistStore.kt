package com.flint.peakfocus.core.data

import android.content.Context
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.Schedule

/**
 * Persists the set of blocked app package names and projects them into the in-memory
 * [ActiveRulesHolder] that both detection paths read — into the holder's dedicated
 * legacy-blocklist lane, so reloading here never disturbs the DataStore-authored rules the
 * app-side bridge publishes. SharedPreferences (not DataStore) so the AccessibilityService can
 * read synchronously on connect. This is the MVP store; richer rules (schedules, limits,
 * allow-list) layer on top later.
 */
class BlocklistStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("flint_blocklist", Context.MODE_PRIVATE)

    var blockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_PACKAGES, value).apply()
            syncToHolder(value)
        }

    /** Optional active-hours window. When enabled, the blocklist only enforces inside it. */
    var scheduleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHED_ON, false)
        set(value) { prefs.edit().putBoolean(KEY_SCHED_ON, value).apply(); load() }

    /** Window start, minutes-of-day [0, 1439]. */
    var scheduleStartMin: Int
        get() = prefs.getInt(KEY_SCHED_START, 0)
        set(value) { prefs.edit().putInt(KEY_SCHED_START, value).apply(); load() }

    /** Window end, minutes-of-day [0, 1439]. */
    var scheduleEndMin: Int
        get() = prefs.getInt(KEY_SCHED_END, 1439)
        set(value) { prefs.edit().putInt(KEY_SCHED_END, value).apply(); load() }

    /** Load persisted state into [ActiveRulesHolder] (call on service connect / app start). */
    fun load() = syncToHolder(blockedPackages)

    fun toggle(packageName: String) {
        val current = blockedPackages.toMutableSet()
        if (!current.add(packageName)) current.remove(packageName)
        blockedPackages = current
    }

    private fun syncToHolder(packages: Set<String>) {
        if (packages.isEmpty()) {
            ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, emptyList())
            return
        }
        val schedule = if (scheduleEnabled) {
            Schedule(
                daysOfWeek = emptySet(),
                startMinuteOfDay = scheduleStartMin,
                endMinuteOfDay = scheduleEndMin,
            )
        } else {
            null
        }
        ActiveRulesHolder.publish(
            ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST,
            listOf(
                BlockRule(
                    id = "blocklist",
                    name = "Blocklist",
                    targets = BlockTargets(apps = packages.map { AppRef(it) }.toSet()),
                    schedule = schedule,
                ),
            ),
        )
    }

    private companion object {
        const val KEY_PACKAGES = "blocked_packages"
        const val KEY_SCHED_ON = "schedule_on"
        const val KEY_SCHED_START = "schedule_start"
        const val KEY_SCHED_END = "schedule_end"
    }
}
