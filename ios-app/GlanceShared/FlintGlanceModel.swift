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
        /// A Pomodoro chain's phase, published only while its own boundary is still ahead.
        enum Phase: String, CaseIterable { case focus, rest }
        let id: String
        let cause: Cause
        let tier: String
        /// Must come from a report for this exact occurrence, not aggregate rule health.
        let isArmed: Bool
        let startedAt: Date
        let endsAt: Date
        var name: String? = nil
        var phase: Phase? = nil
        var round: Int? = nil
        var rounds: Int? = nil
        /// Wall estimate of the logical phase end. Past it, the phase is never claimed.
        var phaseEndsAt: Date? = nil
    }
    struct Occurrence: Equatable {
        let startsAt: Date
        let endsAt: Date
        var name: String? = nil
    }
    struct Cause: Equatable {
        let name: String
        let kind: String
        let tier: String
        var endsAt: Date? = nil
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
    var headline: Cause? = nil
    var activeFocusSessionID: String? = nil

    static func unavailable(at date: Date, history: History? = nil) -> Self {
        .init(protection: .unavailable, checkedAt: nil, validUntil: nil, session: nil,
              protectionEndsAt: nil, next: nil,
              history: history ?? .init(availability: .unavailable, seconds: nil, inProgress: false, readAt: date))
    }

    func replacingHistory(_ value: History) -> Self {
        .init(protection: protection, checkedAt: checkedAt, validUntil: validUntil, session: session,
              protectionEndsAt: protectionEndsAt, next: next, history: value, headline: headline,
              activeFocusSessionID: activeFocusSessionID)
    }
}

struct FlintGlancePresentation: Equatable {
    enum Status: Equatable {
        case idle, saved, armed, enforcing, suspended, pass, degraded, unavailable, stale, expired
    }
    static let openURL = URL(string: "flint://open")!

    let status: Status
    let title: String
    let detail: String
    let countdownEnd: Date?
    let plannedEnd: Date?
    let causeSummary: String?
    /// "Focus · round 2 of 4" or "Rest · focus resumes 10:25". nil when the phase is unknown or
    /// past its own published boundary — a stale phase is never shown as current (design §8).
    let phaseSummary: String?
    let nextStart: Date?
    let nextName: String?
    let historyValue: String
    let historyNote: String?
    let checkedAt: Date?

    init(_ input: FlintGlanceInput, at date: Date, calendar: Calendar = .current) {
        checkedAt = input.checkedAt
        // A future check time or an unbounded/malformed validity window is not evidence.
        let valid = input.checkedAt.map { $0 <= date } == true
            && input.validUntil.map { $0 > date && $0 > (input.checkedAt ?? $0) } == true
        let expired = input.protectionEndsAt.map { $0 <= date } == true
        let headlineExpired = input.headline?.endsAt.map { $0 <= date } == true
        let sessionExpired = input.session.map { $0.endsAt <= date } == true
        let nextStarted = input.next.map { $0.startsAt <= date } == true
        let displayEnd = input.headline.map { $0.endsAt } ?? input.protectionEndsAt
        // A Pomodoro phase is truthful only inside its own published boundary and the snapshot's
        // validity. Past it, no phase is claimed at all (design §8). A planned rest is not
        // enforcing, so it is shown with its own title rather than a false protection claim.
        let phaseText: String? = {
            guard valid, !expired, !headlineExpired, !sessionExpired, !nextStarted,
                  ![.unavailable, .suspended, .pass].contains(input.protection),
                  let session = input.session, let phase = session.phase,
                  let end = session.phaseEndsAt, end > date else { return nil }
            switch phase {
            case .focus:
                let total = session.rounds ?? session.round ?? 0
                return total > 0 ? "Focus · round \(session.round ?? 1) of \(total)" : "Focus"
            case .rest:
                return "Rest · focus resumes \(end.formatted(date: .omitted, time: .shortened))"
            }
        }()
        if input.protection == .unavailable || input.checkedAt == nil || input.validUntil == nil {
            status = .unavailable
        } else if expired {
            status = .expired
        } else if !valid || headlineExpired || sessionExpired || nextStarted {
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
        case .idle:
            // A planned rest pauses the parent's own shield on purpose; the chain is still the
            // active commitment, so the phase names the rest instead of "no protection".
            title = phaseText == nil ? "No protection at last check" : "Pomodoro · planned rest"
            detail = phaseText == nil ? "Open Flint" : "Focus resumes at its scheduled time"
        case .saved: title = "Not armed"; detail = "Open Flint to review"
        case .armed: title = "Armed at last check"; detail = "Registration accepted"
        case .enforcing: title = "Enforcing at last check"; detail = "Reported by Flint"
        case .suspended: title = "Protection suspended"; detail = "Open Flint to review"
        case .pass: title = "Emergency Pass active"; detail = "Protection in recovery"
        case .degraded: title = "Protection degraded"; detail = "Open Flint to review"
        case .unavailable: title = "State unavailable"; detail = "Open Flint to check"
        case .stale: title = "Status needs checking"; detail = "Open Flint to refresh"
        case .expired: title = "Planned end reached"; detail = "Open Flint to check"
        }
        phaseSummary = phaseText
        if valid, [.armed, .enforcing, .degraded].contains(status), let cause = input.headline {
            causeSummary = "\(Self.sanitizedName(cause.name)) · \(cause.tier)"
        } else { causeSummary = nil }
        plannedEnd = valid && [.armed, .enforcing, .pass].contains(status) ? displayEnd : nil
        // Stop the timer at the first unsupported boundary, including snapshot freshness.
        if valid, [.armed, .enforcing].contains(status), let end = displayEnd,
           let until = input.validUntil, end > date {
            countdownEnd = min(end, until)
        } else { countdownEnd = nil }
        if valid, let next = input.next, next.startsAt > date, next.endsAt > next.startsAt {
            nextStart = next.startsAt
            nextName = next.name.map(Self.sanitizedName)
        } else { nextStart = nil; nextName = nil }

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
                let supported = valid && !expired && !headlineExpired && !sessionExpired && !nextStarted
                if history.inProgress {
                    historyValue = supported ? "\(duration) · in progress at update" : "\(duration) · as of update"
                } else { historyValue = duration }
                if history.availability == .degraded { historyNote = "Some history couldn't be read" }
                else if history.inProgress { historyNote = supported ? "In progress · as of update" : "Open Flint to refresh" }
                else { historyNote = nil }
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

    static func sanitizedName(_ value: String) -> String {
        let scalars = value.unicodeScalars.filter {
            !CharacterSet.controlCharacters.contains($0) && $0.properties.generalCategory != .format
        }
        let text = String(String.UnicodeScalarView(scalars)).split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return text.isEmpty ? "Focus session" : String(text.prefix(64))
    }

    /// Future entries may retire an old claim; they never predict arming or completion.
    static func boundaries(_ input: FlintGlanceInput, after date: Date, calendar: Calendar = .current) -> [Date] {
        let midnight = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: date))
        return Set([input.validUntil, input.protectionEndsAt, input.session?.endsAt,
                    input.session?.phaseEndsAt, input.headline?.endsAt, input.next?.startsAt, midnight]
                    .compactMap { $0 }.filter { $0 > date }).sorted()
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
    /// A Pomodoro chain's phase at the time the plan was made; nil for other sessions.
    var phaseSummary: String? = nil
    var phaseEndsAt: Date? = nil

    static func countdownLabel(endsAt: Date, staleAt: Date) -> String {
        staleAt < endsAt ? "Check in" : "Planned time left"
    }

    func mayRequest(lastRequestedSession: String?) -> Bool { lastRequestedSession != sessionID }

    static func make(_ input: FlintGlanceInput, optedIn: Bool, systemEnabled: Bool,
                     at date: Date) -> Self? {
        // A planned rest is not enforcing, but the chain is still the active commitment: keep
        // showing it while its own phase boundary is ahead (design §8).
        let resting = input.session?.phase == .rest
            && (input.session?.phaseEndsAt.map { $0 > date } ?? false)
        guard optedIn, systemEnabled,
              [.armed, .enforcing].contains(input.protection) || resting,
              let checked = input.checkedAt, checked <= date,
              let valid = input.validUntil, valid > date, valid > checked,
              let session = input.session, session.cause != .other,
              session.isArmed, !session.id.isEmpty,
              ["Easy", "Harder", "Hardcore"].contains(session.tier),
              session.startedAt <= date, session.endsAt > date,
              session.endsAt > session.startedAt else { return nil }
        return .init(sessionID: session.id,
                     cause: FlintGlancePresentation.sanitizedName(session.name ?? session.cause.rawValue.capitalized),
                     tier: session.tier, startedAt: session.startedAt,
                     endsAt: session.endsAt, staleAt: min(valid, session.endsAt),
                     phaseSummary: FlintGlancePresentation(input, at: date).phaseSummary,
                     phaseEndsAt: session.phaseEndsAt)
    }
}

/// Coalesce lifecycle/Darwin/preferences while an ActivityKit operation is suspended.
/// Only one mutation pass runs at a time; each pass re-reads authority and consent.
@MainActor
final class FlintGlanceRefreshQueue {
    private var refreshing = false
    private var pendingOptIn: Bool?
    var hasPending: Bool { pendingOptIn != nil }

    func refresh(optedIn: Bool, apply: (Bool) async -> Void) async {
        pendingOptIn = optedIn
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        while let requested = pendingOptIn {
            pendingOptIn = nil
            await apply(requested)
        }
    }
}
