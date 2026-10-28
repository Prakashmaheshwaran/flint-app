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
    ) -> (arm: [FlintOpenLimitRule], failures: [FlintArmingHealth.Failure]) {
        armPlan(for: rules, selectionIssue: {
            FlintSelectionValidation.issue(for: $0.selection, supportsCategories: false)
        })
    }

    static func armPlan(
        for rules: [FlintOpenLimitRule],
        selectionIssue: (FlintOpenLimitRule) -> FlintSelectionIssue?
    ) -> (arm: [FlintOpenLimitRule], failures: [FlintArmingHealth.Failure]) {
        var arm: [FlintOpenLimitRule] = []
        var failures: [FlintArmingHealth.Failure] = []
        for rule in rules where rule.enabled {
            if let issue = selectionIssue(rule) {
                failures.append(.init(activityName: rule.activityName, reason: issue.message))
            } else {
                arm.append(rule)
            }
        }
        return (arm, failures)
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
    }

    public func delete(_ ruleID: String) {
        var all = FlintOpenLimitRule.loadAll()
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        let removed = all.remove(at: index)
        FlintOpenLimitRule.saveAll(all)
        FlintOpenLimitEnforcer.clearShield(for: removed)
        FlintScheduling.stopMonitoring([removed.activityName])
        reload()
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
    }

    /// Re-arm every enabled rule: shield its selection and (re)register its day-boundary
    /// activity. Idempotent; call on launch and after edits.
    public func reload() {
        let all = FlintOpenLimitRule.loadAll()
        // `FlintScheduling.stopMonitoring([])` is a documented no-op (stop-everything now takes
        // the explicit `stopAllMonitoring()`), but keep the local guard as belt-and-braces —
        // this call site must never even flirt with the framework's stop-all overload.
        let names = all.map { $0.activityName }
        if !names.isEmpty {
            FlintScheduling.stopMonitoring(names)
        }
        var attempted = 0
        var armed = 0
        let plan = Self.armPlan(for: all)
        var failures = plan.failures
        for rule in plan.arm {
            FlintOpenLimitEnforcer.applyShield(for: rule)
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: 0, minute: 0),
                intervalEnd: DateComponents(hour: 23, minute: 59),
                repeats: true
            )
            attempted += 1
            do {
                try FlintScheduling.startMonitoring(rule.activityName, during: schedule)
                armed += 1
            } catch {
                // The shield half above still applied — the day-boundary re-arm is what's
                // missing. Record it: without the activity, released tokens stay released.
                failures.append(FlintArmingHealth.Failure(
                    activityName: rule.activityName, reason: String(describing: error)))
            }
        }
        FlintArmingHealth.record(
            domain: "openLimits",
            enabled: all.lazy.filter(\.enabled).count,
            attempted: attempted,
            armed: armed,
            failures: failures
        )
    }
}
#endif
