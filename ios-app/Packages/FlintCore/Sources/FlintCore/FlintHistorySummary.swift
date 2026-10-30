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
    /// intervals retain possible coverage; focusedSeconds also discounts these placement budgets.
    public let uncertainGaps: [FlintUncertainFocusGap]
    public var focusedSeconds: TimeInterval { FlintHistoryBounds.lowerBound([self]) }
    public var isInProgress: Bool { outcome == .running }

    public init(id: String, name: String, startedAt: Date, endedAt: Date, outcome: FlintHistoryOutcome,
                inferred: Bool, route: String?, intervals: [DateInterval], uncertainGaps: [FlintUncertainFocusGap] = []) {
        self.id = id; self.name = name; self.startedAt = startedAt; self.endedAt = endedAt
        self.outcome = outcome; self.inferred = inferred; self.route = route; self.intervals = intervals
        self.uncertainGaps = uncertainGaps
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
            days[index].focusedSeconds = sessions.contains { !$0.uncertainGaps.isEmpty } ?
                FlintHistoryBounds.lowerBound(sessions, window: day) : intervals.reduce(0) { $0 + overlap($1, day) }
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
        let appendOrder = Dictionary(supported.map { ($0.element.id, $0.offset) }, uniquingKeysWith: min)
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
            func plausiblePlan(_ event: FlintHistoryEvent) -> Bool {
                !event.uncertainTime && event.at >= start.at &&
                    (event.plannedEnd.map { $0 > start.at && $0 >= event.at } ?? true)
            }
            var validPlanIDs: Set<String> = plausiblePlan(start) ? [start.id] : []
            var precedingPlan = start
            func ownerFinalPlan(_ revision: FlintHistoryEvent) -> Bool {
                guard let terminal else { return false }
                return revision.type == .rescheduled && revision.reason == "owner_final_plan" &&
                    revision.originRoute == "editor_terminal" && revision.timeConfidence == .certain &&
                    terminal.type == .completed && terminal.originRoute == "editor_terminal" &&
                    terminal.timeConfidence == .certain && !terminal.uncertainTime &&
                    revision.at == terminal.at && revision.plannedEnd == terminal.at
            }
            let revisions = plans.enumerated().sorted {
                let a = $0.element.observedAt ?? $0.element.at
                let b = $1.element.observedAt ?? $1.element.at
                return a == b ? $0.offset < $1.offset : a < b
            }.map(\.element)
            for revision in revisions where revision.id != start.id {
                if validPlanIDs.contains(precedingPlan.id), plausiblePlan(revision),
                   ownerFinalPlan(revision) || (precedingPlan.plannedEnd.map({ revision.at <= $0 }) ?? true) {
                    validPlanIDs.insert(revision.id)
                    precedingPlan = revision
                }
            }
            func validPlan(_ event: FlintHistoryEvent) -> Bool { validPlanIDs.contains(event.id) }
            func planAt(_ at: Int64) -> FlintHistoryEvent { latestPlan(plans.filter { validPlan($0) && $0.at <= at }) }
            let authoritativePlan = revisions.last { validPlan($0) && ownerFinalPlan($0) }
            let plan = authoritativePlan ?? latestPlan(plans)
            let plannedEnd = plan.plannedEnd.map(FlintHistoryEvent.date)
            func inPlan(_ event: FlintHistoryEvent) -> Bool {
                let plan = authoritativePlan ?? planAt(event.at)
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
            var localEvidence: [FlintHistoryEvent]
            if validTerminal { localEvidence = Array(events.prefix(terminalIndex!)) }
            else if let terminal { localEvidence = original.prefix { $0.id != terminal.id }.sorted { $0.at < $1.at } }
            else { localEvidence = events }
            for event in original where event.uncertainTime && (event.type == .breakStarted || event.type == .protectionDegraded) {
                if !localEvidence.contains(where: { $0.id == event.id }) { localEvidence.append(event) }
            }
            let relatedPasses = passes.filter {
                $0.sessions?.contains(id) == true && ($0.uncertainTime || !validTerminal || $0.at <= terminal!.at)
            }
            func rejectedTime(_ event: FlintHistoryEvent) -> Bool {
                if event.type == .started || event.type == .rescheduled {
                    let superseded = authoritativePlan != nil && event.type == .rescheduled && !event.uncertainTime
                    return !validPlan(event) && !superseded
                }
                if Self.coverageTypes.contains(event.type) {
                    let invertedBreak = event.type == .breakStarted && event.breakEnd.map { $0 < event.at } == true
                    return event.uncertainTime || !inPlan(event) || invertedBreak || (!validTerminal && event.date > now)
                }
                if event.type == .passUsed {
                    return event.uncertainTime || !inPlan(event) || event.recoveryEnd.map { $0 < event.at } == true
                }
                return false
            }
            let rejectedTimeIDs = Set((events + relatedPasses).filter(rejectedTime).map(\.id))
            // A rejected timestamp cannot precede an already recorded trusted witness.
            // Replay barriers once in append order; valid phase/grant pairing below still
            // uses logical time, including an end delivered before its matching start.
            let evidence = (localEvidence + relatedPasses).sorted { appendOrder[$0.id, default: 0] < appendOrder[$1.id, default: 0] }
            var trusted = validPlan(start)
            var canExtend = trusted
            var grantBarrier: Int64?
            var coverageEnd = start.date
            for event in evidence {
                if rejectedTimeIDs.contains(event.id) {
                    trusted = false
                    canExtend = false
                } else if Self.coverageTypes.contains(event.type) {
                    let paired = event.type != .breakEnded || event.grant == nil || events.contains {
                        $0.type == .breakStarted && $0.grant == event.grant && $0.at <= event.at && !rejectedTimeIDs.contains($0.id)
                    }
                    if !paired {
                        trusted = false
                        grantBarrier = min(grantBarrier ?? event.at, event.at)
                    } else if canExtend && (grantBarrier.map { event.at < $0 } ?? true) {
                        coverageEnd = max(coverageEnd, event.date)
                    }
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
            // Each accepted witness was already bounded by its then-valid plan. A later
            // rejected revision cannot retroactively erase that independent prefix.
            let planCeiling = outcome == .unconfirmed ? evidencedEnd : plannedEnd ?? evidencedEnd
            let clippedEnd = min(evidencedEnd, planCeiling, readCeiling)
            var intervals: [DateInterval] = []
            if clippedEnd > start.date {
                let whole = DateInterval(start: start.date, end: clippedEnd)
                var breaks: [DateInterval] = []
                // Share time admission with coverage so rejected backdated cuts cannot
                // erase the preserved prefix during subtraction.
                let phases = events.filter { !rejectedTimeIDs.contains($0.id) }
                for (index, event) in phases.enumerated() where event.type == .breakStarted {
                    let nextBoundary = phases.dropFirst(index + 1).first {
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
                for event in phases {
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
                for pass in relatedPasses where !rejectedTimeIDs.contains(pass.id) {
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
            let anchors = (localEvidence + (validTerminal ? [terminal!] : [])).filter {
                !$0.uncertainTime && !rejectedTimeIDs.contains($0.id) &&
                    ($0.id == start.id || $0.id == terminal?.id || $0.type == .openIntercepted || $0.type == .protectionRestored)
            }.map(\.at)
            func uncertainGap(_ event: FlintHistoryEvent, knownStart: Int64? = nil, knownEnd: Int64? = nil,
                              endHint: Int64? = nil, maximum: TimeInterval = .greatestFiniteMagnitude) -> FlintUncertainFocusGap {
                let limit = FlintHistoryEvent.milliseconds(clippedEnd)
                let ceiling = validTerminal ? min(plan.plannedEnd ?? terminal!.at, terminal!.at) :
                    revisions.last(where: validPlan)?.plannedEnd ?? FlintHistoryEvent.milliseconds(max(now, clippedEnd))
                let placeable = event.at >= start.at && event.at <= ceiling && (endHint.map { $0 >= event.at } ?? true)
                let a = knownStart ?? (placeable ? anchors.filter { $0 <= event.at }.max() ?? start.at : start.at)
                let b = knownEnd ?? (placeable && endHint != nil ? anchors.filter { $0 >= endHint! }.min() ?? limit : limit)
                let lower = max(start.date, FlintHistoryEvent.date(a)), upper = min(clippedEnd, FlintHistoryEvent.date(b))
                return .init(window: DateInterval(start: lower, end: max(lower, upper)), maximumSeconds: maximum)
            }
            var uncertainGaps: [FlintUncertainFocusGap] = []
            for event in localEvidence where event.uncertainTime {
                switch event.type {
                case .breakStarted:
                    let close = localEvidence.first { $0.type == .breakEnded && $0.grant == event.grant &&
                        $0.at >= event.at && !rejectedTimeIDs.contains($0.id) }
                    uncertainGaps.append(uncertainGap(event, knownEnd: close?.at, endHint: close?.at ?? event.breakEnd,
                        maximum: event.kind == "ordinary" ? 300 : .greatestFiniteMagnitude))
                case .breakEnded:
                    if let opening = localEvidence.first(where: { $0.type == .breakStarted && $0.grant == event.grant &&
                        $0.at <= event.at && !$0.uncertainTime && !rejectedTimeIDs.contains($0.id) }) {
                        uncertainGaps.append(uncertainGap(event, knownStart: opening.at, endHint: event.at,
                            maximum: opening.kind == "ordinary" ? 300 : .greatestFiniteMagnitude))
                    }
                case .protectionDegraded:
                    let restored = localEvidence.first { $0.type == .protectionRestored && $0.at >= event.at &&
                        !rejectedTimeIDs.contains($0.id) }
                    uncertainGaps.append(uncertainGap(event, knownEnd: restored?.at))
                default: break
                }
            }
            for pass in relatedPasses where pass.uncertainTime {
                uncertainGaps.append(uncertainGap(pass, endHint: pass.recoveryEnd, maximum: 3600))
            }
            return .init(id: id, name: start.name ?? "Focus", startedAt: start.date, endedAt: end,
                         outcome: outcome, inferred: inferred, route: route, intervals: intervals, uncertainGaps: uncertainGaps)
        }.sorted { $0.startedAt == $1.startedAt ? $0.id < $1.id : $0.startedAt < $1.startedAt }
    }

    private static let coverageTypes: [FlintHistoryEventType] = [
        .breakStarted, .breakEnded, .protectionDegraded, .protectionRestored, .openIntercepted,
    ]

    private static func overlap(_ a: DateInterval, _ b: DateInterval) -> TimeInterval {
        max(0, min(a.end, b.end).timeIntervalSince(max(a.start, b.start)))
    }
    static func union(_ intervals: [DateInterval]) -> [DateInterval] {
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
