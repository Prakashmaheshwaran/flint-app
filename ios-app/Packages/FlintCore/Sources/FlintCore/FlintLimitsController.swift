import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Manages daily Time Limits. Each enabled limit registers an all-day `DeviceActivitySchedule`
/// carrying a `DeviceActivityEvent` whose threshold is the daily budget; the monitor shields the
/// limit's apps when the event fires and clears them at the daily boundary (the reset).
public final class FlintLimitsController {

    private let environment: FlintRuleReloadEnvironment
    public init() { environment = .live }
    init(environment: FlintRuleReloadEnvironment) { self.environment = environment }

    public static func armPlan(
        for rules: [FlintLimitRule]
    ) -> FlintRuleArmPlan<FlintLimitRule, FlintArmingHealth.Failure> {
        armPlan(for: rules, selectionIssue: { FlintSelectionValidation.issue(for: $0.selection) })
    }

    static func armPlan(
        for rules: [FlintLimitRule],
        selectionIssue: (FlintLimitRule) -> FlintSelectionIssue?
    ) -> FlintRuleArmPlan<FlintLimitRule, FlintArmingHealth.Failure> {
        var arm: [FlintLimitRule] = []
        var failures: [FlintArmingHealth.Failure] = []
        for rule in rules where rule.enabled {
            if let issue = selectionIssue(rule) {
                failures.append(.init(activityName: rule.activityName, reason: issue.message))
            } else {
                arm.append(rule)
            }
        }
        let failedNames = Set(failures.map(\.activityName))
        return .init(arm: arm, failures: failures,
                     storesToClear: rules.filter { failedNames.contains($0.activityName) }.map(\.storeName))
    }

    public func limits() -> [FlintLimitRule] {
        FlintGroupStore()?.loadLimits() ?? []
    }

    public func upsert(_ limit: FlintLimitRule) {
        guard var all = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
            return
        }
        if let index = all.firstIndex(where: { $0.id == limit.id }) {
            all[index] = limit
        } else {
            all.append(limit)
        }
        guard FlintDesiredRules.save(all, key: .limits, to: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.saveFailure("limits", previous: environment.loadHealth()))
            return
        }
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    public func delete(_ limitID: String) {
        guard var all = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
            return
        }
        all.removeAll { $0.id == limitID }
        guard FlintDesiredRules.save(all, key: .limits, to: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.saveFailure("limits", previous: environment.loadHealth()))
            return
        }
        ManagedSettingsStore(named: ManagedSettingsStore.Name("flintL.\(limitID)")).clearAllSettings()
        FlintScheduling.stopMonitoring(["flint.limit.\(limitID)"])
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    public func setEnabled(_ limitID: String, _ enabled: Bool) {
        guard var all = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
            return
        }
        guard let index = all.firstIndex(where: { $0.id == limitID }) else { return }
        all[index].enabled = enabled
        guard FlintDesiredRules.save(all, key: .limits, to: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.saveFailure("limits", previous: environment.loadHealth()))
            return
        }
        if !enabled {
            ManagedSettingsStore(named: ManagedSettingsStore.Name("flintL.\(limitID)")).clearAllSettings()
        }
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    /// Re-register all enabled limits. Idempotent; call on launch and after edits. Outcomes
    /// are recorded in `FlintArmingHealth` — each limit holds an OS activity slot, and a
    /// rejected registration is a budget that never trips.
    public func reload() {
        guard let all = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
            return
        }
        let plan = Self.armPlan(for: all)
        for name in plan.storesToClear {
            environment.clearStore(name)
        }
        let requests = plan.arm.map { limit in
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: 0, minute: 0),
                intervalEnd: DateComponents(hour: 23, minute: 59),
                repeats: true
            )
            let event = DeviceActivityEvent(
                applications: limit.selection.applicationTokens,
                categories: limit.selection.categoryTokens,
                webDomains: limit.selection.webDomainTokens,
                threshold: DateComponents(minute: limit.thresholdMinutes)
            )
            return FlintActivityRegistration(name: limit.activityName, schedule: schedule,
                events: [DeviceActivityEvent.Name(limit.eventName): event],
                retainedUntil: FlintRetainedProtection.windowEnd(startHour: 0, startMinute: 0,
                    endHour: 23, endMinute: 59, now: Date()))
        }
        let report = FlintActivityRegistration.reconcile(
            requests, prefix: "flint.limit.", enabled: plan.enabled,
            validationFailures: plan.failures, center: environment.center, defaults: environment.defaults,
            onRemove: { name in
                let id = name.dropFirst("flint.limit.".count)
                environment.clearStore("flintL.\(id)")
            })
        environment.record("limits", report)
    }
}
#endif
