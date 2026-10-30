package com.flint.peakfocus.blocking.overlay

/** Already-targeted facts only; no store, clock read, target discovery or policy mutation. */
internal data class TargetResolution(
    val blockingNow: Boolean?,
    val blockingUntilWall: Long?,
    val blockingOngoing: Boolean,
    val recoveryEndsAtWall: Long?,
    val headline: CoveringCause?,
    val nextChangeAtWall: Long?,
)

internal object TargetProtectionReduction {
    fun resolve(
        readiness: Readiness,
        floor: FloorIdentity?,
        causes: List<CoveringCause>,
        nowWall: Long,
        verifiedRecoveryEndsAtWall: Long? = null,
    ): TargetResolution {
        // The floor is independently knowable even when all policy storage is unavailable.
        if (readiness != Readiness.Ready) {
            return TargetResolution(if (floor != null) false else null, null, false, null, null, null)
        }
        val active = causes.filter { it.active && (it.endsAtWall == null || it.endsAtWall > nowWall) }
        val recoveryEnd = verifiedRecoveryEndsAtWall?.takeIf { it > nowWall && floor == null }
        val next = (causes.mapNotNull { it.nextChangeAtWall } + active.mapNotNull { it.endsAtWall } +
            listOfNotNull(recoveryEnd)).filter { it > nowWall }.minOrNull()
        if (floor != null || recoveryEnd != null) {
            return TargetResolution(false, null, false, recoveryEnd, null, next)
        }
        val enforcing = active.filter { it.enforcingNow == true }
        val unknown = active.any { it.enforcingNow == null }
        if (enforcing.isEmpty()) {
            return TargetResolution(if (unknown) null else false, null, false, null, null, next)
        }
        val headline = enforcing.sortedWith(compareByDescending<CoveringCause> { it.tier.ordinal }
            .thenByDescending { it.endsAtWall ?: Long.MAX_VALUE }
            .thenBy { it.causeKey }.thenBy { it.occurrenceId.orEmpty() }).first()
        val ongoing = enforcing.any { it.endsAtWall == null }
        // An unknown contributor may outlast the known ones. Do not invent an exact end.
        val until = if (ongoing || unknown) null else enforcing.maxOf { checkNotNull(it.endsAtWall) }
        return TargetResolution(true, until, ongoing, null, headline, next)
    }
}
