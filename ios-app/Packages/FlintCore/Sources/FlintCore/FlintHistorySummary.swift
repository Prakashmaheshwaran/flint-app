import Foundation

public enum FlintHistoryOutcome: Equatable, Sendable {
    case running, completed, endedEarly, superseded
}

public struct FlintHistorySessionSummary: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let startedAt: Date
    public let endedAt: Date
    public let outcome: FlintHistoryOutcome
    public let inferred: Bool
    public let route: String?
    public let intervals: [DateInterval]
    public var focusedSeconds: TimeInterval { intervals.reduce(0) { $0 + $1.duration } }
    public var isInProgress: Bool { outcome == .running }
}

public struct FlintHistoryDay: Equatable, Sendable, Identifiable {
    public var id: Date { start }
    public let start: Date
    /// nil means before historyStart or unavailable. Zero is an observed day without focus.
    public var focusedSeconds: TimeInterval?
    public var completed = 0
    public var endedEarly = 0
    public var inProgress = false
}

public struct FlintHistorySummary: Sendable {
    public let availability: FlintHistoryAvailability
    public let sessions: [FlintHistorySessionSummary]
    public let days: [FlintHistoryDay]
    public let streak: Int
    public var focusedSeconds: TimeInterval { days.compactMap(\.focusedSeconds).reduce(0, +) }
}

public enum FlintHistoryAggregator {
    public static func summarize(_ snapshot: FlintHistorySnapshot, activeSessionID: String?,
                                 now: Date, calendar: Calendar = .current, dayCount: Int = 7) -> FlintHistorySummary {
        let sessions = sessionSummaries(snapshot.events, activeSessionID: activeSessionID, now: now)
        let today = calendar.startOfDay(for: now)
        let historyDay = snapshot.historyStart.map { calendar.startOfDay(for: $0) }
        var days = (0..<max(1, min(401, dayCount))).reversed().compactMap { offset -> FlintHistoryDay? in
            guard let start = calendar.date(byAdding: .day, value: -offset, to: today) else { return nil }
            let hasData = snapshot.availability != .unavailable && historyDay.map { start >= $0 } == true
            return FlintHistoryDay(start: start, focusedSeconds: hasData ? 0 : nil)
        }
        // Union globally, *after* subtracting each session's own breaks.
        let intervals = union(sessions.flatMap(\.intervals))
        for index in days.indices {
            guard days[index].focusedSeconds != nil,
                  let next = calendar.date(byAdding: .day, value: 1, to: days[index].start) else { continue }
            let day = DateInterval(start: days[index].start, end: next)
            days[index].focusedSeconds = intervals.reduce(0) { $0 + overlap($1, day) }
            for session in sessions {
                if session.isInProgress, session.intervals.contains(where: { overlap($0, day) > 0 }) {
                    days[index].inProgress = true
                }
                guard session.endedAt >= day.start, session.endedAt < day.end else { continue }
                switch session.outcome {
                case .completed: days[index].completed += 1
                case .endedEarly, .superseded: days[index].endedEarly += 1
                case .running: days[index].inProgress = true
                }
            }
        }
        let completedDays = Set(sessions.filter { $0.outcome == .completed }.map { calendar.startOfDay(for: $0.endedAt) })
        var cursor = completedDays.contains(today) ? today : calendar.date(byAdding: .day, value: -1, to: today)
        var streak = 0
        while let day = cursor, let historyDay, day >= historyDay, completedDays.contains(day) {
            streak += 1
            cursor = calendar.date(byAdding: .day, value: -1, to: day)
        }
        return .init(availability: snapshot.availability, sessions: sessions, days: days, streak: streak)
    }

    public static func sessionSummaries(_ records: [FlintHistoryEvent], activeSessionID: String?,
                                       now: Date) -> [FlintHistorySessionSummary] {
        let grouped = Dictionary(grouping: records.enumerated().filter { $0.element.isSupported }, by: { $0.element.session })
        return grouped.compactMap { id, indexed -> FlintHistorySessionSummary? in
            let events = indexed.sorted {
                $0.element.at == $1.element.at ? $0.offset < $1.offset : $0.element.at < $1.element.at
            }.map(\.element)
            guard let start = events.first(where: { $0.type == .started }) else { return nil }
            let terminal = events.first(where: { $0.type.isTerminal })
            let plannedEnd = start.plannedEnd.map(FlintHistoryEvent.date)
            let outcome: FlintHistoryOutcome
            let end: Date
            let inferred: Bool
            let route: String?
            if let terminal {
                outcome = terminal.type == .completed ? .completed : terminal.type == .superseded ? .superseded : .endedEarly
                end = terminal.date; inferred = terminal.inferred == true; route = terminal.route
            } else if let plannedEnd, plannedEnd <= now {
                outcome = .completed; end = plannedEnd; inferred = true; route = nil
            } else if id == activeSessionID {
                outcome = .running; end = now; inferred = false; route = nil
            } else {
                outcome = .endedEarly; end = now; inferred = true; route = "unknown"
            }
            let clippedEnd = min(end, plannedEnd ?? end)
            let unknownEnd = outcome == .endedEarly && inferred && route == "unknown"
            var intervals: [DateInterval] = []
            if clippedEnd > start.date, !unknownEnd {
                let whole = DateInterval(start: start.date, end: clippedEnd)
                var breaks: [DateInterval] = []
                for (index, event) in events.enumerated() where event.type == .breakStarted {
                    let explicitEnd = events.dropFirst(index + 1).first { $0.type == .breakEnded }?.date
                    let breakEnd = explicitEnd ?? event.breakEnd.map(FlintHistoryEvent.date) ?? clippedEnd
                    let a = max(event.date, whole.start), b = min(breakEnd, whole.end)
                    if b > a { breaks.append(DateInterval(start: a, end: b)) }
                }
                var cursor = whole.start
                for pause in union(breaks) {
                    if pause.start > cursor { intervals.append(DateInterval(start: cursor, end: pause.start)) }
                    cursor = max(cursor, pause.end)
                }
                if cursor < whole.end { intervals.append(DateInterval(start: cursor, end: whole.end)) }
            }
            return .init(id: id, name: start.name ?? "Focus", startedAt: start.date, endedAt: end,
                         outcome: outcome, inferred: inferred, route: route, intervals: intervals)
        }.sorted { $0.startedAt == $1.startedAt ? $0.id < $1.id : $0.startedAt < $1.startedAt }
    }

    private static func overlap(_ a: DateInterval, _ b: DateInterval) -> TimeInterval {
        max(0, min(a.end, b.end).timeIntervalSince(max(a.start, b.start)))
    }
    private static func union(_ intervals: [DateInterval]) -> [DateInterval] {
        var result: [DateInterval] = []
        for interval in intervals.sorted(by: { $0.start < $1.start }) {
            if let previous = result.last, interval.start <= previous.end {
                result[result.count - 1] = DateInterval(start: previous.start, end: max(previous.end, interval.end))
            } else { result.append(interval) }
        }
        return result
    }
}

public enum FlintHistoryReconciler {
    /// Host-only. Repeated/concurrent reconciles may append duplicates; first-terminal-wins
    /// aggregation makes those idempotent. It never changes the active/enforcement store.
    public static func reconcile(store: FlintHistoryStore, activeSession: FlintActiveSession?, now: Date,
                                 calendar: Calendar = .current) -> FlintHistorySnapshot {
        let snapshot = store.read()
        guard snapshot.availability != .unavailable else { return snapshot }
        let terminalIDs = Set(snapshot.events.filter { $0.isSupported && $0.type.isTerminal }.map(\.session))
        for session in FlintHistoryAggregator.sessionSummaries(snapshot.events, activeSessionID: activeSession?.id, now: now)
            where session.inferred && !terminalIDs.contains(session.id) {
            store.append(FlintHistoryEvent(type: session.outcome == .completed ? .completed : .endedEarly,
                session: session.id, at: session.endedAt, timeZone: calendar.timeZone, inferred: true, route: session.route))
        }
        store.compactIfNeeded()
        return store.read()
    }
}
