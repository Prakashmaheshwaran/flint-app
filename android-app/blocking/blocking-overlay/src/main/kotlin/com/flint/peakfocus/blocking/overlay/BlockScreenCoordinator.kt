package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.BreakPolicy
import com.flint.peakfocus.blocking.engine.RollingEmergencyPassPolicy
import com.flint.peakfocus.blocking.engine.OpenCreditLedgerPolicy
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.engine.OpenLimitPolicy
import com.flint.peakfocus.core.datastore.BlockRulesStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.datastore.LimitsStore
import com.flint.peakfocus.core.datastore.EditorCommitmentStore
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.datastore.ProtectionTransactionStore
import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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
 *  - **Ordinary access**: 300 seconds clipped to natural ends, a frozen target/cause set,
 *    persisted occurrence-scoped Harder waits and explicit final confirmation.
 *  - **Emergency Pass**: rolling seven-day consumption and 60-minute global recovery,
 *    released only after the complete reviewed scope commits durably.
 *  - **Open Limits**: the shared trusted quota ledger; foreground changes never debit it.
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
    /** A custom focus backend must supply the same atomic rules/pass transaction domain. */
    protectionTransactionStore: ProtectionTransactionStore? = null,
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
    private var cancelRecoveryHandoff: (() -> Unit)? = null
    private var recoveryHandoffTimeout: Job? = null
    private val focusStore = focusStateStore ?: FlintPreferences.focusState(appContext)
    private val limitsStore = configuredLimitsStore ?: FlintPreferences.limits(appContext)
    private val rulesStore = blockRulesStore ?: FlintPreferences.blockRules(appContext)
    private val editorStore = editorCommitmentStore ?: FlintPreferences.editorCommitments(appContext)

    /** Retained only for constructor-injected v1 adapter fixtures, never production charging. */
    private val openLimitPolicy by lazy { OpenLimitPolicy() }
    private val ruleEngine = BlockDecisionEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Observation(val timeLimitSpent: Boolean, val epochDay: Long, val url: String?)
    private val observations = ConcurrentHashMap<String, Observation>()

    private val recoveryBackend = protectionTransactionStore ?: if (focusStateStore == null) FlintPreferences.protectionTransactions(appContext) else null
    private val recoveries = recoveryBackend?.let { store ->
        GlobalRecoveryController(store, scope, statusClock,
            { ActiveRulesHolder.rulesOutside(ActiveRulesHolder.SOURCE_DATASTORE) },
            { EnforcementSignals.detectors.requestEvaluation(); onEnforcementChanged?.invoke(); rerenderNow() }, statusDispatcher)
    }
    // The f1 controller is dormant in production. Keep it only for existing constructor-
    // injected v1 adapter fixtures; production always has the complete atomic backend.
    private val legacyGrants = if (recoveryBackend == null) DurableGrantController(focusStore, scope, nowEpochMs, ::utcOffsetMs, BootIdentity.current(appContext)) else null
    private val focusState = MutableStateFlow<StorageState<com.flint.peakfocus.core.model.FocusSnapshot>>(StorageState.Loading)
    @Volatile private var focusLastGood: com.flint.peakfocus.core.model.FocusSnapshot? = null
    internal val recoveryState get() = recoveries?.state
    private val openState = MutableStateFlow<StorageState<List<OpenLimit>>>(StorageState.Loading)
    private val timeState = MutableStateFlow<StorageState<List<TimeLimit>>>(StorageState.Loading)
    private val rulesState = MutableStateFlow<StorageState<List<BlockRule>>>(StorageState.Loading)
    private val editorState = MutableStateFlow<StorageState<EditorCommitmentState>>(StorageState.Loading)
    private val statusLock = Any()
    private var lastPolicyStatus: ProtectionStatus? = null
    private val statusInvalidations = Channel<Unit>(Channel.CONFLATED)
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
        fun id(emergency: Boolean, now: Long, clock: AccessClockStamp? = null): GrantRequestId {
            val previous = if (emergency) passId else breakId
            if (previous != null && (if (clock != null) previous.issuedAtClock?.let {
                com.flint.peakfocus.blocking.engine.trustedElapsedMs(it, clock)?.let { age -> age < GlobalRecoveryPolicy.REQUEST_LIFETIME_MS }
            } == true else previous.issuedAtEpochMs <= now && now - previous.issuedAtEpochMs < DurableGrantController.RETRY_HORIZON_MS)) return previous
            return (clock?.let(GrantRequestId::issue) ?: GrantRequestId.issue(now)).also { if (emergency) passId = it else breakId = it }
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
            invalidateProtectionStatus()
        } }
        scope.launch { limitsStore.timeLimitsState.collect { state ->
            if (state is StorageState.Ready) timeLimits = state.value
            timeState.value = state
            invalidateProtectionStatus()
        } }
        scope.launch { rulesStore.rulesState.collect { rulesState.value = it; invalidateProtectionStatus() } }
        scope.launch { editorStore.state.collect { editorState.value = it; invalidateProtectionStatus() } }
        scope.launch { safetyFloor.runtimeResolution.collect { invalidateProtectionStatus() } }
        scope.launch {
            val unsubscribe = EnforcementSignals.detectors.subscribe(::publishPathAHealth)
            try { kotlinx.coroutines.awaitCancellation() } finally { unsubscribe() }
        }
        scope.launch { (legacyGrants?.state ?: focusStore.state).collect { state ->
            if (state is StorageState.Ready) {
                if (state.value.revision < (focusLastGood?.revision ?: -1L)) return@collect
                focusLastGood = state.value
            }
            focusState.value = state
            if (state is StorageState.Ready && legacyGrants != null) state.value.exemptions.values.forEach { receipt ->
                if (receipt.untilEpochMs > nowEpochMs() && scheduledExpiries.putIfAbsent(receipt.requestId, receipt.untilEpochMs) == null) {
                    scheduleExemptionExpiry(receipt.requestId, receipt.packageName, receipt.untilEpochMs)
                }
            }
            rerenderNow()
            invalidateProtectionStatus()
        } }
        scope.launch(statusDispatcher) {
            // Start after every source collector, including the focus projection. A Ready
            // synchronous source must reach the first publication without a coalescing delay.
            // ProtectionClock's lazy OS boot lookup still stays on this worker dispatcher.
            var first = true
            while (isActive) {
                if (!first) {
                    withTimeoutOrNull(ProtectionStatusProjection.TICK_MS) { statusInvalidations.receive() }
                    delay(STATUS_COALESCE_MS)
                    statusInvalidations.tryReceive()
                }
                first = false
                publishProtectionStatus()
            }
        }
        scope.launch {
            // Include readiness (and recovery), but exclude counters/receipts from the config
            // signal so our own debits cannot create an event-driven re-evaluation loop.
            combine(focusState, openState, timeState, rulesState) { focus, open, time, rules ->
                listOf(focus::class, (focus as? StorageState.Ready)?.value?.defaultBreakLevel,
                    open, time, rules)
            }.distinctUntilChanged().collect {
                runCatching { onEnforcementChanged?.invoke() }
                rerenderNow()
            }
        }
    }

    /** Cheap invalidation on each collector; policy work runs once on statusDispatcher. */
    private fun invalidateProtectionStatus() { statusInvalidations.trySend(Unit) }

    /** Real policy evaluation; display reads and detector-health callbacks never invoke this. */
    private fun publishProtectionStatus() {
        try {
            safetyFloor.refreshSoonIfOlderThan(SafetyFloorGate.DEFAULT_REFRESH_INTERVAL_MS)
            val now = statusClock() // first call stays off main, including lazy OS boot identity lookup
            val snapshot = ProtectionStatusProjection.evaluate(focusState.value, rulesState.value,
                timeState.value, openState.value, editorState.value, ActiveRulesHolder.rulesOutside(ActiveRulesHolder.SOURCE_DATASTORE),
                safetyFloor.runtimeResolution.value, EnforcementSignals.detectors.pathAConnected, now,
                timeLimitTier = { currentTimeLimitBreakLevel(it.packageName) })
            synchronized(statusLock) {
                val connected = EnforcementSignals.detectors.pathAConnected
                val health = snapshot.detectors.copy(accessibilityConnected = connected,
                    degradedReasons = snapshot.detectors.degradedReasons - "path-a-disconnected" + if (connected) emptySet() else setOf("path-a-disconnected"))
                lastPolicyStatus = snapshot.copy(detectors = health)
                ProtectionStatusHub.publish(checkNotNull(lastPolicyStatus))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            synchronized(statusLock) {
                val previous = lastPolicyStatus
                val failure = ProtectionStatus.UNKNOWN.copy(observedAt = previous?.observedAt,
                    validUntilElapsedRealtimeMs = previous?.validUntilElapsedRealtimeMs,
                    detectors = ProtectionStatus.DetectorHealth(EnforcementSignals.detectors.pathAConnected,
                        ProtectionStatus.PathBState.UNKNOWN, setOf("policy-check-failed")))
                lastPolicyStatus = failure
                ProtectionStatusHub.publish(failure)
            }
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
        if (protected && recoveries == null && openLimitPolicy.countsAsOpen(null, packageName, appContext.packageName)) visits.leave()
        return protected
    }

    /** Loaded tier for Time Limit displays; unknown accounting has no grant affordance. */
    fun currentDefaultBreakLevel(): BreakLevel = focusLastGood?.defaultBreakLevel ?: BreakLevel.HARDCORE

    /**
     * A saved limit owns its tier. Enforcement and the status publisher share this lookup.
     * Unknown limit storage cannot borrow ordinary default-tier rights. The default only
     * supplies a proven Ready legacy SharedPreferences budget without a DataStore record.
     */
    fun currentTimeLimitBreakLevel(packageName: String): BreakLevel =
        if (timeState.value !is StorageState.Ready) BreakLevel.HARDCORE
        else timeLimits.firstOrNull { it.packageName == packageName }?.breakLevel ?: currentDefaultBreakLevel()

    /**
     * The Limit editor's daily Time Limit for [packageName] (minutes), or null. Synchronous
     * snapshot read — safe on the a11y event hot path, same contract as the other snapshots.
     * Detectors take the stricter of this and the legacy `LimitStore` budget; before this
     * lookup existed, DataStore-authored Time Limits were persisted but never enforced.
     */
    fun timeLimitMinutes(packageName: String): Int? =
        timeLimits.firstOrNull { it.packageName == packageName }?.dailyMinutes

    /** Snapshot of the HARDER break bookkeeping (BlockActivity rebuilds its state from this). */
    fun currentBreakSession(): BreakSessionState {
        val pending = recoveries?.lastGood?.harder?.pending
        if (recoveries == null) return focusLastGood?.breakSession ?: BreakSessionState()
        if (pending == null) return BreakSessionState()
        val now = statusClock()
        val remaining = pending?.let { HarderWaitPolicy().remainingMs(it, now) } ?: return BreakSessionState()
        return BreakSessionState(pending = BreakRequestState(now.wallEpochMs - (pending.requiredMs - remaining), now.wallEpochMs + remaining))
    }

    internal fun requestClock(): AccessClockStamp? = recoveries?.lastGood?.let { statusClock() }

    /** True only for a validated rolling-pass availability, never a weekly/default state. */
    fun isEmergencyPassAvailable(): Boolean {
        val snapshot = (recoveries?.state?.value as? StorageState.Ready)?.value ?: return false
        val ledger = snapshot.focus.protection ?: return false
        return RollingEmergencyPassPolicy().eligibility(GlobalRecoveryPolicy.passState(ledger), statusClock()) == RollingEmergencyPassPolicy.Eligibility.Available
    }

    suspend fun previewEmergencyPass(): RecoveryPreview? = recoveries?.preview()
    suspend fun confirmEmergencyPass(preview: RecoveryPreview): RecoveryResult {
        val result = recoveries?.confirm(preview) ?: RecoveryResult.Unavailable()
        if (result is RecoveryResult.Accepted && result.newlyAccepted) result.recovery?.let {
            BlockEventHooks.emergencyPassCommitted(it.operationId, it.affectedRuleIds,
                it.startedAt.wallEpochMs + it.durationMs, it.startedAt.wallEpochMs)
        }
        invalidateProtectionStatus()
        return result
    }
    suspend fun resumeProtectionNow(): Boolean = recoveries?.resumeNow() == true
    suspend fun resumeOrdinaryBreaks(): Boolean = recoveries?.resumeOrdinary() == true
    suspend fun previewEndCommitments(selected: Set<String>, delete: Boolean): RecoveryEndPreview? = recoveries?.previewEnd(selected, delete)
    suspend fun endCommitmentsAndResume(preview: RecoveryEndPreview): Boolean = recoveries?.endAndResume(preview) == true
    suspend fun retryProtectionState(): Boolean = recoveries?.reconcile() == true

    /** Move to Flint's review surface without granting access to the blocked target. */
    internal fun launchRecovery(fallbackTarget: String? = null): Boolean {
        val expectedPackage = visiblePackage ?: return BlockActivity.launchRecovery(appContext, targetPackage = fallbackTarget)
        val expectedRequests = surfaceRequests ?: return false
        clearRecoveryHandoff()
        val (id, cancel) = RecoverySurfaceHandoff.register {
            runOnMain {
                // A late frame from an earlier launch cannot remove a newer target/mount.
                if (visiblePackage == expectedPackage && surfaceRequests === expectedRequests && !expectedRequests.retired)
                    hideLocked()
            }
        }
        cancelRecoveryHandoff = cancel
        if (!BlockActivity.launchRecovery(appContext, id, expectedPackage)) {
            clearRecoveryHandoff()
            return false
        }
        recoveryHandoffTimeout = scope.launch {
            delay(RECOVERY_SURFACE_TIMEOUT_MS)
            runOnMain {
                clearRecoveryHandoff()
                // A dropped/undrawn launch never removes the shield. Ask the detector for
                // the real foreground again; a retry must obtain a fresh frame token.
                if (visiblePackage == expectedPackage && surfaceRequests === expectedRequests) {
                    EnforcementSignals.detectors.requestEvaluation()
                    runCatching { onEnforcementChanged?.invoke() }
                }
            }
        }
        return true
    }

    private fun clearRecoveryHandoff() {
        cancelRecoveryHandoff?.invoke(); cancelRecoveryHandoff = null
        recoveryHandoffTimeout?.cancel(); recoveryHandoffTimeout = null
    }

    /** Re-check protection before a surface hides in response to a previously issued grant. */
    fun isExempt(packageName: String, displayedCause: BlockCause? = null): Boolean {
        val cause = currentBlockCause(packageName, displayedCause)
        if (recoveries != null) return BlockExemptions.hasGlobalRecovery() ||
            (cause?.breakLevel != BreakLevel.HARDCORE && ordinaryExempt(packageName, cause))
        return BlockExemptions.isExempt(packageName, nowEpochMs(), cause?.breakLevel ?: BreakLevel.EASY)
    }

    private fun ordinaryInput(packageName: String, cause: BlockCause? = null) = OrdinaryProtectionInput(packageName, appContext.packageName,
        observations[packageName]?.timeLimitSpent == true || cause?.reason == BlockScreenReason.TIME_LIMIT,
        inspectOpen(packageName) is OpenLimitDecision.Blocked, observations[packageName]?.url,
        cause?.endsAtEpochMs.takeIf { cause?.reason == BlockScreenReason.TIME_LIMIT })

    private fun ordinaryExempt(packageName: String, cause: BlockCause? = null): Boolean {
        val owner = recoveries ?: return BlockExemptions.hasOrdinaryExemption(packageName, nowEpochMs())
        val snapshot = owner.lastGood ?: return false
        val input = ordinaryInput(packageName, cause)
        owner.observeOrdinary(input)
        // A faster raw-source update cannot lend an old frozen grant to a new contributor.
        if ((rulesState.value as? StorageState.Ready)?.value != snapshot.rules ||
            (timeState.value as? StorageState.Ready)?.value != snapshot.times ||
            (openState.value as? StorageState.Ready)?.value != snapshot.opens) return false
        return owner.ordinaryCovers(input, statusClock())
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
        if (BlockExemptions.hasGlobalRecovery()) return null
        val now = nowEpochMs()
        val offset = utcOffsetMs(now)
        observations[packageName] = Observation(timeLimitSpent, epochDay(now, offset), foregroundUrl)
        val timeCause = if (timeLimitSpent) timeLimitBlockCause(currentTimeLimitBreakLevel(packageName), now, offset) else null
        val ordinary = ordinaryExempt(packageName, timeCause)
        val emergency = if (recoveries != null) BlockExemptions.hasGlobalRecovery() else BlockExemptions.hasEmergencyExemption(packageName, now)
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
        if (BlockExemptions.hasGlobalRecovery()) return null
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
            observation.epochDay == epochDay(now, offset)
        ) timeLimitBlockCause(currentTimeLimitBreakLevel(packageName), now, offset) else null
        val openDecision = inspectOpen(packageName)
        val openCause = (openDecision as? OpenLimitDecision.Blocked)?.let {
            openLimitBlockCause(it.limit, now, offset)
        }
        val liveDisplayed = displayedCause?.takeIf { it.endsAtEpochMs == null || now < it.endsAtEpochMs }
            ?.takeUnless { it.ruleId != null && rulesState.value is StorageState.Ready && ActiveRulesHolder.rules.none { rule -> rule.id == it.ruleId } }
            ?.takeUnless { it.reason == BlockScreenReason.OPEN_LIMIT && openState.value is StorageState.Ready && openCause == null }
            ?.let { if (it.reason == BlockScreenReason.TIME_LIMIT) it.copy(breakLevel = currentTimeLimitBreakLevel(packageName)) else it }
        return BlockOverlapPolicy.strongest(timeCause, ruleCause, openCause, liveDisplayed)
    }

    /** HARDER cooldown remaining at [atEpochMs], or null when none is pending. */
    fun breakWaitRemainingMillis(atEpochMs: Long): Long? =
        focusLastGood?.breakSession?.pending
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
        if (BlockExemptions.hasGlobalRecovery()) return OpenLimitDecision.Allowed(null)
        // ADR-012 has no launch debit or unbounded paid foreground visit. The following
        // timed-credit integration owns explicit acceptance; this path only inspects.
        if (recoveries != null) return inspectOpen(foregroundPackage)
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

    private fun counterInputsReady() = focusState.value is StorageState.Ready && openState.value is StorageState.Ready

    private fun grantInputs(cause: BlockCause, emergency: Boolean): List<StorageState<*>> =
        listOf(focusState.value) + when {
            // A global pass requires the complete atomic configuration/occurrence backend.
            emergency -> listOf(recoveries?.state?.value ?: StorageState.Unavailable(com.flint.peakfocus.core.model.StorageFailure.IO))
            cause.reason == BlockScreenReason.OPEN_LIMIT -> listOf(openState.value)
            cause.reason == BlockScreenReason.TIME_LIMIT -> listOf(timeState.value)
            else -> listOf(rulesState.value, ActiveRulesHolder.dataStoreState)
        }

    private fun grantInputsReady(cause: BlockCause, emergency: Boolean) =
        grantInputs(cause, emergency).all { it is StorageState.Ready }

    private fun inspectOpen(packageName: String, includeUnpaidVisit: Boolean = true): OpenLimitDecision {
        val limit = openLimits.firstOrNull { it.packageName == packageName } ?: return OpenLimitDecision.Allowed(null)
        if (recoveries != null) return OpenLimitDecision.Blocked(limit,
            if (recoveries.state.value is StorageState.Ready) "explicit timed credit required" else "storage unavailable")
        val focus = focusLastGood
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
        if (recoveries != null) return // never write legacy launch counts after ADR-012 activation
        if (openLimits.none { it.packageName == visit.packageName }) return
        if (!pendingOpens.add(visit.id.prefix)) return
        scope.launch {
            try {
                if (!counterInputsReady() || safetyFloor.protects(visit.packageName)) return@launch
                val limit = openLimits.firstOrNull { it.packageName == visit.packageName } ?: return@launch
                val result = checkNotNull(legacyGrants).recordOpen(visit.id, visit.packageName, limit)
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
        // A delayed evaluation may still name the underlying app after recovery has taken
        // foreground. Keep an existing shield until its frame ack, but never mount another
        // over Flint's recovery window. Activity pause removes this presentation-only guard.
        if (RecoverySurfaceHandoff.isShowing(packageName)) return
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

    /** Commit a target/frozen-occurrence break. Harder maturity still requires this explicit action. */
    suspend fun requestBreak(packageName: String, cause: BlockCause, requestId: GrantRequestId): GrantResult =
        if (BlockExemptions.hasGlobalRecovery()) GrantResult.Denied else requestGrant(packageName, cause, requestId)

    /** Old package-only callbacks lack a complete reviewed scope and can no longer spend. */
    @Deprecated("Open launchRecovery and explicitly confirm its complete RecoveryPreview")
    suspend fun requestEmergencyPass(packageName: String, cause: BlockCause, requestId: GrantRequestId): GrantResult =
        if (safetyFloor.protects(packageName)) GrantResult.Denied else GrantResult.Unavailable()

    private suspend fun requestGrant(packageName: String, cause: BlockCause, id: GrantRequestId): GrantResult {
        if (recoveries != null) return requestContractBreak(packageName, cause, id)
        if (safetyFloor.protectsFresh(packageName)) return GrantResult.Denied
        if (focusState.value !is StorageState.Ready) return GrantResult.Unavailable()
        if (!busy.add(packageName)) return GrantResult.Unavailable()
        failures.remove(packageName)
        rerenderNow()
        try {
            val current = currentBlockCause(packageName, cause) ?: return GrantResult.Denied
            if (!grantInputsReady(current, false)) return GrantResult.Unavailable()
            runOnMain { if (visiblePackage == packageName) shownCause = current }
            val retry = if (openRetryNeeded(packageName, current)) visits.current(packageName) else null
            val now = nowEpochMs()
            val result = if (retry != null) {
                checkNotNull(legacyGrants).recordOpen(retry.id, packageName, openLimits.firstOrNull { it.packageName == packageName }).also {
                    if (it == GrantResult.OpenAllowed) {
                        visits.acknowledge(retry)
                        runCatching { onEnforcementChanged?.invoke() }
                    }
                }
            } else {
                checkNotNull(legacyGrants).request(GrantRequest(
                    id, packageName, causeKey(packageName, current), GrantScope.Packages(setOf(packageName)),
                    DEFAULT_BREAK_DURATION_MS, current.breakLevel, false, current.spendsOpenOnBreak,
                    usesDefaultLevel = current.reason == BlockScreenReason.TIME_LIMIT && timeLimits.none { it.packageName == packageName },
                ), inputsReady = grantInputsReady(current, false))
            }
            if (result is GrantResult.Unavailable || result == GrantResult.Unconfirmed) failures.add(packageName)
            if (result is GrantResult.Granted && result.newlyAccepted) {
                // Observe-only history hooks happen strictly after the atomic edit returns.
                BlockEventHooks.breakGranted(current.ruleId, result.receipts.first().requestId,
                    result.receipts.maxOf { it.untilEpochMs }, result.atEpochMs)
            }
            return result
        } finally {
            busy.remove(packageName)
            rerenderNow()
        }
    }

    private suspend fun requestContractBreak(packageName: String, displayed: BlockCause, id: GrantRequestId): GrantResult {
        val owner = recoveries ?: return GrantResult.Unavailable()
        if (safetyFloor.protectsFresh(packageName)) return GrantResult.Denied
        if (owner.state.value !is StorageState.Ready || listOf(rulesState.value, timeState.value, openState.value, editorState.value).any { it !is StorageState.Ready })
            return GrantResult.Unavailable()
        if (!busy.add(packageName)) return GrantResult.Unavailable()
        failures.remove(packageName); rerenderNow()
        return try {
            val cause = currentBlockCause(packageName, displayed) ?: return GrantResult.Denied
            val limit = openLimits.firstOrNull { it.packageName == packageName }
            if (limit != null) {
                val credits = owner.lastGood?.openCredits ?: return GrantResult.Unavailable()
                // A free ordinary break cannot replace an unspent credit. R-11 supplies
                // the explicit credit CTA in the following PR; exhaustion keeps tier exits.
                if (OpenCreditLedgerPolicy.remainingCredits(credits, packageName, listOf(limit.dailyOpens)) != 0)
                    return GrantResult.Unavailable()
            }
            val result = owner.requestOrdinary(ordinaryInput(packageName, cause), id)
            if (result is GrantResult.Unavailable || result == GrantResult.Unconfirmed) failures.add(packageName)
            if (result is GrantResult.Granted && result.newlyAccepted) {
                val grant = owner.lastGood?.focus?.protection?.ordinaryGrants?.get(result.receipts.single().requestId)
                val ids = grant?.affectedRuleIds.orEmpty()
                if (ids.isEmpty()) BlockEventHooks.breakGranted(null, result.receipts.single().requestId, result.receipts.single().untilEpochMs, result.atEpochMs)
                else ids.forEach { BlockEventHooks.breakGranted(it, result.receipts.single().requestId, result.receipts.single().untilEpochMs, result.atEpochMs) }
            }
            result
        } finally { busy.remove(packageName); invalidateProtectionStatus(); rerenderNow() }
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
        val focus = focusLastGood ?: return true
        val now = nowEpochMs()
        return openLimitPolicy.decide(packageName, focus.openCounts, openLimits,
            openLimitPolicy.epochDay(now, utcOffsetMs(now))) is OpenLimitDecision.Allowed
    }

    fun screenState(packageName: String, label: String?, cause: BlockCause, now: Long): BlockScreenState {
        val inputs = grantInputs(cause, emergency = cause.breakLevel == BreakLevel.HARDCORE)
        val openEntryPending = recoveries?.lastGood?.openCredits?.let { credits ->
            openLimits.firstOrNull { it.packageName == packageName }?.let { OpenCreditLedgerPolicy.remainingCredits(credits, packageName, listOf(it.dailyOpens)) != 0 }
        } == true
        val status = when {
            inputs.any { it is StorageState.Loading } -> GrantStatus.LOADING
            inputs.any { it !is StorageState.Ready } -> GrantStatus.UNAVAILABLE
            openEntryPending -> GrantStatus.UNAVAILABLE
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
        if (emergency) {
            if (!launchRecovery()) { failures.add(packageName); rerenderNow() }
            return
        }
        val cause = shownCause ?: return
        val id = requests.id(emergency, nowEpochMs(), requestClock())
        scope.launch {
            val result = requestBreak(packageName, cause, id)
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
        clearRecoveryHandoff()
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
    private fun epochDay(now: Long, offset: Long): Long {
        val local = now + offset
        val remainder = ((local % DAY_MS) + DAY_MS) % DAY_MS
        return (local - remainder) / DAY_MS
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    companion object {
        /**
         * How long a granted break lifts the shield. Opal lets users pick a duration; Flint's
         * MVP fixes 5 minutes (a duration picker is UI-layer work, not this seam's).
         */
        const val DEFAULT_BREAK_DURATION_MS: Long = 5 * 60_000L

        /** Runtime-floor refresh age while a shield is visible (background, off the main thread). */
        private const val SHOWN_FLOOR_REFRESH_MS: Long = SafetyFloorGate.DEFAULT_FRESH_INTERVAL_MS

        /** Re-check a hair after expiry so the re-evaluation never races the ledger. */
        private const val EXPIRY_SLACK_MS: Long = 250L

        /** Policy stays blocked while IO is pending; avoid flashing a full screen for fast edits. */
        internal const val PENDING_OPEN_SURFACE_GRACE_MS: Long = 100L

        internal const val STATUS_COALESCE_MS: Long = 25L
        internal const val RECOVERY_SURFACE_TIMEOUT_MS: Long = 2_000L
    }
}
