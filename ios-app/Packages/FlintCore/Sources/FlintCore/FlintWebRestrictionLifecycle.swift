import Foundation

/// Pure scope decisions. Time and Open Limits never acquire web-content restrictions.
public enum FlintWebRestrictionPolicy {
    public enum Cause {
        case session(FlintActiveSession?)
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
        case .timeLimit, .openLimit:
            return false
        }
    }

    #if canImport(FamilyControls)
    public static func shouldApply(to rule: FlintScheduleRule, config: FlintWebRestrictions,
                                  now: Date, calendar: Calendar = .current, clock: FlintClock? = nil) -> Bool {
        guard config.applyDuringSchedules, rule.enabled else { return false }
        guard FlintWebRestrictionChildren.knows(rule) else { return false }
        return rule.occurrence(at: now, clock: clock) != nil
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
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
public final class FlintWebRestrictionLifecycle {
    public static let sessionStoreName = "flint"
    public static let legacyStoreName = "flint.web"

    private let writer: any FlintWebRestrictionWriting
    private let recoveryGate: () -> FlintPassRecoveryGate
    private let clock: FlintClock?
    private let children: FlintWebRestrictionChildren
    private(set) var appliedStores: Set<String> = []
    private(set) var unavailableStores: Set<String> = []

    func resetObservations() { appliedStores.removeAll(); unavailableStores.removeAll() }

    public convenience init(writer: any FlintWebRestrictionWriting,
                recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing },
                clock: FlintClock? = nil) {
        self.init(writer: writer, recoveryGate: recoveryGate, clock: clock, children: .isolated())
    }

    init(writer: any FlintWebRestrictionWriting, recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing },
         clock: FlintClock? = nil, children: FlintWebRestrictionChildren) {
        self.writer = writer
        self.recoveryGate = recoveryGate
        self.clock = clock
        self.children = children
    }

    /// Called before a replacement can arm or commit. Carry the old live child's strength
    /// into the new occurrence; only a provably stronger future preference may replace it.
    func prepareReplacement(_ replacement: FlintActiveSession, replacing previous: FlintActiveSession?,
                            config: FlintWebRestrictions?, clock: FlintClock) -> Bool {
        guard recoveryGate().permitsApplication else { return true }
        guard let previous, previous.isEnforcing(at: clock.now(), clock: clock) else {
            _ = children.retire(scope: .session)
            return true
        }
        let old = FlintWebRestrictionChildren.Cause.session(previous)
        let global = config.flatMap { $0.validationMessage == nil ? $0 : nil }
        guard let frozen = children.resolve(old, global: global) else {
            _ = children.capture([.session(replacement)], global: nil)
            unavailableStores.insert(Self.sessionStoreName)
            return true
        }
        let policy = global.flatMap { FlintWebRestrictionChildren.strengthens($0, over: frozen, cause: old) ? $0 : nil } ?? frozen
        guard let prepared = children.resolve(.session(replacement), global: policy) else {
            unavailableStores.insert(Self.sessionStoreName)
            return true
        }
        return FlintWebRestrictionChildren.strengthens(prepared, over: frozen, cause: old)
    }

    public func reconcileSession(
        _ session: FlintActiveSession?, config: FlintWebRestrictions?, now: Date = Date(),
        clock: FlintClock? = nil
    ) {
        guard recoveryGate().permitsApplication else { return }
        let resolved = resolvedClock(now: now, clock: clock)
        guard let session, session.isEnforcing(at: now, clock: resolved) else {
            writer.clear(store: Self.sessionStoreName)
            _ = children.retire(scope: .session)
            return
        }
        let cause = FlintWebRestrictionChildren.Cause.session(session)
        guard let frozen = children.resolve(cause, global: config) else {
            unavailableStores.insert(Self.sessionStoreName); return
        }
        _ = children.retire(scope: .session, keeping: cause)
        update(store: Self.sessionStoreName, shouldApply: FlintWebRestrictionPolicy.shouldApply(
            to: .session(session), config: frozen, now: now, clock: resolved
        ), config: frozen)
    }

    #if canImport(FamilyControls)
    public func reconcileRule(
        _ rule: FlintScheduleRule, config: FlintWebRestrictions?,
        now: Date = Date(), calendar: Calendar = .current, clock: FlintClock? = nil
    ) {
        guard recoveryGate().permitsApplication else { return }
        let resolved = resolvedClock(now: now, clock: clock)
        // Unmigrated/future metadata cannot prove either an occurrence or a natural end.
        guard FlintWebRestrictionChildren.knows(rule) else { unavailableStores.insert(rule.storeName); return }
        if !rule.enabled {
            writer.clear(store: rule.storeName)
            _ = children.retire(scope: .rule, ownerID: rule.id)
            return
        }
        guard let window = rule.occurrence(at: now, clock: resolved) else {
            writer.clear(store: rule.storeName)
            _ = children.retire(scope: .rule, ownerID: rule.id)
            return
        }
        let cause = FlintWebRestrictionChildren.Cause.rule(rule, window: window)
        guard let frozen = children.resolve(cause, global: config, captureNew: children.read() == .legacy) else {
            unavailableStores.insert(rule.storeName); return
        }
        _ = children.retire(scope: .rule, ownerID: rule.id, keeping: cause)
        update(store: rule.storeName, shouldApply: FlintWebRestrictionPolicy.shouldApply(
            to: rule, config: frozen, now: now, clock: resolved
        ), config: frozen)
    }

    /// Call after saving preferences and on launch/foreground with current, armed causes.
    /// The host passes unobserved schedules as disabled copies and unarmed timed sessions as
    /// nil. Failed repairs are instead listed in preservingStores: their last-good keys end
    /// with the surviving registration or bounded cause-store cleanup. This function
    /// decides scope and eligibility through the trusted occurrence clock; it cannot observe
    /// DeviceActivity registrations.
    /// Deleted rules are cleared by their controller before removal from persisted state.
    public func reconcile(
        activeSession: FlintActiveSession?, rules: [FlintScheduleRule], config: FlintWebRestrictions?,
        now: Date = Date(), calendar: Calendar = .current, preservingStores: Set<String> = [],
        clock: FlintClock? = nil, actualOwners: FlintWebRestrictionEditor.Snapshot? = nil
    ) {
        resetObservations()
        guard recoveryGate().permitsApplication else { return }
        let resolved = resolvedClock(now: now, clock: clock)
        let owners = actualOwners ?? .init(session: activeSession, rules: rules)
        children.observe(owners, global: config, clock: resolved)
        if !(owners.session?.isEnforcing(at: now, clock: resolved) ?? false) {
            reconcileSession(owners.session, config: config, now: now, clock: resolved)
        } else if !preservingStores.contains(Self.sessionStoreName) {
            reconcileSession(activeSession, config: config, now: now, clock: resolved)
        }
        for owner in owners.rules {
            let ended = FlintWebRestrictionChildren.knows(owner)
                && (!owner.enabled || owner.occurrence(at: now, clock: resolved) == nil)
            if ended {
                reconcileRule(owner, config: config, now: now, clock: resolved)
            } else if !preservingStores.contains(owner.storeName), let eligible = rules.first(where: { $0.id == owner.id }) {
                reconcileRule(eligible, config: config, now: now, clock: resolved)
            }
        }
        let liveSession = owners.session?.isEnforcing(at: now, clock: resolved) ?? false
        let liveRules = owners.rules.filter { !FlintWebRestrictionChildren.knows($0)
            || ($0.enabled && $0.occurrence(at: now, clock: resolved) != nil) }
        if !liveSession && liveRules.isEmpty {
            writer.clear(store: Self.legacyStoreName)
        }
    }
    #endif

    private func update(store: String, shouldApply: Bool, config: FlintWebRestrictions) {
        // The committed pass outbox owns global release. Callback suppression itself never
        // grants cleanup authority, especially when the ledger is unreadable.
        guard config.validationMessage == nil, recoveryGate().permitsApplication else { return }
        if shouldApply {
            writer.apply(config, toStore: store)
            appliedStores.insert(store)
        } else {
            writer.clear(store: store)
        }
    }

    private func resolvedClock(now: Date, clock: FlintClock?) -> FlintClock {
        var resolved = clock ?? self.clock ?? .system
        resolved.now = { now }
        return resolved
    }
}

#if canImport(FamilyControls)
/// One session-environment seam; ending a session still clears its entire owning shield store.
public protocol FlintSessionWebRestricting {
    /// Apply or release the session's Safari keys by its logical time on `clock`.
    func reconcile(_ session: FlintActiveSession, clock: FlintClock)
    func prepareReplacement(_ replacement: FlintActiveSession, replacing previous: FlintActiveSession?, clock: FlintClock) -> Bool
}

public extension FlintSessionWebRestricting {
    /// Non-web adapters have no child restriction to carry. The live web adapter overrides it.
    func prepareReplacement(_ replacement: FlintActiveSession, replacing previous: FlintActiveSession?, clock: FlintClock) -> Bool { true }
}

/// First reconcile freezes the session's child under the same transition as its owner.
/// Later raw global preference changes cannot weaken that occurrence's captured policy.
public struct FlintSessionWebRestrictionAdapter: FlintSessionWebRestricting {
    private let lifecycle: FlintWebRestrictionLifecycle
    private let read: () -> FlintWebRestrictionRead
    private let transition: (() -> Void) -> Bool
    private let recordHealth: (FlintArmingHealth.DomainReport) -> Void

    public init(writer: any FlintWebRestrictionWriting,
                configuration: @escaping () -> FlintWebRestrictions?,
                recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing },
                clock: FlintClock? = nil) {
        self.init(writer: writer, read: { configuration().map(FlintWebRestrictionRead.available) ?? .unavailable },
                  recoveryGate: recoveryGate, clock: clock)
    }

    public init(writer: any FlintWebRestrictionWriting, read: @escaping () -> FlintWebRestrictionRead,
                recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing }, clock: FlintClock? = nil) {
        self.init(writer: writer, read: read, recoveryGate: recoveryGate, clock: clock,
            children: .live, transition: { body in
                FlintSessionController().withTransition { body(); return true } ?? false
            }, recordHealth: Self.recordLiveHealth)
    }

    init(writer: any FlintWebRestrictionWriting, configuration: @escaping () -> FlintWebRestrictions?,
         recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing }, clock: FlintClock? = nil,
         children: FlintWebRestrictionChildren, transition: @escaping (() -> Void) -> Bool = { body in body(); return true }) {
        self.init(writer: writer, read: { configuration().map(FlintWebRestrictionRead.available) ?? .unavailable },
            recoveryGate: recoveryGate, clock: clock, children: children, transition: transition)
    }

    init(writer: any FlintWebRestrictionWriting, read: @escaping () -> FlintWebRestrictionRead,
         recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing }, clock: FlintClock? = nil,
         children: FlintWebRestrictionChildren, transition: @escaping (() -> Void) -> Bool = { body in body(); return true },
         recordHealth: @escaping (FlintArmingHealth.DomainReport) -> Void = { _ in }) {
        self.lifecycle = FlintWebRestrictionLifecycle(writer: writer, recoveryGate: recoveryGate, clock: clock, children: children)
        self.read = read
        self.transition = transition
        self.recordHealth = recordHealth
    }

    public func reconcile(_ session: FlintActiveSession, clock: FlintClock) {
        _ = transition {
            lifecycle.resetObservations()
            let state = read()
            if state == .busy { recordHealth(FlintWebRestrictionHostReconciler.busyReport(now: clock.now())); return }
            let config: FlintWebRestrictions?
            if case let .available(value) = state { config = value } else { config = nil }
            lifecycle.reconcileSession(session, config: config, now: clock.now(), clock: clock)
            var failures: [FlintArmingHealth.Failure] = []
            if config == nil || config?.validationMessage != nil {
                failures.append(.init(activityName: "webRestrictions", reason: "Saved website preferences are unavailable. Existing frozen policy was retained; future updates remain deferred."))
            }
            if !lifecycle.unavailableStores.isEmpty {
                failures.append(.init(activityName: session.monitorName, reason: "This session's frozen website policy is unavailable. Existing Safari keys were preserved."))
            }
            let applied = lifecycle.appliedStores.count
            recordHealth(.init(enabled: applied, attempted: applied, armed: applied, failures: failures, updatedAt: clock.now()))
        }
    }

    public func prepareReplacement(_ replacement: FlintActiveSession, replacing previous: FlintActiveSession?, clock: FlintClock) -> Bool {
        var prepared = false
        guard transition({
            lifecycle.resetObservations()
            guard previous?.isEnforcing(at: clock.now(), clock: clock) == true else {
                prepared = lifecycle.prepareReplacement(replacement, replacing: previous, config: nil, clock: clock)
                return
            }
            let state = read()
            let config: FlintWebRestrictions?
            if case let .available(value) = state { config = value } else { config = nil }
            prepared = lifecycle.prepareReplacement(replacement, replacing: previous, config: config, clock: clock)
            if state == .busy { recordHealth(FlintWebRestrictionHostReconciler.busyReport(now: clock.now())) }
            else if !prepared || !lifecycle.unavailableStores.isEmpty || state == .unavailable {
                recordHealth(.init(enabled: 0, attempted: 0, armed: 0, failures: [.init(activityName: "webRestrictions",
                    reason: "The replacement's website policy is unavailable or deferred. Existing Safari keys were preserved; app protection can proceed.")], updatedAt: clock.now()))
            }
        }) else { return true }
        return prepared
    }

    private static func recordLiveHealth(_ report: FlintArmingHealth.DomainReport) {
        guard let group = FlintGroupStore() else { return }
        group.saveArmingHealth(group.loadArmingHealth().replacing("webRestrictions", with: report))
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
        self.init(writer: FlintManagedWebRestrictionWriter(), recoveryGate: { .read() }, clock: .system, children: .live)
    }
}
#endif
#endif
