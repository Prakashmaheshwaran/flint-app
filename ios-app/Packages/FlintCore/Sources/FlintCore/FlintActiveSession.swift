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
    /// How many times the auto-clear was re-registered because iOS ended it before `endsAt`
    /// (bounded, so a device that keeps misfiring can't drain the shared registration pool).
    public let rearmCount: Int?
    /// When the auto-clear was last re-registered for any reason (spaces out repairs).
    public let lastRearmAt: Date?
    /// The protection-contract version the session was started under. nil = started by a build
    /// before ADR-012, so it keeps that build's promised rights until it ends (ADR-012 §12).
    public let contractVersion: Int?
    /// `FlintSessionOrigin` raw value of the request that started it (ADR-012 §11).
    public let origin: String?
    /// The automation that owns it (the Focus filter), for owner-ended stops.
    public let ownerID: String?

    public static let currentContractVersion = 1

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
        usesEndWarning: Bool? = nil,
        rearmCount: Int? = nil,
        lastRearmAt: Date? = nil,
        contractVersion: Int? = nil,
        origin: String? = nil,
        ownerID: String? = nil
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
        self.rearmCount = rearmCount
        self.lastRearmAt = lastRearmAt
        self.contractVersion = contractVersion
        self.origin = origin
        self.ownerID = ownerID
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
    func rearmed(
        monitorName: String,
        plan: FlintSessionAutoClearPlan,
        at date: Date,
        countsTowardLimit: Bool
    ) -> FlintActiveSession {
        FlintActiveSession(
            id: id, name: name, startedAt: startedAt, endsAt: endsAt, breakLevel: breakLevel,
            monitorName: monitorName, monotonicStart: monotonicStart, bootSessionID: bootSessionID,
            autoClearTimeZoneID: plan.timeZoneIdentifier, autoClearUTCOffset: plan.utcOffsetAtEnd,
            usesEndWarning: plan.usesEndWarning,
            rearmCount: (rearmCount ?? 0) + (countsTowardLimit ? 1 : 0),
            lastRearmAt: date,
            contractVersion: contractVersion,
            origin: origin,
            ownerID: ownerID
        )
    }
}
