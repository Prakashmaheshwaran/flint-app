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
    public struct ObservationEnvironment {
        public var authorized: () -> Bool?
        public var registeredActivities: () -> Set<String>
        public var clock: FlintClock
        public var loadActiveSession: () -> FlintActiveSession?
        public var hasActiveSessionRecord: () -> Bool
        public var loadArmingHealth: () -> FlintArmingHealth

        public init(authorized: @escaping () -> Bool?, registeredActivities: @escaping () -> Set<String>,
                    clock: FlintClock,
                    loadActiveSession: @escaping () -> FlintActiveSession? = { FlintGroupStore()?.loadActiveSession() },
                    hasActiveSessionRecord: @escaping () -> Bool = { FlintGroupStore()?.hasActiveSessionRecord() ?? false },
                    loadArmingHealth: @escaping () -> FlintArmingHealth = { FlintGroupStore()?.loadArmingHealth() ?? .init() }) {
            self.authorized = authorized
            self.registeredActivities = registeredActivities
            self.clock = clock
            self.loadActiveSession = loadActiveSession
            self.hasActiveSessionRecord = hasActiveSessionRecord
            self.loadArmingHealth = loadArmingHealth
        }

        public static var system: Self {
            Self(authorized: { AuthorizationCenter.shared.authorizationStatus == .approved },
                 registeredActivities: { Set(DeviceActivityCenter().activities.map(\.rawValue)) }, clock: .system)
        }
    }

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
        sessionOrigin: FlintProtectionStatusSnapshot.Session.Origin? = nil,
        timeIntegrity: FlintTimeIntegrity.Verdict? = nil, quotaCalendar: Calendar = .current,
        writer: Writer = .host,
        environment: ObservationEnvironment = .system,
        effects: PublicationEffects = .system
    ) -> FlintProtectionStatusSnapshot {
        let inputs = currentInputs(now: now, defaults: defaults, pass: pass, ledgerPass: ledgerPass,
            ruleObservations: ruleObservations, thresholdReached: thresholdReached,
            openEnforcing: openEnforcing, sessionArmed: sessionArmed,
            sessionKind: sessionKind, sessionOrigin: sessionOrigin,
            timeIntegrity: timeIntegrity, quotaCalendar: quotaCalendar, environment: environment)
        let snapshot = FlintProtectionStatusBuilder.make(inputs).withWriter(writer.rawValue)
        publish(snapshot, to: defaults, writer: writer, effects: effects)
        return snapshot
    }

    /// Uses the shared typed rule loaders and public session decode/presence APIs rather than
    /// duplicating their storage keys. No controller, registration or shield is mutated.
    /// Observations/thresholds can be supplied by the committing adapter without depending on
    /// B3b's upcoming types. Unknown threshold flags remain non-enforcing.
    public static func currentInputs(
        now: Date = Date(), defaults: UserDefaults? = .flintGroup,
        pass: FlintProtectionStatusSnapshot.Pass = .init(eligibility: .unavailable),
        ledgerPass: FlintLedgerState.Pass? = nil,
        ruleObservations: [String: RuleObservation] = [:],
        thresholdReached: [String: Bool] = [:], openEnforcing: [String: Bool] = [:],
        sessionArmed: Bool? = nil, sessionKind: FlintProtectionStatusSnapshot.Session.Kind = .session,
        sessionOrigin: FlintProtectionStatusSnapshot.Session.Origin? = nil,
        timeIntegrity: FlintTimeIntegrity.Verdict? = nil, quotaCalendar: Calendar = .current,
        environment: ObservationEnvironment = .system
    ) -> FlintProtectionStatusBuilder.Inputs {
        guard defaults != nil else {
            return .init(now: now, quotaCalendar: quotaCalendar, authorized: nil, pass: pass,
                         timeIntegrity: nil, available: false, timeIntegrityApplies: false)
        }
        let active = environment.loadActiveSession()
        let loadedSchedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults)
        let loadedLimits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults)
        let loadedOpens = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults)
        let unreadable = (active == nil && environment.hasActiveSessionRecord())
            || loadedSchedules == nil || loadedLimits == nil || loadedOpens == nil
        let schedules = loadedSchedules ?? []
        let limits = loadedLimits ?? []
        let opens = loadedOpens ?? []
        let health = environment.loadArmingHealth()
        let activities = environment.registeredActivities()
        func observation(id: String, activity: String, domain: String) -> RuleObservation {
            if let supplied = ruleObservations[id] { return supplied }
            // Aggregate health proves this rule armed only when the entire domain succeeded.
            let report = health.domains[domain]
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
                kind: sessionKind,
                origin: sessionOrigin ?? $0.origin.flatMap(FlintProtectionStatusSnapshot.Session.Origin.init(rawValue:)) ?? .appUI)
        }
        let clock = environment.clock
        let liveActive = active.flatMap { $0.isExpired(at: now) ? nil : $0 }
        // A recent observed wall-clock anomaly remains relevant without a manual session.
        // Old diagnostic history and repaired zone/registration events are not clock verdicts.
        let anomaly = FlintSessionHealth.load(from: defaults).lastTimeAnomaly.flatMap { anomaly -> FlintTimeIntegrity.Verdict? in
            guard anomaly.detectedAt <= now, now.timeIntervalSince(anomaly.detectedAt) <= 300 else { return nil }
            switch anomaly.kind {
            case .wallAhead: return .wallAhead(seconds: anomaly.seconds)
            case .wallBehind: return .wallBehind(seconds: anomaly.seconds)
            default: return nil
            }
        }
        let verdict = timeIntegrity ?? liveActive.map {
            FlintTimeIntegrity.verdict(wallStart: $0.startedAt, monotonicStart: $0.monotonicStart,
                bootSessionID: $0.bootSessionID, now: now, monotonicNow: clock.monotonic(),
                currentBootSessionID: clock.bootSessionID())
        } ?? anomaly
        let timeIntegrityApplies = timeIntegrity != nil || liveActive != nil || anomaly != nil
        let hasActiveCommitment = (session.map { !$0.session.isExpired(at: now) && $0.session.startedAt <= now }
            ?? false) || rules.contains { $0.isActive(at: now) }
        let projectedPass = ledgerPass.map {
            FlintProtectionStatusSnapshot.Pass($0,
                at: FlintLedgerInstant(wall: now, monotonic: clock.monotonic(), bootID: clock.bootSessionID()),
                hasActiveCommitment: hasActiveCommitment)
        } ?? pass
        return .init(now: now, quotaCalendar: quotaCalendar, activeSession: session,
            recordUnreadable: unreadable, rules: rules,
            authorized: environment.authorized(),
            pass: projectedPass, timeIntegrity: verdict, available: defaults != nil,
            timeIntegrityApplies: timeIntegrityApplies)
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
