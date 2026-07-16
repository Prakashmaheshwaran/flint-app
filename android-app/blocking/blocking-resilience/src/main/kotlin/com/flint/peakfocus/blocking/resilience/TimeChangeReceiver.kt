package com.flint.peakfocus.blocking.resilience

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.flint.peakfocus.blocking.engine.ClockChangeGuard
import com.flint.peakfocus.blocking.engine.ClockShift
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Time-change re-arm (anti-bypass, spec 2.4/2.5): users can dodge schedules and daily limits
 * by moving the device clock or hopping timezones. This receiver is the Android half of the
 * guard — it *measures* the change and forces re-evaluation; every *judgment* lives in the
 * pure [ClockChangeGuard] / the forward-only rollovers inside the engine policies (which hold
 * on every evaluation even when this broadcast never arrives).
 *
 * What it does, in order (mirrors [BootReceiver]):
 *  1. **Measure + re-baseline** — compares the new wall clock against the persisted anchor
 *     (last wall time + monotonic elapsedRealtime, which never follows the wall clock), then
 *     stores a fresh anchor. `FlintApplication.onCreate` baselines it at process start via
 *     [baselineAnchor], so a reboot (elapsedRealtime restarts) never fakes a jump.
 *  2. **Cache warm-up** — [BlocklistStore.load], exactly like boot: this broadcast may have
 *     started a dead process, and re-evaluation needs live rules in `ActiveRulesHolder`.
 *  3. **Fail-closed re-keying** — applies [ClockChangeGuard] to the persisted focus state
 *     (open counts, Emergency Pass, pending HARDER wait) via `FocusStateStore`; the live
 *     coordinators on both detection paths collect that store, so a guarded write reaches
 *     enforcement without any service dependency. Skipped for DATE_CHANGED — that action
 *     also fires at *genuine* local midnight (legacy devices), which must stay an honest
 *     rollover; manual date edits always fire TIME_SET as well, so nothing is lost.
 *  4. **Registered re-arm hooks** — runs every action added via [registerRearmAction], the
 *     same seam (and, in the app shell, the same Path B gate hook) as [BootReceiver].
 *
 * Delivery is best-effort and never load-bearing: TIME_SET and TIMEZONE_CHANGED are on the
 * implicit-broadcast exceptions list (manifest receivers still get them on API 26+), but a
 * clock changed while the device is off is invisible to everyone. The always-on backward
 * guards in the engine are the backstop, same role [PermissionHealthChecker] plays for boot.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guard = ClockChangeGuard()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!shouldHandle(action)) return
        val appContext = context.applicationContext

        // 1. Measure the jump against the anchor, then re-baseline. Synchronous
        //    SharedPreferences on a tiny file — well inside a receiver's time budget.
        val shift = measureAndRebaseline(appContext, intent)

        // 2. Warm the in-memory rules cache from persisted state (same rationale as boot).
        runCatching { BlocklistStore(appContext).load() }

        // 3. Conservative re-keying of persisted quota/pass/break state (pure policy calls;
        //    DataStore I/O rides on goAsync so the process stays alive until it lands).
        if (rekeysFocusState(action)) {
            applyGuards(appContext, shift, goAsync())
        }

        // 4. App-registered re-arm hooks. Failures are isolated so one bad hook cannot
        //    break the rest of re-arm.
        for (rearm in rearmActions) {
            runCatching { rearm(appContext) }
        }
    }

    /** Read the previous anchor, derive the [ClockShift] it implies, persist a fresh anchor. */
    private fun measureAndRebaseline(appContext: Context, intent: Intent): ClockShift {
        val prefs = appContext.getSharedPreferences(ANCHOR_PREFS, Context.MODE_PRIVATE)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        // TIMEZONE_CHANGED carries the new zone ID ("time-zone" extra, aka Intent.EXTRA_TIMEZONE
        // on API 30+); prefer it over TimeZone.getDefault(), whose cached value can lag the
        // broadcast. TIME_SET / DATE_CHANGED carry no extra — the zone did not move.
        val newZone = intent.getStringExtra(EXTRA_TIME_ZONE_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(TimeZone::getTimeZone)
            ?: TimeZone.getDefault()
        val newOffset = newZone.getOffset(nowWall).toLong()

        val anchorWall = prefs.getLong(KEY_ANCHOR_WALL, Long.MIN_VALUE)
        val anchorElapsed = prefs.getLong(KEY_ANCHOR_ELAPSED, Long.MIN_VALUE)
        val anchorOffset = prefs.getLong(KEY_ANCHOR_OFFSET, newOffset)
        val shift = if (anchorWall == Long.MIN_VALUE || anchorElapsed == Long.MIN_VALUE) {
            ClockShift.unmeasured(nowWall, anchorOffset, newOffset)
        } else {
            ClockShift.fromAnchor(anchorWall, anchorElapsed, nowWall, nowElapsed, anchorOffset, newOffset)
        }
        writeAnchor(prefs, nowWall, nowElapsed, newOffset)
        return shift
    }

    /**
     * Re-key the persisted focus state through [ClockChangeGuard], then release [pending].
     * Best-effort: a failed write leaves prior state, and the forward-only rollovers inside
     * the engine policies still hold on every subsequent evaluation.
     */
    private fun applyGuards(appContext: Context, shift: ClockShift, pending: PendingResult) {
        scope.launch {
            try {
                val store = FlintPreferences.focusState(appContext)
                val counts = store.openCounts.first()
                guard.guardedOpenCounts(counts, shift)
                    .takeIf { it != counts }
                    ?.let { store.setOpenCounts(it) }
                val pass = store.emergencyPass.first()
                guard.guardedEmergencyPass(pass, shift)
                    .takeIf { it != pass }
                    ?.let { store.setEmergencyPass(it) }
                val session = store.breakSession.first()
                guard.guardedBreakSession(session, shift)
                    .takeIf { it != session }
                    ?.let { store.setBreakSession(it) }
                // One-shot Block Now sessions measure DURATION, not a wall deadline: shift
                // their expiry with the injected jump so a set-forward clock can't end a
                // Hardcore session early (nor a set-back stretch one). Upserting also
                // restores a session the lazy expiry sweep raced to delete after the jump.
                val rulesStore = FlintPreferences.blockRules(appContext)
                guard.guardedSessionRules(rulesStore.rules.first(), shift)
                    .forEach { rulesStore.upsert(it) }
            } catch (_: Exception) {
                // Swallowed on purpose (see KDoc above): fail toward existing state, no crash
                // loop from a broadcast. No telemetry — nothing leaves the device (ADR-002).
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * The clock/zone/date change actions this receiver is registered for. Kept in sync
         * with this module's manifest. Note the constant/action mismatch:
         * `Intent.ACTION_TIME_CHANGED` *is* "android.intent.action.TIME_SET".
         */
        private val HANDLED_ACTIONS: Set<String> = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )

        /** ACTION_TIMEZONE_CHANGED's documented extra: the new zone's ID. */
        private const val EXTRA_TIME_ZONE_ID = "time-zone"

        private const val ANCHOR_PREFS = "flint_clock_anchor"
        private const val KEY_ANCHOR_WALL = "wall_epoch_ms"
        private const val KEY_ANCHOR_ELAPSED = "elapsed_realtime_ms"
        private const val KEY_ANCHOR_OFFSET = "utc_offset_ms"

        private val rearmActions = CopyOnWriteArrayList<(Context) -> Unit>()

        /** True only for the time/zone/date actions this receiver is registered for. */
        internal fun shouldHandle(action: String?): Boolean =
            action != null && action in HANDLED_ACTIONS

        /**
         * True for the actions that may conservatively re-key persisted quota state.
         * DATE_CHANGED is deliberately excluded: it also fires at genuine local midnight,
         * and re-keying there would deny the honest fresh day. A *manual* date edit moves
         * the wall clock, so it fires TIME_SET too — no manipulation slips through.
         */
        internal fun rekeysFocusState(action: String?): Boolean =
            action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED

        /**
         * Register extra work to run after a time-change re-arm (call from
         * `Application.onCreate`, same contract as [BootReceiver.registerRearmAction]).
         * Actions run on the main thread inside the receiver's time budget — keep them quick
         * and assume best-effort delivery.
         */
        fun registerRearmAction(action: (Context) -> Unit) {
            rearmActions += action
        }

        /**
         * Persist a fresh clock anchor (wall time + elapsedRealtime + UTC offset). Call from
         * `Application.onCreate`: a manifest receiver always runs after it in the app
         * process, so the anchor is at worst process-start fresh — and re-baselining on
         * every process start is what keeps a reboot's elapsedRealtime reset from ever
         * looking like a clock jump.
         */
        fun baselineAnchor(context: Context) {
            val appContext = context.applicationContext
            val now = System.currentTimeMillis()
            writeAnchor(
                appContext.getSharedPreferences(ANCHOR_PREFS, Context.MODE_PRIVATE),
                now,
                SystemClock.elapsedRealtime(),
                TimeZone.getDefault().getOffset(now).toLong(),
            )
        }

        private fun writeAnchor(
            prefs: android.content.SharedPreferences,
            wallEpochMs: Long,
            elapsedRealtimeMs: Long,
            utcOffsetMs: Long,
        ) {
            prefs.edit()
                .putLong(KEY_ANCHOR_WALL, wallEpochMs)
                .putLong(KEY_ANCHOR_ELAPSED, elapsedRealtimeMs)
                .putLong(KEY_ANCHOR_OFFSET, utcOffsetMs)
                .apply()
        }
    }
}
