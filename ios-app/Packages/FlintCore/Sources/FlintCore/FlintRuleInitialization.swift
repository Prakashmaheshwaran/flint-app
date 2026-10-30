import Foundation
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
import DeviceActivity
import ManagedSettings
import CryptoKit

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

    /// An absent pre-marker domain can be a shipped user's never-used feature. Session,
    /// web, bare quota/ledger and sibling-domain state do not prove that this key was lost.
    static func hasDomainEvidence(_ key: FlintRuleStoreKey, in defaults: UserDefaults,
                                  activities: Set<String>, ledger: FlintLedgerState?, directory: URL?) -> Bool {
        if [key.original, key.rawValue + ".quarantine.adr012"].contains(where: {
            defaults.object(forKey: $0) != nil
        }) || activities.contains(where: { $0.hasPrefix(key.prefix) }) { return true }
        if case let .available(retained) = FlintRetainedProtection.load(from: defaults),
           retained.keys.contains(where: { $0.hasPrefix(key.prefix) }) { return true }
        if key == .limits {
            for name in [FlintLimitThresholds.observationKey, FlintLimitThresholds.key, FlintLimitThresholds.thresholdKey] {
                guard let object = defaults.object(forKey: name) else { continue }
                if let data = object as? Data {
                    guard let entries = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return true }
                    if !entries.isEmpty { return true }
                } else if let entries = object as? [String: Any] {
                    if !entries.isEmpty { return true }
                } else { return true }
            }
        }
        if let directory, let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) {
            let prefixes = [key.original, key.rawValue + ".quarantine.adr012"].map {
                SHA256.hash(data: Data($0.utf8)).map { String(format: "%02x", $0) }.joined() + "."
            }
            if names.contains(where: { name in name.hasSuffix(".raw") && prefixes.contains(where: name.hasPrefix) }) { return true }
        }
        guard let ledger else { return false }
        let causePrefix = key.rawValue + ":"
        if ledger.harder.contains(where: { cause, entry in
            cause.hasPrefix(causePrefix) || entry.pending?.scope.contains(where: { $0.hasPrefix(causePrefix) }) == true
        }) { return true }
        if ledger.operations.contains(where: { $0.id.contains("#" + causePrefix) }) { return true }
        if key == .limits, ledger.passLimitThresholds?.isEmpty == false { return true }
        if ledger.openCredits.grants.values.contains(where: { grant in
            grant.coveredActivities.values.contains(where: { $0.hasPrefix(key.prefix) })
                || (key == .openLimits && !grant.coveredCauses.isEmpty)
        }) { return true }
        return (ledger.emergencyRecovery?.scope.rules ?? []).contains { commitment in
            switch commitment.kind {
            case .schedule, .oneShot, .sleep: return key == .schedules
            case .timeLimit: return key == .limits
            case .openLimit: return key == .openLimits
            }
        }
    }

    private static func prepareLegacyDomains(environment env: FlintRuleReloadEnvironment) -> Bool {
        guard let defaults = env.defaults else { return false }
        let activities = Set(env.center.activities.map(\.rawValue))
        // peek is read-only and acquires no ledger lock: initialization retains lock order.
        let ledger = env.ledger().flatMap { try? $0.peek().get() }
        for key in FlintRuleStoreKey.allCases {
            guard defaults.object(forKey: key.rawValue) == nil, defaults.object(forKey: key.marker) == nil else { continue }
            guard env.acceptSave() else { return false }
            if hasDomainEvidence(key, in: defaults, activities: activities, ledger: ledger, directory: env.recoveryDirectory) {
                // Remember established ownership even if the OS later drops this registration
                // (including after a wall edit). It cannot then masquerade as a never-used feature.
                defaults.set(marker, forKey: key.marker)
                guard defaults.synchronize(), defaults.data(forKey: key.marker) == marker else { return false }
                continue
            }
            let saved: Bool
            switch key {
            case .schedules: saved = FlintDesiredRules.save([FlintScheduleRule](), key: key, to: defaults)
            case .limits: saved = FlintDesiredRules.save([FlintLimitRule](), key: key, to: defaults)
            case .openLimits: saved = FlintDesiredRules.save([FlintOpenLimitRule](), key: key, to: defaults)
            }
            guard saved else { return false }
        }
        return true
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
            let activities = Set(env.center.activities.map(\.rawValue))
            let ledger = env.ledger().flatMap { try? $0.peek().get() }
            if FlintRuleStoreKey.allCases.contains(where: {
                hasDomainEvidence($0, in: defaults, activities: activities, ledger: ledger, directory: env.recoveryDirectory)
            }) { return prepareLegacyDomains(environment: env) }
            // Existing saved evidence alone decides this path; avoid unnecessary native
            // permission queries while an established missing domain is already unavailable.
            guard !hasProtectionEvidence(in: defaults, activities: [], external: false) else {
                return prepareLegacyDomains(environment: env)
            }
            guard !hasProtectionEvidence(in: defaults, activities: Set(env.center.activities.map(\.rawValue)),
                external: env.installationEvidence()) else {
                return prepareLegacyDomains(environment: env)
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
