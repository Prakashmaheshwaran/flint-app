import Foundation

/// A placement window and independently known maximum length, never an invented event.
public struct FlintUncertainFocusGap: Equatable, Sendable {
    public let window: DateInterval
    public let maximumSeconds: TimeInterval

    public init(window: DateInterval, maximumSeconds: TimeInterval) {
        self.window = window; self.maximumSeconds = maximumSeconds
    }
}

enum FlintHistoryBounds {
    static func subtract(_ spans: [DateInterval], _ cut: DateInterval) -> [DateInterval] {
        spans.flatMap { span -> [DateInterval] in
            if cut.end <= span.start || cut.start >= span.end { return [span] }
            var result: [DateInterval] = []
            if cut.start > span.start { result.append(DateInterval(start: span.start, end: cut.start)) }
            if cut.end < span.end { result.append(DateInterval(start: cut.end, end: span.end)) }
            return result
        }
    }

    private static func intersect(_ spans: [DateInterval], _ window: DateInterval) -> [DateInterval] {
        spans.compactMap { span in
            let a = max(span.start, window.start), b = min(span.end, window.end)
            return b > a ? DateInterval(start: a, end: b) : nil
        }
    }

    static func lowerBound(_ sessions: [FlintHistorySessionSummary], window: DateInterval? = nil) -> TimeInterval {
        func clipped(_ spans: [DateInterval]) -> [DateInterval] { window.map { intersect(spans, $0) } ?? spans }
        let possible = FlintHistoryAggregator.union(sessions.flatMap { clipped($0.intervals) })
        // Independently certain focus in another session can fill an uncertain window.
        let certain = FlintHistoryAggregator.union(sessions.flatMap { session in
            session.uncertainGaps.reduce(clipped(session.intervals)) { subtract($0, $1.window) }
        })
        let ambiguous = certain.reduce(possible) { subtract($0, $1) }
        let gaps = sessions.flatMap(\.uncertainGaps)
        let possibleLoss = gaps.reduce(0.0) { total, gap in
            total + min(gap.maximumSeconds, intersect(ambiguous, gap.window).reduce(0) { $0 + $1.duration })
        }
        let lossWindow = FlintHistoryAggregator.union(gaps.flatMap { intersect(ambiguous, $0.window) })
        return max(0, possible.reduce(0) { $0 + $1.duration } - min(possibleLoss, lossWindow.reduce(0) { $0 + $1.duration }))
    }
}
