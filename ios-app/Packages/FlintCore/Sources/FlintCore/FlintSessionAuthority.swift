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

    /// New ledgers and positively readable legacy upgrades create this initial tombstone.
    /// Once a generation exists, an absent projection never recreates this initial authority.
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
        public var emitsStart: Bool? = nil
        public var startEventEmitted: Bool? = nil
        public var previousSession: FlintActiveSession? = nil
        public var previousApplication: PreviousApplication? = nil
        /// Restoring a previously applied commitment is different from inventing application
        /// of a newly accepted start whose installation never finished.
        public var restoresAppliedSession: Bool? = nil

        public func matches(_ session: FlintActiveSession) -> Bool {
            self.session.id == session.id && self.session.monitorName == session.monitorName
                && self.session.startedAt == session.startedAt
        }
    }

    public struct PreviousApplication: Codable, Equatable, Sendable {
        public var selection: Data?
        public var source: FlintSessionSource
        public var emitsStart: Bool?
        public var startEventEmitted: Bool?
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

    /// Read-only status projection. Legacy absence predates generations and migrates on the
    /// next transition; an existing generation without a terminal record is always unknown.
    public static func recordIsUnavailable(_ session: FlintActiveSession?, recordPresent: Bool,
                                           authority: Self?, authorityVersion: Int? = nil, clock: FlintClock) -> Bool {
        if session == nil && recordPresent { return true }
        if authorityVersion != nil && (authorityVersion != 1 || authority == nil) { return true }
        guard let authority else { return false }
        guard authority.isValid else { return true }
        guard let session else { return !authority.provesIdle }
        guard authority.latest != nil else { return false }
        if authority.provesIdle { return authority.latest?.matches(session) != true }
        return authority.observedSession(matching: session, clock: clock) == nil
    }

    public static func observedProjection(_ session: FlintActiveSession?, authority: Self?, clock: FlintClock) -> FlintActiveSession? {
        guard let session else { return nil }
        guard let authority, authority.latest != nil else { return session }
        guard !authority.provesIdle else { return nil }
        return authority.observedSession(matching: session, clock: clock)
    }

    public func acceptedStart(_ operationID: String) -> FlintAcceptedStartResult? {
        acceptedStarts.last { $0.operationID == operationID }
    }

    /// UserDefaults is a projection of the ledger generation. A crash after a ledger checkpoint
    /// must not let an older projection restore time. Conversely, during a policy-ledger outage
    /// the positively decoded record can observe a new boot; keep that bounded current-boot
    /// observation when the ledger becomes readable again.
    public func observedSession(matching stored: FlintActiveSession, clock: FlintClock) -> FlintActiveSession? {
        guard let latest, latest.matches(stored), !provesIdle,
              stored.remainingCheckpoint?.isValid != false,
              let committed = Self.commitmentData(latest.session),
              committed == Self.commitmentData(stored) else { return nil }
        let canonical = latest.session
        var selected = canonical
        if let observed = stored.remainingCheckpoint {
            let saved = canonical.remainingCheckpoint
            let ceiling = saved?.remaining ?? canonical.plannedDuration
                ?? canonical.endsAt.map { max(0, $0.timeIntervalSince(canonical.startedAt)) }
            if let saved, let boot = observed.bootID, boot == saved.bootID {
                if observed.monotonic >= saved.monotonic {
                    guard ceiling.map({ observed.remaining <= $0 + 0.000_001 }) != false else { return nil }
                    let oldDeadline = saved.monotonic + saved.remaining - saved.overrun
                    let newDeadline = observed.monotonic + observed.remaining - observed.overrun
                    guard abs(oldDeadline - newDeadline) < 0.000_001 else { return nil }
                    selected = stored
                }
            } else {
                guard ceiling.map({ observed.remaining <= $0 + 0.000_001 }) != false else { return nil }
                if saved == nil, let boot = observed.bootID, canonical.bootSessionID == boot,
                   let start = canonical.monotonicStart, let planned = ceiling {
                    guard abs(start + planned - (observed.monotonic + observed.remaining - observed.overrun))
                        < 0.000_001 else { return nil }
                }
                if observed.bootID != nil && observed.bootID == clock.bootSessionID() {
                    selected = stored
                } else if saved == nil || observed.remaining < (saved?.remaining ?? .infinity) {
                    selected = stored
                }
            }
        }
        if let observedEnd = stored.naturalEndObservedAt {
            guard stored.remainingCheckpoint?.remaining == 0,
                  stored.isLogicallyExpired(clock: clock) else { return nil }
            selected = selected.checkpointed(clock: clock, naturalEndObservedAt: observedEnd)
        }
        return selected
    }

    /// Exclude observations explicitly, so future commitment fields participate by default.
    private static func commitmentData(_ session: FlintActiveSession) -> Data? {
        guard let data = try? JSONEncoder().encode(session),
              var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        for key in ["remainingCheckpoint", "naturalEndObservedAt"] { object.removeValue(forKey: key) }
        return try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    /// A display-time observation must not invalidate an Emergency Pass confirmation hold.
    /// Exclude only this known observational field; new commitment keys remain bound by default.
    static func scopePolicy(_ data: Data) throws -> Data {
        guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw FlintSessionAuthorityError.generationMismatch
        }
        for key in ["remainingCheckpoint", "naturalEndObservedAt"] { object.removeValue(forKey: key) }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    /// A token is saved before confirmation/start. Without an OS invocation ID identical
    /// Shortcuts within two minutes intentionally share one attempt; explicit IDs avoid that
    /// ambiguity. Terminal outcomes immediately end the heuristic Shortcut retry window.
    public mutating func requestToken(route: String, fingerprint: String,
                                      at now: FlintLedgerInstant, untilDeactivation: Bool = false) -> FlintSessionStartToken {
        if let old = routeRequests.last(where: { $0.route == route && $0.fingerprint == fingerprint }) {
            let result = acceptedStart(old.token.operationID)
            if old.untilDeactivation {
                guard result == nil else { return old.token }
                let refreshed = FlintSessionStartToken(operationID: old.token.operationID, expectedRevision: revision)
                if let index = routeRequests.lastIndex(where: { $0.route == route && $0.fingerprint == fingerprint }) {
                    routeRequests[index].token = refreshed
                }
                return refreshed
            }
            let stillRunning = result.map {
                $0.outcome == .running && !provesIdle && latest?.session.id == $0.sessionID
                    && latest.map { !$0.session.isLogicallyExpired(clock: now.clock) } == true
            } ?? true
            let currentPreview = result != nil || old.token.expectedRevision == revision
            if currentPreview && stillRunning
                && old.at.elapsed(until: now).map({ $0 <= Self.retryWindow }) == true {
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
                                 at now: FlintLedgerInstant, previousSelection: Data? = nil) throws {
        if let expectedRevision, expectedRevision != revision { throw FlintSessionAuthorityError.staleRevision }
        if let old = acceptedStart(operationID) {
            throw old.fingerprint == fingerprint ? FlintSessionAuthorityError.alreadyAccepted : .conflictingReuse
        }
        let previousSession = latest.flatMap { provesIdle ? nil : $0.session }
        let previousApplication = previousSession.flatMap { _ in latest.map {
            PreviousApplication(selection: previousSelection, source: $0.source,
                emitsStart: $0.emitsStart, startEventEmitted: $0.startEventEmitted)
        } }
        let previous = previousSession?.monitorName
        revision += 1
        latest = Generation(revision: revision, session: session, acceptedEnd: session.endsAt,
                            phase: .installing, selection: selection, source: source,
                            previousMonitorName: previous)
        latest?.emitsStart = true
        latest?.startEventEmitted = false
        latest?.previousSession = previousSession
        latest?.previousApplication = previousApplication
        tombstone = nil
        acceptedStarts.append(FlintAcceptedStartResult(operationID: operationID, session: session,
            outcome: .running, acceptedAt: now, fingerprint: fingerprint, expectedRevision: expectedRevision))
        prune()
    }

    /// Retire an unapplied replacement without dropping the previously applied commitment.
    /// The restored generation remains installing until its projection/resources read back.
    public mutating func restorePrevious(_ previous: FlintActiveSession, selection: Data?, at now: Date) throws {
        guard let interrupted = latest, interrupted.phase == .installing,
              let old = interrupted.previousSession,
              old.id == previous.id, old.monitorName == previous.monitorName,
              old.startedAt == previous.startedAt,
              Self.commitmentData(old) == Self.commitmentData(previous),
              previous.remainingCheckpoint?.isValid != false,
              revision < UInt64.max - 2, !provesIdle else {
            throw FlintSessionAuthorityError.generationMismatch
        }
        try terminate(interrupted.session, outcome: .unconfirmed, route: "start_expired_unapplied", at: now)
        revision += 1
        latest = Generation(revision: revision, session: previous, acceptedEnd: previous.endsAt,
            phase: .installing, selection: selection, source: interrupted.previousApplication?.source ?? .legacy,
            previousMonitorName: interrupted.session.monitorName)
        latest?.emitsStart = interrupted.previousApplication?.emitsStart ?? false
        latest?.startEventEmitted = interrupted.previousApplication?.startEventEmitted ?? true
        latest?.restoresAppliedSession = true
        latest?.previousSession = interrupted.session // permitted pre-restoration projection after a crash
        tombstone = nil
        for index in acceptedStarts.indices where acceptedStarts[index].sessionID == previous.id {
            acceptedStarts[index].outcome = .running
            acceptedStarts[index].endedAt = nil
            acceptedStarts[index].endRoute = nil
        }
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
        for index in acceptedStarts.indices where acceptedStarts[index].sessionID != session.id
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
        let oldSession = latest?.session
        let oldApplication = latest.map { PreviousApplication(selection: selection, source: $0.source,
            emitsStart: $0.emitsStart, startEventEmitted: $0.startEventEmitted) }
        let oldMonitor = oldSession?.monitorName
        revision += 1
        latest?.revision = revision
        latest?.session = session
        latest?.acceptedEnd = session.endsAt
        latest?.phase = .installing
        latest?.selection = selection
        latest?.previousMonitorName = oldMonitor
        latest?.emitsStart = false
        latest?.previousSession = oldSession
        latest?.previousApplication = oldApplication
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
        for index in acceptedStarts.indices where acceptedStarts[index].sessionID == session.id {
            acceptedStarts[index].outcome = outcome
            acceptedStarts[index].endedAt = now
            acceptedStarts[index].endRoute = route
        }
        for index in acceptedStarts.indices where acceptedStarts[index].sessionID != session.id
            && acceptedStarts[index].outcome == .running {
            acceptedStarts[index].outcome = .superseded
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
    public var sessionID: String
    public var outcome: Outcome
    public var acceptedAt: FlintLedgerInstant
    public var fingerprint: String
    public var expectedRevision: UInt64?
    public var endedAt: Date? = nil
    public var endRoute: String? = nil

    public init(operationID: String, session: FlintActiveSession, outcome: Outcome,
                acceptedAt: FlintLedgerInstant, fingerprint: String, expectedRevision: UInt64?,
                endedAt: Date? = nil, endRoute: String? = nil) {
        self.operationID = operationID; sessionID = session.id; self.outcome = outcome
        self.acceptedAt = acceptedAt; self.fingerprint = fingerprint; self.expectedRevision = expectedRevision
        self.endedAt = endedAt; self.endRoute = endRoute
    }

    private enum CodingKeys: String, CodingKey {
        case operationID, sessionID, outcome, acceptedAt, fingerprint, expectedRevision, endedAt, endRoute
    }
    private enum LegacyKeys: String, CodingKey { case session }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        operationID = try values.decode(String.self, forKey: .operationID)
        if let id = try values.decodeIfPresent(String.self, forKey: .sessionID) { sessionID = id }
        else {
            sessionID = try decoder.container(keyedBy: LegacyKeys.self)
                .decode(FlintActiveSession.self, forKey: .session).id
        }
        outcome = try values.decode(Outcome.self, forKey: .outcome)
        acceptedAt = try values.decode(FlintLedgerInstant.self, forKey: .acceptedAt)
        fingerprint = try values.decode(String.self, forKey: .fingerprint)
        expectedRevision = try values.decodeIfPresent(UInt64.self, forKey: .expectedRevision)
        endedAt = try values.decodeIfPresent(Date.self, forKey: .endedAt)
        endRoute = try values.decodeIfPresent(String.self, forKey: .endRoute)
    }
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

extension FlintLedgerInstant {
    /// A fixed observation for calculations that must not mix entry time with later IO time.
    var clock: FlintClock {
        FlintClock(now: { self.wall }, monotonic: { self.monotonic }, bootSessionID: { self.bootID }, calendar: { .current })
    }
}
