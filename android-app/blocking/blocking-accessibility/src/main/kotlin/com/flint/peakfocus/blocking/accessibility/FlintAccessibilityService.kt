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
import android.view.accessibility.AccessibilityNodeInfo
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
import com.flint.peakfocus.blocking.overlay.TrustedTimeLimitReader
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
    private val timeReader by lazy { TrustedTimeLimitReader.create(this) }

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
    private var contentReadRequested = false
    private var websiteRulesActive = false
    private var overlayAttached = false
    private var overlayWindowId: Int? = null
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
            read = ::onUrlRead,
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
            if (!BrowserAddressBars.isBrowser(pkg) || pkg != lastForegroundPackage) return
            if (browserWindow != BrowserWindow(pkg, event.windowId)) return
            val active = domainsActive()
            if (active) refreshWebsiteCoverage(pkg, active, contentEvent = true)
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
            } else if (overlayAttached) {
                val top = rootIdentity()
                if (top != null && top.packageName == packageName && top.windowId == event.windowId) {
                    overlayWindowId = event.windowId
                }
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
        // Activity events identify application windows without guessing per-browser class
        // names. Root fallback also works after restart; absent bars are informational.
        if (BrowserAddressBars.isBrowser(pkg) && className != null && runCatching {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(ComponentName(pkg, className), 0)
            }.isSuccess) mainBrowserWindow = window
        evaluateForeground(previous, pkg)
    }

    /** Re-run for the current app after a boundary, publication, boot or clock signal. */
    private fun reevaluateForeground() = reconcileForeground(refreshUrl = true)

    /** Content callbacks must use the live root without scheduling a second URL read. */
    private fun reconcileForeground(refreshUrl: Boolean, activeDomains: Boolean? = null) {
        val live = rootIdentity()
        if (!refreshUrl && live == null) return
        val rootPkg = live?.packageName
        val ownOverlay = live != null && rootPkg == packageName && overlayAttached &&
            (overlayWindowId == null || overlayWindowId == live.windowId)
        if (ownOverlay && overlayWindowId == null) overlayWindowId = live?.windowId
        val pkg = rootPkg?.takeUnless { ownOverlay }
            ?: lastForegroundPackage
            ?: return
        if (pkg == packageName) {
            lastForegroundPackage = pkg
            clearBrowserWindow()
            WebsiteCoverageHealth.foreground(null, activeDomains ?: domainsActive(), false)
            evaluationJob?.cancel()
            coordinator?.hideIfShown()
            armFloorRecovery()
            return
        }
        val contextChanged = pkg != lastForegroundPackage || (live != null && rootPkg == pkg &&
            BrowserAddressBars.isBrowser(pkg) && live != browserWindow)
        if (contextChanged) {
            clearBrowserWindow()
            if (rootPkg == pkg && BrowserAddressBars.isBrowser(pkg)) {
                browserWindow = live
                // A foreground root is sufficient for informational Unconfirmed coverage.
                // Degraded additionally requires a prior URL read and visible address node.
                mainBrowserWindow = browserWindow
            }
            if (!refreshUrl) WebsiteCoverageHealth.foreground(
                browserWindow ?: BrowserWindow(pkg, -1), activeDomains ?: domainsActive(), false,
            )
        }
        lastForegroundPackage = pkg
        // A recheck is not a new open; the shared policy keeps the current visit intact.
        evaluateForeground(previousPackage = pkg, pkg = pkg, refreshUrl = refreshUrl, activeDomains = activeDomains)
    }

    private fun evaluateForeground(
        previousPackage: String?, pkg: String, refreshUrl: Boolean = true, activeDomains: Boolean? = null,
    ) {
        val coordinator = coordinator ?: return // events never precede onServiceConnected
        val active = activeDomains ?: domainsActive()
        if (refreshUrl) refreshWebsiteCoverage(pkg, active)
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

            // Both paths use the same saved period and committed floor. Legacy budgets stay
            // a stricter fallback; a failed query never becomes artificial MAX exhaustion.
            val budget = withContext(Dispatchers.IO) {
                listOfNotNull(LimitStore(this@FlintAccessibilityService).limitMinutes(pkg),
                    coordinator.timeLimitMinutes(pkg)).minOrNull()?.let { timeReader.read(pkg, it) }
            }
            val remaining = budget?.remainingMs
            lastBudgetDeadlineMs = remaining?.let { now + it.coerceAtLeast(0L) }

            // Inspect rules even when a weaker time limit or an old ordinary break applies.
            val url = lastForegroundUrl.takeIf { BrowserAddressBars.isBrowser(pkg) && active }
            val verdict = engine.decide(pkg, url, ActiveRulesHolder.rules, packageName, nowMin, weekday, now)
            val ruleCause = (verdict as? Verdict.Block)?.let {
                ruleBlockCause(ActiveRulesHolder.rules.firstOrNull { rule -> rule.id == it.ruleId }, now, utcOffset)
            }
            val cause = coordinator.evaluateBlockCause(previousPackage, pkg, ruleCause,
                timeLimitSpent = false, foregroundUrl = url, timeBudget = budget)
            if (cause != null) {
                coordinator.showBlocked(pkg, labelFor(pkg), cause)
            } else {
                coordinator.hideIfShown()
            }
            // A configured but unmeasured budget retains a recovery check without blocking.
            val boundaryBudget = remaining ?: if (budget != null) UNMEASURED_BUDGET_BOUNDARY_MS else null
            armBoundary(pkg, now, boundaryBudget,
                retryUsage = budget != null && remaining == null,
                resetRemainingMs = budget?.resetRemainingMs(com.flint.peakfocus.core.datastore.ProtectionClock.now()))
        }
    }

    private fun armBoundary(pkg: String, now: Long, remainingBudgetMs: Long?, retryUsage: Boolean = false,
        resetRemainingMs: Long? = null) {
        val interactive = isInteractive()
        boundaryTimer?.arm(NextEvaluationBoundary.next(
            now, ZoneId.systemDefault(), pkg, ActiveRulesHolder.rules, remainingBudgetMs,
            interactive = interactive,
            // Failed reads or missing access recover without an app switch, only while awake.
            boundedRecovery = retryUsage && interactive,
            budgetResetRemainingMs = resetRemainingMs,
            budgetUsesSavedReset = true,
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
        private var mountedView: View? = null
        private var recoveryFocusYielded: Boolean? = false
        override fun attach(view: View): Boolean {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                0,
                PixelFormat.OPAQUE,
            )
            return runCatching { wm.addView(view, params) }.isSuccess.also {
                if (it) { overlayAttached = true; mountedView = view; recoveryFocusYielded = false }
            }
        }

        override fun detach(view: View) {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
            overlayAttached = false
            overlayWindowId = null
            if (mountedView === view) { mountedView = null; recoveryFocusYielded = false }
        }

        override fun setRecoveryHandoffFocus(enabled: Boolean): Boolean {
            val view = mountedView ?: return !enabled
            val old = view.layoutParams as? WindowManager.LayoutParams ?: return false
            val locallyYielded = old.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
            if (recoveryFocusYielded == enabled && locallyYielded == enabled) return true
            val flags = if (enabled) old.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                else old.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            val next = WindowManager.LayoutParams().apply { copyFrom(old); this.flags = flags }
            return runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(view, next) }
                .onSuccess { recoveryFocusYielded = enabled }
                .onFailure { recoveryFocusYielded = null }.isSuccess
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

    private fun refreshWebsiteCoverage(pkg: String, active: Boolean, contentEvent: Boolean = false) {
        websiteRulesActive = active
        // Package-specific intent resolution, only on foreground reconciliations with website
        // rules. No inventory enumeration or content inspection of unsupported applications.
        val handler = active && !BrowserAddressBars.isBrowser(pkg) && isHttpHandler(pkg)
        WebsiteCoverageHealth.foreground(
            browserWindow ?: BrowserWindow(pkg, -1), active, handler,
            handlerLabel = if (handler) labelFor(pkg).takeUnless { it == pkg } ?: "This app" else null,
            isBrowserHandler = handler && resolvesAsBrowser(pkg),
        )
        if (!active || !BrowserAddressBars.isBrowser(pkg)) {
            urlThrottle?.cancel()
            lastForegroundUrl = null
            return
        }
        val window = browserWindow ?: run {
            val live = rootIdentity() ?: return
            if (live.packageName != pkg) return
            live.also { browserWindow = it; mainBrowserWindow = it }
        }
        WebsiteCoverageHealth.foreground(window, true, false)
        contentReadRequested = contentEvent
        try { urlThrottle?.request(window) } finally { contentReadRequested = false }
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

    private fun resolvesAsBrowser(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER).setPackage(pkg), 0,
        ).any { it.activityInfo?.packageName == pkg }
    }.getOrDefault(false)

    private enum class UrlReadOutcome { CHANGED, UNCHANGED, FOREGROUND_CHANGED }

    private fun onUrlRead(window: BrowserWindow, trailing: Boolean) {
        val active = if (trailing) domainsActive() else websiteRulesActive
        val outcome = readBrowserWindow(window, active)
        // Scrolling still updates coverage and the throttle, but never re-queries usage or
        // runs the full coordinator while the URL is unchanged. Rule/deadline checks are
        // independent. A foreign root must reconcile even if the cached URL did not change.
        if ((trailing || contentReadRequested) && outcome != UrlReadOutcome.UNCHANGED) {
            reconcileForeground(refreshUrl = false, activeDomains = active)
        }
    }

    private fun rootIdentity(): BrowserWindow? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        return try {
            runCatching {
                root.packageName?.toString()?.let { BrowserWindow(it, root.windowId) }
            }.getOrNull()
        } finally { recycle(root) }
    }

    private fun recycle(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }

    /** Exactly one view-id lookup per throttle window; alternate legacy IDs rotate on misses. */
    private fun readBrowserWindow(window: BrowserWindow, active: Boolean): UrlReadOutcome {
        if (browserWindow != window || lastForegroundPackage != window.packageName || !active) return UrlReadOutcome.UNCHANGED
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return UrlReadOutcome.UNCHANGED
        return try {
            // A foreign root never supplies a URL or counts as a miss. Reconcile its live
            // identity before another decision; our own accessibility overlay keeps its target.
            if (root.packageName?.toString() != window.packageName || root.windowId != window.windowId) {
                return if (root.packageName?.toString() == packageName)
                    UrlReadOutcome.UNCHANGED else UrlReadOutcome.FOREGROUND_CHANGED
            }
            val ids = BrowserAddressBars.urlBarViewIds(window.packageName)
            if (ids.isEmpty()) return UrlReadOutcome.UNCHANGED
            val id = ids[urlBarIndex % ids.size]
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
            val text = runCatching { nodes.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() } }.getOrNull()
            val nodePresent = nodes.any { runCatching { it.isVisibleToUser }.getOrDefault(false) }
            nodes.forEach(::recycle)
            val changed = text != lastForegroundUrl
            lastForegroundUrl = text
            if (text == null) urlBarIndex = (urlBarIndex + 1) % ids.size
            if (text != null) mainBrowserWindow = window // startup/root fallback needs no activity event
            WebsiteCoverageHealth.addressBarRead(window, text != null, window == mainBrowserWindow, System.currentTimeMillis(), nodePresent)
            if (changed) UrlReadOutcome.CHANGED else UrlReadOutcome.UNCHANGED
        } finally { recycle(root) }
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
        overlayAttached = false
        overlayWindowId = null
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
