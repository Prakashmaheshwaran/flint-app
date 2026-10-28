import Foundation

/// A live block currently in effect. Persisted to the App Group so the host app, the monitor
/// extension, and the shield extensions all agree on what's running and how hard it is to stop.
///
/// Everything after `monitorName` is optional and decoded with `decodeIfPresent` (synthesized
/// for optionals), so a session saved by an older build still loads; those sessions simply lack
/// the time-integrity anchors and are reconciled by wall clock alone.
public struct FlintActiveSession: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let startedAt: Date
    /// nil = open-ended until manually stopped.
    public let endsAt: Date?
    public let breakLevel: BreakLevel
    /// The `DeviceActivityName` currently registered for this session's auto-clear. Unique per
    /// registration (`flint.session.<token>`), so a callback from a replaced or re-registered
    /// activity can never be mistaken for this session's end. Older builds used the shared
    /// `flint.session` name; the monitor still recognizes it.
    public let monitorName: String
    /// Monotonic clock reading (seconds, includes device sleep, resets on reboot) at start —
    /// the anchor that reveals a wall-clock change while the session runs.
    public let monotonicStart: TimeInterval?
    /// Identifies the boot `monotonicStart` belongs to; nil when the OS doesn't expose one.
    public let bootSessionID: String?
    /// The time zone and UTC offset at `endsAt` that the auto-clear's wall-clock components
    /// were computed in. A change means the registered end no longer lands on `endsAt`.
    public let autoClearTimeZoneID: String?
    public let autoClearUTCOffset: Int?
    /// True when the session is shorter than DeviceActivity's 15-minute interval floor and its
    /// end is delivered by `intervalWillEndWarning` inside a padded 15-minute interval.
    public let usesEndWarning: Bool?

    public init(
        id: String = UUID().uuidString,
        name: String,
        startedAt: Date,
        endsAt: Date?,
        breakLevel: BreakLevel,
        monitorName: String,
        monotonicStart: TimeInterval? = nil,
        bootSessionID: String? = nil,
        autoClearTimeZoneID: String? = nil,
        autoClearUTCOffset: Int? = nil,
        usesEndWarning: Bool? = nil
    ) {
        self.id = id
        self.name = name
        self.startedAt = startedAt
        self.endsAt = endsAt
        self.breakLevel = breakLevel
        self.monitorName = monitorName
        self.monotonicStart = monotonicStart
        self.bootSessionID = bootSessionID
        self.autoClearTimeZoneID = autoClearTimeZoneID
        self.autoClearUTCOffset = autoClearUTCOffset
        self.usesEndWarning = usesEndWarning
    }

    public var isExpired: Bool { isExpired(at: Date()) }

    public func isExpired(at now: Date) -> Bool {
        guard let endsAt else { return false }
        return now >= endsAt
    }

    /// Seconds left, or 0 if open-ended/expired.
    public var remaining: TimeInterval { remaining(at: Date()) }

    public func remaining(at now: Date) -> TimeInterval {
        guard let endsAt else { return 0 }
        return max(0, endsAt.timeIntervalSince(now))
    }

    /// The same session with its auto-clear re-registered under `monitorName` for `plan`.
    func rearmed(monitorName: String, plan: FlintSessionAutoClearPlan) -> FlintActiveSession {
        FlintActiveSession(
            id: id, name: name, startedAt: startedAt, endsAt: endsAt, breakLevel: breakLevel,
            monitorName: monitorName, monotonicStart: monotonicStart, bootSessionID: bootSessionID,
            autoClearTimeZoneID: plan.timeZoneIdentifier, autoClearUTCOffset: plan.utcOffsetAtEnd,
            usesEndWarning: plan.usesEndWarning
        )
    }
}
