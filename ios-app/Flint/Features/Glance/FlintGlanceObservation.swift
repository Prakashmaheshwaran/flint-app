import ActivityKit
import SwiftUI
import UIKit
import FlintCore

enum FlintLiveActivityPreference {
    static let key = "flint.glance.liveActivitiesEnabled"
}

/// Additive host observer. It cannot arm, stop, change tiers or spend a pass.
struct FlintGlanceObservation: ViewModifier {
    @Environment(\.scenePhase) private var phase
    @AppStorage(FlintLiveActivityPreference.key) private var optedIn = false

    func body(content: Content) -> some View {
        content
            .task(id: phase) {
                guard phase == .active else { return }
                if #available(iOS 16.1, *) {
                    FlintGlanceActivityController.shared.register()
                    while !Task.isCancelled {
                        await FlintGlanceActivityController.shared.refresh(optedIn: optedIn)
                        do { try await Task.sleep(nanoseconds: 15_000_000_000) }
                        catch { return }
                    }
                }
            }
            .onChange(of: optedIn) { enabled in
                if #available(iOS 16.1, *) {
                    Task { await FlintGlanceActivityController.shared.refresh(optedIn: enabled) }
                }
            }
    }
}

@available(iOS 16.1, *)
@MainActor
final class FlintGlanceActivityController {
    static let shared = FlintGlanceActivityController()
    private var registered = false
    private let sink = GlanceLifecycleSink()
    private var refreshing = false
    private var pendingOptIn: Bool?
    private var lastStates: [String: FlintFocusActivityAttributes.ContentState] = [:]

    func register() {
        guard !registered else { return }
        registered = true
        FlintSessionLifecycle.register(sink)
        // This singleton lives for the process lifetime. The callback never dereferences
        // the observer pointer; it only schedules a fresh read of B's public snapshot.
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque(), { _, _, _, _, _ in
                Task { @MainActor in
                    await FlintGlanceActivityController.shared.refresh(
                        optedIn: UserDefaults.standard.bool(forKey: FlintLiveActivityPreference.key))
                }
            }, FlintGlanceReader.changedNotificationName as CFString, nil, .deliverImmediately)
    }

    /// Serialize ActivityKit mutations so a slow update cannot recreate an activity after
    /// opt-out. Re-read the latest preference and authority on every queued pass.
    func refresh(optedIn: Bool) async {
        pendingOptIn = optedIn
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        while let requested = pendingOptIn {
            pendingOptIn = nil
            let enabled = requested && UserDefaults.standard.bool(forKey: FlintLiveActivityPreference.key)
            await apply(optedIn: enabled)
        }
    }

    private func apply(optedIn: Bool) async {
        let now = Date()
        let input = FlintGlanceReader().read(at: now)
        let plan = FlintGlanceActivityPlan.make(input, optedIn: optedIn,
            systemEnabled: ActivityAuthorizationInfo().areActivitiesEnabled, at: now)
        let activities = Activity<FlintFocusActivityAttributes>.activities
        var kept: Activity<FlintFocusActivityAttributes>?
        for activity in activities {
            if let plan, activity.attributes.sessionID == plan.sessionID, kept == nil,
               isOngoing(activity) {
                kept = activity
            } else {
                await end(activity)
            }
        }
        guard let plan else { return }
        let state = FlintFocusActivityAttributes.ContentState(cause: plan.cause, tier: plan.tier,
            startedAt: plan.startedAt, endsAt: plan.endsAt, staleAt: plan.staleAt, needsCheck: false)
        if let activity = kept {
            guard lastStates[activity.id] != state else { return }
            if #available(iOS 16.2, *) {
                await activity.update(ActivityContent(state: state, staleDate: plan.staleAt))
            } else { await activity.update(using: state) }
            lastStates[activity.id] = state
        } else {
            // ActivityKit start requests are foreground-only. No notification/remote path.
            guard UIApplication.shared.applicationState == .active,
                  UserDefaults.standard.bool(forKey: FlintLiveActivityPreference.key) else { return }
            do {
                let attributes = FlintFocusActivityAttributes(sessionID: plan.sessionID)
                let activity: Activity<FlintFocusActivityAttributes>
                if #available(iOS 16.2, *) {
                    activity = try Activity.request(attributes: attributes,
                        content: ActivityContent(state: state, staleDate: plan.staleAt), pushType: nil)
                } else {
                    activity = try Activity.request(attributes: attributes, contentState: state, pushType: nil)
                }
                lastStates[activity.id] = state
            } catch {
                // The OS may refuse an optional glance surface. Protection is unaffected.
            }
        }
    }

    private func end(_ activity: Activity<FlintFocusActivityAttributes>) async {
        if #available(iOS 16.2, *) { await activity.end(nil, dismissalPolicy: .immediate) }
        else { await activity.end(using: nil, dismissalPolicy: .immediate) }
        lastStates.removeValue(forKey: activity.id)
    }

    private func isOngoing(_ activity: Activity<FlintFocusActivityAttributes>) -> Bool {
        if activity.activityState == .active { return true }
        if #available(iOS 16.2, *) { return activity.activityState == .stale }
        return false
    }
}

@available(iOS 16.1, *)
private final class GlanceLifecycleSink: FlintSessionEventSink {
    func handle(_ event: FlintSessionLifecycleEvent) {
        // Authority publication may follow the lifecycle event. This is just an extra
        // host read; B's canonical publication and widget-reload hook remain authoritative.
        Task { @MainActor in
            await FlintGlanceActivityController.shared.refresh(
                optedIn: UserDefaults.standard.bool(forKey: FlintLiveActivityPreference.key))
        }
    }
}
