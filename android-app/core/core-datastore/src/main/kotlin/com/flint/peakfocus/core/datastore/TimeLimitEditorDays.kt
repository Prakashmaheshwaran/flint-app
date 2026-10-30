package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*

internal fun readTimeLimitEditorDays(prefs: Preferences): TimeLimitUsageLedger? =
    try { readTimeLimitUsage(prefs) } catch (_: StorageUnavailableException) { null }

internal fun timeLimitEditorEnded(cause: EditorCause, now: AccessClockStamp): Boolean = cause.interval?.let {
    it.remainingAtAnchorMs == 0L || trustedElapsedMs(it.anchor, now) != null && it.remainingMs(now) == 0L
} == true

internal fun materializeTimeLimitEditorCause(ledger: TimeLimitUsageLedger?, packageName: String,
    old: EditorCause?, now: AccessClockStamp, fallbackZone: String): EditorCause {
    // A placeholder describes the recovery scope only. Time authorization separately
    // requires the healthy ledger, so a capture fault cannot disable Emergency Pass.
    if (ledger == null) return old ?: EditorCause("time-unavailable",
        AnchoredInterval(now, 86_400_000, now.wallEpochMs + 86_400_000), fallbackZone)
    if (old != null && old.occurrenceId !in setOf("inactive", "time-unavailable") && !timeLimitEditorEnded(old, now)) return old
    val row = ledger.targets[packageName] ?: wireCorrupt()
    val left = OpenCreditPolicy().remainingToResetMs(row.reset, now)
    return EditorCause("time:${row.periodId}", AnchoredInterval(now, left, now.wallEpochMs + left), row.zoneId)
}

internal fun validateTimeLimitEditorChange(prefs: Preferences, change: EditorChange): String? {
    val limits = change.limits ?: return null
    if (limits.time == prefs.editorTimes().singleOrNull { it.packageName == limits.packageName }) return null
    return if (readTimeLimitEditorDays(prefs) == null) "Time limit accounting is unavailable. Restore access and try again." else null
}

/** Editor-owned preparation. Read old anchors before any shared Open period can roll. */
internal fun prepareTimeLimitEditorDays(prefs: MutablePreferences, previous: EditorCommitmentState?,
    now: AccessClockStamp, deviceZone: String): TimeLimitUsageLedger? {
    if (readTimeLimitBootstrapCapture(prefs) is TimeLimitBootstrapCapture.Unavailable) return null
    val oldQuota = if (StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) != null) readOpenCredits(prefs) else null
    // A Time-owned failure must not poison pass/Open/rule snapshots. Stage the one-time
    // rewrite so an unprovable old Time anchor cannot publish half a migration.
    return try {
        val initialized = StrictPrefsCodec.long(prefs, TIME_USAGE_SCHEMA) != null
        val working = if (initialized) prefs else prefs.toPreferences().toMutablePreferences()
        val captured = prepareTimeLimitSavedZones(working, now, deviceZone)
        val ledger = if (initialized) captured else seedTimeLimitEditorAnchors(captured, previous, oldQuota, now)
        if (!initialized) {
            working.putTimeLimitUsage(ledger)
            working.asMap().forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                prefs[key as Preferences.Key<Any>] = value
            }
        }
        ledger
    } catch (_: StorageUnavailableException) { null }
}

private fun seedTimeLimitEditorAnchors(ledger: TimeLimitUsageLedger, previous: EditorCommitmentState?,
    oldQuota: OpenCreditLedger?, now: AccessClockStamp): TimeLimitUsageLedger = ledger.copy(targets = ledger.targets.mapValues { (pkg, row) ->
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
    val foreground = ledger.foreground?.let { span ->
        val before = ledger.targets[span.packageName]
        val after = targets[span.packageName]
        val elapsed = trustedElapsedMs(span.checkpoint, now)
        if (before?.periodId == span.periodId && after?.periodId == before.periodId + 1 &&
            elapsed != null && elapsed <= DataStoreTimeLimitUsageStore.MAX_FOREGROUND_GAP_MS) {
            val ahead = trustedElapsedMs(span.checkpoint, before.reset.checkpoint)
            val left = if (ahead == null) OpenCreditPolicy().remainingToResetMs(before.reset, span.checkpoint)
                else Math.addExact(before.reset.remainingToResetMs, ahead)
            if (left <= elapsed) {
                // Keep the proven boundary, without charging here. The next observation
                // still rechecks the owner and visit before spending its elapsed suffix.
                val boundary = now.copy(wallEpochMs = (now.wallEpochMs - (elapsed - left)).coerceAtLeast(0),
                    elapsedRealtimeMs = Math.addExact(span.checkpoint.elapsedRealtimeMs, left))
                span.copy(periodId = after.periodId, startedAt = boundary, checkpoint = boundary)
            } else span
        } else span
    }
    val next = ledger.copy(targets = targets, foreground = foreground)
    if (next != ledger) prefs.putTimeLimitUsage(next)
    return next
}

/** Capture a new draft once; an old target keeps its saved zone unless explicitly edited. */
internal fun normalizeTimeLimitEditorChange(change: EditorChange, current: List<TimeLimit>,
    ledger: TimeLimitUsageLedger?, zoneForNewTarget: String): EditorChange {
    val limits = change.limits ?: return change
    val time = limits.time ?: return change
    val previous = current.singleOrNull { it.packageName == limits.packageName }
    // An Open-only paired edit must not silently capture/change an unavailable Time domain.
    if (ledger == null && time == previous) return change
    return change.copy(limits = limits.copy(time = time.withSavedTimeLimitZone(previous,
        ledger?.targets?.get(limits.packageName)?.zoneId ?: zoneForNewTarget)))
}
