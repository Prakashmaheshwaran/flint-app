package com.flint.peakfocus.blocking.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.ForegroundSurface
import com.flint.peakfocus.blocking.engine.UninstallGuard
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
 *  0.5. [UninstallGuard] (anti-bypass): while a HARDCORE window is active, shield the system
 *       surfaces that uninstall Flint, force-stop it, or disable this service — the exemption
 *       check above keeps the Emergency Pass working as the sanctioned exit;
 *  1. daily Time Limit (legacy synchronous LimitStore + UsageQuery — unchanged);
 *  2. rule verdict from [BlockDecisionEngine] (sessions/schedules/allow-list/websites — unchanged);
 *  3. only if allowed: Open Limits (counts the open, blocks past the quota).
 *
 * Compliance: NOT an accessibility tool; the in-app consent flow (feature-onboarding) must run
 * before the user enables this. On connect it loads the persisted blocklist.
 */
class FlintAccessibilityService : AccessibilityService() {

    private val engine = BlockDecisionEngine()
    private val uninstallGuard = UninstallGuard()
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
        evaluateForeground(previous, pkg, event.className?.toString())
    }

    /** Re-run the decision pipeline for whatever is in front (used after exemption expiry). */
    private fun reevaluateForeground() {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?: lastForegroundPackage
            ?: return
        if (pkg == packageName) return
        // previous == pkg on purpose: a re-check is not a new "open" of the app.
        // No window event here, so no trustworthy class name — the guard's text gate still works.
        evaluateForeground(previousPackage = pkg, pkg = pkg, windowClassName = null)
    }

    private fun evaluateForeground(previousPackage: String?, pkg: String, windowClassName: String? = null) {
        val coordinator = coordinator ?: return // events never precede onServiceConnected
        val now = System.currentTimeMillis()
        val utcOffset = TimeZone.getDefault().getOffset(now).toLong()
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Schedule.daysOfWeek is ISO-numbered (1=Mon…7=Sun — core-model's documented contract);
        // Calendar.DAY_OF_WEEK is 1=Sun…7=Sat. Convert before handing to the engine.
        val weekday = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

        // 0) An active break / Emergency Pass window: enforcement stands down until it ends.
        //    Checked before the uninstall guard on purpose: a spent Emergency Pass is the
        //    sanctioned exit from HARDCORE, and it must open these surfaces too.
        if (coordinator.isExempt(pkg)) {
            coordinator.hideIfShown()
            return
        }

        // 0.5) Uninstall guard (anti-bypass): while a HARDCORE window is active, the surfaces
        //      that uninstall Flint / force-stop it / disable this service are shielded. The
        //      block cause is the arming rule's own, so the screen shows the real session.
        if (uninstallGuard.isCandidateSurface(pkg)) {
            val surface = ForegroundSurface(
                packageName = pkg,
                className = windowClassName,
                visibleText = if (uninstallGuard.needsWindowText(pkg)) {
                    collectVisibleText(rootInActiveWindow)
                } else {
                    emptyList()
                },
            )
            val armingRule = uninstallGuard.judge(
                surface, packageName, labelFor(packageName), ActiveRulesHolder.rules, nowMin, weekday,
            )
            if (armingRule != null) {
                coordinator.showBlocked(pkg, labelFor(pkg), ruleBlockCause(armingRule, now, utcOffset))
                return
            }
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

    // MARK: window text reading (uninstall guard)

    /**
     * Visible text + content descriptions in the active window, breadth-first and bounded
     * ([MAX_TEXT_NODES]) so the guard can never turn a window event into an unbounded tree
     * walk. Only called for [UninstallGuard.needsWindowText] surfaces. A node that throws
     * (recycled mid-walk) just ends the sweep — the guard fails toward the text collected so
     * far, and the next window event retries.
     */
    private fun collectVisibleText(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()
        val out = ArrayList<String>()
        runCatching {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_TEXT_NODES) {
                val node = queue.removeFirst()
                visited++
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::addLast)
                }
            }
        }
        return out
    }

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
        /** Node budget for [collectVisibleText] — plenty for a Settings page, bounded for anything. */
        const val MAX_TEXT_NODES = 120

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
