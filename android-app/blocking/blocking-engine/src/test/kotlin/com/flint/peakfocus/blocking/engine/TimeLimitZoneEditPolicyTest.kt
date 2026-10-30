package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeLimitZoneEditPolicyTest {
    private val policy = CommitmentPolicy()
    private fun limit(tier: BreakLevel) = CommitmentPolicy.ProtectionShape(
        name = "Daily limit", tier = tier, allowListMode = false,
        apps = setOf("target"), domains = emptySet(), budget = 30,
        dailyZoneId = "America/New_York",
    )

    @Test fun PC_15_PC_56_activeSavedZoneEditWeakensEvenWithASmallerBudgetAndHigherTier() {
        val expected = mapOf(
            BreakLevel.EASY to CommitmentPolicy.Outcome.ALLOW,
            BreakLevel.HARDER to CommitmentPolicy.Outcome.WAIT,
            BreakLevel.HARDCORE to CommitmentPolicy.Outcome.DEFER,
        )
        expected.forEach { (tier, outcome) ->
            val before = limit(tier)
            val after = before.copy(budget = 1, tier = BreakLevel.HARDCORE, dailyZoneId = "Europe/Berlin")
            assertEquals(CommitmentPolicy.EditKind.WEAKEN, policy.classifyEdit(before, after))
            assertEquals(outcome, policy.decideEdit(before, after, CommitmentPolicy.CauseActivity.DailyLimit(true)))
            assertEquals(CommitmentPolicy.Outcome.ALLOW, policy.decideEdit(before, after, CommitmentPolicy.CauseActivity.Inactive))
        }
    }

    @Test fun PC_15_PC_56_unchangedSavedZoneRetainsBudgetAndCosmeticEditRights() {
        val before = limit(BreakLevel.HARDCORE)
        assertEquals(CommitmentPolicy.EditKind.COSMETIC, policy.classifyEdit(before, before.copy(name = "Renamed")))
        assertEquals(CommitmentPolicy.EditKind.STRENGTHEN, policy.classifyEdit(before, before.copy(budget = 1)))
        assertEquals(CommitmentPolicy.Outcome.ALLOW, policy.decideEdit(before, before.copy(budget = 1), CommitmentPolicy.CauseActivity.DailyLimit(true)))
        // Migration must normalize both sides before comparison; dropping a known zone
        // or adding one only to the requested edit cannot manufacture strengthening.
        assertEquals(CommitmentPolicy.EditKind.WEAKEN, policy.classifyEdit(before, before.copy(dailyZoneId = null)))
        assertEquals(CommitmentPolicy.EditKind.WEAKEN, policy.classifyEdit(before.copy(dailyZoneId = null), before))
    }
}
