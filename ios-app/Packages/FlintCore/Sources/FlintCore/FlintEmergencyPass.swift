import Foundation

/// Compatibility constant. Pass authority belongs exclusively to FlintProtectionLedger and
/// FlintEmergencyPassController; no wall-clock or optional-UserDefaults availability API exists.
public enum FlintEmergencyPass {
    /// One pass per rolling 7 days after use — the contract's constant, kept in one place.
    public static let period: TimeInterval = FlintProtectionConstants.passPeriod

}
