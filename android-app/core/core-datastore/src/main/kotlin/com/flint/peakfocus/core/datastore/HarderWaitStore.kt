package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.flow.Flow

/** One occurrence-scoped ledger shared by breaks and editor/Timer weakening (ADR-012 §4). */
interface HarderWaitStore {
    val state: Flow<StorageState<HarderWaitPolicy.Ledger>>
    /** Acknowledged only after the single DataStore transaction is durable. */
    suspend fun <R> transact(transform: (HarderWaitPolicy.Ledger) -> HarderWaitMutation<R>): R
}

data class HarderWaitMutation<R>(val next: HarderWaitPolicy.Ledger, val result: R)

internal val HARDER_WAIT_KEY = stringPreferencesKey("harder_wait_ledger_v1")

internal fun readHarderWait(prefs: Preferences): HarderWaitPolicy.Ledger {
    val raw = StrictPrefsCodec.string(prefs, HARDER_WAIT_KEY)
    if (raw == null && StrictPrefsCodec.string(prefs, EDITOR_COMMITMENTS_KEY) != null) wireCorrupt()
    return HarderWaitCodec.decode(raw)
}

internal fun MutablePreferences.putHarderWait(ledger: HarderWaitPolicy.Ledger) {
    this[HARDER_WAIT_KEY] = HarderWaitCodec.encode(ledger)
}

class DataStoreHarderWaitStore(private val dataStore: DataStore<Preferences>) : HarderWaitStore {
    override val state = dataStore.data.storageStates(::readHarderWait)

    override suspend fun <R> transact(transform: (HarderWaitPolicy.Ledger) -> HarderWaitMutation<R>): R {
        var result: HarderWaitMutation<R>? = null
        dataStore.edit { prefs ->
            val mutation = transform(readHarderWait(prefs))
            prefs.putHarderWait(mutation.next)
            result = mutation
        }
        return checkNotNull(result).result
    }
}

/** Bounded, canonical, versioned wire format; unknown/malformed data never becomes a fresh wait. */
internal object HarderWaitCodec {
    fun encode(value: HarderWaitPolicy.Ledger): String = buildList {
        add("H|1")
        value.counts.entries.sortedWith(compareBy({ it.key.causeId }, { it.key.occurrenceId })).forEach { (key, count) ->
            require(count >= 0)
            add("C|${wireAtom(key.causeId)}|${wireAtom(key.occurrenceId)}|$count")
        }
        value.pending?.let { p ->
            add("P|${p.operation.kind.name}|${p.startedAt.wallEpochMs}|${p.startedAt.elapsedRealtimeMs}|${wireAtom(p.startedAt.bootSessionId)}|${p.requiredMs}")
            p.operation.causes.entries.sortedWith(compareBy({ it.key.causeId }, { it.key.occurrenceId })).forEach { (key, revision) ->
                add("O|${wireAtom(key.causeId)}|${wireAtom(key.occurrenceId)}|${wireAtom(revision)}")
            }
            p.operation.targetScope.sorted().forEach { add("T|${wireAtom(it)}") }
        }
    }.joinToString("\n").also { decode(it) }

    fun decode(encoded: String?): HarderWaitPolicy.Ledger {
        if (encoded == null) return HarderWaitPolicy.Ledger()
        val rows = wireRows(encoded, "H")
        val counts = linkedMapOf<HarderWaitPolicy.Occurrence, Int>()
        val causes = linkedMapOf<HarderWaitPolicy.Occurrence, String>()
        val targets = linkedSetOf<String>()
        var pendingFields: List<String>? = null
        rows.forEach { f ->
            when (f[0]) {
                "C" -> {
                    wireRequire(f.size == 4)
                    val key = HarderWaitPolicy.Occurrence(readWireAtom(f[1]), readWireAtom(f[2]))
                    val count = f[3].toIntOrNull()?.takeIf { it >= 0 } ?: wireCorrupt()
                    wireRequire(counts.put(key, count) == null)
                }
                "P" -> { wireRequire(f.size == 6 && pendingFields == null); pendingFields = f }
                "O" -> {
                    wireRequire(f.size == 4)
                    val key = HarderWaitPolicy.Occurrence(readWireAtom(f[1]), readWireAtom(f[2]))
                    wireRequire(causes.put(key, readWireAtom(f[3])) == null)
                }
                "T" -> { wireRequire(f.size == 2 && targets.add(readWireAtom(f[1]))) }
                else -> wireFuture()
            }
        }
        val pending = pendingFields?.let { f ->
            val kind = HarderWaitPolicy.OperationKind.entries.firstOrNull { it.name == f[1] } ?: wireFuture()
            val wall = wireLong(f[2]); val elapsed = wireLong(f[3]); val required = wireLong(f[5])
            wireRequire(causes.isNotEmpty() && required in listOf(60_000L, 300_000L, 900_000L))
            HarderWaitPolicy.PendingWait(HarderWaitPolicy.Operation(kind, causes.toMap(), targets.toSet()),
                AccessClockStamp(wall, elapsed, readWireAtom(f[4])), required)
        }
        wireRequire(pending != null || (causes.isEmpty() && targets.isEmpty()))
        return HarderWaitPolicy.Ledger(counts.toMap(), pending)
    }
}

internal fun wireCorrupt(): Nothing = throw StorageUnavailableException(StorageFailure.CORRUPT)
internal fun wireFuture(): Nothing = throw StorageUnavailableException(StorageFailure.UNSUPPORTED_VERSION)
internal fun wireRequire(value: Boolean) { if (!value) wireCorrupt() }
internal fun wireLong(value: String): Long = value.toLongOrNull()?.takeIf { it >= 0 } ?: wireCorrupt()
internal fun wireAtom(value: String): String {
    require(value.isNotBlank() && value.length <= 262_144)
    return PrefsCodec.escape(value)
}
internal fun readWireAtom(value: String): String = PrefsCodec.unescape(value).also {
    wireRequire(it.isNotBlank() && it.length <= 262_144 && PrefsCodec.escape(it) == value)
}
internal fun wireRows(encoded: String, header: String): List<List<String>> {
    wireRequire(encoded.length <= 4_194_304)
    val lines = encoded.split('\n')
    wireRequire(lines.size <= 10_000)
    val version = lines.first().split('|')
    wireRequire(version.size == 2 && version[0] == header && version[1].toIntOrNull() != null)
    if (version[1] != "1") wireFuture()
    return lines.drop(1).map { wireRequire(it.isNotBlank()); it.split('|') }
}
