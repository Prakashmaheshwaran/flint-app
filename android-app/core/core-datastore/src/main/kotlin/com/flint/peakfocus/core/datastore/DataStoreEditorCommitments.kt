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
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

internal val EDITOR_COMMITMENTS_KEY = stringPreferencesKey("editor_commitments_v1")
internal val EDITOR_VERSION_KEY = longPreferencesKey("editor_commitments_version")
private const val EDITOR_DOMAIN_VERSION = 2L
private val EDITOR_DOMAINS = listOf(PrefsKeys.BLOCK_RULES, PrefsKeys.APP_GROUPS, PrefsKeys.TIME_LIMITS,
    PrefsKeys.OPEN_LIMITS, PrefsKeys.DEFAULT_BREAK_LEVEL)
internal const val MAX_EDITOR_CAUSES = 2_000
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
        validateInitializedProtectionChildren(it)
        readEditorState(it).also { _ -> if (StrictPrefsCodec.string(it, EDITOR_COMMITMENTS_KEY) != null) readHarderWait(it) }
    }
    override val terminalEvents = dataStore.data.storageStates(::readTerminalEvents)
    override val terminalDeliveryHealth = dataStore.data.storageStates(::readTerminalDeliveryHealth)
    override suspend fun readTerminalEvents(): List<EditorTerminalEvent> = readTerminalEvents(dataStore.data.first())
    override suspend fun ackTerminalEvents(ids: Set<String>) {
        dataStore.edit { prefs -> prefs.ackTerminalEvents(ids, clockAtCommit?.invoke() ?: ProtectionClock.now()) }
    }
    private val policy = CommitmentPolicy()
    private val harder = HarderWaitPolicy()

    override suspend fun reconcile(now: AccessClockStamp) {
        dataStore.edit { prefs -> prepare(prefs, clockAtCommit?.invoke() ?: now, checkpointQuota = false) }
    }

    override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = transact(now) { prefs, now ->
        val state = prepare(prefs, now, checkpointQuota = false)
        RuleClockProjection.project(editorRulesWithRuntime(prefs.editorRules(), state, now), now)
    }

    override suspend fun guardSessionClock(now: AccessClockStamp, shift: ClockShift): Unit = transact(now) { prefs, now ->
        if (StrictPrefsCodec.string(prefs, EDITOR_COMMITMENTS_KEY) == null) {
            // Legacy import only: there is no anchored duration yet. Never resurrect an
            // already elapsed record, and never apply the same shift to initialized state.
            val current = prefs.editorRules()
            val changed = current.map { rule ->
                val end = rule.expiresAtEpochMs
                if (rule.enabled && end != null && end > shift.oldEpochMs && shift.wallDeltaMs != 0L) {
                    rule.copy(expiresAtEpochMs = end + shift.wallDeltaMs)
                } else rule
            }
            if (changed != current) prefs.putEditorRules(changed)
        }
        var state = checkpointRuleIntervals(prepare(prefs, now), now, force = true)
        val current = prefs.editorRules()
        val changed = current.map { rule ->
            val interval = state.causes[ruleCauseKey(rule.id)]?.interval
            if (rule.expiresAtEpochMs == null || interval == null) rule
            else rule.copy(expiresAtEpochMs = now.wallEpochMs + interval.remainingMs(now))
        }
        if (changed != current) {
            val replacements = current.zip(changed).filter { it.first != it.second }.toMap()
            fun rekeyExpected(expected: EditorExpected) = expected.copy(rules = expected.rules.map { replacements[it] ?: it })
            fun rekeyDraft(change: EditorChange) = change.copy(upserts = change.upserts.map { draft ->
                val before = current.singleOrNull { it.id == draft.id }
                val after = before?.let { replacements[it] }
                if (after != null && draft.expiresAtEpochMs == before.expiresAtEpochMs) draft.copy(expiresAtEpochMs = after.expiresAtEpochMs)
                else draft
            })
            val originalPending = state.pending
            val pendingWasCurrent = originalPending != null && expected(prefs, state, originalPending.change, now) == originalPending.expected
            state = state.copy(
                pending = originalPending?.let { it.copy(change = rekeyDraft(it.change), expected = rekeyExpected(it.expected)) },
                queued = state.queued.map { it.copy(change = rekeyDraft(it.change), expected = rekeyExpected(it.expected)) },
            )
            prefs.putEditorRules(changed)
            // A display-only rewrite must not restart a valid running Harder wait. Only
            // rebind the exact pending operation; a concurrent edit remains stale.
            if (pendingWasCurrent && originalPending?.wait != null) {
                val pending = checkNotNull(state.pending)
                val fresh = decision(prefs, state, pending.change, now)
                val ledger = readHarderWait(prefs)
                val waiting = ledger.pending
                val operation = fresh.second
                if (fresh.first == pending.outcome && operation != null && waiting?.operation == originalPending.wait) {
                    state = state.copy(pending = pending.copy(wait = operation))
                    prefs.putHarderWait(ledger.copy(pending = waiting.copy(operation = operation)))
                }
            }
        }
        prefs.putEditorState(state)
    }

    override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult =
        transact(now) { prefs, stamp -> request(prefs, stamp, safety) { change } }

    override suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean): EditorCommitResult =
        transact(now) { prefs, stamp ->
            request(prefs, stamp, safety) {
                val current = prefs.editorRules().filter { it.id in ruleIds }
                if (current.isEmpty() || (requireAll && current.map { it.id }.toSet() != ruleIds)) null
                else EditorChange(if (requireAll && current.size == 1) "$label ${current.single().name}" else label,
                    upserts = current.map { it.copy(enabled = enabled) })
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
            if (boundaries.isEmpty()) {
                val openEnded = change.keys.any { key -> state.causes[key]?.let { it.interval == null } == true }
                return@transact EditorCommitResult.Rejected(if (openEnded)
                    "This protection has no scheduled end. A future-only change cannot take effect until this protection ends."
                    else "This occurrence ended. Review the change again.")
            }
            state = state.copy(queued = retained + QueuedEditorChange(id, change, pending.expected, boundaries))
        } else {
            if (change.limits?.let { it.time != null || it.open != null } == true) {
                // Initialize while the old domain is still intact, before adding its first limit.
                prepareOpenCredits(prefs, now, defaultZone())
            }
            apply(prefs, change)
            // Explicitly confirmed edits may shorten or extend an active snapshot. Ordinary
            // reconciliation and clock-display rewrites never acquire that authority.
            val causes = state.causes.toMutableMap()
            change.upserts.forEach { rule ->
                val key = ruleCauseKey(rule.id)
                val cause = causes[key] ?: return@forEach
                val ended = cause.interval?.remainingMs(now) == 0L
                val explicitlyEnabled = rule.enabled && pending.expected.rules.singleOrNull { it.id == rule.id }?.enabled == false
                // A rename cannot restart an elapsed finite session. A confirmed Start
                // from disabled may resume only when its current finite end is still future.
                if (ended && rule.expiresAtEpochMs != null && !explicitlyEnabled) return@forEach
                val candidate = ruleOccurrence(rule, cause.zoneId,
                    if (rule.expiresAtEpochMs != null) now else logicalClock(state, now))
                val nextInterval = if (candidate == null) AnchoredInterval(now, 0, now.wallEpochMs)
                    else candidate.interval?.let { AnchoredInterval(now, it.remainingAtAnchorMs, now.wallEpochMs + it.remainingAtAnchorMs) }
                // Null means open-ended; zero means inactive. Both directions must replace
                // the old runtime shape when the user explicitly confirms the change.
                val startsFinite = cause.interval == null && nextInterval != null && candidate != null
                val updated = (if (ended || startsFinite) candidate ?: cause else cause).copy(interval = nextInterval)
                causes[key] = updated
                if (startsFinite && updated.occurrenceId != cause.occurrenceId) {
                    // Assign the canonical window ID without refunding the just-committed
                    // Harder operation or earlier friction on this still-active cause.
                    val ledger = readHarderWait(prefs)
                    val before = editorOccurrence(key, cause)
                    val after = editorOccurrence(key, updated)
                    ledger.counts[before]?.let { count ->
                        prefs.putHarderWait(ledger.copy(counts = ledger.counts - before +
                            (after to maxOf(count, ledger.counts[after] ?: 0))))
                    }
                }
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

    override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): Boolean =
        commitTimer(rule, now, safety) is TimerStartResult.Applying

    override suspend fun commitTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): TimerStartResult = transact(now) { prefs, now ->
        if (hasActiveProtectionRecovery(prefs, now)) return@transact TimerStartResult.Rejected("Resume protection first.")
        var state = prepare(prefs, now)
        require(rule.id.startsWith(CONTRACT_TIMER_ID_PREFIX) && rule.schedule == null && rule.enabled)
        require(!rule.targets.allowListMode && rule.targets.apps.isNotEmpty() && rule.targets.apps.none { it.packageName.isBlank() || it.packageName in floor(safety) })
        require(isValidNewSessionDuration((rule.expiresAtEpochMs ?: 0) - now.wallEpochMs))
        val current = prefs.editorRules()
        if (state.causes.size >= MAX_EDITOR_CAUSES) return@transact TimerStartResult.Rejected("Protection capacity is full. Finish an existing commitment and try again.")
        val occupied = current.any { existing ->
            existing.id == rule.id || isActiveEditorSession(existing, state.causes[ruleCauseKey(existing.id)], now)
        }
        if (occupied) return@transact TimerStartResult.Rejected("A Timer is already active. Finish it before starting another.")
        val startDecision = policy.decide(CommitmentPolicy.Action.START_ADDITIONAL, rule.breakLevel, CommitmentPolicy.CauseActivity.Inactive)
        check(startDecision in setOf(CommitmentPolicy.Outcome.ALLOW, CommitmentPolicy.Outcome.ALLOW_AFTER_DISCLOSURE))
        prefs.putEditorRules(current + rule)
        state = materialize(prefs, state, now)
        prefs.putEditorState(state)
        TimerStartResult.Applying(rule.id, now)
    }

    private suspend fun <R> transact(requestedAt: AccessClockStamp, action: (MutablePreferences, AccessClockStamp) -> R): R {
        var result: Any? = null
        dataStore.edit { result = action(it, clockAtCommit?.invoke() ?: requestedAt) }
        @Suppress("UNCHECKED_CAST") return result as R
    }

    /** Call inside one DataStore edit: migrate, apply queued changes, then materialize new occurrences. */
    internal fun prepare(prefs: MutablePreferences, now: AccessClockStamp, checkpointQuota: Boolean = true): EditorCommitmentState {
        validateInitializedProtectionChildren(prefs)
        // Validate established domains before quota preparation or any migration write.
        val decoded = readEditorState(prefs)
        val previous = if (StrictPrefsCodec.string(prefs, EDITOR_COMMITMENTS_KEY) != null) decoded else null
        val quota = if (prefs.editorTimes().isNotEmpty() || prefs.editorOpens().isNotEmpty() ||
            StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) != null) {
            val zone = previous?.causes?.entries?.firstOrNull { dailyKey(it.key) }?.value?.zoneId ?: defaultZone()
            prepareOpenCredits(prefs, now, zone, checkpoint = checkpointQuota)
        } else null
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
            state = checkNotNull(previous)
            readHarderWait(prefs) // Missing initialized friction state is not a fresh occurrence.
        }
        state = preserveDailyUntilQuotaReset(state, quota, now)
        state = captureNaturalTimerTerminals(prefs, state, now)
        // Timer terminal capture belongs before this checkpoint: a reboot fallback is
        // bounded protection, not evidence that elapsed completion was observed.
        state = checkpointRuleIntervals(state, now)
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
        // A real editor state change is a relevant mutation; an unchanged publication is not.
        if (quota != null && EditorCommitmentCodec.encode(state) != StrictPrefsCodec.string(prefs, EDITOR_COMMITMENTS_KEY)) {
            prepareOpenCredits(prefs, now, quota.quotaZone)
        }
        prefs.putEditorState(state)
        return state
    }

    private fun materialize(prefs: Preferences, state: EditorCommitmentState, now: AccessClockStamp): EditorCommitmentState {
        val quota = if (StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) != null) readOpenCredits(prefs) else null
        val timeline = state.timeline ?: now
        val logical = logicalClock(state, now)
        val causes = state.causes.toMutableMap()
        fun keepOrResolve(key: String, recurring: Boolean = false, finite: Boolean? = null, resolve: (String) -> EditorCause?) {
            val old = causes[key]
            // A queued edit can change either runtime shape without changing the rule ID.
            // Daily causes do not supply a template shape; their reset remains independent.
            val shapeChanged = old != null && finite != null && ((old.interval == null) != !finite)
            if (old != null && !shapeChanged && (old.interval == null || old.interval.remainingMs(now) > 0)) return
            val zone = old?.zoneId ?: defaultZone()
            val candidate = resolve(zone)
            // Retain the last expired identity even between windows, so a later rollback
            // cannot replay the same local occurrence or reset its accounting.
            if (old != null && !shapeChanged && (candidate == null || candidate.occurrenceId == old.occurrenceId ||
                (recurring && ScheduleOccurrences.sameStartDate(candidate.occurrenceId, old.occurrenceId, ZoneId.of(zone))))) return
            val resolved = candidate
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
            // A newly started finite Timer already carries a duration in the current wall
            // estimate. Only recurring templates use the saved schedule timeline.
            keepOrResolve(key, recurring = rule.schedule != null && rule.expiresAtEpochMs == null,
                finite = if (rule.enabled) rule.schedule != null || rule.expiresAtEpochMs != null else null) { zone ->
                ruleOccurrence(rule, zone, if (rule.expiresAtEpochMs != null) now else logical)
            }
        }
        fun daily(key: String) {
            if (quota == null) { keepOrResolve(key) { zone -> dailyOccurrence(zone, logical) }; return }
            val old = causes[key]
            if (old != null && old.occurrenceId != "inactive" && old.interval?.remainingMs(now)?.let { it > 0 } == true) return
            val remaining = OpenCreditPolicy().remainingToResetMs(quota.reset, now)
            causes[key] = EditorCause("quota:${quota.periodId}", AnchoredInterval(now, remaining, now.wallEpochMs + remaining), quota.quotaZone)
        }
        prefs.editorTimes().forEach { daily(timeCauseKey(it.packageName)) }
        prefs.editorOpens().forEach { daily(openCauseKey(it.packageName)) }
        // Retain tombstones only until their natural boundary, preventing mid-period count resets.
        val liveKeys = prefs.editorRules().map { ruleCauseKey(it.id) }.toSet() +
            prefs.editorTimes().map { timeCauseKey(it.packageName) } + prefs.editorOpens().map { openCauseKey(it.packageName) }
        causes.entries.removeAll { (key, c) -> key !in liveKeys && c.interval?.remainingMs(now) == 0L && c.timerTerminal?.pending == null }
        wireRequire(causes.size <= MAX_EDITOR_CAUSES)
        return state.copy(causes = causes.toMap(), timeline = timeline, quotaPeriodId = quota?.periodId ?: state.quotaPeriodId)
    }

    private fun dailyKey(key: String) = key.startsWith("time:") || key.startsWith("open:")

    /** Persist cross-boot bounds or a measured clock-event checkpoint, never on every read. */
    private fun checkpointRuleIntervals(state: EditorCommitmentState, now: AccessClockStamp, force: Boolean = false): EditorCommitmentState {
        fun checkpoint(interval: AnchoredInterval): AnchoredInterval {
            val remaining = interval.remainingMs(now)
            // Observing an elapsed end is a durable terminal checkpoint too. Retaining a
            // positive saved duration here would let a later reboot + rollback revive it.
            if (!force && trustedElapsedMs(interval.anchor, now) != null &&
                (remaining > 0 || interval.remainingAtAnchorMs == 0L)) return interval
            return AnchoredInterval(now, remaining, now.wallEpochMs + remaining)
        }
        return state.copy(
            timeline = state.timeline?.takeIf { trustedElapsedMs(it, now) != null } ?: now,
            causes = state.causes.mapValues { (key, cause) ->
                if (dailyKey(key)) cause else cause.copy(interval = cause.interval?.let(::checkpoint))
            },
            queued = state.queued.map { q -> q.copy(boundaries = q.boundaries.mapValues { (key, interval) ->
                if (dailyKey(key)) interval else checkpoint(interval)
            }) },
        )
    }

    /** Daily counters are reboot-conservative; finite-restriction wall fallback must not reset n. */
    private fun preserveDailyUntilQuotaReset(state: EditorCommitmentState, quota: OpenCreditLedger?, now: AccessClockStamp): EditorCommitmentState {
        if (quota == null) return state
        val rolled = state.quotaPeriodId != null && state.quotaPeriodId != quota.periodId
        val remaining = if (rolled) 0L else quota.reset.remainingToResetMs
        val interval = AnchoredInterval(now, remaining, now.wallEpochMs + remaining)
        fun unchanged(original: AnchoredInterval) = !rolled && trustedElapsedMs(original.anchor, now) != null &&
            original.remainingMs(now) == remaining
        val causes = state.causes.mapValues { (key, cause) ->
            if (!dailyKey(key) || cause.occurrenceId == "inactive" || cause.interval?.let(::unchanged) == true) cause
            else cause.copy(interval = interval)
        }
        val queued = state.queued.map { q -> q.copy(boundaries = q.boundaries.mapValues { (key, original) ->
            if (dailyKey(key) && q.expected.occurrences[key] == state.causes[key]?.occurrenceId && !unchanged(original)) interval else original
        }) }
        return state.copy(causes = causes, queued = queued)
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
        val added = change.upserts.map { ruleCauseKey(it.id) }.toSet() +
            change.limits?.let { listOfNotNull(it.time?.let { timeCauseKey(it.packageName) }, it.open?.let { openCauseKey(it.packageName) }) }.orEmpty()
        if ((readEditorState(prefs).causes.keys + added).size > MAX_EDITOR_CAUSES) return "Protection capacity is full. Finish an existing commitment and try again."
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
    val version = StrictPrefsCodec.long(this, EDITOR_VERSION_KEY)
    validateEditorDomains(this, state, version)
    // Old editor v1 allowed absent unused domains. Establish explicit empty values only
    // during the validated one-time upgrade, in the same commit as its version marker.
    if (version != EDITOR_DOMAIN_VERSION) {
        for (key in EDITOR_DOMAINS) if (StrictPrefsCodec.string(this, key) == null) {
            this[key] = if (key == PrefsKeys.DEFAULT_BREAK_LEVEL) BreakLevel.EASY.name else ""
        }
    }
    val encoded = EditorCommitmentCodec.encode(state)
    if (StrictPrefsCodec.string(this, EDITOR_COMMITMENTS_KEY) != encoded) this[EDITOR_COMMITMENTS_KEY] = encoded
    if (version != EDITOR_DOMAIN_VERSION) this[EDITOR_VERSION_KEY] = EDITOR_DOMAIN_VERSION
}

/** Call inside the same DataStore edit when composing a protection mutation. */
internal fun readEditorState(prefs: Preferences): EditorCommitmentState {
    val raw = StrictPrefsCodec.string(prefs, EDITOR_COMMITMENTS_KEY)
    val version = StrictPrefsCodec.long(prefs, EDITOR_VERSION_KEY)
    if (version != null && version !in 1L..EDITOR_DOMAIN_VERSION) wireFuture()
    if ((raw == null) != (version == null)) wireCorrupt()
    return EditorCommitmentCodec.decode(raw).also { validateEditorDomains(prefs, it, version) }
}

/**
 * Never turn missing established policy into authoritative empty protection. No writes.
 * Read boundaries revalidate their exact snapshot, including callers outside prepare. Editor
 * materialization caps causes at MAX_EDITOR_CAUSES; modern ledger codecs also bound bytes/rows.
 * Legacy configuration decoding remains linear in saved records. Missing v1/markerless domains
 * deliberately rescan surviving ownership on every read until the validated upgrade commits.
 */
private fun validateEditorDomains(prefs: Preferences, state: EditorCommitmentState, version: Long?) {
    if (version != null && version !in 1L..EDITOR_DOMAIN_VERSION) wireFuture()
    val tier = StrictPrefsCodec.level(StrictPrefsCodec.string(prefs, PrefsKeys.DEFAULT_BREAK_LEVEL))
    StrictPrefsCodec.rules(StrictPrefsCodec.string(prefs, PrefsKeys.BLOCK_RULES).orEmpty())
    StrictPrefsCodec.groups(StrictPrefsCodec.string(prefs, PrefsKeys.APP_GROUPS).orEmpty())
    val rawTimes = StrictPrefsCodec.string(prefs, PrefsKeys.TIME_LIMITS)
    StrictPrefsCodec.timeLimits(rawTimes.orEmpty(), tier)
    StrictPrefsCodec.openLimits(StrictPrefsCodec.string(prefs, PrefsKeys.OPEN_LIMITS).orEmpty())
    if (version == EDITOR_DOMAIN_VERSION) {
        for (key in EDITOR_DOMAINS) wireRequire(StrictPrefsCodec.string(prefs, key) != null)
    } else {
        // v1 always wrote Time Limits, including the empty list. Its absence is never unused.
        if (version == 1L) wireRequire(rawTimes != null)
        // Markerless main may lack unused domains, but surviving domain-specific owners
        // still prevent a partial loss of editor metadata from becoming a fresh policy.
        val missing = listOf("rule:" to PrefsKeys.BLOCK_RULES,
            "time:" to PrefsKeys.TIME_LIMITS, "open:" to PrefsKeys.OPEN_LIMITS,
            "default:" to PrefsKeys.DEFAULT_BREAK_LEVEL)
            .filter { StrictPrefsCodec.string(prefs, it.second) == null }
        if (missing.isNotEmpty()) {
            val references = editorDomainReferences(prefs, state)
            for ((prefix, _) in missing) wireRequire(references.none { it.startsWith(prefix) })
        }
    }
}

/** Read surviving references, including inactive/tombstoned and pending ownership, without pruning. */
private fun editorDomainReferences(prefs: Preferences, state: EditorCommitmentState): Set<String> = buildSet {
    addAll(state.causes.keys)
    fun expected(value: EditorExpected) {
        addAll(value.occurrences.keys)
        addAll(value.rules.map { ruleCauseKey(it.id) })
        value.time?.let { add(timeCauseKey(it.packageName)) }
        value.open?.let { add(openCauseKey(it.packageName)) }
    }
    fun wait(value: HarderWaitPolicy.Operation?) { value?.causes?.keys?.forEach { add(it.causeId) } }
    state.pending?.let { addAll(it.change.keys); expected(it.expected); wait(it.wait) }
    state.queued.forEach { addAll(it.change.keys); addAll(it.boundaries.keys); expected(it.expected) }
    val harder = readHarderWait(prefs)
    addAll(harder.counts.keys.map { it.causeId }); wait(harder.pending?.operation)
    fun focusReferences(raw: String?): Boolean {
        val focus = FocusLedgerCodec.decode(raw)
        addAll(focus.breaksByCause.keys)
        focus.exemptions.values.forEach {
            add(it.causeKey)
            if (it.kind == ExemptionKind.OPEN_LIMIT) add(openCauseKey(it.packageName))
        }
        return focus.protectionInitialized
    }
    val protectionInitialized = focusReferences(StrictPrefsCodec.string(prefs, PrefsKeys.FOCUS_LEDGER))
    readEditorTerminals(prefs).pending.values.forEach { add(ruleCauseKey(it.sessionId)) }
    StrictPrefsCodec.string(prefs, ARCHIVE_RULES)?.let { raw ->
        addAll(StrictPrefsCodec.rules(raw).map { ruleCauseKey(it.id) })
    }
    StrictPrefsCodec.string(prefs, ARCHIVE_TIMES)?.let { raw ->
        addAll(StrictPrefsCodec.timeLimits(raw).map { timeCauseKey(it.packageName) })
    }
    // Shipped main counted every allowed foreground open, including apps with no limit.
    // Validate/preserve that accounting, but never infer Open policy from generic usage.
    StrictPrefsCodec.string(prefs, PrefsKeys.OPEN_COUNTS)?.let { StrictPrefsCodec.counts(it) }
    if (StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) != null) {
        val credits = readOpenCredits(prefs)
        addAll((credits.counters.keys + credits.active.keys).map(::openCauseKey))
        OpenCreditCodec.readArchive(StrictPrefsCodec.string(prefs, OPEN_CREDIT_ARCHIVE) ?: wireCorrupt()).first?.let {
            addAll(StrictPrefsCodec.counts(it).keys.map(::openCauseKey))
        }
    } else {
        // An orphan/future ledger cannot establish that an absent domain is unused.
        for (key in listOf(OPEN_CREDIT_STATE, OPEN_CREDIT_COUNTS, OPEN_CREDIT_ARCHIVE))
            wireRequire(StrictPrefsCodec.string(prefs, key) == null)
    }
    val schema = StrictPrefsCodec.long(prefs, PrefsKeys.PROTECTION_SCHEMA)
    val rawProtection = StrictPrefsCodec.string(prefs, PrefsKeys.PROTECTION_LEDGER)
    val rawArchive = StrictPrefsCodec.string(prefs, PrefsKeys.PROTECTION_ARCHIVE)
    if (schema == null) {
        wireRequire(rawProtection == null && rawArchive == null && !protectionInitialized)
    } else {
        if (schema != 1L) wireFuture()
        wireRequire(protectionInitialized)
        // Never call readFocus/validateInitializedProtectionChildren here: they call readEditorState.
        val protection = ProtectionLedgerCodec.decode(rawProtection ?: wireCorrupt())
        wireRequire(protection.lastUsedAtEpochMs == StrictPrefsCodec.long(prefs, PrefsKeys.EMERGENCY_PASS_USED_AT))
        protection.recovery?.let {
            addAll(it.affectedCauseKeys)
            addAll(it.endedTransientIds.map(::ruleCauseKey))
        }
        protection.ordinaryGrants.values.forEach { grant -> grant.scopes.forEach { scope ->
            if (scope.counter.rule.authority == RuleAuthority.Personal)
                add("${scope.counter.rule.source}:${scope.counter.rule.ruleId}")
        } }
        protection.pendingCreditEnds.values.forEach { add(openCauseKey(it.target)) }
        protection.outbox.values.forEach { addAll(it.endedTransientIds.map(::ruleCauseKey)) }
        // Unqualified affectedRuleIds may name foreign rules. History, receipt hashes and
        // cooldown alone cannot establish ownership of every optional editor domain.
        val archive = readProtectionArchiveDomains(rawArchive ?: wireCorrupt())
        archive[PrefsKeys.FOCUS_LEDGER.name]?.let { focusReferences(it) }
        archive[PrefsKeys.BLOCK_RULES.name]?.let { addAll(StrictPrefsCodec.rules(it).map { ruleCauseKey(it.id) }) }
        archive[PrefsKeys.OPEN_LIMITS.name]?.let { addAll(StrictPrefsCodec.openLimits(it).map { openCauseKey(it.packageName) }) }
        archive[PrefsKeys.OPEN_COUNTS.name]?.let { StrictPrefsCodec.counts(it) }
        val tier = archive[PrefsKeys.DEFAULT_BREAK_LEVEL.name]?.let {
            add("default:"); StrictPrefsCodec.level(it)
        } ?: BreakLevel.EASY
        archive[PrefsKeys.TIME_LIMITS.name]?.let { addAll(StrictPrefsCodec.timeLimits(it, tier).map { timeCauseKey(it.packageName) }) }
        archive[PrefsKeys.BREAK_SESSION.name]?.let { StrictPrefsCodec.session(it) }
    }
}

/** Read C3's preserved A|1 inputs without reopening a transaction or interpreting them as live rights. */
private fun readProtectionArchiveDomains(raw: String): Map<String, String?> {
    val strings = setOf(PrefsKeys.FOCUS_LEDGER.name, PrefsKeys.BREAK_SESSION.name, PrefsKeys.OPEN_COUNTS.name,
        PrefsKeys.DEFAULT_BREAK_LEVEL.name, PrefsKeys.BLOCK_RULES.name, PrefsKeys.TIME_LIMITS.name, PrefsKeys.OPEN_LIMITS.name)
    val longs = setOf(PrefsKeys.EMERGENCY_PASS_USED_AT.name, PrefsKeys.EMERGENCY_PASS_WEEK_START.name, PrefsKeys.OPEN_COUNTS_DAY.name)
    val names = strings + longs
    val values = linkedMapOf<String, String?>()
    for (row in wireRows(raw, "A")) {
        wireRequire(row.size == 3)
        val key = readWireAtom(row[0])
        wireRequire(key in names)
        wireRequire(!values.containsKey(key))
        val value = PrefsCodec.unescape(row[2]).also { wireRequire(PrefsCodec.escape(it) == row[2]) }
        values[key] = when (row[1]) {
            "false" -> { wireRequire(value.isEmpty()); null }
            "true" -> {
                // Historical day/week sentinels can be negative; the archive writer accepted Long.
                if (key in longs) wireRequire(value.toLongOrNull()?.toString() == value)
                value
            }
            else -> wireCorrupt()
        }
    }
    wireRequire(values.keys == names)
    return values
}
