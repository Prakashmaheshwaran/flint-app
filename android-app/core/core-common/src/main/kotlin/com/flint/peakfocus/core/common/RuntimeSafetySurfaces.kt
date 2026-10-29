package com.flint.peakfocus.core.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager

/**
 * The device's *runtime* safety-floor surfaces (see `core-model` SafetyFloor): the current
 * default home app, the default and system dialer, and the package that handles uninstalling an
 * app (contract §7/§10: uninstall UI stays reachable on every OEM). A custom default dialer or launcher can be
 * any package, so a build-time list cannot cover it. Shared by the detectors' block coordinator
 * and the app pickers, so a surface the pickers hide is exactly one enforcement refuses to shield.
 *
 * Only the *default* home app is on the floor: it is the way back to the home screen (shielding
 * it loops Home into the block screen). Other packages that merely declare a HOME activity are
 * ordinary apps while the required roles are resolved. During a role-lookup failure,
 * candidate Home/calling handlers remain reachable until the actual default can be proved.
 * A launcher the user just made default is picked up by the coordinator's re-resolve before it
 * mounts a shield.
 *
 * [resolveBySource] keeps each lookup separate: a failing service yields `null` for that source
 * only, so a caller can keep its last known value instead of shrinking the floor. Package
 * visibility for the HOME query comes from this module's manifest `<queries>`.
 */
object RuntimeSafetySurfaces {

    const val SOURCE_HOME = "home"
    const val SOURCE_DEFAULT_DIALER = "default-dialer"
    const val SOURCE_SYSTEM_DIALER = "system-dialer"
    const val SOURCE_UNINSTALLER = "uninstaller"
    const val SOURCE_CANDIDATE_HOME = "candidate-home"
    const val SOURCE_CANDIDATE_CALLING = "candidate-calling"
    const val SOURCE_ORDINARY_LAUNCHERS = "classified-ordinary-launchers"
    val CANDIDATE_SOURCES = setOf(SOURCE_CANDIDATE_HOME, SOURCE_CANDIDATE_CALLING, SOURCE_ORDINARY_LAUNCHERS)

    /** Missing role identities are unresolved, even when the Android API returns normally. */
    internal fun resolvedIdentity(packageName: String?): Set<String>? =
        packageName?.takeIf { it.isNotBlank() }?.let(::setOf)

    /** No telephony is a proven absence; a failed lookup is not. Null defaults use the effective dialer. */
    internal fun resolvedCallingIdentity(hasTelephony: Boolean?, role: () -> String?, effective: () -> String?): Set<String>? {
        if (hasTelephony == false) return emptySet()
        if (hasTelephony == null) return null
        return runCatching { resolvedIdentity(role()) ?: resolvedIdentity(effective()) }.getOrNull()
    }

    /** Source → resolved packages, or null when the role identity could not be established. */
    fun resolveBySource(context: Context): Map<String, Set<String>?> {
        val pm = context.packageManager
        val telecom = runCatching {
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        }
        val hasTelephony = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }.getOrNull()
        val effectiveDialer by lazy { effectiveRole(pm, Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
        return buildMap {
            put(
                SOURCE_HOME,
                runCatching {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    resolvedIdentity(effectiveRole(pm, home))
                }.getOrNull(),
            )
            put(
                SOURCE_UNINSTALLER,
                runCatching {
                    val delete = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
                    @Suppress("DEPRECATION")
                    resolvedIdentity(
                        pm.resolveActivity(delete, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName,
                    )
                }.getOrNull(),
            )
            put(
                SOURCE_DEFAULT_DIALER,
                resolvedCallingIdentity(hasTelephony, { telecom.getOrThrow()?.defaultDialerPackage }, { effectiveDialer }),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    SOURCE_SYSTEM_DIALER,
                    resolvedCallingIdentity(hasTelephony, { telecom.getOrThrow()?.systemDialerPackage }, { effectiveDialer }),
                )
            }
            // The role and candidate results share one refresh/decision snapshot. Handler
            // discovery does not prove that a candidate currently owns a default role.
            if (this[SOURCE_HOME] == null || this[SOURCE_DEFAULT_DIALER] == null) {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val calling = listOf(Intent(Intent.ACTION_DIAL),
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                    Intent(Intent.ACTION_CALL, Uri.parse("tel:")),
                    Intent(Intent.ACTION_VIEW, Uri.parse("tel:")))
                fun callingPackages(packageName: String? = null): Set<String>? {
                    val answers = calling.map { queryPackages(pm, Intent(it).setPackage(packageName)) }
                    return if (answers.any { it == null }) null else answers.flatMapTo(HashSet()) { it.orEmpty() }
                }
                putAll(candidateSources(queryPackages(pm, home), callingPackages(),
                    visibleLaunchers = { queryPackages(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)) },
                    classify = { pkg ->
                        val homeHandlers = queryPackages(pm, Intent(home).setPackage(pkg))
                        val callingHandlers = callingPackages(pkg)
                        when {
                            homeHandlers == null || callingHandlers == null -> null
                            pkg in homeHandlers || pkg in callingHandlers -> true
                            else -> false
                        }
                    }))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryPackages(pm: PackageManager, intent: Intent): Set<String>? = runCatching {
        pm.queryIntentActivities(Intent(intent).addCategory(Intent.CATEGORY_DEFAULT), PackageManager.MATCH_DEFAULT_ONLY).mapTo(HashSet()) {
            requireNotNull(it.activityInfo?.packageName?.takeIf(String::isNotBlank))
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun effectiveRole(pm: PackageManager, intent: Intent): String? {
        val matched = Intent(intent).addCategory(Intent.CATEGORY_DEFAULT)
        val pkg = pm.resolveActivity(matched, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: return null
        // A system chooser is not proof of a default role. It must be a real matching handler.
        return pkg.takeIf { it in queryPackages(pm, matched).orEmpty() }
    }

    /** Empty successful discovery permits ordinary blocking with degraded role health;
     * failed discovery never promotes missing classification to proof of an ordinary app. */
    internal fun candidateSources(home: Set<String>?, calling: Set<String>?,
        visibleLaunchers: () -> Set<String>?, classify: (String) -> Boolean?): Map<String, Set<String>?> = mapOf(
        SOURCE_CANDIDATE_HOME to home,
        SOURCE_CANDIDATE_CALLING to calling,
        SOURCE_ORDINARY_LAUNCHERS to if (home != null && calling != null) emptySet() else
            visibleLaunchers().orEmpty().filterTo(HashSet()) { classify(it) == false },
    )

    /** Union of every lookup that succeeded (for one-shot callers such as the pickers). */
    fun resolve(context: Context): Set<String> =
        resolveBySource(context).filterKeys { it !in CANDIDATE_SOURCES }.values.flatMapTo(LinkedHashSet()) { it.orEmpty() }
}
