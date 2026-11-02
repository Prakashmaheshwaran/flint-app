package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RuleTargetMatcherTest {
    private val rule = BlockRule("rule", "Focus", BlockTargets(
        apps = setOf(AppRef("selected.app")), domains = setOf(DomainRef("WWW.Example.com.")),
    ))

    @Test fun appBlockAndAllowMembershipMatchTheStoredMode() {
        assertTrue(RuleTargetMatcher.matchesApp(rule, "selected.app"))
        assertFalse(RuleTargetMatcher.matchesApp(rule, "other.app"))
        val allowed = rule.copy(targets = rule.targets.copy(allowListMode = true))
        assertFalse(RuleTargetMatcher.matchesApp(allowed, "selected.app"))
        assertTrue(RuleTargetMatcher.matchesApp(allowed, "other.app"))
    }

    @Test fun inactiveOrExpiredTemplatesStillDescribeTheTargetsTheyCover() {
        val inactive = rule.copy(enabled = false, expiresAtEpochMs = 1,
            schedule = Schedule(startMinuteOfDay = 0, endMinuteOfDay = 0))
        assertTrue(RuleTargetMatcher.matchesApp(inactive, "selected.app"))
        assertTrue(RuleTargetMatcher.matchesSite(inactive, "example.com"))
    }

    @Test fun siteMembershipUsesExactEnforcementCanonicalizationAndSubdomainBoundaries() {
        val engine = BlockDecisionEngine()
        for (url in listOf("https://WWW.EXAMPLE.com./a", "sub.example.com", "badexample.com",
            "https://example.com@other.com", "example.com/search?q=https://other.com")) {
            val enforced = engine.decide("browser.app", url, listOf(rule), "flint") is Verdict.Block
            assertEquals(url, enforced, RuleTargetMatcher.matchesSite(rule, url))
        }
        assertEquals("example.com", RuleTargetMatcher.canonicalSite("HTTPS://WWW.Example.com.:443/path"))
    }

    @Test fun allowListDomainsAreIgnoredForSiteCoverage() {
        val allow = rule.copy(targets = rule.targets.copy(allowListMode = true))
        assertFalse(RuleTargetMatcher.matchesSite(allow, "example.com"))
    }

    @Test fun invalidSiteAndAppOnlyRulesDoNotInventWebsiteCoverage() {
        assertNull(RuleTargetMatcher.canonicalSite("not a browser address"))
        assertFalse(RuleTargetMatcher.matchesSite(rule, "not a browser address"))
        assertFalse(RuleTargetMatcher.matchesSite(rule.copy(targets = BlockTargets(apps = rule.targets.apps)), "example.com"))
    }
}
