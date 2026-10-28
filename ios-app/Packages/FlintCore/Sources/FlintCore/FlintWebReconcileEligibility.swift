import Foundation
#if canImport(FamilyControls)
import FamilyControls

/// Web settings share their cause's store and end callback. A later web reconciliation must
/// not resurrect keys just cleared because the cause could not arm. Keep disabled rule copies
/// in the input so the web lifecycle clears their keys rather than silently skipping cleanup.
enum FlintWebReconcileEligibility {
    static func session(_ session: FlintActiveSession?, report: FlintArmingHealth.DomainReport?) -> FlintActiveSession? {
        guard let session else { return nil }
        if session.endsAt == nil { return session } // deliberately open-ended; no auto-end needed
        return isObserved(session.monitorName, domain: "session", report: report) ? session : nil
    }

    static func rules(_ rules: [FlintScheduleRule], report: FlintArmingHealth.DomainReport?) -> [FlintScheduleRule] {
        rules.map { rule in
            var eligible = rule
            eligible.enabled = rule.enabled && isObserved(rule.monitorName, domain: "schedules", report: report)
            return eligible
        }
    }

    private static func isObserved(_ name: String, domain: String, report: FlintArmingHealth.DomainReport?) -> Bool {
        guard let report, report.observedActivities?.contains(name) == true else { return false }
        return !report.failures.contains { $0.activityName == name || $0.activityName == domain }
    }
}
#endif
