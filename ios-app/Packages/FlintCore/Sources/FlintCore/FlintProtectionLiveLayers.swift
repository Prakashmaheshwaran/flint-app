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
            guard FlintGroupStore() != nil else { throw FlintSessionError.appGroupUnavailable }
            let controller = FlintSessionController()
            controller.reconcileUninstallGuard()
            let active = controller.activeSession
            let observed = DeviceActivityCenter().activities.contains(.init(FlintSessionController.monitorName))
            // B2 will supply registration/recovery outcomes. Until integrated, do not claim
            // that reconciling only the uninstall guard re-armed the active session.
            return .init(enabled: active == nil ? 0 : 1, attempted: 0, armed: 0,
                         failures: active == nil ? [] : [.init(activityName: FlintSessionController.monitorName,
                            reason: "Session recovery has not yet verified the current registration.")],
                         observedActivities: observed ? [FlintSessionController.monitorName] : [])
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
        FlintLiveProtectionLayer(domain: "openLimits") { trigger in
            guard let group = FlintGroupStore() else { throw FlintSessionError.appGroupUnavailable }
            // Preserve launch re-shielding. Foreground/time/config reconciliation must not
            // revoke a released grant by blindly applying the full selection (B5 owns grants).
            FlintOpenLimitsController().reload(reapplyShields: trigger.reappliesOpenLimitShields)
            return group.loadArmingHealth().domains["openLimits"] ?? missingReport("openLimits")
        },
        FlintLiveProtectionLayer(domain: "webRestrictions") { _ in
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
