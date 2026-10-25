import SwiftUI
import DeviceActivity
import FlintCore

/// Insights. The numbers come from the `DeviceActivityReport` extension — the only sanctioned
/// way to surface per-app/website usage — which renders inside this frame. Real data on a device
/// with Screen Time authorization; empty in the Simulator (which doesn't provide usage data).
struct StatsView: View {
    enum Range: Hashable { case today, week }

    @State private var range: Range = .week
    private let context = DeviceActivityReport.Context("Total Activity")

    private var filter: DeviceActivityFilter {
        let cal = Calendar.current
        let today = cal.dateInterval(of: .day, for: Date()) ?? DateInterval()
        let interval: DateInterval
        switch range {
        case .today:
            interval = today
        case .week:
            let start = cal.date(byAdding: .day, value: -6, to: today.start) ?? today.start
            interval = DateInterval(start: start, end: today.end)
        }
        return DeviceActivityFilter(
            segment: .daily(during: interval),
            users: .all,
            devices: .init([.iPhone, .iPad])
        )
    }

    var body: some View {
        ZStack(alignment: .top) {
            FlintBrand.ground.ignoresSafeArea()
            LinearGradient(stops: [
                .init(color: Color(hex: 0x0A0C11), location: 0),
                .init(color: Color(hex: 0x15151B), location: 0.45),
                .init(color: Color(hex: 0x2A211D), location: 0.82),
                .init(color: FlintBrand.ground, location: 1),
            ], startPoint: .top, endPoint: .bottom)
            .frame(height: 470)
            .ignoresSafeArea()

            VStack(spacing: 0) {
                HStack {
                    Text("Insights").flintTitle(30)
                    Spacer()
                    FlintSegmented(options: [(Range.today, "Today"), (.week, "7 days")],
                                   selection: $range, height: 36, compact: true)
                }
                .padding(.horizontal, 24)
                .padding(.top, 18)

                DeviceActivityReport(context, filter: filter)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .flintTabBarInset()
        }
    }
}
