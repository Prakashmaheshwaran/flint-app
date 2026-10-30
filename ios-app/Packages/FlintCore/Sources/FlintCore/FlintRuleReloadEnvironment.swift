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
                                         at now: Date = Date(), migrating: Bool = true,
                                         recoveryDirectory: URL? = FlintRuleRecovery.directory,
                                         nativeActivities: Set<String>? = nil,
                                         externalEvidence: (() -> Bool)? = nil) -> [Rule]? {
        guard let defaults, defaults.synchronize() else { return nil }
        let initialization = FlintRuleInitialization.receipt(in: defaults)
        guard initialization != .invalid, initialization != .preparing else { return nil }
        guard FlintRuleRecovery.available(key.original, defaults: defaults, directory: recoveryDirectory),
              FlintRuleRecovery.available(key.rawValue + ".quarantine.adr012", defaults: defaults,
                directory: recoveryDirectory) else { return nil }
        let marker = defaults.object(forKey: key.marker)
        guard initialization != .complete || marker != nil else { return nil }
        if let marker {
            guard let data = marker as? Data,
                  let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  Set(fields.keys) == ["schema"],
                  let decoded = try? JSONDecoder().decode(Marker.self, from: data), decoded.schema == 1 else { return nil }
        }
        guard let object = defaults.object(forKey: key.rawValue) else {
            // A deleted key on an established domain must never mean "remove all activities".
            guard marker == nil, defaults.object(forKey: key.original) == nil,
                  defaults.object(forKey: key.rawValue + ".quarantine.adr012") == nil,
                  !FlintRuleInitialization.hasProtectionEvidence(in: defaults, activities: [], external: false),
                  !FlintRuleInitialization.hasProtectionEvidence(in: defaults,
                    activities: nativeActivities ?? Set(DeviceActivityCenter().activities.map(\.rawValue)),
                    external: (externalEvidence ?? FlintRuleInitialization.externalEvidence)()) else { return nil }
            // A read-only peek may describe an actually empty installation. Serialized host
            // startup/mutation writes the positive receipt and all three domains together.
            return []
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
                FlintRuleRecovery.retain(object, key: key.rawValue + ".quarantine.adr012",
                    defaults: defaults, directory: recoveryDirectory)
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
            if migrated {
                guard FlintRuleRecovery.retain(data, key: key.original, defaults: defaults,
                    directory: recoveryDirectory) else { return nil }
            }
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
        guard let defaults, defaults.synchronize(), Set(rules.map(\.id)).count == rules.count,
              rules.allSatisfy({ $0.validationIssue == nil && $0.protection != nil }),
              let data = try? encode(rules),
              let marker = try? JSONEncoder().encode(Marker(schema: 1)) else { return false }
        let previous = defaults.object(forKey: key.rawValue)
        let previousMarker = defaults.object(forKey: key.marker)
        var saved = false
        defer {
            if !saved {
                // A failed flush must not leave a weaker cached after-image available to a
                // later reconcile. Restore both keys; unreadable storage remains unavailable.
                if let previous { defaults.set(previous, forKey: key.rawValue) }
                else { defaults.removeObject(forKey: key.rawValue) }
                if let previousMarker { defaults.set(previousMarker, forKey: key.marker) }
                else { defaults.removeObject(forKey: key.marker) }
                defaults.synchronize()
            }
        }
        defaults.set(data, forKey: key.rawValue)
        guard defaults.synchronize(), defaults.data(forKey: key.rawValue) == data else { return false }
        defaults.set(marker, forKey: key.marker)
        guard defaults.synchronize(), defaults.data(forKey: key.marker) == marker else { return false }
        saved = true
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
    var applyOpenShield: (FlintOpenLimitRule) -> Bool
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
    var applyRuleShield: (FlintScheduleRule, FlintClock) -> Bool = { FlintRuleShield.apply($0, clock: $1) }
    var applyLimitShield: (FlintLimitRule, FlintClock) -> Bool = { FlintRuleShield.apply($0, clock: $1) }
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
    /// Positive-only restoration when a callback cannot commit a rule transition.
    var addRuleShield: ((FlintScheduleRule, FlintClock) -> Bool)? = nil
    var addLimitShield: ((FlintLimitRule, FlintClock) -> Bool)? = nil
    var addOpenShield: ((FlintOpenLimitRule) -> Bool)? = nil
    var recoveryDirectory: URL? = FlintRuleRecovery.directory
    var recoveryGate: () -> FlintPassRecoveryGate = { .enforcing }
    /// Positive threshold evidence precedes lock acquisition and any outbox recovery.
    var observeLimitThreshold: (String, String) -> Void = { _, _ in }

    @discardableResult
    func applyProtection<Rule: FlintGatedRule>(_ rule: Rule, addingOnly: Bool = false) -> Bool {
        guard recoveryGate().permitsApplication else {
            recordState(Rule.domain.domain, .init(enabled: loadHealth().domains[Rule.domain.domain]?.enabled ?? 1,
                attempted: 0, armed: 0, failures: [], updatedAt: clock.now()))
            return false
        }
        let applied: Bool
        if let schedule = rule as? FlintScheduleRule {
            applied = (addingOnly ? addRuleShield ?? applyRuleShield : applyRuleShield)(schedule, clock)
        } else if let limit = rule as? FlintLimitRule {
            applied = (addingOnly ? addLimitShield ?? applyLimitShield : applyLimitShield)(limit, clock)
        } else if let open = rule as? FlintOpenLimitRule {
            applied = (addingOnly ? addOpenShield ?? applyOpenShield : applyOpenShield)(open)
        } else {
            applied = false
        }
        if !applied {
            recordState(Rule.domain.domain, .init(enabled: loadHealth().domains[Rule.domain.domain]?.enabled ?? 1,
                attempted: 1, armed: 0, failures: [.init(activityName: rule.activityName,
                    reason: "Protection could not be applied yet. Flint will retry saved protection.")], updatedAt: clock.now()))
        }
        return applied
    }

    func recordState(_ domain: String, _ report: FlintArmingHealth.DomainReport) {
        var current = report
        switch recoveryGate() {
        case .enforcing: current.suspendedUntil = nil
        case let .suspended(until): current.suspendedUntil = until; current.armed = 0
        case .unavailable:
            current.armed = 0
            current.failures.append(.init(activityName: domain,
                reason: "Protection state unavailable. Saved commitments and finite protection were retained."))
        }
        record(domain, current)
    }
    var installationEvidence: () -> Bool = FlintRuleInitialization.externalEvidence

    /// The caller holds the transition lock. Checkpoint before decisions, but do not freeze a
    /// successor here: queued changes must apply before the next occurrence can be accepted.
    func observedRules<Rule: FlintGatedRule>(_ type: Rule.Type) -> [Rule]? {
        guard var rules = FlintDesiredRules.load(type, key: Rule.domain, from: defaults, at: clock.now(),
            recoveryDirectory: recoveryDirectory, nativeActivities: Set(center.activities.map(\.rawValue)),
            externalEvidence: installationEvidence) else { return nil }
        let before = rules
        var notes: [FlintArmingHealth.Failure] = []
        for index in rules.indices {
            let anchor = rules[index].protection?.timeAnchor
            if let note = FlintRuleOccurrenceClock.observe(&rules[index], clock: clock) {
                notes.append(.init(activityName: rules[index].activityName, reason: note))
            } else if let anchor, let next = rules[index].protection?.timeAnchor,
                      abs(next.timeline.timeIntervalSince(anchor.timeline)) < 1 {
                // A read-only UI refresh need not rewrite every opaque selection. Every
                // decision still projects the full monotonic delta from the durable anchor.
                rules[index].protection?.timeAnchor = anchor
            }
        }
        if rules != before {
            guard acceptSave(), FlintDesiredRules.save(rules, key: Rule.domain, to: defaults) else { return nil }
        }
        if !notes.isEmpty {
            let domain = "ruleTime." + Rule.domain.domain
            var report = loadHealth().domains[domain] ?? .init(enabled: 0, attempted: 0, armed: 0, failures: [])
            report.failures = Array((report.failures + notes).suffix(64))
            record(domain, report)
        }
        return rules
    }

    /// Natural transitions are committed once, before resetting a daily budget or releasing a
    /// shield. A callback's wall date is never sufficient authority for either operation.
    func freezeOccurrences<Rule: FlintGatedRule>(_ rules: [Rule], resetShields: Bool = true) -> [Rule]? {
        var next = rules
        var changed: [Rule] = []
        for index in next.indices {
            // applyQueued normally precedes this call. A direct/misordered caller must not
            // freeze the old template's successor across a due queue or invalidate its blob.
            if let queue = next[index].protection?.queuedChange,
               FlintRuleOccurrenceClock.isOver(queue.window, for: next[index], clock: clock) { continue }
            let current = next[index].occurrence(at: clock.now(), clock: clock)
            if next[index].protection?.occurrence != current {
                next[index].protection?.occurrence = current
                changed.append(next[index])
            }
        }
        guard changed.isEmpty || (acceptSave() && FlintDesiredRules.save(next, key: Rule.domain, to: defaults)) else { return nil }
        for rule in changed {
            if let limit = rule as? FlintLimitRule {
                if resetShields { clearStore(limit.storeName) }
                clearLimitThreshold(limit.id)
            } else if rule.kind != .openLimit, rule.occurrence(at: clock.now(), clock: clock) == nil {
                clearStore(rule.storeName)
            }
        }
        return next
    }

    func rulesForDisplay<Rule: FlintGatedRule>(_ type: Rule.Type) -> [Rule]? {
        if let read = FlintRuleStoreLock.withLock(environment: self, {
            observedRules(type)
        }) { return read }
        // A busy transition is not evidence that the saved blob is corrupt. A display-only
        // peek may show its valid revision; commits still acquire the boundary and re-check it.
        return FlintDesiredRules.load(type, key: Rule.domain, from: defaults, at: clock.now(), migrating: false,
            recoveryDirectory: recoveryDirectory, nativeActivities: Set(center.activities.map(\.rawValue)),
            externalEvidence: installationEvidence)
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
                    || FlintEmergencyPassRuleEffects.timeLimitReached(limit, at: now, ledger: .appGroup())
                    || store.shield.applications != nil || store.shield.applicationCategories != nil
                    || store.shield.webDomains != nil || store.shield.webDomainCategories != nil
            }, withTransitionBoundary: { body in
                // One cross-process order: session transition → rule domain → ledger.
                FlintSessionController().withTransition {
                    guard FlintEmergencyPassController.prepareForPolicyMutation() else { return false }
                    body(); return true
                } ?? false
            }, markLimitThreshold: { FlintLimitThresholds.markReached($0, at: $1) },
            clearLimitThreshold: { FlintLimitThresholds.clear($0) },
            publishStatus: { FlintProtectionStatus.publishAfterCommit() },
            addRuleShield: { FlintRuleShield.add($0, clock: $1) },
            addLimitShield: { FlintRuleShield.add($0, clock: $1) },
            addOpenShield: { FlintOpenCredit.addShieldFromSnapshot(for: $0) },
            recoveryGate: { FlintPassRecoveryGate.read() },
            observeLimitThreshold: { activity, event in
                _ = FlintEmergencyPassController().noteTimeLimitThreshold(activity: activity, event: event)
            })
    }
}
#endif
