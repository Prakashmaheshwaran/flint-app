import Foundation

/// How a focus session was started. Raw values are the focus-history wire names
/// (`docs/architecture/focus-history.md`); keep them stable.
public enum FlintSessionSource: String, Codable, Equatable, Sendable, CaseIterable {
    case manual
    case preset
    case shortcut
    case pomodoro
    case focusFilter = "focus_filter"
    case legacy
    case unknown
}

/// Why a focus session ended. `.completed` means it reached its planned end; every other
/// case is an early end. Raw values are the focus-history wire names; keep them stable.
public enum FlintSessionEndRoute: String, Codable, Equatable, Sendable, CaseIterable {
    case completed
    case manualStop = "manual_stop"
    case harderWait = "harder_wait"
    case emergencyPass = "emergency_pass"
    case shortcutStop = "shortcut_stop"
    case focusFilterOff = "focus_filter_off"
    case unknown

    public var isEarly: Bool { self != .completed }
}

/// Confidence in an event's effective logical time, independent of delayed observation.
/// Missing confidence keeps legacy plausibility checks; it does not imply certainty.
public enum FlintEventTimeConfidence: Equatable, Sendable, Codable {
    case certain
    case uncertain(reason: String)

    private enum CodingKeys: String, CodingKey { case kind, reason }

    public init(from decoder: Decoder) throws {
        guard let values = try? decoder.container(keyedBy: CodingKeys.self),
              let kind = try? values.decode(String.self, forKey: .kind) else {
            self = .uncertain(reason: "unsupported_time_confidence"); return
        }
        switch kind {
        case "certain": self = .certain
        case "uncertain":
            self = .uncertain(reason: (try? values.decode(String.self, forKey: .reason)) ?? "unsupported_time_confidence")
        default: self = .uncertain(reason: "unsupported_time_confidence")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .certain: try values.encode("certain", forKey: .kind)
        case let .uncertain(reason):
            try values.encode("uncertain", forKey: .kind)
            try values.encode(reason, forKey: .reason)
        }
    }

    var isUncertain: Bool {
        if case .uncertain = self { return true }
        return false
    }
}

/// One focus-session lifecycle transition, as handed to every registered sink.
public enum FlintSessionLifecycleEvent: Equatable, Sendable {
    case started(FlintActiveSession, source: FlintSessionSource, at: Date)
    case ended(FlintActiveSession, route: FlintSessionEndRoute, at: Date)
    case superseded(FlintActiveSession, by: FlintActiveSession, at: Date)
    case passUsed(FlintActiveSession, at: Date)
    case globalPassUsed(operationID: String, sessions: [FlintActiveSession], recoveryEnd: Date?, at: Date)
    case failedToArm(FlintActiveSession, reason: String, at: Date)
    indirect case annotated(FlintSessionLifecycleEvent, origin: String?, reason: String?, observedAt: Date?,
                           timeConfidence: FlintEventTimeConfidence? = nil)

    public var unannotated: FlintSessionLifecycleEvent {
        if case let .annotated(event, _, _, _, _) = self { return event.unannotated }
        return self
    }
}

/// Receives lifecycle events inside enforcement paths, including the monitor extension.
/// Sinks must never throw or wait for host maintenance. History performs only its small,
/// bounded durable ingress write here; parsing and maintenance run separately in the host.
public protocol FlintSessionEventSink: AnyObject {
    func handle(_ event: FlintSessionLifecycleEvent)
}

/// The single emission surface session-lifecycle owners call — one call per transition.
/// It only reports what already happened; it never changes enforcement. Storage and UI
/// behind it belong to focus history (`docs/architecture/focus-history.md`).
public enum FlintSessionLifecycle {

    // Preserve the original exact function names/types, including function references.
    public static func didStart(_ session: FlintActiveSession, replacing previous: FlintActiveSession?,
                                source: FlintSessionSource, at date: Date = Date()) {
        didStart(session, replacing: previous, source: source, at: date, origin: nil)
    }
    public static func didEnd(_ session: FlintActiveSession, route: FlintSessionEndRoute, at date: Date = Date()) {
        didEnd(session, route: route, at: date, origin: nil)
    }
    /// The lifecycle owner supplies the wall equivalent of its accepted logical deadline
    /// after a clock change. Keep the original exact overload available to older callers.
    public static func didEnd(_ session: FlintActiveSession, route: FlintSessionEndRoute,
                              at date: Date = Date(), logicalEnd: Date?) {
        didEnd(session, route: route, at: date, origin: nil, logicalEnd: logicalEnd)
    }
    public static func didUsePass(_ session: FlintActiveSession, at date: Date = Date()) {
        didUsePass(session, at: date, origin: nil)
    }
    public static func didFailToArm(_ session: FlintActiveSession, reason: String, at date: Date = Date()) {
        didFailToArm(session, reason: reason, at: date, origin: nil)
    }
    public static func didUsePass(operationID: String, sessions: [FlintActiveSession], recoveryEnd: Date?,
                                  at date: Date = Date()) {
        didUsePass(operationID: operationID, sessions: sessions, recoveryEnd: recoveryEnd, at: date, origin: nil)
    }

    public static func didStart(_ session: FlintActiveSession, replacing previous: FlintActiveSession?,
                                source: FlintSessionSource, at date: Date = Date(),
                                timeConfidence: FlintEventTimeConfidence?) {
        didStart(session, replacing: previous, source: source, at: date, origin: nil, timeConfidence: timeConfidence)
    }
    public static func didEnd(_ session: FlintActiveSession, route: FlintSessionEndRoute, at date: Date = Date(),
                              timeConfidence: FlintEventTimeConfidence?) {
        didEnd(session, route: route, at: date, origin: nil, timeConfidence: timeConfidence)
    }
    public static func didEnd(_ session: FlintActiveSession, route: FlintSessionEndRoute,
                              at date: Date = Date(), logicalEnd: Date?, timeConfidence: FlintEventTimeConfidence?) {
        didEnd(session, route: route, at: date, origin: nil, logicalEnd: logicalEnd, timeConfidence: timeConfidence)
    }
    public static func didUsePass(_ session: FlintActiveSession, at date: Date = Date(),
                                  timeConfidence: FlintEventTimeConfidence?) {
        didUsePass(session, at: date, origin: nil, timeConfidence: timeConfidence)
    }
    public static func didUsePass(operationID: String, sessions: [FlintActiveSession], recoveryEnd: Date?,
                                  at date: Date = Date(), timeConfidence: FlintEventTimeConfidence?) {
        didUsePass(operationID: operationID, sessions: sessions, recoveryEnd: recoveryEnd, at: date,
                   origin: nil, timeConfidence: timeConfidence)
    }
    public static func didFailToArm(_ session: FlintActiveSession, reason: String, at date: Date = Date(),
                                    timeConfidence: FlintEventTimeConfidence?) {
        didFailToArm(session, reason: reason, at: date, origin: nil, timeConfidence: timeConfidence)
    }

    /// A session became the active session. A still-running `previous` is reported as
    /// superseded by `session`; an already-expired one as completed.
    public static func didStart(
        _ session: FlintActiveSession,
        replacing previous: FlintActiveSession?,
        source: FlintSessionSource,
        at date: Date = Date(), origin: String?, reason: String? = nil, observedAt: Date? = nil,
        timeConfidence: FlintEventTimeConfidence? = nil
    ) {
        if let previous, previous.id != session.id {
            if let endsAt = previous.endsAt, endsAt <= date {
                emit(.ended(previous, route: .completed, at: endsAt), origin: origin, reason: reason,
                     observedAt: observedAt, timeConfidence: timeConfidence)
            } else {
                emit(.superseded(previous, by: session, at: date), origin: origin, reason: reason,
                     observedAt: observedAt, timeConfidence: timeConfidence)
            }
        }
        emit(.started(session, source: source, at: date), origin: origin, reason: reason,
             observedAt: observedAt, timeConfidence: timeConfidence)
    }

    /// Pass `.completed` only after the owner reaches the logical deadline. An explicit
    /// logical end survives wall-clock changes; legacy callers retain the planned end.
    public static func didEnd(
        _ session: FlintActiveSession,
        route: FlintSessionEndRoute,
        at date: Date = Date(), origin: String?, reason: String? = nil, observedAt: Date? = nil,
        logicalEnd: Date? = nil, timeConfidence: FlintEventTimeConfidence? = nil
    ) {
        let deadline = logicalEnd ?? session.endsAt
        guard route != .completed || deadline.map({ $0 <= date }) == true else { return }
        let when = route == .completed ? (deadline ?? date) : date
        let observation = observedAt ?? (route == .completed && logicalEnd != nil && date != when ? date : nil)
        emit(.ended(session, route: route, at: when), origin: origin, reason: reason,
             observedAt: observation, timeConfidence: timeConfidence)
    }

    /// Compatibility overload for a pass observed for one session. Prefer the operation-ID
    /// overload to record the complete affected set and its known recovery window.
    public static func didUsePass(_ session: FlintActiveSession, at date: Date = Date(),
                                  origin: String?, reason: String? = nil, observedAt: Date? = nil,
                                  timeConfidence: FlintEventTimeConfidence? = nil) {
        emit(.passUsed(session, at: date), origin: origin, reason: reason,
             observedAt: observedAt, timeConfidence: timeConfidence)
    }

    public static func didUsePass(operationID: String, sessions: [FlintActiveSession], recoveryEnd: Date?,
                                  at date: Date = Date(), origin: String?, reason: String? = nil,
                                  observedAt: Date? = nil, timeConfidence: FlintEventTimeConfidence? = nil) {
        emit(.globalPassUsed(operationID: operationID, sessions: sessions, recoveryEnd: recoveryEnd, at: date),
             origin: origin, reason: reason, observedAt: observedAt, timeConfidence: timeConfidence)
    }

    /// Arming the session's out-of-app teardown failed. Diagnostic only; not an end.
    public static func didFailToArm(_ session: FlintActiveSession, reason: String, at date: Date = Date(),
                                    origin: String?, observedAt: Date? = nil,
                                    timeConfidence: FlintEventTimeConfidence? = nil) {
        emit(.failedToArm(session, reason: reason, at: date), origin: origin,
             observedAt: observedAt, timeConfidence: timeConfidence)
    }

    // MARK: Sinks

    /// Register a sink for this process. Registering the same instance twice is a no-op.
    public static func register(_ sink: FlintSessionEventSink) {
        registry.add(sink)
    }

    public static func unregister(_ sink: FlintSessionEventSink) {
        registry.remove(sink)
    }

    private static let registry = SinkRegistry()

    private static func emit(_ raw: FlintSessionLifecycleEvent, origin: String? = nil,
                             reason: String? = nil, observedAt: Date? = nil,
                             timeConfidence: FlintEventTimeConfidence? = nil) {
        let event = origin != nil || reason != nil || observedAt != nil || timeConfidence != nil
            ? FlintSessionLifecycleEvent.annotated(raw, origin: origin, reason: reason,
                                                  observedAt: observedAt, timeConfidence: timeConfidence) : raw
        FlintHistorySink.shared.handle(event)
        if Bundle.main.bundleURL.pathExtension == "app" {
            FlintCompletionNotifier.shared.handle(event)
        }
        for sink in registry.snapshot() {
            sink.handle(event)
        }
    }
}

private final class SinkRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var sinks: [FlintSessionEventSink] = []

    func add(_ sink: FlintSessionEventSink) {
        lock.lock(); defer { lock.unlock() }
        guard !sinks.contains(where: { $0 === sink }) else { return }
        sinks.append(sink)
    }

    func remove(_ sink: FlintSessionEventSink) {
        lock.lock(); defer { lock.unlock() }
        sinks.removeAll { $0 === sink }
    }

    func snapshot() -> [FlintSessionEventSink] {
        lock.lock(); defer { lock.unlock() }
        return sinks
    }
}
