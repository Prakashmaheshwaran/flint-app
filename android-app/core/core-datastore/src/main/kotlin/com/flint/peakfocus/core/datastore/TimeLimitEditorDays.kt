package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*

/** Editor-owned preparation. Read old anchors before any shared Open period can roll. */
internal fun prepareTimeLimitEditorDays(prefs: MutablePreferences, previous: EditorCommitmentState?,
    now: AccessClockStamp, deviceZone: String): TimeLimitUsageLedger? {
    if (readTimeLimitBootstrapCapture(prefs) is TimeLimitBootstrapCapture.Unavailable) return null
    val initialized = StrictPrefsCodec.long(prefs, TIME_USAGE_SCHEMA) != null
    val oldQuota = if (StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) != null) readOpenCredits(prefs) else null
    var ledger = prepareTimeLimitSavedZones(prefs, now, deviceZone)
    if (!initialized) {
        ledger = ledger.copy(targets = ledger.targets.mapValues { (pkg, row) ->
            val cause = previous?.causes?.get(timeCauseKey(pkg))
            val interval = cause?.interval
            // Reboot conservatively resumes the saved bound, never a changed wall estimate.
            val remaining = interval?.let {
                trustedElapsedMs(it.anchor, now)?.let { elapsed -> (it.remainingAtAnchorMs - elapsed).coerceAtLeast(0L) }
                    ?: it.remainingAtAnchorMs
            } ?: oldQuota?.let { OpenCreditPolicy().remainingToResetMs(it.reset, now) }
            if (remaining == null || remaining == 0L) row else {
                val logical = previous?.timeline?.let { anchor -> trustedElapsedMs(anchor, now)?.let { anchor.wallEpochMs + it } }
                    ?: oldQuota?.logicalCheckpoint?.let { anchor -> trustedElapsedMs(anchor, now)?.let { anchor.wallEpochMs + it } }
                    ?: now.wallEpochMs
                row.copy(zoneId = cause?.zoneId ?: oldQuota?.quotaZone ?: row.zoneId,
                    reset = OpenCreditPolicy.ResetClock(now, remaining), logicalCheckpoint = now.copy(wallEpochMs = logical),
                    observedAt = now, timeUncertain = row.timeUncertain ||
                        (interval != null && trustedElapsedMs(interval.anchor, now) == null))
            }
        })
        prefs.putTimeLimitUsage(ledger)
    }
    return ledger
}

/** Before applying due edits, expose the OLD Time period's proven end (including zero). */
internal fun preserveTimeLimitEditorEnds(state: EditorCommitmentState, ledger: TimeLimitUsageLedger?,
    now: AccessClockStamp): EditorCommitmentState {
    if (ledger == null) return state
    fun ended(interval: AnchoredInterval?) = interval?.let {
        it.remainingAtAnchorMs == 0L || trustedElapsedMs(it.anchor, now) != null && it.remainingMs(now) == 0L
    } == true
    fun interval(pkg: String): AnchoredInterval? = ledger.targets[pkg]?.let { row ->
        val remaining = OpenCreditPolicy().remainingToResetMs(row.reset, now)
        AnchoredInterval(now, remaining, now.wallEpochMs + remaining)
    }
    val causes = state.causes.mapValues { (key, cause) ->
        if (!key.startsWith("time:") || cause.occurrenceId == "inactive" || ended(cause.interval)) cause else {
            val next = interval(key.removePrefix("time:"))
            if (next == null || cause.interval?.let { trustedElapsedMs(it.anchor, now) != null && it.remainingMs(now) == next.remainingAtAnchorMs } == true) cause
            else cause.copy(interval = next)
        }
    }
    val queued = state.queued.map { q -> q.copy(boundaries = q.boundaries.mapValues { (key, old) ->
        if (key.startsWith("time:") && !ended(old) && q.expected.occurrences[key] == state.causes[key]?.occurrenceId)
            interval(key.removePrefix("time:"))?.takeUnless { trustedElapsedMs(old.anchor, now) != null && old.remainingMs(now) == it.remainingAtAnchorMs } ?: old
        else old
    }) }
    return state.copy(causes = causes, queued = queued)
}

/** Apply after queued edits, so the authorized next template zone controls the new day. */
internal fun advanceTimeLimitEditorDays(prefs: MutablePreferences, ledger: TimeLimitUsageLedger?,
    now: AccessClockStamp, checkpoint: Boolean): TimeLimitUsageLedger? {
    if (ledger == null) return null
    val templates = prefs.editorTimes().associateBy { it.packageName }
    val registered = ledger.targets + templates.filterKeys { it !in ledger.targets }.mapValues { (_, template) ->
        TimeLimitDayAccounting.initial(now, template.zoneId ?: ledger.migrationZoneId)
    }
    val targets = registered.mapValues { (pkg, row) ->
        val next = TimeLimitDayAccounting.reconcile(row, now, templates[pkg]?.zoneId ?: row.zoneId)
        if (checkpoint || next.rolled || trustedElapsedMs(row.observedAt, now) == null || clockDiscontinuity(row.observedAt, now)) next.state else row
    }
    val next = ledger.copy(targets = targets)
    if (next != ledger) prefs.putTimeLimitUsage(next)
    return next
}

/** Capture a new draft once; an old target keeps its saved zone unless explicitly edited. */
internal fun normalizeTimeLimitEditorChange(change: EditorChange, current: List<TimeLimit>,
    ledger: TimeLimitUsageLedger?, zoneForNewTarget: String): EditorChange {
    val limits = change.limits ?: return change
    val time = limits.time ?: return change
    val previous = current.singleOrNull { it.packageName == limits.packageName }
    return change.copy(limits = limits.copy(time = time.withSavedTimeLimitZone(previous,
        ledger?.targets?.get(limits.packageName)?.zoneId ?: zoneForNewTarget)))
}
