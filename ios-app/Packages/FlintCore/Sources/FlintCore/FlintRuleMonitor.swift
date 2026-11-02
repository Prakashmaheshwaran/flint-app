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
        defer { env.reconcileRemovalGuard() }
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
              let all = env.observedRules(type) else {
            restorePositiveSnapshot(type, callback: callback, activity: activity, markThreshold: true)
            env.recordState(Rule.domain.domain, FlintDesiredRules.unavailable(Rule.domain.domain, previous: env.loadHealth()))
            return
        }
        guard let index = all.firstIndex(where: { $0.activityName == activity && $0.enabled }) else {
            env.center.stopMonitoring([.init(activity)]) // stale revision: never touch its shared store
            return
        }
        let rule = all[index]
        let now = env.clock.now()
        let current = rule.occurrence(at: now, clock: env.clock)
        let prematureEnd: Bool
        if case .end = callback, let expected = rule.protection?.occurrence {
            prematureEnd = !FlintRuleOccurrenceClock.isOver(expected, for: rule, clock: env.clock)
        } else { prematureEnd = false }
        let prematureStart: Bool
        if case .start = callback {
            prematureStart = rule.templateOccurrence(at: now) != current
                && (current == nil || current == rule.protection?.occurrence)
        }
        else { prematureStart = false }
        if prematureEnd || prematureStart {
            // The native carrier follows editable wall time. Keep the exact old occurrence,
            // its exhausted threshold and shield, and repair the carrier to its logical end.
            if current != nil {
                if let limit = rule as? FlintLimitRule {
                    if limit.thresholdMinutes == 0 || env.timeLimitIsShielded(limit, now) { env.applyProtection(limit) }
                } else { applyPositive(rule, callback: callback) }
            }
            repairCarrier(rule, reason: prematureEnd ? "prematureEnd" : "prematureStart")
            return
        }
        let resetShields: Bool
        if case .threshold = callback { resetShields = false } else { resetShields = true }
        guard let frozen = env.freezeOccurrences(all, resetShields: resetShields) else {
            if case .end = callback {} else if current != nil { applyPositive(rule, callback: callback) }
            env.recordState(Rule.domain.domain, FlintDesiredRules.saveFailure(Rule.domain.domain, previous: env.loadHealth()))
            return
        }
        let effective = frozen[index]
        switch callback {
        case .start:
            guard current != nil else { return }
            if let schedule = effective as? FlintScheduleRule { env.applyProtection(schedule) }
            else if let limit = effective as? FlintLimitRule {
                if limit.thresholdMinutes == 0 { env.applyProtection(limit); env.markLimitThreshold(limit, now) }
            } else if let open = effective as? FlintOpenLimitRule { env.applyProtection(open) }
        case .end:
            // A delayed old end, including a callback delivered after the next start, cannot
            // end the new window. A carrier's wall end alone is never release authority.
            if current != nil {
                if let schedule = effective as? FlintScheduleRule { env.applyProtection(schedule) }
                else if let limit = effective as? FlintLimitRule, limit.thresholdMinutes == 0 {
                    env.applyProtection(limit); env.markLimitThreshold(limit, now)
                } else if let open = effective as? FlintOpenLimitRule { env.applyProtection(open) }
            }
            // A corrected wall carrier is finite. Register its successor at the trusted
            // boundary so the first repair does not silently disable recurring protection.
            if abs(now.timeIntervalSince(FlintRuleOccurrenceClock.date(for: effective, clock: env.clock))) > FlintTimeIntegrity.discontinuityThreshold {
                repairCarrier(effective, reason: "trustedBoundary")
            }
        case let .threshold(event):
            guard let limit = effective as? FlintLimitRule, event == limit.eventName, current != nil else { return }
            env.applyProtection(limit)
            env.markLimitThreshold(limit, now)
        }
    }

    private func repairCarrier<Rule: FlintGatedRule>(_ rule: Rule, reason: String) {
        guard rule.enabled, let window = rule.previewWindow(at: env.clock.now(), clock: env.clock) else { return }
        guard var rules = FlintDesiredRules.load(Rule.self, key: Rule.domain, from: env.defaults,
                at: env.clock.now(), migrating: false, recoveryDirectory: env.recoveryDirectory),
              let index = rules.firstIndex(where: { $0.id == rule.id && $0.activityName == rule.activityName }) else {
            recordRepair(rule, message: "\(reason): carrier repair skipped because saved rule state is unavailable.")
            return
        }
        let last = rules[index].protection?.carrierRepair
        guard last?.isValid != false else {
            recordRepair(rule, message: "\(reason): carrier repair budget unavailable; trusted protection retained without registering another carrier.")
            return
        }
        var rebootRepair = false
        if let last, last.occurrenceID == window.identity {
            if let elapsed = last.checkpoint.elapsed(until: .init(clock: env.clock)) {
                guard last.attempts < 6, elapsed >= 15 else {
                    recordRepair(rule, message: "\(reason): carrier repair skipped by its persisted spacing/attempt limit; trusted protection retained.")
                    return
                }
            } else { rebootRepair = true } // one eligible attempt on the newly observed boot
        }
        rules[index].protection?.carrierRepair = .init(occurrenceID: window.identity,
            checkpoint: .init(clock: env.clock), attempts: last?.occurrenceID == window.identity ? min(6, last!.attempts + 1) : 1)
        guard env.acceptSave(), FlintDesiredRules.save(rules, key: Rule.domain, to: env.defaults) else {
            recordRepair(rule, message: "\(reason): carrier repair skipped because its checkpoint could not be saved; trusted protection retained.")
            return
        }
        let request = rule.registration(clock: env.clock)
        var message = "\(reason): kept trusted occurrence and daily budget; repaired wall-clock carrier\(rebootRepair ? " after reboot" : "")."
        do {
            try env.center.startMonitoring(.init(request.name), during: request.schedule, events: request.events)
            if !env.center.activities.contains(.init(request.name)) || env.center.schedule(for: .init(request.name)) == nil {
                message = "\(reason): trusted protection retained; repaired carrier was not observed."
            }
        } catch { message = "\(reason): trusted protection retained; carrier repair failed: \(error.localizedDescription)" }
        recordRepair(rule, message: message)
    }

    private func recordRepair<Rule: FlintGatedRule>(_ rule: Rule, message: String) {
        let domain = "ruleTime." + Rule.domain.domain
        var report = env.loadHealth().domains[domain] ?? .init(enabled: 0, attempted: 0, armed: 0, failures: [])
        report.failures = Array((report.failures + [.init(activityName: rule.activityName, reason: message)]).suffix(64))
        env.record(domain, report)
    }

    /// No queue application, migration, counter reset or native retirement without the lock.
    /// Only add protection from an exact readable effective revision; END remains a no-op.
    private func restorePositiveSnapshot<Rule: FlintGatedRule>(_ type: Rule.Type, callback: Callback,
                                                              activity: String, markThreshold: Bool = false) {
        if case .end = callback { return }
        guard let all = FlintDesiredRules.load(type, key: Rule.domain, from: env.defaults,
            at: env.clock.now(), migrating: false, recoveryDirectory: env.recoveryDirectory),
              let rule = all.first(where: { $0.enabled && $0.activityName == activity }),
              rule.occurrence(at: env.clock.now(), clock: env.clock) != nil else { return }
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
