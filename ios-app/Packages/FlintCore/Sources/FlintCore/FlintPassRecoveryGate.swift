import Foundation

/// No lock, initialization, import or write: safe inside existing policy transitions and in
/// extension callbacks. An unavailable ledger is never interpreted as an available pass or
/// permission to weaken. Applying new protection is deferred; bounded last-good state stays.
public enum FlintPassRecoveryGate: Equatable, Sendable {
    case enforcing
    case suspended(until: Date)
    case unavailable

    public static func read(ledger: FlintProtectionLedger? = FlintProtectionLedger.appGroup(),
                            at instant: FlintLedgerInstant = .init(clock: .system)) -> Self {
        guard let ledger, case let .success(state) = ledger.peek() else { return .unavailable }
        return evaluate(state, at: instant)
    }

    public static func evaluate(_ state: FlintLedgerState, at instant: FlintLedgerInstant) -> Self {
        guard state.hasValidPassRecovery else { return .unavailable }
        let status = state.pass.status(at: instant, hasActiveCommitment: true)
        guard status.eligibility != .unavailable else { return .unavailable }
        if status.isRecovering {
            guard let recovery = state.emergencyRecovery,
                  recovery.operationID == state.pass.lastOperationID else { return .unavailable }
            return .suspended(until: instant.wall.addingTimeInterval(status.recoveryRemaining))
        }
        return .enforcing
    }

    public var permitsApplication: Bool { self == .enforcing }
    public var isRecovering: Bool {
        if case .suspended = self { return true }
        return false
    }

    public var statusLabel: String {
        switch self {
        case .enforcing: return "Protection can apply"
        case let .suspended(until): return "Suspended for recovery until \(FlintEmergencyPassStatus.dateText(until))"
        case .unavailable: return "Protection state unavailable"
        }
    }
}

extension FlintLedgerState.Pass {
    var hasValidRecoveryDurations: Bool {
        cooldownRemaining.isFinite && (0...FlintProtectionConstants.passPeriod).contains(cooldownRemaining)
            && recoveryRemaining.isFinite && (0...FlintProtectionConstants.passRecovery).contains(recoveryRemaining)
            && cooldownRemaining >= recoveryRemaining
            && (checkpoint.map { $0.monotonic.isFinite && $0.monotonic >= 0 } ?? (usage != .used))
    }
}

extension FlintLedgerState {
    var hasValidPassRecovery: Bool {
        guard pass.hasValidRecoveryDurations,
              passLimitThresholds?.values.allSatisfy(\.isValid) ?? true else { return false }
        if let recovery = emergencyRecovery {
            return recovery.isValid && pass.usage == .used && pass.lastOperationID == recovery.operationID
                && passLimitThresholds != nil && passSuppressedFocusOwners != nil
        }
        // Legacy imported usage has no operation ID. A missing modern outbox is corruption,
        // including after reboot/checkpoint would otherwise turn its recovery into zero.
        return pass.lastOperationID == nil && pass.recoveryRemaining == 0
    }
}
