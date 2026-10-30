package com.flint.peakfocus.core.history

/** Possible placement and independently known maximum length, never a fabricated interval. */
data class UncertainFocusGap(val window: FocusInterval, val maximumMs: Long)

internal object FocusHistoryBounds {
    fun duration(intervals: List<FocusInterval>): Long = intervals.fold(0L) { total, interval ->
        val length = if (interval.end <= interval.start) 0 else try {
            Math.subtractExact(interval.end, interval.start)
        } catch (_: ArithmeticException) { Long.MAX_VALUE }
        add(total, length)
    }

    private fun add(a: Long, b: Long) = try { Math.addExact(a, b) }
        catch (_: ArithmeticException) { Long.MAX_VALUE }

    fun subtract(intervals: List<FocusInterval>, cut: FocusInterval): List<FocusInterval> {
        if (cut.end <= cut.start) return intervals
        return intervals.flatMap { interval ->
            if (cut.end <= interval.start || cut.start >= interval.end) listOf(interval)
            else listOf(FocusInterval(interval.start, maxOf(interval.start, cut.start)),
                FocusInterval(minOf(interval.end, cut.end), interval.end)).filter { it.end > it.start }
        }
    }

    private fun intersect(intervals: List<FocusInterval>, window: FocusInterval) = intervals.mapNotNull {
        val a = maxOf(it.start, window.start); val b = minOf(it.end, window.end)
        if (b > a) FocusInterval(a, b) else null
    }

    fun lowerBound(sessions: List<FocusSession>, window: FocusInterval? = null): Long {
        fun clipped(intervals: List<FocusInterval>) = window?.let { intersect(intervals, it) } ?: intervals
        val possible = FocusHistoryAggregator.union(sessions.flatMap { clipped(it.intervals) })
        // Another session's independently certain coverage can fill an uncertain gap.
        val certain = FocusHistoryAggregator.union(sessions.flatMap { session ->
            session.uncertainGaps.fold(clipped(session.intervals)) { spans, gap -> subtract(spans, gap.window) }
        })
        val ambiguous = certain.fold(possible) { spans, span -> subtract(spans, span) }
        val gaps = sessions.flatMap { it.uncertainGaps }
        val possibleLoss = gaps.fold(0L) { total, gap ->
            add(total, minOf(gap.maximumMs, duration(intersect(ambiguous, gap.window))))
        }
        val lossWindow = FocusHistoryAggregator.union(gaps.flatMap { intersect(ambiguous, it.window) })
        return (duration(possible) - minOf(possibleLoss, duration(lossWindow))).coerceAtLeast(0)
    }
}
