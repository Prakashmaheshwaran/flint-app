import Foundation
import FlintCore

/// Shared, pure copy mapping so every persisted session failure is visible in Settings.
enum FlintSessionHealthPresentation {
    static func hasCurrentIssue(health: FlintSessionHealth, active: FlintActiveSession?,
                                report: FlintArmingHealth.DomainReport?) -> Bool {
        guard let active else { return false }
        if report?.notAuthorized == true || report?.rearmFailed == true || health.unauthorizedSince != nil { return true }
        if health.lastTimeAnomaly?.sessionID == active.id { return true }
        if let failure = health.lastArmFailure, failure.sessionName == active.name, failure.at >= active.startedAt,
           report?.observedActivities?.contains(active.monitorName) != true { return true }
        return false
    }

    static func messages(health: FlintSessionHealth, report: FlintArmingHealth.DomainReport?) -> [String] {
        var messages: [String] = []
        if let failure = health.lastArmFailure {
            messages.append("Last session arm failure: \(failure.sessionName) — \(failure.reason). "
                + "Recorded at \(timestamp(failure.at)).")
        }
        if let anomaly = health.lastTimeAnomaly {
            let description: String
            switch anomaly.kind {
            case .wallAhead: description = "The clock moved forward during a session"
            case .wallBehind: description = "The clock moved back during a session"
            case .timeZoneChanged: description = "The time zone changed during a session"
            case .prematureEnd: description = "iOS delivered a session end early"
            case .missedEndWarning: description = "A short session's end warning did not arrive"
            }
            let amount = anomaly.kind == .timeZoneChanged && anomaly.seconds == 0
                ? "" : " (\(Int(anomaly.seconds.rounded())) seconds)"
            messages.append("\(description)\(amount). "
                + "Recorded at \(timestamp(anomaly.detectedAt)).")
        }
        if let since = health.unauthorizedSince {
            messages.append("A session is running but Screen Time access is off — iOS isn't blocking it. "
                + "Since \(timestamp(since)).")
        }
        if report?.notAuthorized == true {
            messages.append("At the last check, the running session did not have Screen Time access.")
        }
        if report?.rearmFailed == true {
            let reason = report?.failures.last { $0.activityName != "session.lastAttempt" }?.reason
            messages.append("At the last check, the session's end could not be re-registered. "
                + (reason ?? "See the session failure above."))
        }
        return messages
    }

    private static func timestamp(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }
}
