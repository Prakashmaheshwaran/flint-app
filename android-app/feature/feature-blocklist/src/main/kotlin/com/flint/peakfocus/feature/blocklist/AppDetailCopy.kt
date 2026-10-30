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

/** A string-resource argument the screen resolves (for example a tier name). */
internal data class ResArg(@StringRes val id: Int)

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

/**
 * The headline restates the decision enforcement resolved; it never re-derives one from rows.
 * It names the strongest-tier cause and its tier, then the latest enforcing end, which can
 * belong to another cause.
 */
internal fun headlineLine(headline: AppDetailHeadline): DetailLine = when (headline) {
    AppDetailHeadline.Loading -> DetailLine(R.string.target_loading)
    AppDetailHeadline.Unavailable -> DetailLine(R.string.target_unavailable)
    AppDetailHeadline.SafetyFloor -> DetailLine(R.string.target_safety_floor)
    is AppDetailHeadline.Resolved -> when (val state = headline.state) {
        is AppDetailResolvedState.Paused -> DetailLine(R.string.target_paused_until, time = state.recoveryEndsAtWall)
        is AppDetailResolvedState.Blocked -> {
            val who = listOf(state.name, ResArg(tierName(state.tier)))
            val others = state.otherEnforcingCauses
            when {
                others <= 0 && state.ongoing -> DetailLine(R.string.target_blocked_ongoing, args = who)
                others <= 0 && state.untilWall != null -> DetailLine(R.string.target_blocked_until, args = who, time = state.untilWall)
                others <= 0 -> DetailLine(R.string.target_blocked, args = who)
                state.ongoing -> DetailLine(plural = R.plurals.target_blocked_more_ongoing, count = others, args = who + others)
                state.untilWall != null -> DetailLine(plural = R.plurals.target_blocked_more_until, count = others,
                    args = who + others, time = state.untilWall)
                else -> DetailLine(plural = R.plurals.target_blocked_more, count = others, args = who + others)
            }
        }
        AppDetailResolvedState.BlockedUnattributed -> DetailLine(R.string.target_blocked_unattributed)
        AppDetailResolvedState.CoveredNotBlocking -> DetailLine(R.string.target_covered_not_blocking)
        AppDetailResolvedState.CoverageUnconfirmed -> DetailLine(R.string.target_coverage_unconfirmed)
        AppDetailResolvedState.NotCovered -> DetailLine(R.string.target_not_covered)
    }
}

/** A covering row's status; unknown enforcement stays unknown, never "not blocking". */
internal fun causeStatusLine(cause: AppDetailCause): DetailLine = when {
    cause.enforcingNow == null -> DetailLine(R.string.target_cause_unknown)
    cause.enforcingNow && cause.endsAtWall != null -> DetailLine(R.string.target_cause_blocking_until, time = cause.endsAtWall)
    cause.enforcingNow -> DetailLine(R.string.target_cause_blocking)
    !cause.active -> DetailLine(R.string.target_cause_inactive)
    cause.endsAtWall != null -> DetailLine(R.string.target_cause_active_ends, time = cause.endsAtWall)
    else -> DetailLine(R.string.target_cause_active)
}

@StringRes
internal fun kindName(kind: AppDetailKind): Int = when (kind) {
    AppDetailKind.SESSION -> R.string.target_kind_session
    AppDetailKind.TIMER -> R.string.target_kind_timer
    AppDetailKind.SCHEDULE -> R.string.target_kind_schedule
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
    is AppDetailCreditLine.Used -> DetailLine(R.string.target_credits_used, args = listOf(line.used, line.cap))
    AppDetailCreditLine.Unavailable -> DetailLine(R.string.target_credits_unavailable)
}

/** Measured use only; an unmeasured day says so instead of showing zero. */
internal fun timeLimitLine(limit: AppDetailTimeLimit): DetailLine = when (val used = limit.usedMs) {
    null -> DetailLine(R.string.target_time_unmeasured, args = listOf(formatDailyMinutes(minutes(limit.quotaMs))))
    else -> DetailLine(R.string.target_time_used,
        args = listOf(formatDailyMinutes(minutes(used)), formatDailyMinutes(minutes(limit.quotaMs))))
}

private fun minutes(ms: Long): Int = (ms.coerceAtLeast(0) / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * Browser health for a site, stamped with the time of the check it comes from. A browser-specific
 * state without a recorded check says no check is available rather than sounding current.
 */
internal fun websiteCoverageLine(coverage: AppDetailWebCoverage, observedAtWall: Long?): DetailLine {
    val checked = when (coverage) {
        AppDetailWebCoverage.AVAILABLE -> R.string.target_web_available
        AppDetailWebCoverage.UNCONFIRMED -> R.string.target_web_unconfirmed
        AppDetailWebCoverage.DEGRADED -> R.string.target_web_degraded
        AppDetailWebCoverage.UNSUPPORTED -> R.string.target_web_unsupported
        AppDetailWebCoverage.NEEDS_ACCESSIBILITY -> return DetailLine(R.string.target_web_needs_accessibility)
        AppDetailWebCoverage.AWAITING_BROWSER -> return DetailLine(R.string.target_web_awaiting)
        AppDetailWebCoverage.NOT_REQUIRED -> return DetailLine(R.string.target_web_not_required)
    }
    return if (observedAtWall == null) DetailLine(R.string.target_web_no_check) else DetailLine(checked, time = observedAtWall)
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
