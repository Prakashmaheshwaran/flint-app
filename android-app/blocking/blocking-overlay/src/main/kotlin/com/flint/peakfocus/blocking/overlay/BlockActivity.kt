package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageState
import com.flint.peakfocus.blocking.engine.RollingEmergencyPassPolicy
import com.flint.peakfocus.blocking.engine.ProtectionContract
import com.flint.peakfocus.feature.blockscreen.RecoveryScreen
import com.flint.peakfocus.feature.blockscreen.RecoveryScreenState
import com.flint.peakfocus.feature.blockscreen.RecoveryChoice
import com.flint.peakfocus.feature.blockscreen.RecoveryConfirmation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.flint.peakfocus.feature.blockscreen.BlockScreen
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen fallback host for the shared `BlockScreen` (feature-blockscreen) — used on
 * Path B when the SYSTEM_ALERT_WINDOW grant is missing, so the overlay can't be drawn.
 * Launched with FLAG_ACTIVITY_NEW_TASK from an *active* foreground service (never from the
 * cold background; see the Android-15 note in the Path B service).
 *
 * Same host contract as the overlay surfaces: this Activity owns the clock (1s re-render),
 * and delegates break granting, HARDER cooldown accounting and Emergency-Pass consumption to
 * the shared Path B [BlockScreenCoordinator] (via [PathBBlockHandoff]) — so state stays
 * identical whichever surface the user happens to see. Edge-to-edge, so the ember glow runs
 * behind the system bars like it does in the raw overlay windows. All colors come from
 * [FlintTheme] tokens; the window background is set from the theme at runtime (nothing
 * hard-coded, the old hex in themes.xml is gone).
 *
 * Refused exits — a Back press, a Hardcore break denial, a spent Emergency Pass — feed the
 * screen's nudge counter instead of silently no-oping: the block answers firmly (shake +
 * reject haptic) without ever opening.
 */
class BlockActivity : ComponentActivity() {

    /** One block launch, resolved from its intent extras. */
    private data class BlockedTarget(
        val packageName: String,
        val label: String?,
        val cause: BlockCause,
    )

    /** The blocked target as Compose-observable state: [onNewIntent] swaps it in place of
     *  recreate(), so the glow and breathing mark run continuously across re-used instances. */
    private var target by mutableStateOf<BlockedTarget?>(null)
    private var recoveryMode by mutableStateOf(false)
    private var recoveryHandoffId by mutableStateOf<String?>(null)
    private var recoveryTarget: String? = null
    private var recoveryFrameDrawn = false
    private var recoveryWindowFocused = false
    private var recoveryTopResumed = android.os.Build.VERSION.SDK_INT < 29
    private var recoveryTopCallbackSeen = false
    private var recoveryTopFallbackGranted = false
    private var recoveryTopFallback: Runnable? = null
    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recoveryMultiWindow = false
    private var recoveryFocusRevoked = false
    /** Recovery is a Flint settings surface, not a fallback shield owned by Path B. */
    internal val isRecoverySurface: Boolean get() = recoveryMode

    /** Refusal counter fed to `BlockScreen`'s nudge parameter — increments animate. */
    private var nudge by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        recoveryMultiWindow = android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
        super.onCreate(savedInstanceState)
        // Before setContent, so the glow draws behind the system bars on Android 14-and-below
        // too (the shared screen already safeDrawingPadding()s its content).
        enableEdgeToEdge()
        recoveryMode = intent.getBooleanExtra(EXTRA_RECOVERY, false)
        recoveryHandoffId = intent.getStringExtra(EXTRA_RECOVERY_HANDOFF)
        recoveryTarget = intent.getStringExtra(EXTRA_RECOVERY_TARGET)
        val initial = intent.toBlockedTarget()
        if (initial == null && !recoveryMode) {
            // Legacy/malformed launch (no package to account against): nothing to enforce.
            finish()
            return
        }
        // Revalidate before the first frame: a stale or safety-floor launch (dialer, home app,
        // Settings…) never shows even for the first tick.
        if (!recoveryMode && initial != null && PathBBlockHandoff.coordinator(applicationContext).currentBlockCause(initial.packageName, initial.cause) == null) {
            finish()
            return
        }
        target = initial

        // Back must not leave the block screen. A dispatcher callback (predictive-back safe)
        // replaces the old deprecated onBackPressed override — and instead of swallowing the
        // press silently it nudges: the screen shakes and reject-buzzes, firm but not dead.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (recoveryMode) { openFlint(); finish() } else nudge++
                }
            },
        )

        val coordinator = PathBBlockHandoff.coordinator(applicationContext)

        setContent {
            FlintTheme(darkTheme = true) {
                // Window background from the theme token — the XML theme stays palette-free
                // (design tokens are single-source); its only jobs are the transparent bars
                // for edge-to-edge and the system dark background for the one frame before
                // Compose draws — accepted.
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                }

                if (recoveryMode) {
                    RecoveryContent(coordinator)
                    return@FlintTheme
                }
                val current = target ?: return@FlintTheme
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var refresh by remember { mutableIntStateOf(0) }
                val requests = remember(current) { BlockScreenCoordinator.SurfaceRequests() }

                // Host-owned clock: tick the countdowns; leave when the block window ends or
                // an exemption (granted here or on another surface) starts covering the app.
                // Keyed on the target so a block swapped in by onNewIntent tracks its own
                // window.
                LaunchedEffect(current) {
                    while (isActive) {
                        delay(1_000L)
                        now = System.currentTimeMillis()
                        val effectiveCause = coordinator.currentBlockCause(current.packageName, current.cause)
                        if (effectiveCause == null || coordinator.isExempt(current.packageName, effectiveCause)) {
                            finish()
                            break
                        }
                        if (effectiveCause != current.cause) {
                            target = current.copy(cause = effectiveCause)
                            break // the new target starts a fresh effect with its current protection
                        }
                    }
                }

                val state = remember(current, now, refresh) {
                    coordinator.screenState(
                        current.packageName,
                        current.label,
                        current.cause,
                        now,
                    )
                }
                BlockScreen(
                    state = state,
                    onDismiss = {
                        goHome()
                        finish()
                    },
                    onOpenFlint = {
                        // Leaving for Flint is leaving the blocked app: end this block so the
                        // next block of it is a new interception (Flint's own foreground never
                        // reaches the handoff's hide path).
                        coordinator.hideIfShown()
                        openFlint()
                        finish()
                    },
                    onBreakRequested = {
                        if (!requests.retired) {
                            val id = requests.id(emergency = false, now = System.currentTimeMillis(), clock = coordinator.requestClock(), creditScope = state.openCreditRequestScope)
                            lifecycleScope.launch {
                                val result = coordinator.requestBreak(current.packageName, current.cause, id)
                                requests.recordResult(emergency = false, result)
                                if ((result is GrantResult.Granted || result == GrantResult.OpenAllowed) &&
                                    target == current && coordinator.canDismiss(current.packageName, current.cause)
                                ) {
                                    requests.retired = true
                                    finish()
                                } else if (result == GrantResult.Denied) nudge++
                                refresh++
                            }
                            refresh++ // display Saving before the next clock tick
                        }
                    },
                    onEmergencyPassRequested = {
                        handleRecoveryRequested(coordinator, requests)
                    },
                    nudge = nudge,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a new block (possibly another app) re-used this instance. Swapping the
        // observable target crossfades the words in place — no recreate(), so the glow and
        // the breathing mark never restart. A malformed re-launch keeps the current block.
        setIntent(intent)
        recoveryMode = intent.getBooleanExtra(EXTRA_RECOVERY, false)
        recoveryHandoffId = intent.getStringExtra(EXTRA_RECOVERY_HANDOFF)
        recoveryTarget = intent.getStringExtra(EXTRA_RECOVERY_TARGET)
        recoveryFrameDrawn = false
        recoveryWindowFocused = false
        recoveryFocusRevoked = false
        if (recoveryMode && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            refreshRecoverySurface()
        else RecoverySurfaceHandoff.hidden(this)
        if (recoveryMode) return
        val next = intent.toBlockedTarget() ?: return
        // A re-launch for a floor surface or an already-ended cause means the user is not on a
        // blocked app now: leave rather than cover what is in front (the detector re-launches
        // within a tick if a block is still due).
        if (PathBBlockHandoff.coordinator(applicationContext).currentBlockCause(next.packageName, next.cause) == null) {
            finish()
            return
        }
        target = next
    }

    internal fun handleRecoveryRequested(coordinator: BlockScreenCoordinator, requests: BlockScreenCoordinator.SurfaceRequests) {
        if (!requests.retired && coordinator.launchRecovery(target?.packageName)) requests.retired = true
    }

    private fun refreshRecoverySurface() {
        updateRecoveryTopFallback()
        val targetPackage = recoveryTarget
        // Resumed is not exclusive on Android 10+. Never suppress a target shield for
        // split-screen/freeform recovery: leave that mode to review in fullscreen.
        val mayYieldFocus = recoveryMode && recoveryFrameDrawn && targetPackage != null &&
            (recoveryTopResumed || recoveryTopFallbackGranted) && !recoveryMultiWindow && !recoveryFocusRevoked &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        // Yielding key focus keeps the opaque touch shield mounted. Requiring focus here
        // would deadlock a recovery whose initial focus is still owned by that shield.
        recoveryHandoffId?.let { RecoverySurfaceHandoff.ready(it, mayYieldFocus) }
        // Detachment and the presentation lease additionally require actual window focus.
        if (mayYieldFocus && recoveryWindowFocused) {
            RecoverySurfaceHandoff.showing(this, checkNotNull(targetPackage))
            recoveryHandoffId?.let(RecoverySurfaceHandoff::drawn)
        } else clearRecoverySurface()
    }

    private fun topFallbackEligible(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
        !recoveryTopCallbackSeen && !recoveryTopResumed && recoveryMode && recoveryFrameDrawn &&
        !recoveryMultiWindow && !recoveryFocusRevoked &&
        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun updateRecoveryTopFallback() {
        if (!topFallbackEligible()) { cancelRecoveryTopFallback(); return }
        if (recoveryTopFallbackGranted || recoveryTopFallback != null) return
        // Android may omit the top-resumed callback on a short resume. A continuously
        // drawn, resumed fullscreen Activity may request focus after this bounded delay.
        // It cannot detach the shield or acquire a lease until window focus then arrives.
        // An explicit false callback is authoritative and never enters this fallback.
        recoveryTopFallback = Runnable {
            recoveryTopFallback = null
            if (topFallbackEligible()) { recoveryTopFallbackGranted = true; refreshRecoverySurface() }
        }.also { recoveryHandler.postDelayed(it, RECOVERY_TOP_FALLBACK_MS) }
    }

    private fun cancelRecoveryTopFallback() {
        recoveryTopFallback?.let(recoveryHandler::removeCallbacks)
        recoveryTopFallback = null
        recoveryTopFallbackGranted = false
    }

    private fun clearRecoverySurface() {
        if (RecoverySurfaceHandoff.hidden(this))
            com.flint.peakfocus.core.model.EnforcementSignals.detectors.requestEvaluation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val lostFocus = recoveryWindowFocused && !hasFocus
        recoveryWindowFocused = hasFocus
        if (hasFocus) recoveryFocusRevoked = false
        else if (lostFocus && recoveryFrameDrawn) recoveryFocusRevoked = true
        refreshRecoverySurface()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        recoveryTopCallbackSeen = true
        if (isTopResumedActivity && !recoveryTopResumed) recoveryFocusRevoked = false
        recoveryTopResumed = isTopResumedActivity
        refreshRecoverySurface()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        recoveryMultiWindow = isInMultiWindowMode
        refreshRecoverySurface()
    }

    override fun onResume() {
        super.onResume()
        recoveryTopCallbackSeen = false
        if (recoveryMode) refreshRecoverySurface()
    }

    override fun onPause() {
        cancelRecoveryTopFallback()
        recoveryHandoffId?.let { RecoverySurfaceHandoff.ready(it, false) }
        clearRecoverySurface()
        recoveryWindowFocused = false
        if (android.os.Build.VERSION.SDK_INT >= 29) recoveryTopResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        cancelRecoveryTopFallback()
        recoveryHandoffId?.let { RecoverySurfaceHandoff.ready(it, false) }
        clearRecoverySurface()
        super.onDestroy()
    }

    /** Parses a launch/re-launch intent; null when there's no package to account against. */
    private fun Intent.toBlockedTarget(): BlockedTarget? {
        val blockedPackage = getStringExtra(EXTRA_BLOCKED_PACKAGE)
        if (blockedPackage.isNullOrBlank()) return null
        return BlockedTarget(
            packageName = blockedPackage,
            label = getStringExtra(EXTRA_BLOCKED_LABEL),
            cause = BlockCause(
                reason = enumExtra(EXTRA_REASON, BlockScreenReason.MANUAL_SESSION),
                breakLevel = enumExtra(EXTRA_BREAK_LEVEL, BreakLevel.EASY),
                endsAtEpochMs = getLongExtra(EXTRA_ENDS_AT_EPOCH_MS, NO_END).takeIf { it > 0L },
                ruleId = getStringExtra(EXTRA_RULE_ID),
                displayEnd = if (getBooleanExtra(EXTRA_HAS_DISPLAY_END, false))
                    BlockDisplayEnd(getLongExtra(EXTRA_DISPLAY_END, NO_END).takeIf { it > 0L }) else null,
            ),
        )
    }

    private fun goHome() = runCatching {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openFlint() = runCatching {
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
    }

    @Composable
    private fun RecoveryContent(coordinator: BlockScreenCoordinator) {
        val handoffId = recoveryHandoffId
        DisposableEffect(handoffId) {
            val view = window.decorView
            var active = true
            var posted = false
            val listener = android.view.ViewTreeObserver.OnDrawListener {
                if (!posted) {
                    posted = true
                    // Run after this traversal has drawn the composed recovery content.
                    view.post {
                        if (active && recoveryMode && recoveryHandoffId == handoffId && !isFinishing && !isDestroyed) {
                            recoveryFrameDrawn = true
                            refreshRecoverySurface()
                        }
                    }
                }
            }
            view.viewTreeObserver.addOnDrawListener(listener)
            onDispose {
                active = false
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
        val storage = coordinator.recoveryState?.collectAsState()?.value
        var tick by remember { mutableIntStateOf(0) }
        var preview by remember { mutableStateOf<RecoveryPreview?>(null) }
        var endPreview by remember { mutableStateOf<RecoveryEndPreview?>(null) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { while (isActive) {
            delay(1_000); tick++
            // Only drawn, focused, top-resumed fullscreen recovery renews this lease.
            if (recoveryFrameDrawn) refreshRecoverySurface()
        } }
        // The native clock is already initialized on the controller's worker before Ready.
        val snapshot = (storage as? StorageState.Ready)?.value
        val now = remember(tick, snapshot) { snapshot?.let { AccessClockStamp(System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtime(), it.observedAt.bootSessionId) } }
        val ledger = snapshot?.focus?.protection
        val remaining = if (now != null) GlobalRecoveryPolicy.remaining(ledger?.recovery, now) else 0
        val eligibility = if (ledger != null && now != null) RollingEmergencyPassPolicy().eligibility(GlobalRecoveryPolicy.passState(ledger), now)
            else RollingEmergencyPassPolicy.Eligibility.Unavailable
        fun date(at: Long) = SimpleDateFormat("EEE, MMM d, yyyy h:mm a z", Locale.getDefault()).format(Date(at))
        fun launch(action: suspend () -> Unit) {
            if (saving) return
            saving = true; error = null
            lifecycleScope.launch {
                try { action() }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { error = "Protection state unavailable. Try again." }
                finally { saving = false }
            }
        }
        val confirmation = preview?.let { p -> RecoveryConfirmation(p.affected.map {
            "${it.displayName} · ${if (it.transient) "ends now" else "suspended"}"
        }, date((now?.wallEpochMs ?: p.issuedAt.wallEpochMs) + ProtectionContract.PASS_RECOVERY_MS),
            date((now?.wallEpochMs ?: p.issuedAt.wallEpochMs) + ProtectionContract.PASS_COOLDOWN_MS)) }
        RecoveryScreen(
            RecoveryScreenState(loading = storage == null || storage is StorageState.Loading,
                unavailable = storage is StorageState.Unavailable || (storage is StorageState.Ready && eligibility == RollingEmergencyPassPolicy.Eligibility.Unavailable),
                available = eligibility == RollingEmergencyPassPolicy.Eligibility.Available, remainingRecoveryMillis = remaining,
                resumeAt = now?.let { if (remaining > 0) date(it.wallEpochMs + remaining) else null },
                nextPassAt = (eligibility as? RollingEmergencyPassPolicy.Eligibility.CoolingDown)?.let { date(it.nextEligibleEstimateEpochMs) },
                timeUncertain = (eligibility as? RollingEmergencyPassPolicy.Eligibility.CoolingDown)?.clockUncertain == true,
                previousAccessEnded = ledger?.previousAccessEnded == true || snapshot?.openCredits?.accessEndedAfterRestart == true,
                choices = snapshot?.let { snapshot ->
                    val canDisable = GlobalRecoveryPolicy.recoveryChoices(snapshot, deleteTemplates = false).keys
                    GlobalRecoveryPolicy.recoveryChoices(snapshot).map { (key, name) -> RecoveryChoice(key, name, key in canDisable) }
                }.orEmpty(),
                confirmation = confirmation, endConfirmation = endPreview?.displayNames,
                endConfirmationDeletes = endPreview?.deleteTemplates == true, error = error, saving = saving,
                activeOrdinaryBreaks = ledger?.ordinaryGrants?.values?.count { now != null && it.lifetime.remainingMs(now)?.let { ms -> ms > 0 } == true } ?: 0,
                activeOpenCredits = snapshot?.openCredits?.active?.values?.count { now != null && com.flint.peakfocus.blocking.engine.OpenCreditPolicy().remainingMs(it.grant, now) > 0 } ?: 0),
            onBack = { openFlint(); finish() },
            onPreview = { launch {
                preview = coordinator.previewEmergencyPass()
                if (preview == null) error = "The pass or complete recovery scope is unavailable. Try again."
            } },
            onConfirm = { val p = preview; if (p != null) launch {
                when (coordinator.confirmEmergencyPass(p)) {
                    is RecoveryResult.Accepted -> { preview = null; endPreview = null }
                    RecoveryResult.StalePreview -> { preview = null; error = "Commitments changed. Review the updated list before confirming." }
                    else -> error = "Recovery was not acknowledged. Try again with this confirmation."
                }
            } },
            onCancelConfirmation = { preview = null; endPreview = null },
            onResume = { launch { if (!coordinator.resumeProtectionNow()) error = "Protection state unavailable. Try again." } },
            onPreviewEnd = { selected, delete -> launch {
                endPreview = coordinator.previewEndCommitments(selected, delete)
                if (endPreview == null) error = "Commitments changed. Review the updated list."
            } },
            onConfirmEnd = { val p = endPreview; if (p != null) launch {
                if (coordinator.endCommitmentsAndResume(p)) endPreview = null
                else { endPreview = null; error = "The selection was not changed. Review and try again." }
            } },
            onRetry = { launch { if (!coordinator.retryProtectionState()) error = "Protection state unavailable. Try again." } },
            onResumeBreaks = { launch { if (!coordinator.resumeOrdinaryBreaks()) error = "Protection state unavailable. Try again." } },
            onResumeCredits = { launch { if (!coordinator.resumeOpenCredits()) error = "Protection state unavailable. Try again." } },
        )
    }

    private inline fun <reified T : Enum<T>> Intent.enumExtra(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(getStringExtra(key).orEmpty()) }.getOrDefault(fallback)

    companion object {
        internal const val RECOVERY_TOP_FALLBACK_MS = 250L
        const val EXTRA_BLOCKED_PACKAGE = "flint.blocked_package"
        const val EXTRA_BLOCKED_LABEL = "flint.blocked_label"
        const val EXTRA_REASON = "flint.block_reason"
        const val EXTRA_BREAK_LEVEL = "flint.break_level"
        const val EXTRA_ENDS_AT_EPOCH_MS = "flint.ends_at_epoch_ms"
        const val EXTRA_RULE_ID = "flint.rule_id"
        internal const val EXTRA_HAS_DISPLAY_END = "flint.has_display_end"
        internal const val EXTRA_DISPLAY_END = "flint.display_end"
        const val EXTRA_RECOVERY = "flint.protection_recovery"
        private const val NO_END = -1L

        /** App-shell entry independent of a blocked target. Opening it never spends a pass. */
        internal const val EXTRA_RECOVERY_HANDOFF = "flint.recovery_handoff"
        internal const val EXTRA_RECOVERY_TARGET = "flint.recovery_target"

        fun launchRecovery(context: Context, handoffId: String? = null, targetPackage: String? = null): Boolean = runCatching {
            context.startActivity(Intent(context, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RECOVERY, true).putExtra(EXTRA_RECOVERY_HANDOFF, handoffId)
                .putExtra(EXTRA_RECOVERY_TARGET, targetPackage))
        }.isSuccess

        /** Launch the fallback block screen for [packageName] with a resolved [cause]. */
        fun launch(context: Context, packageName: String, label: String?, cause: BlockCause) {
            // Best-effort: without SYSTEM_ALERT_WINDOW, background-activity-launch limits may
            // block this too — Path B degrades honestly (strategy doc §3).
            runCatching {
                context.startActivity(
                    Intent(context, BlockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_BLOCKED_PACKAGE, packageName)
                        .putExtra(EXTRA_BLOCKED_LABEL, label)
                        .putExtra(EXTRA_REASON, cause.reason.name)
                        .putExtra(EXTRA_BREAK_LEVEL, cause.breakLevel.name)
                        .putExtra(EXTRA_ENDS_AT_EPOCH_MS, cause.endsAtEpochMs ?: NO_END)
                        .putExtra(EXTRA_HAS_DISPLAY_END, cause.displayEnd != null)
                        .putExtra(EXTRA_DISPLAY_END, cause.displayEnd?.endsAtEpochMs ?: NO_END)
                        .putExtra(EXTRA_RULE_ID, cause.ruleId),
                )
            }
        }
    }
}
