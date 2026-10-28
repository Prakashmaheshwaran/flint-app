package com.flint.peakfocus.blocking.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.BlockScreenCoordinator
import com.flint.peakfocus.blocking.overlay.ruleBlockCause
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.Verdict
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Path A — the primary, event-driven detector and the only source of browser URLs.
 *
 * Enforcement is a full-screen **accessibility overlay** (`TYPE_ACCESSIBILITY_OVERLAY`), which an
 * AccessibilityService may draw without the SYSTEM_ALERT_WINDOW grant and without hitting
 * background-activity-launch limits — which is why the branded block screen is hosted *in this
 * window* (a ComposeView via `BlockScreenHostView`) rather than by launching an Activity: the
 * overlay is the more kill-resistant surface, and keeping it preserves this path's documented
 * enforcement behavior. The [BlockScreenCoordinator] (blocking-overlay) supplies the screen,
 * the 1-second clock, and the break / Emergency-Pass / Open-Limit accounting shared with Path B.
 *
 * Decision order per foreground change:
 *  1. the enforcement safety floor ([BlockScreenCoordinator.isSafetyFloor]: dialer, emergency,
 *     home apps, Settings/permission recovery, Flint) — no ordinary cause reaches those;
 *  2. Gather daily Time Limit, rule and Open Limit candidates;
 *  3. the shared coordinator selects the strongest protection, then applies exemptions;
 *     ordinary breaks never bypass a currently applicable Hardcore restriction;
 *  4. count a permitted open only when no other restriction/exemption controls access.
 *
 * No uninstall/disable guard (protection contract ADR-012 §10, Play Accessibility API policy):
 * this service never intercepts uninstallers, Settings, Accessibility disable, app info,
 * force-stop or permission revocation, and never reads Settings window text. Turning access off
 * reduces or stops protection, and that stays the user's call.
 *
 * Compliance: NOT an accessibility tool; the in-app consent flow (feature-onboarding) must run
 * before the user enables this. On connect it loads the persisted blocklist.
 */
class FlintAccessibilityService : AccessibilityService() {

    private val engine = BlockDecisionEngine()
    private var serviceScope: CoroutineScope? = null
    private var coordinator: BlockScreenCoordinator? = null

    /** One-shot re-check armed while a time-limited app sits in front (see [armTimeLimitRecheck]). */
    private var timeLimitRecheck: Job? = null

    /** Last package seen in the foreground (Flint + system surfaces included — the open-limit
     *  policy interprets those transitions itself, e.g. shield bounces are not re-opens). */
    @Volatile private var lastForegroundPackage: String? = null

    /** Class name from the last window event (recognises Flint's own MainActivity coming to front). */
    @Volatile private var lastForegroundClassName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        BlocklistStore(this).load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        coordinator = BlockScreenCoordinator(
            context = this,
            scope = scope,
            surfaceHost = AccessibilityOverlayHost(),
            // Event-driven path: when a break/pass runs out, re-check the app the user is
            // still sitting in — no window event fires just because time passed.
            onExemptionExpired = { reevaluateForeground() },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val previous = lastForegroundPackage
        lastForegroundPackage = pkg
        lastForegroundClassName = event.className?.toString()
        if (pkg == packageName) {
            // Our own windows. The overlay's attach fires a self event too, so hiding on
            // every self event would dismiss the shield the instant it shows — but the app's
            // MAIN ACTIVITY coming to front (launcher, notification, adb) is a deliberate
            // visit, and without this the shield lingers over Flint's own UI (seen live on
            // the emulator). The activity class is package-derived, never hard-coded.
            if (lastForegroundClassName == "$packageName.MainActivity") {
                coordinator?.hideIfShown()
            }
            return
        }
        evaluateForeground(previous, pkg)
    }

    /** Re-run the decision pipeline for whatever is in front (used after exemption expiry). */
    private fun reevaluateForeground() {
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val pkg = rootPkg
            ?: lastForegroundPackage
            ?: return
        if (pkg == packageName) return
        // previous == pkg on purpose: a re-check is not a new "open" of the app.
        evaluateForeground(previousPackage = pkg, pkg = pkg)
    }

    private fun evaluateForeground(previousPackage: String?, pkg: String) {
        val coordinator = coordinator ?: return // events never precede onServiceConnected
        timeLimitRecheck?.cancel() // every fresh decision invalidates a pending budget re-check
        val now = System.currentTimeMillis()
        val utcOffset = TimeZone.getDefault().getOffset(now).toLong()
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Schedule.daysOfWeek is ISO-numbered (1=Mon…7=Sun — core-model's documented contract);
        // Calendar.DAY_OF_WEEK is 1=Sun…7=Sat. Convert before handing to the engine.
        val weekday = IsoWeekday.fromCalendar(cal.get(Calendar.DAY_OF_WEEK))

        // 1) Safety floor (allow-list windows included): blocking the launcher loops the Home
        //    button into the shield; the dialer and emergency surfaces are emergency calling;
        //    Settings/permission pages are how protection is recovered. The coordinator applies
        //    the same floor again; this early return also skips the budget query.
        if (coordinator.isSafetyFloor(pkg)) {
            coordinator.hideIfShown()
            return
        }

        // Time Limit: blocked once today's foreground time reaches the app's daily budget —
        //    the stricter of the legacy store and the Limit editor's DataStore limits (read via
        //    the coordinator's snapshot; they were authored-but-never-enforced before that).
        //    TimeLimit carries no tier, so the store-wide default break level applies.
        val limitMin = listOfNotNull(
            LimitStore(this).limitMinutes(pkg),
            coordinator.timeLimitMinutes(pkg),
        ).minOrNull()
        val timeLimitSpent = limitMin != null && UsageQuery.dailyForegroundMillis(this, pkg) >= limitMin * 60_000L

        // Inspect rules even when a weaker time limit or an old ordinary break applies.
        val url = if (BrowserAddressBars.isBrowser(pkg)) readUrl(rootInActiveWindow, pkg) else null
        val verdict = engine.decide(pkg, url, ActiveRulesHolder.rules, packageName, nowMin, weekday, now)
        val ruleCause = (verdict as? Verdict.Block)?.let {
            ruleBlockCause(ActiveRulesHolder.rules.firstOrNull { rule -> rule.id == it.ruleId }, now, utcOffset)
        }
        val cause = coordinator.evaluateBlockCause(previousPackage, pkg, ruleCause, timeLimitSpent, url)
        if (cause != null) {
            coordinator.showBlocked(pkg, labelFor(pkg), cause)
        } else {
            coordinator.hideIfShown()
            if (limitMin != null && !timeLimitSpent) armTimeLimitRecheck(pkg, limitMin)
        }
    }

    /**
     * Path A is event-driven: nothing fires when a daily budget crosses mid-session, so a
     * user parked inside a time-limited app would only hit the block on their next app
     * switch (Path B's 1s poll catches it when that path is active instead). Arm a one-shot
     * re-check for the moment the remaining budget runs out (+ slack for UsageStats bucket
     * lag). Battery-honest: at most one pending job, only while a limited app is in front,
     * cancelled by the next foreground decision. If the bucket still reads under the budget
     * at re-check time, the Allow path re-arms with the fresh remainder — a self-correcting
     * loop whose steps are never shorter than the true remaining time.
     */
    private fun armTimeLimitRecheck(pkg: String, limitMin: Int) {
        val remaining = limitMin * 60_000L - UsageQuery.dailyForegroundMillis(this, pkg)
        if (remaining <= 0L) return // next event blocks anyway; don't spin
        timeLimitRecheck = serviceScope?.launch {
            delay(remaining + RECHECK_SLACK_MS)
            reevaluateForeground()
        }
    }

    // MARK: Enforcement surface (accessibility overlay window)

    private inner class AccessibilityOverlayHost : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View): Boolean {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                0,
                PixelFormat.OPAQUE,
            )
            return runCatching { wm.addView(view, params) }.isSuccess
        }

        override fun detach(view: View) {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
        }

        override fun goHome() {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun labelFor(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // MARK: URL reading

    /**
     * Address-bar text for [pkg], or null when this browser exposes none. Only [pkg]'s own ids
     * are tried — a Chrome id can never resolve inside Firefox. Node lookups run against a
     * window that may go stale mid-read, so they are allowed to fail rather than kill the service.
     */
    private fun readUrl(root: AccessibilityNodeInfo?, pkg: String): String? {
        root ?: return null
        for (id in BrowserAddressBars.urlBarViewIds(pkg)) {
            val text = runCatching {
                root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.text?.toString()
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        coordinator?.hideIfShown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        coordinator?.hideIfShown()
        timeLimitRecheck?.cancel()
        timeLimitRecheck = null
        serviceScope?.cancel()
        serviceScope = null
        coordinator = null
        super.onDestroy()
    }

    private companion object {
        /** Slack past the computed budget expiry — UsageStats buckets update coarsely. */
        const val RECHECK_SLACK_MS = 5_000L
    }
}
