package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.MutablePreferences
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.*

/** A Time fault denies only a NEW Time authorization, never reading the shared snapshot. */
internal fun <R> validateTimeLimitAuthorization(prefs: MutablePreferences, before: ProtectionSnapshot,
    mutation: ProtectionMutation<R>) {
    val oldGrants = before.focus.protection?.ordinaryGrants.orEmpty()
    val addsTimeGrant = mutation.focus.protection?.ordinaryGrants.orEmpty().any { (id, grant) ->
        grant != oldGrants[id] && grant.scopes.any { it.counter.rule.source in setOf("time", "legacy-time") }
    }
    val newWait = mutation.harder?.pending?.operation
    val addsTimeWait = newWait != null && newWait != before.harder.pending?.operation && newWait.causes.keys.any {
        it.causeId.startsWith("time:") || it.causeId.startsWith("legacy-time:") }
    val newPending = mutation.editor?.pending
    fun changesTime(change: EditorChange) = change.limits?.let { limits ->
        limits.time != before.times.singleOrNull { it.packageName == limits.packageName }
    } == true
    val addsTimeConfirmation = newPending != null && newPending != before.editor.pending && changesTime(newPending.change)
    val addsTimeQueue = mutation.editor?.queued.orEmpty().any { it !in before.editor.queued && changesTime(it.change) }
    // Removing a template during explicit Emergency Pass recovery must remain possible.
    val addsOrChangesTime = mutation.times?.any { time -> before.times.singleOrNull { it.packageName == time.packageName } != time } == true
    if (addsTimeGrant || addsTimeWait || addsTimeConfirmation || addsTimeQueue || addsOrChangesTime)
        readTimeLimitUsage(prefs)
}

/** Restoring/replaying a known target retains its registered floor and original saved day. */
internal fun normalizeTimeLimitMutation(prefs: MutablePreferences, desired: List<TimeLimit>,
    previous: List<TimeLimit>, now: AccessClockStamp, deviceZone: String): List<TimeLimit> {
    if (desired.all { it in previous }) return desired // Deletion-only recovery needs no Time ledger.
    var ledger = prepareTimeLimitSavedZones(prefs, now, deviceZone)
    val normalized = desired.map { time -> time.withSavedTimeLimitZone(
        previous.singleOrNull { it.packageName == time.packageName }, ledger.targets[time.packageName]?.zoneId ?: deviceZone) }
    val newRows = normalized.filter { it.packageName !in ledger.targets }.associate { time ->
        time.packageName to TimeLimitDayAccounting.initial(now, checkNotNull(time.zoneId))
    }
    if (newRows.isNotEmpty()) {
        ledger = ledger.copy(targets = ledger.targets + newRows)
        prefs.putTimeLimitUsage(ledger)
    }
    return normalized
}
