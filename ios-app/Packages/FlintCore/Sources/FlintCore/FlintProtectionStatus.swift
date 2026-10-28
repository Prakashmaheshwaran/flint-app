import Foundation

/// Read-only access to the advisory, privacy-safe protection projection.
/// Enforcement decisions must consult the authoritative protection state instead.
public enum FlintProtectionStatus {
    public static let storageKey = "flint.status.v1"
    public static let changedNotification = "com.flint.peakfocus.status.changed"
    public static let widgetKind = "FlintProtectionWidget"

    public static func load(now: Date = Date(), from defaults: UserDefaults? = .flintGroup)
        -> FlintProtectionStatusSnapshot? {
        guard let data = defaults?.data(forKey: storageKey) else { return nil }
        return decode(data, now: now)
    }

    public static func decode(_ data: Data, now: Date) -> FlintProtectionStatusSnapshot? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        guard let snapshot = try? decoder.decode(FlintProtectionStatusSnapshot.self, from: data),
              snapshot.version == 1, snapshot.state != .unavailable,
              snapshot.observedAt <= now,
              let validUntil = snapshot.validUntil,
              now <= validUntil, snapshot.observedAt <= validUntil else { return nil }
        return snapshot
    }
}
