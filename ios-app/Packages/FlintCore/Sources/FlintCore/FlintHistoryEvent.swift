import Foundation
import CryptoKit

/// The only mapping between lifecycle event kinds and the on-disk vocabulary.
public enum FlintHistoryEventType: Equatable, Sendable, Codable {
    case started, completed, endedEarly, superseded, breakStarted, breakEnded
    case passUsed, failedToArm, rescheduled, openIntercepted
    case protectionDegraded, protectionRestored, accessStarted, accessEnded
    case unknown(String)

    static let knownTypes: [Self] = [.started, .completed, .endedEarly, .superseded, .breakStarted,
                                    .breakEnded, .passUsed, .failedToArm, .rescheduled, .openIntercepted,
                                    .protectionDegraded, .protectionRestored, .accessStarted, .accessEnded]

    public var wireName: String {
        switch self {
        case .started: return "started"
        case .completed: return "completed"
        case .endedEarly: return "ended_early"
        case .superseded: return "superseded"
        case .breakStarted: return "break_started"
        case .breakEnded: return "break_ended"
        case .passUsed: return "pass_used"
        case .failedToArm: return "failed_to_arm"
        case .rescheduled: return "rescheduled"
        case .protectionDegraded: return "protection_degraded"
        case .protectionRestored: return "protection_restored"
        case .accessStarted: return "access_started"
        case .accessEnded: return "access_ended"
        case .openIntercepted: return "open_intercepted"
        case .unknown(let name): return name
        }
    }

    public init(from decoder: Decoder) throws {
        let name = try decoder.singleValueContainer().decode(String.self)
        self = Self.knownTypes
            .first(where: { $0.wireName == name }) ?? .unknown(name)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(wireName)
    }

    public var isTerminal: Bool { self == .completed || self == .endedEarly || self == .superseded }
}

public struct FlintHistoryEvent: Codable, Equatable, Sendable, Identifiable {
    public var v: Int
    public var id: String
    public var type: FlintHistoryEventType
    public var session: String
    public var at: Int64
    public var tz: String
    public var inferred: Bool?
    public var plannedEnd: Int64?
    public var tier: String?
    public var source: String?
    public var name: String?
    public var route: String?
    public var by: String?
    public var breakEnd: Int64?
    public var reason: String?
    public var originRoute: String?
    public var observedAt: Int64?
    public var kind: String?
    public var grant: String?
    public var sessions: [String]?
    public var recoveryEnd: Int64?
    public var timeConfidence: FlintEventTimeConfidence?

    public init(type: FlintHistoryEventType, session: String, at: Date,
                id: String = UUID().uuidString, timeZone: TimeZone = .current,
                inferred: Bool? = nil, plannedEnd: Date? = nil, tier: String? = nil,
                source: String? = nil, name: String? = nil, route: String? = nil,
                by: String? = nil, breakEnd: Date? = nil, reason: String? = nil, v: Int = 1,
                originRoute: String? = nil, observedAt: Date? = nil, kind: String? = nil,
                grant: String? = nil, sessions: [String]? = nil, recoveryEnd: Date? = nil,
                timeConfidence: FlintEventTimeConfidence? = nil) {
        self.v = v; self.id = id; self.type = type; self.session = session
        self.at = Self.milliseconds(at); tz = timeZone.identifier
        self.inferred = inferred; self.plannedEnd = plannedEnd.map(Self.milliseconds)
        self.tier = tier; self.source = source; self.name = name.map { String($0.prefix(64)) }
        self.route = route; self.by = by; self.breakEnd = breakEnd.map(Self.milliseconds)
        self.reason = reason
        self.originRoute = originRoute; self.observedAt = observedAt.map(Self.milliseconds)
        self.kind = kind; self.grant = grant; self.sessions = sessions
        self.recoveryEnd = recoveryEnd.map(Self.milliseconds)
        self.timeConfidence = timeConfidence
    }

    public var date: Date { Self.date(at) }
    public static func date(_ milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: Double(milliseconds) / 1000)
    }
    public static func milliseconds(_ date: Date) -> Int64 {
        let value = date.timeIntervalSince1970 * 1000
        guard value.isFinite, value > Double(Int64.min), value < Double(Int64.max) else { return 0 }
        return Int64(value.rounded(.towardZero))
    }

    /// Dates outside Foundation's supported civil range cannot be bucketed safely.
    public var isSupported: Bool {
        guard v == 1, !session.isEmpty,
              [at, plannedEnd, breakEnd, observedAt, recoveryEnd].compactMap({ $0 }).allSatisfy({
                  $0 >= -62_135_596_800_000 && $0 <= 253_402_300_799_000
              }) else { return false }
        if case .unknown = type { return false }
        return type != .started || (name != nil && tier != nil && source != nil)
    }

    public init(_ event: FlintSessionLifecycleEvent, timeZone: TimeZone = .current) {
        switch event {
        case let .started(s, source, _):
            self.init(type: .started, session: s.id, at: s.startedAt,
                      id: Self.lifecycleID(.started, session: s.id), timeZone: timeZone,
                      plannedEnd: s.endsAt, tier: s.breakLevel.rawValue,
                      source: source.rawValue, name: s.name)
        case let .ended(s, route, at):
            let type: FlintHistoryEventType = route == .completed ? .completed : .endedEarly
            self.init(type: type, session: s.id, at: at,
                      id: Self.lifecycleID(type, session: s.id, discriminator: route.rawValue), timeZone: timeZone,
                      route: route == .completed ? nil : route.rawValue)
        case let .superseded(s, next, at):
            self.init(type: .superseded, session: s.id, at: at,
                      id: Self.lifecycleID(.superseded, session: s.id, discriminator: next.id),
                      timeZone: timeZone, by: next.id)
        case let .passUsed(s, at):
            self.init(type: .passUsed, session: "pass-\(UUID().uuidString)", at: at, timeZone: timeZone, sessions: [s.id])
        case let .globalPassUsed(operationID, sessions, recoveryEnd, at):
            self.init(type: .passUsed, session: operationID, at: at, id: operationID,
                      timeZone: timeZone, sessions: sessions.map(\.id), recoveryEnd: recoveryEnd)
        case let .failedToArm(s, reason, at):
            // Each arm attempt can fail independently; the session is not an operation ID.
            self.init(type: .failedToArm, session: s.id, at: at, timeZone: timeZone, reason: reason)
        case let .annotated(event, origin, reason, observedAt, timeConfidence):
            self.init(event, timeZone: timeZone)
            self.originRoute = origin; self.reason = reason ?? self.reason
            self.observedAt = observedAt.map(Self.milliseconds)
            self.timeConfidence = timeConfidence ?? self.timeConfidence
        }
    }

    /// Only one-shot lifecycle transitions use this identity. Dates and annotations are
    /// evidence, not identity; repeatable phases retain the generic initializer's IDs.
    private static func lifecycleID(_ type: FlintHistoryEventType, session: String,
                                    discriminator: String = "") -> String {
        var identity = Data()
        // UTF-8 byte lengths make arbitrary session/replacement IDs unambiguous.
        for field in ["flint.history.lifecycle.v1", type.wireName, session, discriminator] {
            identity.append(contentsOf: "\(field.utf8.count):".utf8)
            identity.append(contentsOf: field.utf8)
        }
        return "lifecycle-v1-" + SHA256.hash(data: identity).map { String(format: "%02x", $0) }.joined()
    }
}
