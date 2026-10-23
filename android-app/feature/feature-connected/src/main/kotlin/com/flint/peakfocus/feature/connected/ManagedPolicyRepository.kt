package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.google.gson.JsonObject
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ManagedDeviceClock {
    val bootId: String
    fun elapsedRealtime(): Long
}

/** Account-bound inspection cache. This class never writes the personal rule store or active-rule bridge. */
internal class ManagedPolicyRepository(
    private val identity: ConnectedIdentitySession,
    private val transport: ConnectedHttpTransport,
    private val clock: ManagedDeviceClock,
    private val scope: CoroutineScope,
) {
    private val serial = Mutex()
    private val mutableState = MutableStateFlow<ManagedAuthorityState>(ManagedAuthorityState.Loading(null))
    val state: StateFlow<ManagedAuthorityState> = mutableState.asStateFlow()
    private var anchor: ManagedCachedSnapshot? = null

    init {
        identity.observe {
            // This callback runs synchronously with generation invalidation.
            anchor = null
            val current = identity.managedIdentity.value
            mutableState.value = identity.unavailable?.let { ManagedAuthorityState.Unavailable(current, it.message.orEmpty()) }
                ?: if (current == null) ManagedAuthorityState.SignedOut else ManagedAuthorityState.Loading(current)
            if (current != null) scope.launch { hydrate() }
        }
        scope.launch { hydrate() }
    }

    private suspend fun hydrate() = serial.withLock {
        val lease = try { identity.lease() } catch (error: ManagedRequestFailure) {
            if (identity.managedIdentity.value == null && error.kind != ManagedFailureKind.UNAUTHENTICATED) {
                mutableState.value = ManagedAuthorityState.Unavailable(null, error.message.orEmpty())
            } else if (identity.managedIdentity.value == null) {
                mutableState.value = ManagedAuthorityState.SignedOut
            }
            return@withLock
        }
        // A hydration queued before a successful fetch cannot downgrade that fetch.
        if (snapshotOf(mutableState.value)?.identity == lease.managed(identity.projectId)) return@withLock
        try {
            val cache = identity.cacheRead()
            identity.requireCurrent(lease)
            val row = cache.snapshot?.takeIf { it.binding == lease.binding && it.sessionId == lease.sessionId }
            if (row == null) {
                mutableState.value = ManagedAuthorityState.Unavailable(lease.managed(identity.projectId), "No managed policies have been fetched for this session. Refresh to load them.")
            } else {
                val snapshot = ManagedWireCodec.snapshot(row.payload, lease.managed(identity.projectId))
                requireProtocol(snapshot.revision == row.revision && snapshot.serverTimeEpochMs == row.serverTimeEpochMs && ManagedWireCodec.fingerprint(snapshot) == row.fingerprint)
                anchor = row
                mutableState.value = ManagedAuthorityState.Stale(excluding(snapshot, cache.excludedMemberships), managedClock(row), "Saved policies. Refresh to check the service for changes.")
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (identity.isCurrent(lease)) retainFailure(lease, safeMessage(error))
        }
    }

    suspend fun refresh(): ManagedRefreshResult = serial.withLock {
        val lease = try { identity.lease() } catch (error: ManagedRequestFailure) {
            return@withLock if (error.kind == ManagedFailureKind.UNAUTHENTICATED) ManagedRefreshResult.SignedOut
                else ManagedRefreshResult.Retained(mutableState.value)
        }
        refreshLocked(lease)
    }

    private suspend fun refreshLocked(lease: ConnectedLease): ManagedRefreshResult = try {
        val payload = transport.request(lease, "GET", "/v1/me/managed-state", responseLimit = ManagedWireCodec.SNAPSHOT_LIMIT)
        identity.requireCurrent(lease)
        val snapshot = ManagedWireCodec.snapshot(payload, lease.managed(identity.projectId))
        val fingerprint = ManagedWireCodec.fingerprint(snapshot)
        val received = clock.elapsedRealtime()
        requireProtocol(received >= 0)
        val row = ManagedCachedSnapshot(lease.binding, lease.sessionId, snapshot.revision, fingerprint, payload,
            snapshot.serverTimeEpochMs, received, clock.bootId)
        identity.cacheWrite { cache ->
            identity.requireCurrent(lease)
            requireProtocol(cache.fence == ManagedCacheFence(lease.sessionId, lease.binding))
            if (cache.watermarkBinding == lease.binding) {
                requireProtocol(snapshot.revision >= cache.watermark)
                cache.snapshot?.let { previous ->
                    if (snapshot.revision == previous.revision) requireProtocol(fingerprint == previous.fingerprint)
                    if (snapshot.revision > previous.revision) {
                        val old = ManagedWireCodec.snapshot(previous.payload, snapshot.identity)
                        val known = (old.rules.map { it.id to it.revision } + old.tombstones.map { it.id to it.revision }).toMap()
                        requireProtocol(snapshot.rules.all { rule -> rule.revision >= (known[rule.id] ?: 0) })
                        requireProtocol(snapshot.tombstones.all { tomb -> tomb.revision >= (known[tomb.id] ?: 0) })
                        requireProtocol(snapshot.rules.none { rule -> old.tombstones.any { it.id == rule.id } })
                    }
                }
            }
            requireProtocol(snapshot.memberships.none { it.membershipId in cache.excludedMemberships })
            cache.copy(snapshot = row, watermarkBinding = lease.binding, watermark = snapshot.revision, excludedMemberships = emptySet())
        }
        identity.requireCurrent(lease)
        anchor = row
        val ready = ManagedAuthorityState.Ready(snapshot, managedClock(row))
        mutableState.value = ready
        ManagedRefreshResult.Accepted(ready)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (identity.isCurrent(lease)) retainFailure(lease, safeMessage(error))
        ManagedRefreshResult.Retained(mutableState.value)
    }

    suspend fun page(groupId: String): ManagedRulePage = ownerRead { lease ->
        ManagedWireCodec.page(transport.request(lease, "GET", path(groupId)), lease.managed(identity.projectId), groupId)
    }
    suspend fun rule(groupId: String, ruleId: String): ManagedRuleResult = ownerRead { lease ->
        ManagedWireCodec.result(transport.request(lease, "GET", path(groupId, ruleId)), lease.managed(identity.projectId), groupId)
            .also { requireProtocol(it.rule.id == ruleId) }
    }
    suspend fun create(groupId: String, input: ManagedRuleInput): ManagedRuleResult = ownerMutation { lease ->
        val body = JsonObject().apply { add("rule", ManagedWireCodec.input(input)) }.toString()
        val raw = transport.request(lease, "POST", path(groupId), body)
        mutationDecode { ManagedWireCodec.result(raw, lease.managed(identity.projectId), groupId) }
    }
    suspend fun update(groupId: String, ruleId: String, expectedRevision: Long, input: ManagedRuleInput): ManagedRuleResult = ownerMutation { lease ->
        validateRevision(expectedRevision)
        val body = JsonObject().apply { addProperty("expectedRevision", expectedRevision); add("rule", ManagedWireCodec.input(input)) }.toString()
        val raw = transport.request(lease, "PUT", path(groupId, ruleId), body)
        mutationDecode { ManagedWireCodec.result(raw, lease.managed(identity.projectId), groupId).also { requireProtocol(it.rule.id == ruleId) } }
    }
    suspend fun delete(groupId: String, ruleId: String, expectedRevision: Long): ManagedDeleteResult = ownerMutation { lease ->
        validateRevision(expectedRevision)
        val raw = transport.request(lease, "DELETE", path(groupId, ruleId), JsonObject().apply { addProperty("expectedRevision", expectedRevision) }.toString())
        mutationDecode { ManagedWireCodec.deletion(raw, lease.managed(identity.projectId), groupId).also { requireProtocol(it.tombstone.id == ruleId) } }
    }

    /** Join/create are serialized against refresh; only a subsequent complete snapshot adds authority. */
    suspend fun <T> membershipChange(block: suspend (ConnectedLease) -> T): T = serial.withLock {
        val lease = identity.lease()
        val result = block(lease)
        identity.requireCurrent(lease)
        refreshLocked(lease)
        result
    }

    suspend fun leave(groupId: String, block: suspend (ConnectedLease) -> Unit) = serial.withLock {
        val lease = identity.lease()
        block(lease)
        identity.requireCurrent(lease)
        // A successful leave must suppress this exact membership even while offline,
        // and a pre-leave GET cannot run concurrently with this serialized section.
        val current = snapshotOf(mutableState.value)
        val excluded = current?.memberships?.filter { it.groupId == groupId }?.map { it.membershipId }.orEmpty()
        if (current != null) mutableState.value = ManagedAuthorityState.Stale(
            current.copy(memberships = current.memberships.filterNot { it.groupId == groupId }, rules = current.rules.filterNot { it.targetGroupId == groupId },
                tombstones = current.tombstones.filterNot { it.targetGroupId == groupId }), currentClock(), "Membership changed. Refresh to confirm the current policies.")
        try {
            identity.cacheWrite { cache ->
                identity.requireCurrent(lease)
                requireProtocol(cache.fence == ManagedCacheFence(lease.sessionId, lease.binding))
                val savedIds = cache.snapshot?.let { ManagedWireCodec.snapshot(it.payload, lease.managed(identity.projectId)) }
                    ?.memberships?.filter { it.groupId == groupId }?.map { it.membershipId }.orEmpty()
                cache.copy(excludedMemberships = cache.excludedMemberships + excluded + savedIds)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // If durable invalidation failed, invalidate the account session too: an
            // old persisted snapshot must not silently become authoritative on reopen.
            identity.rejectSession(lease)
            throw error
        }
        refreshLocked(lease)
        Unit
    }

    private suspend fun <T> ownerRead(block: suspend (ConnectedLease) -> T): T {
        val lease = identity.lease()
        return block(lease).also { identity.requireCurrent(lease) }
    }
    private suspend fun <T> ownerMutation(block: suspend (ConnectedLease) -> T): T = ownerRead(block)
    private fun managedClock(row: ManagedCachedSnapshot): ManagedClock {
        val elapsed = clock.elapsedRealtime()
        return if (row.bootId != clock.bootId || elapsed < row.receivedElapsedMs)
            ManagedClock.Unconfirmed("The device restarted or its monotonic clock changed. Refresh to confirm service time.")
        else {
            val delta = elapsed - row.receivedElapsedMs
            if (delta > 253_402_300_799_999L - row.serverTimeEpochMs) ManagedClock.Unconfirmed("The saved service time is invalid.")
            else ManagedClock.Confirmed(row.serverTimeEpochMs + delta)
        }
    }
    private fun currentClock(): ManagedClock = anchor?.let(::managedClock) ?: ManagedClock.Unconfirmed("Service time has not been confirmed.")
    private fun retainFailure(lease: ConnectedLease, message: String) {
        val previous = snapshotOf(mutableState.value)?.takeIf { it.identity == lease.managed(identity.projectId) }
        mutableState.value = previous?.let { ManagedAuthorityState.Stale(it, currentClock(), message) }
            ?: ManagedAuthorityState.Unavailable(lease.managed(identity.projectId), message)
    }
    private fun excluding(snapshot: ManagedSnapshot, memberships: Set<String>): ManagedSnapshot {
        val groups = snapshot.memberships.filter { it.membershipId in memberships }.map { it.groupId }.toSet()
        return snapshot.copy(memberships = snapshot.memberships.filterNot { it.groupId in groups },
            rules = snapshot.rules.filterNot { it.targetGroupId in groups }, tombstones = snapshot.tombstones.filterNot { it.targetGroupId in groups })
    }
    private fun path(groupId: String, ruleId: String? = null): String {
        val uuid = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        if (!uuid.matches(groupId) || ruleId != null && !uuid.matches(ruleId)) throw ManagedRequestFailure(ManagedFailureKind.VALIDATION, "The policy identifier is invalid.")
        return "/v1/groups/${URLEncoder.encode(groupId, "UTF-8")}/managed-rules" + (ruleId?.let { "/$it" } ?: "")
    }
    private fun validateRevision(revision: Long) { if (revision !in 1..9_007_199_254_740_991L) throw ManagedRequestFailure(ManagedFailureKind.VALIDATION, "Reload this policy before saving.") }
    private fun requireProtocol(condition: Boolean) { if (!condition) throw ManagedRequestFailure(ManagedFailureKind.PROTOCOL, "The managed policy response conflicts with accepted state. Refresh to try again.") }
    private fun <T> mutationDecode(block: () -> T): T = try { block() } catch (_: ManagedRequestFailure) {
        throw ManagedRequestFailure(ManagedFailureKind.AMBIGUOUS_MUTATION, "The service may have saved this change. Reload and review the policies before submitting again.")
    }
    private fun safeMessage(error: Exception): String = (error as? ConnectedFailure)?.message ?: "Managed policies could not be loaded. The last accepted state is retained."
    private fun snapshotOf(state: ManagedAuthorityState): ManagedSnapshot? = when (state) {
        is ManagedAuthorityState.Ready -> state.snapshot
        is ManagedAuthorityState.Stale -> state.snapshot
        is ManagedAuthorityState.Loading -> state.cached
        else -> null
    }
}
