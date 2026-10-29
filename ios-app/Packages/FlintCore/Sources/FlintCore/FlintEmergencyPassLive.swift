import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

extension FlintEmergencyPassController {
    public convenience init() { self.init(environment: .live) }

    /// Called only at ordinary live mutation boundaries, before their domain/ledger locks.
    /// Pass effects already own that transition and must not recursively replay themselves.
    static func prepareForPolicyMutation() -> Bool {
        if isInPolicyTransition { return true }
        if case .success = FlintEmergencyPassController().reconcile() { return true }
        return false
    }

    @discardableResult
    public func noteTimeLimitThreshold(activity: String, event: String) -> Bool {
        FlintEmergencyPassRuleEffects.noteThreshold(activity: activity, event: event,
            at: .init(clock: .system), defaults: .flintGroup, ledger: FlintProtectionLedger.appGroup())
    }
}

extension FlintEmergencyPassEnvironment {
    static var live: Self {
        let session = FlintSessionController()
        return .init(ledger: { FlintProtectionLedger.appGroup() }, clock: .system,
            scope: { state, now in
                try FlintEmergencyPassScope.load(state: state, at: now, defaults: .flintGroup, calendar: .autoupdatingCurrent)
            }, registerCarrier: { name, start, end in
                let center = DeviceActivityCenter()
                if let schedule = center.schedule(for: .init(name)), let interval = schedule.nextInterval,
                   center.activities.contains(.init(name)) {
                    let warning = schedule.warningTime
                    let lead = (warning?.hour ?? 0) * 3600 + (warning?.minute ?? 0) * 60 + (warning?.second ?? 0)
                    let logicalEnd = interval.end.addingTimeInterval(-TimeInterval(lead))
                    // A padded warning rounds to whole minutes; up to 60 seconds late is
                    // expected. Re-register only for a changed logical deadline/clock.
                    if (0...60).contains(logicalEnd.timeIntervalSince(end)) { return }
                }
                // DeviceActivity resolves whole seconds. Rounding down would make a normal
                // carrier fire before the monotonic recovery deadline with no later callback.
                let roundedEnd = FlintSessionAutoClearPlan.wholeSecond(atOrAfter: end)
                let plan = FlintSessionAutoClearPlan.make(start: start, end: roundedEnd,
                    calendar: .autoupdatingCurrent, strategy: .endWarning)
                try FlintScheduling.startMonitoring(name, during: DeviceActivitySchedule(
                    intervalStart: plan.intervalStart, intervalEnd: plan.intervalEnd,
                    repeats: false, warningTime: plan.warningTime))
                guard center.activities.contains(.init(name)), center.schedule(for: .init(name)) != nil else {
                    throw FlintEmergencyPassError.carrierUnavailable
                }
            }, stopCarrier: { FlintScheduling.stopMonitoring([$0]) },
            terminate: { sessions, at in
                for ended in sessions {
                    guard session.endCommittedEmergencyPass(ended, at: at) else { throw FlintEmergencyPassError.applying }
                }
            }, release: { committedScope in
                var names = Set(committedScope.stores)
                // A rule can start inside recovery; include the fresh validated store inventory.
                // If that read fails the original positively identified stores still release.
                if let ledger = FlintProtectionLedger.appGroup(), case let .success(state) = ledger.peek(),
                   let fresh = try? FlintEmergencyPassScope.load(state: state, at: .init(clock: .system),
                        defaults: .flintGroup, calendar: .autoupdatingCurrent) {
                    names.formUnion(fresh.stores)
                }
                for name in names {
                    guard FlintPassRecoveryGate.read().isRecovering else { throw FlintEmergencyPassError.applying }
                    ManagedSettingsStore(named: .init(name)).clearAllSettings()
                }
            }, resume: { state, now in
                try FlintEmergencyPassRuleEffects.resume(state: state, at: now)
            }, disable: { try FlintEmergencyPassRuleEffects.disable($0) },
            emit: { recovery in
                FlintSessionLifecycle.didUsePass(operationID: recovery.operationID,
                    sessions: recovery.scope.sessions, recoveryEnd: recovery.recoveryEnd,
                    at: recovery.acceptedAt.wall, origin: "app_ui", reason: "emergency_pass")
            }, publish: {
                FlintProtectionStatus.publishAfterCommit()
            }, transition: { work in
                session.withTransition {
                    FlintRuleStoreLock.withLock(environment: .live) {
                        work(); return true
                    } ?? false
                } ?? false
            }, prepareScope: {
                guard let ledger = FlintProtectionLedger.appGroup(), case .success = ledger.read() else {
                    throw FlintEmergencyPassError.unavailable
                }
                let arbiter = FlintRuleArbiter(environment: .live)
                guard arbiter.applyQueued(FlintScheduleRule.self), arbiter.applyQueued(FlintLimitRule.self),
                      arbiter.applyQueued(FlintOpenLimitRule.self) else { throw FlintEmergencyPassError.scopeUnavailable }
            })
    }
}

extension FlintEmergencyPassRuleEffects {
    static func disable(_ authorization: FlintPassDisableAuthorization, defaults: UserDefaults? = .flintGroup) throws {
        try applyCommittedDisable(authorization, ledger: FlintProtectionLedger.appGroup(), defaults: defaults)
        for rule in authorization.rules {
            ManagedSettingsStore(named: .init(rule.storeName)).clearAllSettings()
            FlintScheduling.stopMonitoring([rule.activityName])
        }
    }

    /// An ordinary caller has no capability to supply. A saved capability also stops working
    /// after acknowledgement, outbox replacement or any change to the exact pending list.
    static func applyCommittedDisable(_ authorization: FlintPassDisableAuthorization?, ledger: FlintProtectionLedger?,
                                      defaults: UserDefaults?) throws {
        guard let authorization, !authorization.rules.isEmpty, let ledger,
              case let .success(state) = ledger.peek(), state.hasValidPassRecovery,
              state.pass.usage == .used, state.pass.lastOperationID == authorization.operationID,
              let recovery = state.emergencyRecovery, recovery.operationID == authorization.operationID,
              recovery.acceptedAt == authorization.acceptedAt, recovery.scope.revision == authorization.scopeRevision,
              recovery.disabledRules == authorization.rules else { throw FlintEmergencyPassError.unavailable }
        try persistDisabledRules(authorization.rules, defaults: defaults)
    }

    /// Called only after durable capability validation. A replay doesn't increment an already
    /// disabled rule again; queued edits cannot re-enable it.
    private static func persistDisabledRules(_ exact: [FlintPassCommitment], defaults: UserDefaults?) throws {
        guard let oldSchedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults),
              let oldLimits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults),
              let oldOpens = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults) else {
            throw FlintEmergencyPassError.scopeUnavailable
        }
        let schedules = try disabling(oldSchedules, exact: exact)
        let limits = try disabling(oldLimits, exact: exact)
        let opens = try disabling(oldOpens, exact: exact)
        let ids = Set(exact.map(\.id))
        var sleep = try sleepConfiguration(from: defaults)
        var changedSleep = false
        if ids.contains("schedule:sleep.night"), sleep?.sleepAssist != nil, sleep?.sleepAssist != .off {
            sleep?.sleepAssist = .off; changedSleep = true
        }
        if ids.contains("schedule:sleep.morning"), sleep?.morningAssist != nil, sleep?.morningAssist != .off {
            sleep?.morningAssist = .off; changedSleep = true
        }
        guard FlintDesiredRules.save(schedules, key: .schedules, to: defaults),
              FlintDesiredRules.save(limits, key: .limits, to: defaults),
              FlintDesiredRules.save(opens, key: .openLimits, to: defaults),
              FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults) == schedules,
              FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults) == limits,
              FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults) == opens else {
            throw FlintEmergencyPassError.applying
        }
        if changedSleep {
            guard let defaults, var sleep, (sleep.revision ?? 0) < Int.max / 2 - 1 else {
                throw FlintEmergencyPassError.applying
            }
            sleep.contractVersion = 12; sleep.revision = (sleep.revision ?? 0) + 1
            // The committed pass outbox is the authority for this mutation. Calling the
            // ordinary Sleep editor here would re-enter rule authorization/outbox draining.
            let data = try JSONEncoder().encode(sleep)
            defaults.set(data, forKey: "flint.sleepConfig")
            guard defaults.synchronize(), defaults.data(forKey: "flint.sleepConfig") == data else {
                throw FlintEmergencyPassError.applying
            }
            defaults.set(1, forKey: "flint.sleepConfig.initialized")
            guard defaults.synchronize() else { throw FlintEmergencyPassError.applying }
        }
    }

    /// Preserve future/unknown config bytes instead of decoding defaults and overwriting them.
    static func sleepConfiguration(from defaults: UserDefaults?) throws -> FlintSleepConfig? {
        guard let defaults else { throw FlintEmergencyPassError.scopeUnavailable }
        guard let raw = defaults.object(forKey: "flint.sleepConfig") else {
            guard defaults.object(forKey: "flint.sleepConfig.initialized") == nil else {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            return nil
        }
        let keys: Set<String> = ["enabled", "schedule", "sleepAssist", "morningAssist", "allowGroupID",
            "morningAssistMinutes", "nightBreakLevel", "morningBreakLevel", "revision", "contractVersion"]
        guard let data = raw as? Data,
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              Set(fields.keys).isSubset(of: keys),
              let config = try? JSONDecoder().decode(FlintSleepConfig.self, from: data),
              (config.contractVersion == nil && config.revision == nil)
                || (config.contractVersion == 12 && (config.revision ?? 0) > 0) else {
            throw FlintEmergencyPassError.scopeUnavailable
        }
        return config
    }

    private static func disabling<Rule: FlintGatedRule>(_ rules: [Rule], exact: [FlintPassCommitment]) throws -> [Rule] {
        let prefix: String
        switch Rule.domain { case .schedules: prefix = "schedule:"; case .limits: prefix = "timeLimit:"; case .openLimits: prefix = "openLimit:" }
        return try rules.map { original in
            guard let reviewed = exact.first(where: { $0.id == prefix + original.id }) else { return original }
            if !original.enabled && original.protection?.queuedChange == nil { return original }
            // Ordinary mutations drain this outbox first. A mismatched enabled replacement
            // is never disabled by an old confirmation if storage changed outside that path.
            guard !original.enabled || reviewed.activityName == original.activityName,
                  var metadata = original.protection, metadata.revision < Int.max / 2 - 1 else {
                throw FlintEmergencyPassError.applying
            }
            var disabled = original
            disabled.enabled = false
            metadata.revision += 1; metadata.activityRevision = metadata.revision
            metadata.queuedChange = nil; metadata.occurrence = nil
            metadata.legacyRightsUntil = nil; metadata.usesLegacyActivityName = false
            disabled.protection = metadata
            return disabled
        }
    }
}
#endif
