import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Manages daily Open Limit credits — the host-app arming side of the
/// `FlintOpenLimitEnforcer` seam. Mirrors `FlintLimitsController` (same CRUD + `reload()` shape).
///
/// Arming is two idempotent moves, both re-done by every `reload()`:
///  1. each enabled rule's selection is shielded into the rule's own store
///     (`FlintOpenLimitEnforcer.applyShield`), preserving only verifiable live timed grants;
///  2. each enabled rule registers an all-day repeating `DeviceActivitySchedule`
///     (`rule.activityName`), so the monitor extension re-applies the shield at the day
///     boundary even if the app never launches. The ledger governs the saved-zone quota
///     boundary using observed elapsed time; callbacks cannot independently mint credits.
public final class FlintOpenLimitsController {

    private let environment: FlintRuleReloadEnvironment
    private let selectionIssue: (FlintOpenLimitRule) -> FlintSelectionIssue?
    public init() {
        environment = .live
        selectionIssue = { FlintSelectionValidation.issue(for: $0.selection, context: .openLimit) }
    }
    init(environment: FlintRuleReloadEnvironment,
         selectionIssue: @escaping (FlintOpenLimitRule) -> FlintSelectionIssue? = {
             FlintSelectionValidation.issue(for: $0.selection, context: .openLimit)
         }) {
        self.environment = environment
        self.selectionIssue = selectionIssue
    }

    public static func armPlan(
        for rules: [FlintOpenLimitRule]
    ) -> FlintRuleArmPlan<FlintOpenLimitRule, FlintArmingHealth.Failure> {
        armPlan(for: rules, selectionIssue: {
            FlintSelectionValidation.issue(for: $0.selection, context: .openLimit)
        })
    }

    static func armPlan(
        for rules: [FlintOpenLimitRule],
        selectionIssue: (FlintOpenLimitRule) -> FlintSelectionIssue?
    ) -> FlintRuleArmPlan<FlintOpenLimitRule, FlintArmingHealth.Failure> {
        var arm: [FlintOpenLimitRule] = []
        var failures: [FlintArmingHealth.Failure] = []
        for rule in rules where rule.enabled {
            if let issue = selectionIssue(rule) {
                failures.append(.init(activityName: rule.activityName, reason: issue.message(for: .openLimit)))
            } else {
                arm.append(rule)
            }
        }
        let failedNames = Set(failures.map(\.activityName))
        return .init(arm: arm, failures: failures,
                     storesToClear: rules.filter { failedNames.contains($0.activityName) }.map(\.storeName))
    }

    public func rules() -> [FlintOpenLimitRule] {
        guard let rules = environment.rulesForDisplay(FlintOpenLimitRule.self) else {
            environment.recordState("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
            return []
        }
        return rules
    }

    @discardableResult
    public func upsert(_ rule: FlintOpenLimitRule, confirmation: FlintRuleConfirmation? = nil,
                       creating: Bool = false, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).upsert(rule, confirmation: confirmation, creating: creating, origin: origin)
    }

    @discardableResult
    public func delete(_ ruleID: String, revision: Int,
                       confirmation: FlintRuleConfirmation? = nil, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).delete(FlintOpenLimitRule.self, id: ruleID,
            revision: revision, confirmation: confirmation, origin: origin)
    }

    @discardableResult
    public func setEnabled(_ ruleID: String, _ enabled: Bool, revision: Int,
                           confirmation: FlintRuleConfirmation? = nil, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).setEnabled(FlintOpenLimitRule.self, id: ruleID,
            enabled: enabled, revision: revision, confirmation: confirmation, origin: origin)
    }

    public func cancelPendingChange(_ id: String, confirmation: FlintRuleConfirmation? = nil) {
        guard let confirmation else { return }
        _ = FlintRuleArbiter(environment: environment).cancel(FlintOpenLimitRule.self, id: id, confirmation: confirmation)
    }

    public func cancelQueuedChange(_ id: String, expected: FlintRuleProtection) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).cancelQueued(FlintOpenLimitRule.self, id: id, expected: expected)
    }

    public func deleteBatch(_ rules: [FlintOpenLimitRule], confirmation: FlintRuleConfirmation? = nil,
                            origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).mutate(rules.map {
            .init(id: $0.id, proposed: nil, action: .delete, expectedRevision: $0.protection?.revision ?? 0)
        } as [FlintRuleArbiter.Mutation<FlintOpenLimitRule>], confirmation: confirmation, origin: origin)
    }

    /// Re-arm every enabled rule: shield its selection and (re)register its day-boundary
    /// activity. Idempotent; call on launch and after edits.
    public func reload(reapplyShields: Bool = true) {
        if case .none = FlintRuleStoreLock.withLock(environment: environment, { reloadLocked(reapplyShields: reapplyShields) }) {
            environment.recordState("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
        }
    }

    private func reloadLocked(reapplyShields: Bool) {
        // Credit recovery precedes every rule read, including queued revision recovery.
        // Applying shields later preserves only verifiable live grants.
        environment.reconcileOpenCredits()
        guard FlintRuleArbiter(environment: environment).applyQueued(FlintOpenLimitRule.self) else {
            environment.recordState("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
            return
        }
        guard let observed = environment.observedRules(FlintOpenLimitRule.self),
              let all = environment.freezeOccurrences(observed) else {
            environment.recordState("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
            return
        }
        let plan = Self.armPlan(for: all, selectionIssue: selectionIssue)
        let requests = plan.arm.map { $0.registration(clock: environment.clock) }
        let report = FlintActivityRegistration.reconcile(
            requests, prefix: "flint.openLimit.", enabled: plan.enabled,
            validationFailures: plan.failures, center: environment.center, defaults: environment.defaults,
            now: environment.clock.now(), onRemove: { name in
                FlintRuleCleanup.clearRemoved(name, rules: all, environment: environment)
            })
        // Every trigger restores expired/legacy releases only after the daily registration
        // is observed. applyOpenShield now preserves bounded live grants under the ledger lock;
        // the launch-only flag remains a source-compatible parameter for existing clients.
        for rule in plan.arm where report.observedActivities?.contains(rule.activityName) == true {
            environment.applyProtection(rule)
        }
        environment.recordState("openLimits", report)
    }
}
#endif
