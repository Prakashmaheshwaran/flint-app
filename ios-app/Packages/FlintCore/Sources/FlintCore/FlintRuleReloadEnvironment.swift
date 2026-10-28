import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Reconciliation distinguishes a decoded empty array from unavailable persisted intent.
/// Lives outside FlintGroupStore so its legacy convenience readers stay source compatible.
enum FlintDesiredRules {
    typealias Key = FlintRuleStoreKey

    static func load<Rule: Decodable>(_ type: Rule.Type, key: Key, from defaults: UserDefaults?) -> [Rule]? {
        guard let defaults else { return nil }
        guard let object = defaults.object(forKey: key.rawValue) else { return [] }
        guard let data = object as? Data else { return nil }
        return try? JSONDecoder().decode([Rule].self, from: data)
    }

    static func unavailable(_ domain: String, previous: FlintArmingHealth) -> FlintArmingHealth.DomainReport {
        .init(enabled: previous.domains[domain]?.enabled ?? 0, attempted: 0, armed: 0,
              failures: [.init(activityName: domain,
                reason: "Saved rules are unavailable. Existing protection was preserved; Flint did not change registrations or shields.")])
    }

    static func save<Rule: Encodable>(_ rules: [Rule], key: Key, to defaults: UserDefaults?,
        encode: ([Rule]) throws -> Data = { try JSONEncoder().encode($0) }) -> Bool {
        guard let defaults, let data = try? encode(rules) else { return false }
        defaults.set(data, forKey: key.rawValue)
        return true
    }

    static func saveFailure(_ domain: String, previous: FlintArmingHealth) -> FlintArmingHealth.DomainReport {
        .init(enabled: previous.domains[domain]?.enabled ?? 0, attempted: 0, armed: 0,
              failures: [.init(activityName: domain,
                reason: "Changes could not be saved. Existing saved rules and protection were preserved.")])
    }
}

struct FlintRuleReloadEnvironment {
    var defaults: UserDefaults?
    var center: any FlintActivityRegistering
    var clearStore: (String) -> Void
    var applyOpenShield: (FlintOpenLimitRule) -> Void
    var record: (String, FlintArmingHealth.DomainReport) -> Void
    var loadHealth: () -> FlintArmingHealth = { .init() }

    static var live: Self {
        let group = FlintGroupStore()
        return .init(defaults: .flintGroup, center: DeviceActivityCenter(),
            clearStore: { ManagedSettingsStore(named: .init($0)).clearAllSettings() },
            applyOpenShield: { FlintOpenLimitEnforcer.applyShield(for: $0) },
            record: { domain, report in
                guard let group else { return }
                group.saveArmingHealth(group.loadArmingHealth().replacing(domain, with: report))
            }, loadHealth: { group?.loadArmingHealth() ?? .init() })
    }
}
#endif
