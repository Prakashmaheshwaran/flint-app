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
        hasActiveSessionRecord: Bool = false,
        scheduleIsCurrent: Bool = true,
        now: Date = Date()
    ) -> FlintArmingHealth.DomainReport {
        if recovery.recoveryApplying {
            return .init(enabled: active == nil ? 0 : 1, attempted: 0, armed: 0,
                failures: [.init(activityName: "session", reason: "Emergency Pass recovery needs reconciliation. Open Flint to retry.")],
                updatedAt: now, observedActivities: [])
        }
        if let until = recovery.suspendedUntil {
            return .init(enabled: active == nil ? 0 : 1, attempted: 0, armed: 0, failures: [],
                updatedAt: now, observedActivities: [], suspendedUntil: until)
        }
        // Presence is a fresh fact: a record can become unreadable between recovery and the
        // following decode. Neither case is an authoritative "no session" or an arm attempt.
        if recovery.recordUnreadable || (active == nil && hasActiveSessionRecord) {
            let fallback = "The running session's record can't be read. Existing protection was preserved."
            let reason = recovery.recordUnreadable ? health.lastArmFailure?.reason ?? fallback : fallback
            return .init(enabled: 1, attempted: 0, armed: 0,
                failures: [.init(activityName: "session", reason: reason)], updatedAt: now,
                observedActivities: [], notAuthorized: recovery.notAuthorized,
                rearmFailed: recovery.rearmFailed, recordUnreadable: true)
        }
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
                     notAuthorized: recovery.notAuthorized, rearmFailed: recovery.rearmFailed,
                     recordUnreadable: false)
    }
}
#endif
