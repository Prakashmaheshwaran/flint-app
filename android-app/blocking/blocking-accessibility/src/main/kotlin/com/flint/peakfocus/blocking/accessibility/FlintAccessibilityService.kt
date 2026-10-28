package com.flint.peakfocus.blocking.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.BrowserWindow
import com.flint.peakfocus.blocking.engine.UrlReadThrottle
import com.flint.peakfocus.blocking.engine.WebsiteRulePolicy
import com.flint.peakfocus.blocking.engine.EvaluationTimer
import com.flint.peakfocus.blocking.engine.NextEvaluationBoundary
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.BlockScreenCoordinator
import com.flint.peakfocus.blocking.overlay.ruleBlockCause
import com.flint.peakfocus.blocking.resilience.WebsiteCoverageHealth
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.Verdict
import com.flint.peakfocus.core.model.EnforcementSignals
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** One independent deadline for schedules, expiry, budgets and local day rollover. */
    private var boundaryTimer: EvaluationTimer? = null
    private var stopSignals: (() -> Unit)? = null
    private var stopRules: (() -> Unit)? = null
    private var lastForegroundUrl: String? = null
    private var evaluationJob: Job? = null
    private var lastNonFloorPackage: String? = null
    private var lastBudgetDeadlineMs: Long? = null
    private var screenReceiverRegistered = false
    private var urlThrottle: UrlReadThrottle<BrowserWindow>? = null
    private var browserWindow: BrowserWindow? = null
    private var mainBrowserWindow: BrowserWindow? = null
    private var urlBarIndex = 0
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { reevaluateForeground() }
    }

    /** Last package seen in the foreground (Flint + system surfaces included — the open-limit
     *  policy interprets those transitions itself, e.g. shield bounces are not re-opens). */
    @Volatile private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        releaseDetection()
        BlocklistStore(this).load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        urlThrottle = UrlReadThrottle(
            nowMs = SystemClock::uptimeMillis,
            post = { delayMs, callback ->
                val job = scope.launch { delay(delayMs); callback() }
                ({ job.cancel() })
            },
            read = { window, trailing ->
                readBrowserWindow(window)
                if (trailing) evaluateForeground(window.packageName, window.packageName, refreshUrl = false)
            },
        )
        boundaryTimer = EvaluationTimer(
            nowMs = System::currentTimeMillis,
            post = { delayMs, callback ->
                val job = scope.launch { delay(delayMs); callback() }
                ({ job.cancel() })
            },
            evaluate = ::reevaluateForeground,
        )
        coordinator = BlockScreenCoordinator(
            context = this,
            scope = scope,
            surfaceHost = AccessibilityOverlayHost(),
            // Event-driven path: when a break/pass runs out, re-check the app the user is
            // still sitting in — no window event fires just because time passed.
            onExemptionExpired = { reevaluateForeground() },
            onEnforcementChanged = { scope.launch { reevaluateForeground() } },
        )
        stopRules = ActiveRulesHolder.subscribe { scope.launch { reevaluateForeground() } }
        stopSignals = EnforcementSignals.detectors.subscribe {
            scope.launch {
                UsageQuery.invalidate()
                reevaluateForeground()
            }
        }
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        // Main-thread listeners stand Path B down synchronously before this path can shield.
        EnforcementSignals.detectors.connected(this)
        reevaluateForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Filter before root access, package queries, budget queries or any evaluation.
            if (!BrowserAddressBars.isBrowser(pkg) || pkg != lastForegroundPackage || !domainsActive()) return
            if (browserWindow != BrowserWindow(pkg, event.windowId)) return
            if (refreshWebsiteCoverage(pkg)) evaluateForeground(pkg, pkg, refreshUrl = false)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val previous = lastForegroundPackage
        val className = event.className?.toString()
        if (pkg == packageName) {
            // Attaching our overlay must not replace its underlying foreground target. A real
            // MainActivity visit is different: stand the shield down, retaining a bounded
            // recovery check in case the underlying app returns without a fresh window event.
            if (className == "$packageName.MainActivity") {
                lastForegroundPackage = pkg
                lastForegroundUrl = null
                clearBrowserWindow()
                WebsiteCoverageHealth.foreground(null, domainsActive(), false)
                boundaryTimer?.cancel()
                evaluationJob?.cancel()
                coordinator?.hideIfShown()
                armFloorRecovery()
            }
            return
        }
        if (pkg != previous) lastForegroundUrl = null
        lastForegroundPackage = pkg
        val window = BrowserWindow(pkg, event.windowId)
        if (window != browserWindow) {
            clearBrowserWindow()
            if (BrowserAddressBars.isBrowser(pkg)) browserWindow = window
        }
        // Activity-backed window events identify the main application window; dialog/view
        // events never contribute address-bar misses. No activity-name guesses per browser.
        if (BrowserAddressBars.isBrowser(pkg) && className != null && runCatching {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(ComponentName(pkg, className), 0)
            }.isSuccess) mainBrowserWindow = window
        evaluateForeground(previous, pkg)
    }

    /** Re-run for the current app after a boundary, publication, boot or clock signal. */
    private fun reevaluateForeground() {
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val pkg = rootPkg?.takeUnless { it == packageName }
            ?: lastForegroundPackage
            ?: return
        if (pkg == packageName) { evaluationJob?.cancel(); armFloorRecovery(); return }
        if (pkg != lastForegroundPackage) {
            clearBrowserWindow()
            lastForegroundUrl = null
        }
        lastForegroundPackage = pkg
        // A recheck is not a new open; the shared policy keeps the current visit intact.
        evaluateForeground(previousPackage = pkg, pkg = pkg)
    }

    private fun evaluateForeground(previousPackage: String?, pkg: String, refreshUrl: Boolean = true) {
        val coordinator = coordinator ?: return // events never precede onServiceConnected
        if (refreshUrl) refreshWebsiteCoverage(pkg)
        evaluationJob?.cancel()
        evaluationJob = serviceScope?.launch {
            boundaryTimer?.cancel() // each decision replaces the one pending deadline
            val now = System.currentTimeMillis()
            val utcOffset = TimeZone.getDefault().getOffset(now).toLong()
            val cal = Calendar.getInstance().apply { timeInMillis = now }
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
                armFloorRecovery()
                return@launch
            }
            if (pkg != lastNonFloorPackage) lastBudgetDeadlineMs = null
            lastNonFloorPackage = pkg

            // Time Limit: blocked once today's foreground time reaches the app's daily budget —
            //    the stricter of the legacy store and the Limit editor's DataStore limits (read via
            //    the coordinator's snapshot; they were authored-but-never-enforced before that).
            //    TimeLimit carries no tier, so the store-wide default break level applies.
            val limitMin = listOfNotNull(
                LimitStore(this@FlintAccessibilityService).limitMinutes(pkg),
                coordinator.timeLimitMinutes(pkg),
            ).minOrNull()
            // ADR-012: a configured zero/nonpositive budget is exhausted without measurement.
            val immediatelySpent = limitMin != null && limitMin <= 0
            val needsMeasurement = limitMin != null && limitMin > 0
            // Preserve a recovery deadline even if a floor window cancels this first IO read.
            lastBudgetDeadlineMs = limitMin?.let { lastBudgetDeadlineMs ?: now + it * 60_000L }
            // Cancellation on every newer decision prevents a suspended query from showing a
            // stale shield. The initial seven-day carry-in query never runs on the main thread.
            val used = if (needsMeasurement) withContext(Dispatchers.IO) {
                UsageQuery.dailyForegroundMillisIfGranted(this@FlintAccessibilityService, pkg, now)
            } else null
            // Missing Usage Access leaves positive budgets unmeasured, not exhausted.
            val remaining = if (immediatelySpent) 0L
                else used?.let { checkNotNull(limitMin) * 60_000L - it }
            lastBudgetDeadlineMs = remaining?.let { now + it.coerceAtLeast(0L) }
            val timeLimitSpent = immediatelySpent || (remaining != null && remaining <= 0L)

            // Inspect rules even when a weaker time limit or an old ordinary break applies.
            val url = lastForegroundUrl.takeIf { BrowserAddressBars.isBrowser(pkg) && domainsActive() }
            val verdict = engine.decide(pkg, url, ActiveRulesHolder.rules, packageName, nowMin, weekday, now)
            val ruleCause = (verdict as? Verdict.Block)?.let {
                ruleBlockCause(ActiveRulesHolder.rules.firstOrNull { rule -> rule.id == it.ruleId }, now, utcOffset)
            }
            val cause = coordinator.evaluateBlockCause(previousPackage, pkg, ruleCause, timeLimitSpent, url)
            if (cause != null) {
                coordinator.showBlocked(pkg, labelFor(pkg), cause)
            } else {
                coordinator.hideIfShown()
            }
            // A configured but unmeasured budget retains a recovery check without blocking.
            val boundaryBudget = remaining ?: if (needsMeasurement) UNMEASURED_BUDGET_BOUNDARY_MS else null
            armBoundary(pkg, now, boundaryBudget,
                retryUsage = needsMeasurement && (used == null || used == Long.MAX_VALUE))
        }
    }

    private fun armBoundary(pkg: String, now: Long, remainingBudgetMs: Long?, retryUsage: Boolean = false) {
        val interactive = isInteractive()
        boundaryTimer?.arm(NextEvaluationBoundary.next(
            now, ZoneId.systemDefault(), pkg, ActiveRulesHolder.rules, remainingBudgetMs,
            interactive = interactive,
            // Failed reads or missing access recover without an app switch, only while awake.
            boundedRecovery = retryUsage && interactive,
        ))
    }

    private fun armFloorRecovery() {
        val pkg = lastNonFloorPackage ?: return
        val now = System.currentTimeMillis()
        boundaryTimer?.arm(NextEvaluationBoundary.next(
            now, ZoneId.systemDefault(), pkg, ActiveRulesHolder.rules,
            lastBudgetDeadlineMs?.let { it - now },
            interactive = isInteractive(), boundedRecovery = true,
        ))
    }

    private fun isInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

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

    private fun domainsActive(): Boolean = WebsiteRulePolicy.hasActiveDomains(
        ActiveRulesHolder.rules, System.currentTimeMillis(), ZoneId.systemDefault(),
    )

    private fun refreshWebsiteCoverage(pkg: String): Boolean {
        val active = domainsActive()
        // Package-specific intent resolution, only on foreground reconciliations with website
        // rules. No inventory enumeration or content inspection of unsupported applications.
        val handler = active && !BrowserAddressBars.isBrowser(pkg) && isHttpHandler(pkg)
        WebsiteCoverageHealth.foreground(browserWindow ?: BrowserWindow(pkg, -1), active, handler)
        if (!active || !BrowserAddressBars.isBrowser(pkg)) {
            urlThrottle?.cancel()
            lastForegroundUrl = null
            return false
        }
        val window = browserWindow ?: run {
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
            if (root.packageName?.toString() != pkg) return false
            BrowserWindow(pkg, root.windowId).also { browserWindow = it }
        }
        WebsiteCoverageHealth.foreground(window, true, false)
        return urlThrottle?.request(window) ?: false
    }

    private fun isHttpHandler(pkg: String): Boolean = listOf("https", "http").any { scheme ->
        runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg), 0,
            ).any { it.activityInfo?.packageName == pkg }
        }.getOrDefault(false)
    }

    /** Exactly one view-id lookup per throttle window; alternate legacy IDs rotate on misses. */
    private fun readBrowserWindow(window: BrowserWindow) {
        if (browserWindow != window || lastForegroundPackage != window.packageName || !domainsActive()) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        // Flint's overlay and other windows must neither replace the cached target URL nor
        // count as browser misses. A stale queued callback cannot inspect a new foreground.
        if (root.packageName?.toString() != window.packageName || root.windowId != window.windowId) return
        val ids = BrowserAddressBars.urlBarViewIds(window.packageName)
        if (ids.isEmpty()) return
        val id = ids.getOrNull(urlBarIndex % ids.size) ?: return
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
        val text = runCatching { nodes.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() } }.getOrNull()
        @Suppress("DEPRECATION")
        nodes.forEach { runCatching { it.recycle() } }
        lastForegroundUrl = text
        if (text == null) urlBarIndex = (urlBarIndex + 1) % ids.size
        WebsiteCoverageHealth.addressBarRead(window, text != null, window == mainBrowserWindow, System.currentTimeMillis())
    }

    private fun clearBrowserWindow() {
        urlThrottle?.cancel()
        browserWindow = null
        mainBrowserWindow = null
        urlBarIndex = 0
        lastForegroundUrl = null
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        releaseDetection()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseDetection()
        super.onDestroy()
    }

    private fun releaseDetection() {
        clearBrowserWindow()
        urlThrottle = null
        WebsiteCoverageHealth.reset()
        stopSignals?.invoke()
        stopRules?.invoke()
        stopSignals = null
        stopRules = null
        boundaryTimer?.cancel()
        boundaryTimer = null
        evaluationJob?.cancel()
        evaluationJob = null
        if (screenReceiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
        coordinator?.hideIfShown()
        serviceScope?.cancel()
        serviceScope = null
        coordinator = null
        lastForegroundPackage = null
        lastForegroundUrl = null
        lastNonFloorPackage = null
        lastBudgetDeadlineMs = null
        // Hide Path A before the synchronous wake allows Path B to enforce.
        EnforcementSignals.detectors.disconnected(this)
    }

    private companion object {
        // Requests midnight/recovery without inventing a future exhaustion deadline.
        const val UNMEASURED_BUDGET_BOUNDARY_MS = 0L
    }
}
