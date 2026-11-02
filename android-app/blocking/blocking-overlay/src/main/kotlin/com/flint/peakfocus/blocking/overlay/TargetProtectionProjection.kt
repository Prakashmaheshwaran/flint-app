package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.ZoneId

/** Target measurements and process-local observations; no mutation authority or URL history. */
data class TargetReadEnvironment(
    val status: ProtectionStatus = ProtectionStatus.UNKNOWN,
    val foreignRules: List<BlockRule> = emptyList(),
    /** Retained provenance from the existing enforcement coordinator's gate. */
    val floor: FloorIdentity? = null,
    val browserPackageName: String? = null,
    val websiteCoverage: TargetWebsiteCoverage = TargetWebsiteCoverage.AWAITING_BROWSER,
    val measuredUsageMs: Long? = null,
    val legacyTimeMinutes: Int? = null,
    val legacyTimeReadable: Boolean = true,
    val unavailableReason: String? = null,
    /** Current enforcement publication, distinct from a successfully saved template. */
    val publishedRules: StorageState<List<BlockRule>>? = null,
    /** Fresh retained supported-browser context may establish domain attribution, never app scope. */
    val domainContextKnown: Boolean = false,
    val websiteCoverageObservedAtWall: Long? = null,
    val websiteCoverageValidUntilWall: Long? = null,
    val websiteCoverageIsCurrent: Boolean = false,
)

/** One pure projection, using enforcement's clock, membership, end and recovery policies. */
internal object TargetProtectionProjection {
    private const val ACTIVITY_PROBE = "flint.target.activity.probe"
    private val engine = BlockDecisionEngine()
    private data class RuntimeIdentity(val occurrence: String, val remaining: Long?, val wallEnd: Long?,
        val zone: String, val stopAtEnd: Boolean, val anchor: AccessClockStamp?, val timeline: AccessClockStamp?)
    private fun definition(rule: BlockRule) = rule.copy(
        expiresAtEpochMs = if (rule.runtime != null) null else rule.expiresAtEpochMs, runtime = null) to
        rule.runtime?.let { RuntimeIdentity(it.occurrenceId, it.remainingAtAnchorMs, it.wallEndEpochMs,
            it.zoneId, it.stopAtOccurrenceEnd, it.anchor.takeIf { _ -> it.remainingAtAnchorMs != null },
            it.timeline.takeIf { _ -> it.remainingAtAnchorMs != null && rule.schedule != null }) }

    private fun floor(target: TargetRef, environment: TargetReadEnvironment, selfPackage: String): FloorIdentity? =
        if (target is TargetRef.App) when {
            target.packageName == selfPackage -> FloorIdentity(FloorIdentity.Kind.SELF)
            SafetyFloor.isProtected(target.packageName, selfPackage) -> FloorIdentity(FloorIdentity.Kind.STATIC_SYSTEM)
            else -> environment.floor
        } else null

    private fun domainFresh(environment: TargetReadEnvironment, now: AccessClockStamp?): Boolean {
        val observed = environment.websiteCoverageObservedAtWall ?: return false
        val validUntil = environment.websiteCoverageValidUntilWall ?: return false
        return environment.domainContextKnown && now != null &&
            now.wallEpochMs >= observed && now.wallEpochMs < validUntil
    }

    private fun websiteCoverage(environment: TargetReadEnvironment, now: AccessClockStamp?): TargetWebsiteCoverage =
        if ((environment.domainContextKnown || environment.websiteCoverage == TargetWebsiteCoverage.AVAILABLE) &&
            !domainFresh(environment, now)) TargetWebsiteCoverage.AWAITING_BROWSER else environment.websiteCoverage

    private fun TargetProtection.withWebsiteObservation(target: TargetRef, environment: TargetReadEnvironment,
        now: AccessClockStamp?): TargetProtection = if (target !is TargetRef.Site) this else copy(
        websiteCoverageObservedAtWall = environment.websiteCoverageObservedAtWall,
        websiteCoverageValidUntilWall = environment.websiteCoverageValidUntilWall,
        websiteCoverageIsCurrent = environment.websiteCoverageIsCurrent && domainFresh(environment, now))

    fun unavailable(target: TargetRef, environment: TargetReadEnvironment, selfPackage: String, reason: String,
        now: AccessClockStamp?): TargetProtection {
        val known = floor(target, environment, selfPackage)
        return TargetProtection(Readiness.Unavailable(reason), floor = known, blockingNow = known?.let { false },
            websiteCoverage = websiteCoverage(environment, now).takeIf { target is TargetRef.Site })
            .withWebsiteObservation(target, environment, now)
    }

    fun read(target: TargetRef, state: StorageState<ProtectionSnapshot>, environment: TargetReadEnvironment,
        now: AccessClockStamp, selfPackage: String, legacyZone: ZoneId): TargetProtection {
        val pkg = when (target) {
            is TargetRef.App -> target.packageName
            is TargetRef.Site -> environment.browserPackageName
        }
        val floor = floor(target, environment, selfPackage)
        val domainFresh = domainFresh(environment, now)
        val web = websiteCoverage(environment, now).takeIf { target is TargetRef.Site }
        fun unavailable(reason: String) = finish(Readiness.Unavailable(reason), floor, emptyList(), now, web = web)
            .withWebsiteObservation(target, environment, now)
        val site = (target as? TargetRef.Site)?.let { RuleTargetMatcher.canonicalSite(it.domain)
            ?: return unavailable("invalid-site-target") }
        if (target is TargetRef.App && (target.packageName.isBlank() || target.packageName != target.packageName.trim())) {
            return unavailable("invalid-app-target")
        }
        when (state) {
            StorageState.Loading -> return finish(Readiness.Loading, floor, emptyList(), now, web = web)
                .withWebsiteObservation(target, environment, now)
            is StorageState.Unavailable -> return unavailable("policy-storage-${state.reason.name.lowercase()}")
            is StorageState.Ready -> Unit
        }
        environment.unavailableReason?.let { return unavailable(it) }
        val stored = state.value
        val authored = stored.projectedRulesForReadOrNull(now) ?: return unavailable("rule-occurrence-unavailable")
        val ownIds = authored.mapTo(HashSet()) { it.id }
        val foreign = environment.foreignRules.filter { it.id !in ownIds }
        val projected = authored + runCatching { RuleClockProjection.project(foreign, now) }.getOrElse {
            return unavailable("foreign-rule-occurrence-unavailable")
        }
        val local = Instant.ofEpochMilli(now.wallEpochMs).atZone(legacyZone)
        val minute = local.hour * 60 + local.minute
        val published = (environment.publishedRules as? StorageState.Ready)?.value
        // An unanchored always-on occurrence gets a synthetic caller-now anchor. Compare
        // durable identity, not that per-read value or a projected evaluation timestamp.
        val publicationUnknown = published == null || authored.map(::definition).sortedBy { it.first.id } !=
            published.map(::definition).sortedBy { it.first.id }
        val healthUnknown = TargetRuntimeHealth.unavailableReason(environment.status, now) != null ||
            !environment.status.safetyFloorResolved || publicationUnknown ||
            environment.status.observedAt?.let { trustedElapsedMs(stored.observedAt, it) } == null
        val browserFloor = target is TargetRef.Site && environment.floor != null
        val rows = projected.mapNotNull { rule ->
            val appMatch = pkg != null && RuleTargetMatcher.matchesApp(rule, pkg)
            val domainMatch = site != null && RuleTargetMatcher.matchesSite(rule, site)
            if (!appMatch && !domainMatch) return@mapNotNull null
            // Probe only activity, independently of membership/floor, through the exact engine.
            val active = engine.decide(ACTIVITY_PROBE, null,
                listOf(rule.copy(targets = BlockTargets(apps = setOf(AppRef(ACTIVITY_PROBE))))), selfPackage,
                minute, local.dayOfWeek.value, now.wallEpochMs) is Verdict.Block
            val runtime = rule.runtime
            val end = if (active) ruleWindowEnd(rule, now.wallEpochMs, minute) else null
            val next = runtime?.evaluation?.nextBoundaryEpochMs ?: if (!rule.enabled) null else
                if (active) end else rule.schedule?.let { ScheduleOccurrences.nextStart(it, legacyZone, now.wallEpochMs) }
            val occurrence = runtime?.let {
                val remaining = it.remainingAtAnchorMs
                val wallEnd = it.wallEndEpochMs
                when {
                    it.occurrenceId == "inactive" -> null
                    remaining == null -> it.occurrenceId
                    wallEnd == null -> null
                    AnchoredInterval(it.anchor, remaining, wallEnd).remainingMs(now) == 0L -> null
                    else -> it.occurrenceId
                }
            }
            CoveringCause(rule.id, ProtectionStatus.sanitizeDisplayName(rule.name), kind(rule), rule.breakLevel,
                active, end, runtime?.let { clockDiscontinuity(it.anchor, now) } ?: true, next,
                when {
                    !active || floor != null || browserFloor -> false
                    healthUnknown -> null
                    target is TargetRef.Site && !appMatch &&
                        (web != TargetWebsiteCoverage.AVAILABLE || pkg == null && !domainFresh) -> null
                    else -> true
                }, if (rule.id in ownIds) ruleCauseKey(rule.id) else "foreign-rule:${rule.id}", occurrence)
        }
        val limits = pkg?.let { TargetLimitProjection.app(stored, it, now, environment.measuredUsageMs, environment.legacyTimeMinutes) }
            ?: TargetLimitFacts(emptyList(), null, null, null)
        val limitRows = limits.causes.map { it.copy(enforcingNow = when {
            floor != null || browserFloor -> false
            it.enforcingNow == false -> false
            healthUnknown -> null
            else -> it.enforcingNow
        }) }
        val all = rows + limitRows
        val limitDispositionKnown = limits.causes.none { it.enforcingNow == null } && environment.legacyTimeReadable
        val input = pkg?.let { OrdinaryProtectionInput(it, selfPackage,
            limits.causes.any { c -> c.kind == CauseKind.TIME_LIMIT && c.enforcingNow == true },
            limits.causes.any { c -> c.kind == CauseKind.OPEN_LIMIT && c.enforcingNow == true }, site,
            limits.causes.firstOrNull { c -> c.causeKey.startsWith("legacy-time:") }?.endsAtWall) }
        val recovery = TargetRecoveryProjection.read(stored, now, input, foreign, limitDispositionKnown)
        val reason = limits.unavailableReason ?: if (!environment.legacyTimeReadable) "legacy-limit-state-unavailable"
            else recovery.unavailableReason
        val credit = if (target !is TargetRef.App || floor != null) null else TargetCreditProjection.read(limits.openCredit, stored, input, foreign, now,
            !healthUnknown, limitDispositionKnown, reason, all, recovery.endsAtWall)
        val readiness = reason?.let { Readiness.Unavailable(it) } ?: Readiness.Ready
        val released = if (recovery.endsAtWall != null || floor != null || browserFloor)
            all.map { it.copy(enforcingNow = false) } else all
        val base = finish(readiness, floor, released, now, recovery.endsAtWall,
            credit,
            limits.timeLimit.takeIf { target is TargetRef.App && floor == null }, web)
        val result = base.withWebsiteObservation(target, environment, now).copy(
                nextChangeAtWall = listOfNotNull(base.nextChangeAtWall,
                    credit?.activeCreditEndsAtWall,
                    environment.websiteCoverageValidUntilWall.takeIf { target is TargetRef.Site && domainFresh })
                    .filter { it > now.wallEpochMs }.minOrNull())
        // Without a browser context, browser-app contributors cannot be excluded for a Site.
        return if ((healthUnknown || target is TargetRef.Site && pkg == null) && floor == null && !browserFloor &&
            result.recoveryEndsAtWall == null && result.blockingNow != true && readiness == Readiness.Ready)
            result.copy(blockingNow = null) else if (target is TargetRef.Site && pkg == null && result.blockingNow == true)
            result.copy(blockingUntilWall = null) else result // Browser-app contributors remain unproven.
    }

    private fun kind(rule: BlockRule): CauseKind = when {
        rule.id.startsWith("sleep-") -> CauseKind.SLEEP
        rule.schedule != null -> CauseKind.SCHEDULE
        rule.id.startsWith("session-timer-") -> CauseKind.TIMER
        rule.expiresAtEpochMs != null && !rule.id.startsWith(BlockRule.SESSION_ID_PREFIX) -> CauseKind.SCHEDULE
        else -> CauseKind.SESSION
    }

    private fun finish(readiness: Readiness, floor: FloorIdentity?, causes: List<CoveringCause>, now: AccessClockStamp,
        recovery: Long? = null, open: OpenCreditView? = null, time: TimeLimitView? = null,
        web: TargetWebsiteCoverage? = null): TargetProtection {
        val resolved = TargetProtectionReduction.resolve(readiness, floor, causes, now.wallEpochMs, recovery)
        return TargetProtection(readiness, floor, causes, open, time, resolved.nextChangeAtWall,
            resolved.blockingNow, resolved.blockingUntilWall, resolved.blockingOngoing,
            resolved.recoveryEndsAtWall, resolved.headline, web)
    }
}
