import Foundation
import CryptoKit
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
import FamilyControls
import DeviceActivity

/// Commit-time authority for every rule mutation. Host editors and monitor/foreground queue
/// recovery share this lock, strict domain reader and revision checks (ADR-012 §2/8).
final class FlintRuleArbiter {
    struct Mutation<Rule: FlintGatedRule> {
        let id: String
        var proposed: Rule?
        let action: FlintQueuedRuleChange.Action
        let expectedRevision: Int
        var creating = false
    }
    private let env: FlintRuleReloadEnvironment
    init(environment: FlintRuleReloadEnvironment) { env = environment }

    func upsert<Rule: FlintGatedRule>(_ rule: Rule, confirmation: FlintRuleConfirmation? = nil,
                                     creating: Bool = false) -> FlintRuleMutationResult {
        mutate([Mutation(id: rule.id, proposed: rule, action: .edit,
                         expectedRevision: rule.revision, creating: creating)], confirmation: confirmation)
    }

    func delete<Rule: FlintGatedRule>(_ type: Rule.Type, id: String, revision: Int?,
                                     confirmation: FlintRuleConfirmation?) -> FlintRuleMutationResult {
        withLock {
            guard let all = self.load(type) else { return .unavailable }
            guard let old = all.first(where: { $0.id == id }) else { return .staleRevision }
            return self.mutate([Mutation<Rule>(id: id, proposed: nil, action: .delete,
                expectedRevision: revision ?? old.revision)], confirmation: confirmation)
        }
    }

    func setEnabled<Rule: FlintGatedRule>(_ type: Rule.Type, id: String, enabled: Bool, revision: Int?,
                                         confirmation: FlintRuleConfirmation?) -> FlintRuleMutationResult {
        withLock {
            guard let all = self.load(type) else { return .unavailable }
            guard var rule = all.first(where: { $0.id == id }) else { return .staleRevision }
            let expected = revision ?? rule.revision
            rule.enabled = enabled
            return self.mutate([Mutation(id: id, proposed: rule, action: enabled ? .edit : .turnOff,
                expectedRevision: expected)], confirmation: confirmation)
        }
    }

    func mutate<Rule: FlintGatedRule>(_ mutations: [Mutation<Rule>],
                                      confirmation: FlintRuleConfirmation?, context: String = "") -> FlintRuleMutationResult {
        withLock {
            guard !mutations.isEmpty, Set(mutations.map(\.id)).count == mutations.count,
                  var all = self.load(Rule.self) else { return .unavailable }
            let now = self.env.clock.now()
            var decisions: [FlintProtectionDecision] = []
            var causes: [FlintCauseState] = []
            var scopes: [String] = [context]
            var raisesTier = false
            for mutation in mutations {
                let old = all.first { $0.id == mutation.id }
                if let old {
                    guard !mutation.creating, old.revision == mutation.expectedRevision else { return .staleRevision }
                    guard old.validationIssue == nil else { return .unavailable }
                } else if mutation.action == .delete || (mutation.expectedRevision > 1 && !mutation.creating) {
                    return .staleRevision
                }
                if let proposed = mutation.proposed {
                    guard proposed.id == mutation.id else { return .invalid("Rule identity changed.") }
                    if let issue = proposed.validationIssue { return .invalid(issue) }
                    if proposed.enabled, let issue = self.env.validateSelection(proposed.selection,
                        Rule.domain == .openLimits ? .openLimit : (proposed.allowList ? .allowList : .blockList)) {
                        return .invalid(issue.message)
                    }
                    raisesTier = raisesTier || FlintProtectionPolicy.rank(proposed.breakLevel) > FlintProtectionPolicy.rank(old?.breakLevel ?? .easy)
                }
                let source = old ?? mutation.proposed!
                let occurrence = old?.occurrence(at: now)
                let cause = FlintCauseState(causeID: Self.causeID(source, window: occurrence),
                    occurrenceID: occurrence?.identity, revision: old?.revision ?? 0, kind: source.kind,
                    tier: source.breakLevel, isActive: occurrence != nil, end: occurrence?.end,
                    legacyRights: source.protection?.legacyRightsUntil.map { now < $0 } ?? false)
                causes.append(cause)
                let decision: FlintProtectionDecision
                if mutation.action == .delete { decision = FlintProtectionPolicy.decide(.delete, for: cause) }
                else if mutation.action == .turnOff || (old?.enabled == true && mutation.proposed?.enabled == false) {
                    decision = FlintProtectionPolicy.decide(.toggleOff, for: cause)
                } else if let old, let proposed = mutation.proposed {
                    guard let impact = try? proposed.impact(comparedTo: old, at: now) else { return .unavailable }
                    let action: FlintProtectionAction = impact == .cosmetic ? .editCosmetic : (impact == .strengthen ? .editStrengthen : .editWeaken)
                    decision = FlintProtectionPolicy.decide(action, for: cause)
                } else { decision = .allow(confirm: mutation.proposed?.breakLevel == .hardcore) }
                decisions.append(decision)
                guard let fingerprint = try? sourceFingerprint(mutation) else { return .unavailable }
                scopes.append("\(mutation.id):\(old?.revision ?? 0):\(fingerprint)")
            }
            let digest = SHA256.hash(data: Data(scopes.sorted().joined(separator: "|").utf8))
                .map { String(format: "%02x", $0) }.joined()
            let ticket = FlintRuleConfirmation(revision: causes.map(\.revision).max() ?? 0,
                fingerprint: digest, confirmsTierIncrease: raisesTier)
            if let confirmation {
                guard confirmation.revision == ticket.revision, confirmation.fingerprint == ticket.fingerprint else { return .staleRevision }
            }
            if let index = decisions.firstIndex(where: { if case .deny = $0 { return true }; return false }),
               case let .deny(reason) = decisions[index] {
                let cause = causes[index]
                return .denied(reason, cause: mutations[index].id, tier: cause.tier, end: cause.end)
            }
            if raisesTier, confirmation?.confirmsTierIncrease != true {
                return .confirmationRequired(ticket, raisesTier: true)
            }
            let deferredUntil = decisions.compactMap { if case let .deferUntil(end) = $0 { return end }; return nil }.max()
            if let end = deferredUntil {
                guard confirmation != nil else { return .deferRequired(ticket, until: end) }
                // The whole batch is future-only. Store every future snapshot in the same
                // domain write, retaining each current rule's effective fields/registration.
                let batchID = UUID().uuidString
                for mutation in mutations {
                    if !all.contains(where: { $0.id == mutation.id }), var placeholder = mutation.proposed {
                        placeholder.enabled = false
                        all.append(placeholder)
                    }
                    guard let index = all.firstIndex(where: { $0.id == mutation.id }) else { return .unavailable }
                    var current = all[index]
                    guard var metadata = current.protection,
                          let window = current.occurrence(at: now) ?? causes.compactMap({ cause -> FlintRuleWindow? in
                              guard let end = cause.end else { return nil }; return .init(start: now, end: end)
                          }).first else { return .unavailable }
                    metadata.revision += 1
                    var future = mutation.proposed
                    if future != nil {
                        var next = metadata
                        next.revision += 1; next.activityRevision = next.revision
                        next.usesLegacyActivityName = false; next.legacyRightsUntil = nil
                        next.queuedChange = nil; next.occurrence = nil
                        future!.protection = next
                    }
                    let snapshot = future.flatMap { try? JSONEncoder().encode($0) }
                    if future != nil && snapshot == nil { return .unavailable }
                    metadata.queuedChange = .init(action: mutation.action, baseRevision: metadata.revision,
                        window: .init(start: window.start, end: end), snapshot: snapshot,
                        fingerprint: (try? sourceFingerprint(mutation)) ?? "", batchID: batchID)
                    current.protection = metadata
                    all[index] = current
                }
                guard self.save(all) else { return .unavailable }
                self.notify()
                return .queued(until: end)
            }
            let harder = zip(causes, decisions).compactMap { cause, decision -> FlintCauseState? in
                if case .wait = decision { return cause }; return nil
            }
            if !harder.isEmpty {
                let result = self.harderGate(harder, scope: scopes, operationID: digest, confirmation: false)
                switch result {
                case .applied: break
                case let .waiting(_, remaining, required):
                    if remaining > 0 || confirmation == nil { return .waiting(ticket, remaining: remaining, required: required) }
                case .confirmationRequired:
                    if confirmation == nil { return .waiting(ticket, remaining: 0,
                        required: decisions.compactMap { if case let .wait(s) = $0 { return s }; return nil }.max() ?? 60) }
                default: return result
                }
            } else if confirmation == nil, decisions.contains(.allow(confirm: true)) {
                return .confirmationRequired(ticket, raisesTier: raisesTier)
            }
            var prepared: [Rule] = []
            var previous: [Rule] = []
            for mutation in mutations {
                let old = all.first { $0.id == mutation.id }
                if let old { previous.append(old) }
                guard var next = mutation.proposed else {
                    all.removeAll { $0.id == mutation.id }; continue
                }
                var metadata = old?.protection ?? next.protection ?? .init()
                let impact = old.flatMap { try? next.impact(comparedTo: $0, at: now) }
                metadata.revision = (old?.revision ?? 0) + 1
                if impact != .cosmetic || old?.enabled != next.enabled {
                    metadata.activityRevision = metadata.revision; metadata.usesLegacyActivityName = false
                }
                metadata.contractVersion = 12
                metadata.timeZoneID = next.protection?.timeZoneID ?? metadata.timeZoneID
                metadata.queuedChange = nil
                metadata.legacyRightsUntil = old?.protection?.legacyRightsUntil
                // Preserve the occurrence identity (and Harder count) across rename/strengthen.
                if let window = old?.occurrence(at: now), next.enabled {
                    metadata.occurrence = .init(start: window.start,
                        end: max(window.end, next.templateOccurrence(at: now)?.end ?? window.end))
                } else { metadata.occurrence = next.templateOccurrence(at: now) }
                next.protection = metadata
                guard self.prepare(next, replacing: old) else {
                    self.rollback(prepared, previous: previous)
                    return .unavailable
                }
                prepared.append(next)
                if let index = all.firstIndex(where: { $0.id == next.id }) { all[index] = next }
                else { all.append(next) }
            }
            if !harder.isEmpty {
                guard self.harderGate(harder, scope: scopes, operationID: digest, confirmation: true) == .applied else {
                    self.rollback(prepared, previous: previous); return .unavailable
                }
            }
            guard self.save(all) else { self.rollback(prepared, previous: previous); return .unavailable }
            self.finish(prepared, replacing: previous)
            self.notify()
            return .applied
        }
    }

    /// Called before native reconciliation AND by monitor start/end callbacks. A queued batch
    /// crosses its old logical end before today's adjacent occurrence can be materialized.
    @discardableResult
    func applyQueued<Rule: FlintGatedRule>(_ type: Rule.Type) -> Bool {
        FlintRuleStoreLock.withLock(directory: env.lockDirectory) {
            guard var all = self.load(type) else { return false }
            let now = self.env.clock.now()
            let due = all.filter { ($0.protection?.queuedChange?.window.end ?? .distantFuture) <= now }
            guard !due.isEmpty else { return true }
            var prepared: [Rule] = []
            for old in due {
                guard let queue = old.protection?.queuedChange, queue.baseRevision == old.revision else { return false }
                if queue.action == .delete { all.removeAll { $0.id == old.id }; continue }
                guard let data = queue.snapshot, var next = try? JSONDecoder().decode(Rule.self, from: data),
                      next.id == old.id, next.revision == old.revision + 1, next.validationIssue == nil,
                      next.protection?.queuedChange == nil,
                      (try? next.fingerprint(action: queue.action.rawValue)) == queue.fingerprint else { return false }
                next.protection?.occurrence = next.templateOccurrence(at: now)
                guard self.prepare(next, replacing: old) else { self.rollback(prepared, previous: due); return false }
                prepared.append(next)
                guard let index = all.firstIndex(where: { $0.id == old.id }) else { return false }
                all[index] = next
            }
            guard self.save(all) else { self.rollback(prepared, previous: due); return false }
            self.finish(prepared, replacing: due)
            self.notify()
            return true
        } ?? false
    }

    @discardableResult
    func cancel<Rule: FlintGatedRule>(_ type: Rule.Type, id: String) -> Bool {
        FlintRuleStoreLock.withLock(directory: env.lockDirectory) {
            guard let all = self.load(type), let rule = all.first(where: { $0.id == id }),
                  let occurrence = rule.occurrence(at: self.env.clock.now()), let ledger = self.env.ledger() else { return false }
            return (try? ledger.transaction { state in
                state.cancelHarderWait(cause: Self.causeID(rule, window: occurrence)); return true
            }.get()) ?? false
        } ?? false
    }

    private func harderGate(_ causes: [FlintCauseState], scope: [String], operationID: String,
                            confirmation: Bool) -> FlintRuleMutationResult {
        guard let ledger = env.ledger() else { return .unavailable }
        let instant = FlintLedgerInstant(clock: env.clock)
        return (try? ledger.transaction { state -> FlintRuleMutationResult in
            var remaining: TimeInterval = 0
            var required: TimeInterval = 0
            var allCommitted = true
            for cause in causes {
                if state.hasCommittedHarderWait(cause: cause.causeID, operationID: operationID) { continue }
                allCommitted = false
                required = max(required, FlintProtectionPolicy.harderWait(forCount: state.harder[cause.causeID]?.committedCount ?? 0))
                switch state.requestHarderWait(cause: cause.causeID, kind: "rule.mutation", scope: scope.sorted(),
                    operationID: operationID, at: instant) {
                case let .waiting(seconds): remaining = max(remaining, seconds)
                case .matured: break
                case .none: return .unavailable
                }
            }
            let placeholder = FlintRuleConfirmation(revision: 0, fingerprint: operationID)
            if allCommitted { return .applied }
            if remaining > 0 { return .waiting(placeholder, remaining: remaining, required: required) }
            guard confirmation else { return .waiting(placeholder, remaining: 0, required: required) }
            for cause in causes {
                if state.hasCommittedHarderWait(cause: cause.causeID, operationID: operationID) { continue }
                switch state.commitHarderWait(cause: cause.causeID, operationID: operationID, at: instant) {
                case .committed, .alreadyCommitted: break
                case .nothingPending, .notMatured: return .unavailable
                }
            }
            return .applied
        }.get()) ?? .unavailable
    }

    private func sourceFingerprint<Rule: FlintGatedRule>(_ mutation: Mutation<Rule>) throws -> String {
        if let proposed = mutation.proposed { return try proposed.fingerprint(action: mutation.action.rawValue) }
        return "delete:\(mutation.id)"
    }

    private func prepare<Rule: FlintGatedRule>(_ next: Rule, replacing old: Rule?) -> Bool {
        guard next.enabled else { return true }
        if next.activityName == old?.activityName { return true } // cosmetic: no restart/counter reset
        if let old = old as? FlintLimitRule, old.enabled, old.occurrence(at: env.clock.now()) != nil {
            if #available(iOS 17.4, *) {} else { return false } // cannot reset already-used daily usage
        }
        let request = next.registration()
        do {
            try env.center.startMonitoring(.init(request.name), during: request.schedule, events: request.events)
            guard env.center.activities.contains(.init(request.name)), env.center.schedule(for: .init(request.name)) != nil else {
                env.center.stopMonitoring([.init(request.name)]); return false
            }
            return true
        } catch { return false }
    }

    private func rollback<Rule: FlintGatedRule>(_ prepared: [Rule], previous: [Rule]) {
        let oldNames = Set(previous.map(\.activityName))
        let names = prepared.filter { $0.enabled && !oldNames.contains($0.activityName) }.map { DeviceActivityName($0.activityName) }
        if !names.isEmpty { env.center.stopMonitoring(names) }
    }

    private func finish<Rule: FlintGatedRule>(_ prepared: [Rule], replacing old: [Rule]) {
        for rule in prepared where rule.enabled {
            if let schedule = rule as? FlintScheduleRule, schedule.occurrence(at: env.clock.now()) != nil {
                env.applyRuleShield(schedule)
            } else if let limit = rule as? FlintLimitRule,
                      limit.thresholdMinutes == 0 || old.compactMap({ $0 as? FlintLimitRule })
                        .contains(where: { $0.id == rule.id && env.timeLimitIsShielded($0) }) {
                // Retain an exhausted shield while the new revision includes today's usage.
                // Renaming a limit below quota must not itself exhaust its allowance.
                env.applyLimitShield(limit)
            } else if let open = rule as? FlintOpenLimitRule { env.applyOpenShield(open) }
        }
        for previous in old {
            let replacement = prepared.first { $0.id == previous.id }
            if replacement?.enabled != true { env.clearStore(previous.storeName) }
            if replacement?.activityName != previous.activityName || replacement?.enabled != true {
                env.center.stopMonitoring([.init(previous.activityName)])
            }
        }
    }

    private func save<Rule: FlintGatedRule>(_ all: [Rule]) -> Bool {
        env.acceptSave() && FlintDesiredRules.save(all, key: Rule.domain, to: env.defaults)
    }
    private func load<Rule: FlintGatedRule>(_ type: Rule.Type) -> [Rule]? {
        FlintDesiredRules.load(type, key: Rule.domain, from: env.defaults, at: env.clock.now())
    }
    private func withLock(_ body: () -> FlintRuleMutationResult) -> FlintRuleMutationResult {
        FlintRuleStoreLock.withLock(directory: env.lockDirectory, body) ?? .unavailable
    }
    private func notify() { NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil) }
    static func causeID<Rule: FlintGatedRule>(_ rule: Rule, window: FlintRuleWindow?) -> String {
        "\(Rule.domain.rawValue):\(rule.id)@\(window?.identity ?? "inactive")"
    }
}
#endif
