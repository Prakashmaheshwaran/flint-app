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
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.PathBBlockHandoff
import com.flint.peakfocus.blocking.overlay.PathBNotification
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
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
    private val engine = BlockDecisionEngine()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val displayWake = Channel<Unit>(Channel.CONFLATED)
    internal var queryDispatcher: CoroutineDispatcher = Dispatchers.IO
    private lateinit var poller: UsagePoller
    private lateinit var timeLimitGate: PathBTimeLimitGate
    private lateinit var notification: PathBForegroundNotification
    private var loop: Job? = null
    private var ready = false
    private var resetObservation = true
    private var confirmedMode: FallbackMode? = null
    private var lastPathAConnected = EnforcementSignals.detectors.pathAConnected
    private var stopSignals: (() -> Unit)? = null
    private var stopRules: (() -> Unit)? = null
    private var stopDisplay: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        health.starting(this)
        // Meet the FGS startup obligation before disk/AppOps/UsageStats work. This cannot
        // eliminate a system-server stall inside startForeground itself.
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(PathBForegroundNotification.CHANNEL_ID, "Focus enforcement", NotificationManager.IMPORTANCE_LOW),
            )
            notification = PathBForegroundNotification(this, { n ->
                if (Build.VERSION.SDK_INT >= 34) startForeground(PathBForegroundNotification.NOTIFICATION_ID,
                    n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(PathBForegroundNotification.NOTIFICATION_ID, n)
            }, { n -> manager.notify(PathBForegroundNotification.NOTIFICATION_ID, n) })
            notification.refresh(null)
            health.foregroundStarted(this)
        } catch (failure: RuntimeException) {
            health.unavailable(this, "foreground-start-failed")
            Log.w("FlintFallback", "Foreground startup unavailable", failure)
            stopSelf()
            return
        }
        poller = UsagePoller(this)
        val tracker = DailyLimitTracker(this)
        val limits = LimitStore(this)
        timeLimitGate = PathBTimeLimitGate(
            limitMinutesFor = { pkg -> listOfNotNull(
                limits.limitMinutes(pkg),
                PathBBlockHandoff.coordinator(this).timeLimitMinutes(pkg),
            ).minOrNull() },
            foregroundMillisToday = tracker::foregroundMillisToday,
        )
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
                if (connected) runCatching { PathBBlockHandoff.coordinator(this@UsageStatsForegroundService).hideIfShown() }
                wake.trySend(Unit)
            }
        }
        stopRules = ActiveRulesHolder.subscribe { wake.trySend(Unit) }
        stopDisplay = PathBNotification.subscribeRefresh { displayWake.trySend(Unit) }
        ready = true
        scope.launch {
            for (ignored in displayWake) if (ready) refreshNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-post after a notification permission grant and wake an existing loop after boot
        // or a clock-change start command; do not wait for its polling delay to elapse.
        if (!ready) return START_NOT_STICKY
        refreshNotification(force = true)
        resetObservation = true
        wake.trySend(Unit)
        if (loop?.isActive != true) {
            loop = scope.launch {
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
                        if (connected != EnforcementSignals.detectors.pathAConnected) continue
                        failureReason = "foreground-observation-unavailable"
                        if (mode == FallbackMode.OFF) {
                            health.unavailable(this@UsageStatsForegroundService, "usage-access-missing")
                            try { PathBBlockHandoff.coordinator(this@UsageStatsForegroundService).hideIfShown() }
                            finally { stopForegroundAndEvaluation() }
                            return@launch
                        }
                        val completed = if (mode == FallbackMode.ENFORCING) tick(tickStarted) else {
                            PathBBlockHandoff.coordinator(this@UsageStatsForegroundService).hideIfShown()
                            true
                        }
                        if (completed) {
                            confirmedMode = mode
                            health.mode(this@UsageStatsForegroundService, mode)
                            refreshNotification()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: RuntimeException) {
                        // Permission changes/binder failures must not permanently kill fallback.
                        resetObservation = true
                        confirmedMode = null
                        health.unavailable(this@UsageStatsForegroundService, failureReason)
                        refreshNotification()
                        Log.w("FlintFallback", "Foreground observation unavailable", failure)
                    }
                    withTimeoutOrNull(DetectorCadence.delayMs(mode)) { wake.receive() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun tick(tickStarted: Long): Boolean {
        if (resetObservation) {
            poller.reset()
            timeLimitGate.invalidate()
            UsageQuery.invalidate()
            resetObservation = false
        }
        val now = System.currentTimeMillis()
        val pkg = withContext(queryDispatcher) {
            timedQuery("usage_events_query_ms") { poller.currentForegroundPackage(now) }
        }
        if (EnforcementSignals.detectors.pathAConnected || resetObservation) return false
        if (pkg == null) {
            PathBBlockHandoff.coordinator(this).hideIfShown()
            return true
        }
        // C1 safety floor always precedes time budgets and ordinary rule/open causes.
        if (PathBBlockHandoff.coordinator(this).isSafetyFloor(pkg)) {
            PathBBlockHandoff.onForegroundPolled(this, pkg, Verdict.Allow)
            logDuration("tick_to_handoff_ms", tickStarted)
            return true
        }
        val spent = withContext(queryDispatcher) {
            timedQuery("budget_query_ms") { timeLimitGate.isSpent(pkg, now) }
        }
        if (EnforcementSignals.detectors.pathAConnected || resetObservation) return false
        val cal = java.util.Calendar.getInstance()
        val verdict = engine.decide(
            pkg, null, ActiveRulesHolder.rules, packageName,
            cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE),
            IsoWeekday.fromCalendar(cal.get(java.util.Calendar.DAY_OF_WEEK)), cal.timeInMillis,
        )
        PathBBlockHandoff.onForegroundPolled(this, pkg, verdict, spent)
        // This measures the handoff's return, not first window visibility or painted pixels.
        logDuration("tick_to_handoff_ms", tickStarted)
        return true
    }

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

    private fun refreshNotification(force: Boolean = false) {
        if (!ready) return
        // A failed display update must neither stop enforcement nor renew its lease.
        try { notification.refresh(confirmedMode, force) }
        catch (failure: RuntimeException) { Log.w("FlintFallback", "Notification refresh unavailable", failure) }
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopForegroundAndEvaluation() {
        ready = false
        try {
            removeForegroundNotification()
            health.stopped(this)
        } catch (failure: RuntimeException) {
            health.unavailable(this, "foreground-stop-failed")
            Log.w("FlintFallback", "Foreground stop unavailable", failure)
        } finally { stopSelf() }
    }

    override fun onDestroy() {
        ready = false
        stopSignals?.invoke()
        stopRules?.invoke()
        stopDisplay?.invoke()
        scope.cancel()
        wake.close()
        displayWake.close()
        try {
            if (health.isOwner(this)) {
                if (::poller.isInitialized) runCatching { PathBBlockHandoff.coordinator(this).hideIfShown() }
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
