import Foundation
import FlintCore

/// Reads F's public aggregate, never its storage layout or DeviceActivity report data.
enum FlintGlanceHistoryAdapter {
    static func project(_ summary: FlintHistorySummary, at date: Date,
                        calendar: Calendar = .current) -> FlintGlanceInput.History {
        let availability: FlintGlanceInput.History.Availability
        switch summary.availability {
        case .unavailable: availability = .unavailable
        case .degraded: availability = .degraded
        case .empty: availability = .empty
        case .value: availability = .value
        }
        let today = summary.days.first { calendar.isDate($0.start, inSameDayAs: date) }
        return .init(availability: availability, seconds: today?.focusedSeconds,
                     inProgress: today?.inProgress ?? false, readAt: date)
    }
}
