import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)

/// The host's read/eligibility boundary, shared with tests. Unknown desired intent never
/// authorizes cleanup; failed repairs leave their last-good cause store to its bounded end.
enum FlintWebRestrictionHostReconciler {
    static func reconcile(
        activeSession: FlintActiveSession?, hasActiveSessionRecord: Bool,
        rules: [FlintScheduleRule]?, config: FlintWebRestrictions?, health: FlintArmingHealth,
        lifecycle: FlintWebRestrictionLifecycle, now: Date = Date(), calendar: Calendar = .autoupdatingCurrent
    ) -> FlintArmingHealth.DomainReport {
        guard let rules, let config, let sessionReport = health.domains["session"],
              let rulesReport = health.domains["schedules"],
              !(activeSession == nil && hasActiveSessionRecord), sessionReport.recordUnreadable != true,
              !sessionReport.failures.contains(where: { $0.activityName == "session" }),
              !rulesReport.failures.contains(where: { $0.activityName == "schedules" }) else {
            return .init(enabled: health.domains["webRestrictions"]?.enabled ?? 0, attempted: 0, armed: 0,
                         failures: [.init(activityName: "webRestrictions",
                            reason: "Saved protection state is unavailable. Existing Safari settings were preserved.")],
                         updatedAt: now)
        }

        let failedRules = Set(rulesReport.failures.map(\.activityName))
        var preserving = Set(rules.filter { $0.enabled && failedRules.contains($0.monitorName) }.map(\.storeName))
        var failures = rules.filter { preserving.contains($0.storeName) }.map {
            FlintArmingHealth.Failure(activityName: $0.monitorName,
                reason: "Safari settings were preserved after a failed rule repair; the cause's end or bounded cleanup releases them.")
        }
        if let activeSession, sessionReport.rearmFailed == true || sessionReport.notAuthorized == true
            || sessionReport.failures.contains(where: { $0.activityName == activeSession.monitorName }) {
            preserving.insert(FlintWebRestrictionLifecycle.sessionStoreName)
            failures.append(.init(activityName: activeSession.monitorName,
                reason: "Safari settings were preserved because this session's end or access could not be verified."))
        }
        lifecycle.reconcile(
            activeSession: FlintWebReconcileEligibility.session(activeSession, report: sessionReport),
            rules: FlintWebReconcileEligibility.rules(rules, report: rulesReport), config: config,
            now: now, calendar: calendar, preservingStores: preserving
        )

        let desired = (FlintWebRestrictionPolicy.shouldApply(to: .session(activeSession), config: config, now: now) ? 1 : 0)
            + rules.filter { FlintWebRestrictionPolicy.shouldApply(
                to: .schedule($0.schedule, enabled: $0.enabled), config: config, now: now, calendar: calendar
            ) }.count
        // Web settings register no activities. Never double-count their owners' activity slots
        // or turn a settings write into claimed native enforcement/observation evidence.
        return .init(enabled: desired, attempted: 0, armed: 0, failures: failures, updatedAt: now)
    }
}
#endif
