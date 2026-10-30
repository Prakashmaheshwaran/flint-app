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

@MainActor
struct FlintWebProtectionLayer: FlintProtectionLayer {
    let domain = "webRestrictions"
    let run: () -> FlintArmingHealth.DomainReport

    init(run: @escaping () -> FlintArmingHealth.DomainReport = FlintWebRestrictionHostReconciler.reconcileLive) {
        self.run = run
    }

    func reconcile(trigger: FlintProtectionTrigger) async throws -> FlintArmingHealth.DomainReport {
        let work = run
        return await Task.detached(priority: .userInitiated) { work() }.value
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
        FlintWebProtectionLayer()
    ], prepare: {
        // Establish positive rule initialization before session/pass startup creates its own
        // protection evidence. Existing installations and unavailable domains are untouched.
        if !FlintRuleStoreLock.prepareInitialization(environment: .live, timeout: 0) {
            // A busy cold-launch lock must not spin the main actor. Keep preparation ordered
            // before all layers while the bounded retry runs on a worker thread.
            await Task.detached(priority: .userInitiated) {
                _ = FlintRuleStoreLock.prepareInitialization(environment: .live)
            }.value
        }
    }, publish: {
        // The host initializes the ledger (and runs its one-time legacy pass import) so
        // read-only peeks — extensions, the shield, App detail — find it (ADR-012 §8, §12).
        // Only before its durable initialization marker exists (first launch of this build): a
        // normal launch takes no ledger lock here, and a corrupt ledger isn't re-read each time.
        if UserDefaults.flintGroup?.object(forKey: FlintProtectionLedger.initializedMarkerKey) == nil {
            _ = FlintProtectionLedger.appGroup()?.read()
        }
        FlintProtectionStatus.publishAfterCommit(writer: .host)
    })

    private static func missingReport(_ domain: String) -> FlintArmingHealth.DomainReport {
        .init(enabled: 0, attempted: 0, armed: 0,
              failures: [.init(activityName: domain, reason: "No arming report was recorded.")])
    }
}
#endif
