import Foundation

/// Pure planning only. No ledger writes, OS registration, token decoding or release occurs here.
public enum FlintBreakAuthorization {
    public struct Request: Equatable, Sendable {
        public let operationID: String
        public let scope: FlintBreakScope
        public init(operationID: String, scope: FlintBreakScope) {
            self.operationID = operationID; self.scope = scope
        }
    }
    public enum Denial: Equatable, Sendable {
        case hardcore
        case unavailable
        case activeGrant(remaining: TimeInterval)
        public var message: String {
            switch self {
            case .hardcore: return "Hardcore allows no breaks — Emergency Pass is separate"
            case .unavailable: return "Protection state unavailable"
            case .activeGrant: return "A break is already active"
            }
        }
    }
    public enum Decision: Equatable, Sendable {
        case deny(Denial)
        case wait(seconds: TimeInterval, harderCauses: [FlintBreakGrant.Cause])
        case confirmEasy
        /// A live receipt; native application still requires current contributor authorization.
        case replay(FlintBreakGrant, remaining: TimeInterval)
        /// Audit/idempotency receipt only. Never apply release from this case.
        case replayExpired(FlintBreakGrant)
    }
    public struct CountIncrement: Equatable, Sendable {
        public let cause: FlintBreakGrant.Cause
        public let expectedCount: Int
        public let nextCount: Int
        /// Matches existing session/rule requestHarderWait and commitHarderWait callers.
        /// Rule causeID already incorporates the natural occurrence; do not key by occurrenceID.
        public var ledgerKey: String { cause.causeID }
    }
    public struct CommitPlan: Equatable, Sendable {
        public let grant: FlintBreakGrant
        /// Apply these once, atomically with the grant, after registration succeeds. On retry,
        /// pass committed grants back in: replay produces no plan and no increments.
        public let increments: [CountIncrement]
    }
    public enum CommitResult: Equatable, Sendable {
        case commit(CommitPlan)
        /// Recovery receipt, not a new plan or release authority; remaining may be zero.
        case replay(FlintBreakGrant, remaining: TimeInterval)
        case denied(Denial)
        case confirmationRequired
        case waitNotMatured
        case pendingMismatch
    }

    public static func decide(contributors: [FlintBreakContributor]?, existingGrants: [FlintBreakGrant]?,
                              request: Request, clock: FlintClock) -> Decision {
        evaluate(contributors: contributors, existingGrants: existingGrants, request: request,
                 now: FlintLedgerInstant(clock: clock))
    }

    private static func evaluate(contributors: [FlintBreakContributor]?, existingGrants: [FlintBreakGrant]?,
                                 request: Request, now: FlintLedgerInstant) -> Decision {
        guard !request.operationID.isEmpty, request.scope.isValid, let grants = existingGrants,
              grants.allSatisfy(\.isValid),
              Set(grants.map(\.operationID)).count == grants.count,
              Set(grants.map(\.id)).count == grants.count else { return .deny(.unavailable) }
        // Recover an already committed receipt even if current contributors/time are unreadable.
        // This never creates another grant; adapters must recheck authorizes before applying it.
        if let grant = grants.first(where: { $0.operationID == request.operationID }) {
            guard grant.scope == request.scope else { return .deny(.unavailable) }
            let remaining = grant.remaining(at: now)
            return remaining > 0 ? .replay(grant, remaining: remaining) : .replayExpired(grant)
        }
        guard FlintBreakGrant.validInstant(now), let contributors,
              contributors.allSatisfy({ $0.cause != nil && $0.selection != nil }) else { return .deny(.unavailable) }
        let resolution = FlintBreakScopeResolution.resolve(request: request.scope, contributors: contributors)
        // Any known Hardcore blocker is an unconditional denial, even if another overlap is unknown.
        let possibleHardcore = contributors.contains { contributor in
            guard let cause = contributor.cause, cause.isActive, cause.tier == .hardcore,
                  let selection = contributor.selection else { return false }
            switch selection {
            case let .blockList(scope): return scope.mayOverlap(request.scope)
            case let .allowList(allowed): return !request.scope.targets.isSubset(of: allowed)
            }
        }
        if possibleHardcore { return .deny(.hardcore) }
        guard !resolution.stateUnavailable, resolution.unresolved.isEmpty,
              resolution.releasable == request.scope, !resolution.blocking.isEmpty else { return .deny(.unavailable) }
        let affected = affectedContributors(contributors, resolution: resolution)
        guard affected.count == resolution.blocking.count, affected.count <= 128,
              Set(affected.compactMap { $0.cause?.causeID }).count == affected.count,
              // The adapter supplies readable selections even for inactive entries, and
              // trusted time for every active entry, including out-of-scope causes. This
              // deliberately fails closed for an incompletely reconciled contributor set.
              contributors.filter({ $0.cause?.isActive == true }).allSatisfy({ validTime($0, now: now) }),
              affected.allSatisfy({ contributor in
                  guard let cause = contributor.cause else { return false }
                  return FlintBreakGrant.Cause(cause).isValid && (cause.tier != .harder ||
                      contributor.harder.map { $0.committedCount >= 0 && $0.committedCount < Int.max } == true)
              }) else { return .deny(.unavailable) }
        let remaining = grants.filter { $0.scope.mayOverlap(request.scope) }.map { $0.remaining(at: now) }.max() ?? 0
        if remaining > 0 { return .deny(.activeGrant(remaining: remaining)) }
        let harder = affected.filter { $0.cause?.tier == .harder }
        if !harder.isEmpty {
            return .wait(seconds: harder.map { FlintProtectionPolicy.harderWait(forCount: $0.harder!.committedCount) }.max()!,
                         harderCauses: harder.map { .init($0.cause!) })
        }
        return .confirmEasy
    }

    private static func validTime(_ contributor: FlintBreakContributor, now: FlintLedgerInstant) -> Bool {
        guard contributor.timeKnown else { return false }
        guard let end = contributor.logicalEnd else {
            return contributor.cause?.tier == .easy && contributor.cause?.end == nil
        }
        return FlintBreakGrant.validInstant(end) && end.bootID == now.bootID && end.monotonic > now.monotonic
    }
    private static func affectedContributors(_ contributors: [FlintBreakContributor],
                                             resolution: FlintBreakScopeResolution.Result) -> [FlintBreakContributor] {
        contributors.filter { contributor in
            contributor.cause.map { cause in resolution.blocking.contains { $0.cause == .init(cause) } } == true
        }.sorted { $0.cause!.causeID < $1.cause!.causeID }
    }

    /// Exact wait binding for #120 PendingWait.scope. Include operation ID because resuming
    /// the generic ledger wait may adopt a different ID; a different break must start anew.
    /// Canonical JSON avoids delimiter collisions in identities/tokens. No clock is sampled.
    public static func waitScope(request: Request, contributors: [FlintBreakContributor]) -> [String] {
        guard request.scope.isValid, !request.operationID.isEmpty else { return [] }
        let resolution = FlintBreakScopeResolution.resolve(request: request.scope, contributors: contributors)
        let affected = affectedContributors(contributors, resolution: resolution)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        var parts = ["ordinary-break-v1", request.operationID]
        parts += request.scope.targets.map(\.key).sorted()
        for contributor in affected {
            guard let cause = contributor.cause,
                  let bytes = try? encoder.encode(FlintBreakGrant.Cause(cause)) else { return [] }
            parts.append(bytes.base64EncodedString())
        }
        return parts
    }

    /// Re-evaluates current contributors, exact revisions, waits and overlap at final acceptance.
    /// A matured timer alone cannot commit: final confirmation is always required. The caller
    /// must register the deadline, then persist grant + increments under its transaction before
    /// releasing. This function is NOT an enforcement adapter or proof of a durable commit.
    public static func commitPlan(contributors: [FlintBreakContributor]?, existingGrants: [FlintBreakGrant]?,
                                  request: Request, clock: FlintClock, grantID: String,
                                  confirmed: Bool) -> CommitResult {
        let now = FlintLedgerInstant(clock: clock)
        switch evaluate(contributors: contributors, existingGrants: existingGrants, request: request, now: now) {
        case let .deny(reason): return .denied(reason)
        case let .replay(grant, remaining): return .replay(grant, remaining: remaining)
        case let .replayExpired(grant): return .replay(grant, remaining: 0)
        case .confirmEasy, .wait: break
        }
        guard confirmed else { return .confirmationRequired }
        guard !grantID.isEmpty, existingGrants?.contains(where: { $0.id == grantID }) == false,
              let contributors else { return .denied(.unavailable) }
        let resolution = FlintBreakScopeResolution.resolve(request: request.scope, contributors: contributors)
        let affected = affectedContributors(contributors, resolution: resolution)
        let scope = waitScope(request: request, contributors: contributors)
        guard !scope.isEmpty else { return .denied(.unavailable) }
        var increments: [CountIncrement] = []
        for contributor in affected where contributor.cause?.tier == .harder {
            guard let cause = contributor.cause, let harder = contributor.harder else { return .denied(.unavailable) }
            guard let pending = harder.pending, pending.operationID == request.operationID,
                  pending.kind == "break", pending.scope == scope,
                  pending.requestedAt.bootID == now.bootID else { return .pendingMismatch }
            guard FlintBreakGrant.validInstant(pending.requestedAt), pending.wait.isFinite,
                  pending.wait >= FlintProtectionPolicy.harderWait(forCount: harder.committedCount) else {
                return .denied(.unavailable)
            }
            guard let elapsed = pending.requestedAt.elapsed(until: now), elapsed >= pending.wait else { return .waitNotMatured }
            increments.append(.init(cause: .init(cause), expectedCount: harder.committedCount,
                                    nextCount: harder.committedCount + 1))
        }
        let duration = min(FlintProtectionConstants.breakDuration,
                           affected.compactMap { $0.logicalEnd.map { $0.monotonic - now.monotonic } }.min()
                           ?? FlintProtectionConstants.breakDuration)
        let deadline = FlintLedgerInstant(wall: now.wall.addingTimeInterval(duration),
                                          monotonic: now.monotonic + duration, bootID: now.bootID)
        let grant = FlintBreakGrant(id: grantID, operationID: request.operationID, scope: request.scope,
                                   causes: affected.map { .init($0.cause!) }, acceptedAt: now, deadline: deadline)
        guard grant.isValid else { return .denied(.unavailable) }
        return .commit(.init(grant: grant, increments: increments))
    }
}
