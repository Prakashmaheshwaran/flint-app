import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Manages daily Open Limits (launch-count caps) — the host-app arming side of the
/// `FlintOpenLimitEnforcer` seam. Mirrors `FlintLimitsController` (same CRUD + `reload()` shape).
///
/// Arming is two idempotent moves, both re-done by every `reload()`:
///  1. each enabled rule's selection is shielded into the rule's own store
///     (`FlintOpenLimitEnforcer.applyShield`) — this also puts tokens released by earlier
///     grants back behind the shield;
///  2. each enabled rule registers an all-day repeating `DeviceActivitySchedule`
///     (`rule.activityName`), so the monitor extension re-applies the shield at the day
///     boundary even if the app never launches. The daily open counter itself resets via
///     `FlintOpenLimitState.dayStart` — no schedule needed for that.
public final class FlintOpenLimitsController {

    public init() {}

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
        var all = FlintOpenLimitRule.loadAll()
        if let index = all.firstIndex(where: { $0.id == rule.id }) {
            all[index] = rule
        } else {
            all.append(rule)
        }
        FlintOpenLimitRule.saveAll(all)
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    public func delete(_ ruleID: String) {
        var all = FlintOpenLimitRule.loadAll()
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        let removed = all.remove(at: index)
        FlintOpenLimitRule.saveAll(all)
        FlintOpenLimitEnforcer.clearShield(for: removed)
        FlintScheduling.stopMonitoring([removed.activityName])
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    public func setEnabled(_ ruleID: String, _ enabled: Bool) {
        var all = FlintOpenLimitRule.loadAll()
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        all[index].enabled = enabled
        FlintOpenLimitRule.saveAll(all)
        if !enabled {
            FlintOpenLimitEnforcer.clearShield(for: all[index])
        }
        reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
    }

    /// Re-arm every enabled rule: shield its selection and (re)register its day-boundary
    /// activity. Idempotent; call on launch and after edits.
    public func reload(reapplyShields: Bool = true) {
        let all = FlintOpenLimitRule.loadAll()
        let plan = Self.armPlan(for: all)
        for rule in all where plan.storesToClear.contains(rule.storeName) {
            FlintOpenLimitEnforcer.clearShield(for: rule)
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
            validationFailures: plan.failures,
            onRemove: { name in
                let id = name.dropFirst("flint.openLimit.".count)
                ManagedSettingsStore(named: .init("flintO.\(id)")).clearAllSettings()
            })
        if reapplyShields {
            for rule in plan.arm where report.observedActivities?.contains(rule.activityName) == true {
                FlintOpenLimitEnforcer.applyShield(for: rule)
            }
        }
        FlintArmingHealth.record(
            domain: "openLimits", enabled: report.enabled, attempted: report.attempted,
            armed: report.armed, failures: report.failures,
            observedActivities: report.observedActivities)
    }
}
#endif
