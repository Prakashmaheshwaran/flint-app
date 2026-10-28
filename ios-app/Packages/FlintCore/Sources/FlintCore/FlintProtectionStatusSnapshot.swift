import Foundation

/// Privacy-safe, advisory projection. It contains no selections, tokens or target keys and
/// must never authorize an enforcement mutation. Dates on the wire are seconds since 1970.
public struct FlintProtectionStatusSnapshot: Codable, Equatable, Sendable {
    public enum State: String, Codable, Sendable {
        case protected, notProtected = "not_protected", degraded, suspended, unavailable
    }

    public enum Kind: String, Codable, Sendable {
        case session, pomodoro, recurringSchedule, oneShotSchedule, sleep, timeLimit, openLimit
    }

    public struct Session: Codable, Equatable, Sendable {
        public enum Kind: String, Codable, Sendable { case session, pomodoro }
        public enum Origin: String, Codable, Sendable {
            case appUI = "app_ui", shortcut, focusFilter = "focus_filter", automation
        }

        public let id: String
        public let kind: Kind
        public let name: String
        public let tier: BreakLevel
        public let startedAt: Date
        public let logicalEnd: Date?
        public let origin: Origin
        public let armed: Bool

        public init(id: String, kind: Kind, name: String, tier: BreakLevel, startedAt: Date,
                    logicalEnd: Date?, origin: Origin, armed: Bool) {
            self.id = id
            self.kind = kind
            self.name = name
            self.tier = tier
            self.startedAt = startedAt
            self.logicalEnd = logicalEnd
            self.origin = origin
            self.armed = armed
        }
    }

    public struct Cause: Codable, Equatable, Sendable {
        public let id: String
        public let kind: Kind
        public let displayName: String
        public let tier: BreakLevel
        public let end: Date?
        public let enforcing: Bool

        public init(id: String, kind: Kind, displayName: String, tier: BreakLevel,
                    end: Date?, enforcing: Bool) {
            self.id = id
            self.kind = kind
            self.displayName = displayName
            self.tier = tier
            self.end = end
            self.enforcing = enforcing
        }
    }

    public struct Occurrence: Codable, Equatable, Sendable {
        public let causeID: String
        public let kind: Kind
        public let displayName: String
        public let start: Date
        public let end: Date

        public init(causeID: String, kind: Kind, displayName: String, start: Date, end: Date) {
            self.causeID = causeID
            self.kind = kind
            self.displayName = displayName
            self.start = start
            self.end = end
        }
    }

    public struct Pass: Codable, Equatable, Sendable {
        public enum Eligibility: String, Codable, Sendable {
            case available, coolingDown = "cooling_down"
            case nothingToRecover = "nothing_to_recover", unavailable
        }

        public let eligibility: Eligibility
        public let nextAvailableAt: Date?
        public let recoveryUntil: Date?

        public init(eligibility: Eligibility, nextAvailableAt: Date? = nil, recoveryUntil: Date? = nil) {
            self.eligibility = eligibility
            self.nextAvailableAt = nextAvailableAt
            self.recoveryUntil = recoveryUntil
        }
    }

    public let version: Int
    public let observedAt: Date
    public let validUntil: Date?
    public let state: State
    public let session: Session?
    public let headline: Cause?
    public let causes: [Cause]
    public let nextOccurrence: Occurrence?
    public let pass: Pass
    public let degradedReasons: [String]
    /// Optional traceability only: host, monitor or shield_action; never target information.
    public let writer: String?

    public init(version: Int = 1, observedAt: Date, validUntil: Date?, state: State,
                session: Session?, headline: Cause?, causes: [Cause], nextOccurrence: Occurrence?,
                pass: Pass, degradedReasons: [String], writer: String? = nil) {
        self.version = version
        self.observedAt = observedAt
        self.validUntil = validUntil
        self.state = state
        self.session = session
        self.headline = headline
        self.causes = causes
        self.nextOccurrence = nextOccurrence
        self.pass = pass
        self.degradedReasons = degradedReasons
        self.writer = writer
    }
}
