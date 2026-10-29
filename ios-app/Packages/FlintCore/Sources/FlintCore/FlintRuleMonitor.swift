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
        _ = FlintRuleStoreLock.withLock(environment: env) {
            switch domain {
            case .schedules: process(FlintScheduleRule.self, callback: callback, activity: activity)
            case .limits: process(FlintLimitRule.self, callback: callback, activity: activity)
            case .openLimits: process(FlintOpenLimitRule.self, callback: callback, activity: activity)
            }
        }
        return true // includes unavailable: preserve protection, never fall through to stale cleanup
    }

    private func process<Rule: FlintGatedRule>(_ type: Rule.Type, callback: Callback, activity: String) {
        guard FlintRuleArbiter(environment: env).applyQueued(type),
              var all = FlintDesiredRules.load(type, key: Rule.domain, from: env.defaults, at: env.clock.now()) else {
            env.record(Rule.domain.domain, FlintDesiredRules.unavailable(Rule.domain.domain, previous: env.loadHealth()))
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
                    env.record(Rule.domain.domain, FlintDesiredRules.saveFailure(Rule.domain.domain, previous: env.loadHealth()))
                    return
                }
            }
            if let schedule = rule as? FlintScheduleRule { env.applyRuleShield(schedule) }
            else if let limit = rule as? FlintLimitRule {
                if isNewOccurrence { env.clearStore(limit.storeName); env.clearLimitThreshold(limit.id) }
                if limit.thresholdMinutes == 0 { env.applyLimitShield(limit); env.markLimitThreshold(limit) }
            } else if let open = rule as? FlintOpenLimitRule, isNewOccurrence { env.applyOpenShield(open) }
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
            guard let limit = rule as? FlintLimitRule, event == limit.eventName,
                  limit.occurrence(at: now) != nil else { return }
            env.applyLimitShield(limit)
            env.markLimitThreshold(limit)
        }
    }
}
#endif
