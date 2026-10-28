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
import com.flint.peakfocus.core.datastore.BlockRulesStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.datastore.LimitsStore
import com.flint.peakfocus.core.datastore.EditorCommitmentStore
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.StorageState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision
import com.flint.peakfocus.core.model.TimeLimit
import com.flint.peakfocus.core.model.Verdict
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import com.flint.peakfocus.feature.blockscreen.BlockScreenState
import com.flint.peakfocus.feature.blockscreen.GrantStatus
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
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
 * internally. Grant requests suspend until their accounting and receipts commit together.
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
    /**
     * Invoked (on [scope]) after the first load and every distinct change of the Time Limit,
     * Open Limit or default-tier snapshots, so an event-driven detector can re-evaluate a
     * stationary foreground app whose protection just changed. Not invoked for open-count
     * changes (the coordinator's own writes).
     */
    private val onEnforcementChanged: (() -> Unit)? = null,
    /** Test seam; production resolves the device's runtime floor surfaces lazily. */
    safetyFloorGate: SafetyFloorGate? = null,
    focusStateStore: FocusStateStore? = null,
    configuredLimitsStore: LimitsStore? = null,
    blockRulesStore: BlockRulesStore? = null,
    editorCommitmentStore: EditorCommitmentStore? = null,
    private val statusClock: () -> AccessClockStamp = ProtectionClock::now,
    private val statusDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
        resolveRuntime = { NeverBlockSurfaces.resolveBySource(appContext) },
        nowElapsedMs = SystemClock::elapsedRealtime,
        // Binder lookups stay off the accessibility event (main) thread.
        refreshInBackground = { refresh -> scope.launch(Dispatchers.Default) { refresh() } },
        requiredRuntimeSources = NeverBlockSurfaces.requiredSources(),
    )

    /** Package whose newest mount already force-resolved the floor (cleared on hide). Main thread only. */
    private var mountCheckedPackage: String? = null

    /** Package whose current block was already reported as an intercepted open (cleared on hide). Main thread only. */
    private var interceptedPackage: String? = null
    private var mountedPackage: String? = null
    private var pendingSurfacePackage: String? = null
    private var pendingSurface: Job? = null
    private val focusStore = focusStateStore ?: FlintPreferences.focusState(appContext)
    private val limitsStore = configuredLimitsStore ?: FlintPreferences.limits(appContext)
    private val rulesStore = blockRulesStore ?: FlintPreferences.blockRules(appContext)
    private val editorStore = editorCommitmentStore ?: FlintPreferences.editorCommitments(appContext)

    private val passPolicy = EmergencyPassPolicy()
    private val openLimitPolicy = OpenLimitPolicy()
    private val ruleEngine = BlockDecisionEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Observation(val timeLimitSpent: Boolean, val epochDay: Long, val url: String?)
    private val observations = ConcurrentHashMap<String, Observation>()

    private val grants = DurableGrantController(focusStore, scope, nowEpochMs, ::utcOffsetMs, BootIdentity.current(appContext))
    private val openState = MutableStateFlow<StorageState<List<OpenLimit>>>(StorageState.Loading)
    private val timeState = MutableStateFlow<StorageState<List<TimeLimit>>>(StorageState.Loading)
    private val rulesState = MutableStateFlow<StorageState<List<BlockRule>>>(StorageState.Loading)
    private val editorState = MutableStateFlow<StorageState<EditorCommitmentState>>(StorageState.Loading)
    private val statusLock = Any()
    @Volatile private var statusClockReady = false
    private var lastPolicyStatus: ProtectionStatus? = null
    // Lists are last-known-good only, never a replacement emitted because a read failed.
    @Volatile private var openLimits: List<OpenLimit> = emptyList()
    @Volatile private var timeLimits: List<TimeLimit> = emptyList()
    private val visits = OpenVisits.process
    private val pendingOpens = ConcurrentHashMap.newKeySet<String>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap.newKeySet<String>()
    private val scheduledExpiries = ConcurrentHashMap<String, Long>()

    /** Tokens belong to one mounted surface; a late callback cannot act on a newer mount. */
    internal class SurfaceRequests {
        var breakId: GrantRequestId? = null
        var passId: GrantRequestId? = null
        var retired = false
        fun id(emergency: Boolean, now: Long): GrantRequestId {
            val previous = if (emergency) passId else breakId
            if (previous != null && previous.issuedAtEpochMs <= now &&
                now - previous.issuedAtEpochMs < DurableGrantController.RETRY_HORIZON_MS) return previous
            return GrantRequestId.issue(now).also { if (emergency) passId = it else breakId = it }
        }
        fun recordResult(emergency: Boolean, result: GrantResult) {
            // A stronger cause may keep this mount visible after an acknowledged grant.
            // Its next explicit action is a new request; uncertain retries retain the old id.
            // A confirmed rejection also permits a new identity if the cause has changed.
            if (result is GrantResult.Pending || result is GrantResult.Granted || result == GrantResult.OpenAllowed || result == GrantResult.Denied) {
                if (emergency) passId = null else breakId = null
            }
        }
    }
    private var surfaceRequests: SurfaceRequests? = null

    // The currently mounted surface. Mutated on the main thread only.
    private var shownView: BlockScreenHostView? = null
    private var shownCause: BlockCause? = null
    private var shownLabel: String? = null
    private var ticker: Job? = null
    @Volatile private var visiblePackage: String? = null

    init {
        scope.launch { limitsStore.openLimitsState.collect { state ->
            if (state is StorageState.Ready) openLimits = state.value
            openState.value = state
            publishProtectionStatus()
        } }
        scope.launch { limitsStore.timeLimitsState.collect { state ->
            if (state is StorageState.Ready) timeLimits = state.value
            timeState.value = state
            publishProtectionStatus()
        } }
        scope.launch { rulesStore.rulesState.collect { rulesState.value = it; publishProtectionStatus() } }
        scope.launch { editorStore.state.collect { editorState.value = it; publishProtectionStatus() } }
        scope.launch { safetyFloor.runtimeResolution.collect { publishProtectionStatus() } }
        scope.launch {
            val unsubscribe = EnforcementSignals.detectors.subscribe(::publishPathAHealth)
            try { kotlinx.coroutines.awaitCancellation() } finally { unsubscribe() }
        }
        scope.launch(statusDispatcher) {
            // ProtectionClock resolves the OS boot identity lazily. Initialize it here so
            // status publication on an accessibility/main callback never opens /proc.
            statusClock()
            statusClockReady = true
            while (isActive) {
                safetyFloor.refreshSoonIfOlderThan(SafetyFloorGate.DEFAULT_REFRESH_INTERVAL_MS)
                publishProtectionStatus()
                delay(ProtectionStatusProjection.FRESHNESS_MS)
            }
        }
        scope.launch { grants.state.collect { state ->
            if (state is StorageState.Ready) state.value.exemptions.values.forEach { receipt ->
                if (receipt.untilEpochMs > nowEpochMs() && scheduledExpiries.putIfAbsent(receipt.requestId, receipt.untilEpochMs) == null) {
                    scheduleExemptionExpiry(receipt.requestId, receipt.packageName, receipt.untilEpochMs)
                }
            }
            rerenderNow()
            publishProtectionStatus()
        } }
        scope.launch {
            // Include readiness (and recovery), but exclude counters/receipts from the config
            // signal so our own debits cannot create an event-driven re-evaluation loop.
            combine(grants.state, openState, timeState, rulesState) { focus, open, time, rules ->
                listOf(focus::class, (focus as? StorageState.Ready)?.value?.defaultBreakLevel,
                    open, time, rules)
            }.distinctUntilChanged().collect {
                runCatching { onEnforcementChanged?.invoke() }
                rerenderNow()
            }
        }
    }

    /** Real policy evaluation; display reads and detector-health callbacks never invoke this. */
    private fun publishProtectionStatus() {
        if (!statusClockReady) return
        val snapshot = ProtectionStatusProjection.evaluate(grants.state.value, rulesState.value,
            timeState.value, openState.value, editorState.value, ActiveRulesHolder.rulesOutside(ActiveRulesHolder.SOURCE_DATASTORE),
            safetyFloor.runtimeResolution.value, EnforcementSignals.detectors.pathAConnected, statusClock())
        synchronized(statusLock) {
            lastPolicyStatus = snapshot
            ProtectionStatusHub.publish(snapshot)
        }
    }

    /** A binding signal changes liveness only, preserving the last actual policy check. */
    private fun publishPathAHealth() = synchronized(statusLock) {
        val previous = lastPolicyStatus ?: return@synchronized
        val connected = EnforcementSignals.detectors.pathAConnected
        val reasons = previous.detectors.degradedReasons - "path-a-disconnected" +
            if (connected) emptySet() else setOf("path-a-disconnected")
        val next = previous.copy(detectors = previous.detectors.copy(accessibilityConnected = connected, degradedReasons = reasons))
        lastPolicyStatus = next
        ProtectionStatusHub.publish(next)
    }

    /**
     * True when [packageName] is on the enforcement safety floor (static OEM list, Flint, or the
     * device's resolved dialer/home apps). Detectors short-circuit on this before measuring
     * budgets; [evaluateBlockCause] and [currentBlockCause] apply it regardless.
     */
    fun isSafetyFloor(packageName: String): Boolean {
        val protected = safetyFloor.protects(packageName)
        // Home/runtime floor apps are real departures; system shade/Flint dips preserve the
        // paid visit, exactly as countsAsOpen classifies them. Never debit a floor package.
        if (protected && openLimitPolicy.countsAsOpen(null, packageName, appContext.packageName)) visits.leave()
        return protected
    }

    /** Loaded tier for Time Limit displays; unknown accounting has no grant affordance. */
    fun currentDefaultBreakLevel(): BreakLevel = grants.lastGood?.defaultBreakLevel ?: BreakLevel.HARDCORE

    /** A saved limit owns its tier; the default only supplies the legacy SharedPreferences budget. */
    fun currentTimeLimitBreakLevel(packageName: String): BreakLevel =
        timeLimits.firstOrNull { it.packageName == packageName }?.breakLevel ?: currentDefaultBreakLevel()

    /**
     * The Limit editor's daily Time Limit for [packageName] (minutes), or null. Synchronous
     * snapshot read — safe on the a11y event hot path, same contract as the other snapshots.
     * Detectors take the stricter of this and the legacy `LimitStore` budget; before this
     * lookup existed, DataStore-authored Time Limits were persisted but never enforced.
     */
    fun timeLimitMinutes(packageName: String): Int? =
        timeLimits.firstOrNull { it.packageName == packageName }?.dailyMinutes

    /** Snapshot of the HARDER break bookkeeping (BlockActivity rebuilds its state from this). */
    fun currentBreakSession(): BreakSessionState = grants.lastGood?.breakSession ?: BreakSessionState()

    /** True while this week's Emergency Pass is unspent — drives the HARDCORE CTA's state. */
    fun isEmergencyPassAvailable(): Boolean {
        val now = nowEpochMs()
        val focus = (grants.state.value as? StorageState.Ready)?.value ?: return false
        return passPolicy.isAvailable(focus.emergencyPass, now, utcOffsetMs(now))
    }

    /** Re-check protection before a surface hides in response to a previously issued grant. */
    fun isExempt(packageName: String, displayedCause: BlockCause? = null): Boolean {
        val cause = currentBlockCause(packageName, displayedCause)
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
        if (isSafetyFloor(packageName)) return null
        val now = nowEpochMs()
        val offset = utcOffsetMs(now)
        observations[packageName] = Observation(timeLimitSpent, openLimitPolicy.epochDay(now, offset), foregroundUrl)
        val timeCause = if (timeLimitSpent) timeLimitBlockCause(currentTimeLimitBreakLevel(packageName), now, offset) else null
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
        // Safety floor: no cause of any kind stands on a floor surface (ADR-012 §7, §10).
        if (safetyFloor.protects(packageName)) return null
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
        ) timeLimitBlockCause(currentTimeLimitBreakLevel(packageName), now, offset) else null
        val openDecision = inspectOpen(packageName)
        val openCause = (openDecision as? OpenLimitDecision.Blocked)?.let {
            openLimitBlockCause(it.limit, now, offset)
        }
        val liveDisplayed = displayedCause?.takeIf { it.endsAtEpochMs == null || now < it.endsAtEpochMs }
            ?.takeUnless { it.reason == BlockScreenReason.OPEN_LIMIT && openState.value is StorageState.Ready && openCause == null }
            ?.let { if (it.reason == BlockScreenReason.TIME_LIMIT) it.copy(breakLevel = currentTimeLimitBreakLevel(packageName)) else it }
        return BlockOverlapPolicy.strongest(timeCause, ruleCause, openCause, liveDisplayed)
    }

    /** HARDER cooldown remaining at [atEpochMs], or null when none is pending. */
    fun breakWaitRemainingMillis(atEpochMs: Long): Long? =
        grants.lastGood?.breakSession?.pending
            ?.takeIf { !it.isElapsed(atEpochMs) }
            ?.let { it.effectiveAtEpochMs - atEpochMs }

    /**
     * Open Limits, on every foreground transition: rolls the day over, decides once per real
     * open, and records the opens it allows (shield bounces, system surfaces and same-app
     * changes aren't opens). An open that was allowed stays allowed while the app holds the
     * foreground — [OpenLimitPolicy.onForegroundTransition] carries that grant across
     * re-evaluations, so the app the user just spent their last open on is not shielded a poll
     * tick later. A new limited visit is held until its counter debit commits; the detector's
     * re-evaluation callback then accepts the paid visit. The event hot path never waits on IO.
     */
    fun onForegroundChanged(
        previousPackage: String?,
        foregroundPackage: String,
        recordAllowedOpen: Boolean = true,
    ): OpenLimitDecision {
        if (isSafetyFloor(foregroundPackage)) return OpenLimitDecision.Allowed(null)
        val visit = visits.observe(
            foregroundPackage,
            openLimitPolicy.countsAsOpen(null, foregroundPackage, appContext.packageName),
            recordAllowedOpen && openLimitPolicy.countsAsOpen(previousPackage, foregroundPackage, appContext.packageName),
            nowEpochMs(),
        )
        val decision = inspectOpen(foregroundPackage, includeUnpaidVisit = false)
        if (visit?.packageName == foregroundPackage && visit.charge && !visit.paid && recordAllowedOpen &&
            decision is OpenLimitDecision.Allowed
        ) {
            val limit = openLimits.firstOrNull { it.packageName == foregroundPackage }
            if (limit != null) {
                if (counterInputsReady()) queueOpen(visit)
                if (!visit.paid) return OpenLimitDecision.Blocked(limit, "waiting for durable open accounting")
            }
        }
        return decision
    }

    private fun counterInputsReady() = grants.state.value is StorageState.Ready && openState.value is StorageState.Ready

    private fun grantInputs(cause: BlockCause, emergency: Boolean): List<StorageState<*>> =
        listOf(grants.state.value) + when {
            // A package Emergency Pass covers all cause tiers; configuration cannot change
            // its accounting decision. The durable focus ledger must still be Ready.
            emergency -> emptyList()
            cause.reason == BlockScreenReason.OPEN_LIMIT -> listOf(openState.value)
            cause.reason == BlockScreenReason.TIME_LIMIT -> listOf(timeState.value)
            else -> listOf(rulesState.value, ActiveRulesHolder.dataStoreState)
        }

    private fun grantInputsReady(cause: BlockCause, emergency: Boolean) =
        grantInputs(cause, emergency).all { it is StorageState.Ready }

    private fun inspectOpen(packageName: String, includeUnpaidVisit: Boolean = true): OpenLimitDecision {
        val limit = openLimits.firstOrNull { it.packageName == packageName } ?: return OpenLimitDecision.Allowed(null)
        val focus = grants.lastGood
        if (visits.current(packageName)?.paid == true && focus != null) {
            val day = openLimitPolicy.epochDay(nowEpochMs(), utcOffsetMs(nowEpochMs()))
            return OpenLimitDecision.Allowed((limit.dailyOpens - openLimitPolicy.opensUsed(focus.openCounts, packageName, day)).coerceAtLeast(0))
        }
        if (!counterInputsReady() || focus == null) return OpenLimitDecision.Blocked(limit, "storage unavailable")
        val decision = openLimitPolicy.decide(packageName, focus.openCounts, openLimits,
            openLimitPolicy.epochDay(nowEpochMs(), utcOffsetMs(nowEpochMs())))
        if (includeUnpaidVisit && visits.current(packageName)?.let { it.charge && !it.paid } == true && decision is OpenLimitDecision.Allowed) {
            return OpenLimitDecision.Blocked(limit, "waiting for durable open accounting")
        }
        return decision
    }

    private fun queueOpen(visit: OpenVisits.Visit) {
        if (openLimits.none { it.packageName == visit.packageName }) return
        if (!pendingOpens.add(visit.id.prefix)) return
        scope.launch {
            try {
                if (!counterInputsReady() || safetyFloor.protects(visit.packageName)) return@launch
                val limit = openLimits.firstOrNull { it.packageName == visit.packageName } ?: return@launch
                val result = grants.recordOpen(visit.id, visit.packageName, limit)
                if (result == GrantResult.OpenAllowed) {
                    visits.acknowledge(visit)
                    failures.remove(visit.packageName)
                    runCatching { onEnforcementChanged?.invoke() }
                } else if (result == GrantResult.Denied) {
                    runCatching { onEnforcementChanged?.invoke() }
                } else if ((result is GrantResult.Unavailable || result == GrantResult.Unconfirmed) &&
                    visits.current(visit.packageName) === visit) failures.add(visit.packageName)
            } finally {
                pendingOpens.remove(visit.id.prefix)
                rerenderNow()
            }
        }
    }

    /**
     * Mount (or refresh) the block surface for [packageName]. Same package while visible →
     * in-place re-render (no flicker); different package → swap surfaces. If the surface
     * can't attach, [onBlockSurfaceUnavailable] takes over (full-screen Activity fallback).
     */
    fun showBlocked(packageName: String, appLabel: String?, cause: BlockCause) = runOnMain {
        showBlockedLocked(packageName, appLabel, cause, deferPendingOpen = true)
    }

    private fun showBlockedLocked(packageName: String, appLabel: String?, cause: BlockCause, deferPendingOpen: Boolean) {
        val now = nowEpochMs()
        if (cause.endsAtEpochMs != null && now >= cause.endsAtEpochMs) {
            // Stale cause: the block window already ended. Make sure nothing lingers.
            hideLocked()
            return
        }
        // Defense in depth for any caller: never mount a shield on a floor surface, for any
        // reason. A new mount force-resolves the runtime floor once (a launcher or dialer made
        // default seconds ago); refreshes of the same surface use the short fresh window.
        val onFloor = if (mountCheckedPackage != packageName) {
            safetyFloor.protectsFresh(packageName, maxAgeMs = 0L)
        } else {
            safetyFloor.protectsFresh(packageName)
        }
        if (onFloor) {
            // Only drop this package's own shield; never reveal another app's block.
            if (visiblePackage == packageName) hideLocked()
            return
        }
        val existing = shownView
        if (deferPendingOpen && existing == null && pendingOpenHold(packageName, cause)) {
            if (pendingSurfacePackage != packageName) {
                pendingSurface?.cancel()
                pendingSurfacePackage = packageName
                pendingSurface = scope.launch {
                    delay(PENDING_OPEN_SURFACE_GRACE_MS)
                    runOnMain {
                        if (pendingSurfacePackage != packageName) return@runOnMain
                        pendingSurfacePackage = null
                        pendingSurface = null
                        val current = currentBlockCause(packageName, cause)
                        if (current != null && !isExempt(packageName, current)) {
                            showBlockedLocked(packageName, appLabel, current, deferPendingOpen = false)
                        }
                    }
                }
            }
            return
        }
        pendingSurface?.cancel()
        pendingSurface = null
        pendingSurfacePackage = null
        if (existing != null && visiblePackage == packageName) {
            // Same app, refreshed cause (e.g. new tick, or a different rule took over).
            shownCause = cause
            shownLabel = appLabel
            existing.render(screenState(packageName, appLabel, cause, now))
            reportInterception(packageName, cause, now)
            return
        }
        if (existing != null) hideLocked() // swapping to another app's block surface
        // After the swap's hide (which clears it): this package's mount has force-resolved.
        mountCheckedPackage = packageName
        // Set after hideLocked() — it clears these fields.
        shownCause = cause
        shownLabel = appLabel
        if (mountedPackage != packageName && !pendingOpenHold(packageName, cause)) failures.remove(packageName)
        val requests = SurfaceRequests()
        surfaceRequests = requests
        val view = BlockScreenHostView(
            context = appContext,
            onDismiss = ::handleDismiss,
            onOpenFlint = ::handleOpenFlint,
            onBreakRequested = { handleGrantRequested(packageName, requests, emergency = false) },
            onEmergencyPassRequested = { handleGrantRequested(packageName, requests, emergency = true) },
        )
        view.render(screenState(packageName, appLabel, cause, now))
        val mounted = if (surfaceHost.attach(view)) {
            shownView = view
            visiblePackage = packageName
            startTicker()
            true
        } else {
            onBlockSurfaceUnavailable?.let { fallback ->
                fallback(appContext, packageName, appLabel, cause)
                true
            } ?: false
        }
        // Once per new block of this package that actually reached the user (Path B's fallback
        // re-launches every poll tick while the overlay is unavailable; those are not new
        // interceptions, and a failed attach with no fallback blocked nothing).
        if (mounted) {
            mountedPackage = packageName
            reportInterception(packageName, cause, now)
        }
    }

    private fun reportInterception(packageName: String, cause: BlockCause, now: Long) {
        if (!pendingOpenHold(packageName, cause) && interceptedPackage != packageName) {
            interceptedPackage = packageName
            BlockEventHooks.openIntercepted(cause.ruleId, now)
        }
    }

    /** Drop the block surface if one is up (foreground app became allowed / break granted). */
    fun hideIfShown() = runOnMain { hideLocked() }

    /**
     * Ask for a break against [cause]'s tier, per [BreakPolicy]:
     * EASY → granted now; HARDER → arms the persisted friction request first, grants when
     * asked again after it elapses; HARDCORE → denied, always (Emergency Pass is separate).
     * A grant exempts [packageName] for [DEFAULT_BREAK_DURATION_MS] and — for Open Limit
     * blocks — spends one more recorded open ("use anyway") in the same transaction.
     * Callers retain requestId on failure and only dismiss after an acknowledged result.
     */
    suspend fun requestBreak(packageName: String, cause: BlockCause, requestId: GrantRequestId): GrantResult =
        requestGrant(packageName, cause, requestId, emergency = false)

    /**
     * Explicitly spend this week's Emergency Pass ([EmergencyPassPolicy.consume]). True →
     * pass consumed and [packageName] exempted until the block window's end (or
     * [EMERGENCY_PASS_OPEN_ENDED_EXEMPTION_MS] for open-ended manual sessions — the seam
     * can't stop the session itself; rule mutation belongs to the app layer). False → the
     * pass was already spent this week; the block stands.
     */
    suspend fun requestEmergencyPass(packageName: String, cause: BlockCause, requestId: GrantRequestId): GrantResult =
        requestGrant(packageName, cause, requestId, emergency = true)

    private suspend fun requestGrant(packageName: String, cause: BlockCause, id: GrantRequestId, emergency: Boolean): GrantResult {
        if (safetyFloor.protectsFresh(packageName)) return GrantResult.Denied
        if (grants.state.value !is StorageState.Ready) return GrantResult.Unavailable()
        if (!busy.add(packageName)) return GrantResult.Unavailable()
        failures.remove(packageName)
        rerenderNow()
        try {
            val current = currentBlockCause(packageName, cause) ?: return GrantResult.Denied
            if (!grantInputsReady(current, emergency)) return GrantResult.Unavailable()
            runOnMain { if (visiblePackage == packageName) shownCause = current }
            val retry = if (!emergency && openRetryNeeded(packageName, current)) visits.current(packageName) else null
            val now = nowEpochMs()
            val result = if (retry != null) {
                grants.recordOpen(retry.id, packageName, openLimits.firstOrNull { it.packageName == packageName }).also {
                    if (it == GrantResult.OpenAllowed) {
                        visits.acknowledge(retry)
                        runCatching { onEnforcementChanged?.invoke() }
                    }
                }
            } else {
                val duration = if (emergency) current.endsAtEpochMs?.minus(now)
                    ?: EMERGENCY_PASS_OPEN_ENDED_EXEMPTION_MS else DEFAULT_BREAK_DURATION_MS
                grants.request(GrantRequest(
                    id, packageName, causeKey(packageName, current), GrantScope.Packages(setOf(packageName)),
                    duration, current.breakLevel, emergency, current.spendsOpenOnBreak,
                    usesDefaultLevel = current.reason == BlockScreenReason.TIME_LIMIT && timeLimits.none { it.packageName == packageName },
                    notAfterEpochMs = current.endsAtEpochMs.takeIf { emergency },
                ), inputsReady = grantInputsReady(current, emergency))
            }
            if (result is GrantResult.Unavailable || result == GrantResult.Unconfirmed) failures.add(packageName)
            if (result is GrantResult.Granted && result.newlyAccepted) {
                // Observe-only history hooks happen strictly after the atomic edit returns.
                if (emergency) BlockEventHooks.emergencyPassCommitted(result.receipts.first().requestId,
                    setOfNotNull(current.ruleId), result.receipts.maxOf { it.untilEpochMs }, result.atEpochMs)
                else BlockEventHooks.breakGranted(current.ruleId, result.receipts.first().requestId,
                    result.receipts.maxOf { it.untilEpochMs }, result.atEpochMs)
            }
            return result
        } finally {
            busy.remove(packageName)
            rerenderNow()
        }
    }

    private fun causeKey(packageName: String, cause: BlockCause): String = MessageDigest.getInstance("SHA-256")
        .digest("$packageName|${cause.reason}|${cause.ruleId}|${cause.endsAtEpochMs}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun openRetryNeeded(packageName: String, cause: BlockCause): Boolean {
        val visit = visits.current(packageName) ?: return false
        return cause.reason == BlockScreenReason.OPEN_LIMIT && visit.charge && !visit.paid &&
            counterInputsReady() && inspectOpen(packageName, includeUnpaidVisit = false) is OpenLimitDecision.Allowed
    }

    /** Accounting holds are provisional, not evidence of a genuinely exhausted quota. */
    private fun pendingOpenHold(packageName: String, cause: BlockCause): Boolean {
        val visit = visits.current(packageName) ?: return false
        if (cause.reason != BlockScreenReason.OPEN_LIMIT || !visit.charge || visit.paid) return false
        val focus = grants.lastGood ?: return true
        val now = nowEpochMs()
        return openLimitPolicy.decide(packageName, focus.openCounts, openLimits,
            openLimitPolicy.epochDay(now, utcOffsetMs(now))) is OpenLimitDecision.Allowed
    }

    fun screenState(packageName: String, label: String?, cause: BlockCause, now: Long): BlockScreenState {
        val inputs = grantInputs(cause, emergency = cause.breakLevel == BreakLevel.HARDCORE)
        val status = when {
            inputs.any { it is StorageState.Loading } -> GrantStatus.LOADING
            inputs.any { it !is StorageState.Ready } -> GrantStatus.UNAVAILABLE
            packageName in busy || visits.current(packageName)?.let { it.id.prefix in pendingOpens } == true -> GrantStatus.SAVING
            packageName in failures && openRetryNeeded(packageName, cause) -> GrantStatus.RETRY_OPEN
            packageName in failures -> GrantStatus.FAILED
            else -> GrantStatus.READY
        }
        // These neutral values feed display only. Every policy/credit decision above requires
        // Ready and reads real accounting again inside the transaction.
        return blockScreenState(packageName, label, cause, currentBreakSession(), now, isEmergencyPassAvailable())
            .copy(grantStatus = status)
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

    private fun handleGrantRequested(packageName: String, requests: SurfaceRequests, emergency: Boolean) {
        if (requests.retired || surfaceRequests !== requests || visiblePackage != packageName || packageName in busy) return
        val cause = shownCause ?: return
        val id = requests.id(emergency, nowEpochMs())
        scope.launch {
            val result = if (emergency) requestEmergencyPass(packageName, cause, id) else requestBreak(packageName, cause, id)
            requests.recordResult(emergency, result)
            if (result is GrantResult.Granted || result == GrantResult.OpenAllowed) {
                runOnMain {
                    if (surfaceRequests === requests && visiblePackage == packageName && canDismiss(packageName, cause)) hideLocked()
                }
            }
            rerenderNow()
        }
    }

    fun canDismiss(packageName: String, cause: BlockCause): Boolean =
        currentBlockCause(packageName, cause) == null || isExempt(packageName, cause)

    // MARK: Internals

    /** Main-thread body of hide. Only call via [runOnMain]/[hideIfShown]. */
    private fun hideLocked() {
        (visiblePackage ?: mountedPackage ?: pendingSurfacePackage)?.let { failures.remove(it) }
        pendingSurface?.cancel()
        pendingSurface = null
        pendingSurfacePackage = null
        mountedPackage = null
        ticker?.cancel()
        ticker = null
        mountCheckedPackage = null
        interceptedPackage = null
        surfaceRequests?.retired = true
        surfaceRequests = null
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
        shownView?.render(screenState(pkg, shownLabel, cause, nowEpochMs()))
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
                // Keep the runtime floor current while a shield is up (background refresh), so a
                // launcher/dialer that became default under the shield drops it within seconds.
                safetyFloor.refreshSoonIfOlderThan(SHOWN_FLOOR_REFRESH_MS)
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
                shownView?.render(screenState(pkg, shownLabel, cause, now))
            }
        }
    }

    private fun scheduleExemptionExpiry(requestId: String, packageName: String, untilEpochMs: Long) {
        scope.launch {
            val remaining = (untilEpochMs - nowEpochMs()).coerceAtLeast(0L)
            delay(if (remaining > Long.MAX_VALUE - EXPIRY_SLACK_MS) Long.MAX_VALUE else remaining + EXPIRY_SLACK_MS)
            scheduledExpiries.remove(requestId, untilEpochMs)
            if (packageName == BlockExemptions.GLOBAL_PACKAGE) {
                runCatching { onEnforcementChanged?.invoke() }
            } else runCatching { onExemptionExpired?.invoke(packageName) }
        }
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

        /** Runtime-floor refresh age while a shield is visible (background, off the main thread). */
        private const val SHOWN_FLOOR_REFRESH_MS: Long = SafetyFloorGate.DEFAULT_FRESH_INTERVAL_MS

        /** Re-check a hair after expiry so the re-evaluation never races the ledger. */
        private const val EXPIRY_SLACK_MS: Long = 250L

        /** Policy stays blocked while IO is pending; avoid flashing a full screen for fast edits. */
        internal const val PENDING_OPEN_SURFACE_GRACE_MS: Long = 100L
    }
}
