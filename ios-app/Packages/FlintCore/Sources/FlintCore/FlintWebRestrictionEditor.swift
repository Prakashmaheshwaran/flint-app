import Foundation
import CryptoKit
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)

/// The global preference is live, but weakening it must respect every affected commitment.
/// A denied edit stays a UI draft; this type does not claim to queue future changes.
public final class FlintWebRestrictionEditor {
    public enum Outcome: Equatable {
        case saved
        case protected(until: Date?)
        case waiting(seconds: TimeInterval)
        case confirmationRequired
        case unavailable
        case invalid(String)
    }

    public struct Snapshot: Equatable {
        public var session: FlintActiveSession?
        public var rules: [FlintScheduleRule]
        public init(session: FlintActiveSession?, rules: [FlintScheduleRule]) {
            self.session = session; self.rules = rules
        }
    }

    private let load: () -> FlintWebRestrictions?
    private let snapshot: () -> Snapshot?
    private let persist: (FlintWebRestrictions) -> Bool
    private let ledger: () -> FlintProtectionLedger?
    private let clock: FlintClock

    public init(load: @escaping () -> FlintWebRestrictions?, snapshot: @escaping () -> Snapshot?,
                persist: @escaping (FlintWebRestrictions) -> Bool,
                ledger: @escaping () -> FlintProtectionLedger?, clock: FlintClock) {
        self.load = load; self.snapshot = snapshot; self.persist = persist
        self.ledger = ledger; self.clock = clock
    }

    public convenience init() {
        self.init(load: { .loadForEnforcement() }, snapshot: {
            guard let group = FlintGroupStore(),
                  let rules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: .flintGroup) else { return nil }
            let active = group.loadActiveSession()
            guard active != nil || !group.hasActiveSessionRecord() else { return nil }
            return Snapshot(session: active, rules: rules)
        }, persist: { $0.save() }, ledger: { FlintProtectionLedger.appGroup() }, clock: .system)
    }

    /// Only a final explicit user confirmation may commit matured Harder waits. The request id
    /// stays stable for retries of this draft; its scope is the canonical new-config fingerprint.
    public func save(_ candidate: FlintWebRestrictions, requestID: String, confirmed: Bool = false) -> Outcome {
        guard let previous = load(), let snapshot = snapshot() else { return .unavailable }
        if let message = candidate.validationMessage { return .invalid(message) }
        let now = clock.now()
        let affected = Self.affectedCauses(previous: previous, candidate: candidate, snapshot: snapshot,
                                           now: now, calendar: clock.calendar())
        let decisions = affected.map { cause -> FlintProtectionDecision in
            let off = cause.isRuleBacked ? !candidate.applyDuringSchedules : !candidate.enabled
            return FlintProtectionPolicy.decide(off ? .toggleOff : .editWeaken, for: cause)
        }
        var protectedEnds: [Date] = []
        var blockedWithoutEnd = false
        for decision in decisions {
            switch decision {
            case let .deferUntil(end): protectedEnds.append(end)
            case .deny: blockedWithoutEnd = true
            default: break
            }
        }
        if blockedWithoutEnd || !protectedEnds.isEmpty {
            return .protected(until: blockedWithoutEnd ? nil : protectedEnds.max())
        }
        let harder = affected.filter { $0.tier == .harder }
        if !harder.isEmpty {
            guard let ledger = ledger() else { return .unavailable }
            let scope = [Self.fingerprint(candidate)]
            let operation = "web.edit.\(requestID).\(scope[0])"
            let result = ledger.transaction { state -> Outcome in
                let instant = FlintLedgerInstant(clock: self.clock)
                var remaining: TimeInterval = 0
                for cause in harder where !state.hasCommittedHarderWait(cause: cause.occurrenceID, operationID: operation) {
                    switch state.requestHarderWait(cause: cause.occurrenceID, kind: "web.edit", scope: scope,
                                                   operationID: operation, at: instant) {
                    case let .waiting(seconds): remaining = max(remaining, seconds)
                    case .matured: break
                    case .none: return .unavailable
                    }
                }
                if remaining > 0 { return .waiting(seconds: remaining) }
                guard confirmed else { return .confirmationRequired }
                for cause in harder {
                    switch state.commitHarderWait(cause: cause.occurrenceID, operationID: operation, at: instant) {
                    case .committed, .alreadyCommitted: break
                    case .notMatured, .nothingPending: return .unavailable
                    }
                }
                return .saved
            }
            guard case let .success(outcome) = result else { return .unavailable }
            guard outcome == .saved else { return outcome }
        }
        guard load() == previous, self.snapshot() == snapshot else { return .unavailable }
        return persist(candidate) ? .saved : .unavailable
    }

    public static func weakensContent(_ old: FlintWebRestrictions, _ new: FlintWebRestrictions) -> Bool {
        old.filter != new.filter
            || !Set(old.normalizedBlockedDomains).isSubset(of: Set(new.normalizedBlockedDomains))
            || !Set(new.normalizedAllowedDomains).isSubset(of: Set(old.normalizedAllowedDomains))
            || (old.denyExplicitMedia && !new.denyExplicitMedia)
    }

    public static func affectedCauses(previous: FlintWebRestrictions, candidate: FlintWebRestrictions,
                                     snapshot: Snapshot, now: Date, calendar: Calendar) -> [FlintCauseState] {
        let content = weakensContent(previous, candidate)
        var causes: [FlintCauseState] = []
        if previous.enabled, !candidate.enabled || content, let session = snapshot.session, !session.isExpired(at: now) {
            causes.append(.init(causeID: session.id, kind: .session, tier: session.breakLevel,
                                isActive: true, end: session.endsAt, legacyRights: session.contractVersion == nil))
        }
        if previous.applyDuringSchedules, !candidate.applyDuringSchedules || content {
            for rule in snapshot.rules where rule.enabled {
                guard let window = FlintScheduleWindow.resolve(rule.schedule, at: now, calendar: calendar)?.current else { continue }
                let end = window.end
                let occurrence = "\(rule.monitorName).\(Int(end.timeIntervalSince1970))"
                causes.append(.init(causeID: rule.monitorName, occurrenceID: occurrence,
                                    kind: rule.id.hasPrefix("sleep.") ? .sleep : .recurringSchedule,
                                    tier: rule.breakLevel, isActive: true, end: end))
            }
        }
        return causes
    }

    static func fingerprint(_ config: FlintWebRestrictions) -> String {
        var canonical = config
        canonical.allowedDomains = config.normalizedAllowedDomains.sorted()
        canonical.blockedDomains = config.normalizedBlockedDomains.sorted()
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        let data = (try? encoder.encode(canonical)) ?? Data()
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
#endif
