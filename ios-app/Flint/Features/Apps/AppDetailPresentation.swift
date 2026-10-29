import Foundation
import FlintCore

/// What the App/Site detail screen says about one picked app or site, derived only from lane B's
/// read-only `FlintProtectionStatus.contributors` result. Pure so its wording is testable; it never
/// guesses a cause the query didn't return (ADR-012 §3, §5, §8).
struct AppDetailPresentation: Equatable {
    struct Input: Equatable {
        var state: FlintProtectionStatusSnapshot.State
        var causes: [FlintProtectionStatusSnapshot.Cause]
        var openCount: Int?
        var openCap: Int?
        var coverageUncertain: Bool
    }

    struct Row: Equatable, Identifiable {
        let id: String
        let name: String
        let kind: String
        let tier: String
        let detail: String
    }

    enum Headline: Equatable {
        case unavailable
        case paused
        /// `others` = how many more enforcing rules also block it; `until` = when the last known
        /// one ends, nil when one of them runs until it's stopped (ADR-012 §3).
        case blocked(name: String, others: Int, until: Date?)
        case coveredNotBlocking
        /// Only limits cover it and Flint can't confirm whether one is blocking right now.
        case coveredByLimitUnconfirmed
        case uncertain
        case notCovered
    }

    let headline: Headline
    let rows: [Row]
    /// "2 of 3" when the shared daily counter is readable; nil when no Open Limit covers it.
    let opens: OpensLine?
    let showsUncertaintyNote: Bool
    let showsDegradedNote: Bool

    enum OpensLine: Equatable {
        case used(Int, of: Int)
        case unavailable(cap: Int)
    }

    init(_ input: Input) {
        let causes = Self.ordered(input.causes)
        let strongest = causes.first { $0.enforcing }
        switch input.state {
        case .unavailable:
            headline = .unavailable
        case .suspended:
            headline = .paused
        default:
            if let strongest {
                // "Until" is when the last known enforcing contributor ends; nil = until stopped.
                let enforcing = causes.filter(\.enforcing)
                let until = enforcing.contains { $0.end == nil } ? nil : enforcing.compactMap(\.end).max()
                headline = .blocked(name: strongest.displayName, others: enforcing.count - 1, until: until)
            } else if causes.contains(where: { Self.isLimit($0.kind) }) {
                headline = .coveredByLimitUnconfirmed
            } else if !causes.isEmpty {
                headline = .coveredNotBlocking
            } else if input.coverageUncertain {
                headline = .uncertain
            } else {
                headline = .notCovered
            }
        }
        rows = input.state == .unavailable ? [] : causes.map(Self.row)
        if input.state == .unavailable {
            opens = nil
        } else if let cap = input.openCap {
            opens = input.openCount.map { .used($0, of: cap) } ?? .unavailable(cap: cap)
        } else {
            opens = nil
        }
        showsUncertaintyNote = input.state != .unavailable && input.coverageUncertain && !causes.isEmpty
        showsDegradedNote = input.state == .degraded
    }

    /// Strongest first: tier, then latest end (no end = ongoing, latest), then stable id.
    static func ordered(_ causes: [FlintProtectionStatusSnapshot.Cause]) -> [FlintProtectionStatusSnapshot.Cause] {
        causes.sorted { a, b in
            if a.enforcing != b.enforcing { return a.enforcing }
            if rank(a.tier) != rank(b.tier) { return rank(a.tier) > rank(b.tier) }
            let ae = a.end ?? .distantFuture, be = b.end ?? .distantFuture
            if ae != be { return ae > be }
            return a.id < b.id
        }
    }

    private static func rank(_ tier: BreakLevel) -> Int {
        switch tier {
        case .easy: 0
        case .harder: 1
        case .hardcore: 2
        }
    }

    /// Limits report blocking only when their monitor's state is known; never claim "not
    /// blocking" for them (ADR-012 §5/§8).
    static func isLimit(_ kind: FlintProtectionStatusSnapshot.Kind) -> Bool {
        kind == .timeLimit || kind == .openLimit
    }

    static func endText(_ end: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
        calendar.isDate(end, inSameDayAs: now)
            ? end.formatted(date: .omitted, time: .shortened)
            : end.formatted(.dateTime.weekday(.abbreviated).hour().minute())
    }

    private static func row(_ cause: FlintProtectionStatusSnapshot.Cause) -> Row {
        let end = cause.end.map { endText($0) }
        let detail: String
        switch (cause.enforcing, end) {
        case (true, let end?): detail = String(localized: "Blocking now · until \(end)")
        case (true, nil): detail = String(localized: "Blocking now · until it’s stopped")
        case (false, _) where cause.kind == .timeLimit:
            detail = String(localized: "Blocks once today’s time is used")
        case (false, _) where cause.kind == .openLimit:
            detail = String(localized: "Blocks between the opens you’re allowed")
        case (false, let end?): detail = String(localized: "Active, not blocking right now · ends \(end)")
        case (false, nil): detail = String(localized: "Active, not blocking right now")
        }
        return Row(id: cause.id, name: cause.displayName, kind: kindName(cause.kind),
                   tier: tierName(cause.tier), detail: detail)
    }

    static func kindName(_ kind: FlintProtectionStatusSnapshot.Kind) -> String {
        switch kind {
        case .session: String(localized: "Focus session")
        case .pomodoro: String(localized: "Pomodoro")
        case .recurringSchedule: String(localized: "Schedule")
        case .oneShotSchedule: String(localized: "One-time schedule")
        case .sleep: String(localized: "Sleep")
        case .timeLimit: String(localized: "Time limit")
        case .openLimit: String(localized: "Open limit")
        }
    }

    static func tierName(_ tier: BreakLevel) -> String {
        switch tier {
        case .easy: String(localized: "Easy")
        case .harder: String(localized: "Harder")
        case .hardcore: String(localized: "Hardcore")
        }
    }
}
