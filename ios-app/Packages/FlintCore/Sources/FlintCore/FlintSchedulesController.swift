import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// An enabled rule that is **not enforcing**, because its window can't be registered.
///
/// A blocking app must never let a rule look armed when it isn't, so `reload()` hands these back
/// instead of dropping them on the floor. `issue == nil` means the window passed Flint's checks
/// but `DeviceActivityCenter` still refused it — an OS-side limit that no client-side validation
/// can predict.
public struct FlintScheduleArmFailure: Equatable, Sendable {
    public let ruleID: String
    public let ruleName: String
    public let issue: FlintScheduleIssue?

    public init(ruleID: String, ruleName: String, issue: FlintScheduleIssue?) {
        self.ruleID = ruleID
        self.ruleName = ruleName
        self.issue = issue
    }

    /// The user-facing reason this rule isn't blocking anything. The fallback stays vague on
    /// purpose: `startMonitoring` throws without telling us which of its limits we hit.
    public var message: String {
        issue?.message ?? "iOS refused to register this schedule. Edit its window, or remove "
            + "another schedule and try again."
    }
}

/// Manages recurring/scheduled block rules. Registers one repeating `DeviceActivitySchedule`
/// per enabled rule (unlimited count, no advance cap). The monitor extension applies each
/// rule's selection into the rule's own store on `intervalDidStart`, gated by day-of-week.
public final class FlintSchedulesController {

    public init() {}

    public func rules() -> [FlintScheduleRule] {
        FlintGroupStore()?.loadRules() ?? []
    }

    /// Insert or update a rule, then re-register monitoring.
    @discardableResult
    public func upsert(_ rule: FlintScheduleRule) -> [FlintScheduleArmFailure] {
        guard let group = FlintGroupStore() else { return [] }
        var all = group.loadRules()
        if let index = all.firstIndex(where: { $0.id == rule.id }) {
            all[index] = rule
        } else {
            all.append(rule)
        }
        group.saveRules(all)
        return reload()
    }

    @discardableResult
    public func delete(_ ruleID: String) -> [FlintScheduleArmFailure] {
        guard let group = FlintGroupStore() else { return [] }
        var all = group.loadRules()
        all.removeAll { $0.id == ruleID }
        group.saveRules(all)
        ManagedSettingsStore(named: ManagedSettingsStore.Name("flint.\(ruleID)")).clearAllSettings()
        FlintScheduling.stopMonitoring(["flint.rule.\(ruleID)"])
        return reload()
    }

    @discardableResult
    public func setEnabled(_ ruleID: String, _ enabled: Bool) -> [FlintScheduleArmFailure] {
        guard let group = FlintGroupStore() else { return [] }
        var all = group.loadRules()
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return [] }
        all[index].enabled = enabled
        group.saveRules(all)
        if !enabled {
            ManagedSettingsStore(named: ManagedSettingsStore.Name("flint.\(ruleID)")).clearAllSettings()
        }
        return reload()
    }

    /// Which of `rules` can be handed to `DeviceActivityCenter`, and which can't and why. Disabled
    /// rules are neither armed nor a failure — the user turned those off on purpose. Pure, so the
    /// arm/skip decision is testable without a device.
    public static func armPlan(
        for rules: [FlintScheduleRule]
    ) -> (arm: [FlintScheduleRule], failures: [FlintScheduleArmFailure]) {
        var arm: [FlintScheduleRule] = []
        var failures: [FlintScheduleArmFailure] = []
        for rule in rules where rule.enabled {
            if let issue = rule.schedule.issues.first {
                failures.append(FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: issue))
            } else {
                arm.append(rule)
            }
        }
        return (arm, failures)
    }

    /// Re-register monitoring for every enabled rule with a registrable window. Idempotent; call on
    /// launch and after edits. Returns the enabled rules that are **not** enforcing, so the UI can
    /// say so: a window `DeviceActivityCenter` would reject is skipped rather than pushed through a
    /// `try?` that hides the refusal and leaves the rule's toggle lying about it.
    @discardableResult
    public func reload() -> [FlintScheduleArmFailure] {
        guard let group = FlintGroupStore() else { return [] }
        let all = group.loadRules()
        FlintScheduling.stopMonitoring(all.map { $0.monitorName })

        let plan = Self.armPlan(for: all)
        var failures = plan.failures
        for rule in plan.arm {
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: rule.schedule.startHour, minute: rule.schedule.startMinute),
                intervalEnd: DateComponents(hour: rule.schedule.endHour, minute: rule.schedule.endMinute),
                repeats: true
            )
            do {
                try FlintScheduling.startMonitoring(rule.monitorName, during: schedule)
            } catch {
                failures.append(FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: nil))
            }
        }
        return failures
    }
}
#endif
