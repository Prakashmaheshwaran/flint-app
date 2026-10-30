import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)

/// The host's read/eligibility boundary, shared with tests. Unknown desired intent never
/// authorizes cleanup; failed repairs leave their last-good cause store to its bounded end.
enum FlintWebRestrictionHostReconciler {
    static func busyReport(now: Date = Date()) -> FlintArmingHealth.DomainReport {
        .init(enabled: 0, attempted: 0, armed: 0, failures: [.init(activityName: "webRestrictions",
            reason: "Website rules are busy. Existing settings were preserved; try reconciliation again shortly.")], updatedAt: now)
    }

    static func withRead(_ read: () -> FlintWebRestrictionRead,
                         reconcile: (FlintWebRestrictions?) -> FlintArmingHealth.DomainReport) -> FlintArmingHealth.DomainReport {
        switch read() {
        case .busy: return busyReport()
        case .unavailable: return reconcile(nil)
        case let .available(config): return reconcile(config)
        }
    }

    static func reconcileLive() -> FlintArmingHealth.DomainReport {
        // Read owners, intent and eligibility and perform native writes in one transition.
        // A strengthening cannot commit between our config read and an older native write.
        guard FlintRuleStoreLock.prepareInitialization(environment: .live) else { return busyReport() }
        return FlintSessionController().withTransition {
            withRead(FlintWebRestrictions.readForEnforcement) { config in
                guard let group = FlintGroupStore() else { return busyReport() }
                return reconcile(activeSession: group.loadActiveSession(), hasActiveSessionRecord: group.hasActiveSessionRecord(),
                    rules: FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: .flintGroup, migrating: false),
                    config: config, health: group.loadArmingHealth(), lifecycle: .init(), clock: .system)
            }
        } ?? busyReport()
    }

    static func reconcile(
        activeSession: FlintActiveSession?, hasActiveSessionRecord: Bool,
        rules: [FlintScheduleRule]?, config: FlintWebRestrictions?, health: FlintArmingHealth,
        lifecycle: FlintWebRestrictionLifecycle, now: Date = Date(), calendar: Calendar = .autoupdatingCurrent,
        clock: FlintClock? = nil
    ) -> FlintArmingHealth.DomainReport {
        guard let rules, let sessionReport = health.domains["session"],
              let rulesReport = health.domains["schedules"],
              !(activeSession == nil && hasActiveSessionRecord), sessionReport.recordUnreadable != true,
              activeSession.map({ session in
                  (session.contractVersion == nil || session.contractVersion == FlintActiveSession.currentContractVersion)
                  && session.startedAt.timeIntervalSinceReferenceDate.isFinite
                  && (session.endsAt.map { $0.timeIntervalSinceReferenceDate.isFinite && $0 > session.startedAt } ?? true)
              }) ?? true,
              !sessionReport.failures.contains(where: { $0.activityName == "session" }),
              !rulesReport.failures.contains(where: { $0.activityName == "schedules" }) else {
            return .init(enabled: health.domains["webRestrictions"]?.enabled ?? 0, attempted: 0, armed: 0,
                         failures: [.init(activityName: "webRestrictions",
                            reason: "Saved protection state is unavailable. Existing Safari settings were preserved.")],
                         updatedAt: now)
        }
        guard let config else {
            lifecycle.clearEndedOwners(activeSession: activeSession, rules: rules, now: now, clock: clock)
            return .init(enabled: health.domains["webRestrictions"]?.enabled ?? 0, attempted: 0, armed: 0,
                         failures: [.init(activityName: "webRestrictions",
                            reason: "Saved website rules are unavailable. Live owners were preserved; verified ended owners were released.")],
                         updatedAt: now)
        }
        if let message = config.validationMessage {
            return .init(enabled: 0, attempted: 0, armed: 0,
                         failures: [.init(activityName: "webRestrictions", reason: message)], updatedAt: now)
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
        let eligibleSession = FlintWebReconcileEligibility.session(activeSession, report: sessionReport)
        let eligibleRules = FlintWebReconcileEligibility.rules(rules, report: rulesReport)
        lifecycle.reconcile(
            activeSession: eligibleSession, rules: eligibleRules, config: config,
            now: now, calendar: calendar, preservingStores: preserving, clock: clock
        )

        let applied = (!preserving.contains(FlintWebRestrictionLifecycle.sessionStoreName)
            && FlintWebRestrictionPolicy.shouldApply(to: .session(eligibleSession), config: config, now: now,
                                                   clock: clock) ? 1 : 0)
            + eligibleRules.filter { !preserving.contains($0.storeName) && FlintWebRestrictionPolicy.shouldApply(
                to: $0, config: config, now: now, calendar: calendar, clock: clock
            ) }.count
        // This domain's funnel describes applied settings, not native activity registrations.
        // Settings renders it separately and activity-slot totals exclude the web domain.
        return .init(enabled: applied, attempted: applied, armed: applied, failures: failures, updatedAt: now)
    }
}
#endif
