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
            // TODO(B1 phase 2, after B3b merges): call FlintWebRestrictionLifecycle.reconcile
            // using FlintWebReconcileEligibility and the fresh session/schedules reports.
            // This adapter intentionally performs no native web writes until that handoff.
            let enabled = FlintWebRestrictions.load().enabled
            return .init(enabled: enabled ? 1 : 0, attempted: 0, armed: 0,
                         failures: enabled ? [.init(activityName: "flint.web",
                            reason: "Website restriction reconciliation has not yet been integrated.")] : [])
        }
    ])

    private static func missingReport(_ domain: String) -> FlintArmingHealth.DomainReport {
        .init(enabled: 0, attempted: 0, armed: 0,
              failures: [.init(activityName: domain, reason: "No arming report was recorded.")])
    }
}
#endif
