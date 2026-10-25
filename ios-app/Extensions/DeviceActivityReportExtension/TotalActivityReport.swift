import DeviceActivity
import SwiftUI

extension DeviceActivityReport.Context {
    static let totalActivity = Self("Total Activity")
}

/// Aggregates screen time for the report interval. The host app picks the interval via the
/// `DeviceActivityFilter` it passes to the embedded `DeviceActivityReport`.
struct TotalActivityReport: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .totalActivity
    let content: (ActivitySummary) -> TotalActivityView

    func makeConfiguration(representing data: DeviceActivityResults<DeviceActivityData>) async -> ActivitySummary {
        var summary = ActivitySummary()
        var byDay: [Date: TimeInterval] = [:]
        var byApp: [String: (TimeInterval, Int)] = [:]
        let calendar = Calendar.current
        for await device in data {
            for await segment in device.activitySegments {
                summary.total += segment.totalActivityDuration
                byDay[calendar.startOfDay(for: segment.dateInterval.start), default: 0] += segment.totalActivityDuration
                for await category in segment.categories {
                    for await app in category.applications {
                        guard let name = app.application.localizedDisplayName else { continue }
                        let current = byApp[name] ?? (0, 0)
                        byApp[name] = (current.0 + app.totalActivityDuration, current.1 + app.numberOfPickups)
                    }
                }
            }
        }
        summary.days = byDay.map { ActivitySummary.Day(start: $0.key, duration: $0.value) }.sorted { $0.start < $1.start }
        summary.apps = byApp
            .map { ActivitySummary.App(name: $0.key, duration: $0.value.0, pickups: $0.value.1) }
            .sorted { $0.duration > $1.duration }
            .prefix(3)
            .map { $0 }
        return summary
    }
}
