import Foundation

/// The frozen phase plan of one Pomodoro chain (ADR-012 §1; `docs/architecture/pomodoro-chain.md` §2).
///
/// A Pomodoro is **one** parent commitment spanning every focus round and the planned rests
/// between them. The plan is pure value data: the phase is a function of the parent's trusted
/// logical elapsed time, never of wall time, so a clock change or a process restart cannot move a
/// round boundary.
///
/// Phases run `F1, R1, F2, R2, …, F(rounds)`. The rest after focus round `k` is a long rest when
/// `longRestEvery` is set and `k % longRestEvery == 0`. The chain ends after the last focus round.
public struct FlintPomodoroPlan: Codable, Equatable, Sendable {
    /// Bounds shared with the Android implementation (design §2).
    public static let focusRange = 5...120
    public static let focusStep = 5
    public static let restRange = 1...60
    public static let roundsRange = 2...8
    public static let longRestRange = 5...60

    public enum Kind: String, Codable, Equatable, Sendable {
        case focus
        case rest
        case longRest
    }

    /// One phase of the chain, as elapsed offsets from the parent's start.
    public struct Phase: Codable, Equatable, Sendable, Identifiable {
        public let index: Int
        public let kind: Kind
        /// The 1-based focus round. A rest reports the round it follows.
        public let round: Int
        public let start: TimeInterval
        public let end: TimeInterval

        public var id: Int { index }
        public var isRest: Bool { kind != .focus }
        public var duration: TimeInterval { end - start }
    }

    /// Where a chain's elapsed time falls.
    public enum Position: Equatable, Sendable {
        case phase(Phase)
        case finished

        public var phase: Phase? {
            if case let .phase(value) = self { return value }
            return nil
        }
    }

    /// A rest the chain pre-registers an iOS carrier for.
    public struct Rest: Codable, Equatable, Sendable {
        /// 1-based rest ordinal; also the round the rest follows.
        public let index: Int
        /// Index into `phases`.
        public let phaseIndex: Int
        public let isLong: Bool
        public let start: TimeInterval
        public let end: TimeInterval
    }

    public let focusMinutes: Int
    public let restMinutes: Int
    public let rounds: Int
    /// Both long-rest fields are set, or neither.
    public let longRestMinutes: Int?
    public let longRestEvery: Int?

    /// nil when the values break the design's bounds.
    public init?(
        focusMinutes: Int,
        restMinutes: Int,
        rounds: Int,
        longRestMinutes: Int? = nil,
        longRestEvery: Int? = nil
    ) {
        guard Self.focusRange.contains(focusMinutes), focusMinutes % Self.focusStep == 0,
              Self.restRange.contains(restMinutes),
              Self.roundsRange.contains(rounds) else { return nil }
        switch (longRestMinutes, longRestEvery) {
        case (nil, nil):
            break
        case let (minutes?, every?):
            guard Self.longRestRange.contains(minutes), minutes >= restMinutes,
                   every >= 2, every <= rounds - 1 else { return nil }
        default:
            return nil
        }
        self.focusMinutes = focusMinutes
        self.restMinutes = restMinutes
        self.rounds = rounds
        self.longRestMinutes = longRestMinutes
        self.longRestEvery = longRestEvery
    }

    /// The plan a saved cadence describes, or nil when the saved values are out of bounds.
    public init?(config: FlintPomodoroConfig) {
        self.init(focusMinutes: config.focusMinutes, restMinutes: config.breakMinutes,
                  rounds: config.rounds, longRestMinutes: config.longRestMinutes,
                  longRestEvery: config.longRestEvery)
    }

    /// The classic 25 / 5 × 4 chain.
    public static let standard = FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4)!

    /// The rest after a 1-based round: its kind and length in seconds.
    private func rest(afterRound round: Int) -> (kind: Kind, length: TimeInterval) {
        let isLong = longRestEvery.map { round % $0 == 0 } ?? false
        let minutes = isLong ? (longRestMinutes ?? restMinutes) : restMinutes
        return (isLong ? .longRest : .rest, TimeInterval(minutes * 60))
    }

    /// The whole chain's length: the committed duration of the parent session.
    public var totalDuration: TimeInterval {
        var total = 0.0
        for round in 1...rounds {
            total += TimeInterval(focusMinutes * 60)
            if round < rounds { total += rest(afterRound: round).length }
        }
        return total
    }

    /// Every phase in order.
    public var phases: [Phase] {
        var result: [Phase] = []
        var cursor: TimeInterval = 0
        for round in 1...rounds {
            let focus = TimeInterval(focusMinutes * 60)
            result.append(Phase(index: result.count, kind: .focus, round: round,
                                start: cursor, end: cursor + focus))
            cursor += focus
            guard round < rounds else { continue }
            let rest = rest(afterRound: round)
            result.append(Phase(index: result.count, kind: rest.kind, round: round,
                                start: cursor, end: cursor + rest.length))
            cursor += rest.length
        }
        return result
    }

    /// Every planned rest, in order.
    public var rests: [Rest] {
        phases.compactMap { phase in
            guard phase.isRest else { return nil }
            return Rest(index: phase.round, phaseIndex: phase.index,
                        isLong: phase.kind == .longRest, start: phase.start, end: phase.end)
        }
    }

    /// Half-open phases `[start, end)`; negative input clamps to 0 and a time at or past the total
    /// is `finished`.
    public func phaseAt(elapsed: TimeInterval) -> Position {
        let value = elapsed.isFinite ? max(0, elapsed) : (elapsed.sign == .minus ? 0 : totalDuration)
        guard value < totalDuration else { return .finished }
        for phase in phases where value >= phase.start && value < phase.end {
            return .phase(phase)
        }
        // A chain always covers [0, total); reaching here means an empty plan.
        return .finished
    }
}

extension FlintPomodoroPlan {
    /// Total length in whole minutes (display copy in the start confirmation).
    public var totalMinutes: Int { Int(totalDuration / 60) }

    /// The rest that supplies a carrier at `index` (1-based), or nil.
    public func rest(at index: Int) -> Rest? { rests.first { $0.index == index } }

    /// Whether the plan puts a rest after the given 1-based round.
    func restAfterRound(_ round: Int) -> Rest? { rests.first { $0.index == round } }
}
