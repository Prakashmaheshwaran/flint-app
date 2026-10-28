import Foundation

/// A completed reconciliation, persisted under its own key. It records registration evidence,
/// not proof that an extension callback delivered or a shield is visible on the device.
public struct FlintProtectionObservation: Codable, Equatable {
    public let observedAt: Date
    public let trigger: FlintProtectionTrigger
    public let domains: [String: FlintArmingHealth.DomainReport]

    public init(observedAt: Date, trigger: FlintProtectionTrigger,
                domains: [String: FlintArmingHealth.DomainReport]) {
        self.observedAt = observedAt
        self.trigger = trigger
        self.domains = domains
    }

    public func isStale(at now: Date = Date()) -> Bool {
        // A future observation after clock rollback is not fresh evidence either.
        now < observedAt || now.timeIntervalSince(observedAt) > 24 * 60 * 60
    }

    public var failures: [FlintArmingHealth.Failure] {
        domains.sorted { $0.key < $1.key }.flatMap { $0.value.failures }
    }

    private static let key = "flint.protection.lastObservation.v1"

    public static func load(from defaults: UserDefaults? = .flintGroup) -> Self? {
        guard let data = defaults?.data(forKey: key) else { return nil }
        // A legacy arming ledger has no observation timestamp/trigger: never invent them.
        return try? JSONDecoder().decode(Self.self, from: data)
    }

    public func save(to defaults: UserDefaults? = .flintGroup) {
        defaults?.set(try? JSONEncoder().encode(self), forKey: Self.key)
    }
}

/// Share this event across the app and Core CRUD paths, without touching FlintGroupStore.
extension Notification.Name {
    public static let flintProtectionConfigurationChanged = Notification.Name("flint.protection.configurationChanged")
    public static let flintProtectionReconciled = Notification.Name("flint.protection.reconciled")
}
