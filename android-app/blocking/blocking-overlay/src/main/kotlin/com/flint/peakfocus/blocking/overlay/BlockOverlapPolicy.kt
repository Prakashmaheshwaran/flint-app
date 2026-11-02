package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason

/** Shared reduction for rule, daily-time and open-limit causes on both detection paths. */
internal object BlockOverlapPolicy {
    /** Tier, latest/unknown end, then stable cause priority and rule identity. */
    fun strongest(vararg causes: BlockCause?): BlockCause? {
        val contributors = causes.filterNotNull()
        val strongest = contributors.maxWithOrNull(
            compareBy<BlockCause> { it.breakLevel.ordinal }
                .thenBy { it.endsAtEpochMs == null }
                .thenBy { it.endsAtEpochMs }
                // Preserve the coordinator's existing time -> rule -> open priority on
                // exact ties: choosing an open cause also changes break accounting.
                .thenByDescending {
                    when (it.reason) {
                        BlockScreenReason.TIME_LIMIT -> 0
                        BlockScreenReason.OPEN_LIMIT -> 2
                        else -> 1
                    }
                }
                .thenByDescending { it.ruleId.orEmpty() }
                .thenByDescending { it.reason.name },
        ) ?: return null
        // Recompute from current causes' own ends: a retained surface must not perpetuate a
        // discarded contributor's old display projection. Rule rows are added at render time.
        return BlockDisplayEndProjection.combine(strongest, contributors.map { it.copy(displayEnd = null) }, Long.MIN_VALUE)
    }

    /** An ordinary break never stands down a currently applicable Hardcore restriction. */
    fun afterExemptions(
        cause: BlockCause?,
        ordinaryExemption: Boolean,
        emergencyExemption: Boolean,
    ): BlockCause? = when {
        cause == null -> null
        emergencyExemption -> null
        ordinaryExemption && cause.breakLevel != BreakLevel.HARDCORE -> null
        else -> cause
    }
}
