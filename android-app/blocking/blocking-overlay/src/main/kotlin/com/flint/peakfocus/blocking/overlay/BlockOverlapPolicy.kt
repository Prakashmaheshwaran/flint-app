package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel

/** Shared reduction for rule, daily-time and open-limit causes on both detection paths. */
internal object BlockOverlapPolicy {
    /** Match rule headline ordering: tier, latest/unknown end, then a stable cause identity. */
    fun strongest(vararg causes: BlockCause?): BlockCause? =
        causes.filterNotNull().maxWithOrNull(
            compareBy<BlockCause> { it.breakLevel.ordinal }
                .thenBy { it.endsAtEpochMs == null }
                .thenBy { it.endsAtEpochMs }
                .thenByDescending { it.ruleId?.let { id -> "rule:$id" } ?: "limit:${it.reason.name}" },
        )

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
