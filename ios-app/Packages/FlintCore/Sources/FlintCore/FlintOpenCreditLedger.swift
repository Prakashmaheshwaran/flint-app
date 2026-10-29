import Foundation
import CryptoKit

/// ADR-012 §5/§9. All fields are required in schema v2; unavailable is never zero.
public struct FlintOpenCreditState: Codable, Equatable, Sendable {
    public static let maximumTargets = 4096
    public static let maximumGrants = 128
    public static let maximumOperations = 8192
    public enum Availability: String, Codable, Sendable { case pendingImport, ready, unavailable }
    public var availability: Availability = .pendingImport
    public var quotaZone: String?
    public var periodStart: Date?
    public var checkpoint: FlintLedgerInstant?
    /// Wall timeline advanced only by observed monotonic time, independent of clock edits.
    public var trustedWall: Date?
    public var resetRemaining: TimeInterval = 0
    public var counters: [String: Counter] = [:]
    public var knownTargets: Set<String> = []
    /// Unproven replacement/new identities wait until the next trusted period (PC-38).
    public var pendingTargets: Set<String> = []
    public var grants: [String: FlintOpenCreditGrant] = [:]
    public var carrierCheckpoints: [String: FlintLedgerInstant] = [:]
    /// Daily request idempotency (including aliases of a live grant) survives eviction from
    /// the ledger's general recent-operation list.
    public var acceptedOperations: Set<String> = []
    public var previousAcceptedOperations: Set<String> = []
    public var enrolled: Bool = false
    public init() {}

    public struct Counter: Codable, Equatable, Sendable {
        public let targetKey: String
        public let periodStart: Date
        public var used: Int
        public var lastSeen: Date
        public init(targetKey: String, periodStart: Date, used: Int, lastSeen: Date? = nil) {
            self.targetKey = targetKey; self.periodStart = periodStart; self.used = used
            self.lastSeen = lastSeen ?? periodStart
        }
    }

    var isValid: Bool {
        guard availability != .pendingImport, let quotaZone, TimeZone(identifier: quotaZone) != nil,
              let periodStart, let checkpoint, let trustedWall,
              checkpoint.monotonic.isFinite, checkpoint.monotonic >= 0,
              checkpoint.wall.timeIntervalSince1970.isFinite, trustedWall.timeIntervalSince1970.isFinite,
              periodStart.timeIntervalSince1970.isFinite,
              resetRemaining.isFinite, (0...26 * 3600).contains(resetRemaining),
              counters.count <= Self.maximumTargets, pendingTargets.count <= Self.maximumTargets,
              Set(counters.keys) == knownTargets,
              grants.count <= Self.maximumGrants,
              Set(grants.keys) == Set(carrierCheckpoints.keys),
              acceptedOperations.count <= Self.maximumOperations,
              previousAcceptedOperations.count <= Self.maximumOperations else { return false }
        return counters.allSatisfy { key, counter in
            !key.isEmpty && counter.targetKey == key && counter.periodStart == periodStart && counter.used >= 0 &&
                counter.lastSeen.timeIntervalSince1970.isFinite
        } && grants.allSatisfy { key, grant in
            key == grant.targetKey && counters[key] != nil && grant.isValid &&
                acceptedOperations.contains(grant.operationID) && carrierCheckpoints[key]!.monotonic.isFinite
        }
    }
}

public struct FlintOpenCreditGrant: Codable, Equatable, Sendable {
    public let targetKey: String
    public let operationID: String
    public let grantedAt: FlintLedgerInstant
    /// Display/OS registration estimate. Enforcement uses grantedAt's monotonic timeline.
    public let deadline: Date
    public let duration: TimeInterval
    public let coveredCauses: Set<String>
    /// Effective active fields at acceptance, excluding cosmetic/queued template metadata.
    public let coveredFingerprints: [String: String]
    public let coveredScopes: [String: String]
    public let coveredCaps: [String: Int]
    public let coveredActivities: [String: String]
    /// Exact local token/store snapshot, pruned with the grant. Never exported to status.
    public let restoration: FlintOpenCreditRestoration
    public var activityName: String {
        let digest = SHA256.hash(data: Data((targetKey + "#" + operationID).utf8))
        return "flint.open." + digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }
    var isValid: Bool {
        !targetKey.isEmpty && !operationID.isEmpty && !coveredCauses.isEmpty && coveredCauses.count <= 64 &&
        Set(coveredFingerprints.keys) == coveredCauses &&
        Set(coveredScopes.keys) == coveredCauses && Set(coveredCaps.keys) == coveredCauses &&
        Set(coveredActivities.keys) == coveredCauses &&
        coveredFingerprints.values.allSatisfy({ !$0.isEmpty && $0.count <= 128 }) &&
        coveredScopes.values.allSatisfy({ !$0.isEmpty && $0.count <= 128 }) &&
        duration.isFinite && duration > 0 && duration <= 300 && grantedAt.monotonic.isFinite &&
        grantedAt.monotonic >= 0 && deadline.timeIntervalSince1970.isFinite &&
        abs(deadline.timeIntervalSince(grantedAt.wall) - duration) < 0.001 &&
        restoration.isValid(target: targetKey, coveredCauses: coveredCauses)
    }
    public func remaining(at now: FlintLedgerInstant) -> TimeInterval {
        guard isValid, let elapsed = grantedAt.elapsedForRelease(until: now), elapsed.isFinite else { return 0 }
        return max(0, duration - elapsed)
    }
    /// Cap-only changes preserve planned access; weakening authorization belongs to rule CRUD.
    /// Other effective fields must match. The active revision may advance for a cap change.
    func authorizes(caps: [String: Int], fingerprints: [String: String], scopes: [String: String],
                    activities: [String: String]) -> Bool {
        guard Set(caps.keys) == coveredCauses else { return false }
        return caps.allSatisfy { id, cap in
            coveredFingerprints[id] == fingerprints[id] && fingerprints[id] != nil ||
                (coveredScopes[id] == scopes[id] && scopes[id] != nil &&
                 coveredCaps[id].map { cap != $0 } == true &&
                 Self.sameOrAdvancedActivity(from: coveredActivities[id], to: activities[id]))
        }
    }
    func authorizes(cause: String, cap: Int, fingerprint: String, scope: String, activity: String) -> Bool {
        var caps = coveredCaps, fingerprints = coveredFingerprints, scopes = coveredScopes, activities = coveredActivities
        guard coveredCauses.contains(cause) else { return false }
        caps[cause] = cap; fingerprints[cause] = fingerprint; scopes[cause] = scope; activities[cause] = activity
        return authorizes(caps: caps, fingerprints: fingerprints, scopes: scopes, activities: activities)
    }
    private static func sameOrAdvancedActivity(from old: String?, to new: String?) -> Bool {
        guard let old, let new else { return false }
        if old == new { return true }
        func parts(_ name: String) -> (String, Int)? {
            guard let marker = name.range(of: ".r", options: .backwards),
                  let revision = Int(name[marker.upperBound...]) else { return nil }
            return (String(name[..<marker.lowerBound]), revision)
        }
        guard let before = parts(old), let after = parts(new) else { return false }
        return before.0 == after.0 && after.1 > before.1
    }
}

/// Only one opaque token, already persisted in the App Group by the owning rules. This is
/// needed when rule JSON becomes unreadable before the monitor closes the bounded release.
public struct FlintOpenCreditRestoration: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable { case application, webDomain }
    public let kind: Kind
    /// Canonical single-element token array, exactly the bytes used by FlintOpenLimit.key.
    public let encodedToken: Data
    public let storeNames: Set<String>
    public init(kind: Kind, encodedToken: Data, storeNames: Set<String>) {
        self.kind = kind; self.encodedToken = encodedToken; self.storeNames = storeNames
    }
    public var targetKey: String {
        SHA256.hash(data: encodedToken).map { String(format: "%02x", $0) }.joined()
    }
    func isValid(target: String, coveredCauses: Set<String>) -> Bool {
        guard !encodedToken.isEmpty, encodedToken.count <= 65_536, targetKey == target,
              storeNames == Set(coveredCauses.map { "flintO." + $0 }),
              let array = (try? JSONSerialization.jsonObject(with: encodedToken)) as? [Any] else { return false }
        return array.count == 1
    }
    static func make<T: Encodable>(kind: Kind, token: T, storeNames: Set<String>) -> Self? {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        guard let bytes = try? encoder.encode([token]) else { return nil }
        return .init(kind: kind, encodedToken: bytes, storeNames: storeNames)
    }
    /// The live adapter and fake stores share exact payload/scope validation.
    @discardableResult
    func restore<T: Decodable>(as type: T.Type, target: String, coveredCauses: Set<String>,
                               currentTargets: [String: Set<String>]? = nil,
                               apply: (String, T) -> Void) -> Bool {
        guard isValid(target: target, coveredCauses: coveredCauses),
              let tokens = try? JSONDecoder().decode([T].self, from: encodedToken),
              tokens.count == 1, let token = tokens.first else { return false }
        // Readable deleted/disabled rules stay clear. An unreadable rule set uses exactly
        // the frozen covered stores; a readable set may include a new independent Open owner.
        let names = currentTargets.map { Set($0.filter { $0.value.contains(target) }.keys) } ?? storeNames
        guard names.allSatisfy({ $0.hasPrefix("flintO.") }) else { return false }
        for name in names { apply(name, token) }
        return true
    }
}

public enum FlintOpenCreditBlock: String, Codable, Equatable, Sendable {
    case unavailable, exhausted, otherContributor, categoryOverlap, notOpenLimited, identityNeedsReselection
    case registrationFailed, staleOperation, safetyFloor, applying
    public var message: String {
        switch self {
        case .categoryOverlap: return "Another category rule may still block this app; no credit used."
        case .otherContributor: return "Another protection still blocks this target; no credit used."
        case .exhausted: return "No credits remaining today."
        case .identityNeedsReselection: return "Reselect this target. Access is unavailable until the next quota period; no credit used."
        case .notOpenLimited: return "This target has no enabled Open Limit."
        case .applying: return "Credit accepted. Access needs recovery; retry keeps the same deadline."
        default: return "Access unavailable; no credit used."
        }
    }
}

public struct FlintOpenCreditPreview: Equatable, Sendable {
    public let remaining: Int?
    public let cap: Int?
    public let blockedBy: FlintOpenCreditBlock?
    public let grant: FlintOpenCreditGrant?
    public let blockingContributors: [String]
    /// Proposed elapsed access (or the existing grant's remaining time); may be <300 at reset.
    public let duration: TimeInterval?
    public let resetAtEstimate: Date?
    public let quotaZone: String?
    public let timeUncertain: Bool
    public init(remaining: Int?, cap: Int?, blockedBy: FlintOpenCreditBlock?, grant: FlintOpenCreditGrant? = nil,
                blockingContributors: [String] = [], duration: TimeInterval? = nil,
                resetAtEstimate: Date? = nil, quotaZone: String? = nil, timeUncertain: Bool = true) {
        self.remaining = remaining; self.cap = cap; self.blockedBy = blockedBy; self.grant = grant
        self.blockingContributors = blockingContributors
        self.duration = duration; self.resetAtEstimate = resetAtEstimate
        self.quotaZone = quotaZone; self.timeUncertain = timeUncertain
    }
}

public struct FlintOpenCreditReceipt: Equatable, Sendable {
    public let grant: FlintOpenCreditGrant
    public let remaining: Int
    public let replayed: Bool
}

extension FlintLedgerState {
    public static let legacyOpenCreditMigration = "openCredits.v0"
    public static let legacyOpenCreditKey = "flint.openLimit.state"

    /// A revoked authorization cannot reappear if a later edit restores its old fields.
    mutating func reconcileOpenCreditAuthorization(authorized: (FlintOpenCreditGrant) -> Bool?,
                                                   reshield: (FlintOpenCreditGrant) -> Void) {
        for grant in Array(openCredits.grants.values) where authorized(grant) == false {
            reshield(grant)
            openCredits.grants[grant.targetKey] = nil
            openCredits.carrierCheckpoints[grant.targetKey] = nil
        }
    }

    /// Run inside the ledger migration hook. Original legacy bytes are deliberately retained.
    public mutating func importLegacyOpenCredits(
        _ raw: FlintLegacyValue, targetKeys: Set<String>?, established: Bool,
        at now: FlintLedgerInstant, calendar: Calendar
    ) {
        guard !migrations.contains(Self.legacyOpenCreditMigration) else { return }
        defer { migrations.insert(Self.legacyOpenCreditMigration) }
        var quota = FlintOpenCreditState()
        quota.quotaZone = calendar.timeZone.identifier
        quota.periodStart = calendar.startOfDay(for: now.wall)
        quota.checkpoint = now
        quota.trustedWall = now.wall
        quota.resetRemaining = Self.untilMidnight(now.wall, calendar: calendar)
        quota.availability = .ready
        var counts: [String: Int] = [:]
        switch raw {
        case .absent:
            if established { quota.availability = .unavailable }
        case .unreadable:
            quota.availability = .unavailable
        case let .value(value):
            if let bytes = value as? Data,
               let legacy = try? JSONDecoder().decode(FlintOpenLimitState.self, from: bytes),
               legacy.dayStart.timeIntervalSince1970.isFinite,
               legacy.dayStart <= now.wall.addingTimeInterval(5),
               legacy.counts.count <= FlintOpenCreditState.maximumTargets,
               legacy.counts.allSatisfy({ !$0.key.isEmpty && $0.value >= 0 }) {
                // A previous wall date alone does not prove a trusted reset (§9).
                quota.periodStart = calendar.startOfDay(for: legacy.dayStart)
                counts = legacy.counts
            } else { quota.availability = .unavailable }
        }
        if let targetKeys, targetKeys.count <= FlintOpenCreditState.maximumTargets {
            let unmapped = Set(counts.keys).subtracting(targetKeys)
            // A new/rotated token cannot inherit an empty counter when an old identity exists.
            for key in targetKeys where counts[key] == nil {
                if unmapped.isEmpty { counts[key] = 0 } else { quota.pendingTargets.insert(key) }
            }
            quota.enrolled = !targetKeys.isEmpty || !counts.isEmpty
        } else { quota.availability = .unavailable }
        for (key, used) in counts {
            quota.counters[key] = .init(targetKey: key, periodStart: quota.periodStart!, used: used)
        }
        quota.knownTargets = Set(quota.counters.keys)
        if quota.counters.count > FlintOpenCreditState.maximumTargets { quota.availability = .unavailable }
        openCredits = quota
    }

    /// Expire/re-shield before advancing a period or considering another acceptance. Adapters
    /// execute inside the same cross-process lock as all release writes.
    public mutating func reconcileOpenCredits(
        at now: FlintLedgerInstant, targetKeys: Set<String>, setupCalendar: Calendar? = nil,
        reshield: (FlintOpenCreditGrant) -> Void
    ) -> Bool {
        guard openCredits.isValid, now.monotonic.isFinite, now.monotonic >= 0,
              now.wall.timeIntervalSince1970.isFinite else { return false }
        for grant in Array(openCredits.grants.values) where grant.remaining(at: now) <= 0 {
            reshield(grant)
            openCredits.grants[grant.targetKey] = nil
            openCredits.carrierCheckpoints[grant.targetKey] = nil
        }
        let last = openCredits.checkpoint!
        if let elapsed = last.elapsed(until: now) {
            openCredits.resetRemaining = max(0, openCredits.resetRemaining - elapsed)
            openCredits.trustedWall = openCredits.trustedWall!.addingTimeInterval(elapsed)
        }
        openCredits.checkpoint = now // reboot preserves remaining-to-reset; no wall-date refill
        if openCredits.resetRemaining <= 0 {
            guard targetKeys.count <= FlintOpenCreditState.maximumTargets else { return false }
            // Only finished periods may be pruned. Current targets always fit first; retain
            // the most recently observed historical identities in the remaining bounded space.
            let historical = openCredits.knownTargets.subtracting(targetKeys).sorted {
                let left = openCredits.counters[$0]!.lastSeen, right = openCredits.counters[$1]!.lastSeen
                return left == right ? $0 < $1 : left > right
            }
            let keys = targetKeys.union(historical.prefix(FlintOpenCreditState.maximumTargets - targetKeys.count))
            for grant in openCredits.grants.values { reshield(grant) }
            openCredits.grants.removeAll()
            openCredits.carrierCheckpoints.removeAll()
            openCredits.previousAcceptedOperations = openCredits.acceptedOperations
            openCredits.acceptedOperations.removeAll()
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: openCredits.quotaZone!)!
            let trusted = openCredits.trustedWall!
            openCredits.periodStart = calendar.startOfDay(for: trusted)
            openCredits.resetRemaining = Self.untilMidnight(trusted, calendar: calendar)
            // Replace old periods only after the trusted boundary. Keep bounded historical
            // identities so later reselection is provable, including across deletion/reset.
            // Unknown identities become eligible here, never in their first observed period.
            openCredits.counters = Dictionary(uniqueKeysWithValues: keys.map {
                ($0, .init(targetKey: $0, periodStart: openCredits.periodStart!, used: 0,
                          lastSeen: targetKeys.contains($0) ? trusted : openCredits.counters[$0]?.lastSeen))
            })
            openCredits.knownTargets = keys
            openCredits.pendingTargets = []
            openCredits.enrolled = !keys.isEmpty
            openCredits.availability = .ready
        }
        for key in targetKeys where openCredits.counters[key] != nil {
            openCredits.counters[key]!.lastSeen = openCredits.trustedWall!
        }
        if !openCredits.enrolled && openCredits.availability == .ready && !targetKeys.isEmpty {
            guard targetKeys.count <= FlintOpenCreditState.maximumTargets else { return false }
            // A ledger can exist for passes long before the first Open setup. No target has
            // ever had a pool here; capture the quota zone at that first setup, not app launch.
            if let setupCalendar, openCredits.counters.isEmpty, openCredits.pendingTargets.isEmpty {
                openCredits.quotaZone = setupCalendar.timeZone.identifier
                openCredits.periodStart = setupCalendar.startOfDay(for: now.wall)
                openCredits.trustedWall = now.wall
                openCredits.resetRemaining = Self.untilMidnight(now.wall, calendar: setupCalendar)
            }
            for key in targetKeys {
                openCredits.counters[key] = .init(targetKey: key, periodStart: openCredits.periodStart!, used: 0)
            }
            openCredits.knownTargets = targetKeys
            openCredits.enrolled = true
        }
        let unknown = targetKeys.subtracting(openCredits.counters.keys)
        guard openCredits.pendingTargets.union(unknown).count <= FlintOpenCreditState.maximumTargets else { return false }
        openCredits.pendingTargets.formUnion(unknown)
        return openCredits.isValid
    }

    public func openCreditPreview(target: String, caps: [Int], blockedBy: FlintOpenCreditBlock?,
                                  at now: FlintLedgerInstant, contributors: [String] = [],
                                  coveringCauseIDs: Set<String>? = nil,
                                  authorized: ((FlintOpenCreditGrant) -> Bool)? = nil) -> FlintOpenCreditPreview {
        let cap = caps.min()
        guard let cap else { return .init(remaining: nil, cap: nil, blockedBy: .notOpenLimited) }
        guard openCredits.isValid, openCredits.availability == .ready else {
            return .init(remaining: nil, cap: cap, blockedBy: .unavailable)
        }
        guard let counter = openCredits.counters[target] else {
            return .init(remaining: nil, cap: cap, blockedBy: .identityNeedsReselection)
        }
        let remaining = max(0, cap) > counter.used ? max(0, cap) - counter.used : 0
        let grant = openCredits.grants[target].flatMap { $0.remaining(at: now) > 0 ? $0 : nil }
        let additionalOpenCause = grant.map { grant in
            (coveringCauseIDs.map { !$0.isSubset(of: grant.coveredCauses) } ?? false) ||
                (authorized.map { !$0(grant) } ?? false)
        } ?? false
        return .init(remaining: remaining, cap: cap,
                     blockedBy: blockedBy ?? (additionalOpenCause ? .otherContributor :
                        (grant == nil && remaining == 0 ? .exhausted : nil)), grant: grant,
                     blockingContributors: contributors,
                     duration: grant?.remaining(at: now) ?? min(300, openCredits.resetRemaining),
                     resetAtEstimate: now.wall.addingTimeInterval(openCredits.resetRemaining),
                     quotaZone: openCredits.quotaZone,
                     timeUncertain: now.bootID == nil || abs(now.wall.timeIntervalSince(openCredits.trustedWall!)) > 5)
    }

    /// One atomic acceptance. `register` must succeed before consumption; failure spends
    /// nothing and never releases. Callers release only after this transaction is durable.
    public mutating func acceptOpenCredit(
        target: String, operationID: String, coveringCaps: [String: Int], coveringFingerprints: [String: String],
        coveringScopes: [String: String]? = nil, coveringActivities: [String: String]? = nil, targetKeys: Set<String>,
        restoration: FlintOpenCreditRestoration,
        at now: FlintLedgerInstant, setupCalendar: Calendar? = nil, blocking: () -> FlintOpenCreditBlock?,
        register: (FlintOpenCreditGrant) throws -> Void, reshield: (FlintOpenCreditGrant) -> Void
    ) -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        guard reconcileOpenCredits(at: now, targetKeys: targetKeys, setupCalendar: setupCalendar, reshield: reshield) else {
            return .failure(.denied(.unavailable))
        }
        let scopes = coveringScopes ?? coveringFingerprints
        let activities = coveringActivities ?? coveringCaps.mapValues { _ in "legacy" }
        if let grant = openCredits.grants[target],
           !grant.authorizes(caps: coveringCaps, fingerprints: coveringFingerprints, scopes: scopes, activities: activities) {
            reshield(grant)
            openCredits.grants[target] = nil
            openCredits.carrierCheckpoints[target] = nil
            return .failure(.denied(.otherContributor))
        }
        let preview = openCreditPreview(target: target, caps: Array(coveringCaps.values), blockedBy: blocking(),
                                        at: now, coveringCauseIDs: Set(coveringCaps.keys))
        if let block = preview.blockedBy { return .failure(.denied(block)) }
        guard !operationID.isEmpty, operationID.count <= 128 else { return .failure(.denied(.staleOperation)) }
        if let grant = preview.grant {
            // A different request answered by the same live interval must not become a new
            // charge when its delayed retry arrives after that interval expires.
            if !openCredits.acceptedOperations.contains(operationID) {
                guard openCredits.acceptedOperations.count < FlintOpenCreditState.maximumOperations else {
                    return .failure(.denied(.unavailable))
                }
                openCredits.acceptedOperations.insert(operationID)
            }
            return .success(.init(grant: grant, remaining: preview.remaining!, replayed: true))
        }
        guard FlintProtectionPolicy.decideOpenCredit(creditsRemaining: preview.remaining,
            blockingContributors: []) == .allow(confirm: true) else { return .failure(.denied(.exhausted)) }
        // A delayed retry after expiry must not charge again or recreate access (PC-23).
        guard operation(operationID) == nil,
              !openCredits.acceptedOperations.contains(operationID),
              !openCredits.previousAcceptedOperations.contains(operationID) else { return .failure(.denied(.staleOperation)) }
        guard openCredits.grants.count < FlintOpenCreditState.maximumGrants, coveringCaps.count <= 64 else {
            return .failure(.denied(.unavailable))
        }
        guard openCredits.acceptedOperations.count < FlintOpenCreditState.maximumOperations else {
            return .failure(.denied(.unavailable))
        }
        let duration = min(300, openCredits.resetRemaining)
        guard duration > 0, Set(coveringFingerprints.keys) == Set(coveringCaps.keys),
              Set(scopes.keys) == Set(coveringCaps.keys),
              Set(activities.keys) == Set(coveringCaps.keys),
              coveringFingerprints.values.allSatisfy({ !$0.isEmpty && $0.count <= 128 }),
              scopes.values.allSatisfy({ !$0.isEmpty && $0.count <= 128 }),
              restoration.isValid(target: target, coveredCauses: Set(coveringCaps.keys)) else {
            return .failure(.denied(.unavailable))
        }
        let grant = FlintOpenCreditGrant(targetKey: target, operationID: operationID, grantedAt: now,
            deadline: now.wall.addingTimeInterval(duration), duration: duration, coveredCauses: Set(coveringCaps.keys),
            coveredFingerprints: coveringFingerprints, coveredScopes: scopes,
            coveredCaps: coveringCaps, coveredActivities: activities, restoration: restoration)
        do { try register(grant) } catch { return .failure(.denied(.registrationFailed)) }
        openCredits.counters[target]!.used += 1
        openCredits.grants[target] = grant
        openCredits.carrierCheckpoints[target] = now
        openCredits.acceptedOperations.insert(operationID)
        remember(.init(id: operationID, kind: "openCredit", result: "accepted", at: now.wall))
        return .success(.init(grant: grant, remaining: preview.remaining! - 1, replayed: false))
    }

    private static func untilMidnight(_ now: Date, calendar: Calendar) -> TimeInterval {
        guard let interval = calendar.dateInterval(of: .day, for: now) else { return 26 * 3600 }
        return min(26 * 3600, max(0, interval.end.timeIntervalSince(now)))
    }
}

public enum FlintOpenCreditRefusal: Error, Equatable, Sendable { case denied(FlintOpenCreditBlock) }

/// Shared short-interval adapter. Warning offsets include seconds so a midnight-clipped
/// credit is never silently rounded up to a longer interval.
public struct FlintOpenCreditCarrierPlan: Equatable, Sendable {
    public let intervalStart: DateComponents
    public let intervalEnd: DateComponents
    public let warningTime: DateComponents
    public init(start: Date, remaining: TimeInterval) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        // DeviceActivity uses whole seconds. Round up (at most one second per endpoint);
        // a callback must still verify the monotonic deadline, and early delivery re-arms.
        let rounded = Date(timeIntervalSince1970: start.timeIntervalSince1970.rounded(.up))
        let seconds = max(1, min(300, Int(remaining.rounded(.up))))
        let fields: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        var from = calendar.dateComponents(fields, from: rounded)
        var to = calendar.dateComponents(fields, from: rounded.addingTimeInterval(900))
        from.timeZone = calendar.timeZone; to.timeZone = calendar.timeZone
        intervalStart = from; intervalEnd = to
        warningTime = DateComponents(second: 900 - seconds)
    }
}
