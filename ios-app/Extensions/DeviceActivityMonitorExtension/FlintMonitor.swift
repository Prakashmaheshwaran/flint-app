import Foundation
import DeviceActivity
import ManagedSettings
import FlintCore

/// Applies and clears shields on schedule boundaries and usage-threshold events.
///
/// Writing shields from the extension (not the app) is the documented best practice — it keeps
/// enforcement alive when the app is closed or killed. Runs in a tight, short-lived sandbox.
///
/// Activity names that arrive here:
///  - `flint.session.<token>` — the manual Block Now session's one-shot auto-clear (older
///                         builds used plain `flint.session`). `FlintSessionController` decides:
///                         only the registration recorded on the active session can end it.
///  - `flint.rule.<id>`  — a recurring schedule rule (its own store + embedded selection).
///  - `flint.limit.<id>` — a daily Time Limit (all-day window; its event shields on threshold,
///                         and the day boundary resets it).
///  - `flint.openLimit.<id>` — a daily Open Limit (all-day window; the boundary re-applies the
///                         rule's shield, re-arming tokens released by yesterday's grants).
///
/// Anything else is a stale registration (e.g. a rule deleted while the App Group was
/// unreadable): it is deregistered and otherwise ignored — never treated as the session.
final class FlintMonitor: DeviceActivityMonitor {

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        if let limit = limit(for: activity) {
            clearLimitShield(limit) // new day → fresh budget
        } else if let rule = rule(for: activity) {
            applyRule(rule)
        } else if let openLimit = openLimit(for: activity) {
            applyOpenLimitShield(openLimit) // new day → re-arm tokens released by yesterday's grants
        } else if FlintSessionController.isSessionActivity(activity.rawValue) {
            FlintSessionController().handleMonitorEvent(.intervalDidStart, activity: activity.rawValue)
        } else {
            stopStaleActivity(activity)
        }
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        if let limit = limit(for: activity) {
            clearLimitShield(limit)
        } else if let rule = rule(for: activity) {
            ManagedSettingsStore(named: ManagedSettingsStore.Name(rule.storeName)).clearAllSettings()
        } else if let openLimit = openLimit(for: activity) {
            applyOpenLimitShield(openLimit) // open limits stay shielded across the day roll-over
        } else if FlintSessionController.isSessionActivity(activity.rawValue) {
            // Natural expiry with the app closed/killed: ending the session clears its store,
            // which drops the shield AND the Hardcore uninstall guard living on it.
            FlintSessionController().handleMonitorEvent(.intervalDidEnd, activity: activity.rawValue)
        } else {
            stopStaleActivity(activity)
        }
    }

    /// Only session registrations shorter than 15 minutes carry a warning: it marks their real
    /// end inside the padded interval DeviceActivity requires.
    override func intervalWillEndWarning(for activity: DeviceActivityName) {
        super.intervalWillEndWarning(for: activity)
        guard FlintSessionController.isSessionActivity(activity.rawValue) else { return }
        FlintSessionController().handleMonitorEvent(.intervalWillEndWarning, activity: activity.rawValue)
    }

    override func eventDidReachThreshold(
        _ event: DeviceActivityEvent.Name,
        activity: DeviceActivityName
    ) {
        super.eventDidReachThreshold(event, activity: activity)
        // Only Time Limits register events (the session and the other layers never do), so an
        // unmatched event is stale — a no-op, never a cue to shield the session's selection.
        guard let limit = limit(forEvent: event) else { return }
        applyLimitShield(limit) // daily budget hit → shield until the day resets
    }

    // MARK: Lookups

    private func rule(for activity: DeviceActivityName) -> FlintScheduleRule? {
        FlintGroupStore()?.loadRules().first { $0.monitorName == activity.rawValue }
    }

    private func limit(for activity: DeviceActivityName) -> FlintLimitRule? {
        FlintGroupStore()?.loadLimits().first { $0.activityName == activity.rawValue }
    }

    private func limit(forEvent event: DeviceActivityEvent.Name) -> FlintLimitRule? {
        FlintGroupStore()?.loadLimits().first { $0.eventName == event.rawValue }
    }

    private func openLimit(for activity: DeviceActivityName) -> FlintOpenLimitRule? {
        FlintOpenLimitRule.loadAll().first { $0.activityName == activity.rawValue }
    }

    /// A boundary fired for a name matching no schedule/limit/open-limit and not a session
    /// registration: a stale registration. It used to fall through to the session branch —
    /// applying the saved-selection shield or, worse, tearing down a *live* session's store and
    /// state. Deregister it so it never fires again; touch nothing else.
    private func stopStaleActivity(_ activity: DeviceActivityName) {
        FlintScheduling.stopMonitoring([activity.rawValue])
    }

    // MARK: Shielding

    private func applyRule(_ rule: FlintScheduleRule) {
        let now = Date()
        guard rule.enabled, rule.appliesOn(now) else { return } // same weekday gate for shield + Safari
        let store = ManagedSettingsStore(named: ManagedSettingsStore.Name(rule.storeName))
        let selection = rule.selection
        if rule.allowListMode {
            store.shield.applicationCategories = .all(except: selection.applicationTokens)
            store.shield.webDomainCategories = .all()
        } else {
            store.shield.applications =
                selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
            store.shield.applicationCategories =
                selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
            store.shield.webDomains =
                selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
        }
        FlintWebRestrictionLifecycle().reconcileRule(rule, config: .load(), now: now)
    }

    private func applyLimitShield(_ limit: FlintLimitRule) {
        let store = ManagedSettingsStore(named: ManagedSettingsStore.Name(limit.storeName))
        let selection = limit.selection
        store.shield.applications =
            selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.applicationCategories =
            selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
        store.shield.webDomains =
            selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    }

    private func clearLimitShield(_ limit: FlintLimitRule) {
        ManagedSettingsStore(named: ManagedSettingsStore.Name(limit.storeName)).clearAllSettings()
    }

    private func applyOpenLimitShield(_ rule: FlintOpenLimitRule) {
        guard rule.enabled else { return } // stale registration for a disabled rule → leave it clear
        FlintOpenLimitEnforcer.applyShield(for: rule)
    }
}
