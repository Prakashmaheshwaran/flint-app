package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.overlay.CauseKind
import com.flint.peakfocus.blocking.overlay.Readiness
import com.flint.peakfocus.blocking.overlay.TargetProtection
import com.flint.peakfocus.blocking.overlay.TargetRef
import com.flint.peakfocus.blocking.overlay.TargetWebsiteCoverage

internal fun AppDetailTarget.toTargetRef(): TargetRef = when (this) {
    is AppDetailTarget.App -> TargetRef.App(packageName)
    is AppDetailTarget.Site -> TargetRef.Site(domain)
}

/**
 * Copies lane C's resolved summary. [TargetProtection.blockingNow] decides blocked/unknown/not;
 * the headline cause is the strongest-tier authority, while [TargetProtection.blockingUntilWall]
 * is the latest enforcing end, which can belong to another cause. Rows are attribution only.
 */
internal fun appDetailSnapshot(protection: TargetProtection): AppDetailSnapshot {
    val readiness = when (protection.readiness) {
        Readiness.Loading -> AppDetailReadiness.LOADING
        Readiness.Ready -> AppDetailReadiness.READY
        is Readiness.Unavailable -> AppDetailReadiness.UNAVAILABLE
    }
    val causes = protection.coveringCauses.map { cause ->
        AppDetailCause(
            causeKey = cause.causeKey,
            occurrenceId = cause.occurrenceId,
            ruleId = cause.ruleId,
            name = cause.name,
            kind = when (cause.kind) {
                CauseKind.SESSION -> AppDetailKind.SESSION
                CauseKind.TIMER -> AppDetailKind.TIMER
                CauseKind.SCHEDULE -> AppDetailKind.SCHEDULE
                CauseKind.SLEEP -> AppDetailKind.SLEEP
                CauseKind.TIME_LIMIT -> AppDetailKind.TIME_LIMIT
                CauseKind.OPEN_LIMIT -> AppDetailKind.OPEN_LIMIT
            },
            tier = cause.tier,
            active = cause.active,
            enforcingNow = cause.enforcingNow,
            endsAtWall = cause.endsAtWall,
            timeUncertain = cause.timeUncertain,
        )
    }
    val headline = protection.headline
    val recoveryEnds = protection.recoveryEndsAtWall
    val resolved = when {
        readiness != AppDetailReadiness.READY -> null
        protection.blockingNow == true && headline != null -> AppDetailResolvedState.Blocked(
            name = headline.name,
            tier = headline.tier,
            // Display count only: the decision and its end come from the reader.
            otherEnforcingCauses = (causes.count { it.enforcingNow == true } - 1).coerceAtLeast(0),
            untilWall = protection.blockingUntilWall,
            ongoing = protection.blockingOngoing,
        )
        protection.blockingNow == true -> AppDetailResolvedState.BlockedUnattributed
        // Unknown enforcement stays unknown, even while a recovery interval is running.
        protection.blockingNow == null -> AppDetailResolvedState.CoverageUnconfirmed
        recoveryEnds != null -> AppDetailResolvedState.Paused(recoveryEnds)
        causes.isNotEmpty() -> AppDetailResolvedState.CoveredNotBlocking
        else -> AppDetailResolvedState.NotCovered
    }
    return AppDetailSnapshot(
        readiness = readiness,
        resolved = resolved,
        causes = causes,
        credits = protection.openCredit?.let {
            AppDetailCredits(it.readable, it.used, it.cap, it.activeCreditEndsAtWall)
        },
        safetyFloor = protection.floor != null,
        nextChangeAtWall = protection.nextChangeAtWall,
        websiteCoverage = protection.websiteCoverage?.let {
            when (it) {
                TargetWebsiteCoverage.AVAILABLE -> AppDetailWebCoverage.AVAILABLE
                TargetWebsiteCoverage.UNCONFIRMED -> AppDetailWebCoverage.UNCONFIRMED
                TargetWebsiteCoverage.DEGRADED -> AppDetailWebCoverage.DEGRADED
                TargetWebsiteCoverage.UNSUPPORTED -> AppDetailWebCoverage.UNSUPPORTED
                TargetWebsiteCoverage.NEEDS_ACCESSIBILITY -> AppDetailWebCoverage.NEEDS_ACCESSIBILITY
                TargetWebsiteCoverage.AWAITING_BROWSER -> AppDetailWebCoverage.AWAITING_BROWSER
                TargetWebsiteCoverage.NOT_REQUIRED -> AppDetailWebCoverage.NOT_REQUIRED
            }
        },
        timeLimit = protection.timeLimit?.let {
            AppDetailTimeLimit(it.quotaMs, if (it.measurable) it.usedMs else null, it.tier)
        },
    )
}
