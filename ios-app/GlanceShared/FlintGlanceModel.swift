import Foundation

/// A presentation boundary, never an enforcement decision. The production adapter reads only
/// B's versioned status snapshot and F's public history projection; fixtures implement this seam.
protocol FlintGlanceReading {
    func read(at date: Date) -> FlintGlanceInput
}

struct FlintGlanceInput: Equatable {
    enum Protection: String, CaseIterable {
        case idle, saved, armed, enforcing, suspended, pass, degraded, unavailable
    }
    struct Session: Equatable {
        enum Cause: String, CaseIterable { case manual, timer, pomodoro, other }
        let id: String
        let cause: Cause
        let tier: String
        let startedAt: Date
        let endsAt: Date
    }
    struct Occurrence: Equatable {
        let startsAt: Date
        let endsAt: Date
    }
    struct History: Equatable {
        enum Availability: CaseIterable { case unavailable, degraded, empty, value }
        let availability: Availability
        let seconds: TimeInterval?
        let inProgress: Bool
        let readAt: Date
    }
    let protection: Protection
    let checkedAt: Date?
    let validUntil: Date?
    let session: Session?
    let protectionEndsAt: Date?
    let next: Occurrence?
    let history: History

    static func unavailable(at date: Date) -> Self {
        .init(protection: .unavailable, checkedAt: nil, validUntil: nil, session: nil,
              protectionEndsAt: nil, next: nil,
              history: .init(availability: .unavailable, seconds: nil, inProgress: false, readAt: date))
    }
}

struct FlintGlancePresentation: Equatable {
    enum Status: Equatable {
        case idle, saved, armed, enforcing, suspended, pass, degraded, unavailable, stale, expired
    }
    static let widgetKind = "FlintProtectionWidget"
    static let openURL = URL(string: "flint://open")!

    let status: Status
    let title: String
    let detail: String
    let countdownEnd: Date?
    let nextStart: Date?
    let historyValue: String
    let historyNote: String?
    let checkedAt: Date?

    init(_ input: FlintGlanceInput, at date: Date, calendar: Calendar = .current) {
        checkedAt = input.checkedAt
        // A future check time or an unbounded/malformed validity window is not evidence.
        let valid = input.checkedAt.map { $0 <= date } == true
            && input.validUntil.map { $0 > date && $0 > (input.checkedAt ?? $0) } == true
        let expired = input.protectionEndsAt.map { $0 <= date } == true
        if input.protection == .unavailable || input.checkedAt == nil || input.validUntil == nil {
            status = .unavailable
        } else if expired {
            status = .expired
        } else if !valid {
            status = .stale
        } else {
            switch input.protection {
            case .idle: status = .idle
            case .saved: status = .saved
            case .armed: status = .armed
            case .enforcing: status = .enforcing
            case .suspended: status = .suspended
            case .pass: status = .pass
            case .degraded: status = .degraded
            case .unavailable: status = .unavailable
            }
        }
        switch status {
        case .idle: title = "No active protection"; detail = "Open Flint"
        case .saved: title = "Not armed"; detail = "Open Flint to review"
        case .armed: title = "Protection armed"; detail = "Registration accepted"
        case .enforcing: title = "Protection enforcing"; detail = "Reported by Flint"
        case .suspended: title = "Protection suspended"; detail = "Open Flint to review"
        case .pass: title = "Emergency Pass active"; detail = "Protection in recovery"
        case .degraded: title = "Protection degraded"; detail = "Open Flint to review"
        case .unavailable: title = "State unavailable"; detail = "Open Flint to check"
        case .stale: title = "Status needs checking"; detail = "Open Flint to refresh"
        case .expired: title = "Planned end reached"; detail = "Open Flint to check"
        }
        // Stop the timer at the first unsupported boundary, including snapshot freshness.
        if valid, [.armed, .enforcing].contains(status), let end = input.protectionEndsAt,
           let until = input.validUntil, end > date {
            countdownEnd = min(end, until)
        } else { countdownEnd = nil }
        if valid, let next = input.next, next.startsAt > date, next.endsAt > next.startsAt {
            nextStart = next.startsAt
        } else { nextStart = nil }

        let history = input.history
        if !calendar.isDate(date, inSameDayAs: history.readAt) || history.readAt > date {
            historyValue = "Unavailable"; historyNote = "Open Flint to refresh"
        } else {
            switch history.availability {
            case .unavailable:
                historyValue = "Unavailable"; historyNote = "Focus history unavailable"
            case .empty:
                historyValue = "No sessions yet"; historyNote = nil
            case .degraded, .value:
                let duration = history.seconds.map(Self.duration) ?? "No data"
                historyValue = history.inProgress ? "\(duration) · in progress" : duration
                if history.availability == .degraded { historyNote = "Some history couldn't be read" }
                else { historyNote = history.inProgress ? "In progress · as of update" : nil }
            }
        }
    }

    static func duration(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "Unavailable" }
        let minutes = Int(min(seconds / 60, Double(Int.max / 60)))
        if seconds > 0 && minutes == 0 { return "<1m" }
        if minutes < 60 { return "\(minutes)m" }
        return minutes % 60 == 0 ? "\(minutes / 60)h" : "\(minutes / 60)h \(minutes % 60)m"
    }

    /// Future entries may retire an old claim; they never predict arming or completion.
    static func boundaries(_ input: FlintGlanceInput, after date: Date, calendar: Calendar = .current) -> [Date] {
        let midnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: date))
        return Set([input.validUntil, input.protectionEndsAt, input.session?.endsAt,
                    input.next?.startsAt, midnight].compactMap { $0 }.filter { $0 > date }).sorted()
    }
}

/// Eligibility is based on the authority's armed status, never the existence of activeSession.
struct FlintGlanceActivityPlan: Equatable {
    let sessionID: String
    let cause: String
    let tier: String
    let startedAt: Date
    let endsAt: Date
    let staleAt: Date

    static func make(_ input: FlintGlanceInput, optedIn: Bool, systemEnabled: Bool,
                     at date: Date) -> Self? {
        guard optedIn, systemEnabled, [.armed, .enforcing].contains(input.protection),
              let checked = input.checkedAt, checked <= date,
              let valid = input.validUntil, valid > date, valid > checked,
              let session = input.session, session.cause != .other,
              !session.id.isEmpty, !session.tier.isEmpty,
              session.startedAt <= date, session.endsAt > date,
              session.endsAt > session.startedAt,
              let protectionEnd = input.protectionEndsAt, protectionEnd > date else { return nil }
        return .init(sessionID: session.id, cause: session.cause.rawValue.capitalized,
                     tier: session.tier, startedAt: session.startedAt,
                     endsAt: min(session.endsAt, protectionEnd),
                     staleAt: min(valid, session.endsAt, protectionEnd))
    }
}
