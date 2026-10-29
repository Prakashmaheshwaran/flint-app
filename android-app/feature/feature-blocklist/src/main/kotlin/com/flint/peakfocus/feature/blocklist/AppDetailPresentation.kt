package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.flow.Flow

/**
 * Presentation boundary pending lane C's per-target reader. No implementation reads rule
 * stores or decides coverage here. The production adapter must copy C's resolved headline,
 * end, recovery and counter readability; an aggregate health snapshot cannot supply them.
 */
internal interface AppDetailReader {
    fun observe(target: AppDetailTarget): Flow<AppDetailSnapshot>
}

internal sealed interface AppDetailTarget {
    data class App(val packageName: String) : AppDetailTarget
    data class Site(val domain: String) : AppDetailTarget
}

internal enum class AppDetailReadiness { LOADING, READY, UNAVAILABLE }
internal enum class AppDetailKind { SESSION, POMODORO, SCHEDULE, ONE_SHOT, SLEEP, TIME_LIMIT, OPEN_LIMIT }
internal enum class AppDetailWebCoverage { AVAILABLE, UNCONFIRMED, DEGRADED, UNSUPPORTED, NEEDS_ACCESSIBILITY }

/** This decision is supplied by enforcement, including its tie-break and latest end. */
internal sealed interface AppDetailResolvedState {
    data class Paused(val recoveryEndsAtWall: Long) : AppDetailResolvedState
    data class Blocked(
        val name: String,
        val tier: BreakLevel,
        val otherEnforcingCauses: Int,
        val untilWall: Long?,
        val ongoing: Boolean,
    ) : AppDetailResolvedState
    data object CoveredNotBlocking : AppDetailResolvedState
    data object CoverageUnconfirmed : AppDetailResolvedState
    data object NotCovered : AppDetailResolvedState
}

internal data class AppDetailCause(
    val causeKey: String,
    val occurrenceId: String,
    val ruleId: String?,
    val name: String,
    val kind: AppDetailKind,
    val tier: BreakLevel,
    val active: Boolean,
    val enforcingNow: Boolean?,
    val endsAtWall: Long?,
    val ongoing: Boolean = false,
    val timeUncertain: Boolean = false,
)

internal data class AppDetailCredits(val readable: Boolean, val used: Int?, val cap: Int?)

internal data class AppDetailSnapshot(
    val readiness: AppDetailReadiness,
    val resolved: AppDetailResolvedState?,
    val causes: List<AppDetailCause> = emptyList(),
    val credits: AppDetailCredits? = null,
    val safetyFloor: Boolean = false,
    val nextChangeAtWall: Long? = null,
    val websiteCoverage: AppDetailWebCoverage? = null,
)

internal sealed interface AppDetailHeadline {
    data object Loading : AppDetailHeadline
    data object Unavailable : AppDetailHeadline
    data object SafetyFloor : AppDetailHeadline
    data class Resolved(val state: AppDetailResolvedState) : AppDetailHeadline
}

internal sealed interface AppDetailCreditLine {
    data class Used(val used: Int, val cap: Int) : AppDetailCreditLine
    data object Unavailable : AppDetailCreditLine
}

internal data class AppDetailPresentation(
    val headline: AppDetailHeadline,
    val rows: List<AppDetailCause>,
    val credits: AppDetailCreditLine?,
    val showsSafetyFloorNote: Boolean,
    val nextChangeAtWall: Long?,
    val websiteCoverage: AppDetailWebCoverage? = null,
)

/** Orders attribution for display; it never computes the blocking decision from these rows. */
internal fun appDetailPresentation(input: AppDetailSnapshot): AppDetailPresentation {
    if (input.readiness != AppDetailReadiness.READY || input.resolved == null) {
        return AppDetailPresentation(
            headline = if (input.safetyFloor) AppDetailHeadline.SafetyFloor
                else if (input.readiness == AppDetailReadiness.LOADING) AppDetailHeadline.Loading
                else AppDetailHeadline.Unavailable,
            rows = emptyList(), credits = null,
            showsSafetyFloorNote = input.safetyFloor, nextChangeAtWall = null,
        )
    }
    if (input.safetyFloor) return AppDetailPresentation(
        AppDetailHeadline.SafetyFloor, emptyList(), null, true, null,
    )
    val rows = input.causes.sortedWith(
        compareByDescending<AppDetailCause> { it.enforcingNow == true }
            .thenByDescending { when (it.tier) {
                BreakLevel.EASY -> 0
                BreakLevel.HARDER -> 1
                BreakLevel.HARDCORE -> 2
            } }
            .thenByDescending { if (it.ongoing) Long.MAX_VALUE else it.endsAtWall ?: Long.MIN_VALUE }
            .thenBy { it.causeKey }
            .thenBy { it.occurrenceId },
    )
    val credits = input.credits?.let {
        if (it.readable && it.used != null && it.used >= 0 && it.cap != null && it.cap >= 0)
            AppDetailCreditLine.Used(it.used, it.cap)
        else AppDetailCreditLine.Unavailable
    }
    return AppDetailPresentation(AppDetailHeadline.Resolved(input.resolved), rows, credits, false,
        input.nextChangeAtWall, input.websiteCoverage)
}
