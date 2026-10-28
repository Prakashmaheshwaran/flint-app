import Foundation

// The ledger's protection operations (ADR-012 §4, §6, §9, §12) as pure mutations of
// `FlintLedgerState`, so the same rules run in the host app and both extensions inside one
// `FlintProtectionLedger.transaction`, and every boundary is unit-tested without the file.

// MARK: - Emergency Pass (§6, §9)

public struct FlintPassReceipt: Equatable, Sendable {
    public let operationID: String
    /// Recovery remaining at the read, in elapsed seconds.
    public let recoveryRemaining: TimeInterval
    /// True when this call found an already committed consumption with the same operation id.
    public let replayed: Bool
}

extension FlintLedgerState.Pass {

    /// Advance remaining cooldown and recovery to `now` (§9): on the same boot by elapsed
    /// monotonic time; across a reboot (or unknown boot) credit nothing and re-anchor, so a
    /// powered-off interval never shortens the cooldown.
    public mutating func checkpoint(at now: FlintLedgerInstant) {
        if let last = checkpoint, let elapsed = last.elapsed(until: now) {
            cooldownRemaining = max(0, cooldownRemaining - elapsed)
            recoveryRemaining = max(0, recoveryRemaining - elapsed)
        } else if checkpoint != nil {
            // Reboot: recovery access ends conservatively (§9), cooldown keeps its remainder.
            recoveryRemaining = 0
        }
        checkpoint = now
    }

    /// What `FlintProtectionPolicy.passEligibility` needs, after `checkpoint(at:)`.
    public var policyUsage: FlintPassUsage {
        switch usage {
        case .neverUsed: return .neverUsed
        case .unknown: return .unknown
        case .used: return .used(elapsed: FlintProtectionConstants.passPeriod - cooldownRemaining)
        }
    }

    public var isRecovering: Bool { recoveryRemaining > 0 }
}

extension FlintLedgerState {

    /// Consume the pass and start global recovery in one committed mutation (§6 "consume then
    /// release"): callers release protection only after the transaction holding this commits.
    /// Idempotent by `operationID`.
    public mutating func consumePass(
        operationID: String,
        hasActiveCommitment: Bool,
        at now: FlintLedgerInstant
    ) -> Result<FlintPassReceipt, FlintPassRefusal> {
        pass.checkpoint(at: now)
        if pass.lastOperationID == operationID {
            return .success(FlintPassReceipt(operationID: operationID,
                                             recoveryRemaining: pass.recoveryRemaining, replayed: true))
        }
        switch FlintProtectionPolicy.passEligibility(pass.policyUsage, hasActiveCommitment: hasActiveCommitment) {
        case .available:
            pass.usage = .used
            pass.cooldownRemaining = FlintProtectionConstants.passPeriod
            pass.recoveryRemaining = FlintProtectionConstants.passRecovery
            pass.lastOperationID = operationID
            remember(Operation(id: operationID, kind: "pass", result: "consumed", at: now.wall))
            return .success(FlintPassReceipt(operationID: operationID,
                                             recoveryRemaining: pass.recoveryRemaining, replayed: false))
        case let .coolingDown(remaining):
            return .failure(.coolingDown(remaining: remaining))
        case .nothingToRecover:
            return .failure(.nothingToRecover)
        case .unavailable:
            return .failure(.unavailable)
        }
    }

    /// "Resume protection now" (§6 step 4): truncate recovery without refunding the pass.
    public mutating func endRecovery(at now: FlintLedgerInstant) {
        pass.checkpoint(at: now)
        pass.recoveryRemaining = 0
    }
}

public enum FlintPassRefusal: Error, Equatable, Sendable {
    case coolingDown(remaining: TimeInterval)
    case nothingToRecover
    case unavailable
}

// MARK: - Harder friction (§4, §9)

public enum FlintHarderWaitStatus: Equatable, Sendable {
    case none
    case waiting(remaining: TimeInterval)
    /// The wait matured; an explicit final confirmation may now commit the operation.
    case matured
}

extension FlintLedgerState {

    /// Request (or resume) the persisted wait for one weakening operation on a Harder cause.
    /// The same kind and scope resumes the existing wait; anything else replaces it with a new
    /// full wait. Never increments the committed count.
    @discardableResult
    public mutating func requestHarderWait(
        cause: String,
        kind: String,
        scope: [String],
        operationID: String,
        at now: FlintLedgerInstant
    ) -> FlintHarderWaitStatus {
        var entry = harder[cause] ?? Harder()
        dropPendingAfterReboot(&entry, at: now)
        if let pending = entry.pending, pending.kind == kind, pending.scope == scope {
            harder[cause] = entry
            return status(of: pending, at: now)
        }
        let wait = FlintProtectionPolicy.harderWait(forCount: entry.committedCount)
        let pending = PendingWait(operationID: operationID, kind: kind, scope: scope, requestedAt: now, wait: wait)
        entry.pending = pending
        harder[cause] = entry
        return status(of: pending, at: now)
    }

    public mutating func harderWaitStatus(cause: String, at now: FlintLedgerInstant) -> FlintHarderWaitStatus {
        guard var entry = harder[cause] else { return .none }
        dropPendingAfterReboot(&entry, at: now)
        harder[cause] = entry
        guard let pending = entry.pending else { return .none }
        return status(of: pending, at: now)
    }

    /// Cancellation spends nothing and never resets `n` (§4).
    public mutating func cancelHarderWait(cause: String) {
        harder[cause]?.pending = nil
    }

    /// Commit the operation whose wait matured: increments `n` exactly once. Idempotent by
    /// the pending wait's operation id.
    public mutating func commitHarderWait(cause: String, at now: FlintLedgerInstant) -> Bool {
        guard var entry = harder[cause] else { return false }
        dropPendingAfterReboot(&entry, at: now)
        guard let pending = entry.pending, status(of: pending, at: now) == .matured else {
            harder[cause] = entry
            return false
        }
        if operation(pending.operationID) == nil {
            entry.committedCount += 1
            remember(Operation(id: pending.operationID, kind: "harder.\(pending.kind)", result: "committed", at: now.wall))
        }
        entry.pending = nil
        harder[cause] = entry
        return true
    }

    /// A natural new occurrence starts friction from zero (§4); a rename/toggle does not.
    public mutating func resetHarder(cause: String) {
        harder[cause] = nil
    }

    private func status(of pending: PendingWait, at now: FlintLedgerInstant) -> FlintHarderWaitStatus {
        guard let elapsed = pending.requestedAt.elapsed(until: now) else { return .none }
        return elapsed >= pending.wait ? .matured : .waiting(remaining: pending.wait - elapsed)
    }

    /// §9: a reboot cancels a pending confirmation without incrementing `n`; never infer that
    /// an unobserved wait completed.
    private func dropPendingAfterReboot(_ entry: inout Harder, at now: FlintLedgerInstant) {
        if let pending = entry.pending, pending.requestedAt.elapsed(until: now) == nil {
            entry.pending = nil
        }
    }
}

// MARK: - Legacy import (§12)

extension FlintLedgerState {
    static let legacyPassMigration = "pass.v0"

    /// Import the pre-ledger `flint.emergencyPass.lastUsed` (seconds since 1970, a Double; the
    /// old store treated absence as "never used"). Runs once; the legacy key is left untouched.
    /// A future or malformed value is `unknown` (no pass until repaired), never a fresh pass.
    public mutating func importLegacyPass(lastUsed raw: Any?, at now: FlintLedgerInstant) {
        guard !migrations.contains(Self.legacyPassMigration) else { return }
        defer { migrations.insert(Self.legacyPassMigration) }
        guard pass.lastOperationID == nil, pass.usage == .neverUsed else { return } // ledger already knows more

        switch raw {
        case nil:
            pass.usage = .neverUsed
        case let seconds as Double where seconds > 0:
            let used = Date(timeIntervalSince1970: seconds)
            let wallElapsed = now.wall.timeIntervalSince(used)
            guard wallElapsed >= -FlintTimeIntegrity.discontinuityThreshold else {
                pass.usage = .unknown
                return
            }
            pass.usage = .used
            pass.cooldownRemaining = max(0, FlintProtectionConstants.passPeriod - max(0, wallElapsed))
            pass.checkpoint = now
        case let seconds as Double where seconds == 0:
            pass.usage = .neverUsed // the old store wrote 0 for "never"
        default:
            pass.usage = .unknown
        }
    }
}
