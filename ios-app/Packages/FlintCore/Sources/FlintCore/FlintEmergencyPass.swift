import Foundation

/// The free weekly "get out of jail" pass that lets a user end a Hardcore (Deep Focus) block
/// early. Opal paywalls this; Flint gives it away. Pure, deterministic logic so it's easy to test.
public enum FlintEmergencyPass {
    /// One pass per rolling 7 days.
    public static let period: TimeInterval = 7 * 24 * 60 * 60

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
