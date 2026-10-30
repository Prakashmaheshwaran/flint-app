package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.*
import org.junit.Test

class TargetProtectionReductionTest {
    private var now = 1_000L
    private fun cause(key: String, tier: BreakLevel = BreakLevel.EASY, end: Long? = 3_000,
        active: Boolean = true, enforcing: Boolean? = true, next: Long? = end) =
        CoveringCause(key, key, CauseKind.SCHEDULE, tier, active, end, false, next,
            enforcing, "rule:$key", "occurrence:$key")
    private fun resolve(causes: List<CoveringCause>, readiness: Readiness = Readiness.Ready,
        floor: FloorIdentity? = null, recoveryEnd: Long? = null) =
        TargetProtectionReduction.resolve(readiness, floor, causes, now, recoveryEnd)

    @Test fun strongestTierHeadlineAndLatestEnforcingEndAreIndependent() {
        val hard = cause("hard", BreakLevel.HARDCORE, 2_000)
        val easy = cause("easy", BreakLevel.EASY, 5_000)
        val result = resolve(listOf(easy, hard))
        assertEquals(true, result.blockingNow)
        assertEquals(hard, result.headline)
        assertEquals(5_000L, result.blockingUntilWall)
        assertFalse(result.blockingOngoing)
        assertEquals(2_000L, result.nextChangeAtWall)
    }

    @Test fun headlineTiesUseLatestEndThenStableIdentityIndependentOfInputOrder() {
        val earlier = cause("earlier", end = 2_000)
        val a = cause("a", end = 4_000)
        val b = cause("b", end = 4_000)
        for (items in listOf(listOf(b, earlier, a), listOf(a, b, earlier), listOf(earlier, a, b))) {
            assertEquals(a, resolve(items).headline)
        }
    }

    @Test fun activeCommitmentOnBreakDoesNotContributeToBlockingUntil() {
        val suspended = cause("break", end = 9_000, enforcing = false)
        val blocking = cause("block", end = 4_000)
        val result = resolve(listOf(suspended, blocking))
        assertEquals(blocking, result.headline)
        assertEquals(4_000L, result.blockingUntilWall)
        assertEquals(true, result.blockingNow)
    }

    @Test fun knownOngoingEnforcementIsNotAnUnknownEndOrAnInactiveTemplate() {
        val ongoing = cause("ongoing", end = null)
        val inactive = cause("inactive", BreakLevel.HARDCORE, end = null, active = false)
        val result = resolve(listOf(ongoing, inactive))
        assertEquals(true, result.blockingNow)
        assertTrue(result.blockingOngoing)
        assertNull(result.blockingUntilWall)
        assertEquals(ongoing, result.headline)
    }

    @Test fun unknownEnforcementCannotBecomeUnprotectedOrAnExactEnd() {
        val unknown = cause("unmeasured-time", enforcing = null)
        assertNull(resolve(listOf(unknown)).blockingNow)
        val withKnown = resolve(listOf(unknown, cause("known", end = 2_000)))
        assertEquals(true, withKnown.blockingNow)
        assertNull(withKnown.blockingUntilWall)
        assertFalse(withKnown.blockingOngoing)
    }

    @Test fun staticFloorWinsEvenWhileStorageIsLoadingOrUnavailable() {
        val floor = FloorIdentity(FloorIdentity.Kind.STATIC_SYSTEM)
        for (state in listOf(Readiness.Loading, Readiness.Unavailable("storage-corrupt"))) {
            assertEquals(false, resolve(listOf(cause("hard", BreakLevel.HARDCORE)), state, floor).blockingNow)
            val ordinary = resolve(emptyList(), state)
            assertNull(ordinary.blockingNow)
            assertNull(ordinary.headline)
        }
    }

    @Test fun verifiedTargetRecoveryKeepsTheCommitmentButResolvesItsCurrentRelease() {
        val result = resolve(listOf(cause("hard", BreakLevel.HARDCORE, end = 9_000)), recoveryEnd = 2_000)
        assertEquals(false, result.blockingNow)
        assertEquals(2_000L, result.recoveryEndsAtWall)
        assertEquals(2_000L, result.nextChangeAtWall)
        assertNull(result.headline)
        assertFalse(result.blockingOngoing)
    }

    @Test fun advancingTheFakeClockExpiresRecoveryAndIgnoresPastBoundaries() {
        val dormant = cause("tomorrow", active = false, enforcing = false, next = 8_000)
        now = 4_000
        val result = resolve(listOf(cause("expired", end = 3_000), dormant), recoveryEnd = 2_000)
        assertEquals(false, result.blockingNow)
        assertNull(result.recoveryEndsAtWall)
        assertNull(result.blockingUntilWall)
        assertEquals(8_000L, result.nextChangeAtWall)
        assertEquals(true, resolve(listOf(cause("still-active", end = 9_000)), recoveryEnd = 2_000).blockingNow)
    }
}
