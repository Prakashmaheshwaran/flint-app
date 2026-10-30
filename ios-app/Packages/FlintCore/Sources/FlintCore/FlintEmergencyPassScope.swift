import Foundation
import CryptoKit
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Strict scope reads: unreadable or duplicate records fail the entire preview. Revision uses
/// saved policy plus materialized occurrence endpoints, so elapsed display time alone
/// doesn't stale a two-second hold, but a boundary, edit, replacement or target change does.
enum FlintEmergencyPassScope {
    private struct SessionSelection: Decodable {
        let sessionID: String
        let selection: FamilyActivitySelection
    }

    static func load(state: FlintLedgerState, at now: FlintLedgerInstant,
                     defaults: UserDefaults?, calendar: Calendar, migrating: Bool = true,
                     hasThresholdShield: (String) -> Bool = nativeThresholdShield) throws -> FlintPassScope {
        guard let defaults,
              let schedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults,
                at: now.wall, migrating: migrating, ledgerState: { state }),
              let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults,
                at: now.wall, migrating: migrating, ledgerState: { state }),
              let opens = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults,
                at: now.wall, migrating: migrating, ledgerState: { state }),
              unique(schedules.map(\.id)), unique(limits.map(\.id)), unique(opens.map(\.id)) else {
            throw FlintEmergencyPassError.scopeUnavailable
        }
        _ = try FlintEmergencyPassRuleEffects.sleepConfiguration(from: defaults)
        let sessionClock = FlintClock(now: { now.wall }, monotonic: { now.monotonic },
            bootSessionID: { now.bootID }, calendar: { calendar })
        let storedSession: FlintActiveSession?
        if let raw = defaults.object(forKey: "flint.activeSession") {
            guard let data = raw as? Data, let session = try? JSONDecoder().decode(FlintActiveSession.self, from: data),
                  session.contractVersion == nil || session.contractVersion == FlintActiveSession.currentContractVersion else {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            storedSession = session
        } else { storedSession = nil }
        guard (state.version < 3 || state.sessionAuthority != nil),
              !FlintSessionAuthority.recordIsUnavailable(storedSession,
                recordPresent: defaults.object(forKey: "flint.activeSession") != nil,
                authority: state.sessionAuthority, authorityVersion: state.sessionAuthorityVersion,
                clock: sessionClock) else { throw FlintEmergencyPassError.scopeUnavailable }
        let active = FlintSessionAuthority.observedProjection(storedSession,
            authority: state.sessionAuthority, clock: sessionClock).flatMap {
                $0.isLogicallyExpired(clock: sessionClock) ? nil : $0
            }
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

        let ruleClock = FlintClock(now: { now.wall }, monotonic: { now.monotonic },
            bootSessionID: { now.bootID }, calendar: { calendar })
        func trustedTime<Rule: FlintGatedRule>(_ rule: Rule) -> FlintLedgerInstant {
            .init(wall: FlintRuleOccurrenceClock.date(for: rule, clock: ruleClock),
                monotonic: now.monotonic, bootID: now.bootID)
        }
        var commitments: [FlintPassCommitment] = []
        for rule in schedules where rule.enabled {
            guard rule.validationIssue == nil else {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            if let interval = rule.occurrence(at: now.wall, clock: ruleClock) {
                commitments.append(.init(id: "schedule:\(rule.id)", name: rule.name,
                    kind: rule.id.hasPrefix("sleep.") ? .sleep : (rule.oneShot == nil ? .schedule : .oneShot),
                    naturalStart: interval.start, naturalEnd: interval.end,
                    storeName: rule.storeName, activityName: rule.monitorName, tier: rule.breakLevel,
                    trustedRuleTime: trustedTime(rule)))
            }
        }
        for rule in limits where rule.enabled {
            guard let day = rule.occurrence(at: now.wall, clock: ruleClock) else { continue }
            let trustedDate = FlintRuleOccurrenceClock.date(for: rule, clock: ruleClock)
            let candidate = try thresholdRecord(rule, window: .init(start: day.start, end: day.end))
            let threshold = state.passLimitThresholds?[rule.id]
            let previous = state.emergencyRecovery?.scope.rules.first { $0.id == "timeLimit:\(rule.id)" }
            let wasEnforcing = rule.thresholdMinutes == 0
                || threshold?.provesExhaustion(of: candidate, at: trustedDate) == true
                || (previous?.wasEnforcing == true && previous?.timeLimitThreshold?.provesExhaustion(of: candidate, at: trustedDate) == true)
                || FlintLimitThresholds.hasReached(rule, at: now.wall, in: defaults, clock: ruleClock)
                || (threshold == nil && state.emergencyRecovery == nil && hasThresholdShield(rule.storeName))
            commitments.append(.init(id: "timeLimit:\(rule.id)", name: rule.name, kind: .timeLimit,
                naturalStart: day.start, naturalEnd: day.end,
                storeName: rule.storeName, activityName: rule.activityName, wasEnforcing: wasEnforcing,
                tier: rule.breakLevel, timeLimitThreshold: candidate, trustedRuleTime: trustedTime(rule)))
        }
        for rule in opens where rule.enabled {
            guard let day = rule.occurrence(at: now.wall, clock: ruleClock) else { continue }
            commitments.append(.init(id: "openLimit:\(rule.id)", name: rule.name, kind: .openLimit,
                naturalStart: day.start, naturalEnd: day.end,
                storeName: rule.storeName, activityName: rule.activityName, tier: rule.breakLevel,
                trustedRuleTime: trustedTime(rule)))
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
        // Checkpoints re-encode opaque Sets. Canonicalize known Set fields and exclude only
        // explicit pure bookkeeping; all other saved fields remain policy by default.
        var parts: [String] = []
        for key in FlintRuleStoreKey.allCases.map(\.rawValue) + ["flint.activeSession", "flint.activeSession.selection", "flint.selection",
            "flint.sleepConfig", "flint.focusFilterConfig", "flint.pomodoroConfig", "flint.webRestrictions", FlintRetainedProtection.key] {
            guard let object = defaults.object(forKey: key) else { parts.append("\(key)=absent"); continue }
            guard let data = object as? Data else {
                // Global recovery releases Safari's native stores independently of readable
                // desired web intent. Preserve the bad value; the web adapter reports health.
                if key == "flint.webRestrictions" { parts.append("\(key)=unavailable"); continue }
                throw FlintEmergencyPassError.scopeUnavailable
            }
            // Non-web configuration remains strict; an unknown Sleep/Focus commitment must
            // not silently disappear from the reviewed global scope.
            if key == "flint.sleepConfig", (try? JSONDecoder().decode(FlintSleepConfig.self, from: data)) == nil {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            if key == "flint.focusFilterConfig", (try? JSONDecoder().decode(FlintFocusFilterConfig.self, from: data)) == nil {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            if key == "flint.pomodoroConfig", (try? JSONDecoder().decode(FlintPomodoroConfig.self, from: data)) == nil {
                throw FlintEmergencyPassError.scopeUnavailable
            }
            let policy: Data
            if FlintRuleStoreKey.allCases.contains(where: { $0.rawValue == key }) {
                policy = try canonicalRulePolicy(data)
            } else if key == "flint.activeSession" {
                policy = try FlintSessionAuthority.scopePolicy(data)
            } else { policy = data }
            parts.append("\(key)=\(policy.base64EncodedString())")
        }
        parts += commitments.map { "\($0.id):\($0.naturalStart.timeIntervalSince1970):\($0.naturalEnd.timeIntervalSince1970)" }
        parts += active.map { ["session:\($0.id)"] } ?? []
        let digest = SHA256.hash(data: Data(parts.joined(separator: "\n").utf8))
        let revision = digest.map { String(format: "%02x", $0) }.joined()
        return .init(revision: revision, sessions: active.map { [$0] } ?? [], rules: commitments, stores: stores)
    }

    private static func canonicalRulePolicy(_ data: Data) throws -> Data {
        guard var rules = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            throw FlintEmergencyPassError.scopeUnavailable
        }
        func sortedSet(_ values: [Any]) throws -> [Any] {
            try values.map { value in
                (value, try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]))
            }.sorted { $0.1.lexicographicallyPrecedes($1.1) }.map(\.0)
        }
        for index in rules.indices {
            if var protection = rules[index]["protection"] as? [String: Any] {
                // This is an exclusion allowlist, never a list of policy fields to include.
                for key in ["timeAnchor", "carrierRepair"] { protection.removeValue(forKey: key) }
                rules[index]["protection"] = protection
            }
            if var schedule = rules[index]["schedule"] as? [String: Any], let days = schedule["daysOfWeek"] as? [Any] {
                schedule["daysOfWeek"] = try sortedSet(days); rules[index]["schedule"] = schedule
            }
            if var selection = rules[index]["selection"] as? [String: Any] {
                for key in ["applicationTokens", "categoryTokens", "webDomainTokens", "applications", "categories", "webDomains"] {
                    if let values = selection[key] as? [Any] { selection[key] = try sortedSet(values) }
                }
                rules[index]["selection"] = selection
            }
        }
        return try JSONSerialization.data(withJSONObject: rules, options: [.sortedKeys])
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
    static func timeLimitReached(_ rule: FlintLimitRule, at now: Date, ledger: FlintProtectionLedger?,
                                 clock: FlintClock? = nil) -> Bool {
        var resolved = clock ?? .system; resolved.now = { now }
        let trustedDate = FlintRuleOccurrenceClock.date(for: rule, clock: resolved)
        guard let ledger, case let .success(state) = ledger.peek(), state.hasValidPassRecovery,
              let window = rule.occurrence(at: now, clock: resolved),
              let current = try? FlintEmergencyPassScope.thresholdRecord(rule, window: .init(start: window.start, end: window.end)) else { return false }
        return state.passLimitThresholds?[rule.id]?.provesExhaustion(of: current, at: trustedDate) == true
            || state.emergencyRecovery?.scope.rules.contains(where: {
                $0.id == "timeLimit:\(rule.id)" && $0.wasEnforcing
                    && $0.timeLimitThreshold?.provesExhaustion(of: current, at: trustedDate) == true
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
            guard rule.enabled, rule.occurrence(at: env.clock.now(), clock: env.clock) != nil else {
                env.clearStore(rule.storeName); continue
            }
            guard observed(rule.activityName, domain: "schedules") else { incomplete = true; continue }
            guard env.applyProtection(rule) else { incomplete = true; continue }
            web.reconcileRule(rule, config: config, now: env.clock.now(), calendar: rule.ruleCalendar, clock: env.clock)
        }
        for rule in limits {
            let date = FlintRuleOccurrenceClock.date(for: rule, clock: env.clock)
            guard rule.enabled, scope.rules.contains(where: { $0.id == "timeLimit:\(rule.id)" && $0.wasEnforcing
                && $0.naturalStart <= date && date < $0.naturalEnd }) else {
                env.clearStore(rule.storeName); continue
            }
            guard observed(rule.activityName, domain: "limits") else { incomplete = true; continue }
            guard env.applyProtection(rule) else { incomplete = true; continue }
        }
        for rule in opens {
            guard rule.enabled, rule.occurrence(at: env.clock.now(), clock: env.clock) != nil else {
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
        let clock = FlintClock(now: { now.wall }, monotonic: { now.monotonic },
            bootSessionID: { now.bootID }, calendar: { rule.ruleCalendar })
        FlintLimitThresholds.markReached(rule, at: now.wall, in: defaults, clock: clock)
        return noteThreshold(rule: rule, at: now, ledger: ledger, rules: rules)
    }

    static func noteThreshold(rule: FlintLimitRule, at now: FlintLedgerInstant, ledger: FlintProtectionLedger?,
                              rules: [FlintLimitRule]? = nil) -> Bool {
        let clock = FlintClock(now: { now.wall }, monotonic: { now.monotonic },
            bootSessionID: { now.bootID }, calendar: { rule.ruleCalendar })
        guard rule.enabled, let day = rule.occurrence(at: now.wall, clock: clock), let ledger,
              let receipt = try? FlintEmergencyPassScope.thresholdRecord(rule, window: .init(start: day.start, end: day.end)) else { return false }
        return (try? ledger.transaction { state -> Void in
            state.passLimitThresholds = (state.passLimitThresholds ?? [:]).filter { id, threshold in
                // An observation has no authority to discard a peer it cannot read. Known
                // receipts expire only on that rule's trusted timeline, never callback wall time.
                guard let peer = (rules ?? [rule]).first(where: { $0.id == id }) else { return true }
                return !FlintRuleOccurrenceClock.isOver(.init(start: threshold.window.start, end: threshold.window.end),
                    for: peer, clock: clock)
            }
            state.passLimitThresholds?[rule.id] = receipt
        }.get()) != nil
    }
}
#endif
