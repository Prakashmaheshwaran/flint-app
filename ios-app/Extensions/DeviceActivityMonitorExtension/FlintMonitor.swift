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
///  - `flint.session`    — the manual Block Now session (single shared store + saved selection).
///  - `flint.rule.<id>`  — a recurring schedule rule (its own store + embedded selection).
///  - `flint.limit.<id>` — a daily Time Limit (all-day window; its event shields on threshold,
///                         and the day boundary resets it).
///  - `flint.openLimit.<id>` — a daily Open Limit (all-day window; the boundary re-applies the
///                         rule's shield, re-arming tokens released by yesterday's grants).
///
/// Anything else is a stale registration (e.g. a rule deleted while the App Group was
/// unreadable): it is deregistered and otherwise ignored — never treated as the session.
final class FlintMonitor: DeviceActivityMonitor {

    private let sessionStore = ManagedSettingsStore(named: ManagedSettingsStore.Name("flint"))

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        if let limit = limit(for: activity) {
            clearLimitShield(limit) // new day → fresh budget
        } else if let rule = rule(for: activity) {
            applyRule(rule)
        } else if let openLimit = openLimit(for: activity) {
            applyOpenLimitShield(openLimit) // new day → re-arm tokens released by yesterday's grants
        } else if activity.rawValue == FlintSessionController.monitorName {
            applyShieldFromSavedSelection()
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
        } else if activity.rawValue == FlintSessionController.monitorName {
            // Session over (natural expiry with the app closed/killed): clearing the store
            // drops the shield AND the Hardcore uninstall guard, which lives on the same store.
            sessionStore.clearAllSettings()
            FlintGroupStore()?.clearActiveSession()
        } else {
            stopStaleActivity(activity)
        }
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

    /// A boundary fired for a name matching no schedule/limit/open-limit and not the session
    /// (`flint.session`): a stale registration. It used to fall through to the session branch —
    /// applying the saved-selection shield or, worse, tearing down a *live* session's store and
    /// state. Deregister it so it never fires again; touch nothing else.
    private func stopStaleActivity(_ activity: DeviceActivityName) {
        FlintScheduling.stopMonitoring([activity.rawValue])
    }

    // MARK: Shielding

    private func applyRule(_ rule: FlintScheduleRule) {
        guard rule.appliesOn(Date()) else { return } // weekday gate
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

    private func applyShieldFromSavedSelection() {
        guard let selection = FlintGroupStore()?.loadSelection() else { return }
        sessionStore.shield.applications =
            selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        sessionStore.shield.applicationCategories =
            selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
        sessionStore.shield.webDomains =
            selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
        // Uninstall guard: deny app removal while a Hardcore session runs. The OS-driven
        // (re)apply mirrors the app-side arm in FlintSessionController, so the guard holds even
        // if the host process died right after starting; a stale/non-Hardcore session asserts
        // nil, and intervalDidEnd's clearAllSettings() releases it with the shield.
        sessionStore.application.denyAppRemoval =
            FlintUninstallGuard.shouldDeny(for: FlintGroupStore()?.loadActiveSession()) ? true : nil
    }
}
