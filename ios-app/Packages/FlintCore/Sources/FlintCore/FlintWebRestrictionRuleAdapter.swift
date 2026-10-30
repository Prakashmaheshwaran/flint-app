import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import ManagedSettings

/// Web-owned half of the rule transition. The rule owner calls this before the first arm;
/// callback writers consume only that accepted owner's saved child and trusted occurrence.
enum FlintWebRestrictionRuleAdapter {
    static func freeze<Rule: FlintGatedRule>(_ rules: [Rule], environment: FlintRuleReloadEnvironment,
                                            existingWebStores: (() -> Bool?)? = nil) -> Bool {
        guard Rule.domain == .schedules else { return true }
        guard let schedules = rules as? [FlintScheduleRule], schedules.allSatisfy(FlintWebRestrictionChildren.knows) else { return false }
        // One read for this entire freeze, including all unchanged but newly observed owners.
        let read = FlintWebRestrictions.read(uiRead: false, environment: environment,
            existingWebStores: existingWebStores.map { probe in { probe() ?? true } },
            transition: { _, body in body(); return true })
        let config: FlintWebRestrictions?
        if case let .available(saved) = read, saved.validationMessage == nil { config = saved } else { config = nil }
        let snapshot = FlintWebRestrictionEditor.Snapshot(session: nil, rules: schedules)
        let children = FlintWebRestrictionChildren(defaults: environment.defaults,
            recoveryDirectory: environment.recoveryDirectory, acceptSave: environment.acceptSave)
        let saved = children.capture(FlintWebRestrictionChildren.causes(snapshot, clock: environment.clock), global: config)
        if config == nil || !saved {
            var report = environment.loadHealth().domains["webRestrictions"]
                ?? .init(enabled: 0, attempted: 0, armed: 0, failures: [])
            report.failures.removeAll { $0.activityName == "webRestrictions" }
            report.failures.append(.init(activityName: "webRestrictions",
                reason: "This occurrence's website policy could not be verified. Existing Safari restrictions were preserved; repair requires the running commitment's exit rules."))
            report.updatedAt = environment.clock.now()
            environment.record("webRestrictions", report)
        }
        return saved
    }

    static func reconcile(_ callback: FlintScheduleRule, read: FlintWebRestrictionRead,
                          owners: [FlintScheduleRule]?, children: FlintWebRestrictionChildren,
                          writer: any FlintWebRestrictionWriting, clock: FlintClock,
                          addingOnly: Bool = false, record: (String) -> Void) {
        let global: FlintWebRestrictions
        switch read {
        case let .available(config): global = config
        case .busy:
            record("Safari reconciliation deferred because website rules are busy. Existing keys were preserved; retry on the next rule or host reconciliation.")
            return
        case .unavailable:
            record("Saved website rules are unavailable. Existing Safari settings were preserved pending repair.")
            return
        }
        guard global.validationMessage == nil,
              let owner = owners?.first(where: { $0.id == callback.id }),
              FlintWebRestrictionChildren.knows(owner), owner.enabled,
              owner.protection?.incarnation == callback.protection?.incarnation,
              owner.revision == callback.revision, owner.activityName == callback.activityName,
              let window = owner.occurrence(at: clock.now(), clock: clock),
              owner.protection?.occurrence == window,
              callback.occurrence(at: clock.now(), clock: clock) == window else {
            record("Existing Safari settings retained because the accepted saved occurrence could not be verified; rule reconciliation owns natural-end cleanup.")
            return
        }
        let cause = FlintWebRestrictionChildren.Cause.rule(owner, window: window)
        // A positive-only fallback cannot establish a new child after a failed/busy freeze.
        guard case let .available(envelope) = children.read(), envelope.children.contains(where: { $0.cause == cause }),
              let config = addingOnly ? children.frozen(cause, fallback: nil) : children.resolve(cause, global: global) else {
            record("This occurrence's frozen website policy is unavailable. Existing Safari keys were preserved pending repair.")
            return
        }
        if config.applyDuringSchedules { writer.apply(config, toStore: owner.storeName) }
        else if !addingOnly { writer.clear(store: owner.storeName) }
    }

    static func record(_ rule: FlintScheduleRule, note: String) {
        guard let group = FlintGroupStore() else { return }
        var health = group.loadArmingHealth()
        for domain in ["webRestrictions", "ruleTime.schedules"] {
            var report = health.domains[domain] ?? .init(enabled: 0, attempted: 0, armed: 0, failures: [])
            report.failures = Array((report.failures + [.init(activityName: rule.activityName, reason: note)]).suffix(64))
            health = health.replacing(domain, with: report)
        }
        group.saveArmingHealth(health)
    }
}

/// The fallback can add missing restrictions while a full rule mutation is unavailable.
/// It never replaces a known native filter or turns off explicit-content restrictions.
struct FlintManagedPositiveWebRestrictionWriter: FlintWebRestrictionWriting {
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) {
        let store = ManagedSettingsStore(named: .init(storeName))
        if store.webContent.blockedByFilter == nil {
            store.webContent.blockedByFilter = FlintWebRestrictionStore.filterPolicy(for: config)
        }
        if config.denyExplicitMedia { store.media.denyExplicitContent = true }
    }
    func clear(store storeName: String) {}
}
#endif
