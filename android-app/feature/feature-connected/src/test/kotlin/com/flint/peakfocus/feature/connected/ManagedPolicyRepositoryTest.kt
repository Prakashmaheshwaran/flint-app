package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedPolicyRepositoryTest {
    private class Transport : ConnectedHttpTransport {
        var payload = ManagedFixture.snapshot().toString()
        var response: (suspend () -> String)? = null
        var count = 0
        override suspend fun request(lease: ConnectedLease, method: String, path: String, body: String?, responseLimit: Int): String {
            count++
            return response?.invoke() ?: payload
        }
    }
    @Test fun completeSnapshotsReplaceOmissionsRejectRewindsAndEqualRevisionContentChanges() = runTest {
        val session = ConnectedIdentitySession(FakeConnectedAuth(), InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        val transport = Transport()
        val repo = ManagedPolicyRepository(session, transport, FakeManagedClock(), backgroundScope)
        runCurrent()
        assertTrue(repo.refresh() is ManagedRefreshResult.Accepted)
        assertEquals(1, (repo.state.value as ManagedAuthorityState.Ready).snapshot.rules.size)
        transport.payload = ManagedFixture.snapshot(3, includeRule = false).toString()
        repo.refresh()
        assertTrue((repo.state.value as ManagedAuthorityState.Ready).snapshot.rules.isEmpty())
        transport.payload = ManagedFixture.snapshot(2).toString()
        assertTrue(repo.refresh() is ManagedRefreshResult.Retained)
        assertEquals(3L, (repo.state.value as ManagedAuthorityState.Stale).snapshot.revision)
        transport.payload = ManagedFixture.snapshot(3).toString()
        assertTrue(repo.refresh() is ManagedRefreshResult.Retained)
        assertTrue((repo.state.value as ManagedAuthorityState.Stale).snapshot.rules.isEmpty())
    }
    @Test fun equalRevisionSameContentRefreshesServerTimeAnchor() = runTest {
        val session = ConnectedIdentitySession(FakeConnectedAuth(), InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        val transport = Transport()
        val clock = FakeManagedClock()
        val repo = ManagedPolicyRepository(session, transport, clock, backgroundScope)
        runCurrent(); repo.refresh()
        val first = (repo.state.value as ManagedAuthorityState.Ready).clock as ManagedClock.Confirmed
        clock.elapsed += 1000
        transport.payload = ManagedFixture.snapshot().apply { addProperty("serverTime", "2026-10-11T01:00:01.000Z") }.toString()
        assertTrue(repo.refresh() is ManagedRefreshResult.Accepted)
        assertEquals(first.nowEpochMs + 1000, ((repo.state.value as ManagedAuthorityState.Ready).clock as ManagedClock.Confirmed).nowEpochMs)
    }
    @Test fun networkAndWriteFailureRetainLastGoodWithoutAcceptingNewState() = runTest {
        val memory = InMemoryManagedCacheStore()
        var fail = false
        val store = object : ManagedCacheStore {
            override val state get() = memory.state
            override suspend fun update(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope) = if (fail) ManagedCacheCommit.Unavailable(ManagedCacheFailure.IO) else memory.update(transform)
        }
        val session = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
        val transport = Transport()
        val repo = ManagedPolicyRepository(session, transport, FakeManagedClock(), backgroundScope)
        runCurrent(); repo.refresh()
        fail = true
        transport.payload = ManagedFixture.snapshot(3, false).toString()
        repo.refresh()
        assertEquals(2L, (repo.state.value as ManagedAuthorityState.Stale).snapshot.revision)
        assertEquals(2L, memory.state.filterIsInstance<ManagedCacheState.Ready>().first().value.watermark)
        transport.response = { throw ManagedRequestFailure(ManagedFailureKind.NETWORK, "Offline") }
        repo.refresh()
        assertEquals(1, (repo.state.value as ManagedAuthorityState.Stale).snapshot.rules.size)
    }
    @Test fun sameUidReauthenticationDropsHotAndDurableCacheAndDiscardsInflightSnapshot() = runTest {
        val store = InMemoryManagedCacheStore()
        val session = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
        val transport = Transport()
        val repo = ManagedPolicyRepository(session, transport, FakeManagedClock(), backgroundScope)
        runCurrent(); repo.refresh()
        val response = CompletableDeferred<String>()
        transport.response = { response.await() }
        val request = async { repo.refresh() }
        runCurrent()
        session.authenticate("member@example.test", "synthetic", false)
        assertFalse(repo.state.value is ManagedAuthorityState.Ready)
        assertNull(store.state.filterIsInstance<ManagedCacheState.Ready>().first().value.snapshot)
        response.complete(ManagedFixture.snapshot(3).toString())
        assertTrue(request.await() is ManagedRefreshResult.Retained)
        runCurrent()
        assertTrue(repo.state.value is ManagedAuthorityState.Unavailable)
    }
    @Test fun reopenSameBootUsesMonotonicTimeButRebootOrElapsedRegressionIsUnconfirmed() = runTest {
        val store = InMemoryManagedCacheStore()
        val firstSession = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
        val clock = FakeManagedClock()
        val first = ManagedPolicyRepository(firstSession, Transport(), clock, backgroundScope)
        runCurrent(); first.refresh()
        for ((boot, elapsed, confirmed) in listOf(Triple("boot-1", 2000L, true), Triple("boot-2", 2000L, false), Triple("boot-1", 999L, false))) {
            val restoredSession = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
            val restored = ManagedPolicyRepository(restoredSession, Transport(), FakeManagedClock(boot, elapsed), backgroundScope)
            runCurrent()
            val state = restored.state.value as ManagedAuthorityState.Stale
            assertEquals(confirmed, state.clock is ManagedClock.Confirmed)
            assertEquals(1, state.snapshot.rules.size)
        }
    }
    @Test fun leaveExcludesExactMembershipDurablyAndCannotBeResurrectedByStaleResponse() = runTest {
        val store = InMemoryManagedCacheStore()
        val session = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
        val transport = Transport()
        val repo = ManagedPolicyRepository(session, transport, FakeManagedClock(), backgroundScope)
        runCurrent(); repo.refresh()
        repo.leave(ManagedFixture.group) { }
        assertTrue((repo.state.value as ManagedAuthorityState.Stale).snapshot.rules.isEmpty())
        assertEquals(setOf(ManagedFixture.member), store.state.filterIsInstance<ManagedCacheState.Ready>().first().value.excludedMemberships)
        val restoredSession = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
        val restored = ManagedPolicyRepository(restoredSession, Transport(), FakeManagedClock(), backgroundScope)
        runCurrent()
        assertTrue((restored.state.value as ManagedAuthorityState.Stale).snapshot.rules.isEmpty())
        transport.payload = ManagedFixture.snapshot(3, false, false).toString()
        assertTrue(repo.refresh() is ManagedRefreshResult.Accepted)
        assertTrue(store.state.filterIsInstance<ManagedCacheState.Ready>().first().value.excludedMemberships.isEmpty())
    }
    @Test fun malformedSuccessfulMutationBecomesAmbiguousAndNeverRetries() = runTest {
        val session = ConnectedIdentitySession(FakeConnectedAuth(), InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        val transport = Transport().apply { payload = "{}" }
        val repo = ManagedPolicyRepository(session, transport, FakeManagedClock(), backgroundScope)
        runCurrent()
        try { repo.create(ManagedFixture.group, ManagedFixture.input); fail("Expected ambiguity") }
        catch (error: ManagedRequestFailure) { assertEquals(ManagedFailureKind.AMBIGUOUS_MUTATION, error.kind) }
        assertEquals(1, transport.count)
        assertFalse(repo.state.value is ManagedAuthorityState.Ready)
    }
    @Test fun ownerListingNeverPopulatesMemberAuthority() = runTest {
        val session = ConnectedIdentitySession(FakeConnectedAuth(), InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        val transport = Transport().apply { payload = "{\"revision\":2,\"rules\":[${ManagedFixture.rule()}],\"tombstones\":[]}" }
        val repo = ManagedPolicyRepository(session, transport, FakeManagedClock(), backgroundScope)
        runCurrent()
        assertEquals(1, repo.page(ManagedFixture.group).rules.size)
        assertTrue(repo.state.value is ManagedAuthorityState.Unavailable)
    }
}
