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
        if let last = checkpoint, let elapsed = last.elapsed(until: now),
           let released = last.elapsedForRelease(until: now) {
            // Cooldown credits only time that surely passed; recovery (a release) only ends
            // early, never late.
            cooldownRemaining = max(0, cooldownRemaining - elapsed)
            recoveryRemaining = max(0, recoveryRemaining - released)
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
        case .unknown, .pendingImport: return .unknown
        case .used: return .used(elapsed: FlintProtectionConstants.passPeriod - cooldownRemaining)
        }
    }

    /// The pass as of `now` without mutating the stored record. Raw `cooldownRemaining` /
    /// `recoveryRemaining` are only meaningful at `checkpoint`; enforcement must read them
    /// through this (or after `checkpoint(at:)`), never directly.
    public func status(at now: FlintLedgerInstant, hasActiveCommitment: Bool) -> FlintPassStatus {
        var copy = self
        copy.checkpoint(at: now)
        return FlintPassStatus(
            eligibility: FlintProtectionPolicy.passEligibility(copy.policyUsage, hasActiveCommitment: hasActiveCommitment),
            recoveryRemaining: copy.recoveryRemaining
        )
    }
}

/// The Emergency Pass as of one instant (ADR-012 §6).
public struct FlintPassStatus: Equatable, Sendable {
    public let eligibility: FlintPassEligibility
    /// Global recovery left, in elapsed seconds; 0 = not recovering.
    public let recoveryRemaining: TimeInterval
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
        if pass.lastOperationID == operationID || operation(operationID)?.kind == "pass" {
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

public enum FlintHarderCommit: Equatable, Sendable {
    case committed
    case alreadyCommitted
    case notMatured
    case nothingPending
}

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
        if var pending = entry.pending, pending.kind == kind, pending.scope == scope {
            // Same request resumed: keep its start, adopt the caller's id so the eventual
            // commit is queryable by it.
            pending.operationID = operationID
            entry.pending = pending
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

    /// Commit the operation `operationID` whose wait matured on `cause`: increments that cause's
    /// `n` exactly once. A retry after an ambiguous result (§8) finds the remembered commit and
    /// reports `.alreadyCommitted`. The record is per cause, so one operation id across a
    /// multi-cause batch increments each cause once (§4, PC-19). A retry that carries an id the
    /// wait no longer has (a resumed wait adopted a newer id) reports `.nothingPending`.
    public mutating func commitHarderWait(cause: String, operationID: String, at now: FlintLedgerInstant) -> FlintHarderCommit {
        let record = "\(operationID)#\(cause)"
        if operation(record) != nil { return .alreadyCommitted }
        guard var entry = harder[cause] else { return .nothingPending }
        dropPendingAfterReboot(&entry, at: now)
        defer { harder[cause] = entry }
        guard let pending = entry.pending, pending.operationID == operationID else { return .nothingPending }
        guard status(of: pending, at: now) == .matured else { return .notMatured }
        entry.committedCount += 1
        remember(Operation(id: record, kind: "harder.\(pending.kind)", result: "committed", at: now.wall))
        entry.pending = nil
        return .committed
    }

    /// Bound the per-occurrence map: keep `active` causes, then any with a pending wait, and
    /// drop the rest beyond `limit` (§4 counts reset at a new natural occurrence anyway).
    public mutating func pruneHarder(keeping active: Set<String>, limit: Int = 64) {
        let removable = harder.keys.filter { !active.contains($0) && harder[$0]?.pending == nil }.sorted()
        for key in removable where harder.count > limit { harder[key] = nil }
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

/// A legacy App Group value as read: absent, present, or not readable at all.
public enum FlintLegacyValue {
    case absent
    case value(Any)
    case unreadable
}

extension FlintLedgerState {
    static let legacyPassMigration = "pass.v0"
    /// The pre-ledger pass key (seconds since 1970 as a Double; absent = never used).
    public static let legacyPassKey = "flint.emergencyPass.lastUsed"

    /// Import the pre-ledger `flint.emergencyPass.lastUsed` once. Until this has run, a new
    /// ledger offers no pass (`pendingImport`), so an extension that touches the ledger first
    /// can't hand out a second pass on an established install. Absent on a readable store = the
    /// old "never used" state; unreadable, future or malformed = `unknown` (no pass until
    /// repaired), never a fresh pass. The legacy key is left untouched.
    public mutating func importLegacyPass(_ raw: FlintLegacyValue, at now: FlintLedgerInstant) {
        guard !migrations.contains(Self.legacyPassMigration) else { return }
        defer { migrations.insert(Self.legacyPassMigration) }
        guard pass.usage == .pendingImport else { return } // the ledger already knows more

        switch raw {
        case .absent:
            pass.usage = .neverUsed
        case .unreadable:
            pass.usage = .unknown
        case let .value(value):
            guard let seconds = (value as? NSNumber)?.doubleValue ?? (value as? Double), seconds.isFinite else {
                pass.usage = .unknown
                return
            }
            if seconds == 0 { pass.usage = .neverUsed; return } // the old store's "never"
            guard seconds > 0 else { pass.usage = .unknown; return }
            let wallElapsed = now.wall.timeIntervalSince(Date(timeIntervalSince1970: seconds))
            guard wallElapsed >= -FlintTimeIntegrity.discontinuityThreshold else {
                pass.usage = .unknown
                return
            }
            pass.usage = .used
            pass.cooldownRemaining = max(0, FlintProtectionConstants.passPeriod - max(0, wallElapsed))
            pass.checkpoint = now
        }
    }
}
