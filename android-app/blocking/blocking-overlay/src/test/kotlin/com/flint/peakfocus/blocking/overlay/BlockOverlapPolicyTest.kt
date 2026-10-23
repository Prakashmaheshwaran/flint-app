package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.BreakPolicy
import com.flint.peakfocus.blocking.engine.OpenLimitPolicy
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakDecision
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision
import com.flint.peakfocus.core.model.Verdict
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Regression scenarios for the common Path A/Path B cause and exemption decision. */
class BlockOverlapPolicyTest {
    private val pkg = "com.example.distraction"
    private val now = 10_000L
    private val strictRule = BlockRule(
        "session", "Focus", BlockTargets(apps = setOf(AppRef(pkg))),
        breakLevel = BreakLevel.HARDCORE,
    )

    @Before fun prepare() = BlockExemptions.clearAll()
    @After fun cleanup() = BlockExemptions.clearAll()

    private fun ruleCause(rule: BlockRule, url: String? = null, at: Long = now): BlockCause? {
        val verdict = BlockDecisionEngine().decide(pkg, url, listOf(rule), "com.flint", 600, 1, at)
        return if (verdict is Verdict.Block) ruleBlockCause(rule, at, 0) else null
    }

    private fun effective(vararg causes: BlockCause?, at: Long = now): BlockCause? =
        BlockOverlapPolicy.afterExemptions(
            BlockOverlapPolicy.strongest(*causes),
            BlockExemptions.hasOrdinaryExemption(pkg, at),
            BlockExemptions.hasEmergencyExemption(pkg, at),
        )

    @Test fun `exhausted easy time budget cannot offer a break through a hardcore session`() {
        val easyTime = timeLimitBlockCause(BreakLevel.EASY, now, 0)
        val cause = effective(easyTime, ruleCause(strictRule))!!
        assertEquals(BlockScreenReason.DEEP_FOCUS, cause.reason)
        assertEquals(BreakDecision.DeniedHardcore, BreakPolicy().requestBreak(cause.breakLevel, BreakSessionState(), now))
    }

    @Test fun `hardcore open limit wins over weaker time budget and rule without charging an attempt`() {
        val counts = OpenCountState(0, mapOf(pkg to 2))
        val transition = OpenLimitPolicy().onForegroundTransition(
            "com.launcher", pkg, "com.flint", counts, null,
            listOf(OpenLimit(pkg, 2, BreakLevel.HARDCORE)), 0,
            recordAllowedOpen = false,
        )
        val blocked = transition.decision as OpenLimitDecision.Blocked
        val cause = effective(
            timeLimitBlockCause(BreakLevel.EASY, now, 0),
            ruleCause(strictRule.copy(breakLevel = BreakLevel.HARDER)),
            openLimitBlockCause(blocked.limit, now, 0),
        )!!
        assertEquals(BlockScreenReason.OPEN_LIMIT, cause.reason)
        assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
        assertEquals(counts, transition.counts)
    }

    @Test fun `stronger time budget wins over easy rule and equal tiers retain time display priority`() {
        val harderTime = timeLimitBlockCause(BreakLevel.HARDER, now, 0)
        assertEquals(harderTime, effective(harderTime, ruleCause(strictRule.copy(breakLevel = BreakLevel.EASY))))
        assertEquals(harderTime, effective(harderTime, ruleCause(strictRule.copy(breakLevel = BreakLevel.HARDER))))
    }

    @Test fun `ordinary grant issued before a strict session cannot suppress the newly active cause`() {
        BlockExemptions.grant(pkg, now + 300_000)
        assertNull(effective(timeLimitBlockCause(BreakLevel.EASY, now, 0)))
        assertEquals(BreakLevel.HARDCORE, effective(timeLimitBlockCause(BreakLevel.EASY, now, 0), ruleCause(strictRule))?.breakLevel)
    }

    @Test fun `website and allow-list hardcore rules survive an ordinary browser exemption`() {
        BlockExemptions.grant(pkg, now + 300_000)
        val website = strictRule.copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))
        assertNotNull(effective(ruleCause(website, "https://www.example.com/feed")))
        val allowList = strictRule.copy(targets = BlockTargets(apps = setOf(AppRef("com.allowed")), allowListMode = true))
        assertNotNull(effective(ruleCause(allowList)))
    }

    @Test fun `stale easy action is denied when current rules now require hardcore`() {
        val displayed = ruleCause(strictRule.copy(breakLevel = BreakLevel.EASY))
        val revalidated = BlockOverlapPolicy.strongest(ruleCause(strictRule), displayed)!!
        val pending = BreakSessionState(breaksTaken = 1)
        assertEquals(BreakDecision.DeniedHardcore, BreakPolicy().requestBreak(revalidated.breakLevel, pending, now))
    }

    @Test fun `only emergency grant lifts hardcore and a longer ordinary grant cannot extend it`() {
        BlockExemptions.grant(pkg, now + 300_000)
        BlockExemptions.grantEmergency(pkg, now + 1_000)
        assertNull(effective(ruleCause(strictRule)))
        assertNotNull(effective(ruleCause(strictRule), at = now + 1_000))
        assertNull(effective(ruleCause(strictRule.copy(breakLevel = BreakLevel.EASY)), at = now + 1_000))
    }

    @Test fun `ordinary grant is usable again after the strict restriction actually expires`() {
        BlockExemptions.grant(pkg, now + 300_000)
        val timed = strictRule.copy(expiresAtEpochMs = now + 1_000)
        assertNotNull(effective(ruleCause(timed)))
        assertNull(effective(timeLimitBlockCause(BreakLevel.EASY, now, 0), ruleCause(timed, at = now + 1_000), at = now + 1_000))
    }

    @Test fun `emergency grant never suppresses uninstall guard`() {
        BlockExemptions.grantEmergency(pkg, now + 1_000)
        val guard = uninstallGuardBlockCause(strictRule, now, 0)
        assertEquals(guard, effective(timeLimitBlockCause(BreakLevel.HARDCORE, now, 0), ruleCause(strictRule), guard))
    }
}
