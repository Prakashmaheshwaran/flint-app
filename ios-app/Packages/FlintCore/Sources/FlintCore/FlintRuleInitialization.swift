import Foundation
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
import DeviceActivity
import ManagedSettings

/// R-22 / PC-37,62,70,71: absence is empty only with positive new-install evidence.
/// The receipt is written before all three empty domains, and completed only after their
/// arrays and markers read back. A partial initialization cannot authorize policy reads.
enum FlintRuleInitialization {
    static let receiptKey = "flint.rules.freshInstall.adr012"
    enum Receipt: Equatable { case absent, preparing, complete, invalid }
    private struct Record: Decodable { let schema: Int; let phase: String }
    private static let preparing = Data("{\"schema\":1,\"phase\":\"preparing\"}".utf8)
    private static let complete = Data("{\"schema\":1,\"phase\":\"complete\"}".utf8)
    private static let empty = Data("[]".utf8)
    private static let marker = Data("{\"schema\":1}".utf8)

    static func receipt(in defaults: UserDefaults) -> Receipt {
        guard let object = defaults.object(forKey: receiptKey) else { return .absent }
        guard let data = object as? Data,
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              Set(fields.keys) == ["schema", "phase"],
              let record = try? JSONDecoder().decode(Record.self, from: data), record.schema == 1 else { return .invalid }
        switch record.phase { case "preparing": return .preparing; case "complete": return .complete; default: return .invalid }
    }

    static func hasProtectionEvidence(in defaults: UserDefaults, activities: Set<String>,
                                      external: Bool, ignoring: Set<String> = []) -> Bool {
        if external || activities.contains(where: { $0.hasPrefix("flint.") }) { return true }
        let prefixes = FlintRuleStoreKey.allCases.map(\.rawValue) + [
            "flint.limit.", "flint.open.", "flint.ledger.", "flint.activeSession", "flint.selection",
            "flint.sleepConfig", "flint.webRestrictions", "flint.focusFilterConfig", "flint.pomodoroConfig",
            "flint.emergencyPass", "flint.openLimit.state", "flint.appGroups", "flint.sessions", "flint.pin.",
            "flint.protection.", "flint.armingHealth"
        ]
        return defaults.dictionaryRepresentation().keys.contains { key in
            !ignoring.contains(key) && (key == receiptKey || prefixes.contains(where: key.hasPrefix))
        }
    }

    /// Native APIs cannot enumerate arbitrary named ManagedSettings stores. Inspect the known
    /// primary/legacy stores; any registered Flint activity or retained definition is separately
    /// evidence, including registrations whose desired-state key disappeared.
    static func externalEvidence() -> Bool {
        if let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: FlintGroupStore.appGroupID),
           ["FlintLedger", "FlintRules/Recovery"].contains(where: {
               FileManager.default.fileExists(atPath: container.appendingPathComponent($0).path)
           }) { return true }
        return ["flint", "flint.web"].contains { name in
            let store = ManagedSettingsStore(named: .init(name))
            return store.shield.applications != nil || store.shield.applicationCategories != nil
                || store.shield.webDomains != nil || store.shield.webDomainCategories != nil
                || store.webContent.blockedByFilter != nil || store.application.denyAppRemoval == true
        }
    }

    /// Must hold the initialization lock, before any session/pass operation can create ledger
    /// evidence. Existing installations are left untouched; their missing domains fail closed.
    static func prepare(environment env: FlintRuleReloadEnvironment) -> Bool {
        guard let defaults = env.defaults, defaults.synchronize() else { return false }
        let state = receipt(in: defaults)
        switch state {
        case .complete: return true
        case .invalid: return false
        case .absent, .preparing: break
        }
        let owned = Set(FlintRuleStoreKey.allCases.flatMap { [$0.rawValue, $0.marker] } + [receiptKey])
        if state == .absent {
            // Existing saved evidence alone decides this path; avoid unnecessary native
            // permission queries while an established missing domain is already unavailable.
            guard !hasProtectionEvidence(in: defaults, activities: [], external: false) else { return true }
            guard !hasProtectionEvidence(in: defaults, activities: Set(env.center.activities.map(\.rawValue)),
                external: env.installationEvidence()) else {
                return true // established installation: no initialization authority
            }
            guard env.acceptSave() else { return false }
            defaults.set(preparing, forKey: receiptKey)
            guard defaults.synchronize(), defaults.data(forKey: receiptKey) == preparing else { return false }
        } else {
            guard !hasProtectionEvidence(in: defaults, activities: Set(env.center.activities.map(\.rawValue)),
                external: env.installationEvidence(), ignoring: owned) else { return false }
        }
        for key in FlintRuleStoreKey.allCases {
            // Only this receipt's known empty partial after-image can be resumed.
            if let object = defaults.object(forKey: key.rawValue), object as? Data != empty { return false }
            if let object = defaults.object(forKey: key.marker), object as? Data != marker { return false }
        }
        guard env.acceptSave() else { return false }
        for key in FlintRuleStoreKey.allCases {
            defaults.set(empty, forKey: key.rawValue); defaults.set(marker, forKey: key.marker)
        }
        guard defaults.synchronize(), FlintRuleStoreKey.allCases.allSatisfy({
            defaults.data(forKey: $0.rawValue) == empty && defaults.data(forKey: $0.marker) == marker
        }) else { return false }
        defaults.set(complete, forKey: receiptKey)
        guard defaults.synchronize(), defaults.data(forKey: receiptKey) == complete else {
            defaults.set(preparing, forKey: receiptKey); defaults.synchronize()
            return false
        }
        return true
    }
}
#endif
