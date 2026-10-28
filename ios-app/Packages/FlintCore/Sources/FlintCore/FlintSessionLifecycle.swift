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

/// One focus-session lifecycle transition, as handed to every registered sink.
public enum FlintSessionLifecycleEvent: Equatable, Sendable {
    case started(FlintActiveSession, source: FlintSessionSource, at: Date)
    case ended(FlintActiveSession, route: FlintSessionEndRoute, at: Date)
    case superseded(FlintActiveSession, by: FlintActiveSession, at: Date)
    case passUsed(FlintActiveSession, at: Date)
    case failedToArm(FlintActiveSession, reason: String, at: Date)
}

/// Receives lifecycle events (focus history store, completion notifier). Sinks must be cheap,
/// non-blocking and must never throw or crash: they run inside enforcement paths, including
/// the DeviceActivity monitor extension.
public protocol FlintSessionEventSink: AnyObject {
    func handle(_ event: FlintSessionLifecycleEvent)
}

/// The single emission surface session-lifecycle owners call — one call per transition.
/// It only reports what already happened; it never changes enforcement. Storage and UI
/// behind it belong to focus history (`docs/architecture/focus-history.md`).
public enum FlintSessionLifecycle {

    /// A session became the active session. A still-running `previous` is reported as
    /// superseded by `session`; an already-expired one as completed.
    public static func didStart(
        _ session: FlintActiveSession,
        replacing previous: FlintActiveSession?,
        source: FlintSessionSource,
        at date: Date = Date()
    ) {
        if let previous, previous.id != session.id {
            if let endsAt = previous.endsAt, endsAt <= date {
                emit(.ended(previous, route: .completed, at: endsAt))
            } else {
                emit(.superseded(previous, by: session, at: date))
            }
        }
        emit(.started(session, source: source, at: date))
    }

    /// The session ended. Pass `.completed` only when it reached its planned end; a
    /// completion is recorded at the planned end rather than at `date`.
    public static func didEnd(
        _ session: FlintActiveSession,
        route: FlintSessionEndRoute,
        at date: Date = Date()
    ) {
        let when = route == .completed ? (session.endsAt ?? date) : date
        emit(.ended(session, route: route, at: when))
    }

    /// An Emergency Pass was spent while `session` was active. The caller also reports the
    /// resulting end with `didEnd(_:route: .emergencyPass)`.
    public static func didUsePass(_ session: FlintActiveSession, at date: Date = Date()) {
        emit(.passUsed(session, at: date))
    }

    /// Arming the session's out-of-app teardown failed. Diagnostic only; not an end.
    public static func didFailToArm(_ session: FlintActiveSession, reason: String, at date: Date = Date()) {
        emit(.failedToArm(session, reason: reason, at: date))
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

    private static func emit(_ event: FlintSessionLifecycleEvent) {
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
