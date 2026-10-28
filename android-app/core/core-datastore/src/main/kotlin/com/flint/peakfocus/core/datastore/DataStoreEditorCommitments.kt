package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val EDITOR_COMMITMENTS_KEY = stringPreferencesKey("editor_commitments_v1")
internal val EDITOR_VERSION_KEY = longPreferencesKey("editor_commitments_version")
private val ARCHIVE_RULES = stringPreferencesKey("editor_migration_rules_v1")
private val ARCHIVE_TIMES = stringPreferencesKey("editor_migration_time_limits_v1")

/** All checks, waits/counts, queue writes and effective rule/limit mutations share one commit. */
class DataStoreEditorCommitmentStore(
    private val dataStore: DataStore<Preferences>,
    private val clockAtCommit: (() -> AccessClockStamp)? = null,
    private val defaultZone: () -> String = { TimeZone.getDefault().id },
) : EditorCommitmentStore {
    override val changes: Flow<Unit> = dataStore.data.map { Unit }
    override val state: Flow<StorageState<EditorCommitmentState>> = dataStore.data.storageStates {
        readEditorState(it).also { _ -> if (StrictPrefsCodec.string(it, EDITOR_COMMITMENTS_KEY) != null) readHarderWait(it) }
    }
    private val policy = CommitmentPolicy()
    private val harder = HarderWaitPolicy()

    override suspend fun reconcile(now: AccessClockStamp) {
        dataStore.edit { prefs -> prepare(prefs, clockAtCommit?.invoke() ?: now) }
    }

    override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = transact(now) { prefs, now ->
        val state = prepare(prefs, now)
        prefs.editorRules().map { rule ->
            val cause = state.causes[ruleCauseKey(rule.id)]
            val boundary = state.queued.firstOrNull { !it.needsReview && ruleCauseKey(rule.id) in it.change.keys }
                ?.boundaries?.get(ruleCauseKey(rule.id))
            val interval = boundary ?: cause?.interval
            if (!rule.enabled) rule
            // Preserve future schedule starts for the foreground evaluator's boundary timer.
            else if (rule.schedule != null && boundary == null) rule
            else if (rule.schedule != null && interval?.remainingMs(now) == 0L) rule.copy(enabled = false)
            else if (interval == null) rule
            else {
                val end = now.wallEpochMs + interval.remainingMs(now)
                // The effective snapshot has a finite elapsed-derived end. It cannot roll
                // into a neighboring occurrence before a queued off/delete has applied.
                rule.copy(schedule = null, expiresAtEpochMs = end)
            }
        }
    }

    override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult =
        transact(now) { prefs, stamp -> request(prefs, stamp, safety) { change } }

    override suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean): EditorCommitResult =
        transact(now) { prefs, stamp ->
            request(prefs, stamp, safety) {
                val current = prefs.editorRules().filter { it.id in ruleIds }
                if (current.isEmpty() || (requireAll && current.map { it.id }.toSet() != ruleIds)) null
                else EditorChange(label, upserts = current.map { it.copy(enabled = enabled) })
            }
        }

    private fun request(prefs: MutablePreferences, now: AccessClockStamp, safety: EditorSafety, changeAtCommit: () -> EditorChange?): EditorCommitResult {
        var state = prepare(prefs, now)
        val change = changeAtCommit() ?: return EditorCommitResult.Rejected("This rule changed. Refresh and try again.")
        validation(prefs, change, now, safety)?.let { return EditorCommitResult.Rejected(it) }
        val expected = expected(prefs, state, change, now)
        val decision = decision(prefs, state, change, now)
        if (decision.first == CommitmentPolicy.Outcome.DENY) return EditorCommitResult.Rejected(denial(prefs, state, change, now))
        if (state.nextId == Long.MAX_VALUE) return EditorCommitResult.Rejected("Protection state unavailable. Open recovery and try again.")
        val operation = decision.second
        val pending = state.pending?.takeIf { it.change == change && it.expected == expected && it.outcome == decision.first }
            ?: PendingEditorChange(state.nextId, change, expected, decision.first, operation)
        val previousOperation = state.pending?.wait
        state = state.copy(nextId = maxOf(state.nextId, pending.id + 1), pending = pending)
        var remaining = 0L
        if (operation != null) {
            val waiting = harder.request(readHarderWait(prefs), operation, now)
            prefs.putHarderWait(waiting.ledger)
            remaining = waiting.remainingMs
        } else {
            cancelOwnedWait(prefs, previousOperation)
        }
        if (state.timeline?.bootSessionId != now.bootSessionId) {
            fun checkpoint(interval: AnchoredInterval) = interval.remainingMs(now).let { remaining ->
                AnchoredInterval(now, remaining, now.wallEpochMs + remaining)
            }
            state = state.copy(timeline = now,
                causes = state.causes.mapValues { (_, c) -> c.copy(interval = c.interval?.let(::checkpoint)) },
                queued = state.queued.map { q -> q.copy(boundaries = q.boundaries.mapValues { checkpoint(it.value) }) })
        }
        prefs.putEditorState(state)
        return if (operation != null) EditorCommitResult.Waiting(pending, remaining) else EditorCommitResult.Preview(pending)
    }

    override suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = transact(now) { prefs, now ->
        var state = prepare(prefs, now)
        val pending = state.pending?.takeIf { it.id == id }
            ?: return@transact EditorCommitResult.Rejected("This confirmation changed. Review the current protection and try again.")
        val change = pending.change
        if (expected(prefs, state, change, now) != pending.expected) {
            cancelOwnedWait(prefs, pending.wait)
            prefs.putEditorState(state.copy(pending = null))
            return@transact EditorCommitResult.Rejected("Protection changed or reached a boundary. Review it before confirming.")
        }
        validation(prefs, change, now, safety)?.let { return@transact EditorCommitResult.Rejected(it) }
        val decision = decision(prefs, state, change, now)
        if (decision.first != pending.outcome || decision.second != pending.wait) {
            return@transact EditorCommitResult.Rejected("Protection changed. Review the current action before confirming.")
        }
        if (decision.first == CommitmentPolicy.Outcome.DENY) return@transact EditorCommitResult.Rejected(denial(prefs, state, change, now))
        if (pending.wait != null) {
            when (val committed = harder.commit(readHarderWait(prefs), pending.wait, now)) {
                is HarderWaitPolicy.Commit.Committed -> prefs.putHarderWait(committed.ledger)
                is HarderWaitPolicy.Commit.NotYet -> return@transact EditorCommitResult.Waiting(pending, committed.remainingMs)
                HarderWaitPolicy.Commit.Stale -> return@transact EditorCommitResult.Rejected("The wait was cancelled or changed. Start a new wait; no change was made.")
            }
        }
        val isDeferred = pending.outcome == CommitmentPolicy.Outcome.DEFER
        // A newly confirmed edit replaces an earlier queued edit only for these same records.
        val retained = state.queued.filter { q -> q.change.keys.intersect(change.keys).isEmpty() }
        state = state.copy(pending = null, queued = retained)
        if (isDeferred) {
            val boundaries = change.keys.mapNotNull { key -> state.causes[key]?.interval?.takeIf { it.remainingMs(now) > 0 }?.let { key to it } }.toMap()
            if (boundaries.isEmpty()) return@transact EditorCommitResult.Rejected("This occurrence ended. Review the change again.")
            state = state.copy(queued = retained + QueuedEditorChange(id, change, pending.expected, boundaries))
        } else {
            apply(prefs, change)
            // Explicitly confirmed edits may shorten or extend an active snapshot. Ordinary
            // reconciliation and clock-display rewrites never acquire that authority.
            val causes = state.causes.toMutableMap()
            change.upserts.forEach { rule ->
                val key = ruleCauseKey(rule.id)
                val cause = causes[key] ?: return@forEach
                val interval = cause.interval ?: return@forEach
                if (interval.remainingMs(now) == 0L) return@forEach
                val candidateRemaining = if (rule.expiresAtEpochMs != null) rule.expiresAtEpochMs!! - now.wallEpochMs
                    else ruleOccurrence(rule, cause.zoneId, logicalClock(state, now))?.interval?.remainingAtAnchorMs ?: 0
                val remaining = if (rule.enabled) candidateRemaining.coerceAtLeast(0) else 0
                causes[key] = cause.copy(interval = AnchoredInterval(now, remaining, now.wallEpochMs + remaining))
            }
            state = state.copy(causes = causes.toMap())
        }
        prefs.putEditorState(materialize(prefs, state, now))
        EditorCommitResult.Applied(queued = isDeferred)
    }

    override suspend fun cancel(id: Long) {
        dataStore.edit { prefs ->
            val state = readEditorState(prefs)
            if (state.pending?.id == id) {
                cancelOwnedWait(prefs, state.pending.wait)
                prefs.putEditorState(state.copy(pending = null))
            }
        }
    }

    override suspend fun dismissMigrationNotice() {
        dataStore.edit { prefs -> prefs.putEditorState(readEditorState(prefs).copy(migrationNotice = false)) }
    }

    override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): Boolean = transact(now) { prefs, now ->
        var state = prepare(prefs, now)
        require(rule.id.startsWith(CONTRACT_TIMER_ID_PREFIX) && rule.schedule == null && rule.enabled)
        require(!rule.targets.allowListMode && rule.targets.apps.isNotEmpty() && rule.targets.apps.none { it.packageName.isBlank() || it.packageName in floor(safety) })
        require(isValidNewSessionDuration((rule.expiresAtEpochMs ?: 0) - now.wallEpochMs))
        val current = prefs.editorRules()
        if (!current.canStartSession(rule.id, now.wallEpochMs)) return@transact false
        val startDecision = policy.decide(CommitmentPolicy.Action.START_ADDITIONAL, rule.breakLevel, CommitmentPolicy.CauseActivity.Inactive)
        check(startDecision in setOf(CommitmentPolicy.Outcome.ALLOW, CommitmentPolicy.Outcome.ALLOW_AFTER_DISCLOSURE))
        prefs.putEditorRules(current + rule)
        state = materialize(prefs, state, now)
        prefs.putEditorState(state)
        true
    }

    private suspend fun <R> transact(requestedAt: AccessClockStamp, action: (MutablePreferences, AccessClockStamp) -> R): R {
        var result: Any? = null
        dataStore.edit { result = action(it, clockAtCommit?.invoke() ?: requestedAt) }
        @Suppress("UNCHECKED_CAST") return result as R
    }

    /** Call inside one DataStore edit: migrate, apply queued changes, then materialize new occurrences. */
    internal fun prepare(prefs: MutablePreferences, now: AccessClockStamp): EditorCommitmentState {
        var state: EditorCommitmentState
        if (StrictPrefsCodec.string(prefs, EDITOR_COMMITMENTS_KEY) == null) {
            if (StrictPrefsCodec.long(prefs, EDITOR_VERSION_KEY) != null) wireCorrupt()
            val rawRules = StrictPrefsCodec.string(prefs, PrefsKeys.BLOCK_RULES).orEmpty()
            val rawTimes = StrictPrefsCodec.string(prefs, PrefsKeys.TIME_LIMITS).orEmpty()
            val rules = StrictPrefsCodec.rules(rawRules)
            val oldTier = StrictPrefsCodec.level(StrictPrefsCodec.string(prefs, PrefsKeys.DEFAULT_BREAK_LEVEL))
            val times = StrictPrefsCodec.timeLimits(rawTimes, oldTier)
            prefs.editorOpens() // Validate both sides before changing any bytes.
            state = materialize(prefs, EditorCommitmentState(timeline = now), now)
            state = state.copy(causes = state.causes.mapValues { (_, c) -> c.copy(legacyRights = c.interval == null || c.interval.remainingMs(now) > 0) },
                migrationNotice = rules.isNotEmpty() || times.isNotEmpty())
            val legacyCount = StrictPrefsCodec.session(StrictPrefsCodec.string(prefs, PrefsKeys.BREAK_SESSION)).breaksTaken
            val ledger = readHarderWait(prefs)
            val seeded = ledger.counts.toMutableMap()
            state.causes.forEach { (key, cause) ->
                val tier = rules.singleOrNull { ruleCauseKey(it.id) == key }?.breakLevel
                    ?: times.singleOrNull { timeCauseKey(it.packageName) == key }?.breakLevel
                    ?: prefs.editorOpens().singleOrNull { openCauseKey(it.packageName) == key }?.breakLevel
                if (tier == BreakLevel.HARDER && (cause.interval == null || cause.interval.remainingMs(now) > 0)) {
                    val occurrence = editorOccurrence(key, cause)
                    seeded[occurrence] = maxOf(seeded[occurrence] ?: 0, legacyCount)
                }
            }
            prefs.putHarderWait(ledger.copy(counts = seeded.toMap()))
            prefs[ARCHIVE_RULES] = rawRules
            prefs[ARCHIVE_TIMES] = rawTimes
            prefs[PrefsKeys.TIME_LIMITS] = PrefsCodec.encodeTimeLimits(times)
        } else {
            state = readEditorState(prefs)
            readHarderWait(prefs) // Missing initialized friction state is not a fresh occurrence.
        }
        val due = state.queued.filter { q -> !q.needsReview && q.boundaries.values.all { it.remainingMs(now) == 0L } }
        val stale = mutableSetOf<Long>()
        for (q in due) {
            // Exact records: an unrelated writer cannot make an old queue delete a new revision.
            if (records(prefs, q.change) == q.expected.copy(occurrences = emptyMap())) apply(prefs, q.change)
            else stale += q.id
        }
        state = state.copy(queued = state.queued.filter { it !in due || it.id in stale }
            .map { if (it.id in stale) it.copy(needsReview = true) else it })
        val terminal = state.causes.filterValues { it.occurrenceId != "inactive" && it.interval?.remainingMs(now) == 0L }
            .map { (key, cause) -> editorOccurrence(key, cause) }.toSet()
        if (terminal.isNotEmpty()) prefs.putHarderWait(harder.withoutTerminated(readHarderWait(prefs), terminal))
        state = materialize(prefs, state, now)
        prefs.putEditorState(state)
        return state
    }

    private fun materialize(prefs: Preferences, state: EditorCommitmentState, now: AccessClockStamp): EditorCommitmentState {
        val timeline = state.timeline ?: now
        val logical = logicalClock(state, now)
        val causes = state.causes.toMutableMap()
        fun keepOrResolve(key: String, resolve: (String) -> EditorCause?) {
            val old = causes[key]
            if (old != null && (old.interval == null || old.interval.remainingMs(now) > 0)) return
            val zone = old?.zoneId ?: defaultZone()
            val resolved = resolve(zone) ?: old?.takeIf { it.occurrenceId == "inactive" }
                ?: EditorCause("inactive", AnchoredInterval(now, 0, now.wallEpochMs), zone)
            if (resolved === old) return
            // Anchor remaining duration to the real elapsed stamp, with a wall display estimate.
            causes[key] = resolved.copy(interval = resolved.interval?.let {
                val remaining = it.remainingAtAnchorMs
                AnchoredInterval(now, remaining, now.wallEpochMs + remaining)
            })
        }
        prefs.editorRules().forEach { rule ->
            val key = ruleCauseKey(rule.id)
            val old = causes[key]
            // A finite occurrence is materialized once. A later wall-clock estimate rewrite
            // cannot resurrect it when its anchored duration has already elapsed.
            if (rule.expiresAtEpochMs != null && old?.occurrenceId == rule.id && old.interval?.remainingMs(now) == 0L) return@forEach
            keepOrResolve(key) { zone -> ruleOccurrence(rule, zone, logical) }
        }
        prefs.editorTimes().forEach { limit -> keepOrResolve(timeCauseKey(limit.packageName)) { zone -> dailyOccurrence(zone, logical) } }
        prefs.editorOpens().forEach { limit -> keepOrResolve(openCauseKey(limit.packageName)) { zone -> dailyOccurrence(zone, logical) } }
        // Retain tombstones only until their natural boundary, preventing mid-period count resets.
        val liveKeys = prefs.editorRules().map { ruleCauseKey(it.id) }.toSet() +
            prefs.editorTimes().map { timeCauseKey(it.packageName) } + prefs.editorOpens().map { openCauseKey(it.packageName) }
        causes.entries.removeAll { (key, c) -> key !in liveKeys && c.interval?.remainingMs(now) == 0L }
        require(causes.size <= 2_000) { "Too many commitments" }
        return state.copy(causes = causes.toMap(), timeline = timeline)
    }

    private fun logicalClock(state: EditorCommitmentState, now: AccessClockStamp): AccessClockStamp {
        val timeline = state.timeline ?: return now
        val elapsed = trustedElapsedMs(timeline, now) ?: return now
        return if (timeline.wallEpochMs <= Long.MAX_VALUE - elapsed) now.copy(wallEpochMs = timeline.wallEpochMs + elapsed) else now
    }

    private fun expected(prefs: Preferences, state: EditorCommitmentState, change: EditorChange, now: AccessClockStamp): EditorExpected =
        records(prefs, change).copy(occurrences = change.keys.associateWith { key ->
            state.causes[key]?.takeIf { it.interval == null || it.interval.remainingMs(now) > 0 }?.occurrenceId ?: "inactive"
        })

    private fun records(prefs: Preferences, change: EditorChange): EditorExpected = EditorExpected(
        prefs.editorRules().filter { ruleCauseKey(it.id) in change.keys }.sortedBy { it.id },
        change.limits?.let { l -> prefs.editorTimes().singleOrNull { it.packageName == l.packageName } },
        change.limits?.let { l -> prefs.editorOpens().singleOrNull { it.packageName == l.packageName } }, emptyMap(),
    )

    private fun decision(prefs: Preferences, state: EditorCommitmentState, change: EditorChange, now: AccessClockStamp): Pair<CommitmentPolicy.Outcome, HarderWaitPolicy.Operation?> {
        val outcomes = mutableListOf<CommitmentPolicy.Outcome>()
        val waited = linkedMapOf<HarderWaitPolicy.Occurrence, String>()
        var kind = if (change.timerExit) HarderWaitPolicy.OperationKind.STOP else if (change.upserts.isEmpty() && change.limits?.let { it.time == null && it.open == null } != false) HarderWaitPolicy.OperationKind.DELETE else HarderWaitPolicy.OperationKind.EDIT_WEAKEN
        fun add(key: String, tier: BreakLevel, revision: String, outcome: CommitmentPolicy.Outcome) {
            outcomes += outcome
            if (outcome == CommitmentPolicy.Outcome.WAIT) state.causes[key]?.let { waited[editorOccurrence(key, it)] = revision }
        }
        val current = prefs.editorRules()
        change.keys.filter { it.startsWith("rule:") }.forEach { key ->
            val before = current.singleOrNull { ruleCauseKey(it.id) == key }
            val after = change.upserts.singleOrNull { ruleCauseKey(it.id) == key }
            val cause = state.causes[key]
            val active = before != null && before.enabled && cause != null && (cause.interval == null || cause.interval.remainingMs(now) > 0)
            val activity = if (!active || cause!!.legacyRights) CommitmentPolicy.CauseActivity.Inactive else if (before!!.schedule != null) {
                CommitmentPolicy.CauseActivity.ScheduledOccurrence(true, cause.interval, now)
            } else CommitmentPolicy.CauseActivity.Session(cause.interval, now)
            val outcome = when {
                before == null -> policy.decide(CommitmentPolicy.Action.START_ADDITIONAL, after?.breakLevel ?: BreakLevel.EASY, CommitmentPolicy.CauseActivity.Inactive)
                change.timerExit && before.id.startsWith(NATIVE_TIMER_ID_PREFIX) && !before.id.startsWith(CONTRACT_TIMER_ID_PREFIX) -> CommitmentPolicy.Outcome.ALLOW
                change.timerExit -> policy.decide(CommitmentPolicy.Action.STOP_EARLY, before.breakLevel, activity)
                after == null -> policy.decide(CommitmentPolicy.Action.DELETE, before.breakLevel, activity)
                !before.enabled && after.enabled -> policy.decide(CommitmentPolicy.Action.START_ADDITIONAL, after.breakLevel, CommitmentPolicy.CauseActivity.Inactive)
                !after.enabled && before.enabled -> { kind = HarderWaitPolicy.OperationKind.TOGGLE_OFF; policy.decide(CommitmentPolicy.Action.TOGGLE_OFF, before.breakLevel, activity) }
                else -> policy.decideEdit(ruleShape(before, cause?.zoneId ?: defaultZone()), ruleShape(after, cause?.zoneId ?: defaultZone()), activity)
            }
            add(key, before?.breakLevel ?: after?.breakLevel ?: BreakLevel.EASY, before?.let { PrefsCodec.encodeRules(listOf(it.copy(name = "revision"))) } ?: "new", outcome)
        }
        change.limits?.let { l ->
            val beforeTime = prefs.editorTimes().singleOrNull { it.packageName == l.packageName }
            val beforeOpen = prefs.editorOpens().singleOrNull { it.packageName == l.packageName }
            fun limit(key: String, beforeBudget: Int?, beforeTier: BreakLevel?, afterBudget: Int?, afterTier: BreakLevel?) {
                val activity = if (state.causes[key]?.legacyRights == true) CommitmentPolicy.CauseActivity.Inactive else CommitmentPolicy.CauseActivity.DailyLimit(beforeBudget != null)
                val outcome = when {
                    beforeBudget == null -> policy.decide(CommitmentPolicy.Action.START_ADDITIONAL, afterTier ?: BreakLevel.EASY, CommitmentPolicy.CauseActivity.Inactive)
                    afterBudget == null -> policy.decide(CommitmentPolicy.Action.DELETE, beforeTier!!, activity)
                    else -> policy.decideEdit(limitShape(l.packageName, beforeTier!!, beforeBudget), limitShape(l.packageName, afterTier!!, afterBudget), activity)
                }
                add(key, beforeTier ?: BreakLevel.EASY, "$beforeBudget:$beforeTier", outcome)
            }
            limit(timeCauseKey(l.packageName), beforeTime?.dailyMinutes, beforeTime?.breakLevel, l.time?.dailyMinutes, l.time?.breakLevel)
            limit(openCauseKey(l.packageName), beforeOpen?.dailyOpens, beforeOpen?.breakLevel, l.open?.dailyOpens, l.open?.breakLevel)
        }
        val outcome = listOf(CommitmentPolicy.Outcome.DENY, CommitmentPolicy.Outcome.DEFER, CommitmentPolicy.Outcome.WAIT, CommitmentPolicy.Outcome.ALLOW_AFTER_DISCLOSURE)
            .firstOrNull { it in outcomes } ?: CommitmentPolicy.Outcome.ALLOW
        val operation = if (outcome == CommitmentPolicy.Outcome.WAIT) HarderWaitPolicy.Operation(kind, waited,
            // Exact requested output is part of the scope: editing another draft restarts the wait.
            setOf(change.toString())) else null
        return outcome to operation
    }

    private fun validation(prefs: Preferences, change: EditorChange, now: AccessClockStamp, safety: EditorSafety): String? {
        require(change.upserts.map { it.id }.distinct().size == change.upserts.size && change.upserts.none { it.id in change.deleteIds })
        val current = prefs.editorRules()
        change.upserts.forEach { rule ->
            val old = current.singleOrNull { it.id == rule.id }
            validateEditorRule(rule, old, now)?.let { return it }
            if (rule.targets.allowListMode && !safety.resolved && (old == null || !old.enabled || old.targets != rule.targets || (!old.targets.allowListMode && rule.targets.allowListMode))) {
                return "Calling or Home safety could not be checked. Protection health is degraded; try again before starting allow-list protection."
            }
            if (!rule.targets.allowListMode && rule.targets.apps.any { it.packageName in floor(safety) }) return "Calling, Home, system recovery and Flint apps stay available. Remove those apps from this selection."
        }
        change.limits?.let { l ->
            require(l.time == null || (l.time.packageName == l.packageName && l.time.dailyMinutes in 0..1440))
            require(l.open == null || (l.open.packageName == l.packageName && l.open.dailyOpens in 0..500))
            if ((l.time != null || l.open != null) && l.packageName in floor(safety)) return "Safety and recovery apps stay available and cannot have limits."
        }
        if (change.timerExit && (change.deleteIds.size != 1 || current.singleOrNull { it.id in change.deleteIds }?.let {
            it.id.startsWith(NATIVE_TIMER_ID_PREFIX) && it.enabled && it.schedule == null && !it.isExpired(now.wallEpochMs)
        } != true)) return "This Timer changed or finished. Review its current state."
        return null
    }

    private fun denial(prefs: Preferences, state: EditorCommitmentState, change: EditorChange, now: AccessClockStamp): String {
        val rule = prefs.editorRules().firstOrNull { ruleCauseKey(it.id) in change.keys }
        val end = rule?.let { state.causes[ruleCauseKey(it.id)]?.interval?.let { now.wallEpochMs + it.remainingMs(now) } }
        val deadline = end?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "its end"
        return "${rule?.name ?: "Protection"} · ${rule?.breakLevel ?: BreakLevel.HARDCORE} · Protected until $deadline. Emergency Pass status is unavailable here; check recovery."
    }

    private fun apply(prefs: MutablePreferences, change: EditorChange) {
        if (change.upserts.isNotEmpty() || change.deleteIds.isNotEmpty()) prefs.putEditorRules(
            prefs.editorRules().filterNot { it.id in change.deleteIds || it.id in change.upserts.map(BlockRule::id) } + change.upserts)
        change.limits?.let { l ->
            prefs[PrefsKeys.TIME_LIMITS] = PrefsCodec.encodeTimeLimits(prefs.editorTimes().filterNot { it.packageName == l.packageName } + listOfNotNull(l.time))
            prefs[PrefsKeys.OPEN_LIMITS] = PrefsCodec.encodeOpenLimits(prefs.editorOpens().filterNot { it.packageName == l.packageName } + listOfNotNull(l.open))
        }
    }

    private fun cancelOwnedWait(prefs: MutablePreferences, operation: HarderWaitPolicy.Operation?) {
        if (operation == null) return
        val ledger = readHarderWait(prefs)
        if (ledger.pending?.operation == operation) prefs.putHarderWait(harder.cancel(ledger))
    }

    private fun floor(safety: EditorSafety) = SafetyFloor.STATIC_PACKAGES + "com.flint.peakfocus" + safety.excludedPackages
}

internal fun Preferences.editorRules() = StrictPrefsCodec.rules(StrictPrefsCodec.string(this, PrefsKeys.BLOCK_RULES).orEmpty())
internal fun Preferences.editorTimes() = StrictPrefsCodec.timeLimits(StrictPrefsCodec.string(this, PrefsKeys.TIME_LIMITS).orEmpty(),
    StrictPrefsCodec.level(StrictPrefsCodec.string(this, PrefsKeys.DEFAULT_BREAK_LEVEL)))
internal fun Preferences.editorOpens() = StrictPrefsCodec.openLimits(StrictPrefsCodec.string(this, PrefsKeys.OPEN_LIMITS).orEmpty())
internal fun MutablePreferences.putEditorRules(rules: List<BlockRule>) {
    val encoded = PrefsCodec.encodeRules(rules); StrictPrefsCodec.rules(encoded); this[PrefsKeys.BLOCK_RULES] = encoded
}
internal fun MutablePreferences.putEditorState(state: EditorCommitmentState) {
    this[EDITOR_COMMITMENTS_KEY] = EditorCommitmentCodec.encode(state)
    this[EDITOR_VERSION_KEY] = 1L
}

/** Call inside the same DataStore edit when composing a protection mutation. */
internal fun readEditorState(prefs: Preferences): EditorCommitmentState {
    val raw = StrictPrefsCodec.string(prefs, EDITOR_COMMITMENTS_KEY)
    val version = StrictPrefsCodec.long(prefs, EDITOR_VERSION_KEY)
    if (version != null && version != 1L) wireFuture()
    if ((raw == null) != (version == null)) wireCorrupt()
    return EditorCommitmentCodec.decode(raw)
}
