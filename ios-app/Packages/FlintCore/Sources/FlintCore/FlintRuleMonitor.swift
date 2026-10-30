import Foundation
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
import DeviceActivity

/// The extension's narrow integration seam. Exact activity + event revisions and the persisted
/// logical end are checked before any store write. No session or credit-expiry ownership here.
public final class FlintRuleMonitor {
    public enum Callback { case start, end, threshold(String) }
    private let env: FlintRuleReloadEnvironment
    public init() { env = .live }
    init(environment: FlintRuleReloadEnvironment) { env = environment }

    @discardableResult
    public func handle(_ callback: Callback, activity: String) -> Bool {
        let domain = FlintRuleStoreKey.allCases.first { activity.hasPrefix($0.prefix) }
        guard let domain else { return false }
        if case let .threshold(event) = callback { env.observeLimitThreshold(activity, event) }
        let acquired: Void? = FlintRuleStoreLock.withLock(environment: env) {
            switch domain {
            case .schedules: process(FlintScheduleRule.self, callback: callback, activity: activity)
            case .limits: process(FlintLimitRule.self, callback: callback, activity: activity)
            case .openLimits: process(FlintOpenLimitRule.self, callback: callback, activity: activity)
            }
        }
        if acquired == nil {
            switch domain {
            case .schedules: restorePositiveSnapshot(FlintScheduleRule.self, callback: callback, activity: activity)
            case .limits: restorePositiveSnapshot(FlintLimitRule.self, callback: callback, activity: activity)
            case .openLimits: restorePositiveSnapshot(FlintOpenLimitRule.self, callback: callback, activity: activity)
            }
            env.recordState(domain.domain, .init(enabled: env.loadHealth().domains[domain.domain]?.enabled ?? 0,
                attempted: 0, armed: 0, failures: [.init(activityName: activity,
                    reason: "Rule transition is busy. Readable current start/threshold protection was retained; retry reconciliation.")]))
        }
        return true // includes unavailable: preserve protection, never fall through to stale cleanup
    }

    private func process<Rule: FlintGatedRule>(_ type: Rule.Type, callback: Callback, activity: String) {
        if Rule.domain == .openLimits { env.reconcileOpenCredits() }
        guard FlintRuleArbiter(environment: env).applyQueued(type),
              var all = FlintDesiredRules.load(type, key: Rule.domain, from: env.defaults, at: env.clock.now(),
                recoveryDirectory: env.recoveryDirectory) else {
            restorePositiveSnapshot(type, callback: callback, activity: activity, markThreshold: true)
            env.recordState(Rule.domain.domain, FlintDesiredRules.unavailable(Rule.domain.domain, previous: env.loadHealth()))
            return
        }
        guard let index = all.firstIndex(where: { $0.activityName == activity && $0.enabled }) else {
            env.center.stopMonitoring([.init(activity)]) // stale revision: never touch its shared store
            return
        }
        var rule = all[index]
        let now = env.clock.now()
        switch callback {
        case .start:
            guard let window = rule.templateOccurrence(at: now) else { return }
            let isNewOccurrence = rule.protection?.occurrence?.start != window.start
            if isNewOccurrence {
                rule.protection?.occurrence = window
                all[index] = rule
                guard env.acceptSave(), FlintDesiredRules.save(all, key: Rule.domain, to: env.defaults) else {
                    applyPositive(rule, callback: callback)
                    env.recordState(Rule.domain.domain, FlintDesiredRules.saveFailure(Rule.domain.domain, previous: env.loadHealth()))
                    return
                }
            }
            if let schedule = rule as? FlintScheduleRule { env.applyProtection(schedule) }
            else if let limit = rule as? FlintLimitRule {
                if isNewOccurrence { env.clearStore(limit.storeName); env.clearLimitThreshold(limit.id) }
                if limit.thresholdMinutes == 0 { env.applyProtection(limit); env.markLimitThreshold(limit, now) }
            } else if let open = rule as? FlintOpenLimitRule { env.applyProtection(open) }
        case .end:
            // A delayed old end, including a callback delivered after the next start, cannot
            // end the new window. A carrier's wall end alone is never release authority.
            guard let expected = rule.protection?.occurrence, now >= expected.end else { return }
            if let current = rule.templateOccurrence(at: now), current.start >= expected.end {
                process(type, callback: .start, activity: activity)
                return
            }
            if Rule.domain != .openLimits { env.clearStore(rule.storeName) }
            if Rule.domain == .limits { env.clearLimitThreshold(rule.id) }
        case let .threshold(event):
            guard var limit = rule as? FlintLimitRule, event == limit.eventName,
                  let window = limit.occurrence(at: now) else { return }
            if limit.protection?.occurrence?.start != window.start {
                // Threshold delivery may precede today's start (or yesterday's delayed end).
                // Freeze today's period first so those later callbacks cannot clear a budget
                // already exhausted in this period.
                limit.protection?.occurrence = window
                all[index].protection?.occurrence = window
                guard env.acceptSave(), FlintDesiredRules.save(all, key: Rule.domain, to: env.defaults) else {
                    applyPositive(limit, callback: callback)
                    env.recordState(Rule.domain.domain, FlintDesiredRules.saveFailure(Rule.domain.domain, previous: env.loadHealth()))
                    return
                }
            }
            env.applyProtection(limit)
            env.markLimitThreshold(limit, now)
        }
    }

    /// No queue application, migration, counter reset or native retirement without the lock.
    /// Only add protection from an exact readable effective revision; END remains a no-op.
    private func restorePositiveSnapshot<Rule: FlintGatedRule>(_ type: Rule.Type, callback: Callback,
                                                              activity: String, markThreshold: Bool = false) {
        if case .end = callback { return }
        guard let all = FlintDesiredRules.load(type, key: Rule.domain, from: env.defaults,
            at: env.clock.now(), migrating: false, recoveryDirectory: env.recoveryDirectory),
              let rule = all.first(where: { $0.enabled && $0.activityName == activity }),
              rule.occurrence(at: env.clock.now()) != nil else { return }
        if case let .threshold(event) = callback {
            guard let limit = rule as? FlintLimitRule, event == limit.eventName else { return }
            applyPositive(limit, callback: callback)
            if markThreshold { env.markLimitThreshold(limit, env.clock.now()) }
        } else { applyPositive(rule, callback: callback) }
    }

    private func applyPositive<Rule: FlintGatedRule>(_ rule: Rule, callback: Callback) {
        if let schedule = rule as? FlintScheduleRule {
            env.applyProtection(schedule, addingOnly: true)
        } else if let limit = rule as? FlintLimitRule {
            if case .start = callback, limit.thresholdMinutes > 0 { return }
            env.applyProtection(limit, addingOnly: true)
        } else if let open = rule as? FlintOpenLimitRule {
            env.applyProtection(open, addingOnly: true)
        }
    }
}
#endif
