import Foundation
import CryptoKit
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Shared host/shield/monitor adapter for bounded, explicit Open credits.
public enum FlintOpenCredit {
    /// Release gate: lane H enables this only alongside the explicit timed-credit shield copy.
    /// Core explicit-acceptance APIs remain available for that integration and its tests.
    public static let shieldAcceptanceEnabled = false

    public static func preview(for token: ApplicationToken) -> FlintOpenCreditPreview {
        preview(target: FlintOpenLimit.key(for: token), blockedBy: { blocking(application: token) })
    }
    public static func preview(for token: WebDomainToken) -> FlintOpenCreditPreview {
        preview(target: FlintOpenLimit.key(for: token), blockedBy: { blocking(webDomain: token) })
    }
    public static func accept(for token: ApplicationToken, operationID: String = UUID().uuidString)
        -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        accept(target: FlintOpenLimit.key(for: token), operationID: operationID, blockedBy: { blocking(application: token) },
               restoration: { .make(kind: .application, token: token, storeNames: $0) })
    }
    public static func accept(for token: WebDomainToken, operationID: String = UUID().uuidString)
        -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        accept(target: FlintOpenLimit.key(for: token), operationID: operationID, blockedBy: { blocking(webDomain: token) },
               restoration: { .make(kind: .webDomain, token: token, storeNames: $0) })
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
        defer {
            if activity != nil { FlintProtectionStatus.publishAfterCommit(writer: .monitor) }
        }
        // Grant expiry uses its frozen target even when rules are unreadable. Bulk/legacy
        // re-arming belongs to the controller, after validated registration observation.
        guard let service = service(target: nil, blockedBy: { .init(reason: nil, contributors: []) }) else {
            restoreObservedRules()
            return false
        }
        return service.reconcile(activity: activity, callback: callback)
    }

    static func importLegacy(into state: inout FlintLedgerState, defaults: UserDefaults,
                             at now: FlintLedgerInstant, calendar: Calendar) {
        guard !state.migrations.contains(FlintLedgerState.legacyOpenCreditMigration) else { return }
        let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults)
        let raw = defaults.object(forKey: FlintLedgerState.legacyOpenCreditKey)
        state.importLegacyOpenCredits(raw.map(FlintLegacyValue.value) ?? .absent,
            targetKeys: rules.map(targetKeys), established: rules == nil ||
                defaults.object(forKey: FlintRuleStoreKey.openLimits.rawValue) != nil || raw != nil,
            at: now, calendar: calendar)
    }

    /// Preserve a live grant through reloads; close legacy unbounded releases by applying
    /// the full selection when there is no verifiable ledger grant. Never clear categories.
    static func applyShield(for rule: FlintOpenLimitRule) {
        // Never leave the rule unshielded when the ledger can't be used (ADR-012 §8): without a
        // ledger nothing proves a live grant, so the whole selection applies.
        guard let ledger = FlintProtectionLedger.appGroup() else { apply(rule, grants: [:]); return }
        for attempt in 0..<2 {
            if case .success = transactApplyShield(for: rule, ledger: ledger) { return }
            if attempt == 0 { usleep(100_000) }
        }
        // Still busy or unreadable: shield, keeping only grants a read-only snapshot proves live
        // (their deadlines still end them, PC-30). An unreadable ledger proves none.
        let live = (try? ledger.peek().get())?.openCredits.grants ?? [:]
        apply(rule, grants: live)
    }

    @discardableResult
    private static func transactApplyShield(for rule: FlintOpenLimitRule,
                                            ledger: FlintProtectionLedger) -> Result<Void, FlintLedgerError> {
        ledger.transaction { state -> Void in
            let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup)
            let valid = state.reconcileOpenCredits(at: FlintLedgerInstant(clock: .system),
                targetKeys: rules.map(targetKeys) ?? state.openCredits.knownTargets,
                setupCalendar: FlintClock.system.calendar(), reshield: reshield)
            if valid, let rules {
                state.reconcileOpenCreditAuthorization(authorized: { authorized($0, rules: rules) }, reshield: { grant in
                    reshield(grant)
                    FlintScheduling.stopMonitoring([grant.activityName])
                })
            }
            apply(rule, grants: valid ? state.openCredits.grants : [:])
        }
    }

    private static func preview(target: String, blockedBy: @escaping () -> FlintOpenCreditBlocking) -> FlintOpenCreditPreview {
        service(target: target, blockedBy: blockedBy)?.preview(target: target) ??
            .init(remaining: nil, cap: nil, blockedBy: .unavailable)
    }
    private static func accept(target: String, operationID: String, blockedBy: @escaping () -> FlintOpenCreditBlocking,
                               restoration: @escaping (Set<String>) -> FlintOpenCreditRestoration?)
        -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        defer { FlintProtectionStatus.publishAfterCommit() }
        guard let service = service(target: target, blockedBy: blockedBy, restoration: restoration) else { return .failure(.denied(.unavailable)) }
        return service.accept(target: target, operationID: operationID)
    }

    private static func service(target: String?, blockedBy: @escaping () -> FlintOpenCreditBlocking,
                                restoration: @escaping (Set<String>) -> FlintOpenCreditRestoration? = { _ in nil }) -> FlintOpenCreditService? {
        guard let ledger = FlintProtectionLedger.appGroup() else { return nil }
        let center = DeviceActivityCenter()
        return FlintOpenCreditService(ledger: ledger, clock: .system, context: {
            guard let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup),
                  Set(rules.map(\.id)).count == rules.count else { return nil }
            let covering = rules.filter { rule in rule.enabled && target.map { keys(for: rule).contains($0) } == true }
            guard covering.allSatisfy({ rule in
                FlintSelectionValidation.issue(for: rule.selection, context: .openLimit) == nil &&
                    center.activities.contains(DeviceActivityName(rule.activityName)) &&
                    center.schedule(for: DeviceActivityName(rule.activityName)) != nil
            }) else { return nil }
            guard let fingerprints = try? Dictionary(uniqueKeysWithValues: covering.map {
                ($0.id, try effectiveFingerprint(for: $0))
            }), let scopes = try? Dictionary(uniqueKeysWithValues: covering.map {
                ($0.id, try effectiveFingerprint(for: $0, includingCap: false))
            }) else { return nil }
            let blocked = blockedBy()
            return .init(coveringCaps: Dictionary(uniqueKeysWithValues: covering.map { ($0.id, $0.opensAllowed) }),
                         targetKeys: targetKeys(rules), blockedBy:
                            target == "flint.openLimit.unencodable" ? .identityNeedsReselection : blocked.reason,
                         blockingContributors: blocked.contributors,
                         restoration: restoration(Set(covering.map(\.storeName))), fingerprints: fingerprints, scopes: scopes,
                         activities: Dictionary(uniqueKeysWithValues: covering.map { ($0.id, $0.activityName) }))
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
        }, release: release, reshield: reshield, stop: { FlintScheduling.stopMonitoring([$0]) },
        authorized: { grant in
            guard let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup),
                  Set(rules.map(\.id)).count == rules.count else { return nil }
            return authorized(grant, rules: rules)
        }, unavailable: restoreObservedRules)
    }

    private static func targetKeys(_ rules: [FlintOpenLimitRule]) -> Set<String> {
        rules.reduce(into: Set<String>()) { $0.formUnion(keys(for: $1)) }
    }
    private static func keys(for rule: FlintOpenLimitRule) -> Set<String> {
        Set(rule.selection.applicationTokens.map { FlintOpenLimit.key(for: $0) })
            .union(rule.selection.webDomainTokens.map { FlintOpenLimit.key(for: $0) })
    }
    /// `activityName` carries the active activityRevision after rule-gating migration. The
    /// queued template revision/snapshot and display name never change this authorization.
    static func effectiveFingerprint(for rule: FlintOpenLimitRule, includingCap: Bool = true) throws -> String {
        func tokens<T: Encodable>(_ values: Set<T>) throws -> [String] {
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            return try values.map { try encoder.encode([$0]).base64EncodedString() }.sorted()
        }
        var parts = [rule.id, includingCap ? rule.activityName : "active-revision", rule.breakLevel.rawValue,
                     includingCap ? String(rule.opensAllowed) : "quota",
                     String(rule.enabled), String(rule.selection.includeEntireCategory)]
        // Select only incarnation/time-basis fields; queued metadata and template revisions
        // are deliberately absent. This also works across the additive rule-gating schema.
        let fields = try JSONSerialization.jsonObject(with: JSONEncoder().encode(rule)) as? [String: Any]
        if let metadata = fields?["protection"] as? [String: Any] {
            let start = (metadata["occurrence"] as? [String: Any])?["start"]
            parts += [String(describing: start ?? "unknown"), String(describing: metadata["timeZoneID"] ?? "unknown"),
                      metadata["incarnation"] as? String ?? "legacy-incarnation"]
        }
        parts += ["applications"] + (try tokens(rule.selection.applicationTokens))
        parts += ["categories"] + (try tokens(rule.selection.categoryTokens))
        parts += ["domains"] + (try tokens(rule.selection.webDomainTokens))
        return SHA256.hash(data: try JSONEncoder().encode(parts)).map { String(format: "%02x", $0) }.joined()
    }
    private static func release(_ grant: FlintOpenCreditGrant) throws {
        guard let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup) else {
            throw FlintOpenCreditRefusal.denied(.applying)
        }
        for rule in rules where rule.enabled && grant.coveredCauses.contains(rule.id) {
            guard authorizes(grant, rule: rule) else { throw FlintOpenCreditRefusal.denied(.applying) }
            let store = ManagedSettingsStore(named: .init(rule.storeName))
            var apps = store.shield.applications ?? []
            var domains = store.shield.webDomains ?? []
            apps = apps.filter { FlintOpenLimit.key(for: $0) != grant.targetKey }
            domains = domains.filter { FlintOpenLimit.key(for: $0) != grant.targetKey }
            store.shield.applications = apps.isEmpty ? nil : apps
            store.shield.webDomains = domains.isEmpty ? nil : domains
        }
    }
    private static func authorizes(_ grant: FlintOpenCreditGrant, rule: FlintOpenLimitRule) -> Bool {
        guard let fingerprint = try? effectiveFingerprint(for: rule),
              let scope = try? effectiveFingerprint(for: rule, includingCap: false) else { return false }
        return grant.authorizes(cause: rule.id, cap: rule.opensAllowed, fingerprint: fingerprint, scope: scope,
                                activity: rule.activityName)
    }
    private static func authorized(_ grant: FlintOpenCreditGrant, rules: [FlintOpenLimitRule]) -> Bool {
        guard Set(rules.map(\.id)).count == rules.count else { return false }
        let covering = rules.filter { $0.enabled && keys(for: $0).contains(grant.targetKey) }
        return Set(covering.map(\.id)) == grant.coveredCauses && covering.allSatisfy { authorizes(grant, rule: $0) }
    }
    /// A corrupt ledger cannot identify its hashed carrier. Restore only readable rules
    /// whose daily registration is already observed; never create an unarmed/global shield.
    private static func restoreObservedRules() {
        guard let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup),
              Set(rules.map(\.id)).count == rules.count else { return }
        let center = DeviceActivityCenter()
        var grants: [String: FlintOpenCreditGrant] = [:]
        if let ledger = FlintProtectionLedger.appGroup(), case let .success(state) = ledger.peek(), state.openCredits.isValid {
            grants = state.openCredits.grants
        }
        for rule in rules where rule.enabled && FlintSelectionValidation.issue(for: rule.selection, context: .openLimit) == nil &&
            center.activities.contains(DeviceActivityName(rule.activityName)) &&
            center.schedule(for: DeviceActivityName(rule.activityName)) != nil {
            apply(rule, grants: grants)
        }
    }
    private static func reshield(_ grant: FlintOpenCreditGrant) {
        guard grant.isValid else { return }
        let rules = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: .flintGroup)
        var currentTargets: [String: Set<String>]?
        if let rules, Set(rules.map(\.id)).count == rules.count {
            currentTargets = Dictionary(uniqueKeysWithValues: rules.filter(\.enabled).map { ($0.storeName, keys(for: $0)) })
        }
        // Only one frozen token is restored. Valid deleted/disabled rules stay clear, while
        // unreadable rules fall back to the exact covered stores committed with this grant.
        let snapshot = grant.restoration
        switch snapshot.kind {
        case .application:
            snapshot.restore(as: ApplicationToken.self, target: grant.targetKey, coveredCauses: grant.coveredCauses,
                             currentTargets: currentTargets) { name, token in
                let store = ManagedSettingsStore(named: .init(name))
                store.shield.applications = (store.shield.applications ?? []).union([token])
            }
        case .webDomain:
            snapshot.restore(as: WebDomainToken.self, target: grant.targetKey, coveredCauses: grant.coveredCauses,
                             currentTargets: currentTargets) { name, token in
                let store = ManagedSettingsStore(named: .init(name))
                store.shield.webDomains = (store.shield.webDomains ?? []).union([token])
            }
        }
    }
    private static func apply(_ rule: FlintOpenLimitRule, grants: [String: FlintOpenCreditGrant]) {
        let store = ManagedSettingsStore(named: .init(rule.storeName))
        let now = FlintLedgerInstant(clock: .system)
        let center = DeviceActivityCenter()
        func covered(_ key: String) -> Bool {
            guard let grant = grants[key], authorizes(grant, rule: rule), grant.remaining(at: now) > 0,
                  center.schedule(for: DeviceActivityName(grant.activityName)) != nil else { return false }
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

    private static func otherStores() -> [FlintOpenCreditContributor]? {
        guard let schedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: .flintGroup),
              let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: .flintGroup) else { return nil }
        let names = [("Current session", "flint")] + schedules.map { ("Schedule: " + $0.name, $0.storeName) } +
            limits.map { ("Time Limit: " + $0.name, $0.storeName) }
        return names.map { name, storeName in
            let store = ManagedSettingsStore(named: .init(storeName))
            return .init(name: name,
                applications: Set((store.shield.applications ?? []).map { FlintOpenLimit.key(for: $0) }),
                webDomains: Set((store.shield.webDomains ?? []).map { FlintOpenLimit.key(for: $0) }),
                applicationCategoryExceptions: categoryExceptions(store.shield.applicationCategories, key: { FlintOpenLimit.key(for: $0) }),
                webCategoryExceptions: categoryExceptions(store.shield.webDomainCategories, key: { FlintOpenLimit.key(for: $0) }),
                webContentMayBlock: store.webContent.blockedByFilter != nil)
        }
    }
    private static func blocking(application token: ApplicationToken) -> FlintOpenCreditBlocking {
        .query(target: FlintOpenLimit.key(for: token), kind: .application, stores: otherStores())
    }
    private static func blocking(webDomain token: WebDomainToken) -> FlintOpenCreditBlocking {
        .query(target: FlintOpenLimit.key(for: token), kind: .webDomain, stores: otherStores())
    }
    private static func categoryExceptions<Activity>(
        _ policy: ShieldSettings.ActivityCategoryPolicy<Activity>?, key: (Token<Activity>) -> String
    ) -> Set<String>? {
        switch policy {
        case .some(.none), .none: return nil
        case let .some(.all(except: allowed)): return Set(allowed.map(key))
        case let .some(.specific(categories, except: allowed)):
            return categories.isEmpty ? nil : Set(allowed.map(key))
        @unknown default: return [] // an unknown category policy never proves disjointness
        }
    }
}
#endif
