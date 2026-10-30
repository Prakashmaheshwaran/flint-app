import Foundation

/// R-08 phase 1: an unwired, already reconciled commitment snapshot. Phase 2 projects
/// sessions and materialized rule occurrences under the shared transition/ledger locks.
/// This is commitment activity, not visible shielding: planned rests and unused credits
/// do not deactivate an owner. An inactive template is never an active occurrence.
public struct FlintRemovalGuardOwner: Equatable, Sendable {
    public enum Registration: Equatable, Sendable {
        /// The exact occurrence/revision's required registration was observed.
        case observed
        /// A readable inventory proves registration absent or failed.
        case notObserved
        /// Inventory could not be read. Never infer absence from an error.
        case unavailable
    }

    /// nil means unreadable/unknown owner state, not an absent commitment.
    public let cause: FlintCauseState?
    public let registration: Registration
    /// Frozen opt-in at acceptance. nil is a valid legacy/migrated absence, equivalent
    /// to false; adapters must not substitute the current preference for this field.
    public let removalGuard: Bool?
    /// A finite, same-boot deadline projected from reconciled logical remaining time.
    /// Wall is a display estimate only; never compare it with now to expire an owner.
    public let logicalEnd: FlintLedgerInstant?
    /// false means known time uncertainty (§9): this owner cannot arm a guard.
    public let timeKnown: Bool
    /// true only after durable pass recovery covers this owner; nil means unavailable
    /// pass state. Include rule occurrences starting inside the recovery window (§6).
    public let passRecovered: Bool?

    public init(cause: FlintCauseState?, registration: Registration, removalGuard: Bool?,
                logicalEnd: FlintLedgerInstant?, timeKnown: Bool, passRecovered: Bool?) {
        self.cause = cause
        self.registration = registration
        self.removalGuard = removalGuard
        self.logicalEnd = logicalEnd
        self.timeKnown = timeKnown
        self.passRecovered = passRecovered
    }
}
