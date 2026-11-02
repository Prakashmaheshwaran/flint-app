package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule

/** Read-only target membership, independent of whether its commitment is active right now. */
object RuleTargetMatcher {
    private val engine = BlockDecisionEngine()

    /** Safety-floor disposition is separate so a reader can still describe a legacy selection. */
    fun matchesApp(rule: BlockRule, packageName: String): Boolean =
        rule.targets.apps.any { it.packageName == packageName }.let { selected ->
            if (rule.targets.allowListMode) !selected else selected
        }

    /** Same host/subdomain parser as enforcement; allow-list websites remain unsupported. */
    fun matchesSite(rule: BlockRule, domain: String): Boolean =
        !rule.targets.allowListMode && rule.targets.domains.any { engine.domainMatches(domain, it.domain) }

    fun canonicalSite(raw: String): String? = HostCanonicalizer.canonicalize(raw)
}
