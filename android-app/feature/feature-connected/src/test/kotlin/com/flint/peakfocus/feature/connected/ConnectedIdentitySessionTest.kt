package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.datastore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedIdentitySessionTest {
    @Test fun suspendedStartupRestoreCannotRepublishIdentityAfterSignoutInvalidation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val memory = InMemoryManagedCacheStore(ManagedCacheEnvelope(fence = ManagedCacheFence("old", ManagedCacheBinding("local", "member"))))
        val store = object : ManagedCacheStore {
            override val state: Flow<ManagedCacheState> = flow { gate.await(); emitAll(memory.state) }
            override suspend fun update(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope) = memory.update(transform)
        }
        val auth = FakeConnectedAuth()
        val session = ConnectedIdentitySession(auth, store, "local", "demo-flint", backgroundScope)
        val publications = mutableListOf<String?>()
        session.observe { publications += session.uid }
        runCurrent() // Startup is waiting for its first disk read.
        val logout = async { session.signOut() }
        runCurrent()
        assertNull(session.uid)
        gate.complete(Unit)
        logout.await()
        runCurrent()
        assertTrue(publications.all { it == null })
        assertNull(auth.uid)
        assertNull(session.unavailable)
    }
    @Test fun explicitSameUidLoginRotatesGenerationAndDurableSessionFence() = runTest {
        val auth = FakeConnectedAuth()
        val store = InMemoryManagedCacheStore()
        val session = ConnectedIdentitySession(auth, store, "local", "demo-flint", backgroundScope)
        runCurrent()
        val first = session.lease()
        val identityBefore = session.managedIdentity.value
        val gate = CompletableDeferred<Unit>()
        auth.authentication = gate
        val login = async { session.authenticate("member@example.test", "synthetic", false) }
        runCurrent()
        assertNull(session.managedIdentity.value)
        assertFalse(session.isCurrent(first))
        gate.complete(Unit)
        login.await()
        val second = session.lease()
        assertEquals(first.binding, second.binding)
        assertNotEquals(first.sessionId, second.sessionId)
        assertTrue(second.generation > first.generation)
        assertNotEquals(identityBefore, session.managedIdentity.value)
        assertEquals(second.sessionId, store.state.filterIsInstance<ManagedCacheState.Ready>().first().value.fence!!.sessionId)
    }
    @Test fun signoutInvalidatesBeforeDiskSuspensionAndOld401CannotSignOutNewSession() = runTest {
        val auth = FakeConnectedAuth()
        val session = ConnectedIdentitySession(auth, InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        runCurrent()
        val previous = session.lease()
        session.signOut()
        assertNull(session.managedIdentity.value)
        assertEquals(1, auth.signOutCount)
        session.authenticate("member@example.test", "synthetic", false)
        session.rejectSession(previous)
        assertEquals("member", session.uid)
        assertEquals(1, auth.signOutCount)
    }
    @Test fun processRestoreReusesOnlyMatchingPersistentFence() = runTest {
        val binding = ManagedCacheBinding("local", "member")
        val store = InMemoryManagedCacheStore(ManagedCacheEnvelope(fence = ManagedCacheFence("saved-session", binding)))
        val restored = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
        runCurrent()
        assertEquals("saved-session", restored.lease().sessionId)
        val foreign = ConnectedIdentitySession(FakeConnectedAuth("foreign"), store, "local", "demo-flint", backgroundScope)
        runCurrent()
        assertNotEquals("saved-session", foreign.lease().sessionId)
        assertEquals("foreign", foreign.uid)
    }
    @Test fun corruptInitialCacheIsUnavailableWithoutUncaughtExceptionAndSignOutStillClearsProvider() = runTest {
        val broken = object : ManagedCacheStore {
            override val state = flowOf<ManagedCacheState>(ManagedCacheState.Unavailable(ManagedCacheFailure.CORRUPT))
            override suspend fun update(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope) = ManagedCacheCommit.Unavailable(ManagedCacheFailure.CORRUPT)
        }
        val auth = FakeConnectedAuth()
        val session = ConnectedIdentitySession(auth, broken, "local", "demo-flint", backgroundScope)
        runCurrent()
        assertNull(session.uid)
        assertEquals(ManagedFailureKind.STORAGE, session.unavailable!!.kind)
        try { session.lease(); fail("Unavailable cache accepted") } catch (error: ManagedRequestFailure) { assertEquals(ManagedFailureKind.STORAGE, error.kind) }
        try { session.signOut(); fail("Expected explicit disk failure") } catch (_: ManagedRequestFailure) { }
        assertNull(auth.uid)
        assertNull(session.managedIdentity.value)
    }
    @Test fun temporaryStorageFailureCanRecoverOnExplicitAuthentication() = runTest {
        val memory = InMemoryManagedCacheStore()
        var fail = true
        val store = object : ManagedCacheStore {
            override val state: Flow<ManagedCacheState> get() = if (fail) flowOf(ManagedCacheState.Unavailable(ManagedCacheFailure.IO)) else memory.state
            override suspend fun update(transform: (ManagedCacheEnvelope) -> ManagedCacheEnvelope): ManagedCacheCommit =
                if (fail) ManagedCacheCommit.Unavailable(ManagedCacheFailure.IO) else memory.update(transform)
        }
        val session = ConnectedIdentitySession(FakeConnectedAuth(), store, "local", "demo-flint", backgroundScope)
        runCurrent()
        assertNotNull(session.unavailable)
        fail = false
        session.authenticate("member@example.test", "synthetic", false)
        assertEquals("member", session.lease().binding.userId)
        assertNull(session.unavailable)
    }
}
