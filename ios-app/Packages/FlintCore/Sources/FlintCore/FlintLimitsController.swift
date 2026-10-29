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
        guard let rules = environment.rulesForDisplay(FlintLimitRule.self) else {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
            return []
        }
        return rules
    }

    @discardableResult
    public func upsert(_ limit: FlintLimitRule, confirmation: FlintRuleConfirmation? = nil,
                       creating: Bool = false, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).upsert(limit, confirmation: confirmation, creating: creating, origin: origin)
    }

    @discardableResult
    public func delete(_ limitID: String, revision: Int,
                       confirmation: FlintRuleConfirmation? = nil, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).delete(FlintLimitRule.self, id: limitID,
            revision: revision, confirmation: confirmation, origin: origin)
    }

    @discardableResult
    public func setEnabled(_ limitID: String, _ enabled: Bool, revision: Int,
                           confirmation: FlintRuleConfirmation? = nil, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).setEnabled(FlintLimitRule.self, id: limitID,
            enabled: enabled, revision: revision, confirmation: confirmation, origin: origin)
    }

    public func cancelPendingChange(_ id: String, confirmation: FlintRuleConfirmation? = nil) {
        guard let confirmation else { return }
        _ = FlintRuleArbiter(environment: environment).cancel(FlintLimitRule.self, id: id, confirmation: confirmation)
    }

    public func cancelQueuedChange(_ id: String, expected: FlintRuleProtection) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).cancelQueued(FlintLimitRule.self, id: id, expected: expected)
    }

    /// Re-register all enabled limits. Idempotent; call on launch and after edits. Outcomes
    /// are recorded in `FlintArmingHealth` — each limit holds an OS activity slot, and a
    /// rejected registration is a budget that never trips.
    public func reload() {
        if case .none = FlintRuleStoreLock.withLock(environment: environment, { reloadLocked() }) {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
        }
    }

    private func reloadLocked() {
        guard FlintRuleArbiter(environment: environment).applyQueued(FlintLimitRule.self) else {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
            return
        }
        guard let all = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: environment.defaults) else {
            environment.record("limits", FlintDesiredRules.unavailable("limits", previous: environment.loadHealth()))
            return
        }
        let plan = Self.armPlan(for: all)
        let requests = plan.arm.map { limit in
            var request = limit.registration()
            request.retainedUntil = limit.occurrence(at: environment.clock.now())?.end
            return request
        }
        let report = FlintActivityRegistration.reconcile(
            requests, prefix: "flint.limit.", enabled: plan.enabled,
            validationFailures: plan.failures, center: environment.center, defaults: environment.defaults,
            now: environment.clock.now(), onRemove: { name in
                FlintRuleCleanup.clearRemoved(name, rules: all, environment: environment)
            })
        for limit in plan.arm where limit.thresholdMinutes == 0 && report.observedActivities?.contains(limit.activityName) == true {
            environment.applyLimitShield(limit)
            environment.markLimitThreshold(limit, environment.clock.now())
        }
        environment.record("limits", report)
    }
}
#endif
