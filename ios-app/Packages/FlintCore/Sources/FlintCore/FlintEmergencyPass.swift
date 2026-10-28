import Foundation

/// The free Emergency Pass that lets a user end a Hardcore (Deep Focus) block early.
///
/// Superseded by ADR-012 §6: eligibility belongs on the elapsed-time ledger
/// (`FlintProtectionPolicy.passEligibility`), where missing or corrupt state is unavailable
/// rather than a fresh pass. This wall-clock check remains only until lane B moves the pass
/// onto that ledger; do not add new callers.
public enum FlintEmergencyPass {
    /// One pass per rolling 7 days after use — the contract's constant, kept in one place.
    public static let period: TimeInterval = FlintProtectionConstants.passPeriod

    /// Available if it was never used, or the period has elapsed since the last use.
    public static func isAvailable(lastUsed: Date?, now: Date = Date()) -> Bool {
        guard let lastUsed else { return true }
        return now.timeIntervalSince(lastUsed) >= period
    }

    /// When the next pass becomes available (nil if available now / never used).
    public static func nextAvailable(lastUsed: Date?) -> Date? {
        guard let lastUsed else { return nil }
        return lastUsed.addingTimeInterval(period)
    }
}
