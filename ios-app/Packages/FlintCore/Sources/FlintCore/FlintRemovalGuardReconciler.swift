import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// The only owner of the derived removal setting. OS assignment/read-back proves requested
/// configuration, not that a particular iOS version actually prevents deletion.
public struct FlintRemovalGuardReconciler {
    public static let storeName = "flint.guard"
    public static let migrationKey = "flint.removalGuard.sessionStoreMigration.v1"

    public struct Report: Equatable {
        public var decision: FlintRemovalGuard.Decision = .keep
        public var applied = false
        public var migrated = false
        public var until: Date?
    }

    struct Environment {
        var clock: FlintClock
        var withAuthority: (() -> Void) -> Bool
        var inventory: (FlintClock) -> [FlintRemovalGuardOwner]?
        var isAuthorized: () -> Bool
        var write: (_ store: String, _ deny: Bool?) throws -> Void
        var migrationComplete: () throws -> Bool
        var clearLegacy: () throws -> Void
        var markMigration: () throws -> Void
        var preference: FlintRemovalGuardPreference.Store?
    }

    let environment: Environment
    init(environment: Environment) { self.environment = environment }

    @discardableResult
    public static func reconcile() -> Report { Self(environment: .live).reconcile() }

    @discardableResult
    func reconcile() -> Report {
        var report = Report()
        let env = environment
        _ = env.withAuthority {
            let clock = FlintLedgerInstant(clock: env.clock).clock
            let owners = env.inventory(clock)
            report.decision = FlintRemovalGuard.decide(owners: owners, clock: clock)
            guard report.decision != .keep else { return }
            // Permission loss never authorizes arming. Clearing remains available, especially
            // during committed pass recovery; do not use permitsApplication here.
            if report.decision == .deny && !env.isAuthorized() { return }
            do {
                try env.write(Self.storeName, report.decision == .deny ? true : nil)
                report.applied = true
                if report.decision == .deny {
                    report.until = owners?.filter {
                        FlintRemovalGuard.decide(owners: [$0], clock: clock) == .deny
                    }.compactMap(\.logicalEnd?.wall).max()
                }
                // Release is an idempotent successful apply, including an already-clear
                // guard. Keep and failed assignment cannot retire the old writer's value.
                if try !env.migrationComplete() {
                    try env.clearLegacy()
                    try env.markMigration()
                }
                report.migrated = try env.migrationComplete()
            } catch { /* Retry the exact derived-write → old-clear → marker order next time. */ }
            if let store = env.preference, case let .value(value, _) = store.load(),
               value.deferredOffUntil != nil,
               case .allow = FlintRemovalGuard.decidePreferenceChange(to: false, owners: owners, clock: clock) {
                try? store.save(.init(enabled: false, disclosedAt: value.disclosedAt,
                                      disclosureVersion: value.disclosureVersion))
            }
        }
        return report
    }
}

extension FlintRemovalGuardReconciler.Environment {
    static var live: Self {
        var rules = FlintRuleReloadEnvironment.live
        // This adapter only reads committed recovery. Replaying the pass here would recurse
        // into its release closure and can deadlock. Preserve the same lock order without
        // calling prepareForPolicyMutation or gating release on permitsApplication.
        rules.withTransitionBoundary = { body in
            FlintSessionController().withTransition { body(); return true } ?? false
        }
        let defaults = UserDefaults.flintGroup
        return .init(clock: .system,
            withAuthority: { work in
                FlintRuleStoreLock.withLock(environment: rules) { work(); return true } ?? false
            }, inventory: { clock in
                FlintRemovalGuardInventory.load(environment: rules, store: FlintGroupStore(), clock: clock)
            }, isAuthorized: { AuthorizationCenter.shared.authorizationStatus == .approved },
            write: { name, value in
                let store = ManagedSettingsStore(named: .init(name))
                store.application.denyAppRemoval = value
                guard store.application.denyAppRemoval == value else { throw FlintRemovalGuardPreference.Error.readBackFailed }
            }, migrationComplete: {
                guard let defaults, defaults.synchronize() else { throw FlintRemovalGuardPreference.Error.unavailable }
                guard let marker = defaults.object(forKey: FlintRemovalGuardReconciler.migrationKey) else { return false }
                guard marker as? String == "complete-v1" else { throw FlintRemovalGuardPreference.Error.unavailable }
                return true
            }, clearLegacy: {
                let legacy = ManagedSettingsStore(named: .init("flint"))
                legacy.application.denyAppRemoval = nil
                guard legacy.application.denyAppRemoval == nil else { throw FlintRemovalGuardPreference.Error.readBackFailed }
            }, markMigration: {
                guard let defaults else { throw FlintRemovalGuardPreference.Error.unavailable }
                defaults.set("complete-v1", forKey: FlintRemovalGuardReconciler.migrationKey)
                guard defaults.synchronize(), defaults.string(forKey: FlintRemovalGuardReconciler.migrationKey) == "complete-v1" else {
                    throw FlintRemovalGuardPreference.Error.readBackFailed
                }
            }, preference: defaults.map { .init(defaults: $0) })
    }
}

/// Complete positive inventory, built under session transition → rule lock → ledger. A
/// failed read in ANY domain is nil; an inactive template contributes no guard ownership.
enum FlintRemovalGuardInventory {
    static func load(environment env: FlintRuleReloadEnvironment, store: FlintSessionStateStoring?,
                     clock: FlintClock) -> [FlintRemovalGuardOwner]? {
        guard let defaults = env.defaults, defaults.synchronize(),
              let store, store.isProtectionStateReadable(), let ledger = env.ledger(),
              case .success = ledger.peek() else { return nil }
        let result = ledger.transaction { state -> [FlintRemovalGuardOwner]? in
            let center = env.center
            // DeviceActivityCenter exposes a synchronous inventory, with no throwing API.
            // Only this positive read can prove notObserved. Test/future failing readers
            // pass nil to project; there is no fallback empty set.
            let activities = Set(center.activities.filter { center.schedule(for: $0) != nil }.map(\.rawValue))
            return project(session: store.loadActiveSession(), recordPresent: store.hasActiveSessionRecord(),
                state: state,
                schedules: FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults,
                    at: clock.now(), migrating: false, recoveryDirectory: env.recoveryDirectory),
                limits: FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults,
                    at: clock.now(), migrating: false, recoveryDirectory: env.recoveryDirectory),
                openLimits: FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults,
                    at: clock.now(), migrating: false, recoveryDirectory: env.recoveryDirectory),
                activities: activities, clock: clock)
        }
        return (try? result.get()) ?? nil
    }

    static func project(session: FlintActiveSession?, recordPresent: Bool, state: FlintLedgerState?,
                        schedules: [FlintScheduleRule]?, limits: [FlintLimitRule]?, openLimits: [FlintOpenLimitRule]?,
                        activities: Set<String>?, clock: FlintClock) -> [FlintRemovalGuardOwner]? {
        guard let state, let schedules, let limits, let openLimits, let activities,
              !FlintSessionAuthority.recordIsUnavailable(session, recordPresent: recordPresent,
                authority: state.sessionAuthority, authorityVersion: state.sessionAuthorityVersion, clock: clock),
              state.sessionAuthority?.latest?.phase != .installing else { return nil }
        let pass = FlintPassRecoveryGate.evaluate(state, at: .init(clock: clock))
        guard pass != .unavailable else { return nil }
        var owners: [FlintRemovalGuardOwner] = []
        if let session = FlintSessionAuthority.observedProjection(session, authority: state.sessionAuthority, clock: clock) {
            guard session.remainingCheckpoint?.isValid != false else { return nil }
            let remaining = session.logicalRemaining(clock: clock)
            let end = remaining.map { deadline($0, clock: clock) }
            let boot = clock.bootSessionID()
            let anchorBoot = session.remainingCheckpoint?.bootID ?? session.bootSessionID
            let anchorMono = session.remainingCheckpoint?.monotonic ?? session.monotonicStart
            let known = boot != nil && boot == anchorBoot && anchorMono.map { clock.monotonic() >= $0 } == true
            owners.append(.init(cause: .init(causeID: session.id, occurrenceID: session.id,
                kind: .session, tier: session.breakLevel, isActive: remaining.map { $0 > 0 } ?? true,
                end: end?.wall, legacyRights: session.contractVersion == nil),
                registration: activities.contains(session.monitorName) ? .observed : .notObserved,
                removalGuard: session.removalGuard, logicalEnd: end,
                timeKnown: known,
                passRecovered: pass.isRecovering))
        }
        guard append(schedules, to: &owners, activities: activities, recovering: pass.isRecovering, clock: clock),
              append(limits, to: &owners, activities: activities, recovering: pass.isRecovering, clock: clock),
              append(openLimits, to: &owners, activities: activities, recovering: pass.isRecovering, clock: clock) else { return nil }
        return owners
    }

    private static func append<Rule: FlintGatedRule>(_ rules: [Rule], to owners: inout [FlintRemovalGuardOwner],
        activities: Set<String>, recovering: Bool, clock: FlintClock) -> Bool {
        guard Set(rules.map(\.id)).count == rules.count else { return false }
        for rule in rules {
            guard rule.validationIssue == nil else { return false }
            // Never materialize a template in this read path, nor borrow its opt-in for a
            // different occurrence. Only the persisted accepted window contributes.
            guard let metadata = rule.protection, let window = metadata.occurrence else { continue }
            let timeline = FlintRuleOccurrenceClock.date(for: rule, clock: clock)
            let end = deadline(max(0, window.end.timeIntervalSince(timeline)), clock: clock)
            let anchor = metadata.timeAnchor
            // A reboot fallback is bounded, but a positive remaining interval must first
            // be checkpointed by the owner layer. Re-reading an unpersisted wall fallback
            // must not re-arm or repeatedly extend it after further wall-clock changes.
            let known = anchor?.isValid == true && anchor?.bootID != nil
                && anchor?.bootID == clock.bootSessionID()
                && anchor.map { clock.monotonic() >= $0.monotonic } == true
            owners.append(.init(cause: .init(causeID: Rule.domain.rawValue + ":" + rule.id,
                occurrenceID: metadata.incarnation + ":" + window.identity, revision: metadata.activityRevision,
                kind: rule.kind, tier: rule.breakLevel, isActive: rule.enabled && window.contains(timeline),
                end: end.wall, legacyRights: metadata.legacyRightsUntil != nil),
                registration: activities.contains(rule.activityName) ? .observed : .notObserved,
                removalGuard: metadata.removalGuard, logicalEnd: end, timeKnown: known,
                passRecovered: recovering))
        }
        return true
    }

    private static func deadline(_ remaining: TimeInterval, clock: FlintClock) -> FlintLedgerInstant {
        .init(wall: clock.now().addingTimeInterval(remaining), monotonic: clock.monotonic() + remaining,
              bootID: clock.bootSessionID())
    }
}
#endif
