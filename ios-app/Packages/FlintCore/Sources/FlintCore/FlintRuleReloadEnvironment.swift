import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Reconciliation distinguishes a decoded empty array from unavailable persisted intent.
/// Lives outside FlintGroupStore so its legacy convenience readers stay source compatible.
enum FlintDesiredRules {
    typealias Key = FlintRuleStoreKey
    private struct Marker: Codable { let schema: Int }

    static func load<Rule: FlintGatedRule>(_ type: Rule.Type, key: Key, from defaults: UserDefaults?,
                                         at now: Date = Date(), migrating: Bool = true) -> [Rule]? {
        guard let defaults else { return nil }
        defaults.synchronize()
        let marker = defaults.object(forKey: key.marker)
        if let marker {
            guard let data = marker as? Data,
                  let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  Set(fields.keys) == ["schema"],
                  let decoded = try? JSONDecoder().decode(Marker.self, from: data), decoded.schema == 1 else { return nil }
        }
        guard let object = defaults.object(forKey: key.rawValue) else {
            // A deleted key on an established domain must never mean "remove all activities".
            return marker == nil && defaults.object(forKey: key.original) == nil
                && defaults.object(forKey: key.rawValue + ".quarantine.adr012") == nil ? [] : nil
        }
        guard let data = object as? Data,
              let objects = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]],
              objects.allSatisfy({ Set($0.keys).isSubset(of: Rule.storedKeys) }),
              var rules = try? JSONDecoder().decode([Rule].self, from: data),
              Set(rules.map(\.id)).count == rules.count,
              // The marker is written only after every record has current metadata. Losing
              // that metadata later is corruption, never another import of legacy rights.
              rules.allSatisfy({ $0.validationIssue == nil && (marker == nil || $0.protection != nil) }) else {
            if migrating, defaults.object(forKey: key.rawValue + ".quarantine.adr012") == nil {
                defaults.set(object, forKey: key.rawValue + ".quarantine.adr012")
            }
            return nil
        }
        guard migrating else { return rules }
        var migrated = false
        for index in rules.indices where rules[index].protection == nil {
            let occurrence = rules[index].templateOccurrence(at: now)
            var protection = FlintRuleProtection(legacyRightsUntil: occurrence?.end, usesLegacyActivityName: true,
                requiresTierReview: rules[index].legacyTierNeedsReview)
            protection.occurrence = occurrence
            rules[index].protection = protection
            migrated = true
        }
        if migrated || marker == nil {
            // Backup first. A crash at any checkpoint preserves source bytes and cannot
            // grandfather a later occurrence a second time (PC-70/71).
            if defaults.object(forKey: key.original) == nil { defaults.set(data, forKey: key.original) }
            guard save(rules, key: key, to: defaults) else { return nil }
        }
        return rules
    }

    static func unavailable(_ domain: String, previous: FlintArmingHealth) -> FlintArmingHealth.DomainReport {
        .init(enabled: previous.domains[domain]?.enabled ?? 0, attempted: 0, armed: 0,
              failures: [.init(activityName: domain,
                reason: "Saved rules are unavailable and need recovery. Existing finite protection was preserved; Flint did not change registrations or shields. Restore a known-good device backup or try again after updating Flint. Your saved data was retained for recovery.")])
    }

    static func save<Rule: FlintGatedRule>(_ rules: [Rule], key: Key, to defaults: UserDefaults?,
        encode: ([Rule]) throws -> Data = { try JSONEncoder().encode($0) }) -> Bool {
        guard let defaults, Set(rules.map(\.id)).count == rules.count,
              rules.allSatisfy({ $0.validationIssue == nil && $0.protection != nil }),
              let data = try? encode(rules) else { return false }
        defaults.set(data, forKey: key.rawValue)
        guard defaults.synchronize(), defaults.data(forKey: key.rawValue) == data else { return false }
        defaults.set(try? JSONEncoder().encode(Marker(schema: 1)), forKey: key.marker)
        return defaults.synchronize()
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
    var reconcileOpenCredits: () -> Void = {}
    var clock: FlintClock = .system
    var ledger: () -> FlintProtectionLedger? = { .appGroup() }
    // Tests use an isolated temporary lock. The production environment requires the App Group.
    var lockDirectory: URL? = FileManager.default.temporaryDirectory.appendingPathComponent("FlintRuleTestLocks")
    var acceptSave: () -> Bool = { true }
    var validateSelection: (FamilyActivitySelection, FlintSelectionContext) -> FlintSelectionIssue? = {
        FlintSelectionValidation.issue(for: $0, context: $1)
    }
    var applyRuleShield: (FlintScheduleRule) -> Void = { FlintRuleShield.apply($0) }
    var applyLimitShield: (FlintLimitRule) -> Void = { FlintRuleShield.apply($0) }
    var timeLimitIsShielded: (FlintLimitRule, Date) -> Bool = { limit, _ in
        let store = ManagedSettingsStore(named: .init(limit.storeName))
        return store.shield.applications != nil || store.shield.applicationCategories != nil
            || store.shield.webDomains != nil || store.shield.webDomainCategories != nil
    }
    /// Production binds to the shared session-transition lock; tests can simulate busy scope.
    var withTransitionBoundary: (() -> Void) -> Bool = { body in body(); return true }
    var markLimitThreshold: (FlintLimitRule, Date) -> Void = { _, _ in }
    var clearLimitThreshold: (String) -> Void = { _ in }
    var publishStatus: () -> Void = {}

    func rulesForDisplay<Rule: FlintGatedRule>(_ type: Rule.Type) -> [Rule]? {
        if let read = FlintRuleStoreLock.withLock(environment: self, {
            FlintDesiredRules.load(type, key: Rule.domain, from: defaults, at: clock.now())
        }) { return read }
        // A busy transition is not evidence that the saved blob is corrupt. A display-only
        // peek may show its valid revision; commits still acquire the boundary and re-check it.
        return FlintDesiredRules.load(type, key: Rule.domain, from: defaults, at: clock.now(), migrating: false)
    }

    static var live: Self {
        let group = FlintGroupStore()
        return .init(defaults: .flintGroup, center: DeviceActivityCenter(),
            clearStore: { ManagedSettingsStore(named: .init($0)).clearAllSettings() },
            applyOpenShield: { FlintOpenLimitEnforcer.applyShield(for: $0) },
            record: { domain, report in
                guard let group else { return }
                group.saveArmingHealth(group.loadArmingHealth().replacing(domain, with: report))
            }, loadHealth: { group?.loadArmingHealth() ?? .init() },
            reconcileOpenCredits: { FlintOpenCredit.reconcile() },
            lockDirectory: FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: FlintGroupStore.appGroupID)?
                .appendingPathComponent("FlintRules", isDirectory: true),
            timeLimitIsShielded: { limit, now in
                let store = ManagedSettingsStore(named: .init(limit.storeName))
                return FlintLimitThresholds.hasReached(limit, at: now, in: .flintGroup)
                    || store.shield.applications != nil || store.shield.applicationCategories != nil
                    || store.shield.webDomains != nil || store.shield.webDomainCategories != nil
            }, withTransitionBoundary: { body in
                // One cross-process order: session transition → rule domain → ledger.
                FlintSessionController().withTransition { body(); return true } ?? false
            }, markLimitThreshold: { FlintLimitThresholds.markReached($0, at: $1) },
            clearLimitThreshold: { FlintLimitThresholds.clear($0) },
            publishStatus: { FlintProtectionStatus.publishAfterCommit() })
    }
}
#endif
