import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)

/// Maps B2's recovery result and a fresh native read into the shared health ledger. An old
/// activity name or an accepted repair alone cannot verify the current session's auto-end.
enum FlintSessionProtectionReport {
    static func make(
        active: FlintActiveSession?,
        recovery: FlintSessionReconcileReport,
        health: FlintSessionHealth,
        observedActivities: [String],
        scheduleIsCurrent: Bool = true,
        now: Date = Date()
    ) -> FlintArmingHealth.DomainReport {
        var failures: [FlintArmingHealth.Failure] = []
        let name = active?.monitorName ?? "session"
        if recovery.notAuthorized {
            failures.append(.init(activityName: name,
                reason: "A session is running but Screen Time access is off — iOS isn't blocking it."))
        }
        if recovery.rearmFailed {
            failures.append(.init(activityName: name,
                reason: health.lastArmFailure?.reason ?? "iOS refused to re-register the session's end."))
        } else if let failure = health.lastArmFailure {
            // A refused new start can leave an older, valid session running. Preserve the
            // failure without falsely labelling that older session's observed monitor failed.
            failures.append(.init(activityName: "session.lastAttempt",
                                  reason: "\(failure.sessionName): \(failure.reason)"))
        }
        let observed = active.map { session in observedActivities.filter { $0 == session.monitorName } } ?? []
        let needsAutoEnd = active?.endsAt != nil
        if needsAutoEnd && (observed.isEmpty || !scheduleIsCurrent) && !recovery.rearmFailed {
            failures.append(.init(activityName: name,
                reason: observed.isEmpty ? "The session's end registration was not observed in iOS."
                    : "The session's end registration has not been updated for this time zone."))
        }
        return .init(enabled: active == nil ? 0 : 1,
                     attempted: recovery.rearmed || recovery.rearmFailed ? 1 : 0,
                     armed: needsAutoEnd && !observed.isEmpty && scheduleIsCurrent
                        && !recovery.notAuthorized && !recovery.rearmFailed ? 1 : 0,
                     failures: failures, updatedAt: now, observedActivities: observed,
                     notAuthorized: recovery.notAuthorized, rearmFailed: recovery.rearmFailed)
    }
}
#endif
