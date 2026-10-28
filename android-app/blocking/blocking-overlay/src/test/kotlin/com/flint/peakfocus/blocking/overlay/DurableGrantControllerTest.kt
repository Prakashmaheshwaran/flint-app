package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Deterministic JVM fault tests; the backing store survives controller/process-state recreation. */
class DurableGrantControllerTest {
    private var now = 1_800_000_000_000L
    private val scopes = mutableListOf<CoroutineScope>()
    private val pkg = "com.app"
    @Before fun before() { BlockExemptions.clearAll() }
    @After fun after() { scopes.forEach { it.cancel() }; BlockExemptions.clearAll() }

    private fun controller(store: FocusStateStore, timeoutMs: Long = DurableGrantController.COMMIT_TIMEOUT_MS): DurableGrantController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return DurableGrantController(store, scope, { now }, { 0L }, timeoutMs)
    }
    private fun request(
        id: GrantRequestId = GrantRequestId.issue(now), name: String = pkg, level: BreakLevel = BreakLevel.EASY,
        emergency: Boolean = false, spendOpen: Boolean = false, scope: GrantScope = GrantScope.Packages(setOf(name)),
        duration: Long = 300_000L,
    ) = GrantRequest(id, name, "cause", scope, duration, level, emergency, spendOpen)
    private suspend fun snapshot(store: FocusStateStore) = store.state.filterIsInstance<StorageState.Ready<FocusSnapshot>>().first().value

    private class FaultStore(
        val backing: InMemoryFocusStateStore = InMemoryFocusStateStore(),
        read: Flow<StorageState<FocusSnapshot>>? = null,
    ) : FocusStateStore by backing {
        override val state = read ?: backing.state
        var failure: StorageFailure? = null
        var beforeCommit: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        var loseAcknowledgement = false
        var writes = 0
        override suspend fun <R> transact(transform: (FocusSnapshot) -> FocusMutation<R>): FocusCommit<R> {
            writes++
            failure?.let { return FocusCommit.Unavailable(it) }
            val gate = release
            val result = if (gate != null) {
                val current = backing.state.filterIsInstance<StorageState.Ready<FocusSnapshot>>().first().value
                val proposed = transform(current)
                beforeCommit?.complete(Unit)
                gate.await() // cancellation here cannot publish or consume the proposed state
                backing.transact { check(it == current); proposed }
            } else backing.transact(transform)
            if (loseAcknowledgement) throw CancellationException("process died after durable commit")
            return result
        }
    }

    @Test fun loadingAndUnavailableReadsNeverUseDefaultCreditsOrStartWrites() = runBlocking {
        for (state in listOf<StorageState<FocusSnapshot>>(StorageState.Loading) + StorageFailure.entries.map { StorageState.Unavailable(it) }) {
            val store = FaultStore(read = flowOf(state))
            val grants = controller(store)
            assertTrue(grants.request(request(emergency = true), true) is GrantResult.Unavailable)
            assertTrue(grants.recordOpen(GrantRequestId.issue(now), pkg, OpenLimit(pkg, 1)) is GrantResult.Unavailable)
            assertEquals(0, store.writes)
            assertFalse(BlockExemptions.isExempt(pkg, now))
        }
    }

    @Test fun unavailableRulesOrLimitsRefuseAGrantEvenWhenFocusIsReady() = runBlocking {
        val store = FaultStore()
        assertTrue(controller(store).request(request(), inputsReady = false) is GrantResult.Unavailable)
        assertEquals(0, store.writes)
    }

    @Test fun noExemptionOrCreditChangesBeforeTheDurableWriteReturns() = runBlocking {
        val store = FaultStore().apply { beforeCommit = CompletableDeferred(); release = CompletableDeferred() }
        val grants = controller(store)
        val pending = async { grants.request(request(spendOpen = true), true) }
        store.beforeCommit!!.await()
        assertFalse(pending.isCompleted)
        assertFalse(BlockExemptions.isExempt(pkg, now))
        assertEquals(0, snapshot(store.backing).breakSession.breaksTaken)
        assertEquals(0, snapshot(store.backing).openCounts.opensFor(pkg))
        store.release!!.complete(Unit)
        assertTrue(pending.await() is GrantResult.Granted)
        val saved = snapshot(store.backing)
        assertEquals(1, saved.breakSession.breaksTaken)
        assertEquals(1, saved.openCounts.opensFor(pkg))
        assertEquals(now + 300_000, saved.exemptions.values.single().untilEpochMs)
        assertTrue(BlockExemptions.isExempt(pkg, now))
    }

    @Test fun failedWritesSpendNothingAndTheSameRequestCanBeRetried() = runBlocking {
        for (reason in StorageFailure.entries) {
            BlockExemptions.clearAll()
            val store = FaultStore().apply { failure = reason }
            val grants = controller(store)
            val command = request(emergency = true)
            assertEquals(GrantResult.Unavailable(reason), grants.request(command, true))
            assertNull(snapshot(store.backing).emergencyPass.usedAtEpochMs)
            assertTrue(snapshot(store.backing).exemptions.isEmpty())
            assertFalse(BlockExemptions.isExempt(pkg, now, BreakLevel.HARDCORE))
            store.failure = null
            assertTrue(grants.request(command, true) is GrantResult.Granted)
            assertEquals(now, snapshot(store.backing).emergencyPass.usedAtEpochMs)
        }
    }

    @Test fun cancellationMidCommitPropagatesWithoutPartialConsumption() = runBlocking {
        val store = FaultStore().apply { beforeCommit = CompletableDeferred(); release = CompletableDeferred() }
        val grants = controller(store)
        val pending = async { grants.request(request(spendOpen = true), true) }
        store.beforeCommit!!.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(snapshot(store.backing).exemptions.isEmpty())
        assertEquals(0, snapshot(store.backing).breakSession.breaksTaken)
        assertEquals(0, snapshot(store.backing).openCounts.opensFor(pkg))
        assertFalse(BlockExemptions.isExempt(pkg, now))
    }

    @Test fun aTimedOutAcknowledgementIsUnconfirmedAndItsIdentityCanBeRetriedOnce() = runBlocking {
        val store = FaultStore().apply { release = CompletableDeferred() }
        val command = request(spendOpen = true)
        assertEquals(GrantResult.Unconfirmed, controller(store, timeoutMs = 10).request(command, true))
        assertEquals(0, snapshot(store.backing).breakSession.breaksTaken)
        assertTrue(snapshot(store.backing).exemptions.isEmpty())
        val resumed = controller(store.backing)
        assertTrue(resumed.request(command, true) is GrantResult.Granted)
        assertFalse((resumed.request(command, true) as GrantResult.Granted).newlyAccepted)
        assertEquals(1, snapshot(store.backing).breakSession.breaksTaken)
        assertEquals(1, snapshot(store.backing).openCounts.opensFor(pkg))
    }

    @Test fun lostAcknowledgementRestoresTheRemainingPassOnANewControllerWithoutRespending() = runBlocking {
        val store = FaultStore().apply { loseAcknowledgement = true }
        val command = request(emergency = true, level = BreakLevel.HARDCORE)
        try { controller(store).request(command, true); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        val spent = snapshot(store.backing)
        scopes.forEach { it.cancel() }
        BlockExemptions.clearAll()
        now += 10_000
        val restored = controller(store.backing)
        assertTrue(BlockExemptions.isExempt(pkg, now, BreakLevel.HARDCORE))
        assertEquals(command.id.issuedAtEpochMs + 300_000, BlockExemptions.exemptUntil(pkg, now))
        assertFalse((restored.request(command, true) as GrantResult.Granted).newlyAccepted)
        assertEquals(spent, snapshot(store.backing))
        now = command.id.issuedAtEpochMs + 300_000
        assertFalse(BlockExemptions.isExempt(pkg, now, BreakLevel.HARDCORE))
    }

    @Test fun twoControllersAndConcurrentSurfaceCallbacksConsumeExactlyOneBreakAndExtraOpen() = runBlocking {
        val backing = InMemoryFocusStateStore()
        val a = controller(backing)
        val b = controller(backing)
        val commands = List(64) { request(spendOpen = true) }
        val outcomes = commands.mapIndexed { index, command -> async(Dispatchers.Default) {
            (if (index % 2 == 0) a else b).request(command, true)
        } }.awaitAll().map { it as GrantResult.Granted }
        assertEquals(1, outcomes.count { it.newlyAccepted })
        val saved = snapshot(backing)
        assertEquals(1, saved.breakSession.breaksTaken)
        assertEquals(1, saved.openCounts.opensFor(pkg))
        assertEquals(1, saved.exemptions.size)
    }

    @Test fun concurrentPassCallbacksConsumeOneWeeklyPassAndDoNotExpandItsPackageScope() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        val outcomes = List(32) { async(Dispatchers.Default) { grants.request(request(emergency = true), true) } }.awaitAll()
        assertEquals(1, outcomes.count { (it as GrantResult.Granted).newlyAccepted })
        assertEquals(GrantResult.Denied, grants.request(request(name = "com.other", emergency = true), true))
        assertFalse(BlockExemptions.isExempt("com.other", now, BreakLevel.HARDCORE))
        assertEquals(1, snapshot(store).exemptions.size)
    }

    @Test fun harderWaitAndEscalationAreCommittedWithoutChangingExistingDelays() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        val pending = grants.request(request(level = BreakLevel.HARDER), true) as GrantResult.Pending
        assertEquals(60_000L, pending.session.pending!!.effectiveAtEpochMs - now)
        assertEquals(pending, grants.request(request(level = BreakLevel.HARDER), true))
        assertFalse(BlockExemptions.isExempt(pkg, now))
        now += 60_000
        assertTrue(grants.request(request(level = BreakLevel.HARDER), true) is GrantResult.Granted)
        now += 300_001
        val next = grants.request(request(level = BreakLevel.HARDER), true) as GrantResult.Pending
        assertEquals(300_000L, next.session.pending!!.effectiveAtEpochMs - now)
        assertEquals(1, next.session.breaksTaken)
    }

    @Test fun hardcoreAndTheCommittedDefaultTierCannotBeBypassedByAnEasyDisplaySnapshot() = runBlocking {
        val store = InMemoryFocusStateStore()
        store.setDefaultBreakLevel(BreakLevel.HARDCORE)
        val grants = controller(store)
        assertEquals(GrantResult.Denied, grants.request(request().copy(usesDefaultLevel = true), true))
        assertEquals(GrantResult.Denied, grants.request(request(level = BreakLevel.HARDCORE), true))
        assertEquals(0, snapshot(store).breakSession.breaksTaken)
        assertTrue(snapshot(store).exemptions.isEmpty())
    }

    @Test fun scopeAndDurationAreInputsAndAPackageSetCostsOneCredit() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        grants.request(request(scope = GrantScope.Packages(setOf(pkg, "com.second")), duration = 42_000), true)
        assertEquals(1, snapshot(store).breakSession.breaksTaken)
        assertEquals(2, snapshot(store).exemptions.size)
        assertTrue(BlockExemptions.isExempt("com.second", now + 41_999))
        assertFalse(BlockExemptions.isExempt("com.third", now))
        assertFalse(BlockExemptions.isExempt(pkg, now + 42_000))
    }

    @Test fun globalPassScopeCanBeRepresentedWithoutChangingTheHostPolicy() = runBlocking {
        val store = InMemoryFocusStateStore()
        controller(store).request(request(scope = GrantScope.Global, emergency = true, duration = 3_600_000), true)
        assertEquals("*", snapshot(store).exemptions.values.single().packageName)
        assertTrue(BlockExemptions.isExempt("com.any", now + 3_599_999, BreakLevel.HARDCORE))
        assertFalse(BlockExemptions.isExempt("com.any", now + 3_600_000, BreakLevel.HARDCORE))
    }

    @Test fun aPassCannotRunPastItsExactCauseEndOrSpendForAnEndedCause() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        val end = now + 12_345
        val before = snapshot(store)
        assertEquals(GrantResult.Denied, grants.request(request(name = "com.other", emergency = true).copy(notAfterEpochMs = now), true))
        assertEquals(before, snapshot(store))
        grants.request(request(emergency = true).copy(notAfterEpochMs = end), true)
        assertEquals(end, snapshot(store).exemptions.values.single().untilEpochMs)
        assertFalse(BlockExemptions.isExempt(pkg, end, BreakLevel.HARDCORE))
    }

    @Test fun anAlreadyActivePassAlsoDeduplicatesAnOrdinaryExitWithoutAnotherDebit() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        grants.request(request(emergency = true), true)
        val before = snapshot(store)
        assertFalse((grants.request(request(spendOpen = true), true) as GrantResult.Granted).newlyAccepted)
        assertEquals(before, snapshot(store))
    }

    @Test fun callerScopeMutationDuringIoCannotChangeTheCommittedTargets() = runBlocking {
        val store = FaultStore().apply { beforeCommit = CompletableDeferred(); release = CompletableDeferred() }
        val grants = controller(store)
        val targets = mutableSetOf(pkg, "com.second")
        val pending = async { grants.request(request(scope = GrantScope.Packages(targets)), true) }
        store.beforeCommit!!.await()
        targets.clear()
        targets += "com.unrelated"
        store.release!!.complete(Unit)
        assertTrue(pending.await() is GrantResult.Granted)
        assertEquals(setOf(pkg, "com.second"), snapshot(store.backing).exemptions.values.map { it.packageName }.toSet())
    }

    @Test fun aNormalOpenIsAcknowledgedOnceAndNeverCreatesARuleExemption() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        val id = GrantRequestId.issue(now)
        assertEquals(GrantResult.OpenAllowed, grants.recordOpen(id, pkg, OpenLimit(pkg, 1)))
        assertEquals(GrantResult.OpenAllowed, grants.recordOpen(id, pkg, OpenLimit(pkg, 1)))
        assertEquals(1, snapshot(store).openCounts.opensFor(pkg))
        assertFalse(BlockExemptions.isExempt(pkg, now))
        assertEquals(GrantResult.Denied, grants.recordOpen(GrantRequestId.issue(now), pkg, OpenLimit(pkg, 1)))
        assertEquals(1, snapshot(store).openCounts.opensFor(pkg))
    }

    @Test fun failedNormalOpenWritesAndCancellationCannotIssueAPaidVisit() = runBlocking {
        val store = FaultStore().apply { failure = StorageFailure.IO }
        val grants = controller(store)
        val id = GrantRequestId.issue(now)
        assertTrue(grants.recordOpen(id, pkg, OpenLimit(pkg, 1)) is GrantResult.Unavailable)
        assertEquals(0, snapshot(store.backing).openCounts.opensFor(pkg))
        store.failure = null
        store.release = CompletableDeferred()
        store.beforeCommit = CompletableDeferred()
        val pending = async { grants.recordOpen(id, pkg, OpenLimit(pkg, 1)) }
        store.beforeCommit!!.await()
        pending.cancelAndJoin()
        assertEquals(0, snapshot(store.backing).openCounts.opensFor(pkg))
    }

    @Test fun aGenuineForwardDayRollsCountsInsideTheAcknowledgedOpenTransaction() = runBlocking {
        val store = InMemoryFocusStateStore()
        val today = now / 86_400_000L
        store.setOpenCounts(OpenCountState(today - 1, mapOf(pkg to Int.MAX_VALUE)))
        val grants = controller(store)
        assertEquals(GrantResult.OpenAllowed, grants.recordOpen(GrantRequestId.issue(now), pkg, OpenLimit(pkg, 1)))
        assertEquals(OpenCountState(today, mapOf(pkg to 1)), snapshot(store).openCounts)
    }

    @Test fun normalOpenAndBreakRequestsCannotOverwriteEachOthersReceiptIdentity() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        val command = request()
        grants.request(command, true)
        val saved = snapshot(store)
        assertTrue(grants.recordOpen(command.id, pkg, null) is GrantResult.Unavailable)
        assertEquals(saved, snapshot(store))
        val normal = GrantRequestId.issue(now)
        grants.recordOpen(normal, "com.other", null)
        assertEquals(GrantResult.Denied, grants.request(request(id = normal, name = "com.other"), true))
    }

    @Test fun pruningExpiredReceiptsCannotReplayOldCallbacksOrDropActiveGrants() = runBlocking {
        val store = InMemoryFocusStateStore()
        val old = GrantRequestId(now - DurableGrantController.RETRY_HORIZON_MS - 1, UUID.randomUUID().toString())
        val expired = PersistedExemption(old.prefix + "0", pkg, "cause", ExemptionKind.BREAK, now - 1)
        val active = expired.copy(requestId = "old-active", packageName = "com.active", untilEpochMs = now + 100_000)
        store.transact { FocusMutation(it.copy(exemptions = listOf(expired, active).associateBy { r -> r.requestId }), Unit) }
        val grants = controller(store)
        grants.request(request(name = "com.new"), true)
        val saved = snapshot(store)
        assertFalse(saved.exemptions.containsKey(expired.requestId))
        assertEquals(active, saved.exemptions[active.requestId])
        assertTrue(grants.request(request(id = old), true) is GrantResult.Unavailable)
        assertEquals(saved, snapshot(store))
        assertTrue(BlockExemptions.isExempt("com.active", now))
    }

    @Test fun exactRetryBoundaryRejectsOldNormalOpenCallbacksEvenBeforePruning() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        val id = GrantRequestId.issue(now)
        grants.recordOpen(id, pkg, null)
        now += DurableGrantController.RETRY_HORIZON_MS
        assertTrue(grants.recordOpen(id, pkg, null) is GrantResult.Unavailable)
        assertEquals(1, snapshot(store).openCounts.opensFor(pkg))
    }

    @Test fun rollbackCannotRevivePrunedBreakOrNormalOpenRequestsAfterControllerRecreation() = runBlocking {
        for (normalOpen in listOf(false, true)) {
            BlockExemptions.clearAll()
            val store = InMemoryFocusStateStore()
            val grants = controller(store)
            val id = GrantRequestId.issue(now)
            if (normalOpen) grants.recordOpen(id, pkg, null)
            else grants.request(request(id = id), true)
            now += DurableGrantController.RETRY_HORIZON_MS + 1
            grants.request(request(name = "com.other"), true)
            val saved = snapshot(store)
            assertFalse(saved.exemptions.keys.any { it.startsWith(id.prefix) })
            assertTrue(saved.minimumRequestEpochMs > id.issuedAtEpochMs)
            now = id.issuedAtEpochMs + 1 // old identity would pass an epoch-only freshness check
            val reopened = controller(store)
            val result = if (normalOpen) reopened.recordOpen(id, pkg, null)
                else reopened.request(request(id = id), true)
            assertTrue(result is GrantResult.Unavailable)
            assertEquals(saved, snapshot(store))
        }
    }

    @Test fun activeReceiptsOutliveTheRetryHorizonAndStillDeduplicate() = runBlocking {
        val store = InMemoryFocusStateStore()
        val grants = controller(store)
        val command = request(emergency = true, duration = 2 * DurableGrantController.RETRY_HORIZON_MS)
        grants.request(command, true)
        val saved = snapshot(store)
        now += DurableGrantController.RETRY_HORIZON_MS + 1
        assertFalse((grants.request(command, true) as GrantResult.Granted).newlyAccepted)
        assertEquals(saved, snapshot(store))
        assertTrue(BlockExemptions.isExempt(pkg, now, BreakLevel.HARDCORE))
    }

    @Test fun receiptCapacityFailsWithoutSpendingOrPruningAnActiveDeadline() = runBlocking {
        val store = InMemoryFocusStateStore()
        val receipts = (0 until DurableGrantController.MAX_RECEIPTS).associate { index ->
            val id = "active-$index"
            id to PersistedExemption(id, "com.$index", "cause", ExemptionKind.BREAK, now + 100_000)
        }
        store.transact { FocusMutation(it.copy(exemptions = receipts), Unit) }
        val saved = snapshot(store)
        assertTrue(controller(store).request(request(), true) is GrantResult.Unavailable)
        assertEquals(saved, snapshot(store))
    }

    @Test fun expiredCapacityIsReclaimedBeforeAcceptingTheNextRequest() = runBlocking {
        val store = InMemoryFocusStateStore()
        val receipts = (0 until DurableGrantController.MAX_RECEIPTS).associate { index ->
            val id = "expired-$index"
            id to PersistedExemption(id, "com.$index", "cause", ExemptionKind.BREAK, now - 1)
        }
        store.transact { FocusMutation(it.copy(exemptions = receipts), Unit) }
        assertTrue(controller(store).request(request(), true) is GrantResult.Granted)
        assertEquals(1, snapshot(store).exemptions.size)
    }

    @Test fun oversizedEscapedScopeFailsAsAnOutcomeRatherThanThrowingOrSpending() = runBlocking {
        val store = InMemoryFocusStateStore()
        val packages = (1 until DurableGrantController.MAX_RECEIPTS).map { "com.$it" + "%".repeat(2_000) }.toSet() + pkg
        val result = controller(store).request(request(scope = GrantScope.Packages(packages)), true)
        assertTrue(result is GrantResult.Unavailable)
        assertEquals(0, snapshot(store).breakSession.breaksTaken)
        assertTrue(snapshot(store).exemptions.isEmpty())
    }

    @Test fun laterOlderReadCannotEraseAnAcknowledgedProjection() = runBlocking {
        val store = InMemoryFocusStateStore()
        val old = snapshot(store)
        val reads = MutableStateFlow<StorageState<FocusSnapshot>>(StorageState.Ready(old))
        val wrapped = object : FocusStateStore by store { override val state = reads }
        val grants = controller(wrapped)
        grants.request(request(), true)
        reads.value = StorageState.Unavailable(StorageFailure.IO)
        reads.value = StorageState.Ready(old)
        assertTrue(BlockExemptions.isExempt(pkg, now))
        assertEquals(1, grants.lastGood!!.breakSession.breaksTaken)
    }
}
