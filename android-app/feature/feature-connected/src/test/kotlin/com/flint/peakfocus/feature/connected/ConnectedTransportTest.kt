package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.datastore.InMemoryManagedCacheStore
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedTransportTest {
    @Test fun serverErrorAfterPossibleCommitAndNetworkLossAreAmbiguousWithoutRetry() = runTest {
        val session = ConnectedIdentitySession(FakeConnectedAuth(), InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        runCurrent()
        for (method in listOf("POST", "PUT", "DELETE")) {
            for (network in listOf(false, true)) {
                var calls = 0
                val transport = AuthenticatedConnectedTransport(session, "http://unused") { _, _, _, _, _ ->
                    calls++
                    if (network) throw IOException("Synthetic connection loss")
                    500 to "{}"
                }
                try { transport.request(session.lease(), method, "/v1/example", "{}"); fail("Expected ambiguous mutation") }
                catch (error: ManagedRequestFailure) { assertEquals(ManagedFailureKind.AMBIGUOUS_MUTATION, error.kind) }
                assertEquals(1, calls)
            }
        }
    }
    @Test fun definitiveForbiddenAndReadServerErrorAreNotMutationAmbiguity() = runTest {
        val session = ConnectedIdentitySession(FakeConnectedAuth(), InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        runCurrent()
        for ((method, status, kind) in listOf(Triple("POST", 403, ManagedFailureKind.FORBIDDEN), Triple("GET", 500, ManagedFailureKind.NETWORK))) {
            val transport = AuthenticatedConnectedTransport(session, "http://unused") { _, _, _, _, _ -> status to "{}" }
            try { transport.request(session.lease(), method, "/v1/example"); fail("Expected rejection") }
            catch (error: ManagedRequestFailure) { assertEquals(kind, error.kind) }
        }
    }
    @Test fun explicit401RefreshesExactlyOnceAndSecondRejectionClearsSession() = runTest {
        val auth = FakeConnectedAuth()
        val session = ConnectedIdentitySession(auth, InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        runCurrent()
        var calls = 0
        val transport = AuthenticatedConnectedTransport(session, "http://unused") { _, _, _, _, _ -> calls++; 401 to "{}" }
        try { transport.request(session.lease(), "POST", "/v1/example"); fail("Expected auth rejection") }
        catch (error: ManagedRequestFailure) { assertEquals(ManagedFailureKind.UNAUTHENTICATED, error.kind) }
        assertEquals(2, calls)
        assertEquals(2, auth.tokenCount)
        assertEquals(1, auth.signOutCount)
        assertNull(session.uid)
    }
    @Test fun late401FromPreviousSameUidSessionCannotRejectCurrentAccount() = runTest {
        val auth = FakeConnectedAuth()
        val session = ConnectedIdentitySession(auth, InMemoryManagedCacheStore(), "local", "demo-flint", backgroundScope)
        runCurrent()
        val response = CompletableDeferred<Pair<Int, String>>()
        val transport = AuthenticatedConnectedTransport(session, "http://unused") { _, _, _, _, _ -> response.await() }
        val request = async {
            try { transport.request(session.lease(), "GET", "/v1/example"); null }
            catch (error: ManagedRequestFailure) { error.kind }
        }
        runCurrent()
        session.authenticate("member@example.test", "synthetic", false)
        response.complete(401 to "{}")
        assertEquals(ManagedFailureKind.IDENTITY_CHANGED, request.await())
        assertEquals("member", session.uid)
        assertEquals(0, auth.signOutCount)
    }
}
