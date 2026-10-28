package com.flint.peakfocus.feature.blocklist

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import com.flint.peakfocus.core.model.SafetyFloor

/** One launchable app as the picker shows it. [icon] is null when the drawable couldn't render. */
internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * Enumerates launchable apps via [android.content.pm.PackageManager] — the Android advantage the
 * spec calls out (1.6): unlike iOS's opaque tokens, we can list packages with real labels and
 * icons. Relies on the launcher-intent `<queries>` declaration (this module's manifest and the
 * app's) for package visibility on Android 11+.
 *
 * [loadLaunchable] does binder calls and icon rasterization — call it from a background
 * dispatcher (the ViewModel does). Everything stays on-device; nothing is logged or sent
 * anywhere (ADR-002).
 */
internal class InstalledAppsSource(context: Context) {

    private val appContext = context.applicationContext

    fun loadLaunchable(): List<InstalledApp> {
        val pm = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION") // The ResolveInfoFlags overload needs API 33; minSdk is 23.
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        // The same runtime surfaces enforcement exempts (default/system dialer, home apps).
        val runtimeFloor = RuntimeSafetySurfaces.resolve(appContext)

        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                // Never offer safety-floor apps (Flint, dialer/emergency, home apps, Settings and
                // permission recovery): enforcement never shields them, so a picker entry would
                // promise a block that cannot happen. Already-stored selections stay inert.
                if (SafetyFloor.isProtected(pkg, appContext.packageName, runtimeFloor)) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                val icon = try {
                    info.loadIcon(pm)?.toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)?.asImageBitmap()
                } catch (_: Exception) {
                    null // Hostile/broken drawables degrade to the letter avatar, never crash.
                }
                InstalledApp(packageName = pkg, label = label, icon = icon)
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { app: InstalledApp -> app.label }
                    .thenBy { it.packageName },
            )
    }

    private companion object {
        /** Icons render at ~40dp in lists; 96px keeps a few hundred icons to ~10 MB, not ~100. */
        const val ICON_SIZE_PX = 96
    }
}
