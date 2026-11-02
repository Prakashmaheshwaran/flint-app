package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.engine.RuleTargetMatcher
import com.flint.peakfocus.blocking.engine.WebsiteRulePolicy
import com.flint.peakfocus.blocking.overlay.*
import com.flint.peakfocus.blocking.resilience.WebsiteCoverageHealth
import com.flint.peakfocus.core.data.LimitStore
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.protectionSnapshots
import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.datastore.projectedRulesForReadOrNull
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.StorageState
import com.flint.peakfocus.permissions.UsageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Failure is unknown, not an inactive website policy; the target projector reports its reason. */
internal fun targetDomainRulesActive(stored: ProtectionSnapshot?, foreign: List<BlockRule>,
    target: TargetRef, now: AccessClockStamp): Boolean {
    val rules = stored?.projectedRulesForReadOrNull(now)?.plus(foreign) ?: return true
    return rules.any { rule -> !rule.targets.allowListMode && rule.targets.domains.isNotEmpty() &&
        (target !is TargetRef.Site || RuleTargetMatcher.matchesSite(rule, target.domain)) &&
        (rule.runtime?.evaluation?.active ?:
            WebsiteRulePolicy.hasActiveDomains(listOf(rule), now.wallEpochMs, ZoneId.systemDefault())) }
}

/** Reuses the existing enforcement gate without creating a coordinator or starting enforcement. */
fun createTargetProtectionReader(context: Context, scope: CoroutineScope,
    coordinator: BlockScreenCoordinator): TargetProtectionReader =
    createTargetProtectionReader(context, scope, coordinator::readTargetFloorIdentity)

/**
 * Production read-only assembly. Pass the existing enforcement coordinator's retained floor
 * reader; this factory never creates a coordinator, starts a bridge, or migrates policy.
 * Keep one reader in the host scope. Only the requested app/current browser is measured.
 */
fun createTargetProtectionReader(context: Context, scope: CoroutineScope,
    readFloorIdentity: (String) -> FloorIdentity?): TargetProtectionReader {
    val app = context.applicationContext
    val legacy = LimitStore(app)
    data class Measurement(val at: AccessClockStamp, val period: Long?, val used: Long?)
    val samples = mutableMapOf<String, Measurement>()
    val sampling = Mutex()
    return ObservedTargetProtectionReader(FlintPreferences.protectionSnapshots(app), scope, app.packageName,
        environment = { target, state, now -> withContext(Dispatchers.IO) {
            val status = ProtectionStatusHub.status.value
            val stored = (state as? StorageState.Ready)?.value
            val foreign = ActiveRulesHolder.rulesOutside(ActiveRulesHolder.SOURCE_DATASTORE)
            val domainsActive = targetDomainRulesActive(stored, foreign, target, now)
            val observation = WebsiteCoverageHealth.read(domainsActive, status.detectors.accessibilityConnected, now.wallEpochMs)
            val web = observation.coverage
            val currentBrowser = observation.currentBrowser
            val browser = if (!currentBrowser) null else when (web) {
                is WebCoverage.Available -> web.browser.packageName
                is WebCoverage.Degraded -> web.browser.packageName
                is WebCoverage.Unconfirmed -> web.browser.packageName
                is WebCoverage.Unsupported -> web.packageName
                else -> null
            }
            val domainCoverage = observation.domainContext
            val coverage = when {
                web == WebCoverage.NotRequired -> TargetWebsiteCoverage.NOT_REQUIRED
                web == WebCoverage.Paused -> TargetWebsiteCoverage.NEEDS_ACCESSIBILITY
                domainCoverage is WebCoverage.Available -> TargetWebsiteCoverage.AVAILABLE
                domainCoverage is WebCoverage.Degraded -> TargetWebsiteCoverage.DEGRADED
                domainCoverage is WebCoverage.Unconfirmed -> TargetWebsiteCoverage.UNCONFIRMED
                web is WebCoverage.Unsupported -> TargetWebsiteCoverage.UNSUPPORTED
                else -> TargetWebsiteCoverage.AWAITING_BROWSER
            }
            val pkg = (target as? TargetRef.App)?.packageName ?: browser
            var legacyReadable = true
            val legacyMinutes = pkg?.let { runCatching { legacy.limitMinutes(it) }.getOrElse { legacyReadable = false; null } }
            val needsUsage = pkg != null && (stored?.times?.any { it.packageName == pkg && it.dailyMinutes > 0 } == true ||
                legacyMinutes?.let { it > 0 } == true)
            val measured = if (!needsUsage || !UsageAccess.isGranted(app)) null else sampling.withLock {
                val period = stored?.openCredits?.periodId
                val prior = samples[pkg]
                val sameBoot = prior?.at?.bootSessionId == now.bootSessionId &&
                    prior.at.elapsedRealtimeMs <= now.elapsedRealtimeMs
                if (sameBoot && prior!!.period == period && now.elapsedRealtimeMs - prior.at.elapsedRealtimeMs < 15_000) prior.used
                else {
                    val used = runCatching { UsageQuery.dailyForegroundMillisIfGranted(app, checkNotNull(pkg), now.wallEpochMs) }.getOrNull()
                    // A bounded cache only; no app inventory is persisted or returned.
                    if (samples.size >= 16) samples.clear()
                    samples[checkNotNull(pkg)] = Measurement(now, period, used)
                    used
                }
            }
            TargetReadEnvironment(status, foreign,
                pkg?.let(readFloorIdentity), browser, coverage, measured, legacyMinutes, legacyReadable,
                publishedRules = ActiveRulesHolder.dataStoreState, domainContextKnown = domainCoverage != null,
                websiteCoverageObservedAtWall = observation.observedAtWall,
                websiteCoverageValidUntilWall = observation.validUntilWall,
                websiteCoverageIsCurrent = currentBrowser && domainCoverage != null)
        } })
}
