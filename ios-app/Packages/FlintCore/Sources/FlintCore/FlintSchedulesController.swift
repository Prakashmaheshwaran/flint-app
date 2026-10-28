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
    public let selectionIssue: FlintSelectionIssue?
    public let selectionContext: FlintSelectionContext

    public init(ruleID: String, ruleName: String, issue: FlintScheduleIssue?, selectionIssue: FlintSelectionIssue? = nil,
                selectionContext: FlintSelectionContext = .blockList) {
        self.ruleID = ruleID
        self.ruleName = ruleName
        self.issue = issue
        self.selectionIssue = selectionIssue
        self.selectionContext = selectionContext
    }

    /// The user-facing reason this rule isn't blocking anything. The fallback stays vague on
    /// purpose: `startMonitoring` throws without telling us which of its limits we hit.
    public var message: String {
        issue?.message ?? selectionIssue?.message(for: selectionContext) ?? "iOS refused to register this schedule. Edit its window, or remove "
            + "another schedule and try again."
    }
}

/// Manages recurring/scheduled block rules. Registers one repeating `DeviceActivitySchedule`
/// per enabled rule — one OS activity slot each, and iOS caps those (`FlintArmingHealth`), so
/// every registration outcome is recorded rather than assumed. The monitor extension applies
/// each rule's selection into the rule's own store on `intervalDidStart`, gated by day-of-week.
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
        let failures = reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
        return failures
    }

    @discardableResult
    public func delete(_ ruleID: String) -> [FlintScheduleArmFailure] {
        guard let group = FlintGroupStore() else { return [] }
        var all = group.loadRules()
        all.removeAll { $0.id == ruleID }
        group.saveRules(all)
        ManagedSettingsStore(named: ManagedSettingsStore.Name("flint.\(ruleID)")).clearAllSettings()
        FlintScheduling.stopMonitoring(["flint.rule.\(ruleID)"])
        let failures = reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
        return failures
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
        let failures = reload()
        NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
        return failures
    }

    /// Which of `rules` can be handed to `DeviceActivityCenter`, and which can't and why. Disabled
    /// rules are neither armed nor a failure — the user turned those off on purpose. Pure, so the
    /// arm/skip decision is testable without a device.
    public static func armPlan(
        for rules: [FlintScheduleRule]
    ) -> FlintRuleArmPlan<FlintScheduleRule, FlintScheduleArmFailure> {
        armPlan(for: rules, selectionIssue: {
            FlintSelectionValidation.issue(for: $0.selection, context: $0.allowListMode ? .allowList : .blockList)
        })
    }

    // Count validation can be injected by unit tests without manufacturing opaque Apple tokens.
    static func armPlan(
        for rules: [FlintScheduleRule],
        selectionIssue: (FlintScheduleRule) -> FlintSelectionIssue?
    ) -> FlintRuleArmPlan<FlintScheduleRule, FlintScheduleArmFailure> {
        var arm: [FlintScheduleRule] = []
        var failures: [FlintScheduleArmFailure] = []
        for rule in rules where rule.enabled {
            if let issue = rule.schedule.issues.first {
                failures.append(FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: issue))
            } else if let issue = selectionIssue(rule) {
                failures.append(FlintScheduleArmFailure(
                    ruleID: rule.id, ruleName: rule.name, issue: nil, selectionIssue: issue,
                    selectionContext: rule.allowListMode ? .allowList : .blockList))
            } else {
                arm.append(rule)
            }
        }
        let failedIDs = Set(failures.map(\.ruleID))
        return .init(arm: arm, failures: failures,
                     storesToClear: rules.filter { failedIDs.contains($0.id) }.map(\.storeName))
    }

    /// Builds the health slice for a schedules reload. Kept pure so a locally invalid rule is
    /// regression-tested as an enabled-but-not-attempted failure rather than a false green.
    static func armingHealthReport(
        enabled: Int,
        attempted: Int,
        armed: Int,
        validationFailures: [FlintScheduleArmFailure],
        registrationFailures: [FlintArmingHealth.Failure]
    ) -> FlintArmingHealth.DomainReport {
        let failures = validationFailures.map { failure in
            FlintArmingHealth.Failure(
                activityName: "flint.rule.\(failure.ruleID)",
                reason: failure.message
            )
        } + registrationFailures
        return FlintArmingHealth.DomainReport(
            enabled: enabled,
            attempted: attempted,
            armed: armed,
            failures: failures
        )
    }

    /// Re-register monitoring for every enabled rule with a registrable window. Idempotent; call on
    /// launch and after edits. Returns the enabled rules that are **not** enforcing, so the UI can
    /// say so: a window `DeviceActivityCenter` would reject is skipped rather than pushed through a
    /// `try?` that hides the refusal and leaves the rule's toggle lying about it.
    @discardableResult
    public func reload() -> [FlintScheduleArmFailure] {
        guard let group = FlintGroupStore() else { return [] }
        let all = group.loadRules()
        let plan = Self.armPlan(for: all)
        for name in plan.storesToClear {
            ManagedSettingsStore(named: .init(name)).clearAllSettings()
        }
        let previous = FlintScheduleRegistrationState.load()
        let requests = plan.arm.map { rule in
            FlintActivityRegistration(name: rule.monitorName, schedule: DeviceActivitySchedule(
                intervalStart: DateComponents(hour: rule.schedule.startHour, minute: rule.schedule.startMinute),
                intervalEnd: DateComponents(hour: rule.schedule.endHour, minute: rule.schedule.endMinute),
                repeats: true
            ), configurationChanged: previous[rule.monitorName] != rule)
        }
        let validationFailures = plan.failures.map {
            FlintArmingHealth.Failure(activityName: "flint.rule.\($0.ruleID)", reason: $0.message)
        }
        let report = FlintActivityRegistration.reconcile(
            requests, prefix: "flint.rule.",
            enabled: plan.enabled,
            validationFailures: validationFailures,
            onRemove: { name in
                let id = name.dropFirst("flint.rule.".count)
                ManagedSettingsStore(named: .init("flint.\(id)")).clearAllSettings()
            })
        FlintScheduleRegistrationState.save(plan.arm, observed: report.observedActivities ?? [])
        FlintArmingHealth.record(
            domain: "schedules", enabled: report.enabled, attempted: report.attempted,
            armed: report.armed, failures: report.failures, in: group,
            observedActivities: report.observedActivities)
        return plan.failures + plan.arm.compactMap { rule in
            guard report.failures.contains(where: { $0.activityName == rule.monitorName }) else { return nil }
            return FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: nil)
        }
    }
}
#endif
