package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AccessTarget
import com.flint.peakfocus.core.model.BlockRule

/** A verified release estimate, or an explicit inability to prove this target's scope. */
internal data class TargetRecoveryFacts(val endsAtWall: Long? = null, val unavailableReason: String? = null)

/**
 * Reads the runtime's exact frozen-scope policy without checkpointing or retiring a grant.
 * [endsAtWall][TargetRecoveryFacts.endsAtWall] is only a display estimate from remaining
 * elapsed time. A grant never survives a reboot, gains time from a wall jump, or expands
 * to a new cause. Unknown limit disposition cannot establish ordinary release coverage.
 * The caller must supply the same projected foreign rules and target input as enforcement.
 */
internal object TargetRecoveryProjection {
    fun read(
        snapshot: ProtectionSnapshot,
        now: AccessClockStamp,
        input: OrdinaryProtectionInput?,
        foreignRules: List<BlockRule>,
        limitDispositionKnown: Boolean = true,
    ): TargetRecoveryFacts {
        val ledger = snapshot.focus.protection
            ?: return TargetRecoveryFacts(unavailableReason = "protection-runtime-state-unavailable")
        val globalRemaining = GlobalRecoveryPolicy.remaining(ledger.recovery, now)
        if (globalRemaining > 0) return TargetRecoveryFacts(end(now, globalRemaining))
        val live = ledger.ordinaryGrants.values.filter { it.lifetime.remainingMs(now)?.let { left -> left > 0 } == true }
        // A Site read needs a known browser to prove which App-scoped ordinary grant applies.
        if (input == null) return if (live.isEmpty()) TargetRecoveryFacts()
            else TargetRecoveryFacts(unavailableReason = "target-break-scope-unavailable")
        val grant = live.firstOrNull { it.target == AccessTarget.App(input.packageName) } ?: return TargetRecoveryFacts()
        if (!limitDispositionKnown || OrdinaryProtectionPolicy.frozenScopes(snapshot, input, foreignRules, now) == null) {
            return TargetRecoveryFacts(unavailableReason = "target-break-scope-unavailable")
        }
        if (!OrdinaryProtectionPolicy.covers(snapshot, input, foreignRules, now)) return TargetRecoveryFacts()
        return TargetRecoveryFacts(end(now, checkNotNull(grant.lifetime.remainingMs(now))))
    }

    private fun end(now: AccessClockStamp, remaining: Long): Long =
        if (now.wallEpochMs > Long.MAX_VALUE - remaining) Long.MAX_VALUE else now.wallEpochMs + remaining
}
