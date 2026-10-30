package com.flint.peakfocus.blocking.usagestats

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.IsoWeekday
import com.flint.peakfocus.blocking.overlay.PathBBlockHandoff
import com.flint.peakfocus.blocking.overlay.TrustedTimeLimitBudget
import com.flint.peakfocus.blocking.overlay.TrustedTimeLimitReader
import com.flint.peakfocus.blocking.overlay.TimeLimitForegroundSession
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.model.Verdict
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

internal data class PathBObservation(val packageName: String?, val verdict: Verdict = Verdict.Allow,
    val timeBudget: TrustedTimeLimitBudget? = null, val appLabel: String? = null)

/** Construct and use on the query dispatcher; nothing here creates a view or mounts a shield. */
internal class PathBQueryRuntime(context: Context) {
    private val app = context.applicationContext
    val coordinator = PathBBlockHandoff.coordinator(app)
    private val poller = UsagePoller(app)
    private val timeReader = TrustedTimeLimitReader.create(app)
    private val timeForeground = TimeLimitForegroundSession.create(app, TimeLimitForegroundSession.Path.B)
    private var checkpointForRead: Deferred<Unit>? = null
    private val legacyLimits = LimitStore(app).also { it.all() } // Warm the initial XML read off main.
    private val engine = BlockDecisionEngine()
    private val timeLimitGate = PathBTimeLimitGate(
        limitMinutesFor = { pkg -> listOfNotNull(legacyLimits.limitMinutes(pkg), coordinator.timeLimitMinutes(pkg)).minOrNull() },
        readBudget = { pkg, minutes -> timeReader.read(pkg, minutes) { checkpointForRead?.await() } },
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

    fun checkpointForeground(pkg: String?): Deferred<Unit> = timeForeground.checkpoint(pkg?.takeIf {
        !coordinator.hasVisibleShield(it) && listOfNotNull(legacyLimits.limitMinutes(it), coordinator.timeLimitMinutes(it)).isNotEmpty()
    })

    fun closeForeground() { timeForeground.close() }

    suspend fun observeTarget(pkg: String, now: Long, rules: List<BlockRule>, checkpoint: Deferred<Unit>? = null): PathBObservation {
        checkpointForRead = checkpoint
        try { checkpoint?.await() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { timeLimitGate.invalidate() } // Reader reports the checkpoint failure.
        val budget = timed("budget_query_ms") { timeLimitGate.budget(pkg, ProtectionClock.now()) }
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
        return PathBObservation(pkg, verdict, budget, label)
    }

    private inline fun <T> timed(name: String, action: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try { return action() } finally {
            if (BuildConfig.DEBUG) Log.d("FlintFallbackTiming", "$name=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private companion object { const val LABEL_REFRESH_MS = 60_000L }
}
