import Foundation
import UserNotifications

public protocol FlintNotificationCenter: AnyObject {
    func authorizationStatus(_ completion: @escaping (UNAuthorizationStatus) -> Void)
    func requestAuthorization(_ completion: @escaping (Bool) -> Void)
    func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void)
    func cancel(_ identifiers: [String])
    func pendingIdentifiers(_ completion: @escaping ([String]) -> Void)
}

public final class FlintSystemNotificationCenter: FlintNotificationCenter {
    private let center: UNUserNotificationCenter
    public init(center: UNUserNotificationCenter = .current()) { self.center = center }
    public func authorizationStatus(_ completion: @escaping (UNAuthorizationStatus) -> Void) {
        center.getNotificationSettings { completion($0.authorizationStatus) }
    }
    public func requestAuthorization(_ completion: @escaping (Bool) -> Void) {
        center.requestAuthorization(options: [.alert, .sound]) { allowed, _ in completion(allowed) }
    }
    public func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void) {
        center.add(request, withCompletionHandler: completion)
    }
    public func cancel(_ identifiers: [String]) { center.removePendingNotificationRequests(withIdentifiers: identifiers) }
    public func pendingIdentifiers(_ completion: @escaping ([String]) -> Void) {
        center.getPendingNotificationRequests { completion($0.map(\.identifier)) }
    }
}

/// Host-only notification sink. App Intents executing in the host get the same built-in
/// sink as the UI. An .appex never requests authorization or schedules a notification.
public final class FlintCompletionNotifier: FlintSessionEventSink, @unchecked Sendable {
    public static let shared = FlintCompletionNotifier()
    public static let preferenceKey = "flint.notifications.sessionCompletionEnabled"
    public static let identifierPrefix = "flint.session.complete."
    private let center: FlintNotificationCenter
    private let defaults: UserDefaults
    private let isHost: Bool
    private let clock: () -> Date
    private let history: FlintHistoryStore
    private let queue = DispatchQueue(label: "app.flint.completionNotifications")
    private var generations: [String: UUID] = [:]
    private var terminalSessions: Set<String> = []

    public init(center: FlintNotificationCenter = FlintSystemNotificationCenter(), defaults: UserDefaults = .standard,
                isHost: Bool = Bundle.main.bundleURL.pathExtension == "app", clock: @escaping () -> Date = Date.init,
                history: FlintHistoryStore = .shared) {
        self.center = center; self.defaults = defaults; self.isHost = isHost; self.clock = clock
        self.history = history
        if isHost { queue.async { [self] in reconcilePending() } }
    }

    public var isEnabled: Bool {
        defaults.object(forKey: Self.preferenceKey) == nil || defaults.bool(forKey: Self.preferenceKey)
    }

    public func setEnabled(_ enabled: Bool) {
        guard isHost else { return }
        defaults.set(enabled, forKey: Self.preferenceKey)
        if !enabled {
            queue.async { [self] in
                generations.removeAll()
                center.pendingIdentifiers { [weak self] ids in
                    guard let self else { return }
                    self.queue.async {
                        let removable = ids.filter { identifier in
                            guard identifier.hasPrefix(Self.identifierPrefix) else { return false }
                            let sessionID = String(identifier.dropFirst(Self.identifierPrefix.count))
                            return self.generations[sessionID] == nil
                        }
                        if !removable.isEmpty { self.center.cancel(removable) }
                    }
                }
            }
        }
    }

    public func authorizationStatus(_ completion: @escaping (UNAuthorizationStatus) -> Void) {
        guard isHost else { completion(.denied); return }
        center.authorizationStatus(completion)
    }

    /// Called only from an explicit user action. Denial is never automatically re-prompted.
    public func requestPermission(_ completion: @escaping (Bool) -> Void) {
        guard isHost else { completion(false); return }
        center.authorizationStatus { [center] status in
            if status == .notDetermined { center.requestAuthorization(completion) }
            else { completion(Self.isAuthorized(status)) }
        }
    }

    public static func isAuthorized(_ status: UNAuthorizationStatus) -> Bool {
        status == .authorized || status == .provisional || status == .ephemeral
    }

    public func handle(_ event: FlintSessionLifecycleEvent) {
        guard isHost else { return }
        queue.async { [self] in
            switch event.unannotated {
            case let .started(session, _, _): schedule(session)
            case let .ended(session, route, _):
                terminalSessions.insert(session.id)
                if route.isEarly { cancel(session.id) }
            case let .superseded(session, _, _):
                terminalSessions.insert(session.id); cancel(session.id)
            default: break
            }
        }
    }

    private func cancel(_ sessionID: String) {
        generations.removeValue(forKey: sessionID)
        center.cancel([Self.identifierPrefix + sessionID])
    }

    private func schedule(_ session: FlintActiveSession) {
        terminalSessions.formUnion(persistedTerminalIDs())
        guard !terminalSessions.contains(session.id) else { return }
        guard isEnabled, generations[session.id] == nil, let end = session.endsAt, end > clock() else { return }
        let token = UUID()
        generations[session.id] = token
        center.authorizationStatus { [weak self] status in
            guard let self else { return }
            self.queue.async { [self] in
                guard Self.isAuthorized(status), self.isEnabled,
                      !self.terminalSessions.contains(session.id),
                      self.generations[session.id] == token, end > self.clock() else { return }
                let content = UNMutableNotificationContent()
                content.title = "Planned focus time ended"
                content.body = "Your focus session has reached its planned end."
                content.sound = .default
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = TimeZone(secondsFromGMT: 0)!
                let triggerDate = Date(timeIntervalSince1970: ceil(end.timeIntervalSince1970))
                var components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: triggerDate)
                components.calendar = calendar; components.timeZone = calendar.timeZone
                let request = UNNotificationRequest(identifier: Self.identifierPrefix + session.id, content: content,
                    trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
                self.center.add(request) { [weak self] error in
                    guard let self else { return }
                    self.queue.async {
                        // An early end can win while the OS is accepting the request.
                        if self.generations[session.id] != token || !self.isEnabled {
                            self.center.cancel([request.identifier])
                        } else if error != nil {
                            self.generations.removeValue(forKey: session.id)
                        }
                    }
                }
            }
        }
    }

    private func persistedTerminalIDs() -> Set<String> {
        Set(history.read().events.filter { $0.isSupported && $0.type.isTerminal }.map(\.session))
    }

    private func reconcilePending() {
        terminalSessions.formUnion(persistedTerminalIDs())
        center.pendingIdentifiers { [weak self] identifiers in
            guard let self else { return }
            self.queue.async {
                self.terminalSessions.formUnion(self.persistedTerminalIDs())
                let stale = identifiers.filter {
                    $0.hasPrefix(Self.identifierPrefix) && self.terminalSessions.contains(String($0.dropFirst(Self.identifierPrefix.count)))
                }
                if !stale.isEmpty { self.center.cancel(stale) }
            }
        }
    }

    /// Test seam: drains queued decisions and their synchronous-double callback follow-ups.
    /// OS callbacks deliberately held by a test must be released before calling this.
    func drainForTesting(_ completion: @escaping () -> Void) {
        queue.async { [self] in queue.async { [self] in queue.async { completion() } } }
    }
}
