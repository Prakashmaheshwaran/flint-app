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
        self.activityName = activityName ?? "flint.rule.\(ruleID)"
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
        guard let rules = environment.rulesForDisplay(FlintScheduleRule.self) else {
            environment.recordState("schedules", FlintDesiredRules.unavailable("schedules", previous: environment.loadHealth()))
            return []
        }
        return rules
    }

    /// Insert or update a rule, then re-register monitoring.
    @discardableResult
    public func upsert(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil,
                       creating: Bool = false, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).upsert(rule, confirmation: confirmation, creating: creating, origin: origin)
    }

    @discardableResult
    public func delete(_ ruleID: String, revision: Int,
                       confirmation: FlintRuleConfirmation? = nil, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).delete(FlintScheduleRule.self, id: ruleID,
            revision: revision, confirmation: confirmation, origin: origin)
    }

    @discardableResult
    public func setEnabled(_ ruleID: String, _ enabled: Bool, revision: Int,
                           confirmation: FlintRuleConfirmation? = nil, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).setEnabled(FlintScheduleRule.self, id: ruleID,
            enabled: enabled, revision: revision, confirmation: confirmation, origin: origin)
    }

    public func cancelPendingChange(_ ruleID: String, confirmation: FlintRuleConfirmation? = nil) {
        guard let confirmation else { return }
        _ = FlintRuleArbiter(environment: environment).cancel(FlintScheduleRule.self, id: ruleID, confirmation: confirmation)
    }

    public func cancelQueuedChange(_ ruleID: String, expected: FlintRuleProtection) -> FlintRuleMutationResult {
        FlintRuleArbiter(environment: environment).cancelQueued(FlintScheduleRule.self, id: ruleID, expected: expected)
    }

    @discardableResult
    public func applyBatch(_ rules: [FlintScheduleRule], deleting: [FlintScheduleRule] = [],
                           confirmation: FlintRuleConfirmation? = nil, origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        let edits = rules.map { FlintRuleArbiter.Mutation(id: $0.id, proposed: $0, action: .edit, expectedRevision: $0.revision) }
        let deletes = deleting.map { FlintRuleArbiter.Mutation<FlintScheduleRule>(id: $0.id,
            proposed: nil, action: .delete, expectedRevision: $0.revision) }
        return FlintRuleArbiter(environment: environment).mutate(edits + deletes, confirmation: confirmation, origin: origin)
    }

    /// Which of `rules` can be handed to `DeviceActivityCenter`, and which can't and why. Disabled
    /// rules are neither armed nor a failure — the user turned those off on purpose. Pure, so the
    /// arm/skip decision is testable without a device.
    public static func armPlan(
        for rules: [FlintScheduleRule]
    ) -> FlintRuleArmPlan<FlintScheduleRule, FlintScheduleArmFailure> {
        armPlan(for: rules, selectionIssue: {
            FlintSelectionValidation.armIssue(for: $0.selection, context: $0.allowListMode ? .allowList : .blockList)
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
        guard let failures = FlintRuleStoreLock.withLock(environment: environment, { reloadLocked() }) else {
            environment.recordState("schedules", FlintDesiredRules.unavailable("schedules", previous: environment.loadHealth()))
            return []
        }
        return failures
    }

    private func reloadLocked() -> [FlintScheduleArmFailure] {
        guard FlintRuleArbiter(environment: environment).applyQueued(FlintScheduleRule.self) else {
            environment.recordState("schedules", FlintDesiredRules.unavailable("schedules", previous: environment.loadHealth()))
            return []
        }
        guard let observed = environment.observedRules(FlintScheduleRule.self),
              let all = environment.freezeOccurrences(observed) else {
            environment.recordState("schedules", FlintDesiredRules.unavailable("schedules", previous: environment.loadHealth()))
            return []
        }
        let plan = Self.armPlan(for: all.filter { rule in
            rule.oneShot.map { !FlintRuleOccurrenceClock.isOver($0, for: rule, clock: environment.clock) } ?? true
        })
        let previous = FlintScheduleRegistrationState.load(from: environment.defaults)
        let requests = plan.arm.map { rule in
            var request = rule.registration(clock: environment.clock)
            request.configurationChanged = FlintScheduleRegistrationState.changed(rule, previous: previous[rule.monitorName])
            request.retainedUntil = rule.previewWindow(at: environment.clock.now(), clock: environment.clock).map {
                FlintRuleOccurrenceClock.display($0, for: rule, clock: environment.clock).end
            }
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
        for rule in plan.arm where rule.occurrence(at: environment.clock.now(), clock: environment.clock) != nil
            && report.observedActivities?.contains(rule.monitorName) == true {
            environment.applyProtection(rule)
        }
        environment.recordState("schedules", report)
        return plan.failures + plan.arm.compactMap { rule in
            guard let failure = report.failures.first(where: { $0.activityName == rule.monitorName }) else { return nil }
            return FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: nil,
                                           registrationReason: failure.reason, activityName: rule.monitorName)
        }
    }
}
#endif
