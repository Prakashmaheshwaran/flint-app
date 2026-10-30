package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.blocking.engine.CommitmentPolicy.Outcome
import com.flint.peakfocus.blocking.engine.CommitmentPolicy.ProtectionShape
import com.flint.peakfocus.blocking.engine.DailyLimitLifecyclePolicy.ChangeKind
import com.flint.peakfocus.blocking.engine.DailyLimitLifecyclePolicy.Template
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyLimitLifecyclePolicyTest {
    private val policy = DailyLimitLifecyclePolicy()
    private fun limit(tier: BreakLevel, enabled: Boolean = true, budget: Int = 30) = Template(
        enabled, ProtectionShape("app", tier, false, setOf("app"), emptySet(), budget = budget),
    )

    @Test fun `PC_15 enabled Time limit below budget still gates deletion at its own tier`() {
        val expected = listOf(Outcome.ALLOW, Outcome.WAIT, Outcome.DEFER)
        BreakLevel.entries.forEachIndexed { index, tier ->
            assertEquals(DailyLimitLifecyclePolicy.Decision(ChangeKind.DELETE, expected[index]),
                policy.decide(limit(tier), null))
        }
    }

    @Test fun `PC_16 accepted credit cannot make enabled Open limit freely disableable`() {
        // Credit state is deliberately not an activity input: the template remains committed.
        val before = limit(BreakLevel.HARDCORE, budget = 5)
        assertEquals(DailyLimitLifecyclePolicy.Decision(ChangeKind.TOGGLE_OFF, Outcome.DEFER),
            policy.decide(before, before.copy(enabled = false)))
    }

    @Test fun `PC_15 disable plus tier downgrade or lower budget still uses prior tier`() {
        BreakLevel.entries.forEach { tier ->
            val before = limit(tier)
            val after = limit(BreakLevel.EASY, enabled = false, budget = 0)
            val expected = when (tier) {
                BreakLevel.EASY -> Outcome.ALLOW
                BreakLevel.HARDER -> Outcome.WAIT
                BreakLevel.HARDCORE -> Outcome.DEFER
            }
            assertEquals(expected, policy.decide(before, after).outcome)
        }
    }

    @Test fun `PC_15 budget increase gates while decrease preserves strengthening`() {
        val before = limit(BreakLevel.HARDCORE)
        assertEquals(Outcome.DEFER, policy.decide(before, limit(BreakLevel.HARDCORE, budget = 45)).outcome)
        assertEquals(Outcome.ALLOW, policy.decide(before, limit(BreakLevel.HARDCORE, budget = 15)).outcome)
    }

    @Test fun `PC_36 reenable uses selected tier disclosure without a new accounting operation`() {
        BreakLevel.entries.forEach { tier ->
            val before = limit(BreakLevel.HARDCORE, enabled = false)
            val after = limit(tier)
            val expected = if (tier == BreakLevel.HARDCORE) Outcome.ALLOW_AFTER_DISCLOSURE else Outcome.ALLOW
            assertEquals(DailyLimitLifecyclePolicy.Decision(ChangeKind.START, expected), policy.decide(before, after))
        }
    }

    @Test fun `new enabled and disabled templates have different start requirements`() {
        assertEquals(Outcome.ALLOW_AFTER_DISCLOSURE, policy.decide(null, limit(BreakLevel.HARDCORE)).outcome)
        assertEquals(Outcome.ALLOW, policy.decide(null, limit(BreakLevel.HARDCORE, enabled = false)).outcome)
    }

    @Test fun `disabled templates are freely edited and deleted at every stored tier`() {
        BreakLevel.entries.forEach { tier ->
            val before = limit(tier, enabled = false)
            assertEquals(Outcome.ALLOW, policy.decide(before, null).outcome)
            assertEquals(Outcome.ALLOW, policy.decide(before, limit(BreakLevel.EASY, enabled = false, budget = 60)).outcome)
        }
    }

    @Test fun `D6 unchanged Hardcore Time side does not impose a gate on Easy Open edit`() {
        val time = limit(BreakLevel.HARDCORE)
        val open = limit(BreakLevel.EASY, budget = 5)
        assertEquals(DailyLimitLifecyclePolicy.Decision(ChangeKind.UNCHANGED, Outcome.ALLOW), policy.decide(time, time))
        assertEquals(Outcome.ALLOW, policy.decide(open, open.copy(enabled = false)).outcome)
    }

    @Test fun `D6 each changed side retains its own prior tier`() {
        assertEquals(Outcome.WAIT, policy.decide(limit(BreakLevel.HARDER), null).outcome)
        assertEquals(Outcome.DEFER, policy.decide(limit(BreakLevel.HARDCORE, budget = 5), null).outcome)
    }

    @Test fun `unchanged absent and disabled sides are noops`() {
        assertEquals(ChangeKind.UNCHANGED, policy.decide(null, null).kind)
        val off = limit(BreakLevel.HARDCORE, enabled = false)
        assertEquals(ChangeKind.UNCHANGED, policy.decide(off, off).kind)
    }

    @Test fun `raising enabled tier still requires the ordinary disclosure`() {
        assertEquals(Outcome.ALLOW_AFTER_DISCLOSURE,
            policy.decide(limit(BreakLevel.EASY), limit(BreakLevel.HARDCORE)).outcome)
    }

    @Test fun `legacy current occurrence keeps its rights but reenabling requires disclosure`() {
        val before = limit(BreakLevel.HARDCORE)
        assertEquals(Outcome.ALLOW, policy.decide(before, null, legacyRights = true).outcome)
        assertEquals(Outcome.ALLOW, policy.decide(before, before.copy(enabled = false), legacyRights = true).outcome)
        assertEquals(Outcome.ALLOW_AFTER_DISCLOSURE,
            policy.decide(before.copy(enabled = false), before, legacyRights = true).outcome)
    }
}
