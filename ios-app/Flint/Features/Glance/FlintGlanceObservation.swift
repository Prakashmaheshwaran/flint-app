import ActivityKit
import SwiftUI
import UIKit
import WidgetKit
import FlintCore

enum FlintLiveActivityPreference {
    static let key = "flint.glance.liveActivitiesEnabled"
    static let lastRequestedSessionKey = "flint.glance.lastRequestedActivitySession"
}

/// Additive host observer. It cannot arm, stop, change tiers or spend a pass.
struct FlintGlanceObservation: ViewModifier {
    @Environment(\.scenePhase) private var phase
    @AppStorage(FlintLiveActivityPreference.key) private var optedIn = false

    func body(content: Content) -> some View {
        content
            .onReceive(NotificationCenter.default.publisher(for: FlintHistoryStore.didChangeNotification)) { _ in
                // F's clear/drain signal is independent of protection. B still owns the
                // canonical reload after enforcement publishes a new status snapshot.
                WidgetCenter.shared.reloadTimelines(ofKind: FlintGlanceReader.widgetKind)
            }
            .task(id: phase) {
                guard phase == .active else { return }
                if #available(iOS 16.1, *) {
                    FlintGlanceActivityController.shared.register()
                    while !Task.isCancelled {
                        // This phase task can outlive a Settings change. Read the current
                        // preference each pass instead of retaining its initial value.
                        await FlintGlanceActivityController.shared.refresh(optedIn:
                            UserDefaults.standard.bool(forKey: FlintLiveActivityPreference.key))
                        do { try await Task.sleep(nanoseconds: 15_000_000_000) }
                        catch { return }
                    }
                }
            }
            .onChange(of: optedIn) { enabled in
                if enabled {
                    // A deliberate new opt-in can show the current session again. Ordinary
                    // polling must respect a Live Activity the user dismissed in iOS.
                    UserDefaults.standard.removeObject(forKey: FlintLiveActivityPreference.lastRequestedSessionKey)
                }
                if #available(iOS 16.1, *) {
                    Task { await FlintGlanceActivityController.shared.refresh(optedIn: enabled) }
                }
            }
            .flintGlanceGallery()
    }
}

@available(iOS 16.1, *)
@MainActor
final class FlintGlanceActivityController {
    static let shared = FlintGlanceActivityController()
    private var registered = false
    private let sink = GlanceLifecycleSink()
    private let refreshQueue = FlintGlanceRefreshQueue()
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
        await refreshQueue.refresh(optedIn: optedIn) { requested in
            let enabled = requested && UserDefaults.standard.bool(forKey: FlintLiveActivityPreference.key)
            await self.apply(optedIn: enabled)
        }
    }

    private func apply(optedIn: Bool) async {
        let now = Date()
        let plan: FlintGlanceActivityPlan?
        if optedIn && ActivityAuthorizationInfo().areActivitiesEnabled {
            let input = FlintGlanceReader().readProtection(at: now)
            plan = FlintGlanceActivityPlan.make(input, optedIn: true, systemEnabled: true, at: now)
        } else { plan = nil }
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
        // Ending a replaced activity suspends. A newer signal/opt-out must win
        // before an old plan can request a replacement display.
        guard !refreshQueue.hasPending, let plan else { return }
        let state = FlintFocusActivityAttributes.ContentState(cause: plan.cause, tier: plan.tier,
            startedAt: plan.startedAt, endsAt: plan.endsAt, staleAt: plan.staleAt, needsCheck: false)
        if let activity = kept {
            rememberRequest(plan.sessionID)
            guard lastStates[activity.id] != state else { return }
            if #available(iOS 16.2, *) {
                await activity.update(ActivityContent(state: state, staleDate: plan.staleAt))
            } else { await activity.update(using: state) }
            lastStates[activity.id] = state
        } else {
            // ActivityKit start requests are foreground-only. No notification/remote path.
            guard UIApplication.shared.applicationState == .active,
                  UserDefaults.standard.bool(forKey: FlintLiveActivityPreference.key),
                  plan.mayRequest(lastRequestedSession: UserDefaults.standard.string(
                    forKey: FlintLiveActivityPreference.lastRequestedSessionKey)) else { return }
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
                rememberRequest(plan.sessionID)
            } catch {
                // The OS may refuse an optional glance surface. Protection is unaffected.
            }
        }
    }

    private func end(_ activity: Activity<FlintFocusActivityAttributes>) async {
        let endedByObserver = isOngoing(activity)
        if #available(iOS 16.2, *) { await activity.end(nil, dismissalPolicy: .immediate) }
        else { await activity.end(using: nil, dismissalPolicy: .immediate) }
        lastStates.removeValue(forKey: activity.id)
        if endedByObserver, UserDefaults.standard.string(forKey: FlintLiveActivityPreference.lastRequestedSessionKey)
            == activity.attributes.sessionID {
            // A display we retire for stale/degraded authority may resume after a fresh
            // armed observation. A user/system-dismissed activity keeps its marker.
            UserDefaults.standard.removeObject(forKey: FlintLiveActivityPreference.lastRequestedSessionKey)
        }
    }

    private func rememberRequest(_ sessionID: String) {
        if UserDefaults.standard.string(forKey: FlintLiveActivityPreference.lastRequestedSessionKey) != sessionID {
            UserDefaults.standard.set(sessionID, forKey: FlintLiveActivityPreference.lastRequestedSessionKey)
        }
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
