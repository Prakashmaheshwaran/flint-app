package com.flint.peakfocus.blocking.usagestats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.PathBBlockHandoff
import com.flint.peakfocus.core.model.ActiveRulesHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Path B liveness: a persistent foreground service that polls [UsagePoller] when the
 * AccessibilityService is unavailable. Lowest kill-priority (ongoing notification). When a11y
 * is active, the poll interval can be slowed to save battery (a11y events are the primary
 * trigger) — see the Android strategy doc §6.5.
 *
 * Android 15 note: a background-started FGS needs a visible overlay first; keep this service
 * persistently alive (started from the app/onboarding while in foreground) so it never needs a
 * background start.
 */
class UsageStatsForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = BlockDecisionEngine()
    private lateinit var poller: UsagePoller
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        poller = UsagePoller(this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    tick()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun tick() {
        val pkg = poller.currentForegroundPackage(System.currentTimeMillis()) ?: return
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        // Calendar.DAY_OF_WEEK (1=Sun…7=Sat) → the engine's ISO numbering (1=Mon…7=Sun) via the
        // one shared, tested converter — the same one Path A uses, so both paths gate identically.
        val weekday = IsoWeekday.fromCalendar(cal.get(java.util.Calendar.DAY_OF_WEEK))
        val verdict = engine.decide(pkg, null, ActiveRulesHolder.rules, packageName, nowMin, weekday)
        // Path B enforcement handoff — every tick, Allow included: open-limit counting and
        // overlay stand-down happen behind this call, not just Block rendering.
        PathBBlockHandoff.onForegroundPolled(this, pkg, verdict)
    }

    private fun startAsForeground() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Focus enforcement", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Flint is keeping you focused")
            .setContentText("Enforcing your active blocks.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "flint_enforcement"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 1_000L

        fun start(context: Context) {
            val intent = Intent(context, UsageStatsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the poll loop (e.g. when Path A takes over — running both double-enforces). */
        fun stop(context: Context) {
            context.stopService(Intent(context, UsageStatsForegroundService::class.java))
        }
    }
}
