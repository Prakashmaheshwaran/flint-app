package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.blocking.engine.clockDiscontinuity
import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.blocking.engine.OpenCreditLedgerPolicy
import com.flint.peakfocus.core.model.AccessClockStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

enum class EditorTerminalKind { COMPLETED }

/** Proven natural endpoint; observation/receipt time is never substituted for logical end. */
data class EditorTerminalEvent(
    val id: String,
    val sessionId: String,
    val occurrenceId: String,
    val kind: EditorTerminalKind,
    val logicalEnd: AccessClockStamp,
    val observedAt: AccessClockStamp,
    val revision: Long,
    val timeUncertain: Boolean,
)

data class EditorTerminalEvidence(val logicalEnd: AccessClockStamp, val observedAt: AccessClockStamp, val timeUncertain: Boolean)

/** Finalized stays with its cause until ack/pruning, so retries cannot emit or count it twice. */
data class EditorTimerTerminalState(
    val untrustedBoot: Boolean = false,
    val recorded: Boolean = false,
    val pending: EditorTerminalEvidence? = null,
)

/** Observe-only history health, separate from permission or enforcement authority. */
data class EditorTerminalDeliveryHealth(
    val pendingCount: Int,
    val overflowCount: Int,
    val oldestObservedAt: AccessClockStamp?,
    /** Proven ends beyond the bounded reservoir; never presented as delivered history. */
    val overflowDropped: Long = 0,
    /** Endpoints that could not be represented safely; no completion was manufactured. */
    val captureFailures: Long = 0,
) {
    val deliveryPending: Boolean get() = pendingCount > 0 || overflowCount > 0
    val incompleteHistory: Boolean get() = overflowDropped > 0 || captureFailures > 0
}

/** The sink returns true only after its idempotent durable HistoryEvent append. */
suspend fun EditorCommitmentStore.deliverTerminalEvents(deliver: suspend (EditorTerminalEvent) -> Boolean): Int {
    val delivered = mutableSetOf<String>()
    for (event in readTerminalEvents()) {
        coroutineContext.ensureActive()
        try {
            if (deliver(event)) delivered += event.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed event stays pending without starving later independent events.
        }
    }
    coroutineContext.ensureActive()
    if (delivered.isNotEmpty()) ackTerminalEvents(delivered)
    // Cancellation before this batch ack leaves every stable ID replayable.
    return delivered.size
}

internal val EDITOR_TERMINALS = stringPreferencesKey("editor_terminal_events_v1")
internal val EDITOR_TERMINAL_SCHEMA = longPreferencesKey("editor_terminal_schema_v1")
internal const val MAX_PENDING_TERMINALS = 256
internal const val MAX_OVERFLOW_TERMINALS = 256
internal const val MAX_TERMINAL_ACKS = 1_024
internal const val TERMINAL_ACK_RETENTION_MS = 30L * 86_400_000

internal data class EditorTerminalLedger(
    val revision: Long = 0,
    val pending: Map<String, EditorTerminalEvent> = emptyMap(),
    val acknowledged: Map<String, Long> = emptyMap(),
    val overflowDropped: Long = 0,
    val captureFailures: Long = 0,
)

internal fun readEditorTerminals(prefs: Preferences): EditorTerminalLedger {
    val schema = StrictPrefsCodec.long(prefs, EDITOR_TERMINAL_SCHEMA)
    val raw = StrictPrefsCodec.string(prefs, EDITOR_TERMINALS)
    if (schema == null && raw == null) return EditorTerminalLedger()
    if (schema != null && schema > 2) wireFuture()
    wireRequire((schema == 1L || schema == 2L) && raw != null)
    wireRequire(raw!!.substringBefore('\n') == "T|$schema")
    return EditorTerminalCodec.decode(raw)
}

/** Pure same-edit read for C3's eventual shared lifecycle outbox. */
internal fun readTerminalEvents(prefs: Preferences): List<EditorTerminalEvent> =
    readEditorTerminals(prefs).pending.values.sortedBy { it.revision }

internal fun readTerminalDeliveryHealth(prefs: Preferences): EditorTerminalDeliveryHealth {
    val ledger = readEditorTerminals(prefs)
    val pending = ledger.pending.values
    val overflow = readEditorState(prefs).causes.values.mapNotNull { it.timerTerminal?.pending }
    val oldest = (pending.map { it.observedAt } + overflow.map { it.observedAt }).minByOrNull { it.wallEpochMs }
    return EditorTerminalDeliveryHealth(pending.size, overflow.size, oldest, ledger.overflowDropped, ledger.captureFailures)
}

private fun MutablePreferences.putEditorTerminals(value: EditorTerminalLedger) {
    val encoded = EditorTerminalCodec.encode(value)
    if (StrictPrefsCodec.string(this, EDITOR_TERMINALS) != encoded) this[EDITOR_TERMINALS] = encoded
    if (StrictPrefsCodec.long(this, EDITOR_TERMINAL_SCHEMA) != 2L) this[EDITOR_TERMINAL_SCHEMA] = 2L
}

private fun retainedAcks(ledger: EditorTerminalLedger, wall: Long) = ledger.acknowledged
    .filterValues { wall < it || wall - it <= TERMINAL_ACK_RETENTION_MS }
    .entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
    .take(MAX_TERMINAL_ACKS).associate { it.toPair() }

/** Ack in a separate serialized edit, after the consumer has durably appended its event. */
internal fun MutablePreferences.ackTerminalEvents(ids: Set<String>, now: AccessClockStamp) {
    val current = readEditorTerminals(this)
    val known = ids.intersect(current.pending.keys)
    if (known.isEmpty()) return
    val next = current.copy(pending = current.pending - known,
        acknowledged = current.acknowledged + known.associateWith { now.wallEpochMs })
    putEditorTerminals(next.copy(acknowledged = retainedAcks(next, now.wallEpochMs)))
    pruneAcknowledgedTimers(this, readEditorState(this), next, now)?.let { putEditorState(it) }
}

/** History has durably accepted these IDs; raw rows and recorded tombstones can retire together. */
private fun pruneAcknowledgedTimers(prefs: MutablePreferences, state: EditorCommitmentState,
                                    ledger: EditorTerminalLedger?, now: AccessClockStamp): EditorCommitmentState? {
    if (ledger == null) return null
    val retired = state.causes.filter { (key, cause) ->
        key.startsWith("rule:$NATIVE_TIMER_ID_PREFIX") && cause.timerTerminal?.recorded == true &&
            cause.interval?.remainingMs(now) == 0L && "editor:completed:${cause.occurrenceId}" in ledger.acknowledged &&
            state.queued.none { key in it.change.keys } && state.pending?.change?.keys?.contains(key) != true
    }.keys
    if (retired.isEmpty()) return null
    val ids = retired.map { it.removePrefix("rule:") }.toSet()
    prefs.putEditorRules(prefs.editorRules().filterNot { it.id in ids })
    return state.copy(causes = state.causes - retired)
}

private fun incrementBounded(value: Long) = if (value == Long.MAX_VALUE) value else value + 1

/**
 * Called before due queued deletion, in the same editor edit. No history reconstruction.
 * Pending overflow lives in the already-bounded cause state until acks make room.
 */
internal fun captureNaturalTimerTerminals(prefs: MutablePreferences, state: EditorCommitmentState,
                                          now: AccessClockStamp): EditorCommitmentState {
    // History corruption cannot erase a plan or block an unrelated Easy exit. Preserve evidence
    // in the cause and expose the unavailable outbox through terminalEvents/deliveryHealth.
    var ledger = try { readEditorTerminals(prefs) } catch (_: StorageUnavailableException) { null }
    val original = ledger
    ledger = ledger?.let { it.copy(acknowledged = retainedAcks(it, now.wallEpochMs)) }
    val rules = prefs.editorRules().associateBy { it.id }
    val causes = state.causes.toMutableMap()
    var overflowCount = 0
    for ((key, cause) in state.causes.toSortedMap()) {
        if (!key.startsWith("rule:$NATIVE_TIMER_ID_PREFIX") || cause.timerTerminal?.recorded == true) continue
        val id = key.removePrefix("rule:")
        if (cause.occurrenceId != id) continue
        val interval = cause.interval ?: continue
        var terminal = cause.timerTerminal ?: EditorTimerTerminalState()
        var evidence = terminal.pending
        if (evidence == null) {
            if (trustedElapsedMs(interval.anchor, now) == null) {
                terminal = terminal.copy(untrustedBoot = true)
            } else if (!terminal.untrustedBoot && interval.remainingAtAnchorMs > 0 &&
                interval.remainingMs(now) == 0L && rules[id]?.let { it.enabled && it.expiresAtEpochMs != null } == true) {
                val end = try { AccessClockStamp(interval.wallEndEpochMs,
                    Math.addExact(interval.anchor.elapsedRealtimeMs, interval.remainingAtAnchorMs), interval.anchor.bootSessionId) }
                catch (_: ArithmeticException) { null }
                val candidate = end?.let { EditorTerminalEvidence(it, now, clockDiscontinuity(interval.anchor, now)) }
                val valid = candidate != null && try { validateTerminalEvidence(candidate); true }
                    catch (_: StorageUnavailableException) { false }
                if (!valid) {
                    ledger = ledger?.let { it.copy(captureFailures = incrementBounded(it.captureFailures)) }
                    terminal = terminal.copy(recorded = true)
                } else {
                    evidence = candidate
                    terminal = terminal.copy(pending = evidence)
                }
            }
        }
        if (evidence != null) {
            val eventId = "editor:completed:${cause.occurrenceId}"
            val current = ledger
            if (current != null && (eventId in current.pending || eventId in current.acknowledged)) {
                terminal = terminal.copy(recorded = true, pending = null)
            } else if (current != null && current.pending.size < MAX_PENDING_TERMINALS && current.revision < Long.MAX_VALUE &&
                eventId.length <= 2_048 && id.length <= 2_048) {
                val event = EditorTerminalEvent(eventId, id, cause.occurrenceId, EditorTerminalKind.COMPLETED,
                    evidence.logicalEnd, evidence.observedAt, current.revision + 1, evidence.timeUncertain)
                val next = current.copy(revision = event.revision, pending = current.pending + (eventId to event))
                // A capacity/size refusal keeps compact evidence for a later delivery, never drops it.
                val fits = try { EditorTerminalCodec.encode(next); true } catch (_: StorageUnavailableException) { false }
                if (fits) {
                    ledger = next
                    terminal = terminal.copy(recorded = true, pending = null)
                }
            }
        }
        if (terminal.pending != null) {
            if (overflowCount < MAX_OVERFLOW_TERMINALS) overflowCount++
            else if (ledger != null) {
                ledger = ledger.copy(overflowDropped = incrementBounded(ledger.overflowDropped))
                terminal = terminal.copy(recorded = true, pending = null)
            } else {
                // The outbox itself is unavailable. Keep this cause finalized, and surface
                // unavailable health rather than inventing a trustworthy loss count.
                terminal = terminal.copy(recorded = true, pending = null)
            }
        }
        if (terminal != EditorTimerTerminalState()) causes[key] = cause.copy(timerTerminal = terminal)
    }
    if (ledger != null && ledger != original) prefs.putEditorTerminals(ledger)
    val captured = state.copy(causes = causes.toMap())
    return pruneAcknowledgedTimers(prefs, captured, ledger, now) ?: captured
}

/** Canonical, versioned, bounded outbox. Unacknowledged events are never age-pruned. */
internal object EditorTerminalCodec {
    fun encode(value: EditorTerminalLedger): String = buildList {
        wireRequire(value.revision >= 0 && value.pending.size <= MAX_PENDING_TERMINALS && value.acknowledged.size <= MAX_TERMINAL_ACKS)
        wireRequire(value.overflowDropped >= 0 && value.captureFailures >= 0)
        wireRequire(value.pending.keys.intersect(value.acknowledged.keys).isEmpty())
        add("T|2"); add("R|${value.revision}"); add("H|${value.overflowDropped}|${value.captureFailures}")
        value.pending.toSortedMap().forEach { (id, event) ->
            wireRequire(id == event.id && event.revision in 1..value.revision)
            validateTerminalEvidence(EditorTerminalEvidence(event.logicalEnd, event.observedAt, event.timeUncertain))
            wireRequire(event.sessionId.startsWith(NATIVE_TIMER_ID_PREFIX) && event.occurrenceId == event.sessionId && id == "editor:completed:${event.occurrenceId}")
            add("E|${wireAtom(id)}|${wireAtom(event.sessionId)}|${wireAtom(event.occurrenceId)}|${event.kind.name}|" +
                "${terminalClock(event.logicalEnd)}|${terminalClock(event.observedAt)}|${event.revision}|${event.timeUncertain}")
        }
        value.acknowledged.toSortedMap().forEach { (id, at) -> wireRequire(at >= 0); add("A|${wireAtom(id)}|$at") }
    }.joinToString("\n").also { wireRequire(it.length <= 4_194_304); decode(it) }

    fun decode(raw: String): EditorTerminalLedger {
        var revision: Long? = null
        var health: Pair<Long, Long>? = null
        val pending = linkedMapOf<String, EditorTerminalEvent>(); val acks = linkedMapOf<String, Long>()
        val hasHealth = raw.substringBefore('\n') == "T|2"
        val rows = wireRows(if (hasHealth) "T|1" + raw.removePrefix("T|2") else raw, "T")
        rows.forEach { f -> when (f[0]) {
            "R" -> { wireRequire(f.size == 2 && revision == null); revision = wireLong(f[1]) }
            "H" -> { wireRequire(f.size == 3 && health == null); health = wireLong(f[1]) to wireLong(f[2]) }
            "E" -> {
                wireRequire(f.size == 13)
                val kind = EditorTerminalKind.entries.firstOrNull { it.name == f[4] } ?: wireFuture()
                val event = EditorTerminalEvent(readWireAtom(f[1]), readWireAtom(f[2]), readWireAtom(f[3]), kind,
                    readTerminalClock(f.subList(5, 8)), readTerminalClock(f.subList(8, 11)), wireLong(f[11]), terminalBool(f[12]))
                wireRequire(event.sessionId.startsWith(NATIVE_TIMER_ID_PREFIX) && event.occurrenceId == event.sessionId && event.id == "editor:completed:${event.occurrenceId}")
                validateTerminalEvidence(EditorTerminalEvidence(event.logicalEnd, event.observedAt, event.timeUncertain))
                wireRequire(pending.put(event.id, event) == null)
            }
            "A" -> { wireRequire(f.size == 3 && acks.put(readWireAtom(f[1]), wireLong(f[2])) == null) }
            else -> wireFuture()
        } }
        wireRequire(revision != null && (!hasHealth || health != null) && pending.values.all { it.revision in 1..revision!! })
        wireRequire(pending.values.map { it.revision }.distinct().size == pending.size)
        wireRequire(pending.size <= MAX_PENDING_TERMINALS && acks.size <= MAX_TERMINAL_ACKS && pending.keys.intersect(acks.keys).isEmpty())
        return EditorTerminalLedger(revision!!, pending.toMap(), acks.toMap(), health?.first ?: 0, health?.second ?: 0)
    }
}

internal fun terminalClock(value: AccessClockStamp) = "${value.wallEpochMs}|${value.elapsedRealtimeMs}|${wireAtom(value.bootSessionId)}"
internal fun readTerminalClock(f: List<String>) = AccessClockStamp(wireLong(f[0]), wireLong(f[1]), readWireAtom(f[2]))
internal fun terminalBool(value: String) = value.toBooleanStrictOrNull() ?: wireCorrupt()
internal fun validateTerminalEvidence(value: EditorTerminalEvidence) {
    listOf(value.logicalEnd, value.observedAt).forEach { wireRequire(it.wallEpochMs in 0..OpenCreditLedgerPolicy.MAX_WALL_MS && it.elapsedRealtimeMs >= 0 && it.bootSessionId.isNotBlank()) }
    wireRequire(trustedElapsedMs(value.logicalEnd, value.observedAt)?.let { it <= OpenCreditLedgerPolicy.MAX_WALL_MS } == true)
    if (!value.timeUncertain) wireRequire(!clockDiscontinuity(value.logicalEnd, value.observedAt))
}
