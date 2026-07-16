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
import androidx.core.content.ContextCompat
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.NeverBlockSurfaces
import com.flint.peakfocus.blocking.overlay.PathBBlockHandoff
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.Verdict
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
    private lateinit var timeLimitGate: PathBTimeLimitGate
    private var neverBlock: Set<String> = emptySet()
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        poller = UsagePoller(this)
        val limitTracker = DailyLimitTracker(this)
        val limitStore = LimitStore(this)
        timeLimitGate = PathBTimeLimitGate(
            limitMinutesFor = { pkg ->
                listOfNotNull(
                    limitStore.limitMinutes(pkg),
                    PathBBlockHandoff.coordinator(this).timeLimitMinutes(pkg),
                ).minOrNull()
            },
            foregroundMillisToday = limitTracker::foregroundMillisToday,
        )
        neverBlock = NeverBlockSurfaces.resolve(this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-post the FGS notification on every start: a POST_NOTIFICATIONS grant that lands
        // after onCreate (the Android 13+ runtime ask) never resurrects a dropped notification
        // on its own — the next PathBServiceGate.sync() on app resume routes here and fixes it.
        startAsForeground()
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
        val now = System.currentTimeMillis()
        val pkg = poller.currentForegroundPackage(now) ?: return

        // Default launcher/dialer are never shielded; route as Allow so the overlay stands
        // down and open-limit transition bookkeeping stays intact (launcher→app IS an open).
        if (pkg in neverBlock) {
            PathBBlockHandoff.onForegroundPolled(this, pkg, Verdict.Allow)
            return
        }

        // Daily Time Limit — Path B's mirror of the a11y service's check, same order (ahead
        // of the rule verdict) and same threshold sources (legacy LimitStore + the Limit
        // editor's DataStore limits via the shared coordinator snapshot, stricter wins), so
        // the two paths can never disagree about a budget. [DailyLimitTracker] measures
        // consumption; its buckets are coarse/delayed — fine for daily budgets.
        if (timeLimitGate.isSpent(pkg, now)) {
            PathBBlockHandoff.onTimeLimitExceeded(this, pkg)
            return
        }

        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        // Schedule.daysOfWeek is ISO-numbered (1=Mon…7=Sun — core-model's documented contract,
        // and what feature-blocklist persists); Calendar.DAY_OF_WEEK is 1=Sun…7=Sat. Convert
        // before asking the engine, or "Weekdays" rules block Sunday and skip Friday.
        val weekday = IsoWeekday.fromCalendar(cal.get(java.util.Calendar.DAY_OF_WEEK))
        val verdict = engine.decide(pkg, null, ActiveRulesHolder.rules, packageName, nowMin, weekday, now)
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
        // The channel-id constructor is API 26+; below O channels don't exist yet.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // Brand strike silhouette (alpha-only) + spark accent, not a stock system glyph.
        val notification: Notification = builder
            .setContentTitle("Flint is keeping you focused")
            .setContentText("Enforcing your active blocks.")
            .setSmallIcon(R.drawable.ic_stat_flint)
            .setColor(ContextCompat.getColor(this, R.color.flint_spark))
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
