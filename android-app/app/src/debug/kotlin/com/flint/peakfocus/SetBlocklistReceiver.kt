package com.flint.peakfocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.data.LimitStore

/**
 * DEBUG ONLY — sets the blocklist / schedule deterministically for testing via adb:
 *
 *   adb shell am broadcast -n com.flint.peakfocus/.SetBlocklistReceiver --es package <pkg>
 *   adb shell am broadcast -n com.flint.peakfocus/.SetBlocklistReceiver --ez clear true
 *   adb shell am broadcast -n com.flint.peakfocus/.SetBlocklistReceiver --ei schedStart 540 --ei schedEnd 1020
 *   adb shell am broadcast -n com.flint.peakfocus/.SetBlocklistReceiver --ez schedClear true
 *
 * Lives in src/debug so it never ships in a release build.
 */
class SetBlocklistReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = BlocklistStore(context)
        when {
            intent.getBooleanExtra("clear", false) -> store.blockedPackages = emptySet()
            intent.getBooleanExtra("schedClear", false) -> store.scheduleEnabled = false
            intent.hasExtra("schedStart") -> {
                store.scheduleStartMin = intent.getIntExtra("schedStart", 0)
                store.scheduleEndMin = intent.getIntExtra("schedEnd", 1439)
                store.scheduleEnabled = true
            }
            intent.hasExtra("limitPkg") -> {
                LimitStore(context).setLimit(
                    intent.getStringExtra("limitPkg")!!,
                    intent.getIntExtra("limitMin", 0),
                )
            }
            else -> intent.getStringExtra("package")?.let { pkg ->
                store.blockedPackages = store.blockedPackages + pkg
            }
        }
    }
}
