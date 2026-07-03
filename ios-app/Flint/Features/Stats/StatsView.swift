import SwiftUI
import DeviceActivity

/// Embeds the `DeviceActivityReport` extension's "Total Activity" scene — the only sanctioned way
/// to surface per-app/website usage. Renders real data on a device with Screen Time authorization;
/// empty in the Simulator (which doesn't provide usage data).
struct StatsView: View {
    private let context = DeviceActivityReport.Context("Total Activity")

    private var filter: DeviceActivityFilter {
        let today = Calendar.current.dateInterval(of: .day, for: Date()) ?? DateInterval()
        return DeviceActivityFilter(
            segment: .daily(during: today),
            users: .all,
            devices: .init([.iPhone, .iPad])
        )
    }

    var body: some View {
        NavigationStack {
            DeviceActivityReport(context, filter: filter)
                .navigationTitle("Today")
                .navigationBarTitleDisplayMode(.inline)
        }
    }
}
