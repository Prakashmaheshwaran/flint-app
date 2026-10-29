import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import DeviceActivity

@MainActor
private struct FlintLiveProtectionLayer: FlintProtectionLayer {
    let domain: String
    let run: (FlintProtectionTrigger) throws -> FlintArmingHealth.DomainReport

    func reconcile(trigger: FlintProtectionTrigger) async throws -> FlintArmingHealth.DomainReport {
        try run(trigger)
    }
}

extension FlintProtectionReconciler {
    /// Shared across scenes as well as tabs. No view model owns protection startup.
    public static let shared = FlintProtectionReconciler(layers: [
        FlintLiveProtectionLayer(domain: "session") { _ in
            guard let group = FlintGroupStore() else { throw FlintSessionError.appGroupUnavailable }
            let controller = FlintSessionController()
            let recovery = controller.reconcile()
            // Recovery can replace the session's monitor name. Observe the newly persisted
            // session, and require its schedule as well as its presence in the native list.
            let active = controller.activeSession
            let center = DeviceActivityCenter()
            let observed = center.activities.filter { center.schedule(for: $0) != nil }.map(\.rawValue)
            let report = FlintSessionProtectionReport.make(active: active, recovery: recovery,
                health: FlintSessionHealth.load(), observedActivities: observed,
                hasActiveSessionRecord: group.hasActiveSessionRecord(),
                scheduleIsCurrent: active.map { FlintSessionAutoClearPlan.isCurrent(for: $0, in: .autoupdatingCurrent) } ?? true)
            group.saveArmingHealth(group.loadArmingHealth().replacing("session", with: report))
            return report
        },
        FlintLiveProtectionLayer(domain: "schedules") { _ in
            guard let group = FlintGroupStore() else { throw FlintSessionError.appGroupUnavailable }
            FlintSchedulesController().reload()
            return group.loadArmingHealth().domains["schedules"] ?? missingReport("schedules")
        },
        FlintLiveProtectionLayer(domain: "limits") { _ in
            guard let group = FlintGroupStore() else { throw FlintSessionError.appGroupUnavailable }
            FlintLimitsController().reload()
            return group.loadArmingHealth().domains["limits"] ?? missingReport("limits")
        },
        FlintOpenLimitProtectionLayer { reapplyShields in
            guard let group = FlintGroupStore() else { throw FlintSessionError.appGroupUnavailable }
            // Preserve launch re-shielding. Foreground/time/config reconciliation must not
            // revoke a released grant by blindly applying the full selection (B5 owns grants).
            FlintOpenLimitsController().reload(reapplyShields: reapplyShields)
            return group.loadArmingHealth().domains["openLimits"] ?? missingReport("openLimits")
        },
        FlintLiveProtectionLayer(domain: "webRestrictions") { _ in
            guard let group = FlintGroupStore() else { throw FlintSessionError.appGroupUnavailable }
            let active = group.loadActiveSession()
            return FlintWebRestrictionHostReconciler.reconcile(
                activeSession: active, hasActiveSessionRecord: group.hasActiveSessionRecord(),
                rules: FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: .flintGroup),
                config: FlintWebRestrictions.loadForEnforcement(), health: group.loadArmingHealth(),
                lifecycle: FlintWebRestrictionLifecycle()
            )
        }
    ], publish: {
        // The host initializes the ledger (and runs its one-time legacy pass import) so
        // read-only peeks — extensions, the shield, App detail — find it (ADR-012 §8, §12).
        // Only while a peek can't read it: a normal launch takes no ledger lock here.
        if let ledger = FlintProtectionLedger.appGroup(), case .failure = ledger.peek() {
            _ = ledger.read()
        }
        FlintProtectionStatus.publishAfterCommit(writer: .host)
    })

    private static func missingReport(_ domain: String) -> FlintArmingHealth.DomainReport {
        .init(enabled: 0, attempted: 0, armed: 0,
              failures: [.init(activityName: domain, reason: "No arming report was recorded.")])
    }
}
#endif
