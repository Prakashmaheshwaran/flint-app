import SwiftUI
import DeviceActivity
import FlintCore

/// Insights. The numbers come from the `DeviceActivityReport` extension — the only sanctioned
/// way to surface per-app/website usage. The host explains known availability constraints;
/// only the report extension can decide whether returned usage is empty.
struct StatsView: View {
    @ObservedObject var auth: AuthorizationModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var range: InsightsRange = .week
    @State private var referenceDate = Date()
    @State private var reportID = UUID()
    @State private var accessFeedback: String?
    @State private var showReportHelp = false
    private let context = DeviceActivityReport.Context("Total Activity")

    private var availability: InsightsAvailability {
        .resolve(auth.status, isSimulator: InsightsAvailability.isSimulator)
    }

    private var filter: DeviceActivityFilter {
        DeviceActivityFilter(
            segment: .daily(during: range.dateInterval(containing: referenceDate)),
            users: .all,
            devices: .init([.iPhone, .iPad])
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, 24)
                .padding(.top, 18)

            if availability == .report {
                DeviceActivityReport(context, filter: filter)
                    .id(reportID)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                Button("Report help") { showReportHelp = true }
                    .font(FlintFont.text(14))
                    .foregroundStyle(FlintBrand.bone)
                    .frame(minHeight: 44)
            } else {
                ScrollView {
                    unavailableContent
                        .padding(24)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .flintTabBarInset()
        .onAppear { refresh() }
        .onChange(of: scenePhase) { if $0 == .active { refresh() } }
        .onChange(of: range) { _ in reloadReport() }
        .sheet(isPresented: $showReportHelp) { reportHelp }
    }

    @ViewBuilder
    private var header: some View {
        if typeSize.isAccessibilitySize {
            stackedHeader
        } else {
            ViewThatFits(in: .horizontal) {
                HStack {
                    title.fixedSize()
                    Spacer(minLength: 16)
                    rangePicker.fixedSize()
                }
                stackedHeader
            }
        }
    }

    private var stackedHeader: some View {
        VStack(alignment: .leading, spacing: 16) {
            title
            rangePicker
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var title: some View {
        Text("Insights").flintTitle(30).accessibilityAddTraits(.isHeader)
    }

    private var rangePicker: some View {
        FlintSegmented(options: [(InsightsRange.today, "Today"), (.week, "7 days")],
                       selection: $range, height: 44, compact: !typeSize.isAccessibilitySize)
            .accessibilityIdentifier("insights.range")
    }

    private var unavailableContent: some View {
        FlintCard {
            VStack(alignment: .leading, spacing: 16) {
                Text(stateTitle)
                    .flintTitle(24)
                    .accessibilityAddTraits(.isHeader)
                Text(stateDescription).flintSecondary(16)

                if availability == .needsAuthorization || availability == .needsSettings {
                    if InsightsAvailability.isSimulator {
                        Text("Simulator can’t provide Screen Time reports. View Insights on a physical iPhone or iPad.")
                            .flintSecondary(15)
                    }
                    AuthorizationControls(auth: auth, identifierPrefix: "insights.authorization", onApproved: refresh)
                }

                if availability == .unavailable {
                    Button("Check access again") {
                        refresh()
                        if availability == .unavailable {
                            accessFeedback = "Screen Time access could not be confirmed. Try again after checking Settings."
                        }
                    }
                    .font(FlintFont.text(16, weight: .medium))
                    .foregroundStyle(FlintBrand.bone)
                    .frame(minHeight: 44)
                    .disabled(auth.isRequesting)
                    .accessibilityIdentifier("insights.checkAccess")
                    if let accessFeedback {
                        Text(accessFeedback).flintSecondary(15)
                            .accessibilityIdentifier("insights.accessFeedback")
                    }
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var stateTitle: String {
        switch availability {
        case .needsAuthorization, .needsSettings: return "Screen Time access needed"
        case .simulator: return "Insights needs a device"
        case .unavailable: return "Insights is unavailable"
        case .report: return ""
        }
    }

    private var stateDescription: String {
        switch availability {
        case .needsAuthorization:
            return "Allow Screen Time to see your activity for \(range.description). Your usage stays on your device."
        case .needsSettings:
            return "Your activity for \(range.description) is unavailable while Screen Time access is off. Your usage stays on your device."
        case .simulator:
            return "Simulator can’t provide Screen Time reports for \(range.description). Run Flint on a physical iPhone or iPad and allow Screen Time to see your activity."
        case .unavailable:
            return "Flint couldn’t confirm Screen Time access. Check access again, or review Screen Time in Settings."
        case .report: return ""
        }
    }

    private func refresh() {
        auth.refresh()
        accessFeedback = nil
        reloadReport()
    }

    private func reloadReport() {
        referenceDate = Date()
        reportID = UUID()
    }

    private var reportHelp: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Screen Time reports").flintTitle(28).accessibilityAddTraits(.isHeader)
                Text("If no report appears, try reloading or choosing another range. You can also check Flint’s Screen Time access in Settings and return here.")
                    .flintSecondary(16)
                Text("Screen Time supplies the report. A blank report doesn’t mean you had no activity.")
                    .flintSecondary(16)
                Button("Reload report") {
                    refresh()
                    showReportHelp = false
                }
                .buttonStyle(EmberButtonStyle())
                Button("Done") { showReportHelp = false }
                    .buttonStyle(BoneButtonStyle())
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(24)
        }
        .background(FlintBrand.ground)
    }
}

/// Insights' ambient decoration. `RootView` hosts it behind the clipped tab content, so it can
/// paint the top safe-area band without imposing its 470 pt height on a short viewport.
struct InsightsBackdrop: View {
    var body: some View {
        LinearGradient(stops: [
            .init(color: Color(hex: 0x0A0C11), location: 0),
            .init(color: Color(hex: 0x15151B), location: 0.45),
            .init(color: Color(hex: 0x2A211D), location: 0.82),
            .init(color: FlintBrand.ground, location: 1),
        ], startPoint: .top, endPoint: .bottom)
        .frame(height: 470)
    }
}
