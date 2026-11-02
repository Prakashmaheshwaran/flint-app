import Foundation

/// A finite commitment's last measured remaining duration (ADR-012 §9; R-05). `overrun` keeps
/// the logical completion instant when an observation arrives late, even after another save.
public struct FlintSessionRemainingCheckpoint: Codable, Equatable, Sendable {
    public let remaining: TimeInterval
    public let wall: Date
    public let monotonic: TimeInterval
    public let bootID: String?
    public let overrun: TimeInterval
    public let timeUncertain: Bool

    public init(remaining: TimeInterval, wall: Date, monotonic: TimeInterval, bootID: String?,
                overrun: TimeInterval = 0, timeUncertain: Bool = false) {
        self.remaining = remaining
        self.wall = wall
        self.monotonic = monotonic
        self.bootID = bootID
        self.overrun = overrun
        self.timeUncertain = timeUncertain
    }

    public var isValid: Bool {
        remaining.isFinite && remaining >= 0 && monotonic.isFinite && monotonic >= 0
            && wall.timeIntervalSince1970.isFinite && overrun.isFinite && overrun >= 0
            && (remaining == 0 || overrun == 0)
    }

    func elapsed(clock: FlintClock) -> TimeInterval? {
        guard let bootID, !bootID.isEmpty, bootID == clock.bootSessionID() else { return nil }
        let elapsed = clock.monotonic() - monotonic
        return elapsed >= 0 ? elapsed : nil
    }
}
