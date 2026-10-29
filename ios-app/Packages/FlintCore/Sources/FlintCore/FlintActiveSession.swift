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
    /// Re-registrations after forward wall-clock jumps (bounded on their own). nil = none yet.
    public let clockRearmCount: Int?
    /// When the auto-clear was last re-registered for any reason (spaces out repairs).
    public let lastRearmAt: Date?
    /// The protection-contract version the session was started under. nil = started by a build
    /// before ADR-012, so it keeps that build's promised rights until it ends (ADR-012 §12).
    public let contractVersion: Int?
    /// `FlintSessionOrigin` raw value of the request that started it (ADR-012 §11).
    public let origin: String?
    /// The automation that owns it (the Focus filter), for owner-ended stops.
    public let ownerID: String?
    /// The committed length in seconds (ADR-012 §9 "duration remaining"). nil for sessions saved
    /// before it was recorded: their planned length is `endsAt − startedAt`.
    public let plannedDuration: TimeInterval?
    /// The start request that created this session, so a retried request returns it instead of
    /// minting a new id and deadline (PC-03/PC-10).
    public let startRequestID: String?

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
        ownerID: String? = nil,
        plannedDuration: TimeInterval? = nil,
        startRequestID: String? = nil,
        clockRearmCount: Int? = nil
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
        self.plannedDuration = plannedDuration
        self.startRequestID = startRequestID
        self.clockRearmCount = clockRearmCount
    }

    /// Time left on the commitment, by the clock ADR-012 §9 trusts: within the boot that started
    /// the session, monotonic elapsed time governs — a wall-clock change can neither shorten nor
    /// extend it. Across a reboot (or without anchors) no elapsed time can be trusted, so the
    /// bounded fallback `min(planned, max(0, wallEnd − now))` applies. nil for an open-ended
    /// session.
    public func logicalRemaining(clock: FlintClock) -> TimeInterval? {
        guard let endsAt else { return nil }
        let planned = plannedDuration ?? endsAt.timeIntervalSince(startedAt)
        if let start = monotonicStart, let boot = bootSessionID, boot == clock.bootSessionID() {
            let elapsed = clock.monotonic() - start
            if elapsed >= 0 { return max(0, planned - elapsed) }
        }
        return min(planned, max(0, endsAt.timeIntervalSince(clock.now())))
    }

    /// Whether the commitment is over by `logicalRemaining` (allowing `slack` for the whole-second
    /// rounding of the registered interval). Open-ended sessions never expire.
    public func isLogicallyExpired(clock: FlintClock, slack: TimeInterval = 0) -> Bool {
        guard let remaining = logicalRemaining(clock: clock) else { return false }
        return remaining <= slack
    }

    /// The wall-clock end equivalent to the logical remaining time, for display and OS
    /// registration after a same-boot clock change.
    public func logicalEnd(clock: FlintClock) -> Date? {
        logicalRemaining(clock: clock).map { clock.now().addingTimeInterval($0) }
    }

    /// `logicalEnd` for display, publication and cause identity: the recorded wall end while the
    /// clock is steady (so it doesn't jitter between reads), the logical end in whole seconds
    /// after a real discontinuity. Later than `clock.now()` exactly while time remains.
    public func stableLogicalEnd(clock: FlintClock) -> Date? {
        guard let remaining = logicalRemaining(clock: clock) else { return nil }
        let now = clock.now()
        let end = now.addingTimeInterval(remaining)
        guard remaining > 0 else { return min(end, endsAt ?? end) }
        if let endsAt, endsAt > now,
           abs(end.timeIntervalSince(endsAt)) <= FlintTimeIntegrity.discontinuityThreshold { return endsAt }
        return FlintSessionAutoClearPlan.wholeSecond(atOrAfter: end)
    }

    /// Whether the session still enforces: by logical time when a clock is given (ADR-012 §9),
    /// else by its wall end at `now`, for pure callers that hold only a date.
    public func isEnforcing(at now: Date, clock: FlintClock?) -> Bool {
        guard let clock else { return !isExpired(at: now) }
        return !isLogicallyExpired(clock: clock)
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
        countsTowardLimit: Bool,
        countsTowardClockLimit: Bool = false,
        endsAt newEnd: Date? = nil
    ) -> FlintActiveSession {
        FlintActiveSession(
            id: id, name: name, startedAt: startedAt, endsAt: newEnd ?? endsAt, breakLevel: breakLevel,
            monitorName: monitorName, monotonicStart: monotonicStart, bootSessionID: bootSessionID,
            autoClearTimeZoneID: plan.timeZoneIdentifier, autoClearUTCOffset: plan.utcOffsetAtEnd,
            usesEndWarning: plan.usesEndWarning,
            rearmCount: (rearmCount ?? 0) + (countsTowardLimit ? 1 : 0),
            lastRearmAt: date,
            contractVersion: contractVersion,
            origin: origin,
            ownerID: ownerID,
            plannedDuration: plannedDuration ?? endsAt.map { $0.timeIntervalSince(startedAt) },
            startRequestID: startRequestID,
            clockRearmCount: countsTowardClockLimit ? (clockRearmCount ?? 0) + 1 : clockRearmCount
        )
    }
}
