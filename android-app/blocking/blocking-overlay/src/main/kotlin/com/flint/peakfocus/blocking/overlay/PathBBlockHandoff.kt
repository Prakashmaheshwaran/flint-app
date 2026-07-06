package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.view.View
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.OpenLimitDecision
import com.flint.peakfocus.core.model.Verdict
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Path B's enforcement handoff: the single entry point the UsageStats poll loop calls with
 * every detection, so poll-driven blocking flows through the exact same decision glue
 * ([BlockScreenCoordinator] → policies → shared `BlockScreen` UI) as the accessibility path.
 *
 * Wiring: `UsageStatsForegroundService.tick()` calls
 *
 * ```kotlin
 * PathBBlockHandoff.onForegroundPolled(this, pkg, verdict, timeLimitSpent)
 * ```
 *
 * every poll (Allow included) — that's how Open Limits count opens, how the overlay comes
 * down when the user leaves the app, and how break exemptions stand enforcement down.
 * [timeLimitSpent] is the service's cached "daily budget spent" fact (`PathBTimeLimitGate`
 * over `LimitStore` + `DailyLimitTracker` in blocking-usagestats — this module deliberately
 * doesn't read usage stats itself). The decision order below mirrors Path A exactly:
 * exemption → Time Limit → rule verdict → Open Limits.
 *
 * Enforcement surface: the `TYPE_APPLICATION_OVERLAY` window ([OverlayController]) when the
 * SYSTEM_ALERT_WINDOW grant is present; otherwise the full-screen [BlockActivity] fallback
 * (best-effort — without the overlay grant, background-activity-launch limits may also bite;
 * Path B is the documented-degraded path, see the module README/strategy doc).
 */
object PathBBlockHandoff {

    @Volatile private var coordinator: BlockScreenCoordinator? = null
    @Volatile private var lastForegroundPackage: String? = null

    /**
     * The process-wide Path B coordinator (lazily created). [BlockActivity] also uses this
     * for its break/pass accounting so both Path B surfaces share one state brain.
     */
    fun coordinator(context: Context): BlockScreenCoordinator {
        coordinator?.let { return it }
        synchronized(this) {
            coordinator?.let { return it }
            return create(context.applicationContext).also { coordinator = it }
        }
    }

    /**
     * Feed one poll observation through the shared decision flow. Cheap when nothing changed;
     * callable from any thread (UI work is marshalled internally). [timeLimitSpent] = the
     * caller's fact that this app's daily time budget is used up; it blocks with the
     * TIME_LIMIT cause before rules are consulted — the same precedence Path A applies —
     * but never overrides an active break/pass exemption.
     */
    fun onForegroundPolled(
        context: Context,
        packageName: String,
        verdict: Verdict,
        timeLimitSpent: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val c = coordinator(appContext)
        val previous = lastForegroundPackage
        lastForegroundPackage = packageName

        if (packageName == appContext.packageName) return // Flint itself (incl. our block UI)
        if (c.isExempt(packageName)) {
            c.hideIfShown() // an active break / Emergency Pass window: stand down
            return
        }

        val now = System.currentTimeMillis()
        val utcOffset = TimeZone.getDefault().getOffset(now).toLong()
        if (timeLimitSpent) {
            // Same tier source as Path A: TimeLimit carries no per-limit tier, so the
            // store-wide default break level applies.
            c.showBlocked(
                packageName,
                labelFor(appContext, packageName),
                timeLimitBlockCause(c.currentDefaultBreakLevel(), now, utcOffset),
            )
            return
        }
        when (verdict) {
            is Verdict.Block -> {
                val rule = ActiveRulesHolder.rules.firstOrNull { it.id == verdict.ruleId }
                c.showBlocked(packageName, labelFor(appContext, packageName), ruleBlockCause(rule, now, utcOffset))
            }
            Verdict.Allow -> {
                // Not rule-blocked: count/check Open Limits. Order matters — an app the rules
                // already shield never records an open (you never actually got into it).
                when (val decision = c.onForegroundChanged(previous, packageName)) {
                    is OpenLimitDecision.Blocked ->
                        c.showBlocked(packageName, labelFor(appContext, packageName), openLimitBlockCause(decision.limit, now, utcOffset))
                    is OpenLimitDecision.Allowed -> c.hideIfShown()
                }
            }
        }
    }

    private fun create(appContext: Context): BlockScreenCoordinator {
        val overlay = OverlayController(appContext)
        val host = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean {
                if (!overlay.canShow()) return false
                overlay.show(view)
                return overlay.isShowing()
            }

            override fun detach(view: View) = overlay.hide()

            override fun goHome() {
                // "Go back" without an AccessibilityService: launch the home screen. With the
                // SYSTEM_ALERT_WINDOW grant held (required for this path) plus the user's tap,
                // this stays within background-activity-launch allowances; best-effort anyway.
                runCatching {
                    appContext.startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        return BlockScreenCoordinator(
            context = appContext,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            surfaceHost = host,
            onBlockSurfaceUnavailable = { ctx, pkg, label, cause ->
                BlockActivity.launch(ctx, pkg, label, cause)
            },
            // No expiry callback: the poll loop re-evaluates within a tick anyway.
            onExemptionExpired = null,
        )
    }

    private fun labelFor(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
}
