import Foundation

public enum FlintHistoryOutcome: Equatable, Sendable {
    case running, completed, endedEarly, superseded, unconfirmed
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

    public init(id: String, name: String, startedAt: Date, endedAt: Date, outcome: FlintHistoryOutcome,
                inferred: Bool, route: String?, intervals: [DateInterval]) {
        self.id = id; self.name = name; self.startedAt = startedAt; self.endedAt = endedAt
        self.outcome = outcome; self.inferred = inferred; self.route = route; self.intervals = intervals
    }
}

public struct FlintHistoryDay: Equatable, Sendable, Identifiable {
    public var id: Date { start }
    public let start: Date
    /// nil means before historyStart or unavailable. Zero is an observed day without focus.
    public var focusedSeconds: TimeInterval?
    public var completed = 0
    public var endedEarly = 0
    public var inProgress = false
    public var unconfirmed = 0

    public init(start: Date, focusedSeconds: TimeInterval?, completed: Int = 0, endedEarly: Int = 0,
                inProgress: Bool = false, unconfirmed: Int = 0) {
        self.start = start; self.focusedSeconds = focusedSeconds; self.completed = completed
        self.endedEarly = endedEarly; self.inProgress = inProgress
        self.unconfirmed = unconfirmed
    }
}

public struct FlintHistorySummary: Sendable {
    public let availability: FlintHistoryAvailability
    public let sessions: [FlintHistorySessionSummary]
    public let days: [FlintHistoryDay]
    public let streak: Int
    public var focusedSeconds: TimeInterval { days.compactMap(\.focusedSeconds).reduce(0, +) }

    public init(availability: FlintHistoryAvailability, sessions: [FlintHistorySessionSummary],
                days: [FlintHistoryDay], streak: Int) {
        self.availability = availability; self.sessions = sessions; self.days = days; self.streak = streak
    }
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
                case .unconfirmed: days[index].unconfirmed += 1
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
        let supported = records.enumerated().filter { $0.element.isSupported }
        let passes = supported.map(\.element).filter { $0.type == .passUsed && $0.sessions != nil }
        let grouped = Dictionary(grouping: supported, by: { $0.element.session })
        return grouped.compactMap { id, indexed -> FlintHistorySessionSummary? in
            let events = indexed.sorted {
                $0.element.at == $1.element.at ? $0.offset < $1.offset : $0.element.at < $1.element.at
            }.map(\.element)
            let original = indexed.map(\.element)
            guard let start = original.first(where: { $0.type == .started }) else { return nil }
            let terminalIndex = events.firstIndex(where: { $0.type.isTerminal })
            let terminal = terminalIndex.map { events[$0] }
            let planIDs = Set(events.prefix(terminalIndex ?? events.count)
                .filter { $0.type == .started || $0.type == .rescheduled }.map(\.id))
            // Equal observation milliseconds retain append/revision order, independently
            // of the logical order used to pair phases and locate the terminal.
            let recordedPlans = original.filter { planIDs.contains($0.id) }
            let plans = recordedPlans.isEmpty ? [start] : recordedPlans
            func latestPlan(_ candidates: [FlintHistoryEvent]) -> FlintHistoryEvent {
                candidates.enumerated().max {
                    let a = $0.element.observedAt ?? $0.element.at
                    let b = $1.element.observedAt ?? $1.element.at
                    return a == b ? $0.offset < $1.offset : a < b
                }?.element ?? start
            }
            func planAt(_ at: Int64) -> FlintHistoryEvent { latestPlan(plans.filter { $0.at <= at }) }
            let plan = latestPlan(plans)
            let plannedEnd = plan.plannedEnd.map(FlintHistoryEvent.date)
            func validPlan(_ event: FlintHistoryEvent) -> Bool {
                !event.uncertainTime && event.at >= start.at && (event.plannedEnd.map { $0 > start.at } ?? true)
            }
            func inPlan(_ event: FlintHistoryEvent) -> Bool {
                let plan = planAt(event.at)
                return event.at >= start.at && validPlan(plan) && (plan.plannedEnd.map { event.at <= $0 } ?? true)
            }
            let unknownEnd = terminal?.type == .endedEarly && terminal?.inferred == true && terminal?.route == "unknown"
            let validTerminal = terminal.map { event in
                guard !event.uncertainTime, !unknownEnd, validPlan(plan), event.at > start.at else { return false }
                if event.type == .completed {
                    guard let end = plan.plannedEnd else { return false }
                    return event.timeConfidence == .certain ? event.at >= end : event.at == end
                }
                return plan.plannedEnd.map { event.at <= $0 } ?? true
            } ?? false
            // Delayed outbox delivery is ordered by logical event time, not append order.
            // Once a terminal is accepted, later cleanup cannot invalidate that decision.
            let localEvidence: [FlintHistoryEvent]
            if validTerminal { localEvidence = Array(events.prefix(terminalIndex!)) }
            else if let terminal { localEvidence = original.prefix { $0.id != terminal.id }.sorted { $0.at < $1.at } }
            else { localEvidence = events }
            let relatedPasses = passes.filter { $0.sessions?.contains(id) == true && (!validTerminal || $0.at <= terminal!.at) }
            let evidence = (localEvidence + relatedPasses).sorted { $0.at < $1.at }
            var trusted = validPlan(start)
            var coverageEnd = start.date
            for event in evidence {
                if event.type == .started || event.type == .rescheduled {
                    if !validPlan(event) { trusted = false }
                } else if Self.coverageTypes.contains(event.type) {
                    let paired = event.type != .breakEnded || event.grant == nil || events.contains {
                        $0.type == .breakStarted && $0.grant == event.grant && $0.at <= event.at
                    }
                    let invertedBreak = event.type == .breakStarted && event.breakEnd.map { $0 < event.at } == true
                    if event.uncertainTime || !inPlan(event) || !paired || invertedBreak || (!validTerminal && event.date > now) {
                        trusted = false
                    } else if trusted { coverageEnd = max(coverageEnd, event.date) }
                } else if event.type == .passUsed && (event.uncertainTime || event.recoveryEnd.map { $0 < event.at } == true) {
                    trusted = false
                }
            }
            let outcome: FlintHistoryOutcome
            let end: Date
            let inferred: Bool
            let route: String?
            if !trusted || (terminal != nil && !validTerminal) {
                outcome = .unconfirmed
                // A rejected terminal is raw evidence, not a trustworthy display day.
                end = validPlan(plan) ? min(plannedEnd ?? now, now) : now
                inferred = true; route = terminal?.route
            } else if let terminal {
                outcome = terminal.type == .completed ? .completed : terminal.type == .superseded ? .superseded : .endedEarly
                end = terminal.date; inferred = terminal.inferred == true; route = terminal.route
            } else if let plannedEnd, plannedEnd <= now {
                outcome = .unconfirmed; end = plannedEnd; inferred = true; route = nil
            } else if id == activeSessionID {
                outcome = .running; end = now; inferred = false; route = nil
            } else {
                outcome = .unconfirmed; end = now; inferred = true; route = "unknown"
            }
            // A plan/start alone proves no elapsed coverage. Later lifecycle observations
            // can confirm only the prefix through that observation, never the planned tail.
            let evidencedEnd = outcome == .unconfirmed ? coverageEnd : end
            let readCeiling = outcome == .running || terminal == nil ? now : evidencedEnd
            let clippedEnd = min(evidencedEnd, plannedEnd ?? evidencedEnd, readCeiling)
            var intervals: [DateInterval] = []
            if clippedEnd > start.date {
                let whole = DateInterval(start: start.date, end: clippedEnd)
                var breaks: [DateInterval] = []
                for (index, event) in events.enumerated() where event.type == .breakStarted {
                    let nextBoundary = events.dropFirst(index + 1).first {
                        if let grant = event.grant { return $0.type == .breakEnded && $0.grant == grant && !$0.uncertainTime }
                        return $0.type == .breakStarted || ($0.type == .breakEnded && !$0.uncertainTime)
                    }
                    let explicitEnd = nextBoundary?.type == .breakEnded ? nextBoundary?.date : nil
                    let breakEnd = explicitEnd ?? event.breakEnd.map(FlintHistoryEvent.date) ?? clippedEnd
                    let a = max(event.date, whole.start), b = min(breakEnd, whole.end)
                    if b > a { breaks.append(DateInterval(start: a, end: b)) }
                }
                // Repeated degraded observations do not close an existing coverage gap.
                var gapStart: Date?
                for event in events {
                    if event.type == .protectionDegraded { gapStart = gapStart ?? event.date }
                    if event.type == .protectionRestored, !event.uncertainTime, let gap = gapStart {
                        let a = max(gap, whole.start), b = min(event.date, whole.end)
                        if b > a { breaks.append(DateInterval(start: a, end: b)) }
                        gapStart = nil
                    }
                }
                if let gap = gapStart, max(gap, whole.start) < whole.end {
                    breaks.append(DateInterval(start: max(gap, whole.start), end: whole.end))
                }
                for pass in passes where pass.sessions?.contains(id) == true {
                    guard let recoveryEnd = pass.recoveryEnd else { continue }
                    let a = max(pass.date, whole.start), b = min(FlintHistoryEvent.date(recoveryEnd), whole.end)
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

    private static let coverageTypes: [FlintHistoryEventType] = [
        .breakStarted, .breakEnded, .protectionDegraded, .protectionRestored, .openIntercepted,
    ]

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

private extension FlintHistoryEvent {
    var uncertainTime: Bool { timeConfidence?.isUncertain == true || reason == "time_uncertain" }
}

public enum FlintHistoryReconciler {
    /// Drain committed outbox records and maintain retention. History is a projection:
    /// only the lifecycle owner may commit an end, including during cold-start recovery.
    public static func reconcile(store: FlintHistoryStore, activeSession: FlintActiveSession?, now: Date,
                                 calendar: Calendar = .current) -> FlintHistorySnapshot {
        let snapshot = store.read()
        guard snapshot.availability != .unavailable else { return snapshot }
        store.compactIfNeeded()
        return store.read()
    }
}
