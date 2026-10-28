import Foundation
import FlintCore

/// Replaced only by public B/F adapters, never by direct reads of enforcement preference keys.
struct FlintGlanceReader: FlintGlanceReading {
    // Public Darwin notification name supplied by B's v1 contract, not a storage key.
    static let changedNotificationName = "com.flint.peakfocus.status.changed"

    func read(at date: Date) -> FlintGlanceInput {
        let protection = readProtection(at: date)
        let snapshot = FlintHistoryStore.shared.readOnlySnapshot()
        let summary = FlintHistoryAggregator.summarize(snapshot, activeSessionID: protection.session?.id,
                                                        now: date, dayCount: 1)
        let history = FlintGlanceHistoryAdapter.project(summary, at: date)
        return protection.replacingHistory(history)
    }

    /// The activity manager needs only B's small status blob, never the history log.
    func readProtection(at date: Date) -> FlintGlanceInput {
        .unavailable(at: date)
    }
}
