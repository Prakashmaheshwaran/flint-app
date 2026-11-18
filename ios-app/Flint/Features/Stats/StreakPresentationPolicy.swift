import Foundation
import FlintCore

/// The streak rules that both streak surfaces must agree on.
///
/// History that could not be read is not a zero streak: the Insights card is hidden and the
/// Today badge falls back to the neutral "Focus streak" label. A summary that loaded but has
/// no completed days is a legitimate zero and keeps the normal zero-streak wording.
enum StreakPresentationPolicy {

    /// Insights shows the hero only when history was readable (values or a degraded read).
    static func showsCard(for summary: FlintHistorySummary?) -> Bool {
        guard let summary else { return false }
        return summary.availability != .unavailable
    }

    /// The celebration sheet carries the streak number, so it must not open on unreadable
    /// history either — that would present the same false zero the card hides.
    static func showsSheet(for summary: FlintHistorySummary?) -> Bool {
        showsCard(for: summary)
    }

    /// Today's badge label. Unreadable history must not claim "No active focus streak".
    static func badgeLabel(for summary: FlintHistorySummary?) -> String {
        guard let summary, summary.availability != .unavailable else {
            return "Focus streak"
        }
        if summary.streak <= 0 { return "No active focus streak" }
        return summary.streak == 1 ? "Focus streak, 1 day" : "Focus streak, \(summary.streak) days"
    }
}
