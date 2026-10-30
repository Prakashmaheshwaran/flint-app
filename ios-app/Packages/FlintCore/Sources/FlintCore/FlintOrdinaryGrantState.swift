import Foundation
import CryptoKit

public enum FlintBreakEndReason: String, Codable, Equatable, Sendable {
    case deadline, supersession, pass, reboot
    case resumeNow = "resume_now"
    case newCommitment = "new_commitment"
}

/// Accepted authority, including the end and selection that revision-zero sessions cannot
/// identify on their own. Wall estimates are deliberately excluded from equality after a
/// clock edit; the same-boot monotonic end is the authority.
public struct FlintBreakAuthority: Codable, Equatable, Sendable {
    public let cause: FlintBreakGrant.Cause
    public let storeName: String
    public let selection: FlintBreakScope
    public let allowList: Bool
    public let logicalEnd: FlintLedgerInstant?
    public let focusSessionID: String?

    init(contributor: FlintBreakContributor, storeName: String) throws {
        guard let state = contributor.cause, let selection = contributor.selection,
              contributor.timeKnown else { throw FlintOrdinaryBreakError.unavailable }
        cause = .init(state); self.storeName = storeName; logicalEnd = contributor.logicalEnd
        focusSessionID = state.kind == .session ? state.causeID : nil
        switch selection {
        case let .blockList(scope): self.selection = scope; allowList = false
        case let .allowList(allowed): self.selection = .init(targets: allowed); allowList = true
        }
    }

    var isValid: Bool {
        cause.isValid && !storeName.isEmpty && storeName.utf8.count <= 256 &&
            (selection.isValid || (allowList && selection.targets.isEmpty)) &&
            (!allowList || selection.targets.allSatisfy { $0.kind == .application }) &&
            (logicalEnd.map(FlintBreakGrant.validInstant) ?? (cause.tier == .easy))
    }

    func matches(_ contributor: FlintBreakContributor, storeName: String) -> Bool {
        guard contributor.cause?.isActive == true,
              let other = try? Self(contributor: contributor, storeName: storeName),
              cause == other.cause, self.storeName == other.storeName,
              selection == other.selection, allowList == other.allowList else { return false }
        switch (logicalEnd, other.logicalEnd) {
        case (nil, nil): return true
        case let (old?, new?):
            return old.bootID == new.bootID && abs(old.monotonic - new.monotonic) < 0.01
        default: return false
        }
    }
}

public enum FlintOrdinaryBreakError: Error, Equatable, LocalizedError {
    case unavailable, confirmationRequired, waitNotMatured, pendingMismatch, expiredRequest
    case hardcore
    case activeGrant(TimeInterval)
    public var errorDescription: String? {
        switch self {
        case .hardcore: return "Hardcore allows no breaks — Emergency Pass is separate"
        case .activeGrant: return "A break is already active"
        case .confirmationRequired: return "Confirm the 5-minute break"
        case .waitNotMatured: return "The break is not available yet"
        case .pendingMismatch: return "Your protection changed. Start a new wait."
        case .expiredRequest: return "This access request has ended. No extra credit was spent."
        case .unavailable: return "Access unavailable"
        }
    }
}

/// Active authorization and pending effects stay strict. Finished receipts are separately
/// encoded so a corrupt audit entry can be retained without poisoning unrelated protection.
public struct FlintOrdinaryGrantState: Codable, Equatable, Sendable {
    public static let maximumGrants = 128
    public static let maximumReceipts = 128
    public static let maximumOperations = 8192
    public static let maximumEvents = 256
    public static let maximumBytes = 4 * 1024 * 1024
    public var version = 1
    public var generation = UUID().uuidString
    public var previousGeneration: String?
    public var acceptedOperations: Set<String> = []
    public var previousAcceptedOperations: Set<String> = []
    public var records: [Record] = []
    public var receipts: [Data] = []
    public var eventOutbox: [Data] = []
    public var pending: FlintBreakAuthorization.Request?
    public init() {}
    static func validObservation(_ now: FlintLedgerInstant) -> Bool {
        now.wall.timeIntervalSince1970.isFinite && now.monotonic.isFinite && now.monotonic >= 0
    }

    /// Lane F's sink currently returns Void, which cannot acknowledge durable append. These
    /// intents survive until the separate typed history adapter supplies a positive receipt.
    public struct EventIntent: Codable, Equatable, Sendable {
        public enum Kind: String, Codable, Sendable { case started = "break_started", ended = "break_ended" }
        public let kind: Kind
        public let grantID: String
        public let focusSessionIDs: Set<String>
        public let at: Date
        public let observedAt: Date
        public let breakEnd: Date?
        public let reason: FlintBreakEndReason?
        public let timeConfidence: FlintEventTimeConfidence?
        public var breakKind = "ordinary"
        public var acknowledgedSessionIDs: Set<String> = []
        public var isAcknowledged: Bool { acknowledgedSessionIDs == focusSessionIDs }
        public func stableID(for sessionID: String) -> String { grantID + "." + kind.rawValue + "." + sessionID }
    }

    public struct Record: Codable, Equatable, Sendable {
        public let grant: FlintBreakGrant
        public let authorities: [FlintBreakAuthority]
        /// Shrinks permanently when a new commitment invalidates part of the accepted scope.
        public var releasedTargets: Set<FlintBreakScope.Target>
        public var carrierCheckpoint: FlintLedgerInstant
        public var endedAt: FlintLedgerInstant?
        public var endReason: FlintBreakEndReason?
        public var applicationPending = true
        public var events: [EventIntent]

        init(grant: FlintBreakGrant, authorities: [FlintBreakAuthority]) {
            self.grant = grant; self.authorities = authorities
            releasedTargets = grant.scope.targets; carrierCheckpoint = grant.acceptedAt
            events = [.init(kind: .started, grantID: grant.id,
                focusSessionIDs: Set(authorities.compactMap(\.focusSessionID)), at: grant.acceptedAt.wall,
                observedAt: grant.acceptedAt.wall, breakEnd: grant.deadline.wall, reason: nil, timeConfidence: .certain)]
        }
        public var activityName: String { Self.activityName(grant.id) }
        static func activityName(_ id: String) -> String {
            "flint.break." + SHA256.hash(data: Data(id.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
        }
        public func remaining(at now: FlintLedgerInstant) -> TimeInterval {
            endReason == nil ? grant.remaining(at: now) : 0
        }
        var isValid: Bool {
            grant.isValid && authorities.count == grant.causes.count && authorities.allSatisfy(\.isValid) &&
                Set(authorities.map { $0.cause.causeID }) == Set(grant.causes.map(\.causeID)) &&
                authorities.allSatisfy { grant.causes.contains($0.cause) } &&
                releasedTargets.isSubset(of: grant.scope.targets) &&
                FlintBreakGrant.validInstant(carrierCheckpoint) &&
                ((endedAt == nil && endReason == nil && events.count == 1) ||
                 (endedAt.map(FlintOrdinaryGrantState.validObservation) == true && endReason != nil && events.count == 2)) &&
                events.first?.kind == .started && events.allSatisfy {
                    $0.grantID == grant.id && $0.focusSessionIDs == Set(authorities.compactMap(\.focusSessionID)) &&
                        $0.acknowledgedSessionIDs.isSubset(of: $0.focusSessionIDs) && $0.breakKind == "ordinary" &&
                        $0.at.timeIntervalSince1970.isFinite && $0.observedAt.timeIntervalSince1970.isFinite
                }
        }
        mutating func end(_ reason: FlintBreakEndReason, at now: FlintLedgerInstant) {
            guard endReason == nil else { return }
            endReason = reason; applicationPending = true
            let elapsed = grant.acceptedAt.elapsedForRelease(until: now)
            let trusted = now.bootID != nil && now.bootID == grant.acceptedAt.bootID && elapsed != nil
            let logicalDate: Date
            if trusted {
                logicalDate = reason == .deadline ? grant.deadline.wall : grant.acceptedAt.wall.addingTimeInterval(elapsed!)
            } else { logicalDate = now.wall }
            endedAt = .init(wall: logicalDate, monotonic: reason == .deadline ? grant.deadline.monotonic : now.monotonic, bootID: now.bootID)
            events.append(.init(kind: .ended, grantID: grant.id,
                focusSessionIDs: Set(authorities.compactMap(\.focusSessionID)), at: logicalDate,
                observedAt: now.wall, breakEnd: nil, reason: reason,
                timeConfidence: trusted ? .certain : .uncertain(reason: "reboot_or_unknown_boot")))
        }
    }

    public struct Receipt: Codable, Equatable, Sendable {
        public let grantID: String
        public let operationID: String
        public let scopeDigest: String
        public let reason: FlintBreakEndReason
        public let endedAt: FlintLedgerInstant
        init(_ record: Record) {
            grantID = record.grant.id; operationID = record.grant.operationID
            scopeDigest = Self.digest(record.grant.scope)
            reason = record.endReason!; endedAt = record.endedAt!
        }
        static func digest(_ scope: FlintBreakScope) -> String {
            SHA256.hash(data: Data(scope.targets.map(\.key).sorted().joined(separator: "\n").utf8))
                .map { String(format: "%02x", $0) }.joined()
        }
    }

    var isValid: Bool {
        guard version == 1, UUID(uuidString: generation) != nil,
              previousGeneration.map({ UUID(uuidString: $0) != nil && $0 != generation }) ?? true,
              records.count <= Self.maximumGrants, receipts.count <= Self.maximumReceipts,
              eventOutbox.count + records.reduce(0, { $0 + ($1.endReason == nil ? 2 : $1.events.count) }) <= Self.maximumEvents,
              acceptedOperations.count <= Self.maximumOperations,
              previousAcceptedOperations.count <= Self.maximumOperations,
              acceptedOperations.allSatisfy({ validOperation($0, generation: generation) }),
              previousAcceptedOperations.allSatisfy({ validOperation($0, generation: previousGeneration) }),
              Set(records.map { $0.grant.id }).count == records.count,
              Set(records.map { $0.grant.operationID }).count == records.count,
              records.allSatisfy({ $0.isValid && contains($0.grant.operationID) }) else { return false }
        // Receipt contents are audit only. Preserve invalid bytes; neither decode nor use
        // them as active authorization. The aggregate cap also bounds retained corruption.
        return (try? FlintProtectionLedger.makeEncoder().encode(self).count).map { $0 <= Self.maximumBytes } ?? false
    }

    func contains(_ operationID: String) -> Bool {
        acceptedOperations.contains(operationID) || previousAcceptedOperations.contains(operationID)
    }
    func validOperation(_ id: String, generation: String?) -> Bool {
        guard let generation, id.hasPrefix(generation + ":") else { return false }
        return UUID(uuidString: String(id.dropFirst(generation.count + 1))) != nil
    }
    /// Generation-bound IDs make bounded retention safe: even after both replay sets rotate,
    /// an old ID cannot become a fresh request. No wall-clock period is inferred.
    mutating func newOperationID() throws -> String {
        if acceptedOperations.count >= Self.maximumOperations {
            guard records.isEmpty else { throw FlintOrdinaryBreakError.unavailable }
            previousGeneration = generation; previousAcceptedOperations = acceptedOperations
            generation = UUID().uuidString; acceptedOperations = []
        }
        return generation + ":" + UUID().uuidString
    }

    mutating func archiveFinished() throws {
        for record in records where record.endReason != nil && !record.applicationPending {
            receipts.append(try FlintProtectionLedger.makeEncoder().encode(Receipt(record)))
            for event in record.events where !event.isAcknowledged {
                eventOutbox.append(try FlintProtectionLedger.makeEncoder().encode(event))
            }
        }
        records.removeAll { $0.endReason != nil && !$0.applicationPending }
        receipts = Array(receipts.suffix(Self.maximumReceipts))
    }

    func receipt(for request: FlintBreakAuthorization.Request) -> Receipt? {
        receipts.reversed().compactMap { try? FlintProtectionLedger.makeDecoder().decode(Receipt.self, from: $0) }
            .first { $0.operationID == request.operationID && $0.scopeDigest == Receipt.digest(request.scope) }
    }
}

extension FlintLedgerState {
    /// All checks precede mutation. Throwing is essential: the outer ledger transaction is
    /// the rollback boundary, including if a later commit unexpectedly fails.
    mutating func commitOrdinaryBreak(_ plan: FlintBreakAuthorization.CommitPlan,
                                     authorities: [FlintBreakAuthority],
                                     request: FlintBreakAuthorization.Request,
                                     contributors: [FlintBreakContributor], at now: FlintLedgerInstant) throws {
        var grants = ordinaryGrants ?? FlintOrdinaryGrantState()
        guard grants.isValid, grants.validOperation(request.operationID, generation: grants.generation),
              !grants.contains(request.operationID), grants.records.count < FlintOrdinaryGrantState.maximumGrants,
              plan.grant.operationID == request.operationID, plan.grant.scope == request.scope else {
            throw FlintOrdinaryBreakError.unavailable
        }
        let scope = FlintBreakAuthorization.waitScope(request: request, contributors: contributors)
        guard !scope.isEmpty else { throw FlintOrdinaryBreakError.unavailable }
        for increment in plan.increments {
            guard let entry = harder[increment.ledgerKey], entry.committedCount == increment.expectedCount,
                  increment.nextCount == increment.expectedCount + 1,
                  !hasCommittedHarderWait(cause: increment.ledgerKey, operationID: request.operationID),
                  let pending = entry.pending, pending.operationID == request.operationID,
                  pending.kind == "break", pending.scope == scope, pending.requestedAt.bootID == now.bootID,
                  FlintBreakGrant.validInstant(pending.requestedAt), pending.wait.isFinite,
                  pending.wait >= FlintProtectionPolicy.harderWait(forCount: entry.committedCount),
                  pending.requestedAt.elapsed(until: now).map({ $0 >= pending.wait }) == true else {
                throw FlintOrdinaryBreakError.pendingMismatch
            }
        }
        let record = FlintOrdinaryGrantState.Record(grant: plan.grant, authorities: authorities)
        guard record.isValid else { throw FlintOrdinaryBreakError.unavailable }
        grants.records.append(record); grants.acceptedOperations.insert(request.operationID)
        guard grants.isValid else { throw FlintOrdinaryBreakError.unavailable }
        for increment in plan.increments {
            guard commitHarderWait(cause: increment.ledgerKey, operationID: request.operationID, at: now) == .committed else {
                throw FlintOrdinaryBreakError.pendingMismatch
            }
        }
        ordinaryGrants = grants
    }
}
