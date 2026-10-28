import Foundation

/// Re-read inside the ledger lock, including on the commit-to-release recovery path.
public struct FlintOpenCreditContext {
    public var coveringCaps: [String: Int]
    public var targetKeys: Set<String>
    public var blockedBy: FlintOpenCreditBlock?
    public var blockingContributors: [String]
    public var restoration: FlintOpenCreditRestoration?
    public init(coveringCaps: [String: Int], targetKeys: Set<String>, blockedBy: FlintOpenCreditBlock? = nil,
                blockingContributors: [String] = [], restoration: FlintOpenCreditRestoration? = nil) {
        self.coveringCaps = coveringCaps; self.targetKeys = targetKeys; self.blockedBy = blockedBy
        self.blockingContributors = blockingContributors
        self.restoration = restoration
    }
}

/// The same transaction/adapter ordering is exercised with fake stores and registrars in tests.
/// There is deliberately no host timer and no UserDefaults consumption path.
public final class FlintOpenCreditService {
    private let ledger: FlintProtectionLedger
    private let clock: FlintClock
    private let context: () -> FlintOpenCreditContext?
    private let register: (FlintOpenCreditGrant, TimeInterval) throws -> Void
    private let registered: (String) -> Bool
    private let release: (FlintOpenCreditGrant) throws -> Void
    private let reshield: (FlintOpenCreditGrant) -> Void
    private let stop: (String) -> Void

    public init(ledger: FlintProtectionLedger, clock: FlintClock,
                context: @escaping () -> FlintOpenCreditContext?,
                register: @escaping (FlintOpenCreditGrant, TimeInterval) throws -> Void,
                registered: @escaping (String) -> Bool, release: @escaping (FlintOpenCreditGrant) throws -> Void,
                reshield: @escaping (FlintOpenCreditGrant) -> Void, stop: @escaping (String) -> Void) {
        self.ledger = ledger; self.clock = clock; self.context = context; self.register = register
        self.registered = registered; self.release = release; self.reshield = reshield; self.stop = stop
    }

    /// Does not consume, register, or release. Advancing a snapshot never replaces the ledger.
    public func preview(target: String) -> FlintOpenCreditPreview {
        guard case var .success(state) = ledger.peek(), let context = context() else {
            return .init(remaining: nil, cap: nil, blockedBy: .unavailable)
        }
        let now = FlintLedgerInstant(clock: clock)
        guard state.reconcileOpenCredits(at: now, targetKeys: context.targetKeys, reshield: { _ in }) else {
            return .init(remaining: nil, cap: context.coveringCaps.values.min(), blockedBy: .unavailable)
        }
        return state.openCreditPreview(target: target, caps: Array(context.coveringCaps.values),
            blockedBy: context.blockedBy, at: now, contributors: context.blockingContributors,
            coveringCauseIDs: Set(context.coveringCaps.keys))
    }

    public func accept(target: String, operationID: String) -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        var newRegistration: String?
        let committed = ledger.transaction { state -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> in
            guard let context = self.context(), let restoration = context.restoration else { return .failure(.denied(.unavailable)) }
            let now = FlintLedgerInstant(clock: self.clock)
            return state.acceptOpenCredit(target: target, operationID: operationID,
                coveringCaps: context.coveringCaps, targetKeys: context.targetKeys, restoration: restoration, at: now,
                blocking: { context.blockedBy }, register: { grant in
                    newRegistration = grant.activityName
                    try self.register(grant, grant.remaining(at: FlintLedgerInstant(clock: self.clock)))
                    guard grant.remaining(at: FlintLedgerInstant(clock: self.clock)) > 0 else {
                        throw FlintOpenCreditRefusal.denied(.registrationFailed)
                    }
                }, reshield: self.reshieldAndStop)
        }
        let receipt: FlintOpenCreditReceipt
        switch committed {
        case let .success(.success(value)): receipt = value
        case let .success(.failure(error)):
            if let name = newRegistration { stop(name) }
            return .failure(error)
        case .failure:
            if let name = newRegistration { stop(name) }
            return .failure(.denied(.unavailable))
        }
        // A second lock prevents a callback expiring the grant between the durable commit
        // and the native release. Process death here retains consumption and desired recovery.
        return applyCommitted(receipt)
    }

    private func applyCommitted(_ receipt: FlintOpenCreditReceipt) -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        let applied = ledger.transaction { state -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> in
            guard let context = self.context(), state.reconcileOpenCredits(
                at: FlintLedgerInstant(clock: self.clock), targetKeys: context.targetKeys, reshield: self.reshieldAndStop),
                let grant = state.openCredits.grants[receipt.grant.targetKey],
                grant.operationID == receipt.grant.operationID else { return .failure(.denied(.applying)) }
            guard context.blockedBy == nil, Set(context.coveringCaps.keys).isSubset(of: grant.coveredCauses) else {
                return .failure(.denied(.applying))
            }
            do {
                let now = FlintLedgerInstant(clock: self.clock)
                if !self.registered(grant.activityName) || Self.clockMoved(state.openCredits.carrierCheckpoints[grant.targetKey], to: now) {
                    try self.register(grant, grant.remaining(at: FlintLedgerInstant(clock: self.clock)))
                    state.openCredits.carrierCheckpoints[grant.targetKey] = now
                }
            } catch { return .failure(.denied(.applying)) }
            let fresh = FlintLedgerInstant(clock: self.clock)
            guard grant.remaining(at: fresh) > 0 else {
                _ = state.reconcileOpenCredits(at: fresh, targetKeys: context.targetKeys, reshield: self.reshieldAndStop)
                return .failure(.denied(.applying))
            }
            guard let current = self.context(), current.blockedBy == nil,
                  Set(current.coveringCaps.keys).isSubset(of: grant.coveredCauses) else {
                return .failure(.denied(.applying))
            }
            // Native store writes occur while the same lock used by expiry is held.
            do { try self.release(grant) } catch { return .failure(.denied(.applying)) }
            return .success(receipt)
        }
        guard case let .success(result) = applied else { return .failure(.denied(.applying)) }
        return result
    }

    public enum Callback: Equatable { case start, warning, end }

    /// Exact grant identity only. Early callbacks keep the logical deadline; a missed warning
    /// at carrier end is recorded as recovery rather than an on-time five-minute result.
    public func reconcile(activity: String? = nil, callback: Callback? = nil) -> Bool {
        let result = ledger.transaction { state -> Bool in
            // Expiry remains possible using the frozen target when saved rules are corrupt.
            // New acceptance still requires a readable live context above.
            let context = self.context()
            let now = FlintLedgerInstant(clock: self.clock)
            let original = activity.flatMap { name in state.openCredits.grants.values.first { $0.activityName == name } }
            guard state.reconcileOpenCredits(at: now,
                targetKeys: context?.targetKeys ?? state.openCredits.knownTargets, reshield: self.reshieldAndStop) else { return false }
            if let activity, original == nil { self.stop(activity); return true }
            if let original, state.openCredits.grants[original.targetKey] == nil,
               let elapsed = original.grantedAt.elapsedForRelease(until: now), elapsed > original.duration + 1 {
                state.remember(.init(id: original.operationID + ".expiry", kind: "openCredit.expiry",
                    result: callback == .end ? "missed_warning_recovered" : "late_reshield",
                    at: now.wall))
            }
            for grant in Array(state.openCredits.grants.values) {
                // Warnings before the elapsed deadline (including clock edits) must re-arm.
                // Do not re-register on intervalDidStart: that would create a callback loop.
                let repair = !self.registered(grant.activityName) ||
                    (grant.activityName == activity && (callback == .warning || callback == .end)) ||
                    Self.clockMoved(state.openCredits.carrierCheckpoints[grant.targetKey], to: now) ||
                    activity == nil
                do {
                    if repair {
                        try self.register(grant, grant.remaining(at: now))
                        state.openCredits.carrierCheckpoints[grant.targetKey] = now
                    }
                } catch {
                    // A committed grant whose carrier disappeared closes without refund.
                    self.reshieldAndStop(grant)
                    state.openCredits.grants[grant.targetKey] = nil
                    state.openCredits.carrierCheckpoints[grant.targetKey] = nil
                    state.remember(.init(id: grant.operationID + ".repair", kind: "openCredit.repair",
                                         result: "registration_failed_closed", at: now.wall))
                }
            }
            return true
        }
        return (try? result.get()) ?? false
    }

    private func reshieldAndStop(_ grant: FlintOpenCreditGrant) {
        reshield(grant)
        stop(grant.activityName)
    }

    private static func clockMoved(_ checkpoint: FlintLedgerInstant?, to now: FlintLedgerInstant) -> Bool {
        guard let checkpoint, let elapsed = checkpoint.elapsed(until: now) else { return true }
        return abs(now.wall.timeIntervalSince(checkpoint.wall) - elapsed) > FlintTimeIntegrity.discontinuityThreshold
    }
}
