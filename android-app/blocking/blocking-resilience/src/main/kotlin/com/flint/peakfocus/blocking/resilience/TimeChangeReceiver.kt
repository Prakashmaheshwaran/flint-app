package com.flint.peakfocus.blocking.resilience

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flint.peakfocus.blocking.engine.ClockChangeGuard
import com.flint.peakfocus.blocking.engine.ClockShift
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.datastore.FocusCommit
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.FocusMutation
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal const val CLOCK_GUARD_TIMEOUT_MS = 2_000L

/** Transform current accounting together: an older warm-up read must never refund a grant. */
internal suspend fun guardCurrentFocus(store: FocusStateStore, shift: ClockShift): FocusCommit<Unit> =
    store.transact { current ->
        val guard = ClockChangeGuard()
        FocusMutation(current.copy(
            openCounts = guard.guardedOpenCounts(current.openCounts, shift),
            emergencyPass = guardedLegacyPassForMigration(current.emergencyPass, shift),
            breakSession = guard.guardedBreakSession(current.breakSession, shift),
            // Only shorten access on a measured rollback. In particular, never move an
            // inactive normal-open receipt (until=0) into an active release interval.
            exemptions = current.exemptions.mapValues { (_, receipt) ->
                if (receipt.untilEpochMs == 0L || shift.wallDeltaMs >= 0) receipt
                else receipt.copy(untilEpochMs = (receipt.untilEpochMs + shift.wallDeltaMs).coerceAtLeast(0))
            },
        ), Unit)
    }

/** Legacy weekly migration mirror only; initialized rolling-ledger state bypasses this hook. */
private fun guardedLegacyPassForMigration(pass: EmergencyPassState, shift: ClockShift): EmergencyPassState =
    ClockChangeGuard().guardedEmergencyPass(pass, shift)

/** Independent bounded domains: unreadable focus state must not skip valid session guards. */
internal suspend fun applyClockGuards(
    focus: FocusStateStore, shift: ClockShift,
    perGuardTimeoutMs: Long = CLOCK_GUARD_TIMEOUT_MS,
    guardRules: suspend () -> Unit,
) = coroutineScope {
    launch { isolateClockGuard(perGuardTimeoutMs) { guardCurrentFocus(focus, shift) } }
    launch { isolateClockGuard(perGuardTimeoutMs, guardRules) }
    Unit
}

private suspend fun isolateClockGuard(timeoutMs: Long, block: suspend () -> Unit) {
    try { withTimeoutOrNull(timeoutMs) { block() } }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { }
}

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
 *     stores a fresh anchor. [baselineAnchor] initializes only an absent record; a cold
 *     process keeps the prior evidence until this measurement. Boot identity rejects
 *     untrusted cross-boot elapsed time, including a reboot with a higher current uptime.
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
        rearmDetectors(rearmActions.map { rearm -> { rearm(appContext) } })
    }

    /** Read the previous anchor, derive the [ClockShift] it implies, persist a fresh anchor. */
    private fun measureAndRebaseline(appContext: Context, intent: Intent): ClockShift {
        val now = ProtectionClock.now()
        // TIMEZONE_CHANGED carries the new zone ID ("time-zone" extra, aka Intent.EXTRA_TIMEZONE
        // on API 30+); prefer it over TimeZone.getDefault(), whose cached value can lag the
        // broadcast. TIME_SET / DATE_CHANGED carry no extra — the zone did not move.
        val newZone = intent.getStringExtra(EXTRA_TIME_ZONE_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(TimeZone::getTimeZone)
            ?: TimeZone.getDefault()
        return ClockAnchorStore(appContext.getSharedPreferences(ClockAnchorStore.PREFS, Context.MODE_PRIVATE))
            .measureAndRebaseline(now, newZone.getOffset(now.wallEpochMs).toLong())
    }

    /**
     * Re-key the persisted focus state through [ClockChangeGuard], then release [pending].
     * Best-effort: a failed write leaves prior state, and the forward-only rollovers inside
     * the engine policies still hold on every subsequent evaluation.
     */
    private fun applyGuards(appContext: Context, shift: ClockShift, pending: PendingResult) {
        scope.launch {
            try {
                withTimeout(5_000) {
                    val store = FlintPreferences.focusState(appContext)
                    val editor = FlintPreferences.editorCommitments(appContext)
                    // The transaction reads the latest records and anchored duration. A
                    // delayed callback cannot overwrite a stronger edit or revive a deletion.
                    applyClockGuards(store, shift) { editor.guardSessionClock(ProtectionClock.now(), shift) }
                }
            } catch (_: Exception) {
                // Swallowed on purpose (see KDoc above): fail toward existing state, no crash
                // loop from a broadcast. No telemetry — nothing leaves the device (ADR-002).
            } finally {
                // A stationary target must see the committed guards too, even if the first
                // wake raced their asynchronous writes or one independent guard failed.
                rearmDetectors(rearmActions.map { rearm -> { rearm(appContext) } })
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
         * Initialize clock evidence if absent. Application starts before a cold manifest
         * receiver; overwriting an existing anchor here would erase the jump it must measure.
         */
        fun baselineAnchor(context: Context) {
            val appContext = context.applicationContext
            val now = ProtectionClock.now()
            ClockAnchorStore(appContext.getSharedPreferences(ClockAnchorStore.PREFS, Context.MODE_PRIVATE))
                .initialize(now, TimeZone.getDefault().getOffset(now.wallEpochMs).toLong())
        }
    }
}
