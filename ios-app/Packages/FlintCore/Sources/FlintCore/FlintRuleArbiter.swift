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
                                     creating: Bool = false, origin: FlintRuleMutationOrigin = .interactive) -> FlintRuleMutationResult {
        mutate([Mutation(id: rule.id, proposed: rule, action: .edit,
                         expectedRevision: rule.revision, creating: creating)], confirmation: confirmation, origin: origin)
    }

    func delete<Rule: FlintGatedRule>(_ type: Rule.Type, id: String, revision: Int,
                                     confirmation: FlintRuleConfirmation?, origin: FlintRuleMutationOrigin = .interactive) -> FlintRuleMutationResult {
        withLock {
            guard let all = self.load(type) else { return .unavailable }
            guard all.contains(where: { $0.id == id }) else { return .staleRevision }
            return self.mutate([Mutation<Rule>(id: id, proposed: nil, action: .delete,
                expectedRevision: revision)], confirmation: confirmation, origin: origin)
        }
    }

    func setEnabled<Rule: FlintGatedRule>(_ type: Rule.Type, id: String, enabled: Bool, revision: Int,
                                         confirmation: FlintRuleConfirmation?, origin: FlintRuleMutationOrigin = .interactive) -> FlintRuleMutationResult {
        withLock {
            guard let all = self.load(type) else { return .unavailable }
            guard var rule = all.first(where: { $0.id == id }) else { return .staleRevision }
            rule.enabled = enabled
            return self.mutate([Mutation(id: id, proposed: rule, action: enabled ? .edit : .turnOff,
                expectedRevision: revision)], confirmation: confirmation, origin: origin)
        }
    }

    func mutate<Rule: FlintGatedRule>(_ mutations: [Mutation<Rule>],
                                      confirmation: FlintRuleConfirmation?, context: String = "",
                                      origin: FlintRuleMutationOrigin = .interactive) -> FlintRuleMutationResult {
        withLock {
            guard !mutations.isEmpty, Set(mutations.map(\.id)).count == mutations.count,
                  self.applyQueued(Rule.self),
                  var all = self.load(Rule.self, freezing: true) else { return .unavailable }
            let original = all
            let now = self.env.clock.now()
            var decisions: [FlintProtectionDecision] = []
            var causes: [FlintCauseState] = []
            var scopes: [String] = [context]
            var waitScopes: [String] = [context]
            var previews: [FlintRuleChangePreview] = []
            var nextOccurrenceEnds: [String: Date] = [:]
            var raisesTier = false
            for mutation in mutations {
                let old = all.first { $0.id == mutation.id }
                if let old {
                    guard !mutation.creating, old.revision == mutation.expectedRevision else { return .staleRevision }
                    guard old.validationIssue == nil else { return .unavailable }
                    if old.protection?.queuedChange != nil {
                        guard mutation.action == .edit, let proposed = mutation.proposed,
                              let impact = try? proposed.impact(comparedTo: old, at: now, clock: self.env.clock),
                              impact == .strengthen || (impact == .cosmetic && proposed.name != old.name) else {
                            return .invalid("This rule already has a queued change. Cancel it before saving another change.")
                        }
                    }
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
                    raisesTier = raisesTier || (old.map {
                        FlintProtectionPolicy.rank(proposed.breakLevel) > FlintProtectionPolicy.rank($0.breakLevel)
                    } ?? (proposed.breakLevel == .hardcore))
                }
                let source = old ?? mutation.proposed!
                let occurrence = old?.occurrence(at: now, clock: self.env.clock)
                let displayEnd = occurrence.map { FlintRuleOccurrenceClock.display($0, for: source, clock: self.env.clock).end }
                // A new draft has no committed incarnation yet (Sleep rebuilds it for each
                // request). Mint that only on commit, after a stable creation preview.
                let identity = old.map { Self.causeID($0, window: occurrence) }
                    ?? "\(Rule.domain.rawValue):\(mutation.id)@new"
                let cause = FlintCauseState(causeID: identity,
                    occurrenceID: occurrence?.identity, revision: old?.revision ?? 0, kind: source.kind,
                    tier: source.breakLevel, isActive: occurrence != nil, end: displayEnd,
                    legacyRights: source.protection?.legacyRightsUntil.map {
                        FlintRuleOccurrenceClock.date(for: source, clock: self.env.clock) < $0
                    } ?? false)
                causes.append(cause)
                let displayed = mutation.proposed ?? source
                previews.append(.init(id: displayed.id, name: displayed.name, action: mutation.action,
                    tier: displayed.breakLevel, applicationCount: displayed.selection.applicationTokens.count,
                    categoryCount: displayed.selection.categoryTokens.count,
                    webDomainCount: displayed.selection.webDomainTokens.count, allowList: displayed.allowList,
                    budget: displayed.budget, isTimeLimit: displayed.kind == .timeLimit,
                    timeZoneID: displayed.zone.identifier,
                    window: (displayed.previewWindow(at: now, clock: self.env.clock) ?? occurrence).map {
                        FlintRuleOccurrenceClock.display($0, for: source, clock: self.env.clock)
                    }))
                let decision: FlintProtectionDecision
                if mutation.action == .delete { decision = FlintProtectionPolicy.decide(.delete, for: cause) }
                else if mutation.action == .turnOff || (old?.enabled == true && mutation.proposed?.enabled == false) {
                    decision = FlintProtectionPolicy.decide(.toggleOff, for: cause)
                } else if old?.enabled == false, mutation.proposed?.enabled == true,
                          mutation.proposed?.breakLevel == .hardcore {
                    // Enabling a saved Hardcore template begins a new commitment and must
                    // disclose its finite window even when the tier itself did not change.
                    decision = .allow(confirm: true)
                } else if let old, let proposed = mutation.proposed {
                    guard let impact = try? proposed.impact(comparedTo: old, at: now, clock: self.env.clock) else { return .unavailable }
                    let action: FlintProtectionAction = impact == .cosmetic ? .editCosmetic : (impact == .strengthen ? .editStrengthen : .editWeaken)
                    decision = FlintProtectionPolicy.decide(action, for: cause)
                    if impact == .weaken, proposed.enabled, occurrence != nil,
                       let before = old as? FlintLimitRule, let after = proposed as? FlintLimitRule,
                       before.thresholdMinutes != after.thresholdMinutes || before.selection != after.selection
                        || before.protection?.timeZoneID != after.protection?.timeZoneID,
                       self.env.timeLimitIsShielded(before, now) {
                        // DeviceActivity cannot synchronously report the used minutes for a
                        // revised threshold/target set. Keep today's exhausted snapshot and
                        // offer a future revision instead of claiming an inaccurate release.
                        if case .deferUntil = decision {} else { nextOccurrenceEnds[mutation.id] = displayEnd }
                    }
                } else { decision = .allow(confirm: mutation.proposed?.breakLevel == .hardcore) }
                decisions.append(decision)
                guard let fingerprint = try? sourceFingerprint(mutation) else { return .unavailable }
                let activation = occurrence == nil ? mutation.proposed?.previewWindow(at: now, clock: self.env.clock) : nil
                scopes.append("\(cause.causeID):\(old?.revision ?? 0):\(occurrence?.end.timeIntervalSince1970 ?? 0):\(activation?.start.timeIntervalSince1970 ?? 0):\(activation?.end.timeIntervalSince1970 ?? 0):\(fingerprint)")
                guard let before = try? source.fingerprint(action: "current", includeName: false) else { return .unavailable }
                let after: String
                if let proposed = mutation.proposed {
                    guard let value = try? proposed.fingerprint(action: mutation.action.rawValue, includeName: false) else { return .unavailable }
                    after = value
                } else { after = "delete" }
                // Cosmetic revisions invalidate an old confirmation, but do not restart a
                // persisted wait for the same exact protection change (ADR-012 §2/4).
                waitScopes.append("\(cause.causeID):\(old?.activityName ?? "new"):\(occurrence?.end.timeIntervalSince1970 ?? 0):\(before):\(after)")
            }
            let policyDeferrals = decisions.compactMap { if case let .deferUntil(end) = $0 { return end }; return nil }
            var deferredEnds = nextOccurrenceEnds
            if !policyDeferrals.isEmpty || !nextOccurrenceEnds.isEmpty {
                for index in decisions.indices {
                    switch decisions[index] {
                    case let .deferUntil(end): deferredEnds[mutations[index].id] = end
                    case .wait: deferredEnds[mutations[index].id] = causes[index].end
                    default: break // Easy and inactive members apply in the atomic acceptance.
                    }
                }
            }
            for index in previews.indices {
                previews[index].deferredUntil = deferredEnds[mutations[index].id]
                previews[index].offersEarlierHarderEdit = deferredEnds[mutations[index].id] != nil && causes[index].tier == .harder
            }
            let digest = SHA256.hash(data: Data(scopes.sorted().joined(separator: "|").utf8))
                .map { String(format: "%02x", $0) }.joined()
            let ticket = FlintRuleConfirmation(revision: causes.map(\.revision).max() ?? 0,
                fingerprint: digest, confirmsTierIncrease: raisesTier, preview: previews)
            if let confirmation {
                guard confirmation.revision == ticket.revision, confirmation.fingerprint == ticket.fingerprint else { return .staleRevision }
                guard origin == .interactive else { return .confirmationRequired(ticket, raisesTier: raisesTier) }
            }
            if let index = decisions.firstIndex(where: { if case .deny = $0 { return true }; return false }),
               case let .deny(reason) = decisions[index] {
                let cause = causes[index]
                return .denied(reason, cause: mutations[index].id, tier: cause.tier, end: cause.end)
            }
            if !policyDeferrals.isEmpty {
                // An active Hardcore future weakening still depends on readable protection
                // state (§8). Easy edits, including future exhausted-budget edits, need no
                // ledger; natural expiry remains independently bounded by the saved window.
                guard let ledger = self.env.ledger(), case .success = ledger.read() else { return .unavailable }
            }
            let deferredUntil = deferredEnds.values.max()
            if let end = deferredUntil {
                guard confirmation != nil, !raisesTier || confirmation?.confirmsTierIncrease == true else {
                    return policyDeferrals.isEmpty ? .nextOccurrenceRequired(ticket, until: end) : .deferRequired(ticket, until: end)
                }
                return self.acceptDeferredBatch(mutations, all: all, ends: deferredEnds, at: now)
            }
            if raisesTier, confirmation?.confirmsTierIncrease != true {
                return .confirmationRequired(ticket, raisesTier: true)
            }
            let harder = zip(causes, decisions).compactMap { cause, decision -> FlintCauseState? in
                if case .wait = decision { return cause }; return nil
            }
            var authorizationCommitted = false
            let enabledBefore = all.filter(\.enabled).count
            let authorizationEnd = harder.compactMap(\.end).max() ?? now
            func unavailableAfterAuthorization() -> FlintRuleMutationResult {
                guard authorizationCommitted else { return .unavailable }
                self.env.recordState(Rule.domain.domain, .init(enabled: enabledBefore,
                    attempted: 0, armed: 0, failures: [.init(activityName: Rule.domain.domain,
                        reason: "Change authorized but not saved — try again. Existing protection stays until \(authorizationEnd.formatted(date: .abbreviated, time: .shortened)).")]))
                return .authorizedButNotSaved(ticket, until: authorizationEnd)
            }
            if !harder.isEmpty {
                let result = self.harderGate(harder, scope: waitScopes, operationID: digest, confirmation: false)
                switch result {
                case .applied: break
                case .authorizedButNotSaved:
                    authorizationCommitted = true
                    if confirmation == nil { return unavailableAfterAuthorization() }
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
                guard let proposed = mutation.proposed else {
                    all.removeAll { $0.id == mutation.id }; continue
                }
                guard let next = self.successor(proposed, replacing: old, at: now) else {
                    self.rollback(prepared, previous: previous); return unavailableAfterAuthorization()
                }
                guard self.prepare(next, replacing: old) else {
                    self.rollback(prepared, previous: previous)
                    return unavailableAfterAuthorization()
                }
                prepared.append(next)
                if let index = all.firstIndex(where: { $0.id == next.id }) { all[index] = next }
                else { all.append(next) }
            }
            if !harder.isEmpty {
                guard self.occurrencesUnchanged(mutations, original: original, since: now) else {
                    self.rollback(prepared, previous: previous); return .staleRevision
                }
                let gate = self.harderGate(harder, scope: waitScopes, operationID: digest, confirmation: true)
                guard gate == .applied else {
                    self.rollback(prepared, previous: previous)
                    return gate == .staleRevision ? .staleRevision : unavailableAfterAuthorization()
                }
                authorizationCommitted = true
            }
            guard self.occurrencesUnchanged(mutations, original: original, since: now) else {
                self.rollback(prepared, previous: previous); return .staleRevision
            }
            guard self.save(all) else { self.rollback(prepared, previous: previous); return unavailableAfterAuthorization() }
            self.finish(prepared, replacing: previous)
            self.notify()
            return .applied
        }
    }

    /// Called before native reconciliation AND by monitor start/end callbacks. A queued batch
    /// crosses its old logical end before today's adjacent occurrence can be materialized.
    @discardableResult
    func applyQueued<Rule: FlintGatedRule>(_ type: Rule.Type) -> Bool {
        FlintRuleStoreLock.withLock(environment: env) {
            guard var all = self.load(type) else { return false }
            let now = self.env.clock.now()
            let due = all.filter { rule in
                rule.protection?.queuedChange.map { FlintRuleOccurrenceClock.isOver($0.window, for: rule, clock: self.env.clock) } ?? false
            }
            guard !due.isEmpty else { return true }
            let original = all
            var prepared: [Rule] = []
            func failed() -> Bool {
                self.rollback(prepared, previous: due)
                var recovery = original
                let reason = "The queued change couldn't be applied. The saved rule remains scheduled; Flint will retry at a safe boundary."
                for old in due {
                    guard let index = recovery.firstIndex(where: { $0.id == old.id }),
                          let queue = old.protection?.queuedChange else { continue }
                    var retained = old
                    if let next = old.templateOccurrence(at: FlintRuleOccurrenceClock.date(for: old, clock: self.env.clock)), next.start >= queue.window.end {
                        // The original recurring template already reached its next natural
                        // occurrence. Keep that finite snapshot and retry only at its own end,
                        // never weaken a newly active Hardcore occurrence on a later foreground.
                        retained.protection?.occurrence = next
                        var retry = FlintQueuedRuleChange(action: queue.action, baseRevision: queue.baseRevision,
                            window: next, snapshot: queue.snapshot, fingerprint: queue.fingerprint, batchID: queue.batchID)
                        retry.failureReason = reason
                        retained.protection?.queuedChange = retry
                    } else { retained.protection?.queuedChange?.failureReason = reason }
                    recovery[index] = retained
                }
                let saved = self.save(recovery)
                if saved {
                    for rule in recovery where due.contains(where: { $0.id == rule.id }) {
                        let endedOneShot = rule.absoluteWindow.map { FlintRuleOccurrenceClock.isOver($0, for: rule, clock: self.env.clock) } ?? false
                        if rule.enabled && !endedOneShot {
                            let observed = self.env.center.activities.contains(.init(rule.activityName))
                                && self.env.center.schedule(for: .init(rule.activityName)) != nil
                            let armed = observed || self.prepare(rule, replacing: nil)
                            if armed && rule.occurrence(at: now, clock: self.env.clock) != nil {
                                self.finish([rule], replacing: [rule], resetTimeBudget: false)
                            } else if rule.occurrence(at: now, clock: self.env.clock) == nil { self.env.clearStore(rule.storeName) } // independent natural end
                        } else if endedOneShot {
                            self.env.clearStore(rule.storeName)
                            self.env.center.stopMonitoring([.init(rule.activityName)])
                        }
                    }
                }
                self.env.recordState(Rule.domain.domain, .init(enabled: recovery.filter(\.enabled).count,
                    attempted: 0, armed: 0, failures: [.init(activityName: Rule.domain.domain, reason: reason)]))
                return false
            }
            for old in due {
                guard let queue = old.protection?.queuedChange, queue.baseRevision == old.revision else { return failed() }
                if queue.action == .delete { all.removeAll { $0.id == old.id }; continue }
                guard let data = queue.snapshot, var next = try? JSONDecoder().decode(Rule.self, from: data),
                      next.id == old.id, next.revision == old.revision + 1, next.validationIssue == nil,
                      next.protection?.incarnation == old.protection?.incarnation,
                      next.protection?.queuedChange == nil,
                      (try? next.fingerprint(action: queue.action.rawValue)) == queue.fingerprint else { return failed() }
                next.protection?.timeAnchor = old.protection?.timeAnchor
                let nextOccurrence = next.templateOccurrence(at: FlintRuleOccurrenceClock.date(for: old, clock: self.env.clock))
                next.protection?.occurrence = nextOccurrence
                guard self.prepare(next, replacing: old) else { return failed() }
                prepared.append(next)
                guard let index = all.firstIndex(where: { $0.id == old.id }) else { return failed() }
                all[index] = next
            }
            guard self.save(all) else { return failed() }
            self.finish(prepared, replacing: due, resetTimeBudget: true)
            self.recordLateQueues(due, remaining: all)
            self.notify()
            return true
        } ?? false
    }

    @discardableResult
    func cancelQueued<Rule: FlintGatedRule>(_ type: Rule.Type, id: String,
                                           expected: FlintRuleProtection) -> FlintRuleMutationResult {
        withLock {
            guard var all = self.load(type) else { return .unavailable }
            guard let index = all.firstIndex(where: { $0.id == id }),
                  var current = all[index].protection,
                  current.incarnation == expected.incarnation, current.revision == expected.revision,
                  let queue = expected.queuedChange, current.queuedChange == queue else { return .staleRevision }
            // Removing a future weakening preserves the effective native revision and counters.
            // Match the stored queue itself; a discarded wait/confirmation ticket is irrelevant.
            current.queuedChange = nil; current.revision += 1
            all[index].protection = current
            guard self.save(all) else { return .unavailable }
            self.notify()
            return .applied
        }
    }

    @discardableResult
    func cancel<Rule: FlintGatedRule>(_ type: Rule.Type, id: String, confirmation: FlintRuleConfirmation? = nil) -> Bool {
        FlintRuleStoreLock.withLock(environment: env) {
            guard let all = self.load(type), let rule = all.first(where: { $0.id == id }),
                  let occurrence = rule.occurrence(at: self.env.clock.now(), clock: self.env.clock), let ledger = self.env.ledger() else { return false }
            return (try? ledger.transaction { state in
                let cause = Self.causeID(rule, window: occurrence)
                if let confirmation, state.harder[cause]?.pending?.operationID != confirmation.fingerprint { return false }
                state.cancelHarderWait(cause: cause); return true
            }.get()) ?? false
        } ?? false
    }

    private func harderGate(_ causes: [FlintCauseState], scope: [String], operationID: String,
                            confirmation: Bool) -> FlintRuleMutationResult {
        guard let ledger = env.ledger() else { return .unavailable }
        return (try? ledger.transaction { state -> FlintRuleMutationResult in
            let instant = FlintLedgerInstant(clock: self.env.clock)
            guard causes.allSatisfy({ $0.end.map { instant.wall < $0 } ?? false }) else { return .staleRevision }
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
            if allCommitted {
                return confirmation ? .applied : .authorizedButNotSaved(placeholder,
                    until: causes.compactMap(\.end).max() ?? instant.wall)
            }
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

    private func acceptDeferredBatch<Rule: FlintGatedRule>(_ mutations: [Mutation<Rule>], all original: [Rule],
                                                         ends: [String: Date], at now: Date) -> FlintRuleMutationResult {
        var all = original
        let batchID = UUID().uuidString
        var preflight: [Rule] = []
        var prepared: [Rule] = []
        var previous: [Rule] = []
        var accepted = false
        defer {
            rollback(preflight, previous: original)
            if !accepted { rollback(prepared, previous: original) }
        }
        for mutation in mutations {
            let old = all.first { $0.id == mutation.id }
            if let end = ends[mutation.id] {
                guard var current = old, var metadata = current.protection,
                      let window = current.occurrence(at: now, clock: env.clock),
                      abs(FlintRuleOccurrenceClock.display(window, for: current, clock: env.clock).end.timeIntervalSince(end)) < 1 else { return .unavailable }
                metadata.occurrence = window; metadata.revision += 1
                var future = mutation.proposed
                if future != nil {
                    var next = metadata
                    next.revision += 1; next.activityRevision = next.revision
                    next.usesLegacyActivityName = false; next.legacyRightsUntil = nil
                    next.queuedChange = nil; next.occurrence = nil
                    next.timeZoneID = future!.protection?.timeZoneID ?? metadata.timeZoneID
                    future!.protection = next
                    guard future!.validationIssue == nil, prepare(future!, replacing: nil) else { return .unavailable }
                    preflight.append(future!)
                }
                let snapshot = future.flatMap { try? JSONEncoder().encode($0) }
                guard future == nil || snapshot != nil,
                      let fingerprint = try? sourceFingerprint(mutation) else { return .unavailable }
                metadata.queuedChange = .init(action: mutation.action, baseRevision: metadata.revision,
                    window: window, snapshot: snapshot, fingerprint: fingerprint, batchID: batchID)
                current.protection = metadata
                guard current.validationIssue == nil, let index = all.firstIndex(where: { $0.id == current.id }) else { return .unavailable }
                all[index] = current
            } else {
                if let old { previous.append(old) }
                guard let proposed = mutation.proposed else {
                    all.removeAll { $0.id == mutation.id }; continue
                }
                guard let next = successor(proposed, replacing: old, at: now), prepare(next, replacing: old) else { return .unavailable }
                prepared.append(next)
                if let index = all.firstIndex(where: { $0.id == next.id }) { all[index] = next }
                else { all.append(next) }
            }
        }
        // All native preflights and revision fences succeeded; one domain write accepts every
        // member. Each active strict member keeps its own boundary, never a sibling's window.
        guard occurrencesUnchanged(mutations, original: original, since: now) else { return .staleRevision }
        guard save(all), let end = ends.values.max() else { return .unavailable }
        accepted = true
        finish(prepared, replacing: previous)
        notify()
        return .queued(until: end)
    }

    private func occurrencesUnchanged<Rule: FlintGatedRule>(_ mutations: [Mutation<Rule>],
                                                           original: [Rule], since start: Date) -> Bool {
        let now = env.clock.now()
        return mutations.allSatisfy { mutation in
            guard let old = original.first(where: { $0.id == mutation.id }) else { return true }
            // The original frozen window identifies the preview; a boundary crossed during
            // native preflight invalidates it even when wall time did not advance normally.
            return old.protection?.occurrence == old.occurrence(at: now, clock: env.clock)
        }
    }

    private func successor<Rule: FlintGatedRule>(_ proposed: Rule, replacing old: Rule?, at now: Date) -> Rule? {
        var next = proposed
        var metadata = old?.protection ?? .init(timeZoneID: next.protection?.timeZoneID ?? TimeZone.current.identifier,
            requiresTierReview: next.protection?.requiresTierReview ?? false)
        let impact = old.flatMap { try? next.impact(comparedTo: $0, at: now, clock: env.clock) }
        let timeline = old.map { FlintRuleOccurrenceClock.date(for: $0, clock: env.clock) } ?? now
        metadata.revision = (old?.revision ?? 0) + 1
        if impact != .cosmetic || old?.enabled != next.enabled {
            metadata.activityRevision = metadata.revision; metadata.usesLegacyActivityName = false
        }
        metadata.contractVersion = 12
        metadata.timeZoneID = next.protection?.timeZoneID ?? metadata.timeZoneID
        metadata.queuedChange = nil
        metadata.legacyRightsUntil = next.enabled && old?.enabled == true ? old?.protection?.legacyRightsUntil : nil
        if let window = old?.occurrence(at: now, clock: env.clock), next.enabled {
            if impact == .weaken {
                metadata.occurrence = next.templateOccurrence(at: timeline).map { .init(start: window.start, end: $0.end) }
            } else {
                metadata.occurrence = .init(start: window.start,
                    end: max(window.end, next.templateOccurrence(at: timeline)?.end ?? window.end))
            }
        } else { metadata.occurrence = next.templateOccurrence(at: timeline) }
        if impact != .strengthen, let queued = old?.protection?.queuedChange {
            guard let preserved = rebase(queued, type: Rule.self, revision: metadata.revision,
                end: max(queued.window.end, metadata.occurrence?.end ?? queued.window.end)) else { return nil }
            metadata.queuedChange = preserved
        }
        next.protection = metadata
        FlintRuleOccurrenceClock.observe(&next, clock: env.clock)
        return next.validationIssue == nil ? next : nil
    }

    private func rebase<Rule: FlintGatedRule>(_ queue: FlintQueuedRuleChange, type: Rule.Type,
                                             revision: Int, end: Date) -> FlintQueuedRuleChange? {
        var payload: Data?
        if queue.action != .delete {
            guard let bytes = queue.snapshot, var future = try? JSONDecoder().decode(type, from: bytes) else { return nil }
            future.protection?.revision = revision + 1; future.protection?.activityRevision = revision + 1
            guard let encoded = try? JSONEncoder().encode(future) else { return nil }
            payload = encoded
        }
        var result = FlintQueuedRuleChange(action: queue.action, baseRevision: revision,
            window: .init(start: queue.window.start, end: end), snapshot: payload,
            fingerprint: queue.fingerprint, batchID: queue.batchID)
        result.failureReason = queue.failureReason
        return result
    }

    private func prepare<Rule: FlintGatedRule>(_ next: Rule, replacing old: Rule?) -> Bool {
        guard next.enabled else { return true }
        _ = FlintWebRestrictionRuleAdapter.freeze([next], environment: env, existingWebStores: env.existingWebStores)
        if next.activityName == old?.activityName { return true } // cosmetic: no restart/counter reset
        if let old = old as? FlintLimitRule, old.enabled, old.occurrence(at: env.clock.now(), clock: env.clock) != nil {
            if #available(iOS 17.4, *) {} else { return false } // cannot reset already-used daily usage
        }
        let request = next.registration(clock: env.clock)
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

    private func finish<Rule: FlintGatedRule>(_ prepared: [Rule], replacing old: [Rule], resetTimeBudget: Bool = false) {
        let now = env.clock.now()
        for rule in prepared where rule.enabled {
            if let schedule = rule as? FlintScheduleRule, schedule.occurrence(at: env.clock.now(), clock: env.clock) != nil {
                env.applyProtection(schedule)
            } else if let limit = rule as? FlintLimitRule {
                if limit.thresholdMinutes == 0 || (!resetTimeBudget && old.compactMap({ $0 as? FlintLimitRule })
                    .contains(where: { $0.id == rule.id && env.timeLimitIsShielded($0, now) })) {
                    // Strengthening keeps an exhausted shield. A cosmetic edit below quota
                    // never exhausts the allowance; a due future revision starts a fresh day.
                    env.applyProtection(limit)
                    env.markLimitThreshold(limit, now)
                } else if resetTimeBudget {
                    env.clearStore(limit.storeName)
                    env.clearLimitThreshold(limit.id)
                }
            } else if let open = rule as? FlintOpenLimitRule { env.applyProtection(open) }
        }
        for previous in old {
            let replacement = prepared.first { $0.id == previous.id }
            let previousOccurrence = previous.occurrence(at: now, clock: env.clock)
            let legacyRights = previous.protection?.legacyRightsUntil.map {
                FlintRuleOccurrenceClock.date(for: previous, clock: env.clock) < $0
            } ?? false
            if replacement?.enabled != true, previous.breakLevel == .hardcore,
               previousOccurrence != nil, !legacyRights {
                // Defense at the native retirement boundary as well as the policy gate:
                // a wall jump is never permission to clear/stop an active Hardcore cause.
                env.record(Rule.domain.domain, .init(enabled: old.filter(\.enabled).count,
                    attempted: 0, armed: 0, failures: [.init(activityName: previous.activityName,
                        reason: "Active Hardcore protection retained until its trusted occurrence end.")]))
                continue
            }
            if replacement?.enabled != true
                || (replacement?.window != nil && replacement?.occurrence(at: env.clock.now(), clock: env.clock) == nil) {
                env.clearStore(previous.storeName)
                if previous.kind == .timeLimit { env.clearLimitThreshold(previous.id) }
            }
            if replacement?.activityName != previous.activityName || replacement?.enabled != true {
                env.center.stopMonitoring([.init(previous.activityName)])
            }
        }
    }

    private func save<Rule: FlintGatedRule>(_ all: [Rule]) -> Bool {
        env.acceptSave() && FlintDesiredRules.save(all, key: Rule.domain, to: env.defaults)
    }
    private func load<Rule: FlintGatedRule>(_ type: Rule.Type, freezing: Bool = false) -> [Rule]? {
        guard let rules = env.observedRules(type) else { return nil }
        return freezing ? env.freezeOccurrences(rules) : rules
    }
    private func withLock(_ body: () -> FlintRuleMutationResult) -> FlintRuleMutationResult {
        FlintRuleStoreLock.withLock(environment: env, body) ?? .unavailable
    }
    private func notify() {
        env.ordinaryPolicyDidChange()
        FlintRuleStoreLock.afterUnlock(environment: env) {
            self.env.publishStatus()
            NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
        }
    }
    private func recordLateQueues<Rule: FlintGatedRule>(_ applied: [Rule], remaining: [Rule]) {
        let notes = applied.compactMap { rule -> FlintArmingHealth.Failure? in
            guard let queue = rule.protection?.queuedChange,
                  FlintRuleOccurrenceClock.date(for: rule, clock: env.clock).timeIntervalSince(queue.window.end) > 60 else { return nil }
            // This minute is a diagnostic threshold, never a grace period or release authority.
            let formatter = DateFormatter()
            formatter.timeZone = rule.zone; formatter.dateStyle = .medium; formatter.timeStyle = .short
            return .init(activityName: "queued.\(rule.id)",
                reason: "Queued change to \(rule.name) applied late (due \(formatter.string(from: queue.window.end))).")
        }
        guard !notes.isEmpty else { return }
        FlintRuleStoreLock.afterUnlock(environment: env) {
            // A containing reload records native observations before unlocking. Preserve that
            // report and append a diagnostic that cannot masquerade as an activity failure.
            var report = self.env.loadHealth().domains[Rule.domain.domain]
                ?? .init(enabled: remaining.filter(\.enabled).count, attempted: 0, armed: 0, failures: [])
            let keys = Set(notes.map(\.activityName))
            report.failures.removeAll { keys.contains($0.activityName) }
            report.failures.append(contentsOf: notes)
            report.updatedAt = self.env.clock.now()
            self.env.recordState(Rule.domain.domain, report)
        }
    }
    static func causeID<Rule: FlintGatedRule>(_ rule: Rule, window: FlintRuleWindow?) -> String {
        "\(Rule.domain.rawValue):\(rule.id):\(rule.protection?.incarnation ?? "legacy")@\(window?.identity ?? "inactive")"
    }
}
#endif
