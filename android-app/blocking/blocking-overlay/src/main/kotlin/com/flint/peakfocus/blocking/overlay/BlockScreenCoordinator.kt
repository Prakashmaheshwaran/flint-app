package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.BreakPolicy
import com.flint.peakfocus.blocking.engine.EmergencyPassPolicy
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.engine.OpenLimitPolicy
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.datastore.LimitsStore
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.BreakDecision
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision
import com.flint.peakfocus.core.model.TimeLimit
import com.flint.peakfocus.core.model.Verdict
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The host-side brain that turns pure engine decisions into actual enforcement. One instance
 * per detection path (the AccessibilityService owns one; `PathBBlockHandoff` owns the Path B
 * one), both funneling through the same policies, the same persisted state and the same
 * process-wide [BlockExemptions] — so a break granted on one path stands down the other.
 *
 * Responsibilities (the pieces `feature-blockscreen` explicitly leaves to its host):
 *  - **Safety floor** ([SafetyFloorGate], audit R2): the dialer, emergency surfaces, home apps,
 *    Settings/permission recovery and Flint are never shielded by a rule, allow-list, schedule,
 *    Sleep, Time Limit or Open Limit cause, on either path. Checked before any cause is built
 *    and again (with freshly resolved runtime surfaces) before a block is returned or mounted.
 *  - **Clock**: while a block surface is visible, re-render it every second with fresh
 *    [BlockScreenState] (countdowns tick, HARDER cooldowns drain, expired windows auto-hide).
 *  - **Break accounting** ([BreakPolicy]): EASY grants immediately; HARDER arms/settles the
 *    persisted friction request (reset only inside Flint — never offered here); HARDCORE
 *    never grants. A granted break = a temporary [BlockExemptions] grant + shield down.
 *  - **Emergency Pass** ([EmergencyPassPolicy]): explicit weekly consumption; the only way
 *    past HARDCORE. Never spent implicitly.
 *  - **Open Limits** ([OpenLimitPolicy]): counts real opens on foreground transitions and
 *    answers over-quota checks from in-memory snapshots (safe on the a11y event hot path).
 *
 * Persistence flows (core-datastore) are collected once into `@Volatile` snapshots so all
 * decisions here are synchronous — DataStore is never touched from `onAccessibilityEvent`
 * (its own documented requirement). Writes go through the caller-supplied [scope].
 *
 * Threading: detector entry points ([onForegroundChanged], [isExempt], [showBlocked],
 * [hideIfShown]) may be called from any thread; view work is marshalled to the main looper
 * internally. [requestBreak]/[requestEmergencyPass] compute synchronously against snapshots.
 */
class BlockScreenCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val surfaceHost: SurfaceHost,
    /**
     * Invoked when [surfaceHost] cannot attach an overlay (Path B without the
     * SYSTEM_ALERT_WINDOW grant) — e.g. launch the full-screen [BlockActivity] fallback.
     */
    private val onBlockSurfaceUnavailable: ((Context, String, String?, BlockCause) -> Unit)? = null,
    /**
     * Invoked (on [scope]) when a break/pass exemption granted here runs out, so an
     * event-driven detector can re-evaluate the foreground app without waiting for the next
     * window event. Poll-driven detectors can pass null — the next tick re-blocks anyway.
     */
    private val onExemptionExpired: ((String) -> Unit)? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    /** Test seam; production resolves the device's runtime floor surfaces lazily. */
    safetyFloorGate: SafetyFloorGate? = null,
) {

    /** How a path actually mounts the block surface. Implementations own the window type. */
    interface SurfaceHost {
        /** Add [view] full-screen over the blocked app. False = this surface is unavailable. */
        fun attach(view: View): Boolean

        /** Remove a previously attached [view]. Must tolerate double calls. */
        fun detach(view: View)

        /** Send the user home ("Go back"): GLOBAL_ACTION_HOME on Path A, HOME intent on B. */
        fun goHome()
    }

    private val appContext = context.applicationContext
    private val safetyFloor = safetyFloorGate ?: SafetyFloorGate(
        selfPackage = appContext.packageName,
        resolveRuntime = { NeverBlockSurfaces.resolve(appContext) },
        nowElapsedMs = SystemClock::elapsedRealtime,
    )
    private val focusStore: FocusStateStore = FlintPreferences.focusState(appContext)
    private val limitsStore: LimitsStore = FlintPreferences.limits(appContext)

    private val breakPolicy = BreakPolicy()
    private val passPolicy = EmergencyPassPolicy()
    private val openLimitPolicy = OpenLimitPolicy()
    private val ruleEngine = BlockDecisionEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Observation(val timeLimitSpent: Boolean, val epochDay: Long, val url: String?)
    private val observations = ConcurrentHashMap<String, Observation>()

    // Persisted-state snapshots (collected below). Volatile: written by the collectors on the
    // caller's scope, read synchronously from detector callbacks. Until the first emission the
    // documented store defaults apply (EASY, pass available, no counts) — a block landing in
    // that first-millisecond window errs toward the default, never toward a crash.
    @Volatile private var breakSession = BreakSessionState()
    @Volatile private var emergencyPass = EmergencyPassState()
    @Volatile private var openCounts = OpenCountState()
    @Volatile private var openLimits: List<OpenLimit> = emptyList()
    @Volatile private var timeLimits: List<TimeLimit> = emptyList()
    @Volatile private var defaultBreakLevel = BreakLevel.EASY

    /**
     * The package whose current open the Open-Limit machine already granted (see
     * [OpenLimitPolicy.onForegroundTransition]). Deliberately in-memory, like [BlockExemptions]:
     * a dead process forgets the grant and re-decides, so enforcement fails strict.
     */
    @Volatile private var openGrantedPackage: String? = null

    // The currently mounted surface. Mutated on the main thread only.
    private var shownView: BlockScreenHostView? = null
    private var shownCause: BlockCause? = null
    private var shownLabel: String? = null
    private var ticker: Job? = null
    @Volatile private var visiblePackage: String? = null

    init {
        scope.launch { focusStore.breakSession.collect { breakSession = it } }
        scope.launch { focusStore.emergencyPass.collect { emergencyPass = it } }
        scope.launch { focusStore.openCounts.collect { openCounts = it } }
        scope.launch { focusStore.defaultBreakLevel.collect { defaultBreakLevel = it } }
        scope.launch { limitsStore.openLimits.collect { openLimits = it } }
        scope.launch { limitsStore.timeLimits.collect { timeLimits = it } }
    }

    /**
     * True when [packageName] is on the enforcement safety floor (static OEM list, Flint, or the
     * device's resolved dialer/home apps). Detectors short-circuit on this before measuring
     * budgets; [evaluateBlockCause] and [currentBlockCause] apply it regardless.
     */
    fun isSafetyFloor(packageName: String): Boolean = safetyFloor.protects(packageName)

    /** Store-wide default break level — the tier for Time Limit blocks (no per-limit tier yet). */
    fun currentDefaultBreakLevel(): BreakLevel = defaultBreakLevel

    /**
     * The Limit editor's daily Time Limit for [packageName] (minutes), or null. Synchronous
     * snapshot read — safe on the a11y event hot path, same contract as the other snapshots.
     * Detectors take the stricter of this and the legacy `LimitStore` budget; before this
     * lookup existed, DataStore-authored Time Limits were persisted but never enforced.
     */
    fun timeLimitMinutes(packageName: String): Int? =
        timeLimits.firstOrNull { it.packageName == packageName }?.dailyMinutes

    /** Snapshot of the HARDER break bookkeeping (BlockActivity rebuilds its state from this). */
    fun currentBreakSession(): BreakSessionState = breakSession

    /** True while this week's Emergency Pass is unspent — drives the HARDCORE CTA's state. */
    fun isEmergencyPassAvailable(): Boolean {
        val now = nowEpochMs()
        return passPolicy.isAvailable(emergencyPass, now, utcOffsetMs(now))
    }

    /** Re-check protection before a surface hides in response to a previously issued grant. */
    fun isExempt(packageName: String, displayedCause: BlockCause? = null): Boolean {
        val cause = currentBlockCause(packageName, displayedCause)
        if (cause?.reason == BlockScreenReason.UNINSTALL_GUARD) return false
        return BlockExemptions.isExempt(packageName, nowEpochMs(), cause?.breakLevel ?: BreakLevel.EASY)
    }

    /**
     * Both detectors submit all existing restrictions before exemptions or priority can allow
     * access. Open-limit inspection does not spend an open behind another restriction.
     */
    fun evaluateBlockCause(
        previousPackage: String?,
        packageName: String,
        ruleCause: BlockCause?,
        timeLimitSpent: Boolean,
        foregroundUrl: String? = null,
    ): BlockCause? {
        // Safety floor before every cause: nothing is measured, counted or shielded. Open-limit
        // bookkeeping is untouched too — a floor app is not an open, and leaving one for an app
        // is still decided by countsAsOpen on the next real transition.
        if (safetyFloor.protects(packageName)) return null
        val now = nowEpochMs()
        val offset = utcOffsetMs(now)
        observations[packageName] = Observation(timeLimitSpent, openLimitPolicy.epochDay(now, offset), foregroundUrl)
        val timeCause = if (timeLimitSpent) timeLimitBlockCause(defaultBreakLevel, now, offset) else null
        val ordinary = BlockExemptions.hasOrdinaryExemption(packageName, now)
        val emergency = BlockExemptions.hasEmergencyExemption(packageName, now)
        val openDecision = onForegroundChanged(
            previousPackage,
            packageName,
            recordAllowedOpen = ruleCause == null && timeCause == null && !ordinary && !emergency,
        )
        val openCause = (openDecision as? OpenLimitDecision.Blocked)?.let {
            openLimitBlockCause(it.limit, now, offset)
        }
        val cause = BlockOverlapPolicy.afterExemptions(
            BlockOverlapPolicy.strongest(timeCause, ruleCause, openCause),
            ordinary,
            emergency,
        )
        // About to block: re-check against freshly resolved runtime surfaces, so a dialer or
        // launcher made default seconds ago is never shielded from a stale cache.
        return cause?.takeUnless { safetyFloor.protectsFresh(packageName) }
    }

    /**
     * Revalidate stale surface actions against current rule/open snapshots and the detector's
     * last measured daily-time result. Usage measurement stays owned by the detector. A
     * still-live displayed cause is retained conservatively until that detector refreshes it.
     */
    fun currentBlockCause(packageName: String, displayedCause: BlockCause? = null): BlockCause? {
        val now = nowEpochMs()
        if (safetyFloor.protects(packageName)) {
            // Only the separately designed uninstall guard may stand on a floor surface (it
            // shields specific Settings/installer pages); every ordinary cause stands down.
            return displayedCause?.takeIf {
                it.reason == BlockScreenReason.UNINSTALL_GUARD &&
                    (it.endsAtEpochMs == null || now < it.endsAtEpochMs)
            }
        }
        val offset = utcOffsetMs(now)
        val observation = observations[packageName]
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val verdict = ruleEngine.decide(
            packageName,
            observation?.url,
            ActiveRulesHolder.rules,
            appContext.packageName,
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            IsoWeekday.fromCalendar(cal.get(Calendar.DAY_OF_WEEK)),
            now,
        )
        val ruleCause = (verdict as? Verdict.Block)?.let { block ->
            ruleBlockCause(ActiveRulesHolder.rules.firstOrNull { it.id == block.ruleId }, now, offset)
        }
        val timeCause = if (observation?.timeLimitSpent == true &&
            observation.epochDay == openLimitPolicy.epochDay(now, offset)
        ) timeLimitBlockCause(defaultBreakLevel, now, offset) else null
        val openDecision = openLimitPolicy.onForegroundTransition(
            previousPackage = packageName,
            foregroundPackage = packageName,
            selfPackage = appContext.packageName,
            state = openCounts,
            granted = openGrantedPackage,
            limits = openLimits,
            epochDay = openLimitPolicy.epochDay(now, offset),
            recordAllowedOpen = false,
        ).decision
        val openCause = (openDecision as? OpenLimitDecision.Blocked)?.let {
            openLimitBlockCause(it.limit, now, offset)
        }
        val liveDisplayed = displayedCause?.takeIf { it.endsAtEpochMs == null || now < it.endsAtEpochMs }
        return BlockOverlapPolicy.strongest(timeCause, ruleCause, openCause, liveDisplayed)
    }

    /** HARDER cooldown remaining at [atEpochMs], or null when none is pending. */
    fun breakWaitRemainingMillis(atEpochMs: Long): Long? =
        breakSession.pending
            ?.takeIf { !it.isElapsed(atEpochMs) }
            ?.let { it.effectiveAtEpochMs - atEpochMs }

    /**
     * Open Limits, on every foreground transition: rolls the day over, decides once per real
     * open, and records the opens it allows (shield bounces, system surfaces and same-app
     * changes aren't opens). An open that was allowed stays allowed while the app holds the
     * foreground — [OpenLimitPolicy.onForegroundTransition] carries that grant across
     * re-evaluations, so the app the user just spent their last open on is not shielded a poll
     * tick later. Callers block on [OpenLimitDecision.Blocked]. Synchronous; persistence is
     * fired asynchronously on [scope].
     */
    fun onForegroundChanged(
        previousPackage: String?,
        foregroundPackage: String,
        recordAllowedOpen: Boolean = true,
    ): OpenLimitDecision {
        val now = nowEpochMs()
        val day = openLimitPolicy.epochDay(now, utcOffsetMs(now))
        val transition = openLimitPolicy.onForegroundTransition(
            previousPackage = previousPackage,
            foregroundPackage = foregroundPackage,
            selfPackage = appContext.packageName,
            state = openCounts,
            granted = openGrantedPackage,
            limits = openLimits,
            epochDay = day,
            recordAllowedOpen = recordAllowedOpen,
        )
        openGrantedPackage = transition.granted
        if (transition.counts != openCounts) {
            openCounts = transition.counts
            persist { focusStore.setOpenCounts(transition.counts) }
        }
        return transition.decision
    }

    /**
     * Mount (or refresh) the block surface for [packageName]. Same package while visible →
     * in-place re-render (no flicker); different package → swap surfaces. If the surface
     * can't attach, [onBlockSurfaceUnavailable] takes over (full-screen Activity fallback).
     */
    fun showBlocked(packageName: String, appLabel: String?, cause: BlockCause) = runOnMain {
        val now = nowEpochMs()
        if (cause.endsAtEpochMs != null && now >= cause.endsAtEpochMs) {
            // Stale cause: the block window already ended. Make sure nothing lingers.
            hideLocked()
            return@runOnMain
        }
        if (cause.reason != BlockScreenReason.UNINSTALL_GUARD && safetyFloor.protectsFresh(packageName)) {
            // Defense in depth for any caller: never mount an ordinary shield on a floor surface.
            if (visiblePackage == packageName) hideLocked()
            return@runOnMain
        }
        val existing = shownView
        if (existing != null && visiblePackage == packageName) {
            // Same app, refreshed cause (e.g. new tick, or a different rule took over).
            shownCause = cause
            shownLabel = appLabel
            existing.render(blockScreenState(packageName, appLabel, cause, breakSession, now, isEmergencyPassAvailable()))
            return@runOnMain
        }
        if (existing != null) hideLocked() // swapping to another app's block surface
        // Set after hideLocked() — it clears these fields.
        shownCause = cause
        shownLabel = appLabel
        val view = BlockScreenHostView(
            context = appContext,
            onDismiss = ::handleDismiss,
            onOpenFlint = ::handleOpenFlint,
            onBreakRequested = ::handleBreakRequested,
            onEmergencyPassRequested = ::handleEmergencyPassRequested,
        )
        view.render(blockScreenState(packageName, appLabel, cause, breakSession, now, isEmergencyPassAvailable()))
        if (surfaceHost.attach(view)) {
            shownView = view
            visiblePackage = packageName
            startTicker()
        } else {
            onBlockSurfaceUnavailable?.invoke(appContext, packageName, appLabel, cause)
        }
    }

    /** Drop the block surface if one is up (foreground app became allowed / break granted). */
    fun hideIfShown() = runOnMain { hideLocked() }

    /**
     * Ask for a break against [cause]'s tier, per [BreakPolicy]:
     * EASY → granted now; HARDER → arms the persisted friction request first, grants when
     * asked again after it elapses; HARDCORE → denied, always (Emergency Pass is separate).
     * A grant exempts [packageName] for [DEFAULT_BREAK_DURATION_MS] and — for Open Limit
     * blocks — spends one more recorded open ("use anyway"). Returns the raw decision so
     * Activity hosts can finish() on grant.
     */
    fun requestBreak(packageName: String, cause: BlockCause): BreakDecision {
        val now = nowEpochMs()
        val currentCause = currentBlockCause(packageName, cause) ?: cause
        val decision = breakPolicy.requestBreak(currentCause.breakLevel, breakSession, now)
        when (decision) {
            is BreakDecision.Granted -> {
                breakSession = decision.session
                persist { focusStore.setBreakSession(decision.session) }
                val until = now + DEFAULT_BREAK_DURATION_MS
                BlockExemptions.grant(packageName, until)
                scheduleExemptionExpiry(packageName, until)
                if (currentCause.spendsOpenOnBreak) {
                    // The extra open is recorded but never granted: what lifts the shield here
                    // is the break's exemption window, so the block returns when that window
                    // ends — the same contract every other tier gets.
                    val day = openLimitPolicy.epochDay(now, utcOffsetMs(now))
                    val next = openLimitPolicy.recordOpen(openCounts, packageName, day)
                    openCounts = next
                    persist { focusStore.setOpenCounts(next) }
                }
            }
            is BreakDecision.Pending -> {
                breakSession = decision.session
                persist { focusStore.setBreakSession(decision.session) }
            }
            BreakDecision.DeniedHardcore -> runOnMain {
                // An old EASY/HARDER surface may still be visible when a strict rule starts.
                if (visiblePackage == packageName) {
                    shownCause = currentCause
                    rerenderNow()
                }
            }
        }
        return decision
    }

    /**
     * Explicitly spend this week's Emergency Pass ([EmergencyPassPolicy.consume]). True →
     * pass consumed and [packageName] exempted until the block window's end (or
     * [EMERGENCY_PASS_OPEN_ENDED_EXEMPTION_MS] for open-ended manual sessions — the seam
     * can't stop the session itself; rule mutation belongs to the app layer). False → the
     * pass was already spent this week; the block stands.
     */
    fun requestEmergencyPass(packageName: String, cause: BlockCause): Boolean {
        val now = nowEpochMs()
        val consumed = passPolicy.consume(emergencyPass, now, utcOffsetMs(now)) ?: return false
        emergencyPass = consumed
        persist { focusStore.setEmergencyPass(consumed) }
        val until = cause.endsAtEpochMs ?: (now + EMERGENCY_PASS_OPEN_ENDED_EXEMPTION_MS)
        BlockExemptions.grantEmergency(packageName, until)
        scheduleExemptionExpiry(packageName, until)
        return true
    }

    // MARK: Surface callbacks (Compose invokes these on the main thread)

    private fun handleDismiss() {
        hideIfShown()
        surfaceHost.goHome()
    }

    private fun handleOpenFlint() {
        hideIfShown()
        // Launching our own task from a service context: exempt from background-activity-launch
        // limits on Path A (system-bound AccessibilityService) and covered by the
        // SYSTEM_ALERT_WINDOW grant + the user's tap on Path B. Best-effort regardless.
        runCatching {
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { appContext.startActivity(it) }
        }
    }

    private fun handleBreakRequested() {
        val packageName = visiblePackage ?: return
        val cause = shownCause ?: return
        when (requestBreak(packageName, cause)) {
            is BreakDecision.Granted -> hideIfShown() // reveal the app; user is on a break
            is BreakDecision.Pending -> rerenderNow() // swap the hold control for the wait pill
            BreakDecision.DeniedHardcore -> Unit
        }
    }

    private fun handleEmergencyPassRequested() {
        val packageName = visiblePackage ?: return
        val cause = shownCause ?: return
        if (requestEmergencyPass(packageName, cause)) {
            hideIfShown()
        }
        // else: pass already used this week — the block stands. (BlockScreenState has no slot
        // for "pass unavailable" feedback yet; that copy belongs to feature-blockscreen.)
    }

    // MARK: Internals

    /** Main-thread body of hide. Only call via [runOnMain]/[hideIfShown]. */
    private fun hideLocked() {
        ticker?.cancel()
        ticker = null
        val view = shownView ?: return
        shownView = null
        visiblePackage = null
        shownCause = null
        shownLabel = null
        surfaceHost.detach(view)
    }

    /** Re-render the visible surface immediately (state changed outside the 1s tick). */
    private fun rerenderNow() = runOnMain {
        val pkg = visiblePackage ?: return@runOnMain
        val cause = shownCause ?: return@runOnMain
        shownView?.render(blockScreenState(pkg, shownLabel, cause, breakSession, nowEpochMs(), isEmergencyPassAvailable()))
    }

    /**
     * The host-owned clock: once per second re-render with fresh state so countdowns tick and
     * HARDER cooldowns drain; auto-hide when the block window ends or an exemption (granted
     * from any surface, including BlockActivity) starts covering the package.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(1_000L)
                val pkg = visiblePackage ?: break
                val cause = currentBlockCause(pkg, shownCause)
                val now = nowEpochMs()
                if (cause == null) {
                    hideLocked() // block window over (schedule ended / midnight rollover)
                    break
                }
                shownCause = cause
                if (isExempt(pkg, cause)) {
                    hideLocked() // a break/pass was granted meanwhile (possibly by the Activity)
                    break
                }
                shownView?.render(blockScreenState(pkg, shownLabel, cause, breakSession, now, isEmergencyPassAvailable()))
            }
        }
    }

    private fun scheduleExemptionExpiry(packageName: String, untilEpochMs: Long) {
        val callback = onExemptionExpired ?: return
        scope.launch {
            delay((untilEpochMs - nowEpochMs()).coerceAtLeast(0L) + EXPIRY_SLACK_MS)
            callback(packageName)
        }
    }

    private fun persist(write: suspend () -> Unit) {
        // DataStore serializes updateData calls per instance, so submission order is kept.
        scope.launch { runCatching { write() } }
    }

    private fun utcOffsetMs(now: Long): Long = TimeZone.getDefault().getOffset(now).toLong()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    companion object {
        /**
         * How long a granted break lifts the shield. Opal lets users pick a duration; Flint's
         * MVP fixes 5 minutes (a duration picker is UI-layer work, not this seam's).
         */
        const val DEFAULT_BREAK_DURATION_MS: Long = 5 * 60_000L

        /**
         * Emergency-Pass exemption for open-ended manual sessions, where no window end exists
         * to pin the exemption to. Approximates Opal's "pass ends the session" as far as this
         * seam can reach — actually stopping the rule/session is app-layer work.
         */
        const val EMERGENCY_PASS_OPEN_ENDED_EXEMPTION_MS: Long = 30 * 60_000L

        /** Re-check a hair after expiry so the re-evaluation never races the ledger. */
        private const val EXPIRY_SLACK_MS: Long = 250L
    }
}
