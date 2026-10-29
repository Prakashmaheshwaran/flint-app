package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TargetRecoveryProjectionTest {
    private val now = AccessClockStamp(1_800_000_000_000, 1_000, "boot")
    private val input = OrdinaryProtectionInput("selected.app", "self", false, false)
    private val id = "p1-11111111-1111-4111-8111-111111111111"
    private fun snapshot(): ProtectionSnapshot {
        val rule = BlockRule("session-timer-v2-one", "Timer", BlockTargets(apps = setOf(AppRef(input.packageName))),
            breakLevel = BreakLevel.EASY, expiresAtEpochMs = now.wallEpochMs + 600_000)
        return ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(now, now)), listOf(rule), emptyList(), emptyList(),
            EditorCommitmentState(causes = mapOf(ruleCauseKey(rule.id) to EditorCause("occurrence",
                AnchoredInterval(now, 600_000, now.wallEpochMs + 600_000), "UTC")), timeline = now),
            HarderWaitPolicy.Ledger(), now)
    }
    private fun granted(): ProtectionSnapshot {
        val stored = snapshot()
        val mutation = OrdinaryProtectionPolicy.request(stored, input, id, now, emptyList(),
            OrdinaryProtectionPolicy.frozenScopes(stored, input, emptyList(), now)!!)
        assertTrue(mutation.result is GrantResult.Granted)
        return stored.copy(focus = mutation.focus)
    }
    private fun read(stored: ProtectionSnapshot, at: AccessClockStamp = now,
        target: OrdinaryProtectionInput? = input, limitsKnown: Boolean = true) =
        TargetRecoveryProjection.read(stored, at, target, emptyList(), limitsKnown)

    // PC-20/PC-24: a read cannot consume, checkpoint, or broaden the frozen grant.
    @Test fun ordinaryEndUsesTheExactRuntimeFrozenScopeAndDoesNotCheckpointOrMintEvents() {
        val stored = granted()
        val ledger = stored.focus.protection!!.copy()
        assertEquals(now.wallEpochMs + 300_000, read(stored).endsAtWall)
        repeat(20) { assertNull(read(stored).unavailableReason) }
        assertEquals(ledger, stored.focus.protection)
        assertEquals(1, stored.focus.protection!!.outbox.size)
    }

    @Test fun anotherTargetNeverInheritsTheOrdinaryGrant() {
        assertNull(read(granted(), target = input.copy(packageName = "other.app")).endsAtWall)
    }

    @Test fun changedDefinitionOccurrenceOrStrongerTierImmediatelyStopsCoveringTheTarget() {
        val stored = granted()
        assertNull(read(stored.copy(rules = stored.rules.map { it.copy(name = "Changed") })).endsAtWall)
        assertNull(read(stored.copy(rules = stored.rules.map { it.copy(breakLevel = BreakLevel.HARDCORE) })).endsAtWall)
        assertNull(read(stored.copy(editor = stored.editor.copy(causes = stored.editor.causes.mapValues {
            it.value.copy(occurrenceId = "next")
        }))).endsAtWall)
    }

    // PC-54/PC-55/PC-59: wall estimates move; elapsed access and consumption do not.
    @Test fun wallJumpChangesOnlyTheEstimateWhileExpiryAndRebootNeverRenewAccess() {
        val stored = granted()
        val jumped = now.copy(wallEpochMs = now.wallEpochMs - 86_400_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        assertEquals(jumped.wallEpochMs + 299_000, read(stored, jumped).endsAtWall)
        assertNull(read(stored, now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 300_000)).endsAtWall)
        assertNull(read(stored, now.copy(bootSessionId = "next", elapsedRealtimeMs = 0)).endsAtWall)
    }

    @Test fun unknownLimitDispositionOrMissingScopeCannotProveAnOrdinaryRelease() {
        val stored = granted()
        assertEquals("target-break-scope-unavailable", read(stored, limitsKnown = false).unavailableReason)
        assertEquals("target-break-scope-unavailable", read(stored.copy(editor = EditorCommitmentState())).unavailableReason)
        assertNull(read(snapshot(), limitsKnown = false).unavailableReason)
    }

    @Test fun siteWithoutKnownBrowserCannotAssumeAnAppScopedBreakCoversIt() {
        assertEquals("target-break-scope-unavailable", read(granted(), target = null).unavailableReason)
        assertNull(read(snapshot(), target = null).unavailableReason)
    }

    // PC-41/PC-42: global recovery includes targets whose scope is otherwise unknown.
    @Test fun committedGlobalRecoveryCoversAllTargetsWithoutMutatingItsLifecycle() {
        val stored = snapshot()
        val recovery = ProtectionRecovery(id, now, setOf("rule:one"), setOf("one"), emptySet(), 300_000)
        val active = stored.copy(focus = stored.focus.copy(protection = stored.focus.protection!!.copy(recovery = recovery)))
        assertEquals(now.wallEpochMs + 300_000, read(active, target = null, limitsKnown = false).endsAtWall)
        assertEquals(now.wallEpochMs + 300_000, read(active, target = input.copy(packageName = "other.app")).endsAtWall)
        assertNull(read(active, now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 300_000)).endsAtWall)
        assertEquals(recovery, active.focus.protection!!.recovery)
        assertTrue(active.focus.protection!!.outbox.isEmpty())
    }

    @Test fun missingRuntimeLedgerIsExplicitlyUnavailableAndNeverInitializesIt() {
        val stored = snapshot().let { it.copy(focus = it.focus.copy(protection = null)) }
        assertEquals("protection-runtime-state-unavailable", read(stored).unavailableReason)
        assertNull(stored.focus.protection)
    }
}
