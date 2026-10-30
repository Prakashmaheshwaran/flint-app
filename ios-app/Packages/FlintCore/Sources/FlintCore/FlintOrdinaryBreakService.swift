import Foundation

public struct FlintBreakContext {
    public struct Owner {
        public let contributor: FlintBreakContributor
        public let storeName: String
        public init(_ contributor: FlintBreakContributor, storeName: String) {
            self.contributor = contributor; self.storeName = storeName
        }
    }
    public let owners: [Owner]
    public let unavailableKinds: Set<FlintBreakScope.Target.Kind>
    public init(owners: [Owner], unavailableKinds: Set<FlintBreakScope.Target.Kind> = []) {
        self.owners = owners; self.unavailableKinds = unavailableKinds
    }
    var contributors: [FlintBreakContributor] { owners.map(\.contributor) }
    func canRelease(_ scope: FlintBreakScope) -> Bool {
        guard !scope.targets.contains(where: { unavailableKinds.contains($0.kind) }) else { return false }
        let apps = scope.targets.filter { $0.kind == .application }
        return owners.allSatisfy { owner in
            guard owner.contributor.cause?.isActive == true,
                  case let .allowList(allowed)? = owner.contributor.selection else { return true }
            return allowed.union(apps).count <= FlintShieldLimits.tokenCap
        }
    }
    func authorities(for grant: FlintBreakGrant) throws -> [FlintBreakAuthority] {
        try owners.filter { owner in grant.causes.contains { $0.causeID == owner.contributor.cause?.causeID } }
            .map { try .init(contributor: $0.contributor, storeName: $0.storeName) }
    }

    /// Re-resolve ALL contributors for each target, including revision-zero session ends.
    /// An unknown/category overlap never proves release. Returned scope only shrinks.
    func authorizedTargets(for record: FlintOrdinaryGrantState.Record, at now: FlintLedgerInstant)
        -> Set<FlintBreakScope.Target> {
        guard record.remaining(at: now) > 0, contributors.allSatisfy({
            $0.cause != nil && $0.selection != nil && ($0.cause?.isActive != true || $0.timeKnown)
        }) else { return [] }
        let request = FlintBreakScope(targets: record.releasedTargets)
        let resolution = FlintBreakScopeResolution.resolve(request: request, contributors: contributors)
        guard !resolution.stateUnavailable else { return [] }
        return resolution.releasable.targets.filter { target in
            guard !unavailableKinds.contains(target.kind) else { return false }
            let blocking = resolution.blocking.filter { $0.targets.contains(target) }
            return !blocking.isEmpty && blocking.allSatisfy { entry in
                guard let owner = owners.first(where: { $0.contributor.cause?.causeID == entry.cause.causeID }),
                      let accepted = record.authorities.first(where: { $0.cause.causeID == entry.cause.causeID }) else { return false }
                return accepted.matches(owner.contributor, storeName: owner.storeName)
            }
        }
    }
}

public enum FlintBreakOutcome: Equatable {
    case waiting(FlintBreakAuthorization.Request, remaining: TimeInterval, total: TimeInterval)
    case confirmation(FlintBreakAuthorization.Request)
    case active(FlintOrdinaryGrantState.Record, remaining: TimeInterval)
    case ended(FlintBreakEndReason)
    case refused(FlintOrdinaryBreakError)
}

/// Injectable transaction/native seam. The live transition takes session → rules locks;
/// context is read again inside every ledger transaction. Native effects never authorize a
/// grant: a registered carrier and positively read-back durable record precede release.
public final class FlintOrdinaryBreakService {
    public enum Callback { case start, warning, end }
    private let ledger: FlintProtectionLedger
    private let clock: FlintClock
    private let transition: (() -> Void) -> Bool
    private let context: (FlintLedgerState, FlintLedgerInstant) -> FlintBreakContext?
    private let register: (FlintOrdinaryGrantState.Record, TimeInterval) throws -> Void
    private let registered: (String) -> Bool
    private let stop: (String) -> Void
    private let apply: ([FlintOrdinaryGrantState.Record], FlintBreakContext?, FlintLedgerInstant) throws -> Void
    private let gate: () -> Bool

    public init(ledger: FlintProtectionLedger, clock: FlintClock,
                transition: @escaping (() -> Void) -> Bool,
                context: @escaping (FlintLedgerState, FlintLedgerInstant) -> FlintBreakContext?,
                register: @escaping (FlintOrdinaryGrantState.Record, TimeInterval) throws -> Void,
                registered: @escaping (String) -> Bool, stop: @escaping (String) -> Void,
                apply: @escaping ([FlintOrdinaryGrantState.Record], FlintBreakContext?, FlintLedgerInstant) throws -> Void,
                gate: @escaping () -> Bool = { true }) {
        self.ledger = ledger; self.clock = clock; self.transition = transition; self.context = context
        self.register = register; self.registered = registered; self.stop = stop; self.apply = apply; self.gate = gate
    }

    public func request(scope: FlintBreakScope, operationID: String? = nil) -> FlintBreakOutcome {
        var answer: FlintBreakOutcome = .refused(.unavailable)
        guard transition({
            guard self.gate(), self.reconcileLocked() else { return }
            let result = self.ledger.transaction { state -> FlintBreakOutcome in
                let now = FlintLedgerInstant(clock: self.clock)
                guard let context = self.context(state, now), scope.isValid,
                      context.canRelease(scope) else { throw FlintOrdinaryBreakError.unavailable }
                var grants = state.ordinaryGrants ?? FlintOrdinaryGrantState()
                let id = try operationID ?? (grants.pending?.scope == scope ? grants.pending?.operationID : nil) ?? grants.newOperationID()
                let request = FlintBreakAuthorization.Request(operationID: id, scope: scope)
                if let receipt = grants.receipt(for: request) { return .ended(receipt.reason) }
                if let record = grants.records.first(where: { $0.grant.operationID == id }) {
                    guard record.grant.scope == scope else { throw FlintOrdinaryBreakError.unavailable }
                    return record.endReason.map(FlintBreakOutcome.ended) ?? .active(record, remaining: record.remaining(at: now))
                }
                guard grants.validOperation(id, generation: grants.generation), !grants.contains(id) else {
                    return .refused(.expiredRequest)
                }
                switch FlintBreakAuthorization.decide(contributors: context.contributors,
                    existingGrants: grants.records.filter { $0.endReason == nil }.map(\.grant), request: request, clock: now.clock) {
                case let .deny(reason): return self.denied(reason, grants: grants, now: now)
                case .confirmEasy:
                    grants.pending = request; state.ordinaryGrants = grants
                    return .confirmation(request)
                case let .wait(total, causes):
                    let binding = FlintBreakAuthorization.waitScope(request: request, contributors: context.contributors)
                    guard !binding.isEmpty else { throw FlintOrdinaryBreakError.unavailable }
                    var remaining: TimeInterval = 0
                    for cause in causes {
                        switch state.requestHarderWait(cause: cause.causeID, kind: "break", scope: binding,
                                                      operationID: id, at: now) {
                        case let .waiting(left): remaining = max(remaining, left)
                        case .matured: break
                        case .none: throw FlintOrdinaryBreakError.unavailable
                        }
                    }
                    grants.pending = request; state.ordinaryGrants = grants
                    return remaining > 0 ? .waiting(request, remaining: remaining, total: total) : .confirmation(request)
                case .replay, .replayExpired: throw FlintOrdinaryBreakError.unavailable
                }
            }
            if case let .success(value) = result { answer = value }
        }) else { return .refused(.unavailable) }
        return answer
    }

    public func confirm(_ request: FlintBreakAuthorization.Request) -> FlintBreakOutcome {
        var answer: FlintBreakOutcome = .refused(.unavailable)
        guard transition({
            guard self.gate(), self.reconcileLocked() else { return }
            var registration: String?
            var refusal: FlintOrdinaryBreakError?
            let committed = self.ledger.transaction { state -> String in
                let now = FlintLedgerInstant(clock: self.clock)
                guard let grants = state.ordinaryGrants, let context = self.context(state, now) else {
                    throw FlintOrdinaryBreakError.unavailable
                }
                guard context.canRelease(request.scope) else {
                    throw FlintOrdinaryBreakError.unavailable
                }
                if let receipt = grants.receipt(for: request) { return receipt.grantID }
                if let record = grants.records.first(where: { $0.grant.operationID == request.operationID }) {
                    guard record.grant.scope == request.scope else { throw FlintOrdinaryBreakError.unavailable }
                    return record.grant.id // read-back below decides live versus terminal receipt
                }
                guard grants.pending == request, grants.validOperation(request.operationID, generation: grants.generation),
                      !grants.contains(request.operationID) else {
                    refusal = .expiredRequest; throw FlintOrdinaryBreakError.expiredRequest
                }
                let result = FlintBreakAuthorization.commitPlan(contributors: context.contributors,
                    existingGrants: grants.records.filter { $0.endReason == nil }.map(\.grant), request: request,
                    clock: now.clock, grantID: UUID().uuidString, confirmed: true)
                guard case let .commit(plan) = result else {
                    switch result {
                    case .waitNotMatured: refusal = .waitNotMatured
                    case .pendingMismatch: refusal = .pendingMismatch
                    case .denied(.hardcore): refusal = .hardcore
                    default: refusal = .unavailable
                    }
                    throw refusal!
                }
                let authorities = try context.authorities(for: plan.grant)
                // Validate the entire batch against a value copy before native registration.
                var checked = state
                try checked.commitOrdinaryBreak(plan, authorities: authorities, request: request,
                                               contributors: context.contributors, at: now)
                let record = FlintOrdinaryGrantState.Record(grant: plan.grant, authorities: authorities)
                registration = record.activityName
                try self.register(record, record.remaining(at: now))
                guard self.registered(record.activityName), record.remaining(at: .init(clock: self.clock)) > 0 else {
                    throw FlintOrdinaryBreakError.unavailable
                }
                try state.commitOrdinaryBreak(plan, authorities: authorities, request: request,
                                             contributors: context.contributors, at: .init(clock: self.clock))
                state.ordinaryGrants?.pending = nil
                return record.grant.id
            }
            guard case let .success(id) = committed else {
                if let registration { self.stop(registration) }
                answer = .refused(refusal ?? .unavailable); return
            }
            guard self.reconcileLocked(), case let .success(state) = self.ledger.peek() else { return }
            if let receipt = state.ordinaryGrants?.receipt(for: request) { answer = .ended(receipt.reason); return }
            guard let record = state.ordinaryGrants?.records.first(where: { $0.grant.id == id }) else { return }
            answer = record.endReason.map(FlintBreakOutcome.ended) ?? .active(record, remaining: record.remaining(at: .init(clock: self.clock)))
        }) else { return .refused(.unavailable) }
        return answer
    }

    public func cancel(_ request: FlintBreakAuthorization.Request) {
        _ = transition {
            _ = self.ledger.transaction { state in
                for key in state.harder.keys where state.harder[key]?.pending?.operationID == request.operationID &&
                    state.harder[key]?.pending?.kind == "break" { state.cancelHarderWait(cause: key) }
                if state.ordinaryGrants?.pending == request { state.ordinaryGrants?.pending = nil }
            }
        }
    }

    public func resumeNow(grantID: String) -> Bool {
        var success = false
        guard transition({
            guard case .success = self.ledger.transaction({ state in
                guard let i = state.ordinaryGrants?.records.firstIndex(where: { $0.grant.id == grantID }) else {
                    throw FlintOrdinaryBreakError.unavailable
                }
                state.ordinaryGrants?.records[i].end(.resumeNow, at: .init(clock: self.clock))
            }) else { return }
            success = self.reconcileLocked()
        }) else { return false }
        return success
    }

    @discardableResult
    public func reconcile(activity: String? = nil, callback: Callback? = nil) -> Bool {
        var success = false
        guard transition({ success = self.reconcileLocked(activity: activity, callback: callback) }) else { return false }
        return success
    }

    private func reconcileLocked(activity: String? = nil, callback: Callback? = nil) -> Bool {
        let result = ledger.transaction { state -> [FlintOrdinaryGrantState.Record] in
            guard var grants = state.ordinaryGrants else { return [] }
            let now = FlintLedgerInstant(clock: self.clock)
            guard FlintOrdinaryGrantState.validObservation(now) else { throw FlintOrdinaryBreakError.unavailable }
            let context = self.context(state, now)
            for i in grants.records.indices where grants.records[i].endReason == nil {
                let record = grants.records[i]
                if record.grant.acceptedAt.bootID != now.bootID || now.monotonic < record.grant.acceptedAt.monotonic {
                    grants.records[i].end(.reboot, at: now)
                } else if record.remaining(at: now) == 0 {
                    grants.records[i].end(.deadline, at: now)
                    let late = max(0, now.monotonic - record.grant.deadline.monotonic)
                    if late > 1 {
                        state.remember(.init(id: record.grant.id + ".break.expiry", kind: "break.expiry",
                            result: callback == .end ? "missed_warning_recovered" : "late_reshield",
                            at: now.wall))
                    }
                } else if let context {
                    let allowed = context.authorizedTargets(for: record, at: now)
                    if allowed != record.releasedTargets {
                        grants.records[i].releasedTargets = allowed; grants.records[i].applicationPending = true
                        if allowed.isEmpty {
                            let newCause = context.owners.contains { owner in
                                owner.contributor.cause?.isActive == true && !record.authorities.contains {
                                    $0.cause.causeID == owner.contributor.cause?.causeID
                                }
                            }
                            let oldStillPresent = context.owners.contains { owner in
                                owner.contributor.cause?.isActive == true && record.authorities.contains {
                                    $0.cause.causeID == owner.contributor.cause?.causeID
                                }
                            }
                            grants.records[i].end(newCause && oldStillPresent ? .newCommitment : .supersession, at: now)
                        }
                    }
                }
                guard grants.records[i].endReason == nil else { continue }
                let elapsed = record.carrierCheckpoint.elapsed(until: now)
                let clockMoved = elapsed.map { abs(now.wall.timeIntervalSince(record.carrierCheckpoint.wall) - $0) > FlintTimeIntegrity.discontinuityThreshold } ?? true
                let repair = !self.registered(record.activityName) || clockMoved ||
                    (activity == record.activityName && (callback == .warning || callback == .end))
                if repair {
                    do {
                        try self.register(grants.records[i], record.remaining(at: now))
                        guard self.registered(record.activityName) else { throw FlintOrdinaryBreakError.unavailable }
                        grants.records[i].carrierCheckpoint = now
                    } catch { grants.records[i].end(.supersession, at: now) }
                }
            }
            state.ordinaryGrants = grants
            return grants.records
        }
        guard case let .success(records) = result else { return false }
        if let activity, !records.contains(where: { $0.activityName == activity }) { stop(activity) }
        guard !records.isEmpty else { return true }
        // Native application follows a fresh durable snapshot while the outer transition
        // still excludes callbacks and policy changes. Deadlines are checked again here.
        guard case let .success(state) = ledger.peek() else { return false }
        let now = FlintLedgerInstant(clock: clock)
        let current = context(state, now)
        do { if gate() { try apply(records, current, now) } } catch { return false }
        for record in records where record.endReason != nil { stop(record.activityName) }
        return (try? ledger.transaction { state in
            guard var grants = state.ordinaryGrants else { return }
            for i in grants.records.indices { grants.records[i].applicationPending = false }
            try grants.archiveFinished(); state.ordinaryGrants = grants
        }.get()) != nil
    }

    /// Until F2 supplies an append receipt the live adapter does not call this. A sink retry
    /// uses the stable row ID, so a crash after append but before ack does not duplicate history.
    public func drainEvents(append: (FlintOrdinaryGrantState.EventIntent, String) -> Bool) -> Bool {
        var success = false
        guard transition({
            success = (try? self.ledger.transaction { state in
                guard var grants = state.ordinaryGrants else { return }
                for i in grants.records.indices {
                    for j in grants.records[i].events.indices {
                        let event = grants.records[i].events[j]
                        for session in event.focusSessionIDs.subtracting(event.acknowledgedSessionIDs).sorted() {
                            if append(event, session) { grants.records[i].events[j].acknowledgedSessionIDs.insert(session) }
                        }
                    }
                }
                var retained: [Data] = []
                for raw in grants.eventOutbox {
                    guard var event = try? FlintProtectionLedger.makeDecoder().decode(FlintOrdinaryGrantState.EventIntent.self, from: raw) else {
                        retained.append(raw); continue // quarantine in place; not authorization
                    }
                    for session in event.focusSessionIDs.subtracting(event.acknowledgedSessionIDs).sorted() {
                        if append(event, session) { event.acknowledgedSessionIDs.insert(session) }
                    }
                    if !event.isAcknowledged { retained.append(try FlintProtectionLedger.makeEncoder().encode(event)) }
                }
                grants.eventOutbox = retained
                try grants.archiveFinished(); state.ordinaryGrants = grants
            }.get()) != nil
        }) else { return false }
        return success
    }

    private func denied(_ reason: FlintBreakAuthorization.Denial, grants: FlintOrdinaryGrantState,
                        now: FlintLedgerInstant) -> FlintBreakOutcome {
        switch reason {
        case .hardcore: return .refused(.hardcore)
        case .unavailable: return .refused(.unavailable)
        case let .activeGrant(left):
            if let record = grants.records.first(where: { $0.remaining(at: now) > 0 }) { return .active(record, remaining: left) }
            return .refused(.activeGrant(left))
        }
    }
}
