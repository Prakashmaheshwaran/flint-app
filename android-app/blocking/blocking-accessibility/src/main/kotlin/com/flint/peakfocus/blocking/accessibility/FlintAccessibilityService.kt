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
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.engine.UninstallGuard
import com.flint.peakfocus.blocking.overlay.BlockScreenCoordinator
import com.flint.peakfocus.blocking.overlay.NeverBlockSurfaces
import com.flint.peakfocus.blocking.overlay.openLimitBlockCause
import com.flint.peakfocus.blocking.overlay.ruleBlockCause
import com.flint.peakfocus.blocking.overlay.timeLimitBlockCause
import com.flint.peakfocus.blocking.overlay.uninstallGuardBlockCause
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
 * Decision order per foreground change (existing checks preserved, new ones layered in):
 *  0. [UninstallGuard] — while a Hardcore window is active, the system uninstaller and
 *     Settings-pages-about-Flint are shielded (anti-bypass). Deliberately ahead of the
 *     break/pass stand-down: an exemption granted on some *other* rule must not open the
 *     uninstall window, and Hardcore itself never grants breaks;
 *  1. active break/pass exemption → stand down;
 *  2. daily Time Limit (legacy synchronous LimitStore + UsageQuery — unchanged);
 *  3. rule verdict from [BlockDecisionEngine] (sessions/schedules/allow-list/websites — unchanged);
 *  4. only if allowed: Open Limits (counts the open, blocks past the quota).
 *
 * Compliance: NOT an accessibility tool; the in-app consent flow (feature-onboarding) must run
 * before the user enables this. On connect it loads the persisted blocklist.
 */
class FlintAccessibilityService : AccessibilityService() {

    private val engine = BlockDecisionEngine()
    private val uninstallGuard = UninstallGuard()
    private var serviceScope: CoroutineScope? = null
    private var coordinator: BlockScreenCoordinator? = null

    /** One-shot re-check armed while a time-limited app sits in front (see [armTimeLimitRecheck]). */
    private var timeLimitRecheck: Job? = null

    /** Runtime-resolved default launcher + dialer — never shielded (see [NeverBlockSurfaces]). */
    @Volatile private var neverBlock: Set<String> = emptySet()

    /** Flint's own launcher label — what the uninstall guard scans Settings text for. */
    private val selfLabel: String by lazy { labelFor(packageName) }

    /** Last package seen in the foreground (Flint + system surfaces included — the open-limit
     *  policy interprets those transitions itself, e.g. shield bounces are not re-opens). */
    @Volatile private var lastForegroundPackage: String? = null

    /** Class name from the last window event — [reevaluateForeground] reuses it because a
     *  root node's own className is a View class ("android.widget.FrameLayout"), never the
     *  Activity, and would blind the uninstall guard's class-hint gate. */
    @Volatile private var lastForegroundClassName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        neverBlock = NeverBlockSurfaces.resolve(this)
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
        evaluateForeground(previous, pkg, event.className?.toString())
    }

    /** Re-run the decision pipeline for whatever is in front (used after exemption expiry). */
    private fun reevaluateForeground() {
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val pkg = rootPkg
            ?: lastForegroundPackage
            ?: return
        if (pkg == packageName) return
        // The last event's class name still describes this window as long as the package
        // hasn't changed underneath us; a mismatch means we know nothing about the class.
        val className = lastForegroundClassName.takeIf { rootPkg == null || rootPkg == lastForegroundPackage }
        // previous == pkg on purpose: a re-check is not a new "open" of the app.
        evaluateForeground(previousPackage = pkg, pkg = pkg, windowClassName = className)
    }

    private fun evaluateForeground(previousPackage: String?, pkg: String, windowClassName: String?) {
        val coordinator = coordinator ?: return // events never precede onServiceConnected
        timeLimitRecheck?.cancel() // every fresh decision invalidates a pending budget re-check
        // Default launcher/dialer are never shielded (allow-list windows included) — blocking
        // the launcher loops the Home button into the shield; the dialer is emergency calling.
        if (pkg in neverBlock) {
            coordinator.hideIfShown()
            return
        }
        val now = System.currentTimeMillis()
        val utcOffset = TimeZone.getDefault().getOffset(now).toLong()
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Schedule.daysOfWeek is ISO-numbered (1=Mon…7=Sun — core-model's documented contract);
        // Calendar.DAY_OF_WEEK is 1=Sun…7=Sat. Convert before handing to the engine/guard.
        val weekday = IsoWeekday.fromCalendar(cal.get(Calendar.DAY_OF_WEEK))

        // 0) Uninstall guard — checked ahead of the break/pass stand-down so an exemption
        //    granted on some other rule can never open the uninstall window (see class KDoc).
        //    The isArmed pre-gate keeps the node-text sweep (binder round-trips) off the
        //    event thread for every user without an active Hardcore window.
        if (uninstallGuard.isCandidateSurface(pkg) &&
            uninstallGuard.isArmed(ActiveRulesHolder.rules, nowMin, weekday, now)
        ) {
            val surface = ForegroundSurface(
                packageName = pkg,
                className = windowClassName,
                visibleText = if (uninstallGuard.needsWindowText(pkg)) collectVisibleText() else emptyList(),
            )
            val armingRule =
                uninstallGuard.judge(surface, packageName, selfLabel, ActiveRulesHolder.rules, nowMin, weekday, now)
            if (armingRule != null) {
                coordinator.showBlocked(pkg, labelFor(pkg), uninstallGuardBlockCause(armingRule, now, utcOffset))
                return
            }
        }

        // 1) An active break / Emergency Pass window: enforcement stands down until it ends.
        if (coordinator.isExempt(pkg)) {
            coordinator.hideIfShown()
            return
        }

        // 2) Time Limit: blocked once today's foreground time reaches the app's daily budget —
        //    the stricter of the legacy store and the Limit editor's DataStore limits (read via
        //    the coordinator's snapshot; they were authored-but-never-enforced before that).
        //    TimeLimit carries no tier, so the store-wide default break level applies.
        val limitMin = listOfNotNull(
            LimitStore(this).limitMinutes(pkg),
            coordinator.timeLimitMinutes(pkg),
        ).minOrNull()
        if (limitMin != null && UsageQuery.dailyForegroundMillis(this, pkg) >= limitMin * 60_000L) {
            coordinator.showBlocked(
                pkg,
                labelFor(pkg),
                timeLimitBlockCause(coordinator.currentDefaultBreakLevel(), now, utcOffset),
            )
            return
        }

        // 3) Rule verdict (manual sessions, schedules, allow-list mode, websites) — unchanged.
        val url = if (pkg in BROWSER_PACKAGES) readUrl(rootInActiveWindow) else null
        when (val verdict = engine.decide(pkg, url, ActiveRulesHolder.rules, packageName, nowMin, weekday, now)) {
            is Verdict.Block -> {
                val rule = ActiveRulesHolder.rules.firstOrNull { it.id == verdict.ruleId }
                coordinator.showBlocked(pkg, labelFor(pkg), ruleBlockCause(rule, now, utcOffset))
            }
            Verdict.Allow -> {
                // 4) Open Limits — only for apps the rules let through, so a shielded app
                //    never records an open you didn't actually get.
                when (val decision = coordinator.onForegroundChanged(previousPackage, pkg)) {
                    is OpenLimitDecision.Blocked -> coordinator.showBlocked(
                        pkg,
                        labelFor(pkg),
                        openLimitBlockCause(decision.limit, now, utcOffset),
                    )
                    is OpenLimitDecision.Allowed -> {
                        coordinator.hideIfShown()
                        if (limitMin != null) armTimeLimitRecheck(pkg, limitMin)
                    }
                }
            }
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

    // MARK: Window-text collection (uninstall guard)

    /**
     * Best-effort sweep of the text visible in the active window — node text plus content
     * descriptions, breadth-first, capped at [MAX_TEXT_NODES] so a pathological Settings
     * hierarchy can't stall the event thread. Only called when the guard is armed AND says
     * the surface needs text ([UninstallGuard.needsWindowText]). An empty result makes the
     * guard judge on the class name alone — fail-closed for the protected screen families,
     * open elsewhere ([UninstallGuard.isProtectedSettingsSurface]'s documented contract).
     */
    private fun collectVisibleText(): List<String> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
        val texts = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TEXT_NODES) {
            val node = queue.removeFirst()
            visited++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
            for (i in 0 until node.childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
            }
        }
        return texts
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
        timeLimitRecheck?.cancel()
        timeLimitRecheck = null
        serviceScope?.cancel()
        serviceScope = null
        coordinator = null
        super.onDestroy()
    }

    private companion object {
        /** Node-visit budget for [collectVisibleText] — plenty for any real Settings page. */
        const val MAX_TEXT_NODES = 200

        /** Slack past the computed budget expiry — UsageStats buckets update coarsely. */
        const val RECHECK_SLACK_MS = 5_000L

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
