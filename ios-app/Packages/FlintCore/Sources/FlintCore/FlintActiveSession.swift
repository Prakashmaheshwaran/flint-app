import Foundation

/// A live block currently in effect. Persisted to the App Group so the host app, the monitor
/// extension, and the shield extensions all agree on what's running and how hard it is to stop.
public struct FlintActiveSession: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let startedAt: Date
    /// nil = open-ended until manually stopped.
    public let endsAt: Date?
    public let breakLevel: BreakLevel
    /// The `DeviceActivityName` used to register the auto-clear schedule (so we can stop it).
    public let monitorName: String

    public init(
        id: String = UUID().uuidString,
        name: String,
        startedAt: Date,
        endsAt: Date?,
        breakLevel: BreakLevel,
        monitorName: String
    ) {
        self.id = id
        self.name = name
        self.startedAt = startedAt
        self.endsAt = endsAt
        self.breakLevel = breakLevel
        self.monitorName = monitorName
    }

    public var isExpired: Bool {
        guard let endsAt else { return false }
        return Date() >= endsAt
    }

    /// Seconds left, or 0 if open-ended/expired.
    public var remaining: TimeInterval {
        guard let endsAt else { return 0 }
        return max(0, endsAt.timeIntervalSinceNow)
    }
}
