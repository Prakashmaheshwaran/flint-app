import Foundation

/// Pure scope decisions. Time and Open Limits never acquire web-content restrictions.
public enum FlintWebRestrictionPolicy {
    public enum Cause {
        case session(FlintActiveSession?)
        case schedule(FlintSchedule, enabled: Bool)
        case timeLimit
        case openLimit
    }

    public static func shouldApply(
        to cause: Cause,
        config: FlintWebRestrictions,
        now: Date = Date(),
        calendar: Calendar = .current,
        clock: FlintClock? = nil
    ) -> Bool {
        switch cause {
        case let .session(session):
            guard config.enabled, let session else { return false }
            // With a clock, logical time decides: a same-boot wall jump can't release it (§9).
            return session.isEnforcing(at: now, clock: clock)
        case let .schedule(schedule, enabled):
            return config.applyDuringSchedules && enabled && schedule.isWindowActive(at: now, calendar: calendar)
        case .timeLimit, .openLimit:
            return false
        }
    }

    #if canImport(FamilyControls)
    public static func shouldApply(to rule: FlintScheduleRule, config: FlintWebRestrictions,
                                  now: Date, calendar: Calendar = .current, clock: FlintClock? = nil) -> Bool {
        guard config.applyDuringSchedules, rule.enabled else { return false }
        if rule.protection != nil || rule.oneShot != nil { return rule.occurrence(at: now, clock: clock) != nil }
        return rule.schedule.isWindowActive(at: now, calendar: calendar)
    }
    #endif
}

/// `apply` receives an eligible cause; implementations must not treat the session toggle as a
/// master switch. `clear` releases only webContent.blockedByFilter and media.denyExplicitContent.
public protocol FlintWebRestrictionWriting {
    func apply(_ config: FlintWebRestrictions, toStore storeName: String)
    func clear(store storeName: String)
}

/// Shared host/extension entry point. Reconciliation never clears a whole cause's store:
/// shields and uninstall protection belong to their own lifecycle.
public final class FlintWebRestrictionLifecycle {
    public static let sessionStoreName = "flint"
    public static let legacyStoreName = "flint.web"

    private let writer: any FlintWebRestrictionWriting
    private let recoveryGate: () -> FlintPassRecoveryGate
    private let clock: FlintClock?

    public init(writer: any FlintWebRestrictionWriting,
                recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing },
                clock: FlintClock? = nil) {
        self.writer = writer
        self.recoveryGate = recoveryGate
        self.clock = clock
    }

    public func reconcileSession(
        _ session: FlintActiveSession?, config: FlintWebRestrictions?, now: Date = Date(),
        clock: FlintClock? = nil
    ) {
        guard let config else {
            guard recoveryGate().permitsApplication,
                  session.map({ !$0.isEnforcing(at: now, clock: clock ?? self.clock) }) ?? true else { return }
            writer.clear(store: Self.sessionStoreName)
            return
        }
        update(store: Self.sessionStoreName, shouldApply: FlintWebRestrictionPolicy.shouldApply(
            to: .session(session), config: config, now: now, clock: clock ?? self.clock
        ), config: config)
    }

    #if canImport(FamilyControls)
    /// Unavailable preferences cannot extend a positively ended owner. These must be the
    /// actual saved owners, before registration eligibility masks them as nil/disabled.
    func clearEndedOwners(activeSession: FlintActiveSession?, rules: [FlintScheduleRule],
                          now: Date, clock: FlintClock? = nil) {
        guard recoveryGate().permitsApplication else { return }
        let resolved = clock ?? self.clock
        reconcileSession(activeSession, config: nil, now: now, clock: resolved)
        for rule in rules where !rule.enabled || rule.occurrence(at: now, clock: resolved) == nil {
            writer.clear(store: rule.storeName)
        }
        // The legacy shared store can only be released when every known owner has ended.
        if !(activeSession?.isEnforcing(at: now, clock: resolved) ?? false),
           !rules.contains(where: { $0.enabled && $0.occurrence(at: now, clock: resolved) != nil }) {
            writer.clear(store: Self.legacyStoreName)
        }
    }

    public func reconcileRule(
        _ rule: FlintScheduleRule, config: FlintWebRestrictions,
        now: Date = Date(), calendar: Calendar = .current, clock: FlintClock? = nil
    ) {
        update(store: rule.storeName, shouldApply: FlintWebRestrictionPolicy.shouldApply(
            to: rule, config: config, now: now, calendar: calendar, clock: clock ?? self.clock
        ), config: config)
    }

    /// Call after saving preferences and on launch/foreground with current, armed causes.
    /// The host passes unobserved schedules as disabled copies and unarmed timed sessions as
    /// nil. Failed repairs are instead listed in preservingStores: their last-good keys end
    /// with the surviving registration or bounded cause-store cleanup. This function
    /// decides scope and eligibility through the trusted occurrence clock; it cannot observe
    /// DeviceActivity registrations.
    /// Deleted rules are cleared by their controller before removal from persisted state.
    public func reconcile(
        activeSession: FlintActiveSession?, rules: [FlintScheduleRule], config: FlintWebRestrictions,
        now: Date = Date(), calendar: Calendar = .current, preservingStores: Set<String> = [],
        clock: FlintClock? = nil
    ) {
        guard config.validationMessage == nil, recoveryGate().permitsApplication else { return }
        if !preservingStores.contains(Self.sessionStoreName) {
            reconcileSession(activeSession, config: config, now: now, clock: clock)
        }
        for rule in rules where !preservingStores.contains(rule.storeName) {
            reconcileRule(rule, config: config, now: now, calendar: calendar, clock: clock)
        }
        writer.clear(store: Self.legacyStoreName)
    }
    #endif

    private func update(store: String, shouldApply: Bool, config: FlintWebRestrictions) {
        // The committed pass outbox owns global release. Callback suppression itself never
        // grants cleanup authority, especially when the ledger is unreadable.
        guard config.validationMessage == nil, recoveryGate().permitsApplication else { return }
        if shouldApply {
            writer.apply(config, toStore: store)
        } else {
            writer.clear(store: store)
        }
    }
}

#if canImport(FamilyControls)
/// One session-environment seam; ending a session still clears its entire owning shield store.
public protocol FlintSessionWebRestricting {
    /// Apply or release the session's Safari keys by its logical time on `clock`.
    func reconcile(_ session: FlintActiveSession, clock: FlintClock)
}

/// Uses the same policy/writer as host reconciliation. Config is loaded at the point of use,
/// so edits and disabling a scope update running owners rather than leaving stale web keys.
public struct FlintSessionWebRestrictionAdapter: FlintSessionWebRestricting {
    private let lifecycle: FlintWebRestrictionLifecycle
    private let configuration: () -> FlintWebRestrictions?

    public init(writer: any FlintWebRestrictionWriting,
                configuration: @escaping () -> FlintWebRestrictions?,
                recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing },
                clock: FlintClock? = nil) {
        self.lifecycle = FlintWebRestrictionLifecycle(writer: writer, recoveryGate: recoveryGate, clock: clock)
        self.configuration = configuration
    }

    public func reconcile(_ session: FlintActiveSession, clock: FlintClock) {
        lifecycle.reconcileSession(session, config: configuration(), now: clock.now(), clock: clock)
    }
}
#endif

#if canImport(ManagedSettings)
public struct FlintManagedWebRestrictionWriter: FlintWebRestrictionWriting {
    public init() {}

    public func apply(_ config: FlintWebRestrictions, toStore storeName: String) {
        FlintWebRestrictionStore(named: storeName).apply(config)
    }

    public func clear(store storeName: String) {
        FlintWebRestrictionStore(named: storeName).clear()
    }
}

extension FlintWebRestrictionLifecycle {
    public convenience init() {
        self.init(writer: FlintManagedWebRestrictionWriter(), recoveryGate: { .read() }, clock: .system)
    }
}
#endif
