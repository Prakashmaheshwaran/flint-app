package com.flint.peakfocus.blocking.usagestats

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.PathBBlockHandoff
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.DetectorFailover
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.model.FallbackMode
import com.flint.peakfocus.core.model.Verdict
import kotlinx.coroutines.CancellationException
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
    private lateinit var poller: UsagePoller
    private lateinit var timeLimitGate: PathBTimeLimitGate
    private var loop: Job? = null
    private var resetObservation = true
    private var notifiedMode: FallbackMode? = null
    private var stopSignals: (() -> Unit)? = null
    private var stopRules: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
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
                if (EnforcementSignals.detectors.pathAConnected) {
                    PathBBlockHandoff.coordinator(this@UsageStatsForegroundService).hideIfShown()
                }
                wake.trySend(Unit)
            }
        }
        stopRules = ActiveRulesHolder.subscribe { wake.trySend(Unit) }
        startAsForeground(currentMode())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-post after a notification permission grant and wake an existing loop after boot
        // or a clock-change start command; do not wait for its polling delay to elapse.
        startAsForeground(currentMode())
        resetObservation = true
        wake.trySend(Unit)
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    try {
                        val mode = currentMode()
                        if (mode != notifiedMode) startAsForeground(mode)
                        if (mode == FallbackMode.OFF) {
                            PathBBlockHandoff.coordinator(this@UsageStatsForegroundService).hideIfShown()
                            stopSelf()
                            return@launch
                        }
                        if (mode == FallbackMode.ENFORCING) tick()
                        else PathBBlockHandoff.coordinator(this@UsageStatsForegroundService).hideIfShown()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: RuntimeException) {
                        // Permission changes/binder failures must not permanently kill fallback.
                        resetObservation = true
                        Log.w("FlintFallback", "Foreground observation unavailable", failure)
                    }
                    withTimeoutOrNull(POLL_INTERVAL_MS) { wake.receive() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun tick() {
        if (resetObservation) {
            poller.reset()
            timeLimitGate.invalidate()
            UsageQuery.invalidate()
            resetObservation = false
        }
        val now = System.currentTimeMillis()
        val pkg = withContext(Dispatchers.IO) { poller.currentForegroundPackage(now) }
        if (EnforcementSignals.detectors.pathAConnected || resetObservation) return
        if (pkg == null) {
            PathBBlockHandoff.coordinator(this).hideIfShown()
            return
        }
        // C1 safety floor always precedes time budgets and ordinary rule/open causes.
        if (PathBBlockHandoff.coordinator(this).isSafetyFloor(pkg)) {
            PathBBlockHandoff.onForegroundPolled(this, pkg, Verdict.Allow)
            return
        }
        val spent = withContext(Dispatchers.IO) { timeLimitGate.isSpent(pkg, now) }
        if (EnforcementSignals.detectors.pathAConnected || resetObservation) return
        val cal = java.util.Calendar.getInstance()
        val verdict = engine.decide(
            pkg, null, ActiveRulesHolder.rules, packageName,
            cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE),
            IsoWeekday.fromCalendar(cal.get(java.util.Calendar.DAY_OF_WEEK)), cal.timeInMillis,
        )
        PathBBlockHandoff.onForegroundPolled(this, pkg, verdict, spent)
    }

    private fun currentMode(): FallbackMode {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        val granted = permission == AppOpsManager.MODE_ALLOWED
        return DetectorFailover.mode(granted, EnforcementSignals.detectors.pathAConnected)
    }

    private fun startAsForeground(mode: FallbackMode) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Focus enforcement", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("Flint focus protection is running")
            .setContentText(when (mode) {
                FallbackMode.STANDBY -> "Backup ready while Accessibility is connected."
                FallbackMode.ENFORCING -> "Checking active blocks with Usage Access."
                FallbackMode.OFF -> "Usage Access is unavailable."
            })
            .setSmallIcon(R.drawable.ic_stat_flint)
            .setColor(ContextCompat.getColor(this, R.color.flint_spark))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, notification)
        notifiedMode = mode
    }

    override fun onDestroy() {
        stopSignals?.invoke()
        stopRules?.invoke()
        scope.cancel()
        wake.close()
        PathBBlockHandoff.coordinator(this).hideIfShown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "flint_enforcement"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            val intent = Intent(context, UsageStatsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) { context.stopService(Intent(context, UsageStatsForegroundService::class.java)) }
    }
}
