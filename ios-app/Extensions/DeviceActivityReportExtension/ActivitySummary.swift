import Foundation

/// Report-private values. Nothing is persisted or sent to the host app.
struct ActivitySummary {
    struct Day: Identifiable {
        let start: Date
        let duration: TimeInterval
        var id: Date { start }
    }

    struct App: Identifiable {
        let name: String
        let duration: TimeInterval
        let pickups: Int
        var id: String { name }
    }

    enum ContentState: Equatable { case activity, empty, noData }

    var total: TimeInterval = 0
    var days: [Day] = []
    var apps: [App] = []

    var contentState: ContentState {
        // Keep even sub-minute activity, pickups, or partial app/day data visible.
        if total > 0 || days.contains(where: { $0.duration > 0 })
            || apps.contains(where: { $0.duration > 0 || $0.pickups > 0 }) {
            return .activity
        }
        return days.isEmpty ? .noData : .empty
    }
}
