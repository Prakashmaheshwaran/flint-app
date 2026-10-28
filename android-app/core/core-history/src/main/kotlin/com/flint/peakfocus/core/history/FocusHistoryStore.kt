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
        // Repair a truncated tail before appending, otherwise the next event would join that tail.
        if (snapshot.degraded) rewriteLog(snapshot.events)
        val existing = snapshot.events.mapTo(mutableSetOf()) { it.id }
        val additions = events(snapshot).filter { existing.add(it.id) }
        additions.forEach { event ->
            require(event.knownType != HistoryEventType.COMPLETED || event.at <= clock.millis()) {
                "Completion cannot precede its logical deadline"
            }
            val encoded = HistoryCodec.encode(event)
            HistoryCodec.decode(encoded) // reject invalid writes; never corrupt the durable log
            val bytes = (encoded + "\n").toByteArray(Charsets.UTF_8)
            require(bytes.size <= HARD_CAP) { "Event exceeds log cap" }
            FileOutputStream(log, true).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
        }
        snapshot = readLocked()
        compactIfNeeded(snapshot)
    }

    suspend fun clear(activeSessionIds: Set<String>): Boolean = operation {
        val snapshot = readLocked()
        val running = FocusHistoryAggregator.sessions(snapshot.events, clock.millis(), activeSessionIds)
            .filter { it.outcome == SessionOutcome.RUNNING }.mapTo(mutableSetOf()) { it.id }
        val retained = snapshot.events.filter { it.session in running }
        val old = readMetadata()
        val at = maxOf(old.cleared ?: Long.MIN_VALUE, clock.millis())
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
        val bytes = log.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        val events = mutableListOf<HistoryEvent>()
        var bad = false
        val lines = text.split('\n')
        lines.forEachIndexed { index, line ->
            if (index == lines.lastIndex && line.isEmpty()) return@forEachIndexed
            if (index == lines.lastIndex) { bad = true; return@forEachIndexed }
            try { events += HistoryCodec.decode(line) } catch (_: Exception) { bad = true }
        }
        if (bad) {
            degraded = true
            quarantine(bytes)
            writeMetadata(meta.copy(degraded = true))
        }
        if (meta.pendingClear != null) {
            rewriteLog(events.filter { it.session in meta.keepSessions })
            degraded = false
            writeMetadata(meta.copy(cleared = maxOf(meta.cleared ?: Long.MIN_VALUE, meta.pendingClear),
                degraded = false, pendingClear = null, keepSessions = emptySet()))
            return readLocked()
        }
        return HistorySnapshot(events, maxOf(createdAt, clearedAt ?: createdAt),
            readable = !bad || events.isNotEmpty(), degraded = degraded,
            diagnostic = if (degraded) "Some history couldn't be read" else null).also { state.value = it }
    }

    private data class Metadata(
        val created: Long, val cleared: Long? = null, val compactedDay: Long? = null, val degraded: Boolean = false,
        val pendingClear: Long? = null, val keepSessions: Set<String> = emptySet(),
    )

    private fun readMetadata(): Metadata {
        if (!metadata.exists()) {
            // A pre-existing log without metadata must not invent an earlier history start.
            writeMetadata(Metadata(clock.millis()))
        }
        val bytes = metadata.readBytes()
        return try {
            val fields = FlatJson.decode(bytes.toString(Charsets.UTF_8))
            require(fields["v"] == 1L)
            fun long(key: String) = fields[key]?.let { it as? Long ?: error("Invalid $key") }
            val created = long("createdAt") ?: error("History metadata unavailable")
            val pending = long("pendingClear")
            val keep = fields["keepSessions"]?.let { value ->
                (value as? List<*>)?.map { it as? String ?: error("Invalid retained session") }?.toSet()
                    ?: error("Invalid retained sessions")
            }.orEmpty()
            require(pending == null || fields.containsKey("keepSessions"))
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
        val sizes = groups.mapValues { (_, events) -> events.sumOf { (HistoryCodec.encode(it) + "\n").toByteArray(Charsets.UTF_8).size.toLong() } }
        var size = retainedIds.sumOf { sizes.getValue(it) }
        groups.entries.sortedBy { (_, events) -> events.maxOf { it.at } }.forEach { (id, _) ->
            if (size > HARD_CAP && retainedIds.remove(id)) size -= sizes.getValue(id)
        }
        rewriteLog(snapshot.events.filter { it.session in retainedIds })
        lastCompaction = clock.instant().atZone(zone()).toLocalDate().toEpochDay()
        val meta = readMetadata()
        writeMetadata(meta.copy(compactedDay = lastCompaction, degraded = degraded))
        state.value = readLocked()
    }

    private fun rewriteLog(events: List<HistoryEvent>) = atomicWrite(log,
        events.joinToString("") { HistoryCodec.encode(it) + "\n" }.toByteArray(Charsets.UTF_8))

    private fun quarantine(bytes: ByteArray, label: String = "events") {
        val dir = File(directory, "quarantine")
        check(dir.isDirectory || dir.mkdirs())
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // Filename includes content digest so reopening the same corrupt log never makes another copy.
        val existing = dir.listFiles().orEmpty().firstOrNull { it.name.endsWith("-$digest.jsonl") }
        if (existing == null || !existing.readBytes().contentEquals(bytes)) {
            atomicWrite(existing ?: File(dir, "${clock.millis()}-$label-$digest.jsonl"), bytes)
        }
        dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedByDescending { it.lastModified() }
            .drop(3).forEach { check(it.delete()) }
    }

    private fun syncWrite(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try { syncWrite(temporary, bytes); check(temporary.renameTo(file)) { "History replace failed" } }
        finally { temporary.delete() }
    }

    companion object {
        private val processMutex = Mutex()
        const val RETENTION_MS = 400L * 24 * 60 * 60 * 1000
        const val SOFT_CAP = 1024 * 1024
        const val HARD_CAP = 5 * 1024 * 1024
    }
}
