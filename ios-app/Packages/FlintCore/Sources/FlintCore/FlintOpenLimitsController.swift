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
        FlintOpenLimitRule.loadAll()
    }

    public func upsert(_ rule: FlintOpenLimitRule) {
        guard var all = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: environment.defaults) else {
            environment.record("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
            return
        }
        if let index = all.firstIndex(where: { $0.id == rule.id }) {
            all[index] = rule
        } else {
            all.append(rule)
        }
        guard FlintDesiredRules.save(all, key: .openLimits, to: environment.defaults) else {
            environment.record("openLimits", FlintDesiredRules.saveFailure("openLimits", previous: environment.loadHealth()))
            return
        }
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    public func delete(_ ruleID: String) {
        guard var all = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: environment.defaults) else {
            environment.record("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
            return
        }
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        let removed = all.remove(at: index)
        guard FlintDesiredRules.save(all, key: .openLimits, to: environment.defaults) else {
            environment.record("openLimits", FlintDesiredRules.saveFailure("openLimits", previous: environment.loadHealth()))
            return
        }
        FlintOpenLimitEnforcer.clearShield(for: removed)
        FlintScheduling.stopMonitoring([removed.activityName])
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    public func setEnabled(_ ruleID: String, _ enabled: Bool) {
        guard var all = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: environment.defaults) else {
            environment.record("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
            return
        }
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        all[index].enabled = enabled
        guard FlintDesiredRules.save(all, key: .openLimits, to: environment.defaults) else {
            environment.record("openLimits", FlintDesiredRules.saveFailure("openLimits", previous: environment.loadHealth()))
            return
        }
        if !enabled {
            FlintOpenLimitEnforcer.clearShield(for: all[index])
        }
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    /// Re-arm every enabled rule: shield its selection and (re)register its day-boundary
    /// activity. Idempotent; call on launch and after edits.
    public func reload(reapplyShields: Bool = true) {
        // Every trigger closes expired/legacy releases, including foreground/time changes.
        // Live bounded grants survive applyShield; the old launch-only flag is source-compatible.
        environment.reconcileOpenCredits()
        guard let all = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: environment.defaults) else {
            environment.record("openLimits", FlintDesiredRules.unavailable("openLimits", previous: environment.loadHealth()))
            return
        }
        let plan = Self.armPlan(for: all, selectionIssue: selectionIssue)
        for rule in all where plan.storesToClear.contains(rule.storeName) {
            environment.clearStore(rule.storeName)
        }
        let requests = plan.arm.map { rule in
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: 0, minute: 0),
                intervalEnd: DateComponents(hour: 23, minute: 59),
                repeats: true
            )
            return FlintActivityRegistration(name: rule.activityName, schedule: schedule)
        }
        let report = FlintActivityRegistration.reconcile(
            requests, prefix: "flint.openLimit.", enabled: plan.enabled,
            validationFailures: plan.failures, center: environment.center, defaults: environment.defaults,
            onRemove: { name in
                let id = name.dropFirst("flint.openLimit.".count)
                environment.clearStore("flintO.\(id)")
            })
        if reapplyShields {
            for rule in plan.arm where report.observedActivities?.contains(rule.activityName) == true {
                environment.applyOpenShield(rule)
            }
        }
        environment.record("openLimits", report)
    }
}
#endif
