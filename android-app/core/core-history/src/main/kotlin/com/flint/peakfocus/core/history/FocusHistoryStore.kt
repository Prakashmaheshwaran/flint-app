package com.flint.peakfocus.core.history

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class HistoryClearPhase { BEFORE_INTENT, INTENT_COMMITTED, LOG_REPLACED, METADATA_COMMITTED }

/** App-private, single-process log. All instances share the lock; no enforcement caller waits here. */
class FocusHistoryStore(
    val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val clearPhase: (HistoryClearPhase) -> Unit = {},
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val log = File(directory, "events.v1.jsonl")
    private val metadata = File(directory, "metadata.v1.json")
    private val state = MutableStateFlow(HistorySnapshot())
    val snapshots: StateFlow<HistorySnapshot> = state.asStateFlow()
    private var lastCompaction: Long? = null
    private var degraded = false

    suspend fun load(): HistorySnapshot = operation {
        val snapshot = readLocked()
        compactIfNeeded(snapshot)
    }.let { state.value }

    suspend fun append(event: HistoryEvent): Boolean = update { listOf(event) }

    /** Reconcile against disk while holding the same lock used by every append/clear. */
    suspend fun update(events: (HistorySnapshot) -> List<HistoryEvent>): Boolean = operation {
        var snapshot = readLocked()
        val existing = snapshot.events.mapTo(mutableSetOf()) { it.id }
        val additions = events(snapshot).filter { existing.add(it.id) }
        val accepted = snapshot.events.toMutableList()
        val terminals = snapshot.events.filter { it.knownType?.terminal == true }
            .groupBy { it.session }.toMutableMap()
        val writes = linkedMapOf<String, ByteArray>()
        var replacedTerminal = false
        var total = 0L
        additions.forEach { original ->
            val encoded = HistoryCodec.encode(original)
            require('\n' !in encoded) { "An event must occupy one JSONL record" }
            require(utf8Size(encoded) + 1 <= MAX_RECORD_BYTES) { "Event exceeds append budget" }
            val bytes = (encoded + "\n").toByteArray(Charsets.UTF_8)
            total += bytes.size
            require(total <= MAX_BATCH_BYTES) { "Batch exceeds append budget" }
            val event = HistoryCodec.decode(encoded, forAppend = true)
            if (event.knownType == HistoryEventType.COMPLETED) {
                require(event.at <= clock.millis()) { "Completion cannot be in the future" }
                val plan = accepted.filter { it.session == event.session && it.at <= event.at && it.knownType in setOf(
                    HistoryEventType.STARTED, HistoryEventType.RESCHEDULED) }.sortedBy { it.at }.lastOrNull()
                require(plan == null || (plan.plannedEnd != null && event.at >= plan.plannedEnd)) {
                    "Completion cannot precede its effective logical deadline"
                }
            }
            if (event.knownType?.terminal == true) {
                val prior = terminals[event.session].orEmpty()
                if (prior.isNotEmpty()) {
                    // First explicit outcome wins. A missing-row observation cannot
                    // outrank it, regardless of delivery order or wall timestamps.
                    if (event.observationTerminal || prior.any { !it.observationTerminal }) return@forEach
                    accepted.removeAll { it.session == event.session && it.knownType?.terminal == true }
                    prior.forEach { writes.remove(it.id) }
                    replacedTerminal = true
                }
                terminals[event.session] = listOf(event)
            }
            accepted += event
            writes[event.id] = bytes
        }
        require(accepted.size <= MAX_RECORDS) { "History record budget exceeded" }
        require(accepted.sumOf { it.sessions?.size ?: 0 } <= MAX_REFERENCES_TOTAL) { "History reference budget exceeded" }
        // Validation is all-or-nothing. Repair only after every candidate passed validation.
        if (snapshot.degraded || replacedTerminal) rewriteLog(accepted)
        else if (writes.size > 1) atomicWrite(log) { stream ->
            log.inputStream().use { it.copyTo(stream) }
            writes.values.forEach(stream::write)
        } else if (writes.isNotEmpty()) FileOutputStream(log, true).use { stream ->
            stream.write(writes.values.single()); stream.flush(); stream.fd.sync()
        }
        snapshot = readLocked()
        compactIfNeeded(snapshot)
    }

    /** The caller supplies owner-active identities, independently of history's planned endpoints. */
    suspend fun clear(activeSessionIds: Set<String>): Boolean = clear { activeSessionIds }

    /** Re-read owner activity under the history lock; unknown activity never authorizes clear. */
    suspend fun clear(activeSessionIds: () -> Set<String>?): Boolean {
        var ownerReady = true
        val committed = operation {
            val active = try { activeSessionIds() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            if (active == null) { ownerReady = false; return@operation }
            val snapshot = readLocked()
            val running = snapshot.events.groupBy { it.session }.filter { (id, events) ->
                id in active && events.any { it.knownType == HistoryEventType.STARTED } &&
                    events.none { it.knownType?.terminal == true }
            }.keys
            val retained = retainForSessions(snapshot.events, running)
            val old = readMetadata()
            // A rollback cannot put already committed records after the clear boundary.
            val recordedThrough = snapshot.events.filter { it.knownType != null }.maxOfOrNull {
                maxOf(it.at, it.observedAt ?: it.at)
            } ?: Long.MIN_VALUE
            val at = maxOf(old.cleared ?: Long.MIN_VALUE, clock.millis(), recordedThrough)
            clearPhase(HistoryClearPhase.BEFORE_INTENT)
            val intent = old.copy(pendingClear = at, keepSessions = running)
            writeMetadata(intent)
            clearPhase(HistoryClearPhase.INTENT_COMMITTED)
            rewriteLog(retained)
            clearPhase(HistoryClearPhase.LOG_REPLACED)
            degraded = false
            writeMetadata(intent.copy(cleared = at, degraded = false, pendingClear = null, keepSessions = emptySet()))
            clearPhase(HistoryClearPhase.METADATA_COMMITTED)
            state.value = readLocked()
        }
        return committed && ownerReady
    }

    suspend fun compact(): Boolean = operation { compactLocked(readLocked()) }

    private suspend fun operation(block: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            try { block(); true } catch (e: CancellationException) { throw e } catch (e: Exception) {
                degraded = true
                state.value = state.value.copy(degraded = true, diagnostic = e.javaClass.simpleName)
                false
            }
        }
    }

    private fun readLocked(): HistorySnapshot {
        check(directory.isDirectory || directory.mkdirs()) { "History directory unavailable" }
        val meta = readMetadata()
        val createdAt = meta.created
        val clearedAt = meta.cleared
        lastCompaction = meta.compactedDay
        degraded = degraded || meta.degraded
        if (!log.exists()) syncWrite(log, byteArrayOf())
        if (log.length() > READ_CAP) {
            // Preserve the original before resetting. Never allocate an unbounded external file.
            return resetOverBudgetLog(meta)
        }
        val events = mutableListOf<HistoryEvent>()
        var bad = false
        // Count before materializing any record/ownership graph. Quarantine externally
        // dense logs before a degraded reset, so subsequent sessions can still be recorded.
        var records = 0
        log.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer); if (count < 0) break
                repeat(count) { if (buffer[it] == '\n'.code.toByte()) records++ }
            }
        }
        if (records > MAX_RECORDS) {
            return resetOverBudgetLog(meta)
        }
        // JSONL is LF-delimited. Raw CR is valid JSON whitespace and must survive verbatim.
        var references = 0
        try { log.bufferedReader().use { reader ->
            val buffer = CharArray(8192); val line = StringBuilder()
            while (true) {
                val count = reader.read(buffer); if (count < 0) break
                var start = 0
                repeat(count) { index ->
                    if (buffer[index] == '\n') {
                        line.append(buffer, start, index - start)
                        try {
                            val event = HistoryCodec.decode(line.toString())
                            references += event.sessions?.size ?: 0
                            if (references > MAX_REFERENCES_TOTAL) throw ReferenceBudgetExceeded()
                            events += event
                            if (event.opaque && event.v == 1 && HistoryEventType.fromWire(event.type) != null) bad = true
                        } catch (e: ReferenceBudgetExceeded) { throw e } catch (_: Exception) { bad = true }
                        line.setLength(0); start = index + 1
                    }
                }
                line.append(buffer, start, count - start)
                check(line.length <= READ_CAP)
            }
            if (line.isNotEmpty()) bad = true
        } } catch (_: ReferenceBudgetExceeded) { return resetOverBudgetLog(meta) }
        if (bad) {
            degraded = true
            quarantine(log)
            writeMetadata(meta.copy(degraded = true))
        }
        if (meta.pendingClear != null) {
            rewriteLog(retainForSessions(events, meta.keepSessions))
            degraded = false
            writeMetadata(meta.copy(cleared = maxOf(meta.cleared ?: Long.MIN_VALUE, meta.pendingClear),
                degraded = false, pendingClear = null, keepSessions = emptySet()))
            return readLocked()
        }
        return HistorySnapshot(events, maxOf(createdAt, clearedAt ?: createdAt),
            readable = !bad || events.isNotEmpty(), degraded = degraded,
            diagnostic = if (degraded) "Some history couldn't be read" else null,
            clearedAt = clearedAt).also { state.value = it }
    }

    private data class Metadata(
        val created: Long, val cleared: Long? = null, val compactedDay: Long? = null, val degraded: Boolean = false,
        val pendingClear: Long? = null, val keepSessions: Set<String> = emptySet(),
    )

    private class ReferenceBudgetExceeded : Exception()
    private fun resetOverBudgetLog(meta: Metadata): HistorySnapshot {
        quarantine(log)
        atomicWrite(log, byteArrayOf())
        degraded = true
        writeMetadata(meta.copy(degraded = true, pendingClear = null, keepSessions = emptySet()))
        return readLocked()
    }

    private fun readMetadata(): Metadata {
        if (!metadata.exists()) {
            // A pre-existing log without metadata must not invent an earlier history start.
            writeMetadata(Metadata(clock.millis()))
        }
        if (metadata.length() > HistoryCodec.MATERIALIZATION_LIMIT) {
            quarantine(metadata, "metadata")
            degraded = true
            return Metadata(clock.millis(), degraded = true).also { writeMetadata(it) }
        }
        val bytes = metadata.readBytes()
        return try {
            val fields = FlatJson.decode(bytes.toString(Charsets.UTF_8))
            require(fields["v"] == 1L)
            fun long(key: String) = fields[key]?.let { it as? Long ?: error("Invalid $key") }
            val created = long("createdAt") ?: error("History metadata unavailable")
            val pending = long("pendingClear")
            val retained = fields["keepSessions"]
            require(pending == null || retained is List<*>) { "Invalid clear intent" }
            val keep = retained?.let { value ->
                (value as? List<*>)?.map { (it as? String)?.takeIf(String::isNotBlank)
                    ?: error("Invalid retained session") }?.toSet() ?: error("Invalid retained sessions")
            }.orEmpty()
            val flag = fields["degraded"]?.let { it as? Boolean ?: error("Invalid diagnostic") } ?: false
            Metadata(created, long("clearedAt"), long("compactedDay"), flag, pending, keep)
        } catch (e: Exception) {
            quarantine(bytes, "metadata")
            // Neither creation nor clear can be trusted. Never invent an earlier historyStart.
            degraded = true
            Metadata(clock.millis(), degraded = true).also { writeMetadata(it) }
        }
    }

    private fun writeMetadata(meta: Metadata) = atomicWrite(metadata,
        FlatJson.encode(linkedMapOf("v" to 1L, "createdAt" to meta.created, "clearedAt" to meta.cleared,
            "compactedDay" to meta.compactedDay, "degraded" to meta.degraded,
            "pendingClear" to meta.pendingClear, "keepSessions" to meta.keepSessions.toList())).toByteArray(Charsets.UTF_8))

    private fun compactIfNeeded(snapshot: HistorySnapshot) {
        val today = clock.instant().atZone(zone()).toLocalDate().toEpochDay()
        if (lastCompaction != today || log.length() > SOFT_CAP) compactLocked(snapshot)
    }

    private fun compactLocked(snapshot: HistorySnapshot) {
        val cutoff = clock.millis() - RETENTION_MS
        val groups = snapshot.events.groupBy { it.session }
        val retainedIds = groups.filterValues { events -> events.maxOf { it.at } >= cutoff }.keys.toMutableSet()
        // A global pass belongs to its operation and every affected session. Keep that
        // shared record until all retained owners disappear, including during hard-cap trim.
        val owners = groupOwners(groups)
        val dependents = mutableMapOf<String, MutableList<String>>()
        owners.forEach { (group, ids) -> ids.forEach { dependents.getOrPut(it) { mutableListOf() }.add(group) } }
        val references = owners.mapValues { (_, ids) -> ids.count { it in retainedIds } }.toMutableMap()
        val sizes = groups.mapValues { (_, events) -> events.sumOf { utf8Size(HistoryCodec.encode(it)) + 1 } }
        var size = groups.keys.sumOf { if (references.getValue(it) > 0) sizes.getValue(it) else 0 }
        groups.entries.sortedBy { (_, events) -> events.maxOf { it.at } }.forEach { (id, _) ->
            if (size > HARD_CAP && retainedIds.remove(id)) dependents[id].orEmpty().forEach { group ->
                val remaining = references.getValue(group) - 1
                references[group] = remaining
                if (remaining == 0) size -= sizes.getValue(group)
            }
        }
        rewriteLog(snapshot.events.filter { references.getValue(it.session) > 0 })
        lastCompaction = clock.instant().atZone(zone()).toLocalDate().toEpochDay()
        val meta = readMetadata()
        writeMetadata(meta.copy(compactedDay = lastCompaction, degraded = degraded))
        state.value = readLocked()
    }

    private fun rewriteLog(events: List<HistoryEvent>) = atomicWrite(log) { stream ->
        stream.writer(Charsets.UTF_8).apply {
            events.forEach { write(HistoryCodec.encode(it)); write("\n") }; flush()
        }
    }

    private fun groupOwners(groups: Map<String, List<HistoryEvent>>): Map<String, Set<String>> =
        groups.mapValues { (id, group) -> buildSet {
            add(id)
            group.filter { it.knownType == HistoryEventType.PASS_USED }.forEach { addAll(it.sessions.orEmpty()) }
        } }
    private fun retainForSessions(events: List<HistoryEvent>, retained: Set<String>): List<HistoryEvent> {
        val owners = groupOwners(events.groupBy { it.session })
        return events.filter { event -> owners.getValue(event.session).any { it in retained } }.map { event ->
            if (event.knownType != HistoryEventType.PASS_USED || event.sessions == null) return@map event
            val references = event.sessions.filter { it in retained }
            if (references == event.sessions) return@map event
            // A shared pass may survive for a running session; clear the other IDs
            // while retaining unrelated forward-compatible fields in its envelope.
            val line = event.originalLine?.let { original ->
                FlatJson.encode(FlatJson.decode(original).toMutableMap().apply { put("sessions", references) })
            }
            event.copy(sessions = references, originalLine = line)
        }
    }

    private fun quarantine(bytes: ByteArray, label: String = "events") {
        val source = File(directory, "quarantine-source.tmp")
        try { syncWrite(source, bytes); quarantine(source, label) } finally { source.delete() }
    }

    private fun quarantine(source: File, label: String = "events") {
        val dir = File(directory, "quarantine")
        check(dir.isDirectory || dir.mkdirs())
        fun digest(file: File): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest()
        }
        val hash = digest(source)
        val digest = hash.joinToString("") { "%02x".format(it) }
        // Filename includes content digest so reopening the same corrupt log never makes another copy.
        val existing = dir.listFiles().orEmpty().firstOrNull { it.name.endsWith("-$digest.jsonl") }
        if (existing == null || existing.length() != source.length() || !digest(existing).contentEquals(hash)) {
            atomicWrite(existing ?: File(dir, "${clock.millis()}-$label-$digest.jsonl")) { output ->
                source.inputStream().use { it.copyTo(output) }
            }
        }
        dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedByDescending { it.lastModified() }
            .drop(3).forEach { check(it.delete()) }
    }

    private fun syncWrite(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        atomicWrite(file) { it.write(bytes) }
    }
    private fun atomicWrite(file: File, write: (FileOutputStream) -> Unit) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { write(it); it.flush(); it.fd.sync() }
            check(temporary.renameTo(file)) { "History replace failed" }
        }
        finally { temporary.delete() }
    }

    private fun utf8Size(text: String): Long {
        var bytes = 0L; var index = 0
        while (index < text.length) {
            val c = text[index++]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
                c.isSurrogate() -> 1 // UTF-8 encoder's replacement byte
                else -> 3
            }
        }
        return bytes
    }

    companion object {
        private val processMutex = Mutex()
        const val RETENTION_MS = 400L * 24 * 60 * 60 * 1000
        const val SOFT_CAP = 1024 * 1024
        const val HARD_CAP = 5 * 1024 * 1024
        const val READ_CAP = 2L * HARD_CAP
        const val MAX_RECORD_BYTES = 64 * 1024
        const val MAX_BATCH_BYTES = 1024 * 1024
        const val MAX_RECORDS = 20_000
        const val MAX_REFERENCES_TOTAL = 20_000
    }
}
