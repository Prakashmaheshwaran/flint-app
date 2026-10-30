package com.flint.peakfocus.feature.blocklist

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.flint.peakfocus.core.model.BreakLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Resource choice for one App/Site detail sentence: [args] fill its placeholders in order, then
 * a formatted [time] if present; [count] selects a plural. Pure so every branch is JVM-testable.
 */
internal data class DetailLine(
    @StringRes val text: Int = 0,
    @PluralsRes val plural: Int = 0,
    val count: Int = 0,
    val args: List<Any> = emptyList(),
    val time: Long? = null,
)

/** The headline restates the decision enforcement resolved; it never re-derives one from rows. */
internal fun headlineLine(headline: AppDetailHeadline): DetailLine = when (headline) {
    AppDetailHeadline.Loading -> DetailLine(R.string.target_loading)
    AppDetailHeadline.Unavailable -> DetailLine(R.string.target_unavailable)
    AppDetailHeadline.SafetyFloor -> DetailLine(R.string.target_safety_floor)
    is AppDetailHeadline.Resolved -> when (val state = headline.state) {
        is AppDetailResolvedState.Paused -> DetailLine(R.string.target_paused_until, time = state.recoveryEndsAtWall)
        is AppDetailResolvedState.Blocked -> when {
            state.otherEnforcingCauses <= 0 && state.ongoing -> DetailLine(R.string.target_blocked_ongoing, args = listOf(state.name))
            state.otherEnforcingCauses <= 0 && state.untilWall != null ->
                DetailLine(R.string.target_blocked_until, args = listOf(state.name), time = state.untilWall)
            state.otherEnforcingCauses <= 0 -> DetailLine(R.string.target_blocked, args = listOf(state.name))
            state.ongoing -> DetailLine(plural = R.plurals.target_blocked_more_ongoing,
                count = state.otherEnforcingCauses, args = listOf(state.name, state.otherEnforcingCauses))
            state.untilWall != null -> DetailLine(plural = R.plurals.target_blocked_more_until,
                count = state.otherEnforcingCauses, args = listOf(state.name, state.otherEnforcingCauses), time = state.untilWall)
            else -> DetailLine(plural = R.plurals.target_blocked_more,
                count = state.otherEnforcingCauses, args = listOf(state.name, state.otherEnforcingCauses))
        }
        AppDetailResolvedState.CoveredNotBlocking -> DetailLine(R.string.target_covered_not_blocking)
        AppDetailResolvedState.CoverageUnconfirmed -> DetailLine(R.string.target_coverage_unconfirmed)
        AppDetailResolvedState.NotCovered -> DetailLine(R.string.target_not_covered)
    }
}

/** A covering row's status; unknown enforcement stays unknown, never "not blocking". */
internal fun causeStatusLine(cause: AppDetailCause): DetailLine = when {
    cause.enforcingNow == null -> DetailLine(R.string.target_cause_unknown)
    cause.enforcingNow && cause.ongoing -> DetailLine(R.string.target_cause_blocking_ongoing)
    cause.enforcingNow && cause.endsAtWall != null -> DetailLine(R.string.target_cause_blocking_until, time = cause.endsAtWall)
    cause.enforcingNow -> DetailLine(R.string.target_cause_blocking)
    !cause.active -> DetailLine(R.string.target_cause_inactive)
    cause.endsAtWall != null && !cause.ongoing -> DetailLine(R.string.target_cause_active_ends, time = cause.endsAtWall)
    else -> DetailLine(R.string.target_cause_active)
}

@StringRes
internal fun kindName(kind: AppDetailKind): Int = when (kind) {
    AppDetailKind.SESSION -> R.string.target_kind_session
    AppDetailKind.POMODORO -> R.string.target_kind_pomodoro
    AppDetailKind.SCHEDULE -> R.string.target_kind_schedule
    AppDetailKind.ONE_SHOT -> R.string.target_kind_one_shot
    AppDetailKind.SLEEP -> R.string.target_kind_sleep
    AppDetailKind.TIME_LIMIT -> R.string.target_kind_time_limit
    AppDetailKind.OPEN_LIMIT -> R.string.target_kind_open_limit
}

@StringRes
internal fun tierName(tier: BreakLevel): Int = when (tier) {
    BreakLevel.EASY -> R.string.target_tier_easy
    BreakLevel.HARDER -> R.string.target_tier_harder
    BreakLevel.HARDCORE -> R.string.target_tier_hardcore
}

/** "Used today" only from a readable counter; otherwise say the count isn't available. */
internal fun creditLine(line: AppDetailCreditLine): DetailLine = when (line) {
    is AppDetailCreditLine.Used -> DetailLine(R.string.target_opens_used, args = listOf(line.used, line.cap))
    AppDetailCreditLine.Unavailable -> DetailLine(R.string.target_opens_unavailable)
}

@StringRes
internal fun websiteCoverageText(coverage: AppDetailWebCoverage): Int = when (coverage) {
    AppDetailWebCoverage.AVAILABLE -> R.string.target_web_available
    AppDetailWebCoverage.UNCONFIRMED -> R.string.target_web_unconfirmed
    AppDetailWebCoverage.DEGRADED -> R.string.target_web_degraded
    AppDetailWebCoverage.UNSUPPORTED -> R.string.target_web_unsupported
    AppDetailWebCoverage.NEEDS_ACCESSIBILITY -> R.string.target_web_needs_accessibility
}

/** Today's times show as a clock time; other days add the weekday (mirrors iOS endText). */
internal fun detailTimeText(
    epochMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val time = at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return if (at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()) time
    else at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + time
}
