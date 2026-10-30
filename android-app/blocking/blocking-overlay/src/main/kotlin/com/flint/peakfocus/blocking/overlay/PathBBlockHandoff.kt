package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.view.View
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.model.Verdict
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.datastore.LimitsStore
import com.flint.peakfocus.core.datastore.BlockRulesStore
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Path B's enforcement handoff: the single entry point the UsageStats poll loop calls with
 * every detection, so poll-driven blocking flows through the exact same decision glue
 * ([BlockScreenCoordinator] → policies → shared `BlockScreen` UI) as the accessibility path.
 *
 * Wiring (owned by the integrator — `blocking-usagestats` is outside this task's scope):
 * in `UsageStatsForegroundService.tick()`, replace the `if (verdict is Verdict.Block) { TODO }`
 * stub with the one-liner
 *
 * ```kotlin
 * PathBBlockHandoff.onForegroundPolled(this, pkg, verdict)
 * ```
 *
 * and add `implementation(project(":blocking:blocking-overlay"))` to that module. Passing
 * *every* tick (Allow included) matters: it's how Open Limits count opens, how the overlay
 * comes down when the user leaves the app, and how break exemptions stand enforcement down.
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
     * Compatibility entry for a daily Time Limit hit. Also evaluate current rules, so callers
     * using this entry cannot accidentally shadow a Hardcore session with an EASY budget.
     */
    fun onTimeLimitExceeded(context: Context, packageName: String) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val rules = ActiveRulesHolder.rules
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val verdict = BlockDecisionEngine().decide(
            packageName,
            null,
            rules,
            appContext.packageName,
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            IsoWeekday.fromCalendar(cal.get(Calendar.DAY_OF_WEEK)),
            now,
        )
        onForegroundPolled(appContext, packageName, verdict, timeLimitSpent = true, rules = rules)
    }

    /**
     * Feed one poll observation through the shared decision flow. Cheap when nothing changed;
     * callable from any thread (UI work is marshalled internally).
     */
    fun onForegroundPolled(
        context: Context,
        packageName: String,
        verdict: Verdict,
        timeLimitSpent: Boolean = false,
        /** Resolved by the detector on IO; missing labels use the existing UI fallback. */
        appLabel: String? = null,
        /** Same immutable snapshot used to produce [verdict], even across a detector's IO suspension. */
        rules: List<BlockRule> = ActiveRulesHolder.rules,
    ) {
        val appContext = context.applicationContext
        val c = coordinator(appContext)
        val previous = lastForegroundPackage
        lastForegroundPackage = packageName

        if (packageName == appContext.packageName) return // Flint itself (incl. our block UI)
        val now = System.currentTimeMillis()
        val utcOffset = TimeZone.getDefault().getOffset(now).toLong()
        val ruleCause = (verdict as? Verdict.Block)?.let {
            ruleBlockCause(rules.firstOrNull { rule -> rule.id == it.ruleId }, now, utcOffset)
        }
        val cause = c.evaluateBlockCause(previous, packageName, ruleCause, timeLimitSpent)
        if (cause != null) {
            c.showBlocked(packageName, appLabel, cause)
        } else {
            c.hideIfShown()
        }
    }

    /** Production construction, with store/surface seams for deterministic handoff tests. */
    internal fun create(
        appContext: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        surfaceHost: BlockScreenCoordinator.SurfaceHost? = null,
        focusStateStore: FocusStateStore? = null,
        limitsStore: LimitsStore? = null,
        rulesStore: BlockRulesStore? = null,
        safetyFloor: SafetyFloorGate? = null,
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ): BlockScreenCoordinator {
        val overlay = OverlayController(appContext)
        val host = surfaceHost ?: object : BlockScreenCoordinator.SurfaceHost {
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
        // Defer until construction has published the singleton. A synchronous initial
        // readiness signal can otherwise re-enter coordinator() through a live subscriber.
        val requestEvaluation: () -> Unit = {
            scope.launch { yield(); EnforcementSignals.detectors.requestEvaluation() }
        }
        return BlockScreenCoordinator(
            context = appContext,
            scope = scope,
            surfaceHost = host,
            onBlockSurfaceUnavailable = { ctx, pkg, label, cause ->
                BlockActivity.launch(ctx, pkg, label, cause)
            },
            onExemptionExpired = { requestEvaluation() },
            onEnforcementChanged = requestEvaluation,
            focusStateStore = focusStateStore,
            configuredLimitsStore = limitsStore,
            blockRulesStore = rulesStore,
            safetyFloorGate = safetyFloor,
            nowEpochMs = nowEpochMs,
        )
    }

}
