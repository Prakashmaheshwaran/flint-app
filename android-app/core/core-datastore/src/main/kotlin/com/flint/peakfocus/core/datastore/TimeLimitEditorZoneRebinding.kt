package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.MutablePreferences
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.model.*

/** Marker-v3 only: prove the old exact operation before rebinding a saved-zone spelling. */
internal fun rebindTimeLimitEditorZones(prefs: MutablePreferences, state: EditorCommitmentState,
    beforeTimes: List<TimeLimit>, ledger: TimeLimitUsageLedger, zoneForNewTarget: String, now: AccessClockStamp,
    expected: (EditorChange) -> EditorExpected,
    decision: (EditorChange) -> Pair<CommitmentPolicy.Outcome, HarderWaitPolicy.Operation?>,
): EditorCommitmentState {
    fun normalize(change: EditorChange) = normalizeTimeLimitEditorChange(change, prefs.editorTimes(), ledger, zoneForNewTarget)
    fun beforeExpected(change: EditorChange) = expected(change).copy(time =
        change.limits?.let { limits -> beforeTimes.singleOrNull { it.packageName == limits.packageName } })
    var harder = readHarderWait(prefs)
    val pending = state.pending?.let { old ->
        val change = normalize(old.change)
        val current = beforeExpected(old.change) == old.expected
        val fresh = decision(change)
        var proven = current && fresh.first == old.outcome
        if (old.wait == null) proven = proven && fresh.second == null else {
            val operation = fresh.second
            val waiting = harder.pending
            val revisions = operation?.causes?.mapValues { (occurrence, revision) ->
                val time = old.change.limits?.let { limits -> beforeTimes.singleOrNull { it.packageName == limits.packageName } }
                if (time != null && occurrence.causeId == timeCauseKey(time.packageName)) "${time.dailyMinutes}:${time.breakLevel}" else revision
            }
            val scopes = buildSet {
                add(setOf(old.change.toString()))
                if (old.change.limits?.time?.zoneId == null) add(setOf(legacyTimeLimitEditorScope(old.change)))
            }
            proven = proven && operation != null && revisions != null && waiting != null && waiting.operation == old.wait &&
                HarderWaitPolicy().remainingMs(waiting, now) != null &&
                scopes.any { operation.copy(causes = revisions, targetScope = it) == old.wait }
        }
        if (proven) {
            val operation = fresh.second
            if (operation != null) harder = harder.copy(pending = checkNotNull(harder.pending).copy(operation = operation))
            old.copy(change = change, expected = expected(change), wait = operation)
        } else {
            // Cancel only this pending confirmation; spent friction and another owner's
            // pending wait survive. A failed proof never manufactures a fresh operation.
            if (harder.pending?.operation == old.wait) harder = harder.copy(pending = null)
            null
        }
    }
    val queued = state.queued.map { old ->
        val current = beforeExpected(old.change).copy(occurrences = emptyMap()) == old.expected.copy(occurrences = emptyMap())
        if (!current) old.copy(needsReview = true) else {
            val change = normalize(old.change)
            old.copy(change = change, expected = old.expected.copy(time = expected(change).time))
        }
    }
    if (harder != readHarderWait(prefs)) prefs.putHarderWait(harder)
    return state.copy(pending = pending, queued = queued)
}
