package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager

/**
 * The device's *actual* default launcher and dialer, resolved at service start — the engine's
 * static safety floor ([com.flint.peakfocus.blocking.engine] SystemSurfaces) can only name
 * common OEM packages, and these two must never be shielded: blocking the launcher turns the
 * Home button into a block-screen loop during allow-list windows (Sleep Mode, brick-phone
 * mode), and blocking the dialer blocks emergency calling. Both detectors consult this set
 * before any verdict. Deliberately NOT part of the open-limit "doesn't count" heuristic —
 * launcher→app transitions are exactly the opens that should count.
 */
object NeverBlockSurfaces {

    fun resolve(context: Context): Set<String> = buildSet {
        val pm = context.packageManager
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let(::add)
        }
        runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.let(::add)
        }
    }
}
