package com.flint.peakfocus.blocking.usagestats

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.flint.peakfocus.blocking.overlay.PathBBlockHandoff
import com.flint.peakfocus.blocking.overlay.PathBNotification
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.DetectorCadence
import com.flint.peakfocus.core.model.DetectorFailover
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.model.FallbackMode
import com.flint.peakfocus.core.model.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A persistent specialUse FGS, started while the app is visible or from an allowed boot/time
 * broadcast. While Path A is actually bound it stays in cheap standby (no UsageEvents queries).
 * Unbind/destruction wakes fallback immediately; it never depends on an Android 12+ background
 * FGS start after revocation. The trade-off is an ongoing, honestly labelled notification.
 */
class UsageStatsForegroundService : Service() {
    // Serialize shield ownership on Main with Path A; expensive platform reads run on IO and
    // re-check ownership after suspension before they can hand off a decision.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val displayWake = Channel<Unit>(Channel.CONFLATED)
    internal var queryDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var notificationDispatcher: CoroutineDispatcher = Dispatchers.IO
    /** Fault-injection seam for a cold store/provider; invoked only on the query dispatcher. */
    internal var beforeRuntimeInitialization: (() -> Unit)? = null
    private lateinit var queryRuntime: PathBQueryRuntime
    private lateinit var notification: PathBForegroundNotification
    private var loop: Job? = null
    @Volatile private var ready = false
    private var foregroundPosted = false
    @Volatile private var stopping = false
    @Volatile private var displayGeneration = 0L
    private var forceDisplayPending = false
    private var resetObservation = true
    private var confirmedMode: FallbackMode? = null
    private var lastPathAConnected = EnforcementSignals.detectors.pathAConnected
    private var stopSignals: (() -> Unit)? = null
    private var stopRules: (() -> Unit)? = null
    private var stopDisplay: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        health.starting(this)
        stopSignals = EnforcementSignals.detectors.subscribe {
            scope.launch {
                resetObservation = true
                val connected = EnforcementSignals.detectors.pathAConnected
                if (connected != lastPathAConnected) {
                    lastPathAConnected = connected
                    confirmedMode = null
                    health.handoff(this@UsageStatsForegroundService)
                    displayWake.trySend(Unit)
                }
                if (connected && ready) {
                    queryRuntime.checkpointForeground(null)
                    runCatching { queryRuntime.coordinator.hideIfShown() }
                }
                wake.trySend(Unit)
            }
        }
        stopRules = ActiveRulesHolder.subscribe { wake.trySend(Unit) }
        stopDisplay = PathBNotification.subscribeRefresh { displayWake.trySend(Unit) }
        scope.launch {
            for (ignored in displayWake) if (ready) {
                val force = forceDisplayPending
                forceDisplayPending = false
                refreshNotification(force)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only mandatory platform channel/startForeground calls precede this return path.
        // No customizer, preference, AppOps, UsageStats or PackageManager query runs here.
        // Android can deliver a new start before the stopSelf/onDestroy pair completes.
        // It carries a new FGS obligation, including after permission or initialization failure.
        if (stopping) {
            stopping = false
            confirmedMode = null
            health.starting(this)
        }
        if (!ensureForeground()) return START_NOT_STICKY
        requestNotification(force = true)
        resetObservation = true
        wake.trySend(Unit)
        if (loop?.isActive != true) {
            loop = scope.launch {
                if (!ready) {
                    try {
                        val runtime = withContext(queryDispatcher) {
                            beforeRuntimeInitialization?.invoke()
                            currentCoroutineContext().ensureActive()
                            PathBQueryRuntime(this@UsageStatsForegroundService)
                        }
                        if (stopping || !health.isOwner(this@UsageStatsForegroundService)) return@launch
                        queryRuntime = runtime
                        ready = true
                        requestNotification(force = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        health.unavailable(this@UsageStatsForegroundService, "service-initialization-failed")
                        stopping = true
                        try { removeForegroundNotification() }
                        catch (stopFailure: RuntimeException) {
                            Log.w("FlintFallback", "Foreground stop unavailable", stopFailure)
                        }
                        finally { stopSelf() }
                        Log.w("FlintFallback", "Foreground observation initialization unavailable", failure)
                        return@launch
                    }
                }
                while (isActive && ready) {
                    val connected = EnforcementSignals.detectors.pathAConnected
                    var mode = if (connected) FallbackMode.STANDBY else FallbackMode.ENFORCING
                    var failureReason = "usage-access-check-failed"
                    val tickStarted = SystemClock.elapsedRealtime()
                    try {
                        // Standby probes once a minute or on an explicit detector/rule wake.
                        // Every lease renewal has a real successful permission observation.
                        mode = withContext(queryDispatcher) {
                            timedQuery("usage_access_query_ms") {
                                DetectorFailover.mode(usageAccessGranted(), connected)
                            }
                        }
                        if (!health.isOwner(this@UsageStatsForegroundService)) return@launch
                        if (connected != EnforcementSignals.detectors.pathAConnected) {
                            // A connection change invalidated this probe. Yield before retrying
                            // so binding churn cannot create a tight AppOps binder loop.
                            delay(100)
                            continue
                        }
                        failureReason = "foreground-observation-unavailable"
                        if (mode == FallbackMode.OFF) {
                            health.unavailable(this@UsageStatsForegroundService, "usage-access-missing")
                            try { queryRuntime.coordinator.hideIfShown() }
                            finally { stopForegroundAndEvaluation() }
                            return@launch
                        }
                        val completed = if (mode == FallbackMode.ENFORCING) tick(tickStarted) else {
                            queryRuntime.checkpointForeground(null)
                            queryRuntime.coordinator.hideIfShown()
                            true
                        }
                        if (completed) {
                            val modeChanged = confirmedMode != mode
                            confirmedMode = mode
                            health.mode(this@UsageStatsForegroundService, mode)
                            if (modeChanged) requestNotification()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: RuntimeException) {
                        // Permission changes/binder failures must not permanently kill fallback.
                        if (::queryRuntime.isInitialized) queryRuntime.checkpointForeground(null)
                        resetObservation = true
                        val modeChanged = confirmedMode != null
                        confirmedMode = null
                        health.unavailable(this@UsageStatsForegroundService, failureReason)
                        if (modeChanged) requestNotification()
                        Log.w("FlintFallback", "Foreground observation unavailable", failure)
                    }
                    withTimeoutOrNull(DetectorCadence.delayMs(mode)) { wake.receive() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun tick(tickStarted: Long): Boolean {
        val reset = resetObservation
        resetObservation = false
        val now = System.currentTimeMillis()
        val rules = ActiveRulesHolder.rules
        val pkg = withContext(queryDispatcher) {
            queryRuntime.foregroundPackage(now, reset)
        }
        if (observationInvalidated()) return false
        if (pkg == null) {
            queryRuntime.checkpointForeground(null)
            queryRuntime.coordinator.hideIfShown()
            return true
        }
        // Cached membership is nonblocking. Keep its visit-departure mutation on Main,
        // after ownership revalidation, so an invalidated Path B sample cannot end A's visit.
        if (queryRuntime.coordinator.isSafetyFloor(pkg)) {
            queryRuntime.checkpointForeground(null)
            PathBBlockHandoff.onForegroundPolled(this, pkg, Verdict.Allow, rules = rules)
            logDuration("tick_to_handoff_ms", tickStarted)
            return true
        }
        val checkpoint = queryRuntime.checkpointForeground(pkg)
        val observation = withContext(queryDispatcher) { queryRuntime.observeTarget(pkg, now, rules, checkpoint) }
        if (observationInvalidated()) return false
        val blocked = PathBBlockHandoff.onForegroundPolled(this, pkg, observation.verdict,
            appLabel = observation.appLabel, rules = rules, timeBudget = observation.timeBudget)
        queryRuntime.checkpointForeground(pkg.takeUnless { blocked })
        // This measures the handoff's return, not first window visibility or painted pixels.
        logDuration("tick_to_handoff_ms", tickStarted)
        return true
    }

    private fun observationInvalidated() =
        !health.isOwner(this) || EnforcementSignals.detectors.pathAConnected || resetObservation

    private inline fun <T> timedQuery(label: String, query: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try { return query() } finally { logDuration(label, started) }
    }

    private fun logDuration(label: String, started: Long) {
        if (BuildConfig.DEBUG) Log.d("FlintFallbackTiming", "$label=${SystemClock.elapsedRealtime() - started}")
    }

    private fun usageAccessGranted(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return permission == AppOpsManager.MODE_ALLOWED
    }

    private fun ensureForeground(): Boolean {
        if (foregroundPosted) return true
        return try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(PathBForegroundNotification.CHANNEL_ID, "Focus enforcement", NotificationManager.IMPORTANCE_LOW))
            val generation = displayGeneration
            notification = PathBForegroundNotification(this, { n ->
                if (Build.VERSION.SDK_INT >= 34) startForeground(PathBForegroundNotification.NOTIFICATION_ID,
                    n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(PathBForegroundNotification.NOTIFICATION_ID, n)
            }, { n ->
                // A customizer may finish after cancellation; never republish for a retired
                // service instance or after a permission-driven stop.
                if (generation == displayGeneration && ready && !stopping && health.isOwner(this)) {
                    manager.notify(PathBForegroundNotification.NOTIFICATION_ID, n)
                }
            })
            notification.startMinimal()
            foregroundPosted = true
            health.foregroundStarted(this)
            true
        } catch (failure: RuntimeException) {
            stopping = true
            health.unavailable(this, "foreground-start-failed")
            Log.w("FlintFallback", "Foreground startup unavailable", failure)
            stopSelf()
            false
        }
    }

    private fun requestNotification(force: Boolean = false) {
        forceDisplayPending = forceDisplayPending || force
        displayWake.trySend(Unit)
    }

    private suspend fun refreshNotification(force: Boolean = false) {
        if (!ready) return
        // A failed display update must neither stop enforcement nor renew its lease.
        val mode = confirmedMode
        val display = notification
        try { withContext(notificationDispatcher) { display.refresh(mode, force) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RuntimeException) { Log.w("FlintFallback", "Notification refresh unavailable", failure) }
    }

    private fun removeForegroundNotification() {
        foregroundPosted = false
        displayGeneration++ // Retire even a still-blocked customizer before the next start.
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopForegroundAndEvaluation() {
        if (::queryRuntime.isInitialized) queryRuntime.closeForeground()
        ready = false
        stopping = true
        try {
            removeForegroundNotification()
            health.stopped(this)
        } catch (failure: RuntimeException) {
            health.unavailable(this, "foreground-stop-failed")
            Log.w("FlintFallback", "Foreground stop unavailable", failure)
        } finally { stopSelf() }
    }

    override fun onDestroy() {
        if (::queryRuntime.isInitialized) queryRuntime.closeForeground()
        ready = false
        stopping = true
        stopSignals?.invoke()
        stopRules?.invoke()
        stopDisplay?.invoke()
        scope.cancel()
        wake.close()
        displayWake.close()
        try {
            if (health.isOwner(this)) {
                if (::queryRuntime.isInitialized) runCatching { queryRuntime.coordinator.hideIfShown() }
                removeForegroundNotification()
            }
        } finally {
            health.stopped(this)
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val health = PathBServiceLifecycle(SystemClock::elapsedRealtime,
            publish = ProtectionStatusHub::publishPathBHealth)

        fun start(context: Context) {
            val intent = Intent(context, UsageStatsForegroundService::class.java)
            try {
                val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                if (component != null) health.startRequested() else health.startFailed()
            } catch (failure: RuntimeException) {
                health.startFailed()
                Log.w("FlintFallback", "Foreground start request unavailable", failure)
            }
        }

        fun stop(context: Context, degradedReasons: Set<String> = emptySet()) {
            val observedGeneration = health.stopGeneration()
            try {
                val wasRunning = context.stopService(Intent(context, UsageStatsForegroundService::class.java))
                health.stopRequested(observedGeneration, wasRunning, degradedReasons)
            } catch (failure: RuntimeException) {
                health.stopFailed(observedGeneration)
                Log.w("FlintFallback", "Foreground stop request unavailable", failure)
            }
        }

        fun permissionCheckFailed() { health.gateFailed("usage-access-check-failed") }
    }
}
