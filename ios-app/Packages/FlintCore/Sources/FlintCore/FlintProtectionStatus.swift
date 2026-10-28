import Foundation
#if canImport(WidgetKit)
import WidgetKit
#endif
#if canImport(Darwin)
import CoreFoundation
#endif

public enum FlintProtectionStatus {
    public static let storageKey = "flint.status.v1"
    public static let changedNotification = "com.flint.peakfocus.status.changed"
    public static let widgetKind = "FlintProtectionWidget"

    /// Injectable side effects let extensions and tests share the exact write-then-notify path.
    public struct PublicationEffects {
        public var reloadTimelines: (String) -> Void
        public var postDarwinNotification: (String) -> Void

        public init(reloadTimelines: @escaping (String) -> Void,
                    postDarwinNotification: @escaping (String) -> Void) {
            self.reloadTimelines = reloadTimelines
            self.postDarwinNotification = postDarwinNotification
        }

        public static var system: Self {
            Self(reloadTimelines: { kind in
                #if canImport(WidgetKit)
                WidgetCenter.shared.reloadTimelines(ofKind: kind)
                #endif
            }, postDarwinNotification: { name in
                #if canImport(Darwin)
                CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                    CFNotificationName(rawValue: name as CFString), nil, nil, true)
                #endif
            })
        }
    }

    public static func load(now: Date = Date(), from defaults: UserDefaults? = .flintGroup)
        -> FlintProtectionStatusSnapshot? {
        guard let data = defaults?.data(forKey: storageKey) else { return nil }
        return decode(data, now: now)
    }

    public static func decode(_ data: Data, now: Date) -> FlintProtectionStatusSnapshot? {
        guard data.count <= 64 * 1024 else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        guard let snapshot = try? decoder.decode(FlintProtectionStatusSnapshot.self, from: data),
              snapshot.version == 1, snapshot.state != .unavailable,
              snapshot.observedAt <= now,
              let validUntil = snapshot.validUntil,
              now <= validUntil, snapshot.observedAt <= validUntil else { return nil }
        return snapshot
    }

    /// One JSON blob, no read/modify/write of enforcement state, no synchronous IPC wait.
    public static func publish(_ snapshot: FlintProtectionStatusSnapshot,
                               to defaults: UserDefaults? = .flintGroup,
                               writer: Writer = .host,
                               effects: PublicationEffects = .system) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        let publication = snapshot.withWriter(writer.rawValue)
        guard let defaults, let data = try? encoder.encode(publication) else { return }
        defaults.set(data, forKey: storageKey)
        effects.reloadTimelines(widgetKind)
        effects.postDarwinNotification(changedNotification)
    }

    public enum Writer: String, Sendable {
        case host, monitor, shieldAction = "shield_action"
    }

    /// All keys here are supplied by the caller and exist only in-process. `.unknown` is
    /// appropriate for opaque category membership; it never invents an app/category match.
    public enum TargetScope: Equatable, Sendable {
        case only(Set<String>)
        case allExcept(Set<String>)
        case unknown

        func contains(_ targetKey: String) -> Bool {
            switch self {
            case .only(let keys): return keys.contains(targetKey)
            case .allExcept(let keys): return !keys.contains(targetKey)
            case .unknown: return false
            }
        }
    }

    public struct Contributors: Equatable, Sendable {
        public let state: FlintProtectionStatusSnapshot.State
        public let causes: [FlintProtectionStatusSnapshot.Cause]
        public let openCount: Int?
        public let openCap: Int?
        public let coverageUncertain: Bool
    }

    /// Read-only detail/shield query. An unavailable/stale counter stays nil; never mint a
    /// fresh daily allowance from an unreadable record. No target key appears in the result.
    public static func contributors(for targetKey: String,
                                    inputs: FlintProtectionStatusBuilder.Inputs,
                                    coverageByCauseID: [String: TargetScope],
                                    counterState: FlintOpenLimitState? = nil) -> Contributors {
        let snapshot = FlintProtectionStatusBuilder.make(inputs)
        guard snapshot.state != .unavailable else {
            return Contributors(state: .unavailable, causes: [], openCount: nil, openCap: nil,
                                coverageUncertain: true)
        }
        let causes = snapshot.causes.filter { coverageByCauseID[$0.id]?.contains(targetKey) == true }
        let coveringIDs = Set(causes.map(\.id))
        // A covering budget with unknown exhaustion may already block the target. It cannot
        // authorize an Open release/charge merely because the snapshot makes no claim.
        let unknownThreshold = inputs.rules.contains {
            coveringIDs.contains($0.id) && $0.kind == .timeLimit && $0.thresholdReached == nil
        }
        // An unresolved window cannot prove whether its rule is active. Only a caller-supplied
        // definitive exclusion can rule out that rule for this target.
        let unresolvedCoverage = inputs.rules.contains {
            guard $0.enabled && !$0.available else { return false }
            guard let scope = coverageByCauseID[$0.id], scope != .unknown else { return true }
            return scope.contains(targetKey)
        }
        let uncertain = unknownThreshold || unresolvedCoverage || snapshot.causes.contains {
            coverageByCauseID[$0.id] == nil || coverageByCauseID[$0.id] == .unknown
        }
        let openIDs = Set(causes.filter { $0.kind == .openLimit }.map(\.id))
        let openRules = inputs.rules.filter { openIDs.contains($0.id) }
        let cap = openRules.allSatisfy { $0.opensAllowed != nil }
            ? openRules.compactMap(\.opensAllowed).min() : nil
        var count: Int?
        if cap != nil, let counterState,
           inputs.quotaCalendar.isDate(counterState.dayStart, inSameDayAs: inputs.now) {
            let stored = counterState.counts[targetKey] ?? 0
            if stored >= 0 { count = stored }
        }
        let targetState: FlintProtectionStatusSnapshot.State
        if causes.isEmpty && !uncertain {
            targetState = .notProtected
        } else if (snapshot.state == .protected || snapshot.state == .notProtected)
                    && !causes.contains(where: \.enforcing) {
            targetState = uncertain ? .degraded : .notProtected
        } else {
            targetState = snapshot.state
        }
        return Contributors(state: targetState, causes: causes, openCount: count,
                            openCap: cap.map { max(0, $0) }, coverageUncertain: uncertain)
    }
}

extension FlintProtectionStatusSnapshot {
    func withWriter(_ writer: String) -> Self {
        Self(version: version, observedAt: observedAt, validUntil: validUntil, state: state,
             session: session, headline: headline, causes: causes, nextOccurrence: nextOccurrence,
             pass: pass, degradedReasons: degradedReasons, writer: writer)
    }
}
