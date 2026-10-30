import Foundation

/// R-23 phase 1: pure persisted value only; NOT wired into the ledger, UI, or native stores.
/// Phase 2 adds an optional ledger field with decodeIfPresent. A present invalid/future grant
/// must fail decoding, never become a missing grant or default available state (ADR-012 §8).
public struct FlintBreakGrant: Codable, Equatable, Sendable, Identifiable {
    public static let currentVersion = 1
    public struct Cause: Codable, Equatable, Sendable {
        public let causeID: String
        public let occurrenceID: String
        public let revision: Int
        public let tier: BreakLevel
        public init(_ cause: FlintCauseState) {
            causeID = cause.causeID; occurrenceID = cause.occurrenceID
            revision = cause.revision; tier = cause.tier
        }
        private enum CodingKeys: String, CodingKey { case causeID, occurrenceID, revision, tier }
        var isValid: Bool { !causeID.isEmpty && !occurrenceID.isEmpty && revision >= 0 && tier != .hardcore }
    }

    public let version: Int
    public let id: String
    public let operationID: String
    public let scope: FlintBreakScope
    public let causes: [Cause]
    public let acceptedAt: FlintLedgerInstant
    /// Wall is the display/registration estimate; monotonic + bootID govern the actual end.
    public let deadline: FlintLedgerInstant

    // Construction belongs to commitPlan, which caps the end and rechecks authorization.
    init(id: String, operationID: String, scope: FlintBreakScope, causes: [Cause],
         acceptedAt: FlintLedgerInstant, deadline: FlintLedgerInstant) {
        version = Self.currentVersion; self.id = id; self.operationID = operationID
        self.scope = scope; self.causes = causes; self.acceptedAt = acceptedAt; self.deadline = deadline
    }
    private enum CodingKeys: String, CodingKey { case version, id, operationID, scope, causes, acceptedAt, deadline }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decode(Int.self, forKey: .version)
        id = try values.decode(String.self, forKey: .id)
        operationID = try values.decode(String.self, forKey: .operationID)
        scope = try values.decode(FlintBreakScope.self, forKey: .scope)
        causes = try values.decode([Cause].self, forKey: .causes)
        acceptedAt = try values.decode(FlintLedgerInstant.self, forKey: .acceptedAt)
        deadline = try values.decode(FlintLedgerInstant.self, forKey: .deadline)
        guard isValid else {
            throw DecodingError.dataCorruptedError(forKey: .version, in: values,
                                                   debugDescription: "Invalid or unsupported ordinary break grant")
        }
    }
    var isValid: Bool {
        let duration = deadline.monotonic - acceptedAt.monotonic
        return version == Self.currentVersion && !id.isEmpty && !operationID.isEmpty && scope.isValid &&
            !causes.isEmpty && causes.count <= 128 && causes.allSatisfy(\.isValid) &&
            Set(causes.map(\.occurrenceID)).count == causes.count &&
            Self.validInstant(acceptedAt) && Self.validInstant(deadline) && acceptedAt.bootID == deadline.bootID &&
            duration > 0 && duration <= FlintProtectionConstants.breakDuration &&
            abs(deadline.wall.timeIntervalSince(acceptedAt.wall) - duration) < 0.001
    }
    static func validInstant(_ instant: FlintLedgerInstant) -> Bool {
        instant.wall.timeIntervalSince1970.isFinite && instant.monotonic.isFinite && instant.monotonic >= 0 &&
            instant.bootID.map { !$0.isEmpty } == true
    }
    public func remaining(at now: FlintLedgerInstant) -> TimeInterval {
        guard isValid, Self.validInstant(now), acceptedAt.bootID == now.bootID,
              let elapsed = acceptedAt.elapsedForRelease(until: now) else { return 0 }
        return max(0, deadline.monotonic - acceptedAt.monotonic - elapsed)
    }
    public func remaining(clock: FlintClock) -> TimeInterval { remaining(at: FlintLedgerInstant(clock: clock)) }

    /// Replays are receipts, not authority to release a new cause/revision or extend the end.
    public func authorizes(_ cause: FlintCauseState, target: FlintBreakScope.Target, at now: FlintLedgerInstant) -> Bool {
        remaining(at: now) > 0 && cause.isActive && scope.targets.contains(target) && causes.contains(Cause(cause))
    }
}
