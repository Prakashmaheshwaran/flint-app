package com.flint.peakfocus

import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.blocking.engine.OpenCreditLedgerPolicy
import com.flint.peakfocus.blocking.overlay.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetProtectionReadersTest {
    private val now = AccessClockStamp(1_800_000_000_000, 1_000, "boot")
    private val target = TargetRef.App("selected.app")
    private val domain = BlockRule("domain", "Domain", BlockTargets(domains = setOf(DomainRef("example.com"))))
    private fun snapshot() = ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(now, now)),
        listOf(domain), emptyList(), emptyList(), EditorCommitmentState(timeline = now),
        HarderWaitPolicy.Ledger(), now, OpenCreditLedgerPolicy.initial(now, "UTC"))

    @Test fun missingRuleOccurrenceInProductionObservationPreservesRetainedFloorAndPreciseFailure() = runTest {
        val stored = snapshot() // Saved rule has no materialized EditorCause.
        val snapshots = object : ProtectionSnapshotReader {
            override val state = flowOf(StorageState.Ready(stored))
            override val openCredits = flowOf(StorageState.Ready(stored.openCredits))
        }
        val retained = FloorIdentity(FloorIdentity.Kind.RUNTIME_HOME, 123, true)
        var floorReads = 0
        val reader = ObservedTargetProtectionReader(snapshots, backgroundScope, "flint", { selected, state, at ->
            val active = targetDomainRulesActive((state as? StorageState.Ready)?.value, emptyList(), selected, at)
            floorReads++
            TargetReadEnvironment(floor = retained, websiteCoverage = if (active)
                TargetWebsiteCoverage.AWAITING_BROWSER else TargetWebsiteCoverage.NOT_REQUIRED)
        }, clock = { now }, ticks = flowOf(now), worker = StandardTestDispatcher(testScheduler))
        val values = mutableListOf<TargetProtection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reader.observe(target).collect(values::add) }
        runCurrent()
        assertTrue(floorReads > 0)
        assertEquals(Readiness.Unavailable("rule-occurrence-unavailable"), values.last().readiness)
        assertEquals(retained, values.last().floor)
        assertEquals(false, values.last().blockingNow)
        assertTrue(stored.editor.causes.isEmpty())
    }

    @Test fun failedDomainProjectionIsUnknownWhileKnownInactiveAndUnrelatedPoliciesNeedNoCoverage() {
        val stored = snapshot()
        assertTrue(targetDomainRulesActive(stored, emptyList(), TargetRef.Site("example.com"), now))
        val ready = stored.copy(editor = stored.editor.copy(causes = mapOf(ruleCauseKey(domain.id) to
            EditorCause("domain", null, "UTC"))))
        assertTrue(targetDomainRulesActive(ready, emptyList(), TargetRef.Site("example.com"), now))
        assertFalse(targetDomainRulesActive(ready, emptyList(), TargetRef.Site("other.example"), now))
        assertFalse(targetDomainRulesActive(ready.copy(rules = listOf(domain.copy(enabled = false))),
            emptyList(), TargetRef.Site("example.com"), now))
    }
}
