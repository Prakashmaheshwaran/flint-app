package com.flint.peakfocus.blocking.usagestats

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.PathBBlockHandoff
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.Verdict
import java.util.Calendar

internal data class PathBObservation(val packageName: String?, val verdict: Verdict = Verdict.Allow,
    val timeLimitSpent: Boolean = false, val appLabel: String? = null)

/** Construct and use on the query dispatcher; nothing here creates a view or mounts a shield. */
internal class PathBQueryRuntime(context: Context) {
    private val app = context.applicationContext
    val coordinator = PathBBlockHandoff.coordinator(app)
    private val poller = UsagePoller(app)
    private val tracker = DailyLimitTracker(app)
    private val legacyLimits = LimitStore(app).also { it.all() } // Warm the initial XML read off main.
    private val engine = BlockDecisionEngine()
    private val timeLimitGate = PathBTimeLimitGate(
        limitMinutesFor = { pkg -> listOfNotNull(legacyLimits.limitMinutes(pkg), coordinator.timeLimitMinutes(pkg)).minOrNull() },
        foregroundMillisToday = tracker::foregroundMillisToday,
    )
    private var labelPackage: String? = null
    private var label: String? = null
    private var labelCheckedAt: Long? = null

    fun foregroundPackage(now: Long, reset: Boolean): String? {
        if (reset) {
            poller.reset()
            timeLimitGate.invalidate()
            UsageQuery.invalidate()
        }
        return timed("usage_events_query_ms") { poller.currentForegroundPackage(now) }
    }

    fun observeTarget(pkg: String, now: Long, rules: List<BlockRule>): PathBObservation {
        val spent = timed("budget_query_ms") { timeLimitGate.isSpent(pkg, now) }
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val verdict = engine.decide(pkg, null, rules, app.packageName,
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            IsoWeekday.fromCalendar(cal.get(Calendar.DAY_OF_WEEK)), now)
        val at = SystemClock.elapsedRealtime()
        // Labels are presentation only: up to 60s of staleness is intentional, and no
        // enforcement identity, target or tier decision depends on the cached display name.
        if (pkg != labelPackage || labelCheckedAt?.let { at < it || at - it >= LABEL_REFRESH_MS } != false) {
            labelPackage = pkg
            labelCheckedAt = at
            label = runCatching {
                val pm = app.packageManager
                @Suppress("DEPRECATION")
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
        }
        return PathBObservation(pkg, verdict, spent, label)
    }

    private inline fun <T> timed(name: String, action: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try { return action() } finally {
            if (BuildConfig.DEBUG) Log.d("FlintFallbackTiming", "$name=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private companion object { const val LABEL_REFRESH_MS = 60_000L }
}
