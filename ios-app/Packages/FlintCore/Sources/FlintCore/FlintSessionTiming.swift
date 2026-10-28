import Foundation

// MARK: - Auto-clear plan

/// How a timed session's out-of-app teardown is registered with `DeviceActivityCenter`: the
/// non-repeating interval's wall-clock endpoints and, for short sessions, the end warning.
///
/// `DeviceActivitySchedule` rejects intervals under 15 minutes, yet Flint offers 5- and
/// 10-minute sessions. A session of N < 15 minutes registers a padded 15-minute interval with
/// `warningTime = 15 − N` minutes, so iOS's `intervalWillEndWarning` arrives at N minutes and
/// the monitor ends the session there. If a device never delivers that warning, the padded
/// interval's end still ends the session at 15 minutes — protection is never dropped early.
/// Device behaviour is unverified; lane B's physical-device acceptance checklist records the
/// check, and `.floor` is the honest fallback (refuse sessions under 15 minutes).
///
/// Pure value math so every boundary is unit-tested without Screen Time.
public struct FlintSessionAutoClearPlan: Equatable, Sendable {
    public enum ShortSessionStrategy: Equatable, Sendable {
        /// Pad to 15 minutes and end on `intervalWillEndWarning` (the shipped default).
        case endWarning
        /// Refuse sessions shorter than 15 minutes.
        case floor
    }

    public static let shortSessionStrategy: ShortSessionStrategy = .endWarning

    /// DeviceActivity's minimum interval length.
    public static let minimumInterval: TimeInterval = 15 * 60
    /// Shortest timed session Flint arms.
    public static let minimumDuration: TimeInterval = 60
    /// Longest timed session: the interval is wall-clock hour/minute/second components without
    /// a date, so a span of a day or more would wrap onto the wrong end.
    public static let maximumDuration: TimeInterval = 23 * 3600 + 59 * 60

    public let intervalStart: DateComponents
    public let intervalEnd: DateComponents
    /// Set only when the session is shorter than `minimumInterval`.
    public let warningTime: DateComponents?
    public let timeZoneIdentifier: String
    /// Seconds from GMT at the session's end in `timeZoneIdentifier`.
    public let utcOffsetAtEnd: Int

    public var usesEndWarning: Bool { warningTime != nil }

    public enum Issue: Equatable, Sendable {
        case tooShort
        case tooLong
    }

    /// Validates a requested timed duration against the strategy in force.
    public static func issue(
        forDuration duration: TimeInterval,
        strategy: ShortSessionStrategy = shortSessionStrategy
    ) -> Issue? {
        let floor = strategy == .floor ? minimumInterval : minimumDuration
        if duration < floor { return .tooShort }
        if duration > maximumDuration { return .tooLong }
        return nil
    }

    /// The registration that ends a session at `end`, starting now (`start`). `end` may be any
    /// remaining span — a re-registration after a time-zone change passes the remainder.
    public static func make(
        start: Date,
        end: Date,
        calendar: Calendar,
        strategy: ShortSessionStrategy = shortSessionStrategy
    ) -> FlintSessionAutoClearPlan {
        let span = end.timeIntervalSince(start)
        var intervalEndDate = end
        var warning: DateComponents?
        if span < minimumInterval {
            intervalEndDate = start.addingTimeInterval(minimumInterval)
            // Whole minutes only, rounded down: the warning may land a little after `end`,
            // never before it (the monitor refuses to end a session early).
            let lead = Int((minimumInterval - max(0, span)) / 60)
            if strategy == .endWarning, lead > 0 { warning = DateComponents(minute: lead) }
        }
        let fields: Set<Calendar.Component> = [.hour, .minute, .second]
        let zone = calendar.timeZone
        return FlintSessionAutoClearPlan(
            intervalStart: calendar.dateComponents(fields, from: start),
            intervalEnd: calendar.dateComponents(fields, from: intervalEndDate),
            warningTime: warning,
            timeZoneIdentifier: zone.identifier,
            utcOffsetAtEnd: zone.secondsFromGMT(for: end)
        )
    }

    /// Whether a session's registered components still land on its `endsAt` in `calendar`. Only
    /// the UTC offset at `endsAt` matters (a renamed zone identifier with the same offset lands
    /// on the same instant). Legacy sessions (no recorded offset) are assumed to.
    public static func isCurrent(for session: FlintActiveSession, in calendar: Calendar) -> Bool {
        guard let endsAt = session.endsAt, let offset = session.autoClearUTCOffset else { return true }
        return calendar.timeZone.secondsFromGMT(for: endsAt) == offset
    }

    /// Whole seconds, rounded up: DeviceActivity takes hour/minute/second components, so a
    /// session's start and end are kept on whole seconds and the registered end lands exactly
    /// on `endsAt` instead of up to a second before it.
    public static func wholeSecond(atOrAfter date: Date) -> Date {
        Date(timeIntervalSinceReferenceDate: date.timeIntervalSinceReferenceDate.rounded(.up))
    }
}

// MARK: - Clock

/// Every time source session enforcement reads, injectable for tests.
public struct FlintClock {
    public var now: () -> Date
    /// Seconds on a system-wide clock that keeps counting through device sleep, ignores
    /// wall-clock changes and restarts at boot (`CLOCK_MONOTONIC_RAW`). Host and extensions
    /// read the same counter, so anchors compare across processes.
    public var monotonic: () -> TimeInterval
    /// Identifies the current boot, or nil when unavailable.
    public var bootSessionID: () -> String?
    public var calendar: () -> Calendar

    public init(
        now: @escaping () -> Date,
        monotonic: @escaping () -> TimeInterval,
        bootSessionID: @escaping () -> String?,
        calendar: @escaping () -> Calendar
    ) {
        self.now = now
        self.monotonic = monotonic
        self.bootSessionID = bootSessionID
        self.calendar = calendar
    }

    public static let system = FlintClock(
        now: { Date() },
        monotonic: { TimeInterval(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)) / 1_000_000_000 },
        bootSessionID: { FlintClock.readBootSessionID() },
        // `autoupdatingCurrent` follows a time-zone change made while the process runs.
        calendar: { Calendar.autoupdatingCurrent }
    )

    static func readBootSessionID() -> String? {
        var size = 0
        guard sysctlbyname("kern.bootsessionuuid", nil, &size, nil, 0) == 0, size > 1 else { return nil }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname("kern.bootsessionuuid", &buffer, &size, nil, 0) == 0 else { return nil }
        let value = String(cString: buffer)
        return value.isEmpty ? nil : value
    }
}

// MARK: - Time integrity

/// Detects a wall-clock change during a running session by comparing elapsed wall time with
/// elapsed monotonic time since the session's anchors.
///
/// Detection only. iOS cannot veto a clock change, and DeviceActivity fires on wall-clock
/// components, so a clock moved forward can still end a session early and one moved back can
/// stretch it. Flint records the anomaly so it is never silent; which clock governs a session's
/// end is the protection contract's call (ADR-012), not this type's.
public enum FlintTimeIntegrity {
    /// A wall-vs-monotonic discrepancy above this is a clock discontinuity (ADR-012 §9).
    public static let discontinuityThreshold: TimeInterval = 5
    /// How far from `endsAt` a monitor end may be observed and still be reported as an on-time
    /// completion to focus history (its provisional 120 s rule; ADR-012 §11 still forbids
    /// ending before the logical deadline, which `endSlack` enforces).
    public static let onTimeWindow: TimeInterval = 120
    /// How early an end callback may arrive and still count as the planned end — DeviceActivity
    /// resolves whole-second components. Anything earlier is premature and never ends a session.
    public static let endSlack: TimeInterval = 1

    public enum Verdict: Equatable, Sendable {
        case consistent
        /// The wall clock reads later than real elapsed time (moved forward) by `seconds`.
        case wallAhead(seconds: TimeInterval)
        /// The wall clock reads earlier than real elapsed time (moved back) by `seconds`.
        case wallBehind(seconds: TimeInterval)
        /// No anchors (older session) or the device rebooted since the start.
        case unverifiable
    }

    public static func verdict(for session: FlintActiveSession, clock: FlintClock) -> Verdict {
        verdict(
            wallStart: session.startedAt, monotonicStart: session.monotonicStart,
            bootSessionID: session.bootSessionID,
            now: clock.now(), monotonicNow: clock.monotonic(), currentBootSessionID: clock.bootSessionID()
        )
    }

    public static func verdict(
        wallStart: Date,
        monotonicStart: TimeInterval?,
        bootSessionID: String?,
        now: Date,
        monotonicNow: TimeInterval,
        currentBootSessionID: String?
    ) -> Verdict {
        guard let monotonicStart else { return .unverifiable }
        let sameBootKnown: Bool
        if let bootSessionID, let currentBootSessionID {
            guard bootSessionID == currentBootSessionID else { return .unverifiable }
            sameBootKnown = true
        } else {
            sameBootKnown = false
        }
        // A monotonic reading below the anchor proves a reboot.
        guard monotonicNow >= monotonicStart else { return .unverifiable }
        let skew = now.timeIntervalSince(wallStart) - (monotonicNow - monotonicStart)
        // An undetected reboot makes monotonic time *under*-count, which looks exactly like a
        // forward clock change — so "ahead" is only trusted when the boot is known to match.
        // A reboot can never make the wall clock look behind.
        if skew > discontinuityThreshold { return sameBootKnown ? .wallAhead(seconds: skew) : .unverifiable }
        if skew < -discontinuityThreshold { return .wallBehind(seconds: -skew) }
        return .consistent
    }
}

// MARK: - Session health (user-visible arming and time state)

/// What went wrong arming or timing the manual session, persisted so Settings can show it even
/// when the failing start came from a background intent or a Focus filter.
public struct FlintSessionHealth: Codable, Equatable, Sendable {
    public struct ArmFailure: Codable, Equatable, Sendable {
        public let sessionName: String
        public let reason: String
        public let at: Date
    }

    public struct TimeAnomaly: Codable, Equatable, Sendable {
        public enum Kind: String, Codable, Sendable {
            /// The wall clock moved forward during the session.
            case wallAhead
            /// The wall clock moved back during the session.
            case wallBehind
            /// The time zone changed; the auto-clear was re-registered for the same end.
            case timeZoneChanged
            /// iOS delivered the session's end before `endsAt`; Flint kept the block and
            /// re-registered its end.
            case prematureEnd
            /// A short session's end warning never arrived; its padded interval end finished it
            /// `seconds` after `endsAt` (the device check for the warning-time adapter).
            case missedEndWarning
        }

        public let kind: Kind
        /// Size of the detected skew in seconds; 0 when not measurable.
        public let seconds: TimeInterval
        public let sessionID: String
        public let detectedAt: Date
    }

    public var lastArmFailure: ArmFailure?
    public var lastTimeAnomaly: TimeAnomaly?
    /// Set while a session is recorded as running but Screen Time authorization is not approved,
    /// i.e. iOS is enforcing nothing for it. Cleared once authorization is back or it ends.
    public var unauthorizedSince: Date?

    public init(lastArmFailure: ArmFailure? = nil, lastTimeAnomaly: TimeAnomaly? = nil, unauthorizedSince: Date? = nil) {
        self.lastArmFailure = lastArmFailure
        self.lastTimeAnomaly = lastTimeAnomaly
        self.unauthorizedSince = unauthorizedSince
    }

    private static let storageKey = "flint.sessionHealth"

    public static func load(from defaults: UserDefaults? = .flintGroup) -> FlintSessionHealth {
        guard let data = defaults?.data(forKey: storageKey),
              let health = try? JSONDecoder().decode(FlintSessionHealth.self, from: data) else {
            return FlintSessionHealth()
        }
        return health
    }

    public func save(to defaults: UserDefaults? = .flintGroup) {
        defaults?.set(try? JSONEncoder().encode(self), forKey: Self.storageKey)
    }

    /// Load-modify-save; a no-op without the App Group.
    static func update(in defaults: UserDefaults?, _ change: (inout FlintSessionHealth) -> Void) {
        guard let defaults else { return }
        var health = load(from: defaults)
        change(&health)
        health.save(to: defaults)
    }
}
