import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// An enabled rule whose requested monitoring is not verified. A refused replacement can
/// retain last-good protection, but must never be presented as successfully re-armed.
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
    public let registrationReason: String?
    public let activityName: String

    public init(ruleID: String, ruleName: String, issue: FlintScheduleIssue?, selectionIssue: FlintSelectionIssue? = nil,
                selectionContext: FlintSelectionContext = .blockList, registrationReason: String? = nil,
                activityName: String? = nil) {
        self.ruleID = ruleID
        self.ruleName = ruleName
        self.issue = issue
        self.selectionIssue = selectionIssue
        self.selectionContext = selectionContext
        self.registrationReason = registrationReason
        self.activityName = activityName ?? "flint.rule.\(ruleID).r1"
    }

    /// Why the requested monitoring is not verified. A prior shield can still be present.
    public var message: String {
        issue?.message ?? selectionIssue?.message(for: selectionContext) ?? registrationReason ?? "iOS refused to register this schedule. Edit its window, or remove "
            + "another schedule and try again."
    }
}

/// Manages recurring/scheduled block rules. Registers one repeating `DeviceActivitySchedule`
/// per enabled rule — one OS activity slot each, and iOS caps those (`FlintArmingHealth`), so
/// every registration outcome is recorded rather than assumed. The monitor extension applies
/// each rule's selection into the rule's own store on `intervalDidStart`, gated by day-of-week.
public final class FlintSchedulesController {

    private let environment: FlintRuleReloadEnvironment
    public init() { environment = .live }
    init(environment: FlintRuleReloadEnvironment) { self.environment = environment }

    public func rules() -> [FlintScheduleRule] {
        FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: environment.defaults, at: environment.clock.now()) ?? []
    }

    /// Insert or update a rule, then re-register monitoring.
    @discardableResult
    public func upsert(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil,
                       creating: Bool = false) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).upsert(rule, confirmation: confirmation, creating: creating)
    }

    @discardableResult
    public func delete(_ ruleID: String, revision: Int? = nil,
                       confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).delete(FlintScheduleRule.self, id: ruleID,
            revision: revision, confirmation: confirmation)
    }

    @discardableResult
    public func setEnabled(_ ruleID: String, _ enabled: Bool, revision: Int? = nil,
                           confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).setEnabled(FlintScheduleRule.self, id: ruleID,
            enabled: enabled, revision: revision, confirmation: confirmation)
    }

    public func cancelPendingChange(_ ruleID: String) { _ = FlintRuleArbiter(environment: environment).cancel(FlintScheduleRule.self, id: ruleID) }

    @discardableResult
    public func applyBatch(_ rules: [FlintScheduleRule], deleting: [FlintScheduleRule] = [],
                           confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let edits = rules.map { FlintRuleArbiter.Mutation(id: $0.id, proposed: $0, action: .edit, expectedRevision: $0.revision) }
        let deletes = deleting.map { FlintRuleArbiter.Mutation<FlintScheduleRule>(id: $0.id,
            proposed: nil, action: .delete, expectedRevision: $0.revision) }
        return FlintRuleArbiter(environment: environment).mutate(edits + deletes, confirmation: confirmation)
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
                failures.append(FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: issue,
                    activityName: rule.monitorName))
            } else if let issue = selectionIssue(rule) {
                failures.append(FlintScheduleArmFailure(
                    ruleID: rule.id, ruleName: rule.name, issue: nil, selectionIssue: issue,
                    selectionContext: rule.allowListMode ? .allowList : .blockList, activityName: rule.monitorName))
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
                activityName: failure.activityName,
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
        FlintRuleStoreLock.withLock(directory: environment.lockDirectory) { reloadLocked() } ?? []
    }

    private func reloadLocked() -> [FlintScheduleArmFailure] {
        guard FlintRuleArbiter(environment: environment).applyQueued(FlintScheduleRule.self) else {
            environment.record("schedules", FlintDesiredRules.unavailable("schedules", previous: environment.loadHealth()))
            return []
        }
        guard let all = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: environment.defaults) else {
            environment.record("schedules", FlintDesiredRules.unavailable("schedules", previous: environment.loadHealth()))
            return []
        }
        let plan = Self.armPlan(for: all.filter { ($0.oneShot?.end ?? .distantFuture) > environment.clock.now() })
        for name in plan.storesToClear {
            environment.clearStore(name)
        }
        let previous = FlintScheduleRegistrationState.load(from: environment.defaults)
        let requests = plan.arm.map { rule in
            var request = rule.registration()
            request.configurationChanged = FlintScheduleRegistrationState.changed(rule, previous: previous[rule.monitorName])
            request.retainedUntil = rule.oneShot?.end ?? FlintRetainedProtection.windowEnd(startHour: rule.schedule.startHour,
                    startMinute: rule.schedule.startMinute, endHour: rule.schedule.endHour,
                    endMinute: rule.schedule.endMinute, daysOfWeek: rule.schedule.daysOfWeek,
                    now: environment.clock.now(), calendar: rule.ruleCalendar)
            return request
        }
        let validationFailures = plan.failures.map {
            FlintArmingHealth.Failure(activityName: $0.activityName, reason: $0.message)
        }
        let report = FlintActivityRegistration.reconcile(
            requests, prefix: "flint.rule.",
            enabled: plan.enabled,
            validationFailures: validationFailures, center: environment.center, defaults: environment.defaults,
            now: environment.clock.now(), onRemove: { name in
                FlintRuleCleanup.clearRemoved(name, rules: all, environment: environment)
            })
        FlintScheduleRegistrationState.save(plan.arm, observed: report.observedActivities ?? [], to: environment.defaults)
        environment.record("schedules", report)
        return plan.failures + plan.arm.compactMap { rule in
            guard let failure = report.failures.first(where: { $0.activityName == rule.monitorName }) else { return nil }
            return FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: nil,
                                           registrationReason: failure.reason, activityName: rule.monitorName)
        }
    }
}
#endif
