import SwiftUI
#if DEBUG
import ActivityKit
import WidgetKit
import FlintCore

/// Simulator evidence only. Uses an isolated suite and the public publisher; never
/// writes the production App Group, changes consent or arms Screen Time.
struct FlintUIPreviewGlance: View {
    let family: WidgetFamily
    private let now = Date()

    private var input: FlintGlanceInput {
        let suite = "FlintUIPreviewGlance.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let active = FlintActiveSession(id: "glance-preview", name: "Fixture study", startedAt: now.addingTimeInterval(-600),
            endsAt: now.addingTimeInterval(1800), breakLevel: .harder, monitorName: "preview-monitor",
            monotonicStart: 400, bootSessionID: "preview-boot")
        let environment = FlintProtectionStatus.ObservationEnvironment(authorized: { true },
            registeredActivities: { [active.monitorName] },
            clock: .init(now: { now }, monotonic: { 1000 }, bootSessionID: { "preview-boot" }, calendar: { .current }),
            loadActiveSession: { active }, hasActiveSessionRecord: { true }, loadArmingHealth: { .init() })
        FlintProtectionStatus.publishFromCurrentState(now: now, defaults: defaults,
            pass: .init(eligibility: .available), environment: environment, effects: .silent)
        return FlintGlanceReader.project(FlintProtectionStatus.load(now: now, from: defaults), at: now)
            .replacingHistory(.init(availability: .value, seconds: 1200, inProgress: true, readAt: now))
    }

    var body: some View {
        VStack(spacing: 22) {
            Text("DEBUG fixture preview").font(.headline)
            Text("Simulated observation · no enforcement").font(.caption)
            if family == .systemSmall || family == .systemMedium {
                widget(family, width: family == .systemSmall ? 170 : 350, height: 180)
            } else {
                widget(.accessoryInline, width: 350, height: 35)
                widget(.accessoryCircular, width: 80, height: 80)
                widget(.accessoryRectangular, width: 250, height: 100)
            }
            if #available(iOS 16.1, *), let plan = FlintGlanceActivityPlan.make(input, optedIn: true, systemEnabled: true, at: now) {
                Text("Live Activity content preview").font(.caption)
                FlintFocusActivityView(state: .init(cause: plan.cause, tier: plan.tier, startedAt: plan.startedAt,
                    endsAt: plan.endsAt, staleAt: plan.staleAt, needsCheck: false), needsCheck: false)
                    .background(FlintBrand.ground).clipShape(RoundedRectangle(cornerRadius: 18))
                Button("Start fixture Live Activity") { requestFixture(plan) }
                    .buttonStyle(.bordered)
            }
            Text("Widget content only; OS placement is separate.").font(.caption)
        }
        .padding()
    }

    private func widget(_ family: WidgetFamily, width: CGFloat, height: CGFloat) -> some View {
        FlintGlanceWidgetView(entry: .init(date: now, input: input))
            .environment(\.widgetFamily, family)
            .padding(family == .systemSmall || family == .systemMedium ? 14 : 0)
            .frame(width: width, height: height)
            .background(FlintBrand.ground).clipShape(RoundedRectangle(cornerRadius: 18))
    }

    @available(iOS 16.1, *)
    private func requestFixture(_ plan: FlintGlanceActivityPlan) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = FlintFocusActivityAttributes.ContentState(cause: plan.cause, tier: plan.tier,
            startedAt: plan.startedAt, endsAt: plan.endsAt, staleAt: plan.staleAt, needsCheck: false)
        // A distinct fixture ID cannot be confused with a real protection occurrence.
        guard !Activity<FlintFocusActivityAttributes>.activities.contains(where: { $0.attributes.sessionID == "glance-preview" }) else { return }
        if #available(iOS 16.2, *) {
            _ = try? Activity.request(attributes: FlintFocusActivityAttributes(sessionID: "glance-preview"),
                content: ActivityContent(state: state, staleDate: plan.staleAt), pushType: nil)
        } else {
            _ = try? Activity.request(attributes: FlintFocusActivityAttributes(sessionID: "glance-preview"), contentState: state, pushType: nil)
        }
    }
}
#endif

extension View {
    @ViewBuilder func flintGlanceGallery() -> some View {
        #if DEBUG
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "-FlintUIPreviewGlance"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1) {
            let value = ProcessInfo.processInfo.arguments[index + 1]
            FlintUIPreviewGlance(family: value == "small" ? .systemSmall : value == "medium" ? .systemMedium : .accessoryRectangular)
        } else { self }
        #else
        self
        #endif
    }
}
