package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel

/** Shared reduction for rule, daily-time and open-limit causes on both detection paths. */
internal object BlockOverlapPolicy {
    /** Earlier candidates retain their existing display priority when protection is equal. */
    fun strongest(vararg causes: BlockCause?): BlockCause? =
        causes.filterNotNull().maxByOrNull { it.breakLevel.ordinal }

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
