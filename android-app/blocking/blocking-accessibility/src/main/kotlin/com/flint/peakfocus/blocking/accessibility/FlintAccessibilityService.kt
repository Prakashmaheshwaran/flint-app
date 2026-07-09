package com.flint.peakfocus.blocking.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.BlockScreenCoordinator
import com.flint.peakfocus.blocking.overlay.openLimitBlockCause
import com.flint.peakfocus.blocking.overlay.ruleBlockCause
import com.flint.peakfocus.blocking.overlay.timeLimitBlockCause
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.OpenLimitDecision
import com.flint.peakfocus.core.model.Verdict
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
 * Decision order per foreground change (existing checks preserved, new ones layered in):
 *  0. active break/pass exemption → stand down;
 *  1. daily Time Limit (legacy synchronous LimitStore + UsageQuery — unchanged);
 *  2. rule verdict from [BlockDecisionEngine] (sessions/schedules/allow-list/websites — unchanged);
 *  3. only if allowed: Open Limits (counts the open, blocks past the quota).
 *
 * Compliance: NOT an accessibility tool; the in-app consent flow (feature-onboarding) must run
 * before the user enables this. On connect it loads the persisted blocklist.
 */
class FlintAccessibilityService : AccessibilityService() {

    private val engine = BlockDecisionEngine()
    private var serviceScope: CoroutineScope? = null
    private var coordinator: BlockScreenCoordinator? = null

    /** Last package seen in the foreground (Flint + system surfaces included — the open-limit
     *  policy interprets those transitions itself, e.g. shield bounces are not re-opens). */
    @Volatile private var lastForegroundPackage: String? = null

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
        if (pkg == packageName) return // our own overlay/UI
        evaluateForeground(previous, pkg)
    }

    /** Re-run the decision pipeline for whatever is in front (used after exemption expiry). */
    private fun reevaluateForeground() {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?: lastForegroundPackage
            ?: return
        if (pkg == packageName) return
        // previous == pkg on purpose: a re-check is not a new "open" of the app.
        evaluateForeground(previousPackage = pkg, pkg = pkg)
    }

    private fun evaluateForeground(previousPackage: String?, pkg: String) {
        val coordinator = coordinator ?: return // events never precede onServiceConnected
        val now = System.currentTimeMillis()
        val utcOffset = TimeZone.getDefault().getOffset(now).toLong()

        // 0) An active break / Emergency Pass window: enforcement stands down until it ends.
        if (coordinator.isExempt(pkg)) {
            coordinator.hideIfShown()
            return
        }

        // 1) Time Limit: blocked once today's foreground time reaches the app's daily budget.
        //    (Legacy synchronous stores — unchanged. TimeLimit carries no tier, so the
        //    store-wide default break level applies.)
        val limitMin = LimitStore(this).limitMinutes(pkg)
        if (limitMin != null && UsageQuery.dailyForegroundMillis(this, pkg) >= limitMin * 60_000L) {
            coordinator.showBlocked(
                pkg,
                labelFor(pkg),
                timeLimitBlockCause(coordinator.currentDefaultBreakLevel(), now, utcOffset),
            )
            return
        }

        // 2) Rule verdict (manual sessions, schedules, allow-list mode, websites) — unchanged.
        val url = if (pkg in BROWSER_PACKAGES) readUrl(rootInActiveWindow) else null
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Calendar.DAY_OF_WEEK (1=Sun…7=Sat) → the engine's ISO numbering (1=Mon…7=Sun) via the
        // one shared, tested converter — otherwise a "Weekdays" rule blocks Sunday and skips Friday.
        val weekday = IsoWeekday.fromCalendar(cal.get(Calendar.DAY_OF_WEEK))
        when (val verdict = engine.decide(pkg, url, ActiveRulesHolder.rules, packageName, nowMin, weekday)) {
            is Verdict.Block -> {
                val rule = ActiveRulesHolder.rules.firstOrNull { it.id == verdict.ruleId }
                coordinator.showBlocked(pkg, labelFor(pkg), ruleBlockCause(rule, now, utcOffset))
            }
            Verdict.Allow -> {
                // 3) Open Limits — only for apps the rules let through, so a shielded app
                //    never records an open you didn't actually get.
                when (val decision = coordinator.onForegroundChanged(previousPackage, pkg)) {
                    is OpenLimitDecision.Blocked -> coordinator.showBlocked(
                        pkg,
                        labelFor(pkg),
                        openLimitBlockCause(decision.limit, now, utcOffset),
                    )
                    is OpenLimitDecision.Allowed -> coordinator.hideIfShown()
                }
            }
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

    private fun readUrl(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        for (id in URL_BAR_IDS) {
            val text = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.text?.toString()
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
        serviceScope?.cancel()
        serviceScope = null
        coordinator = null
        super.onDestroy()
    }

    private companion object {
        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.opera.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
        )
        val URL_BAR_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.brave.browser:id/url_bar",
        )
    }
}
