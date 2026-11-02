import Foundation
import CryptoKit
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)

/// Future preferences and live children are committed together only after every affected gate.
/// A denied edit stays a UI draft; this type does not claim to queue future changes.
public final class FlintWebRestrictionEditor {
    public enum Outcome: Equatable, Sendable {
        case saved
        case protected(until: Date?)
        case waiting(seconds: TimeInterval)
        case confirmationRequired
        case unavailable
        case busy
        case invalid(String)
    }

    public struct Snapshot: Equatable {
        public var session: FlintActiveSession?
        public var rules: [FlintScheduleRule]
        public init(session: FlintActiveSession?, rules: [FlintScheduleRule]) {
            self.session = session; self.rules = rules
        }

        /// The caller holds the session transition. A projection gap is not idle when a
        /// durable generation remains live; installing generations cannot authorize edits.
        static func observing(session: FlintActiveSession?, recordPresent: Bool, rules: [FlintScheduleRule]?,
                              ledgerState: FlintLedgerState?, clock: FlintClock) -> Self? {
            guard let rules, let state = ledgerState,
                  !FlintSessionAuthority.recordIsUnavailable(session, recordPresent: recordPresent,
                    authority: state.sessionAuthority, authorityVersion: state.sessionAuthorityVersion, clock: clock),
                  state.sessionAuthority?.latest?.phase != .installing || state.sessionAuthority?.provesIdle == true else { return nil }
            if let session {
                guard session.contractVersion == nil || session.contractVersion == FlintActiveSession.currentContractVersion,
                      session.startedAt.timeIntervalSinceReferenceDate.isFinite,
                      session.endsAt.map({ $0.timeIntervalSinceReferenceDate.isFinite && $0 > session.startedAt }) ?? true,
                      session.plannedDuration.map({ $0.isFinite && $0 > 0 }) ?? true,
                      session.remainingCheckpoint?.isValid != false else { return nil }
            }
            return .init(session: FlintSessionAuthority.observedProjection(session,
                authority: state.sessionAuthority, clock: clock), rules: rules)
        }
    }

    private let read: () -> FlintWebRestrictionRead
    private let snapshot: () -> Snapshot?
    private let persist: (FlintWebRestrictions) -> Bool
    private let ledger: () -> FlintProtectionLedger?
    private let clock: FlintClock
    private let withTransitionBoundary: (() -> Void) -> Bool
    private let prepare: () -> Outcome?
    private let children: FlintWebRestrictionChildren?

    public convenience init(load: @escaping () -> FlintWebRestrictions?, snapshot: @escaping () -> Snapshot?,
                persist: @escaping (FlintWebRestrictions) -> Bool,
                ledger: @escaping () -> FlintProtectionLedger?, clock: FlintClock,
                withTransitionBoundary: @escaping (() -> Void) -> Bool = { body in body(); return true }) {
        self.init(read: { load().map(FlintWebRestrictionRead.available) ?? .unavailable },
                  snapshot: snapshot, persist: persist, ledger: ledger, clock: clock,
                  withTransitionBoundary: withTransitionBoundary)
    }

    init(read: @escaping () -> FlintWebRestrictionRead, snapshot: @escaping () -> Snapshot?,
         persist: @escaping (FlintWebRestrictions) -> Bool,
         ledger: @escaping () -> FlintProtectionLedger?, clock: FlintClock,
         children: FlintWebRestrictionChildren? = nil,
         prepare: @escaping () -> Outcome? = { nil },
         withTransitionBoundary: @escaping (() -> Void) -> Bool = { body in body(); return true }) {
        self.read = read; self.snapshot = snapshot; self.persist = persist
        self.ledger = ledger; self.clock = clock; self.withTransitionBoundary = withTransitionBoundary
        self.prepare = prepare
        self.children = children
    }

    public convenience init() {
        self.init(read: { FlintWebRestrictions.readForUI() }, snapshot: {
            guard let group = FlintGroupStore(),
                  FlintRuleArbiter(environment: .live).applyQueued(FlintScheduleRule.self),
                  let rules = FlintRuleReloadEnvironment.live.observedRules(FlintScheduleRule.self) else { return nil }
            return Snapshot.observing(session: group.loadActiveSession(), recordPresent: group.hasActiveSessionRecord(),
                rules: rules, ledgerState: try? FlintProtectionLedger.appGroup()?.peek().get(), clock: .system)
        }, persist: { $0.saveRecoveringUnavailableIntent() }, ledger: { FlintProtectionLedger.appGroup() }, clock: .system,
           children: .live,
           prepare: {
               switch FlintRuleStoreLock.initializationReadiness(environment: .live) {
               case .ready: return nil
               case .contended: return .busy
               case .failed: return .unavailable
               }
           },
           withTransitionBoundary: { body in
               FlintRuleStoreLock.withLock(environment: .live) { body(); return true } ?? false
           })
    }

    /// Only a final explicit user confirmation may commit matured Harder waits. The request id
    /// stays stable for retries of this draft; its scope is the canonical new-config fingerprint.
    public func save(_ candidate: FlintWebRestrictions, requestID: String, confirmed: Bool = false) -> Outcome {
        if let outcome = prepare() { return outcome }
        var result = Outcome.unavailable
        guard withTransitionBoundary({ result = saveLocked(candidate, requestID: requestID, confirmed: confirmed) }) else {
            return prepare() == .unavailable ? .unavailable : .busy
        }
        return result
    }

    private func saveLocked(_ candidate: FlintWebRestrictions, requestID: String, confirmed: Bool) -> Outcome {
        let previousRead = read()
        let previous: FlintWebRestrictions?
        switch previousRead {
        case let .available(config): previous = config
        case .unavailable: previous = nil
        case .busy: return .busy
        }
        guard let snapshot = snapshot() else { return .unavailable }
        if let message = candidate.validationMessage { return .invalid(message) }
        // An unknown saved zone/occurrence cannot authorize weakening an existing native child.
        guard snapshot.rules.allSatisfy(FlintWebRestrictionChildren.knows) else {
            return .unavailable
        }
        let now = clock.now()
        let live = FlintWebRestrictionChildren.causes(snapshot, clock: clock)
        if let children, !children.observe(snapshot, global: previous, clock: clock) { return .unavailable }
        let childRead = children?.read()
        var priorPolicies: [String] = []
        let affected: [FlintCauseState]
        if let children {
            affected = live.flatMap { cause -> [FlintCauseState] in
                let frozen = children.frozen(cause, fallback: previous)
                priorPolicies.append(cause.key + ":" + (frozen.map(Self.fingerprint) ?? "unavailable"))
                let owner = Snapshot(session: cause.scope == .session ? snapshot.session : nil,
                                     rules: cause.scope == .rule ? snapshot.rules.filter { $0.id == cause.ownerID } : [])
                return Self.affectedCauses(previous: frozen, candidate: candidate, snapshot: owner,
                                           now: now, calendar: clock.calendar(), clock: clock)
            }
        } else {
            affected = Self.affectedCauses(previous: previous, candidate: candidate, snapshot: snapshot,
                                          now: now, calendar: clock.calendar(), clock: clock)
            priorPolicies = [previous.map(Self.fingerprint) ?? "unavailable"]
        }
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
            let policy = try? snapshot.rules.compactMap { rule -> String? in
                guard let window = rule.occurrence(at: now, clock: clock), harder.contains(where: {
                    $0.causeID == FlintRuleArbiter.causeID(rule, window: window)
                }) else { return nil }
                return try rule.fingerprint(action: "web.current", includeName: false)
            }.sorted()
            guard let policy else { return .unavailable }
            let scope = priorPolicies.sorted() + [Self.fingerprint(candidate)] + policy
            let revisions = harder.map { "\($0.occurrenceID):\($0.revision)" }.sorted()
            let identity = SHA256.hash(data: Data((scope + revisions).joined(separator: "|").utf8))
                .map { String(format: "%02x", $0) }.joined()
            let operation = "web.edit.\(requestID).\(identity)"
            let result = ledger.transaction { state -> Outcome in
                let instant = FlintLedgerInstant(clock: self.clock)
                var remaining: TimeInterval = 0
                var refreshedConfirmation = false
                for cause in harder where !state.hasCommittedHarderWait(cause: cause.occurrenceID, operationID: operation) {
                    if let pending = state.harder[cause.occurrenceID]?.pending,
                       pending.operationID != operation, pending.kind == "web.edit", pending.scope == scope {
                        refreshedConfirmation = true
                    }
                    switch state.requestHarderWait(cause: cause.occurrenceID, kind: "web.edit", scope: scope,
                                                   operationID: operation, at: instant) {
                    case let .waiting(seconds): remaining = max(remaining, seconds)
                    case .matured: break
                    case .none: return .unavailable
                    }
                }
                if remaining > 0 { return .waiting(seconds: remaining) }
                guard confirmed, !refreshedConfirmation else { return .confirmationRequired }
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
        guard read() == previousRead, self.snapshot().map({ Self.samePolicy($0, snapshot) }) == true,
              children?.read() == childRead,
              affected.allSatisfy({ $0.end.map { self.clock.now() < $0 } ?? true }) else { return .unavailable }
        if children?.saveAuthorized(candidate, snapshot: snapshot, clock: clock) ?? persist(candidate) { return .saved }
        if !harder.isEmpty {
            let end = harder.compactMap(\.end).max().map { " Existing protection stays until \($0.formatted(date: .abbreviated, time: .shortened))." } ?? ""
            return .invalid("Change authorized but not saved — try again." + end)
        }
        return .unavailable
    }

    public static func weakensContent(_ old: FlintWebRestrictions, _ new: FlintWebRestrictions) -> Bool {
        old.filter != new.filter
            || !Set(old.normalizedBlockedDomains).isSubset(of: Set(new.normalizedBlockedDomains))
            || !Set(new.normalizedAllowedDomains).isSubset(of: Set(old.normalizedAllowedDomains))
            || (old.denyExplicitMedia && !new.denyExplicitMedia)
    }

    public static func affectedCauses(previous: FlintWebRestrictions?, candidate: FlintWebRestrictions,
                                     snapshot: Snapshot, now: Date, calendar: Calendar,
                                     clock: FlintClock? = nil) -> [FlintCauseState] {
        // Unknown prior intent cannot prove a strengthening. Re-establishment is a weakening
        // against every live web-relevant owner, even when this draft opts a scope in.
        let content = previous.map { weakensContent($0, candidate) } ?? true
        var causes: [FlintCauseState] = []
        // A session's eligibility and end come from logical time when a clock is given: a
        // same-boot wall jump must not make a live session's frozen policy freely editable (§9).
        if previous?.enabled ?? true, !candidate.enabled || content, let session = snapshot.session,
           session.isEnforcing(at: now, clock: clock) {
            let end = clock.flatMap(session.stableLogicalEnd) ?? session.endsAt
            causes.append(.init(causeID: session.id, kind: .session, tier: session.breakLevel,
                                isActive: true, end: end, legacyRights: previous != nil && session.contractVersion == nil))
        }
        if previous?.applyDuringSchedules ?? true, !candidate.applyDuringSchedules || content {
            for rule in snapshot.rules where rule.enabled && FlintWebRestrictionChildren.knows(rule) {
                let resolved = clock ?? .system
                guard let window = rule.occurrence(at: now, clock: resolved) else { continue }
                let identity = FlintRuleArbiter.causeID(rule, window: window)
                causes.append(.init(causeID: identity, occurrenceID: identity, revision: rule.revision,
                                    kind: rule.kind, tier: rule.breakLevel, isActive: true,
                                    end: FlintRuleOccurrenceClock.display(window, for: rule, clock: resolved).end,
                                    legacyRights: previous != nil && (rule.protection?.legacyRightsUntil.map {
                                        FlintRuleOccurrenceClock.date(for: rule, clock: resolved) < $0
                                    } ?? false)))
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

    private static func samePolicy(_ lhs: Snapshot, _ rhs: Snapshot) -> Bool {
        func policy(_ snapshot: Snapshot) -> Snapshot {
            var copy = snapshot
            for index in copy.rules.indices { copy.rules[index].protection?.timeAnchor = nil }
            return copy
        }
        return policy(lhs) == policy(rhs)
    }
}
#endif
