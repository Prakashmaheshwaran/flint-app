import CryptoKit
import Foundation

/// Persisted with the policy ledger, under session transition → ledger lock. A missing active
/// UserDefaults key is idle only when this authority has the latest generation's tombstone.
public struct FlintSessionAuthority: Codable, Equatable, Sendable {
    public static let startMemory = 64
    public static let retryWindow: TimeInterval = 120

    public var revision: UInt64
    public var latest: Generation?
    public var tombstone: Tombstone?
    public var acceptedStarts: [FlintAcceptedStartResult]
    public var routeRequests: [RouteRequest]

    /// Only a validated new ledger may create this initial no-session tombstone. A legacy
    /// decoded ledger without an authority must adopt a positively decoded session or fail closed.
    public init(initializedAt: Date) {
        revision = 0
        latest = nil
        tombstone = Tombstone(sessionID: nil, monitorName: nil, generationRevision: 0,
                              route: "initialized", at: initializedAt)
        acceptedStarts = []
        routeRequests = []
    }

    public struct Generation: Codable, Equatable, Sendable {
        public enum Phase: String, Codable, Sendable { case installing, applied }
        public var revision: UInt64
        public var session: FlintActiveSession
        public var acceptedEnd: Date?
        public var phase: Phase
        /// Kept only until installation finishes, to recover a crash before the session save.
        public var selection: Data?
        public var source: FlintSessionSource
        public var previousMonitorName: String?

        public func matches(_ session: FlintActiveSession) -> Bool {
            self.session.id == session.id && self.session.monitorName == session.monitorName
                && self.session.startedAt == session.startedAt
        }
    }

    public struct Tombstone: Codable, Equatable, Sendable {
        public var sessionID: String?
        public var monitorName: String?
        public var generationRevision: UInt64
        public var route: String
        public var at: Date
    }

    public struct RouteRequest: Codable, Equatable, Sendable {
        public var route: String
        public var fingerprint: String
        public var token: FlintSessionStartToken
        public var at: FlintLedgerInstant
        /// Focus keeps its activation identity until an actual Focus-off observation.
        public var untilDeactivation: Bool
    }

    public var provesIdle: Bool {
        guard let tombstone else { return false }
        guard let latest else {
            return revision == 0 && tombstone.generationRevision == 0
                && tombstone.sessionID == nil && tombstone.monitorName == nil
        }
        return tombstone.sessionID == latest.session.id
            && tombstone.monitorName == latest.session.monitorName
            && tombstone.generationRevision == latest.revision
    }

    public var isValid: Bool {
        guard revision < UInt64.max,
              acceptedStarts.count <= Self.startMemory, routeRequests.count <= Self.startMemory,
              Set(acceptedStarts.map(\.operationID)).count == acceptedStarts.count else { return false }
        if let latest {
            guard latest.revision > 0, latest.revision <= revision,
                  latest.session.remainingCheckpoint?.isValid != false else { return false }
            if tombstone != nil { return provesIdle }
            return revision == latest.revision
        }
        return provesIdle
    }

    public func acceptedStart(_ operationID: String) -> FlintAcceptedStartResult? {
        acceptedStarts.last { $0.operationID == operationID }
    }

    /// A token is saved before confirmation/start. Without an OS invocation ID identical
    /// Shortcuts within two minutes intentionally share one attempt; explicit IDs avoid that
    /// ambiguity. Terminal outcomes immediately end the heuristic Shortcut retry window.
    public mutating func requestToken(route: String, fingerprint: String,
                                      at now: FlintLedgerInstant, untilDeactivation: Bool = false) -> FlintSessionStartToken {
        if let old = routeRequests.last(where: { $0.route == route && $0.fingerprint == fingerprint }) {
            let result = acceptedStart(old.token.operationID)
            let stillRunning = result.map { $0.outcome == .running && !$0.session.isLogicallyExpired(clock: now.clock) } ?? true
            let currentPreview = result != nil || old.token.expectedRevision == revision
            if old.untilDeactivation || (currentPreview && stillRunning
                && old.at.elapsed(until: now).map { $0 <= Self.retryWindow } == true) {
                return old.token
            }
        }
        let token = FlintSessionStartToken(operationID: UUID().uuidString, expectedRevision: revision)
        routeRequests.removeAll { $0.route == route }
        routeRequests.append(RouteRequest(route: route, fingerprint: fingerprint, token: token,
                                           at: now, untilDeactivation: untilDeactivation))
        if routeRequests.count > Self.startMemory { routeRequests.removeFirst(routeRequests.count - Self.startMemory) }
        return token
    }

    public mutating func deactivate(route: String) {
        routeRequests.removeAll { $0.route == route }
    }

    public mutating func install(_ session: FlintActiveSession, selection: Data, source: FlintSessionSource,
                                 operationID: String, fingerprint: String, expectedRevision: UInt64?,
                                 at now: FlintLedgerInstant) throws {
        if let expectedRevision, expectedRevision != revision { throw FlintSessionAuthorityError.staleRevision }
        if let old = acceptedStart(operationID) {
            throw old.fingerprint == fingerprint ? FlintSessionAuthorityError.alreadyAccepted : .conflictingReuse
        }
        let previous = latest.flatMap { provesIdle ? nil : $0.session.monitorName }
        revision += 1
        latest = Generation(revision: revision, session: session, acceptedEnd: session.endsAt,
                            phase: .installing, selection: selection, source: source,
                            previousMonitorName: previous)
        tombstone = nil
        acceptedStarts.append(FlintAcceptedStartResult(operationID: operationID, session: session,
            outcome: .running, acceptedAt: now, fingerprint: fingerprint, expectedRevision: expectedRevision))
        prune()
    }

    /// Import only a positively decoded legacy record. Absence never enters this migration.
    public mutating func adopt(_ session: FlintActiveSession) {
        revision += 1
        latest = Generation(revision: revision, session: session, acceptedEnd: session.endsAt,
                            phase: .applied, selection: nil, source: .legacy, previousMonitorName: nil)
        tombstone = nil
    }

    public mutating func markApplied(_ session: FlintActiveSession) throws {
        guard latest?.matches(session) == true, !provesIdle else { throw FlintSessionAuthorityError.generationMismatch }
        latest?.phase = .applied
        latest?.selection = nil
        for index in acceptedStarts.indices where acceptedStarts[index].session.id != session.id
            && acceptedStarts[index].outcome == .running {
            acceptedStarts[index].outcome = .superseded
        }
    }

    /// Time observations do not invalidate a confirmation. A changed registration does.
    public mutating func checkpoint(_ session: FlintActiveSession) throws {
        guard latest?.matches(session) == true, !provesIdle else { throw FlintSessionAuthorityError.generationMismatch }
        latest?.session = session
    }

    public mutating func rearm(_ session: FlintActiveSession, selection: Data? = nil) throws {
        guard latest?.session.id == session.id, !provesIdle else { throw FlintSessionAuthorityError.generationMismatch }
        let oldMonitor = latest?.session.monitorName
        revision += 1
        latest?.revision = revision
        latest?.session = session
        latest?.acceptedEnd = session.endsAt
        latest?.phase = .installing
        latest?.selection = selection
        latest?.previousMonitorName = oldMonitor
    }

    /// Commit the terminal decision before any release. Replaying this exact tombstone is a
    /// no-op; a previous generation can never write a tombstone over its successor.
    @discardableResult
    public mutating func terminate(_ session: FlintActiveSession, outcome: FlintAcceptedStartResult.Outcome,
                                   route: String, at now: Date) throws -> Bool {
        guard let latest, latest.matches(session) else { throw FlintSessionAuthorityError.generationMismatch }
        if provesIdle { return false }
        revision += 1
        tombstone = Tombstone(sessionID: session.id, monitorName: session.monitorName,
                              generationRevision: latest.revision, route: route, at: now)
        for index in acceptedStarts.indices where acceptedStarts[index].session.id == session.id {
            acceptedStarts[index].outcome = outcome
            acceptedStarts[index].endedAt = now
            acceptedStarts[index].endRoute = route
        }
        return true
    }

    private mutating func prune() {
        if acceptedStarts.count > Self.startMemory {
            acceptedStarts.removeFirst(acceptedStarts.count - Self.startMemory)
        }
    }
}

public struct FlintSessionStartToken: Codable, Equatable, Sendable {
    public let operationID: String
    public let expectedRevision: UInt64

    public init(operationID: String, expectedRevision: UInt64) {
        self.operationID = operationID
        self.expectedRevision = expectedRevision
    }
}

public struct FlintAcceptedStartResult: Codable, Equatable, Sendable {
    public enum Outcome: String, Codable, Sendable {
        case running, completed, stopped, superseded, unconfirmed, failedToApply
    }
    public var operationID: String
    public var session: FlintActiveSession
    public var outcome: Outcome
    public var acceptedAt: FlintLedgerInstant
    public var fingerprint: String
    public var expectedRevision: UInt64?
    public var endedAt: Date? = nil
    public var endRoute: String? = nil

    public var sessionID: String { session.id }
}

public enum FlintSessionAuthorityError: Error, Equatable {
    case conflictingReuse, staleRevision, generationMismatch, alreadyAccepted
}

/// Canonical parameter identity, with opaque selection identities supplied by the shield seam.
/// Confirmation itself is excluded: asking and then confirming is the same operation.
public struct FlintSessionStartParameters: Codable, Equatable, Sendable {
    public var duration: TimeInterval
    public var tier: BreakLevel
    public var selection: String
    public var name: String
    public var source: FlintSessionSource
    public var origin: String
    public var ownerID: String?
    public var replacing: Bool

    public init(duration: TimeInterval, tier: BreakLevel, selection: String, name: String,
                source: FlintSessionSource, origin: String, ownerID: String?, replacing: Bool) {
        self.duration = duration; self.tier = tier; self.selection = selection; self.name = name
        self.source = source; self.origin = origin; self.ownerID = ownerID; self.replacing = replacing
    }

    public var fingerprint: String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(self) else { return "invalid" }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

private extension FlintLedgerInstant {
    var clock: FlintClock {
        FlintClock(now: { self.wall }, monotonic: { self.monotonic }, bootSessionID: { self.bootID }, calendar: { .current })
    }
}
