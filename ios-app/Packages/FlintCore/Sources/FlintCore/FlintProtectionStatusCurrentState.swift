import Foundation
#if canImport(FamilyControls) && canImport(DeviceActivity)
import FamilyControls
import DeviceActivity

extension FlintProtectionStatusBuilder.RuleInput {
    /// Projects only rule metadata; the FamilyActivitySelection never crosses this boundary.
    public static func schedule(_ rule: FlintScheduleRule,
                                kind: FlintProtectionStatusSnapshot.Kind = .recurringSchedule,
                                armed: Bool, observed: Bool, now: Date, calendar: Calendar) -> Self {
        .schedule(id: rule.id, kind: kind, displayName: rule.name, tier: rule.breakLevel,
                  schedule: rule.schedule, enabled: rule.enabled, armed: armed,
                  observed: observed, now: now, calendar: calendar)
    }
}

extension FlintProtectionStatus {
    public struct RuleObservation: Equatable, Sendable {
        public let armed: Bool
        public let observed: Bool

        public init(armed: Bool, observed: Bool) {
            self.armed = armed
            self.observed = observed
        }
    }

    /// One-line commit hook, intentionally not installed in controllers/extensions here.
    /// Supply the committed ledger's pass value, or an already projected pass status. This
    /// never calls ledger.read(): that API transacts/writes and would nest the commit's lock.
    /// Missing established state must not imply a free pass.
    @discardableResult
    public static func publishFromCurrentState(
        now: Date = Date(), defaults: UserDefaults? = .flintGroup,
        pass: FlintProtectionStatusSnapshot.Pass = .init(eligibility: .unavailable),
        ledgerPass: FlintLedgerState.Pass? = nil,
        ruleObservations: [String: RuleObservation] = [:],
        thresholdReached: [String: Bool] = [:], openEnforcing: [String: Bool] = [:],
        sessionArmed: Bool? = nil, sessionKind: FlintProtectionStatusSnapshot.Session.Kind = .session,
        sessionOrigin: FlintProtectionStatusSnapshot.Session.Origin = .appUI,
        timeIntegrity: FlintTimeIntegrity.Verdict? = nil, quotaCalendar: Calendar = .current,
        effects: PublicationEffects = .system
    ) -> FlintProtectionStatusSnapshot {
        let inputs = currentInputs(now: now, defaults: defaults, pass: pass, ledgerPass: ledgerPass,
            ruleObservations: ruleObservations, thresholdReached: thresholdReached,
            openEnforcing: openEnforcing, sessionArmed: sessionArmed,
            sessionKind: sessionKind, sessionOrigin: sessionOrigin,
            timeIntegrity: timeIntegrity, quotaCalendar: quotaCalendar)
        let snapshot = FlintProtectionStatusBuilder.make(inputs)
        publish(snapshot, to: defaults, effects: effects)
        return snapshot
    }

    /// Strictly decodes the existing public model formats rather than using the legacy
    /// loaders' corrupt-to-empty fallbacks. No controller, registration or shield is mutated.
    /// Observations/thresholds can be supplied by the committing adapter without depending on
    /// B3b's upcoming types. Unknown threshold flags remain non-enforcing.
    public static func currentInputs(
        now: Date = Date(), defaults: UserDefaults? = .flintGroup,
        pass: FlintProtectionStatusSnapshot.Pass = .init(eligibility: .unavailable),
        ledgerPass: FlintLedgerState.Pass? = nil,
        ruleObservations: [String: RuleObservation] = [:],
        thresholdReached: [String: Bool] = [:], openEnforcing: [String: Bool] = [:],
        sessionArmed: Bool? = nil, sessionKind: FlintProtectionStatusSnapshot.Session.Kind = .session,
        sessionOrigin: FlintProtectionStatusSnapshot.Session.Origin = .appUI,
        timeIntegrity: FlintTimeIntegrity.Verdict? = nil, quotaCalendar: Calendar = .current
    ) -> FlintProtectionStatusBuilder.Inputs {
        var unreadable = false
        func read<T: Decodable>(_ key: String, as type: T.Type) -> T? {
            guard let object = defaults?.object(forKey: key) else { return nil }
            guard let data = object as? Data, let value = try? JSONDecoder().decode(type, from: data) else {
                unreadable = true
                return nil
            }
            return value
        }
        let active = read("flint.activeSession", as: FlintActiveSession.self)
        let schedules = read("flint.rules", as: [FlintScheduleRule].self) ?? []
        let limits = read("flint.limits", as: [FlintLimitRule].self) ?? []
        let opens = read("flint.openLimit.rules", as: [FlintOpenLimitRule].self) ?? []
        let health = read("flint.armingHealth", as: FlintArmingHealth.self)
        let activities = Set(DeviceActivityCenter().activities.map(\.rawValue))
        func observation(id: String, activity: String, domain: String) -> RuleObservation {
            if let supplied = ruleObservations[id] { return supplied }
            // Aggregate health proves this rule armed only when the entire domain succeeded.
            let report = health?.domains[domain]
            let armed = report.map {
                $0.updatedAt <= now && $0.enabled > 0 && $0.armed == $0.enabled && $0.failures.isEmpty
            } ?? false
            return RuleObservation(armed: armed, observed: activities.contains(activity))
        }
        var rules = schedules.map { rule -> FlintProtectionStatusBuilder.RuleInput in
            let status = observation(id: rule.id, activity: rule.monitorName, domain: "schedules")
            return .schedule(rule, kind: rule.id.hasPrefix("sleep.") ? .sleep : .recurringSchedule,
                armed: status.armed, observed: status.observed,
                now: now, calendar: quotaCalendar)
        }
        rules += limits.map { rule in
            let status = observation(id: rule.id, activity: rule.activityName, domain: "limits")
            return .init(id: rule.id, kind: .timeLimit, displayName: rule.name, tier: rule.breakLevel,
                enabled: rule.enabled, armed: status.armed, observed: status.observed,
                active: rule.enabled, thresholdReached: thresholdReached[rule.id])
        }
        rules += opens.map { rule in
            let status = observation(id: rule.id, activity: rule.activityName, domain: "openLimits")
            return .init(id: rule.id, kind: .openLimit, displayName: rule.name, tier: rule.breakLevel,
                enabled: rule.enabled, armed: status.armed, observed: status.observed,
                active: rule.enabled, enforcing: openEnforcing[rule.id] == true,
                opensAllowed: rule.opensAllowed)
        }
        let session = active.map {
            FlintProtectionStatusBuilder.SessionInput($0,
                armed: sessionArmed ?? activities.contains($0.monitorName),
                kind: sessionKind, origin: sessionOrigin)
        }
        let clock = FlintClock.system
        let verdict = timeIntegrity ?? active.map {
            FlintTimeIntegrity.verdict(wallStart: $0.startedAt, monotonicStart: $0.monotonicStart,
                bootSessionID: $0.bootSessionID, now: now, monotonicNow: clock.monotonic(),
                currentBootSessionID: clock.bootSessionID())
        } ?? .unverifiable
        let hasActiveCommitment = (session.map { !$0.session.isExpired(at: now) && $0.session.startedAt <= now }
            ?? false) || rules.contains { $0.isActive(at: now) }
        let projectedPass = ledgerPass.map {
            FlintProtectionStatusSnapshot.Pass($0,
                at: FlintLedgerInstant(wall: now, monotonic: clock.monotonic(), bootID: clock.bootSessionID()),
                hasActiveCommitment: hasActiveCommitment)
        } ?? pass
        return .init(now: now, quotaCalendar: quotaCalendar, activeSession: session,
            recordUnreadable: unreadable, rules: rules,
            authorized: AuthorizationCenter.shared.authorizationStatus == .approved,
            pass: projectedPass, timeIntegrity: verdict, available: defaults != nil)
    }
}
#endif

extension FlintProtectionStatusSnapshot.Pass {
    /// Uses the ledger's elapsed-time status; never projects its raw stored remainders.
    public init(_ pass: FlintLedgerState.Pass, at instant: FlintLedgerInstant,
                hasActiveCommitment: Bool) {
        let status = pass.status(at: instant, hasActiveCommitment: hasActiveCommitment)
        let eligibility: Eligibility
        let next: Date?
        switch status.eligibility {
        case .available: eligibility = .available; next = nil
        case .nothingToRecover: eligibility = .nothingToRecover; next = nil
        case .unavailable: eligibility = .unavailable; next = nil
        case .coolingDown(let remaining):
            eligibility = .coolingDown
            next = instant.wall.addingTimeInterval(remaining)
        }
        self.init(eligibility: eligibility, nextAvailableAt: next,
                  recoveryUntil: status.isRecovering ? instant.wall.addingTimeInterval(status.recoveryRemaining) : nil)
    }
}
