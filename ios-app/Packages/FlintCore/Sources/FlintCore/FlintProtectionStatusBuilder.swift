import Foundation

/// Pure projection from explicit observations. Registration acceptance is advisory health,
/// not physical-device proof. This builder neither loads storage nor mutates protection.
public enum FlintProtectionStatusBuilder {
    public typealias Snapshot = FlintProtectionStatusSnapshot

    public struct SessionInput: Sendable {
        public var session: FlintActiveSession
        public var armed: Bool
        public var enforcing: Bool
        public var kind: Snapshot.Session.Kind
        public var origin: Snapshot.Session.Origin

        public init(_ session: FlintActiveSession, armed: Bool, enforcing: Bool = true,
                    kind: Snapshot.Session.Kind = .session, origin: Snapshot.Session.Origin = .appUI) {
            self.session = session
            self.armed = armed
            self.enforcing = enforcing
            self.kind = kind
            self.origin = origin
        }
    }

    /// Metadata only: selections/target keys cannot be encoded into the shared projection.
    /// Schedule windows are resolved by FlintScheduleWindow, or supplied as frozen instants
    /// for one-shot occurrences. Limits use `active` and expire at the quota midnight.
    public struct RuleInput: Sendable {
        public var id: String
        public var kind: Snapshot.Kind
        public var displayName: String
        public var tier: BreakLevel
        public var enabled: Bool
        public var armed: Bool
        public var observed: Bool
        public var available: Bool
        public var currentWindow: DateInterval?
        public var nextWindow: DateInterval?
        public var active: Bool
        /// A Time Limit claims enforcement only for known true; nil never means exhausted.
        public var thresholdReached: Bool?
        /// Allows the caller to describe planned rests / accepted Open credits / scoped gaps.
        public var enforcing: Bool
        public var nextBoundary: Date?
        public var opensAllowed: Int?

        public init(id: String, kind: Snapshot.Kind, displayName: String, tier: BreakLevel,
                    enabled: Bool = true, armed: Bool, observed: Bool, available: Bool = true,
                    currentWindow: DateInterval? = nil, nextWindow: DateInterval? = nil,
                    active: Bool = false, thresholdReached: Bool? = nil, enforcing: Bool = true,
                    nextBoundary: Date? = nil, opensAllowed: Int? = nil) {
            self.id = id
            self.kind = kind
            self.displayName = displayName
            self.tier = tier
            self.enabled = enabled
            self.armed = armed
            self.observed = observed
            self.available = available
            self.currentWindow = currentWindow
            self.nextWindow = nextWindow
            self.active = active
            self.thresholdReached = thresholdReached
            self.enforcing = enforcing
            self.nextBoundary = nextBoundary
            self.opensAllowed = opensAllowed
        }

        public static func schedule(id: String, kind: Snapshot.Kind = .recurringSchedule,
                                    displayName: String, tier: BreakLevel, schedule: FlintSchedule,
                                    enabled: Bool = true, armed: Bool, observed: Bool,
                                    now: Date, calendar: Calendar) -> Self {
            let windows = FlintScheduleWindow.resolve(schedule, at: now, calendar: calendar)
            return Self(id: id, kind: kind, displayName: displayName, tier: tier,
                        enabled: enabled, armed: armed, observed: observed, available: windows != nil,
                        currentWindow: windows?.current, nextWindow: windows?.next)
        }

        var isLimit: Bool { kind == .timeLimit || kind == .openLimit }

        func isActive(at now: Date) -> Bool {
            guard enabled else { return false }
            if isLimit { return active }
            guard let currentWindow else { return false }
            return currentWindow.start <= now && now < currentWindow.end
        }
    }

    public struct Inputs: Sendable {
        public var now: Date
        /// The persisted quota zone belongs in this calendar; do not auto-reset on travel.
        public var quotaCalendar: Calendar
        public var activeSession: SessionInput?
        public var recordUnreadable: Bool
        public var rules: [RuleInput]
        /// nil means authorization could not be read, false means known permission loss.
        public var authorized: Bool?
        public var pass: Snapshot.Pass
        /// nil means the required time state is unavailable; unverifiable means uncertainty.
        public var timeIntegrity: FlintTimeIntegrity.Verdict?
        /// No live duration anchor needs assessment. This is distinct from a trusted clock.
        public var timeIntegrityApplies: Bool
        public var available: Bool

        public init(now: Date, quotaCalendar: Calendar = .current, activeSession: SessionInput? = nil,
                    recordUnreadable: Bool = false, rules: [RuleInput] = [], authorized: Bool?,
                    pass: Snapshot.Pass, timeIntegrity: FlintTimeIntegrity.Verdict?, available: Bool = true,
                    timeIntegrityApplies: Bool = true) {
            self.now = now
            self.quotaCalendar = quotaCalendar
            self.activeSession = activeSession
            self.recordUnreadable = recordUnreadable
            self.rules = rules
            self.authorized = authorized
            self.pass = pass
            self.timeIntegrity = timeIntegrity
            self.timeIntegrityApplies = timeIntegrityApplies
            self.available = available
        }
    }

    public static func make(_ inputs: Inputs) -> Snapshot {
        let now = inputs.now
        let enabled = inputs.rules.filter(\.enabled)
        var reasons = Set<String>()
        var unavailable = !inputs.available || inputs.authorized == nil
            || (inputs.timeIntegrityApplies && inputs.timeIntegrity == nil)
            || inputs.pass.eligibility == .unavailable || enabled.contains { !$0.available }
        if inputs.recordUnreadable {
            unavailable = true
            reasons.insert("record_unreadable")
        }
        if unavailable { reasons.insert("state_unavailable") }
        if inputs.authorized == false { reasons.insert("not_authorized") }
        let timeTrusted = !inputs.timeIntegrityApplies || inputs.timeIntegrity == .consistent
        if inputs.timeIntegrityApplies && inputs.timeIntegrity != nil && !timeTrusted { reasons.insert("time_uncertain") }
        let recovery = inputs.pass.recoveryUntil.flatMap { $0 > now ? $0 : nil }
        let canEnforce = !unavailable && inputs.authorized == true && timeTrusted && recovery == nil
        var boundaries = [now.addingTimeInterval(24 * 3600)]
        var causes: [Snapshot.Cause] = []
        var session: Snapshot.Session?
        var occurrences: [Snapshot.Occurrence] = []
        if let recovery { boundaries.append(recovery) }
        if let next = inputs.pass.nextAvailableAt, next > now { boundaries.append(next) }

        if let input = inputs.activeSession {
            let active = input.session
            if active.startedAt > now {
                unavailable = true
                reasons.insert("time_uncertain")
                reasons.insert("state_unavailable")
            } else if !active.isExpired(at: now) {
                if let end = active.endsAt { boundaries.append(end) }
                if !input.armed { reasons.insert("registration_failed") }
                if input.armed && !inputs.recordUnreadable && !unavailable && recovery == nil {
                    session = Snapshot.Session(id: active.id, kind: input.kind, name: active.name,
                        tier: active.breakLevel, startedAt: active.startedAt, logicalEnd: active.endsAt,
                        origin: input.origin, armed: true)
                }
                causes.append(Snapshot.Cause(id: active.id,
                    kind: input.kind == .pomodoro ? .pomodoro : .session, displayName: active.name,
                    tier: active.breakLevel, end: active.endsAt,
                    enforcing: canEnforce && input.armed && input.enforcing))
            }
        }

        let midnight = inputs.quotaCalendar.date(byAdding: .day, value: 1,
                                                 to: inputs.quotaCalendar.startOfDay(for: now))
        for rule in enabled {
            if !rule.armed { reasons.insert("registration_failed") }
            if !rule.observed { reasons.insert("registration_unobserved") }
            if let boundary = rule.nextBoundary, boundary > now { boundaries.append(boundary) }
            if let window = rule.nextWindow, window.start > now {
                boundaries.append(window.start)
                occurrences.append(.init(causeID: rule.id, kind: rule.kind,
                    displayName: rule.displayName, start: window.start, end: window.end))
            }
            if rule.isLimit {
                if let midnight, midnight > now { boundaries.append(midnight) }
                else { unavailable = true; reasons.insert("state_unavailable") }
            }
            guard rule.isActive(at: now) else { continue }
            let end = rule.isLimit ? midnight : rule.currentWindow?.end
            if let end { boundaries.append(end) }
            let reached = rule.kind != .timeLimit || rule.thresholdReached == true
            causes.append(.init(id: rule.id, kind: rule.kind, displayName: rule.displayName,
                tier: rule.tier, end: end,
                enforcing: canEnforce && rule.available && rule.armed && rule.observed && reached && rule.enforcing))
        }
        if unavailable {
            session = nil
            causes = causes.map { .init(id: $0.id, kind: $0.kind, displayName: $0.displayName,
                                        tier: $0.tier, end: $0.end, enforcing: false) }
        }
        causes.sort(by: stronger)
        let headline = causes.first(where: \.enforcing)
        let state: Snapshot.State
        if unavailable { state = .unavailable }
        else if recovery != nil { state = .suspended }
        else if !reasons.isEmpty { state = .degraded }
        else if headline != nil { state = .protected }
        else { state = .notProtected }
        occurrences.sort { $0.start == $1.start ? $0.causeID < $1.causeID : $0.start < $1.start }
        return Snapshot(observedAt: now, validUntil: boundaries.filter { $0 > now }.min(),
            state: state, session: session, headline: headline, causes: causes,
            nextOccurrence: occurrences.first, pass: inputs.pass, degradedReasons: reasons.sorted())
    }

    /// nil end is an ongoing Easy commitment and sorts after every finite end.
    static func stronger(_ lhs: Snapshot.Cause, _ rhs: Snapshot.Cause) -> Bool {
        func rank(_ tier: BreakLevel) -> Int {
            switch tier { case .easy: return 0; case .harder: return 1; case .hardcore: return 2 }
        }
        if lhs.tier != rhs.tier { return rank(lhs.tier) > rank(rhs.tier) }
        if lhs.end != rhs.end { return (lhs.end ?? .distantFuture) > (rhs.end ?? .distantFuture) }
        return lhs.id < rhs.id
    }
}
