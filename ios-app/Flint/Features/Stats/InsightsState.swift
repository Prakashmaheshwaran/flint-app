import Foundation
import FamilyControls

/// Only states the host can establish. Report loading, failure and usage stay inside Apple's
/// report service; an empty host frame is not evidence of zero usage or a failed report.
enum InsightsAvailability: Equatable {
    case needsAuthorization
    case needsSettings
    case simulator
    case report
    case unavailable

    static var isSimulator: Bool {
        #if targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }

    static func resolve(_ status: AuthorizationStatus, isSimulator: Bool) -> Self {
        switch status {
        case .notDetermined: return .needsAuthorization
        case .denied: return .needsSettings
        case .approved: return isSimulator ? .simulator : .report
        default:
            // `approvedWithDataAccess` (iOS 26.4+) also means access was granted, so it must
            // show the report rather than the unavailable state. Only genuinely unknown
            // statuses are unavailable. A plain `default` (not `@unknown default`) is needed
            // because an early return does not make the switch exhaustive to the compiler.
            if #available(iOS 26.4, *), status == .approvedWithDataAccess {
                return isSimulator ? .simulator : .report
            }
            return .unavailable
        }
    }
}

enum InsightsRange: Hashable {
    case today, week

    var description: String { self == .today ? "today" : "the last 7 days" }

    func dateInterval(containing date: Date, calendar: Calendar = .current) -> DateInterval {
        let today = calendar.dateInterval(of: .day, for: date) ?? DateInterval(start: date, duration: 0)
        switch self {
        case .today:
            return today
        case .week:
            let start = calendar.date(byAdding: .day, value: -6, to: today.start) ?? today.start
            return DateInterval(start: start, end: today.end)
        }
    }
}
