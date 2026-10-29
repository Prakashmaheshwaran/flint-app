import Foundation
import CryptoKit
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Strict scope reads: unreadable or duplicate records fail the entire preview. Revision uses
/// the exact saved bytes plus materialized occurrence endpoints, so elapsed display time alone
/// doesn't stale a two-second hold, but a boundary, edit, replacement or target change does.
enum FlintEmergencyPassScope {
    private struct SessionSelection: Decodable {
        let sessionID: String
        let selection: FamilyActivitySelection
    }

    static func load(state: FlintLedgerState, at now: FlintLedgerInstant,
                     defaults: UserDefaults?, calendar: Calendar,
                     hasThresholdShield: (String) -> Bool = nativeThresholdShield) throws -> FlintPassScope {
        guard let defaults,
              let schedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults, at: now.wall),
              let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults, at: now.wall),
              let opens = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults, at: now.wall),
              unique(schedules.map(\.id)), unique(limits.map(\.id)), unique(opens.map(\.id)) else {
            throw FlintEmergencyPassError.scopeUnavailable
        }
        _ = try FlintEmergencyPassRuleEffects.sleepConfiguration(from: defaults)
        let active: FlintActiveSession?
        if let raw = defaults.object(forKey: "flint.activeSession") {
            guard let data = raw as? Data, let session = try? JSONDecoder().decode(FlintActiveSession.self, from: data),
                  session.contractVersion == nil || session.contractVersion == FlintActiveSession.currentContractVersion else {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            let clock = FlintClock(now: { now.wall }, monotonic: { now.monotonic },
                bootSessionID: { now.bootID }, calendar: { calendar })
            active = session.isLogicallyExpired(clock: clock) ? nil : session
        } else { active = nil }
        if let active {
            if let object = defaults.object(forKey: "flint.activeSession.selection") {
                guard let data = object as? Data,
                      let saved = try? JSONDecoder().decode(SessionSelection.self, from: data), saved.sessionID == active.id else {
                    throw FlintEmergencyPassError.scopeUnavailable
                }
            } else if active.contractVersion != nil {
                throw FlintEmergencyPassError.scopeUnavailable
            }
        }

        var commitments: [FlintPassCommitment] = []
        for rule in schedules where rule.enabled {
            guard rule.validationIssue == nil else {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            if let interval = rule.occurrence(at: now.wall) {
                commitments.append(.init(id: "schedule:\(rule.id)", name: rule.name,
                    kind: rule.id.hasPrefix("sleep.") ? .sleep : (rule.oneShot == nil ? .schedule : .oneShot),
                    naturalStart: interval.start, naturalEnd: interval.end,
                    storeName: rule.storeName, activityName: rule.monitorName, tier: rule.breakLevel))
            }
        }
        for rule in limits where rule.enabled {
            guard let day = rule.occurrence(at: now.wall) else { continue }
            let candidate = try thresholdRecord(rule, window: .init(start: day.start, end: day.end))
            let threshold = state.passLimitThresholds?[rule.id]
            let previous = state.emergencyRecovery?.scope.rules.first { $0.id == "timeLimit:\(rule.id)" }
            let wasEnforcing = rule.thresholdMinutes == 0
                || threshold?.provesExhaustion(of: candidate, at: now.wall) == true
                || (previous?.wasEnforcing == true && previous?.timeLimitThreshold?.provesExhaustion(of: candidate, at: now.wall) == true)
                || FlintLimitThresholds.hasReached(rule, at: now.wall, in: defaults)
                || (threshold == nil && state.emergencyRecovery == nil && hasThresholdShield(rule.storeName))
            commitments.append(.init(id: "timeLimit:\(rule.id)", name: rule.name, kind: .timeLimit,
                naturalStart: day.start, naturalEnd: day.end,
                storeName: rule.storeName, activityName: rule.activityName, wasEnforcing: wasEnforcing,
                tier: rule.breakLevel, timeLimitThreshold: candidate))
        }
        for rule in opens where rule.enabled {
            guard let day = rule.occurrence(at: now.wall) else { continue }
            commitments.append(.init(id: "openLimit:\(rule.id)", name: rule.name, kind: .openLimit,
                naturalStart: day.start, naturalEnd: day.end,
                storeName: rule.storeName, activityName: rule.activityName, tier: rule.breakLevel))
        }
        commitments.sort { $0.id < $1.id }
        var knownStores = ["flint", FlintWebRestrictionLifecycle.legacyStoreName] + schedules.map(\.storeName)
            + limits.map(\.storeName) + opens.map(\.storeName)
        // A crash after retiring a template can leave a positively identified last-good store.
        // Its retained receipt remains part of the global release scope; unknown receipts close
        // the preview rather than authorizing a blanket store sweep.
        guard case let .available(retained) = FlintRetainedProtection.load(from: defaults) else {
            throw FlintEmergencyPassError.scopeUnavailable
        }
        for activity in retained.keys {
            guard let domain = FlintRuleStoreKey.allCases.first(where: { activity.hasPrefix($0.prefix) }),
                  let id = FlintRuleActivity.ruleID(activity, prefix: domain.prefix), unique([id]) else {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            let prefix: String
            switch domain { case .schedules: prefix = "flint."; case .limits: prefix = "flintL."; case .openLimits: prefix = "flintO." }
            knownStores.append(prefix + id)
        }
        let stores = Array(Set(knownStores)).sorted()
        // The data encoding order of opaque token Sets isn't stable between encodes. Hash the
        // persisted bytes instead of re-encoding selections; occurrence metadata is canonical.
        var parts: [String] = []
        for key in FlintRuleStoreKey.allCases.map(\.rawValue) + ["flint.activeSession", "flint.activeSession.selection", "flint.selection",
            "flint.sleepConfig", "flint.focusFilterConfig", "flint.pomodoroConfig", "flint.webRestrictions", FlintRetainedProtection.key] {
            guard let object = defaults.object(forKey: key) else { parts.append("\(key)=absent"); continue }
            guard let data = object as? Data else { throw FlintEmergencyPassError.scopeUnavailable }
            // Non-rule configuration still belongs to the scope: malformed enabled Sleep or
            // Safari state cannot be silently treated as disabled while spending a global pass.
            if key == "flint.sleepConfig", (try? JSONDecoder().decode(FlintSleepConfig.self, from: data)) == nil {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            if key == "flint.focusFilterConfig", (try? JSONDecoder().decode(FlintFocusFilterConfig.self, from: data)) == nil {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            if key == "flint.pomodoroConfig", (try? JSONDecoder().decode(FlintPomodoroConfig.self, from: data)) == nil {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            if key == "flint.webRestrictions" {
                guard let config = try? JSONDecoder().decode(FlintWebRestrictions.self, from: data),
                      config.validationMessage == nil else { throw FlintEmergencyPassError.scopeUnavailable }
            }
            parts.append("\(key)=\(data.base64EncodedString())")
        }
        parts += commitments.map { "\($0.id):\($0.naturalStart.timeIntervalSince1970):\($0.naturalEnd.timeIntervalSince1970)" }
        parts += active.map { ["session:\($0.id)"] } ?? []
        let digest = SHA256.hash(data: Data(parts.joined(separator: "\n").utf8))
        let revision = digest.map { String(format: "%02x", $0) }.joined()
        return .init(revision: revision, sessions: active.map { [$0] } ?? [], rules: commitments, stores: stores)
    }

    private static func unique(_ ids: [String]) -> Bool {
        Set(ids).count == ids.count && ids.allSatisfy {
            !$0.isEmpty && $0.count <= 100 && $0.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || ".-_".contains($0)) }
        }
    }

    static func nativeThresholdShield(_ name: String) -> Bool {
        let store = ManagedSettingsStore(named: .init(name))
        return store.shield.applications != nil || store.shield.applicationCategories != nil
            || store.shield.webDomains != nil || store.shield.webDomainCategories != nil
    }

    static func thresholdRecord(_ rule: FlintLimitRule, window: DateInterval) throws -> FlintPassLimitThreshold {
        func tokens<T: Encodable>(_ values: Set<T>) throws -> [String] {
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            return try values.map { try encoder.encode([$0]).base64EncodedString() }.sorted()
        }
        return .init(window: window, thresholdMinutes: rule.thresholdMinutes,
            applications: Set(try tokens(rule.selection.applicationTokens)),
            categories: Set(try tokens(rule.selection.categoryTokens)),
            webDomains: Set(try tokens(rule.selection.webDomainTokens)),
            includeEntireCategory: rule.selection.includeEntireCategory)
    }
}

/// Native re-application is a consequence of ending recovery, never a new commitment. Only
/// currently enabled templates and their naturally active occurrences are considered.
enum FlintEmergencyPassRuleEffects {
    static func timeLimitReached(_ rule: FlintLimitRule, at now: Date, ledger: FlintProtectionLedger?) -> Bool {
        guard let ledger, case let .success(state) = ledger.peek(), state.hasValidPassRecovery,
              let window = rule.occurrence(at: now),
              let current = try? FlintEmergencyPassScope.thresholdRecord(rule, window: .init(start: window.start, end: window.end)) else { return false }
        return state.passLimitThresholds?[rule.id]?.provesExhaustion(of: current, at: now) == true
            || state.emergencyRecovery?.scope.rules.contains(where: {
                $0.id == "timeLimit:\(rule.id)" && $0.wasEnforcing
                    && $0.timeLimitThreshold?.provesExhaustion(of: current, at: now) == true
            }) == true
    }
    static func resume(state: FlintLedgerState, at now: FlintLedgerInstant,
                       environment: FlintRuleReloadEnvironment = .live,
                       web: FlintWebRestrictionLifecycle = .init()) throws {
        var reports: [String: FlintArmingHealth.DomainReport] = [:]
        var env = environment
        let record = env.record
        env.record = { domain, report in reports[domain] = report; record(domain, report) }
        // Queued changes whose natural boundary passed are already authorized. Reconcile
        // them before deriving today's resume scope; no missed occurrence is recreated.
        let arbiter = FlintRuleArbiter(environment: env)
        guard arbiter.applyQueued(FlintScheduleRule.self), arbiter.applyQueued(FlintLimitRule.self),
              arbiter.applyQueued(FlintOpenLimitRule.self) else { throw FlintEmergencyPassError.applying }
        FlintSchedulesController(environment: env).reload()
        FlintLimitsController(environment: env).reload()
        FlintOpenLimitsController(environment: env).reload(reapplyShields: false)
        let fresh = FlintLedgerInstant(clock: env.clock)
        // SDK registration can take time; observations may persist without the outer session
        // lock during it. Derive native writes from a fresh durable threshold view.
        guard let ledger = env.ledger(), case let .success(current) = ledger.peek(),
              current.hasValidPassRecovery,
              current.emergencyRecovery?.operationID == state.emergencyRecovery?.operationID else {
            throw FlintEmergencyPassError.applying
        }
        let scope = try FlintEmergencyPassScope.load(state: current, at: fresh, defaults: env.defaults,
            calendar: env.clock.calendar(), hasThresholdShield: { _ in false })
        guard let schedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: env.defaults),
              let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: env.defaults),
              let opens = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: env.defaults),
              let config = FlintWebRestrictions.loadForEnforcement(from: env.defaults), config.validationMessage == nil else {
            throw FlintEmergencyPassError.scopeUnavailable
        }
        func observed(_ name: String, domain: String) -> Bool {
            guard let report = reports[domain], report.observedActivities?.contains(name) == true,
                  !report.failures.contains(where: { $0.activityName == name || $0.activityName == domain }) else { return false }
            return env.center.activities.contains(.init(name)) && env.center.schedule(for: .init(name)) != nil
        }
        var incomplete = false
        for rule in schedules {
            guard rule.enabled, rule.occurrence(at: env.clock.now()) != nil else {
                env.clearStore(rule.storeName); continue
            }
            guard observed(rule.activityName, domain: "schedules") else { incomplete = true; continue }
            guard env.applyProtection(rule) else { incomplete = true; continue }
            web.reconcileRule(rule, config: config, now: env.clock.now(), calendar: rule.ruleCalendar)
        }
        for rule in limits {
            let date = env.clock.now()
            guard rule.enabled, scope.rules.contains(where: { $0.id == "timeLimit:\(rule.id)" && $0.wasEnforcing
                && $0.naturalStart <= date && date < $0.naturalEnd }) else {
                env.clearStore(rule.storeName); continue
            }
            guard observed(rule.activityName, domain: "limits") else { incomplete = true; continue }
            guard env.applyProtection(rule) else { incomplete = true; continue }
        }
        for rule in opens {
            guard rule.enabled, rule.occurrence(at: env.clock.now()) != nil else {
                env.clearStore(rule.storeName); continue
            }
            guard observed(rule.activityName, domain: "openLimits") else { incomplete = true; continue }
            guard env.applyProtection(rule) else { incomplete = true; continue }
        }
        if incomplete { throw FlintEmergencyPassError.applying }
    }

    /// Persist the observed threshold even when recovery suppresses the native apply. The
    /// original natural end bounds it; midnight/reboot never replays an expired occurrence.
    /// This observation runs before trying the session transition lock. Read without migration
    /// and match both callback revisions, so a late old callback cannot exhaust its replacement.
    static func noteThreshold(activity: String, event: String, at now: FlintLedgerInstant,
                              defaults: UserDefaults?, ledger: FlintProtectionLedger?) -> Bool {
        guard let rules = FlintDesiredRules.load(FlintLimitRule.self, key: .limits,
                from: defaults, at: now.wall, migrating: false),
              let rule = rules.first(where: { $0.enabled && $0.activityName == activity && $0.eventName == event }) else {
            return false
        }
        // Preserve the peer's exact revision/window-bound positive observation even if a
        // different ledger writer is busy. This is usage evidence, never pass authority.
        FlintLimitThresholds.markReached(rule, at: now.wall, in: defaults)
        return noteThreshold(rule: rule, at: now, ledger: ledger)
    }

    static func noteThreshold(rule: FlintLimitRule, at now: FlintLedgerInstant, ledger: FlintProtectionLedger?) -> Bool {
        guard rule.enabled, let day = rule.occurrence(at: now.wall), let ledger,
              let receipt = try? FlintEmergencyPassScope.thresholdRecord(rule, window: .init(start: day.start, end: day.end)) else { return false }
        return (try? ledger.transaction { state -> Void in
            state.passLimitThresholds = (state.passLimitThresholds ?? [:]).filter { $0.value.window.end > now.wall }
            state.passLimitThresholds?[rule.id] = receipt
        }.get()) != nil
    }
}
#endif
