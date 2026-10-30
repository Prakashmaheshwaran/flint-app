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
        switch FlintRuleStoreLock.initializationReadiness(environment: .live) {
        case .ready: break
        case .contended: return busyReport()
        case .failed: return unavailableReport(health: FlintGroupStore()?.loadArmingHealth() ?? .init(), now: Date())
        }
        return FlintSessionController().withTransition {
            withRead(FlintWebRestrictions.readForEnforcement) { config in
                guard let group = FlintGroupStore() else { return busyReport() }
                let snapshot = FlintWebRestrictionEditor.Snapshot.observing(session: group.loadActiveSession(),
                    recordPresent: group.hasActiveSessionRecord(),
                    rules: FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: .flintGroup, migrating: false),
                    ledgerState: try? FlintProtectionLedger.appGroup()?.peek().get(), clock: .system)
                return reconcile(snapshot: snapshot, config: config, health: group.loadArmingHealth(),
                    lifecycle: .init(), clock: .system)
            }
        } ?? busyReport()
    }

    static func reconcile(snapshot: FlintWebRestrictionEditor.Snapshot?, config: FlintWebRestrictions?,
                          health: FlintArmingHealth, lifecycle: FlintWebRestrictionLifecycle,
                          now: Date = Date(), clock: FlintClock) -> FlintArmingHealth.DomainReport {
        guard let snapshot else { return unavailableReport(health: health, now: now) }
        // Snapshot.observing already proved the generation/record relationship. The legacy
        // typed entry point below still needs an explicit record-presence consistency flag.
        return reconcile(activeSession: snapshot.session, hasActiveSessionRecord: snapshot.session != nil,
            rules: snapshot.rules, config: config, health: health, lifecycle: lifecycle, now: now, clock: clock)
    }

    private static func unavailableReport(health: FlintArmingHealth, now: Date) -> FlintArmingHealth.DomainReport {
        .init(enabled: health.domains["webRestrictions"]?.enabled ?? 0, attempted: 0, armed: 0,
              failures: [.init(activityName: "webRestrictions",
                 reason: "Saved protection state is unavailable. Existing Safari settings were preserved.")], updatedAt: now)
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
            return unavailableReport(health: health, now: now)
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
        // Eligibility affects writes, never owner lifetime or child pruning. Keep the actual
        // snapshots separate from the registration-masked nil/disabled projections.
        if eligibleSession == nil, let activeSession, activeSession.isEnforcing(at: now, clock: clock) {
            if preserving.insert(FlintWebRestrictionLifecycle.sessionStoreName).inserted {
                failures.append(.init(activityName: activeSession.monitorName,
                    reason: "Safari updates were deferred because this live session's registration is not verified."))
            }
        }
        for rule in rules {
            if !FlintWebRestrictionChildren.knows(rule) || (rule.enabled && rule.occurrence(at: now, clock: clock) != nil
                && eligibleRules.first(where: { $0.id == rule.id })?.enabled != true) {
                if preserving.insert(rule.storeName).inserted {
                    failures.append(.init(activityName: rule.monitorName,
                        reason: "Safari updates were deferred because this rule's occurrence or registration is not verified."))
                }
            }
        }
        if config == nil || config?.validationMessage != nil {
            failures.append(.init(activityName: "webRestrictions", reason: config?.validationMessage
                ?? "Saved website preferences are unavailable. Verifiable frozen children were retained; unknown children need repair."))
        }
        lifecycle.reconcile(
            activeSession: eligibleSession, rules: eligibleRules, config: config,
            now: now, calendar: calendar, preservingStores: preserving, clock: clock,
            actualOwners: .init(session: activeSession, rules: rules)
        )
        failures += lifecycle.unavailableStores.sorted().map { .init(activityName: $0,
            reason: "This occurrence's frozen website policy is unavailable. Existing Safari keys were preserved.") }
        let applied = lifecycle.appliedStores.count
        // This domain's funnel describes applied settings, not native activity registrations.
        // Settings renders it separately and activity-slot totals exclude the web domain.
        return .init(enabled: applied, attempted: applied, armed: applied, failures: failures, updatedAt: now)
    }
}
#endif
