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
)

/** One pure projection, using enforcement's clock, membership, end and recovery policies. */
internal object TargetProtectionProjection {
    private const val ACTIVITY_PROBE = "flint.target.activity.probe"
    private val engine = BlockDecisionEngine()

    fun read(target: TargetRef, state: StorageState<ProtectionSnapshot>, environment: TargetReadEnvironment,
        now: AccessClockStamp, selfPackage: String, legacyZone: ZoneId): TargetProtection {
        val pkg = when (target) {
            is TargetRef.App -> target.packageName
            is TargetRef.Site -> environment.browserPackageName
        }
        val floor = if (target is TargetRef.App) when {
            target.packageName == selfPackage -> FloorIdentity(FloorIdentity.Kind.SELF)
            SafetyFloor.isProtected(target.packageName, selfPackage) -> FloorIdentity(FloorIdentity.Kind.STATIC_SYSTEM)
            else -> environment.floor
        } else null
        val web = environment.websiteCoverage.takeIf { target is TargetRef.Site }
        fun unavailable(reason: String) = finish(Readiness.Unavailable(reason), floor, emptyList(), now, web = web)
        val site = (target as? TargetRef.Site)?.let { RuleTargetMatcher.canonicalSite(it.domain)
            ?: return unavailable("invalid-site-target") }
        if (target is TargetRef.App && (target.packageName.isBlank() || target.packageName != target.packageName.trim())) {
            return unavailable("invalid-app-target")
        }
        when (state) {
            StorageState.Loading -> return finish(Readiness.Loading, floor, emptyList(), now, web = web)
            is StorageState.Unavailable -> return unavailable("policy-storage-${state.reason.name.lowercase()}")
            is StorageState.Ready -> Unit
        }
        environment.unavailableReason?.let { return unavailable(it) }
        val stored = state.value
        val authored = runCatching { stored.projectedRulesForRead(now) }.getOrElse {
            return unavailable("rule-occurrence-unavailable")
        }
        val ownIds = authored.mapTo(HashSet()) { it.id }
        val foreign = environment.foreignRules.filter { it.id !in ownIds }
        val projected = authored + runCatching { RuleClockProjection.project(foreign, now) }.getOrElse {
            return unavailable("foreign-rule-occurrence-unavailable")
        }
        val local = Instant.ofEpochMilli(now.wallEpochMs).atZone(legacyZone)
        val minute = local.hour * 60 + local.minute
        val healthUnknown = TargetRuntimeHealth.unavailableReason(environment.status, now) != null ||
            !environment.status.safetyFloorResolved
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
            val occurrence = runtime?.occurrenceId?.takeUnless { it == "inactive" }?.takeUnless {
                runtime.remainingAtAnchorMs?.let { remaining ->
                    runtime.wallEndEpochMs?.let { end -> AnchoredInterval(runtime.anchor, remaining, end).remainingMs(now) == 0L } != false
                } == true
            }
            CoveringCause(rule.id, ProtectionStatus.sanitizeDisplayName(rule.name), kind(rule), rule.breakLevel,
                active, end, runtime?.let { clockDiscontinuity(it.anchor, now) } ?: true, next,
                when {
                    !active || floor != null || browserFloor -> false
                    healthUnknown -> null
                    target is TargetRef.Site && (!appMatch && web != TargetWebsiteCoverage.AVAILABLE || pkg == null) -> null
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
        val readiness = reason?.let { Readiness.Unavailable(it) } ?: Readiness.Ready
        val released = if (recovery.endsAtWall != null || floor != null || browserFloor)
            all.map { it.copy(enforcingNow = false) } else all
        val result = finish(readiness, floor, released, now, recovery.endsAtWall,
            limits.openCredit.takeIf { target is TargetRef.App && floor == null },
            limits.timeLimit.takeIf { target is TargetRef.App && floor == null }, web)
        // Without a browser context, browser-app contributors cannot be excluded for a Site.
        return if (target is TargetRef.Site && pkg == null && result.recoveryEndsAtWall == null &&
            result.blockingNow != true && readiness == Readiness.Ready) result.copy(blockingNow = null) else result
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
