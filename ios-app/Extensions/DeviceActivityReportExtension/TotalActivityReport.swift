import DeviceActivity
import SwiftUI

extension DeviceActivityReport.Context {
    static let totalActivity = Self("Total Activity")
}

/// Aggregates total screen-time for the report interval. The host app picks the interval via the
/// `DeviceActivityFilter` it passes to the embedded `DeviceActivityReport`.
struct TotalActivityReport: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .totalActivity
    let content: (String) -> TotalActivityView

    func makeConfiguration(representing data: DeviceActivityResults<DeviceActivityData>) async -> String {
        let total = await data
            .flatMap { $0.activitySegments }
            .reduce(0) { $0 + $1.totalActivityDuration }

        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.hour, .minute]
        formatter.unitsStyle = .abbreviated
        return formatter.string(from: total) ?? "0m"
    }
}
