package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.engine.OpenCreditLedgerPolicy
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservedTargetProtectionReaderTest {
    private val now = AccessClockStamp(1_800_000_000_000, 1_000, "boot")
    private fun snapshot() = ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(now, now)),
        emptyList(), emptyList(), emptyList(), EditorCommitmentState(timeline = now), HarderWaitPolicy.Ledger(), now)
    private fun snapshots(source: Flow<StorageState<ProtectionSnapshot>>) = object : ProtectionSnapshotReader {
        override val state = source
        override val openCredits = flowOf(StorageState.Ready(null))
    }

    @Test fun multipleTargetsAndTicksShareOneDecodeAndStopUpstreamWhenTheLastCollectorLeaves() = runTest {
        val stored = snapshot()
        var collections = 0
        var active = 0
        val source = MutableStateFlow<StorageState<ProtectionSnapshot>>(StorageState.Ready(stored))
        val raw = snapshots(flow {
            collections++; active++
            try { emitAll(source) } finally { active-- }
        })
        val clock = MutableStateFlow(now)
        val reader = ObservedTargetProtectionReader(raw, backgroundScope, "flint", { _, _, _ -> TargetReadEnvironment() },
            clock = { clock.value }, ticks = clock, worker = StandardTestDispatcher(testScheduler))
        val first = mutableListOf<TargetProtection>()
        val second = mutableListOf<TargetProtection>()
        val a = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.App("one.app")).collect(first::add) }
        val b = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.App("two.app")).collect(second::add) }
        runCurrent()
        assertEquals(1, collections)
        assertEquals(Readiness.Ready, first.last().readiness)
        repeat(20) {
            clock.value = now.copy(wallEpochMs = now.wallEpochMs + it * 1_000, elapsedRealtimeMs = now.elapsedRealtimeMs + it * 1_000)
            runCurrent()
        }
        assertEquals(1, collections)
        assertEquals(stored, source.value.let { (it as StorageState.Ready).value })
        assertTrue(stored.focus.protection!!.outbox.isEmpty())
        a.cancel(); b.cancel(); runCurrent()
        assertEquals(0, active)
    }

    @Test fun loadingAndIoFailureNeverBecomeReadyEmptyProtectionAndStaticFloorStillWins() = runTest {
        val reader = ObservedTargetProtectionReader(snapshots(flow { throw IOException("private path must not leak") }),
            backgroundScope, "flint", { _, _, _ -> TargetReadEnvironment() }, ticks = flowOf(now),
            clock = { now }, worker = StandardTestDispatcher(testScheduler))
        val values = mutableListOf<TargetProtection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.App("flint")).collect(values::add) }
        runCurrent()
        assertTrue(values.none { it.readiness == Readiness.Ready })
        assertEquals(Readiness.Unavailable("policy-storage-io"), values.last().readiness)
        assertEquals(FloorIdentity.Kind.SELF, values.last().floor!!.kind)
        assertEquals(false, values.last().blockingNow)
        assertTrue(values.all { it.coveringCauses.isEmpty() })
    }

    @Test fun loadingPropagatesWhileTheInitialStoreReadIsPending() = runTest {
        val source = MutableStateFlow<StorageState<ProtectionSnapshot>>(StorageState.Loading)
        val reader = ObservedTargetProtectionReader(snapshots(source), backgroundScope, "flint",
            { _, _, _ -> TargetReadEnvironment() }, clock = { now }, ticks = flowOf(now), worker = StandardTestDispatcher(testScheduler))
        val values = mutableListOf<TargetProtection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.App("one.app")).collect(values::add) }
        runCurrent()
        assertEquals(Readiness.Loading, values.last().readiness)
        assertNull(values.last().blockingNow)
        source.value = StorageState.Unavailable(StorageFailure.CORRUPT)
        runCurrent()
        assertEquals(Readiness.Unavailable("policy-storage-corrupt"), values.last().readiness)
        assertNull(values.last().blockingNow)
    }

    @Test fun observationFailureIsSanitizedAndDoesNotEraseAnIndependentStaticFloor() = runTest {
        val reader = ObservedTargetProtectionReader(snapshots(flowOf(StorageState.Ready(snapshot()))), backgroundScope, "flint",
            { _, _, _ -> throw IllegalStateException("private observation") }, ticks = flowOf(now),
            clock = { now }, worker = StandardTestDispatcher(testScheduler))
        val values = mutableListOf<TargetProtection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.App("com.android.settings")).collect(values::add) }
        runCurrent()
        assertEquals(Readiness.Unavailable("runtime-observation-unavailable"), values.last().readiness)
        assertEquals(FloorIdentity.Kind.STATIC_SYSTEM, values.last().floor!!.kind)
        assertEquals(false, values.last().blockingNow)
    }

    @Test fun projectionSamplesTheClockAfterObservationsInsteadOfUsingAnOlderInvalidatingTick() = runTest {
        var sampled = now
        val stored = snapshot().let { it.copy(focus = it.focus.copy(protection = it.focus.protection!!.copy(
            recovery = ProtectionRecovery("pass", now, emptySet(), emptySet(), emptySet(), 300_000)))) }
        val reader = ObservedTargetProtectionReader(snapshots(flowOf(StorageState.Ready(stored))), backgroundScope, "flint",
            { _, _, _ ->
                sampled = now.copy(wallEpochMs = now.wallEpochMs + 86_400_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 500)
                TargetReadEnvironment()
            }, clock = { sampled }, ticks = flowOf(now), worker = StandardTestDispatcher(testScheduler))
        val values = mutableListOf<TargetProtection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.Site("example.com")).collect(values::add) }
        runCurrent()
        assertEquals(sampled.wallEpochMs + 299_500, values.last().recoveryEndsAtWall)
        assertEquals(false, values.last().blockingNow)
        assertEquals(now, stored.observedAt)
    }

    @Test fun quotaProjectionThrowsBecomeUnavailableAndTheSameCollectionRecoversOnTheNextTick() = runTest {
        for (name in listOf("one.app", "flint")) {
            val stored = snapshot().copy(times = listOf(TimeLimit(name, 5)),
                editor = EditorCommitmentState(causes = mapOf(timeCauseKey(name) to EditorCause("period",
                    AnchoredInterval(now, 60_000, now.wallEpochMs + 60_000), "UTC")), timeline = now),
                openCredits = OpenCreditLedgerPolicy.initial(now, "UTC"))
            var sampled = now.copy(elapsedRealtimeMs = Long.MAX_VALUE)
            val ticks = MutableStateFlow(sampled)
            val reader = ObservedTargetProtectionReader(snapshots(flowOf(StorageState.Ready(stored))), backgroundScope, "flint",
                { _, _, _ -> TargetReadEnvironment() }, clock = { sampled }, ticks = ticks,
                worker = StandardTestDispatcher(testScheduler))
            val values = mutableListOf<TargetProtection>()
            val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.App(name)).collect(values::add) }
            runCurrent()
            assertEquals(Readiness.Unavailable("target-projection-unavailable"), values.last().readiness)
            if (name == "flint") assertEquals(false, values.last().blockingNow) else assertNull(values.last().blockingNow)
            assertTrue(collecting.isActive)
            sampled = now; ticks.value = now; runCurrent()
            assertEquals(Readiness.Ready, values.last().readiness)
            assertTrue(stored.focus.protection!!.outbox.isEmpty())
            collecting.cancel(); runCurrent()
        }
    }

    @Test fun cancellationIsNeverConvertedIntoAnUnavailableEmission() = runTest {
        val reader = ObservedTargetProtectionReader(snapshots(flowOf(StorageState.Ready(snapshot()))), backgroundScope, "flint",
            { _, _, _ -> TargetReadEnvironment() }, clock = { throw CancellationException("cancelled") }, ticks = flowOf(now),
            worker = StandardTestDispatcher(testScheduler))
        val values = mutableListOf<TargetProtection>()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(TargetRef.App("one.app")).collect(values::add) }
        runCurrent()
        assertTrue(collecting.isCancelled)
        assertTrue(values.none { it.readiness is Readiness.Unavailable })
    }

    @Test fun aThrowingSiteProjectionRetainsCoverageProvenanceButNeverTrustsMissingOrExpiredStamps() = runTest {
        val browser = "com.android.chrome"
        val stored = snapshot().copy(times = listOf(TimeLimit(browser, 5)),
            editor = EditorCommitmentState(causes = mapOf(timeCauseKey(browser) to EditorCause("period",
                AnchoredInterval(now, 60_000, now.wallEpochMs + 60_000), "UTC")), timeline = now),
            openCredits = OpenCreditLedgerPolicy.initial(now, "UTC"))
        val fresh = TargetReadEnvironment(browserPackageName = browser,
            websiteCoverage = TargetWebsiteCoverage.AVAILABLE, domainContextKnown = true,
            websiteCoverageObservedAtWall = now.wallEpochMs - 1_000,
            websiteCoverageValidUntilWall = now.wallEpochMs + 119_000, websiteCoverageIsCurrent = true)
        for (env in listOf(fresh, fresh.copy(websiteCoverageObservedAtWall = null),
            fresh.copy(websiteCoverageValidUntilWall = null), fresh.copy(websiteCoverageValidUntilWall = now.wallEpochMs))) {
            val sampled = now.copy(elapsedRealtimeMs = Long.MAX_VALUE)
            val reader = ObservedTargetProtectionReader(snapshots(flowOf(StorageState.Ready(stored))), backgroundScope, "flint",
                { _, _, _ -> env }, clock = { sampled }, ticks = flowOf(sampled), worker = StandardTestDispatcher(testScheduler))
            val values = mutableListOf<TargetProtection>()
            val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                reader.observe(TargetRef.Site("example.com")).collect(values::add)
            }
            runCurrent()
            val view = values.last()
            assertEquals(Readiness.Unavailable("target-projection-unavailable"), view.readiness)
            assertEquals(env.websiteCoverageObservedAtWall, view.websiteCoverageObservedAtWall)
            assertEquals(env.websiteCoverageValidUntilWall, view.websiteCoverageValidUntilWall)
            assertEquals(if (env == fresh) TargetWebsiteCoverage.AVAILABLE else TargetWebsiteCoverage.AWAITING_BROWSER,
                view.websiteCoverage)
            assertEquals(env == fresh, view.websiteCoverageIsCurrent)
            assertNull(view.blockingNow)
            collecting.cancel(); runCurrent()
        }
    }
}
