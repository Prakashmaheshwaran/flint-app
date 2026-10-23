package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.ManagedIdentity
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provider tokens never appear in UI models, cache records, logs, or exception messages. */
internal interface ConnectedAuthGateway {
    val uid: String?
    val email: String?
    fun observe(listener: () -> Unit): () -> Unit
    suspend fun authenticate(email: String, password: String, create: Boolean)
    suspend fun resetPassword(email: String)
    suspend fun token(forceRefresh: Boolean): String
    fun signOut()
}

internal data class ConnectedLease(val binding: ManagedCacheBinding, val sessionId: String, val generation: Long) {
    fun managed(projectId: String) = ManagedIdentity(projectId, binding.userId, generation)
}

/** Application-lived identity; explicit same-UID authentication rotates a durable fence. */
internal class ConnectedIdentitySession(
    private val auth: ConnectedAuthGateway,
    private val store: ManagedCacheStore,
    val namespace: String,
    val projectId: String,
    private val scope: CoroutineScope,
) {
    private val serial = Mutex()
    private val counter = AtomicLong(0)
    private val initialized = CompletableDeferred<Unit>()
    private val listeners = linkedSetOf<() -> Unit>()
    @Volatile private var current: ConnectedLease? = null
    @Volatile private var pendingGeneration: Long? = null
    @Volatile var unavailable: ManagedRequestFailure? = null
        private set
    private val identity = MutableStateFlow<ManagedIdentity?>(null)
    val managedIdentity: StateFlow<ManagedIdentity?> = identity.asStateFlow()
    val uid: String? get() = current?.binding?.userId
    val email: String? get() = if (uid == auth.uid) auth.email else null
    val generation: Long get() = counter.get()

    init {
        scope.launch {
            try {
                serial.withLock {
                    if (counter.get() == 0L) restore(first = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (pendingGeneration == null && counter.get() == 0L) {
                    unavailable = storageFailure()
                    publish(null)
                }
            } finally {
                initialized.complete(Unit)
                auth.observe { scope.launch { reconcileProvider() } }
            }
        }
    }

    fun observe(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }
    private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }
    private fun invalidate(): Long {
        val next = counter.incrementAndGet()
        pendingGeneration = next
        current = null
        identity.value = null
        notifyChanged()
        return next
    }
    private fun publish(lease: ConnectedLease?) {
        current = lease
        identity.value = lease?.managed(projectId)
        notifyChanged()
    }

    suspend fun lease(): ConnectedLease {
        initialized.await()
        unavailable?.let { throw it }
        return current?.also(::requireCurrent) ?: throw ManagedRequestFailure(ManagedFailureKind.UNAUTHENTICATED, "Sign in to continue.")
    }
    fun isCurrent(lease: ConnectedLease): Boolean = current == lease && counter.get() == lease.generation && auth.uid == lease.binding.userId
    fun requireCurrent(lease: ConnectedLease) {
        if (!isCurrent(lease)) throw ManagedRequestFailure(ManagedFailureKind.IDENTITY_CHANGED, "Your account session changed. Refresh to continue.")
    }
    suspend fun token(lease: ConnectedLease, refresh: Boolean): String {
        requireCurrent(lease)
        return auth.token(refresh).also { requireCurrent(lease) }
    }
    suspend fun resetPassword(email: String) = auth.resetPassword(email)

    suspend fun authenticate(email: String, password: String, create: Boolean) {
        val ticket = invalidate() // Before the first suspension, including same-UID login.
        initialized.await()
        serial.withLock {
            if (counter.get() != ticket) throw changed()
            val sessionId = UUID.randomUUID().toString()
            try {
                cacheWrite { it.copy(fence = ManagedCacheFence(sessionId, null), snapshot = null, excludedMemberships = emptySet()) }
                auth.authenticate(email, password, create)
                if (counter.get() != ticket) throw changed()
                val binding = ManagedCacheBinding(namespace, auth.uid ?: throw changed())
                cacheWrite { cache ->
                    require(cache.fence?.sessionId == sessionId)
                    cache.copy(fence = ManagedCacheFence(sessionId, binding))
                }
                if (counter.get() != ticket) throw changed()
                pendingGeneration = null
                unavailable = null
                publish(ConnectedLease(binding, sessionId, ticket))
            } catch (error: Exception) {
                if (counter.get() == ticket) {
                    pendingGeneration = null
                    // A failed sign-in can leave the previous provider user present. Restore
                    // that identity under the NEW fence, never resurrect its prior cache.
                    try { restore(first = false) } catch (recovery: Exception) {
                        if (recovery is CancellationException) throw recovery
                        if (counter.get() == ticket) {
                            unavailable = storageFailure()
                            publish(null)
                        }
                    }
                }
                throw error
            }
        }
    }

    suspend fun signOut() {
        val ticket = invalidate() // Hot authority and UI identity clear synchronously.
        initialized.await()
        serial.withLock {
            if (counter.get() != ticket) return@withLock
            try {
                cacheWrite { it.copy(fence = ManagedCacheFence(UUID.randomUUID().toString(), null), snapshot = null, excludedMemberships = emptySet()) }
                unavailable = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                unavailable = storageFailure()
                throw error
            } finally {
                auth.signOut()
                if (counter.get() == ticket) { pendingGeneration = null; publish(null) }
            }
        }
    }

    suspend fun rejectSession(lease: ConnectedLease) { if (isCurrent(lease)) signOut() }

    private suspend fun reconcileProvider() {
        initialized.await()
        if (pendingGeneration != null || current?.binding?.userId == auth.uid) return
        val ticket = invalidate()
        serial.withLock {
            if (counter.get() == ticket) {
                pendingGeneration = null
                try { restore(first = false) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (counter.get() == ticket) {
                        unavailable = storageFailure()
                        publish(null)
                    }
                }
            }
        }
    }

    private suspend fun restore(first: Boolean) {
        val ticket = counter.get()
        val user = auth.uid
        val cache = cacheRead()
        if (counter.get() != ticket || auth.uid != user) throw changed()
        unavailable = null
        if (user == null) {
            if (cache.fence?.binding != null || cache.snapshot != null) cacheWrite {
                if (counter.get() != ticket || auth.uid != user) throw changed()
                it.copy(fence = ManagedCacheFence(UUID.randomUUID().toString(), null), snapshot = null, excludedMemberships = emptySet())
            }
            if (counter.get() != ticket || auth.uid != user) throw changed()
            publish(null)
            return
        }
        val binding = ManagedCacheBinding(namespace, user)
        val existing = cache.fence?.takeIf { first && it.binding == binding }
        val fence = existing ?: ManagedCacheFence(UUID.randomUUID().toString(), binding)
        if (existing == null) cacheWrite {
            if (counter.get() != ticket || auth.uid != user) throw changed()
            it.copy(fence = fence, snapshot = null, excludedMemberships = emptySet())
        }
        if (counter.get() != ticket || auth.uid != user) throw changed()
        val generation = if (counter.get() == 0L) counter.incrementAndGet() else counter.get()
        publish(ConnectedLease(binding, fence.sessionId, generation))
    }

    internal suspend fun cacheRead(): ManagedCacheEnvelope = when (val result = store.state.first { it !is ManagedCacheState.Loading }) {
        is ManagedCacheState.Ready -> result.value
        else -> throw storageFailure()
    }
    internal suspend fun cacheWrite(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope): ManagedCacheEnvelope = when (val result = store.update(transform)) {
        is ManagedCacheCommit.Committed -> result.value
        is ManagedCacheCommit.Unavailable -> throw storageFailure()
    }
    private fun changed() = ManagedRequestFailure(ManagedFailureKind.IDENTITY_CHANGED, "Your account session changed. Refresh to continue.")
    private fun storageFailure() = ManagedRequestFailure(ManagedFailureKind.STORAGE, "The local managed cache is unavailable. No new policy state was accepted.")
}
