import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Shared host/shield/monitor adapter for bounded, explicit Open credits.
public enum FlintOpenCredit {
    public static func preview(for token: ApplicationToken) -> FlintOpenCreditPreview {
        preview(target: FlintOpenLimit.key(for: token), blockedBy: { blocking(application: token) })
    }
    public static func preview(for token: WebDomainToken) -> FlintOpenCreditPreview {
        preview(target: FlintOpenLimit.key(for: token), blockedBy: { blocking(webDomain: token) })
    }
    public static func accept(for token: ApplicationToken, operationID: String = UUID().uuidString)
        -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        accept(target: FlintOpenLimit.key(for: token), operationID: operationID, blockedBy: { blocking(application: token) })
    }
    public static func accept(for token: WebDomainToken, operationID: String = UUID().uuidString)
        -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        accept(target: FlintOpenLimit.key(for: token), operationID: operationID, blockedBy: { blocking(webDomain: token) })
    }

    public static func isCarrier(_ name: String) -> Bool {
        let prefix = "flint.open."
        let suffix = name.dropFirst(prefix.count)
        return name.hasPrefix(prefix) && suffix.count == 16 && suffix.allSatisfy { "0123456789abcdef".contains($0) }
    }

    /// Host launch/foreground, and the three monitor boundary callbacks, share this entry.
    @discardableResult
    public static func reconcile(activity: String? = nil, callback: FlintOpenCreditService.Callback? = nil) -> Bool {
        guard activity == nil || isCarrier(activity!) else { return false }
        let result = service(target: nil, blockedBy: { nil })?.reconcile(activity: activity, callback: callback) ?? false
        // Also closes pre-upgrade unbounded releases; only this domain's saved targets are
        // touched. applyShield excludes verifiable live grants under the same ledger lock.
        if let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup) {
            for rule in rules where rule.enabled { applyShield(for: rule) }
        }
        return result
    }

    static func importLegacy(into state: inout FlintLedgerState, defaults: UserDefaults,
                             at now: FlintLedgerInstant, calendar: Calendar) {
        guard !state.migrations.contains(FlintLedgerState.legacyOpenCreditMigration) else { return }
        let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults)
        let raw = defaults.object(forKey: FlintLedgerState.legacyOpenCreditKey)
        state.importLegacyOpenCredits(raw.map(FlintLegacyValue.value) ?? .absent,
            targetKeys: rules.map(targetKeys), established: rules == nil || rules?.isEmpty == false,
            at: now, calendar: calendar)
    }

    /// Preserve a live grant through reloads; close legacy unbounded releases by applying
    /// the full selection when there is no verifiable ledger grant. Never clear categories.
    static func applyShield(for rule: FlintOpenLimitRule) {
        guard let ledger = FlintProtectionLedger.appGroup() else { apply(rule, grants: [:]); return }
        let result = ledger.transaction { state -> Void in
            let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup)
            let valid = state.reconcileOpenCredits(at: FlintLedgerInstant(clock: .system),
                targetKeys: rules.map(targetKeys) ?? [], reshield: reshield)
            apply(rule, grants: valid ? state.openCredits.grants : [:])
        }
        if case .failure = result { apply(rule, grants: [:]) }
    }

    private static func preview(target: String, blockedBy: @escaping () -> FlintOpenCreditBlock?) -> FlintOpenCreditPreview {
        service(target: target, blockedBy: blockedBy)?.preview(target: target) ??
            .init(remaining: nil, cap: nil, blockedBy: .unavailable)
    }
    private static func accept(target: String, operationID: String, blockedBy: @escaping () -> FlintOpenCreditBlock?)
        -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        guard let service = service(target: target, blockedBy: blockedBy) else { return .failure(.denied(.unavailable)) }
        return service.accept(target: target, operationID: operationID)
    }

    private static func service(target: String?, blockedBy: @escaping () -> FlintOpenCreditBlock?) -> FlintOpenCreditService? {
        guard let ledger = FlintProtectionLedger.appGroup() else { return nil }
        let center = DeviceActivityCenter()
        return FlintOpenCreditService(ledger: ledger, clock: .system, context: {
            guard let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup),
                  Set(rules.map(\.id)).count == rules.count else { return nil }
            let covering = rules.filter { rule in rule.enabled && target.map { keys(for: rule).contains($0) } == true }
            return .init(coveringCaps: Dictionary(uniqueKeysWithValues: covering.map { ($0.id, $0.opensAllowed) }),
                         targetKeys: targetKeys(rules), blockedBy:
                            target == "flint.openLimit.unencodable" ? .identityNeedsReselection : blockedBy())
        }, register: { grant, remaining in
            guard remaining > 0 else { throw FlintOpenCreditRefusal.denied(.unavailable) }
            let plan = FlintOpenCreditCarrierPlan(start: Date(), remaining: remaining)
            let schedule = DeviceActivitySchedule(intervalStart: plan.intervalStart, intervalEnd: plan.intervalEnd,
                                                   repeats: false, warningTime: plan.warningTime)
            try center.startMonitoring(DeviceActivityName(grant.activityName), during: schedule)
            guard center.activities.contains(DeviceActivityName(grant.activityName)),
                  center.schedule(for: DeviceActivityName(grant.activityName)) != nil else {
                throw FlintOpenCreditRefusal.denied(.registrationFailed)
            }
        }, registered: { name in
            center.activities.contains(DeviceActivityName(name)) && center.schedule(for: DeviceActivityName(name)) != nil
        }, release: release, reshield: reshield, stop: { FlintScheduling.stopMonitoring([$0]) })
    }

    private static func targetKeys(_ rules: [FlintOpenLimitRule]) -> Set<String> {
        rules.reduce(into: Set<String>()) { $0.formUnion(keys(for: $1)) }
    }
    private static func keys(for rule: FlintOpenLimitRule) -> Set<String> {
        Set(rule.selection.applicationTokens.map { FlintOpenLimit.key(for: $0) })
            .union(rule.selection.webDomainTokens.map { FlintOpenLimit.key(for: $0) })
    }
    private static func release(_ grant: FlintOpenCreditGrant) {
        guard let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup) else { return }
        for rule in rules where rule.enabled && grant.coveredCauses.contains(rule.id) {
            let store = ManagedSettingsStore(named: .init(rule.storeName))
            var apps = store.shield.applications ?? []
            var domains = store.shield.webDomains ?? []
            apps = apps.filter { FlintOpenLimit.key(for: $0) != grant.targetKey }
            domains = domains.filter { FlintOpenLimit.key(for: $0) != grant.targetKey }
            store.shield.applications = apps.isEmpty ? nil : apps
            store.shield.webDomains = domains.isEmpty ? nil : domains
        }
    }
    private static func reshield(_ grant: FlintOpenCreditGrant) {
        guard let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup) else { return }
        for rule in rules where rule.enabled {
            let store = ManagedSettingsStore(named: .init(rule.storeName))
            let apps = rule.selection.applicationTokens.filter { FlintOpenLimit.key(for: $0) == grant.targetKey }
            let domains = rule.selection.webDomainTokens.filter { FlintOpenLimit.key(for: $0) == grant.targetKey }
            if !apps.isEmpty { store.shield.applications = (store.shield.applications ?? []).union(apps) }
            if !domains.isEmpty { store.shield.webDomains = (store.shield.webDomains ?? []).union(domains) }
        }
    }
    private static func apply(_ rule: FlintOpenLimitRule, grants: [String: FlintOpenCreditGrant]) {
        let store = ManagedSettingsStore(named: .init(rule.storeName))
        let now = FlintLedgerInstant(clock: .system)
        func covered(_ key: String) -> Bool {
            guard let grant = grants[key], grant.coveredCauses.contains(rule.id), grant.remaining(at: now) > 0,
                  DeviceActivityCenter().schedule(for: DeviceActivityName(grant.activityName)) != nil else { return false }
            return true
        }
        let apps = rule.selection.applicationTokens.filter { !covered(FlintOpenLimit.key(for: $0)) }
        let domains = rule.selection.webDomainTokens.filter { !covered(FlintOpenLimit.key(for: $0)) }
        store.shield.applications = apps.isEmpty ? nil : apps
        store.shield.webDomains = domains.isEmpty ? nil : domains
        // Unsupported legacy Open categories remain saved but unarmed; no category release.
        store.shield.applicationCategories = nil
        store.shield.webDomainCategories = nil
    }

    private static func otherStores() -> [ManagedSettingsStore]? {
        guard let schedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: .flintGroup),
              let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: .flintGroup) else { return nil }
        return (["flint"] + schedules.map(\.storeName) + limits.map(\.storeName)).map { ManagedSettingsStore(named: .init($0)) }
    }
    private static func blocking(application token: ApplicationToken) -> FlintOpenCreditBlock? {
        guard let stores = otherStores() else { return .unavailable }
        for store in stores {
            if store.shield.applications?.contains(token) == true { return .otherContributor }
            switch store.shield.applicationCategories {
            case .all(except: let allowed)?: if !allowed.contains(token) { return .categoryOverlap }
            case .specific(let categories, except: let allowed)?:
                if !categories.isEmpty && !allowed.contains(token) { return .categoryOverlap }
            case nil: break
            @unknown default: return .categoryOverlap
            }
        }
        return nil
    }
    private static func blocking(webDomain token: WebDomainToken) -> FlintOpenCreditBlock? {
        guard let stores = otherStores() else { return .unavailable }
        for store in stores {
            if store.shield.webDomains?.contains(token) == true { return .otherContributor }
            switch store.shield.webDomainCategories {
            case .all(except: let allowed)?: if !allowed.contains(token) { return .categoryOverlap }
            case .specific(let categories, except: let allowed)?:
                if !categories.isEmpty && !allowed.contains(token) { return .categoryOverlap }
            case nil: break
            @unknown default: return .categoryOverlap
            }
        }
        return nil
    }
}
#endif
