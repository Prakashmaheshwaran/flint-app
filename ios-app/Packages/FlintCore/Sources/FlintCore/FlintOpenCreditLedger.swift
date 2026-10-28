import Foundation
import CryptoKit

/// ADR-012 §5/§9. All fields are required in schema v2; unavailable is never zero.
public struct FlintOpenCreditState: Codable, Equatable, Sendable {
    public static let maximumTargets = 4096
    public static let maximumGrants = 128
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
    public var enrolled: Bool = false
    public init() {}

    public struct Counter: Codable, Equatable, Sendable {
        public let targetKey: String
        public let periodStart: Date
        public var used: Int
        public init(targetKey: String, periodStart: Date, used: Int) {
            self.targetKey = targetKey; self.periodStart = periodStart; self.used = used
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
              grants.count <= Self.maximumGrants else { return false }
        return counters.allSatisfy { key, counter in
            !key.isEmpty && counter.targetKey == key && counter.periodStart == periodStart && counter.used >= 0
        } && grants.allSatisfy { key, grant in
            key == grant.targetKey && counters[key] != nil && grant.isValid
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
    public var activityName: String {
        let digest = SHA256.hash(data: Data((targetKey + "#" + operationID).utf8))
        return "flint.open." + digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }
    var isValid: Bool {
        !targetKey.isEmpty && !operationID.isEmpty && !coveredCauses.isEmpty && coveredCauses.count <= 64 &&
        duration.isFinite && duration > 0 && duration <= 300 && grantedAt.monotonic.isFinite &&
        grantedAt.monotonic >= 0 && deadline.timeIntervalSince1970.isFinite &&
        abs(deadline.timeIntervalSince(grantedAt.wall) - duration) < 0.001
    }
    public func remaining(at now: FlintLedgerInstant) -> TimeInterval {
        guard isValid, let elapsed = grantedAt.elapsedForRelease(until: now), elapsed.isFinite else { return 0 }
        return max(0, duration - elapsed)
    }
}

public enum FlintOpenCreditBlock: String, Codable, Equatable, Sendable {
    case unavailable, exhausted, otherContributor, categoryOverlap, notOpenLimited, identityNeedsReselection
    case registrationFailed, staleOperation, safetyFloor
    public var message: String {
        switch self {
        case .categoryOverlap: return "Another category rule may still block this app; no credit used."
        case .otherContributor: return "Another protection still blocks this target; no credit used."
        case .exhausted: return "No credits remaining today."
        case .identityNeedsReselection: return "Reselect this target. Access is unavailable until the next quota period; no credit used."
        case .notOpenLimited: return "This target has no enabled Open Limit."
        default: return "Access unavailable; no credit used."
        }
    }
}

public struct FlintOpenCreditPreview: Equatable, Sendable {
    public let remaining: Int?
    public let cap: Int?
    public let blockedBy: FlintOpenCreditBlock?
    public let grant: FlintOpenCreditGrant?
    public init(remaining: Int?, cap: Int?, blockedBy: FlintOpenCreditBlock?, grant: FlintOpenCreditGrant? = nil) {
        self.remaining = remaining; self.cap = cap; self.blockedBy = blockedBy; self.grant = grant
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
        at now: FlintLedgerInstant, targetKeys: Set<String>, reshield: (FlintOpenCreditGrant) -> Void
    ) -> Bool {
        guard openCredits.isValid, now.monotonic.isFinite, now.monotonic >= 0,
              now.wall.timeIntervalSince1970.isFinite else { return false }
        for grant in Array(openCredits.grants.values) where grant.remaining(at: now) <= 0 {
            reshield(grant)
            openCredits.grants[grant.targetKey] = nil
        }
        let last = openCredits.checkpoint!
        if let elapsed = last.elapsed(until: now) {
            openCredits.resetRemaining = max(0, openCredits.resetRemaining - elapsed)
            openCredits.trustedWall = openCredits.trustedWall!.addingTimeInterval(elapsed)
        }
        openCredits.checkpoint = now // reboot preserves remaining-to-reset; no wall-date refill
        if openCredits.resetRemaining <= 0 {
            for grant in openCredits.grants.values { reshield(grant) }
            openCredits.grants.removeAll()
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: openCredits.quotaZone!)!
            let trusted = openCredits.trustedWall!
            openCredits.periodStart = calendar.startOfDay(for: trusted)
            openCredits.resetRemaining = Self.untilMidnight(trusted, calendar: calendar)
            // Prune old periods only after the trusted boundary. Current unknown identities
            // become eligible here, never in the period in which they first appeared.
            let keys = targetKeys.union(openCredits.pendingTargets)
            guard keys.count <= FlintOpenCreditState.maximumTargets else { return false }
            openCredits.counters = Dictionary(uniqueKeysWithValues: keys.map {
                ($0, .init(targetKey: $0, periodStart: openCredits.periodStart!, used: 0))
            })
            openCredits.knownTargets = keys
            openCredits.pendingTargets = []
            openCredits.enrolled = !keys.isEmpty
            openCredits.availability = .ready
        }
        if !openCredits.enrolled && openCredits.availability == .ready && !targetKeys.isEmpty {
            guard targetKeys.count <= FlintOpenCreditState.maximumTargets else { return false }
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
                                  at now: FlintLedgerInstant) -> FlintOpenCreditPreview {
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
        return .init(remaining: remaining, cap: cap,
                     blockedBy: blockedBy ?? (grant == nil && remaining == 0 ? .exhausted : nil), grant: grant)
    }

    /// One atomic acceptance. `register` must succeed before consumption; failure spends
    /// nothing and never releases. Callers release only after this transaction is durable.
    public mutating func acceptOpenCredit(
        target: String, operationID: String, coveringCaps: [String: Int], targetKeys: Set<String>,
        at now: FlintLedgerInstant, blocking: () -> FlintOpenCreditBlock?,
        register: (FlintOpenCreditGrant) throws -> Void, reshield: (FlintOpenCreditGrant) -> Void
    ) -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        guard reconcileOpenCredits(at: now, targetKeys: targetKeys, reshield: reshield) else {
            return .failure(.denied(.unavailable))
        }
        let preview = openCreditPreview(target: target, caps: Array(coveringCaps.values), blockedBy: blocking(), at: now)
        if let block = preview.blockedBy { return .failure(.denied(block)) }
        if let grant = preview.grant {
            return .success(.init(grant: grant, remaining: preview.remaining!, replayed: true))
        }
        guard FlintProtectionPolicy.decideOpenCredit(creditsRemaining: preview.remaining,
            blockingContributors: []) == .allow(confirm: true) else { return .failure(.denied(.exhausted)) }
        // A delayed retry after expiry must not charge again or recreate access (PC-23).
        guard !operationID.isEmpty, operation(operationID) == nil else { return .failure(.denied(.staleOperation)) }
        guard openCredits.grants.count < FlintOpenCreditState.maximumGrants, coveringCaps.count <= 64 else {
            return .failure(.denied(.unavailable))
        }
        let duration = min(300, openCredits.resetRemaining)
        guard duration > 0 else { return .failure(.denied(.unavailable)) }
        let grant = FlintOpenCreditGrant(targetKey: target, operationID: operationID, grantedAt: now,
            deadline: now.wall.addingTimeInterval(duration), duration: duration, coveredCauses: Set(coveringCaps.keys))
        do { try register(grant) } catch { return .failure(.denied(.registrationFailed)) }
        openCredits.counters[target]!.used += 1
        openCredits.grants[target] = grant
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
