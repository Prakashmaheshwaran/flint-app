package com.flint.peakfocus.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No credentials or personal rules belong in this dedicated cache. */
data class ManagedCacheBinding(val namespace: String, val userId: String)
data class ManagedCacheFence(val sessionId: String, val binding: ManagedCacheBinding?)
data class ManagedCachedSnapshot(
    val binding: ManagedCacheBinding,
    val sessionId: String,
    val revision: Long,
    val fingerprint: String,
    val payload: String,
    val serverTimeEpochMs: Long,
    val receivedElapsedMs: Long,
    val bootId: String,
)
data class ManagedCacheEnvelope(
    val fence: ManagedCacheFence? = null,
    val snapshot: ManagedCachedSnapshot? = null,
    val watermarkBinding: ManagedCacheBinding? = null,
    val watermark: Long = 0,
    val excludedMemberships: Set<String> = emptySet(),
)
enum class ManagedCacheFailure { IO, CORRUPT, UNSUPPORTED_VERSION }
sealed interface ManagedCacheState {
    data object Loading : ManagedCacheState
    data class Ready(val value: ManagedCacheEnvelope) : ManagedCacheState
    data class Unavailable(val reason: ManagedCacheFailure) : ManagedCacheState
}
sealed interface ManagedCacheCommit {
    data class Committed(val value: ManagedCacheEnvelope) : ManagedCacheCommit
    data class Unavailable(val reason: ManagedCacheFailure) : ManagedCacheCommit
}
interface ManagedCacheStore {
    val state: Flow<ManagedCacheState>
    suspend fun update(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope): ManagedCacheCommit
}

private val Context.managedCacheDataStore by preferencesDataStore(name = "managed_authority_cache")
object ManagedCaches {
    fun open(context: Context): ManagedCacheStore = DataStoreManagedCacheStore(context.applicationContext.managedCacheDataStore)
}

private object ManagedKeys {
    val version = intPreferencesKey("schema")
    val session = stringPreferencesKey("session")
    val namespace = stringPreferencesKey("namespace")
    val uid = stringPreferencesKey("uid")
    val watermarkNamespace = stringPreferencesKey("watermark_namespace")
    val watermarkUid = stringPreferencesKey("watermark_uid")
    val watermark = longPreferencesKey("watermark")
    val excluded = stringSetPreferencesKey("excluded_memberships")
    val snapshotNamespace = stringPreferencesKey("snapshot_namespace")
    val snapshotUid = stringPreferencesKey("snapshot_uid")
    val snapshotSession = stringPreferencesKey("snapshot_session")
    val revision = longPreferencesKey("snapshot_revision")
    val fingerprint = stringPreferencesKey("snapshot_fingerprint")
    val payload = stringPreferencesKey("snapshot_payload")
    val serverTime = longPreferencesKey("server_time")
    val receivedElapsed = longPreferencesKey("received_elapsed")
    val boot = stringPreferencesKey("boot")
    val all = setOf(version, session, namespace, uid, watermarkNamespace, watermarkUid, watermark,
        excluded, snapshotNamespace, snapshotUid, snapshotSession, revision, fingerprint, payload, serverTime, receivedElapsed, boot)
    val snapshot = setOf(snapshotNamespace, snapshotUid, snapshotSession, revision, fingerprint, payload, serverTime, receivedElapsed, boot)
}

class DataStoreManagedCacheStore(private val disk: DataStore<Preferences>) : ManagedCacheStore {
    override val state: Flow<ManagedCacheState> = disk.data.map { decodeManagedCache(it) }
        .onStart { emit(ManagedCacheState.Loading) }
        .catch { error ->
            when (error) {
                is CorruptionException -> emit(ManagedCacheState.Unavailable(ManagedCacheFailure.CORRUPT))
                is IOException -> emit(ManagedCacheState.Unavailable(ManagedCacheFailure.IO))
                else -> throw error
            }
        }

    override suspend fun update(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope): ManagedCacheCommit {
        var next: ManagedCacheEnvelope? = null
        return try {
            disk.edit { prefs ->
                val current = when (val state = decodeManagedCache(prefs)) {
                    is ManagedCacheState.Ready -> state.value
                    is ManagedCacheState.Unavailable -> throw ManagedCacheException(state.reason)
                    ManagedCacheState.Loading -> error("Unexpected loading state")
                }
                val updated = transform(current)
                validateManagedTransition(current, updated)
                if (updated != current) writeManagedCache(prefs, updated)
                next = updated
            }
            ManagedCacheCommit.Committed(checkNotNull(next))
        } catch (error: ManagedCacheException) {
            ManagedCacheCommit.Unavailable(error.failure)
        } catch (_: CorruptionException) {
            ManagedCacheCommit.Unavailable(ManagedCacheFailure.CORRUPT)
        } catch (_: IOException) {
            ManagedCacheCommit.Unavailable(ManagedCacheFailure.IO)
        }
    }
}

/** A serialized, immutable fake for cache/identity race tests. */
class InMemoryManagedCacheStore(initial: ManagedCacheEnvelope = ManagedCacheEnvelope()) : ManagedCacheStore {
    private val mutex = Mutex()
    private val value = MutableStateFlow(initial)
    override val state: Flow<ManagedCacheState> = value.map<ManagedCacheEnvelope, ManagedCacheState> { ManagedCacheState.Ready(it) }.onStart { emit(ManagedCacheState.Loading) }
    override suspend fun update(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope): ManagedCacheCommit = mutex.withLock {
        val next = transform(value.value)
        validateManagedTransition(value.value, next)
        value.value = next.copy(excludedMemberships = next.excludedMemberships.toSet())
        ManagedCacheCommit.Committed(value.value)
    }
}

private class ManagedCacheException(val failure: ManagedCacheFailure) : Exception()
private fun validAtom(value: String, max: Int = 512) = value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)
private fun validateManagedTransition(old: ManagedCacheEnvelope, next: ManagedCacheEnvelope) {
    fun binding(value: ManagedCacheBinding) { require(validAtom(value.namespace) && validAtom(value.userId, 128)) }
    next.fence?.let { require(validAtom(it.sessionId, 80)); it.binding?.let(::binding) }
    require(next.watermark >= 0 && next.watermark <= 9_007_199_254_740_991L)
    next.watermarkBinding?.let(::binding)
    require(next.watermarkBinding != null || next.watermark == 0L)
    if (old.watermarkBinding != null && old.watermarkBinding == next.watermarkBinding) require(next.watermark >= old.watermark)
    require(next.excludedMemberships.size <= 20 && next.excludedMemberships.all { validAtom(it, 80) })
    next.snapshot?.let {
        binding(it.binding)
        require(validAtom(it.sessionId, 80) && validAtom(it.bootId, 100))
        require(it.revision >= 0 && it.revision <= next.watermark && it.binding == next.watermarkBinding)
        require(next.fence?.binding == it.binding && next.fence.sessionId == it.sessionId)
        require(it.fingerprint.matches(Regex("[a-f0-9]{64}")))
        require(it.payload.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        require(it.serverTimeEpochMs in 0..253_402_300_799_999L && it.receivedElapsedMs >= 0)
    }
}

private fun decodeManagedCache(prefs: Preferences): ManagedCacheState = try {
    if (prefs.asMap().isEmpty()) ManagedCacheState.Ready(ManagedCacheEnvelope())
    else {
        val keys = prefs.asMap().keys
        require(keys.all { it in ManagedKeys.all })
        val version = checkNotNull(prefs[ManagedKeys.version])
        if (version != 1) throw ManagedCacheException(ManagedCacheFailure.UNSUPPORTED_VERSION)
        require(ManagedKeys.watermark in keys && ManagedKeys.excluded in keys)
        val snapshotKeys = keys.intersect(ManagedKeys.snapshot)
        require(snapshotKeys.isEmpty() || snapshotKeys == ManagedKeys.snapshot)
        fun binding(namespace: Preferences.Key<String>, uid: Preferences.Key<String>): ManagedCacheBinding? {
            val n = prefs[namespace]; val u = prefs[uid]
            require((n == null) == (u == null))
            return n?.let { ManagedCacheBinding(it, checkNotNull(u)) }
        }
        val fenceBinding = binding(ManagedKeys.namespace, ManagedKeys.uid)
        val session = prefs[ManagedKeys.session]
        require(session != null || fenceBinding == null)
        val cachedBinding = binding(ManagedKeys.snapshotNamespace, ManagedKeys.snapshotUid)
        val cached = cachedBinding?.let {
            ManagedCachedSnapshot(it, checkNotNull(prefs[ManagedKeys.snapshotSession]), checkNotNull(prefs[ManagedKeys.revision]),
                checkNotNull(prefs[ManagedKeys.fingerprint]), checkNotNull(prefs[ManagedKeys.payload]), checkNotNull(prefs[ManagedKeys.serverTime]),
                checkNotNull(prefs[ManagedKeys.receivedElapsed]), checkNotNull(prefs[ManagedKeys.boot]))
        }
        val value = ManagedCacheEnvelope(session?.let { ManagedCacheFence(it, fenceBinding) }, cached,
            binding(ManagedKeys.watermarkNamespace, ManagedKeys.watermarkUid), checkNotNull(prefs[ManagedKeys.watermark]),
            checkNotNull(prefs[ManagedKeys.excluded]).toSet())
        validateManagedTransition(ManagedCacheEnvelope(), value)
        ManagedCacheState.Ready(value)
    }
} catch (error: ManagedCacheException) {
    ManagedCacheState.Unavailable(error.failure)
} catch (_: RuntimeException) {
    ManagedCacheState.Unavailable(ManagedCacheFailure.CORRUPT)
}

private fun writeManagedCache(prefs: MutablePreferences, value: ManagedCacheEnvelope) {
    prefs.clear() // This is a dedicated managed-only DataStore, never flint_preferences.
    prefs[ManagedKeys.version] = 1
    value.fence?.let { fence ->
        prefs[ManagedKeys.session] = fence.sessionId
        fence.binding?.let { prefs[ManagedKeys.namespace] = it.namespace; prefs[ManagedKeys.uid] = it.userId }
    }
    value.watermarkBinding?.let { prefs[ManagedKeys.watermarkNamespace] = it.namespace; prefs[ManagedKeys.watermarkUid] = it.userId }
    prefs[ManagedKeys.watermark] = value.watermark
    prefs[ManagedKeys.excluded] = value.excludedMemberships.toSet()
    value.snapshot?.let {
        prefs[ManagedKeys.snapshotNamespace] = it.binding.namespace; prefs[ManagedKeys.snapshotUid] = it.binding.userId
        prefs[ManagedKeys.snapshotSession] = it.sessionId; prefs[ManagedKeys.revision] = it.revision
        prefs[ManagedKeys.fingerprint] = it.fingerprint; prefs[ManagedKeys.payload] = it.payload
        prefs[ManagedKeys.serverTime] = it.serverTimeEpochMs; prefs[ManagedKeys.receivedElapsed] = it.receivedElapsedMs; prefs[ManagedKeys.boot] = it.bootId
    }
}
