package com.flint.peakfocus.core.datastore

import androidx.datastore.core.CorruptionException
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.blocking.engine.ClockShift
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.blocking.engine.ScheduleOccurrences
import com.flint.peakfocus.blocking.engine.isValidNewSessionDuration
import com.flint.peakfocus.blocking.engine.isValidOccurrenceDuration
import com.flint.peakfocus.core.model.*
import java.util.Calendar
import java.util.TimeZone
import java.time.ZoneId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/** New Timer rights are identified durably; an old all-tier Timer never acquires new rights. */
const val CONTRACT_TIMER_ID_PREFIX = "session-timer-v2-"

data class EditorLimitsChange(val packageName: String, val time: TimeLimit?, val open: OpenLimit?)

/** One confirmed atomic operation; paired Sleep rules and paired limit kinds cannot split. */
data class EditorChange(
    val label: String,
    val upserts: List<BlockRule> = emptyList(),
    val deleteIds: Set<String> = emptySet(),
    val limits: EditorLimitsChange? = null,
    val timerExit: Boolean = false,
) {
    val keys: Set<String> get() = (upserts.map { ruleCauseKey(it.id) } + deleteIds.map(::ruleCauseKey) +
        limits?.let { listOf(timeCauseKey(it.packageName), openCauseKey(it.packageName)) }.orEmpty()).toSet()
}

/** The actual records and occurrence identities displayed in a confirmation. */
data class EditorExpected(val rules: List<BlockRule>, val time: TimeLimit?, val open: OpenLimit?, val occurrences: Map<String, String>)
data class EditorCause(
    val occurrenceId: String,
    val interval: AnchoredInterval?,
    val zoneId: String,
    val legacyRights: Boolean = false,
    val timerTerminal: EditorTimerTerminalState? = null,
)
data class PendingEditorChange(val id: Long, val change: EditorChange, val expected: EditorExpected,
    val outcome: CommitmentPolicy.Outcome, val wait: HarderWaitPolicy.Operation? = null)
data class QueuedEditorChange(val id: Long, val change: EditorChange, val expected: EditorExpected,
    val boundaries: Map<String, AnchoredInterval>, val needsReview: Boolean = false)
data class EditorCommitmentState(
    val nextId: Long = 1,
    val causes: Map<String, EditorCause> = emptyMap(),
    val pending: PendingEditorChange? = null,
    val queued: List<QueuedEditorChange> = emptyList(),
    val migrationNotice: Boolean = false,
    val timeline: AccessClockStamp? = null,
    /** Daily occurrence identity changes only when the trusted shared quota period rolls. */
    val quotaPeriodId: Long? = null,
)

sealed interface EditorCommitResult {
    data class Preview(val pending: PendingEditorChange) : EditorCommitResult
    data class Waiting(val pending: PendingEditorChange, val remainingMs: Long) : EditorCommitResult
    data class Applied(val queued: Boolean = false) : EditorCommitResult
    data class Rejected(val reason: String) : EditorCommitResult
}

interface EditorCommitmentStore {
    val state: Flow<StorageState<EditorCommitmentState>>
    /** Includes raw template changes made by other writers, even when the sidecar is unchanged. */
    val changes: Flow<Unit> get() = state.map { Unit }
    val terminalEvents: Flow<StorageState<List<EditorTerminalEvent>>> get() = error("Terminal delivery is unsupported by this store")
    val terminalDeliveryHealth: Flow<StorageState<EditorTerminalDeliveryHealth>> get() =
        error("Terminal delivery health is unsupported by this store")
    suspend fun readTerminalEvents(): List<EditorTerminalEvent> = when (val value = terminalEvents.first { it !is StorageState.Loading }) {
        is StorageState.Ready -> value.value
        is StorageState.Unavailable -> throw StorageUnavailableException(value.reason)
        StorageState.Loading -> error("No terminal snapshot")
    }
    suspend fun ackTerminalEvents(ids: Set<String>) { error("Terminal acknowledgements are unsupported by this store") }
    suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult
    suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean = true): EditorCommitResult
    suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult
    suspend fun cancel(id: Long)
    suspend fun dismissMigrationNotice()
    /** Apply due future-only changes BEFORE materializing an adjacent occurrence. */
    suspend fun reconcile(now: AccessClockStamp)
    /** Queued templates are clipped at their old end until the reconciler applies them. */
    suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule>
    /** Re-key finite display estimates from fresh anchored state in one transaction. */
    suspend fun guardSessionClock(now: AccessClockStamp, shift: ClockShift) {
        throw UnsupportedOperationException("Clock guarding requires an atomic editor store")
    }
    /** Called by the explicit Start action after the selected duration/tier disclosure. */
    suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety = EditorSafety(false)): Boolean
}

/** A fresh resolution supplied by the adapter, never a permission bit or a visible shield. */
data class EditorSafety(val resolved: Boolean, val excludedPackages: Set<String> = emptySet())

/** Publication boundaries must replace stale Ready state even when decoding throws unexpectedly. */
fun editorPublicationFailure(cause: Throwable): StorageFailure = when (cause) {
    is StorageUnavailableException -> cause.reason
    is CorruptionException -> StorageFailure.CORRUPT
    is IOException -> StorageFailure.IO
    else -> StorageFailure.CORRUPT
}

fun ruleCauseKey(id: String) = "rule:$id"
fun timeCauseKey(pkg: String) = "time:$pkg"
fun openCauseKey(pkg: String) = "open:$pkg"

/** Shared occurrence identity for C4b breaks and C4c edits. Session identity is its rule id. */
fun editorOccurrence(key: String, cause: EditorCause) = HarderWaitPolicy.Occurrence(key, cause.occurrenceId)

/** Calendar endpoints are resolved in the saved zone, including an overnight tail and DST. */
internal fun ruleOccurrence(rule: BlockRule, zone: String, now: AccessClockStamp): EditorCause? {
    if (!rule.enabled) return null
    rule.expiresAtEpochMs?.let { end ->
        if (end <= now.wallEpochMs) return null
        return EditorCause(rule.id, AnchoredInterval(now, end - now.wallEpochMs, end), zone)
    }
    val schedule = rule.schedule ?: return EditorCause(rule.id, null, zone)
    val occurrence = ScheduleOccurrences.containing(schedule, ZoneId.of(zone), now.wallEpochMs) ?: return null
    return EditorCause(occurrence.id,
        AnchoredInterval(now, occurrence.endsAtEpochMs - now.wallEpochMs, occurrence.endsAtEpochMs), zone)
}

internal fun dailyOccurrence(zone: String, now: AccessClockStamp): EditorCause {
    val start = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
        timeInMillis = now.wallEpochMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val end = (start.clone() as Calendar).apply { add(Calendar.DATE, 1) }.timeInMillis
    require(isValidOccurrenceDuration(end - start.timeInMillis))
    return EditorCause("${start.timeInMillis}", AnchoredInterval(now, end - now.wallEpochMs, end), zone)
}

internal fun ruleShape(rule: BlockRule, zone: String) = CommitmentPolicy.ProtectionShape(
    name = rule.name, tier = rule.breakLevel, allowListMode = rule.targets.allowListMode,
    apps = rule.targets.apps.map { it.packageName }.toSet(), domains = rule.targets.domains.map { it.domain }.toSet(),
    window = rule.schedule?.let { CommitmentPolicy.Window(it.startMinuteOfDay, it.endMinuteOfDay, it.daysOfWeek, zone) },
    endsAtEpochMs = rule.expiresAtEpochMs,
)

internal fun limitShape(pkg: String, tier: BreakLevel, budget: Int) = CommitmentPolicy.ProtectionShape(
    name = pkg, tier = tier, allowListMode = false, apps = setOf(pkg), domains = emptySet(), budget = budget,
)

internal fun validateEditorRule(rule: BlockRule, old: BlockRule?, now: AccessClockStamp): String? {
    if (rule.name.isBlank()) return "Give this rule a name."
    if (rule.schedule == null && rule.expiresAtEpochMs == null && rule.breakLevel != BreakLevel.EASY &&
        (old == null || old.schedule != null || old.expiresAtEpochMs != null || old.breakLevel != rule.breakLevel || (!old.enabled && rule.enabled))) {
        return "Choose a schedule or a finite Timer for Harder or Hardcore protection."
    }
    rule.expiresAtEpochMs?.let {
        if (old?.expiresAtEpochMs != it && !isValidNewSessionDuration(it - now.wallEpochMs)) {
            return "Choose a duration of at most 24 hours."
        }
    }
    rule.schedule?.let {
        if (it.startMinuteOfDay !in 0..1439 || it.endMinuteOfDay !in 0..1439 ||
            it.startMinuteOfDay == it.endMinuteOfDay || it.daysOfWeek.any { day -> day !in 1..7 }) {
            return "Choose a valid schedule of at most 26 hours per occurrence."
        }
    }
    return null
}
