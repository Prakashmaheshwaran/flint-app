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
        calendar: Calendar = .current
    ) -> Bool {
        switch cause {
        case let .session(session):
            guard config.enabled, let session else { return false }
            return session.endsAt.map { now < $0 } ?? true
        case let .schedule(schedule, enabled):
            return config.applyDuringSchedules && enabled && schedule.isWindowActive(at: now, calendar: calendar)
        case .timeLimit, .openLimit:
            return false
        }
    }
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

    public init(writer: any FlintWebRestrictionWriting) {
        self.writer = writer
    }

    public func reconcileSession(
        _ session: FlintActiveSession?, config: FlintWebRestrictions, now: Date = Date()
    ) {
        update(store: Self.sessionStoreName, shouldApply: FlintWebRestrictionPolicy.shouldApply(
            to: .session(session), config: config, now: now
        ), config: config)
    }

    #if canImport(FamilyControls)
    public func reconcileRule(
        _ rule: FlintScheduleRule, config: FlintWebRestrictions,
        now: Date = Date(), calendar: Calendar = .current
    ) {
        update(store: rule.storeName, shouldApply: FlintWebRestrictionPolicy.shouldApply(
            to: .schedule(rule.schedule, enabled: rule.enabled), config: config, now: now, calendar: calendar
        ), config: config)
    }

    /// Call after saving preferences and on launch/foreground with current persisted causes.
    /// Deleted rules are cleared by their controller before removal from persisted state.
    public func reconcile(
        activeSession: FlintActiveSession?, rules: [FlintScheduleRule], config: FlintWebRestrictions,
        now: Date = Date(), calendar: Calendar = .current
    ) {
        reconcileSession(activeSession, config: config, now: now)
        for rule in rules {
            reconcileRule(rule, config: config, now: now, calendar: calendar)
        }
        writer.clear(store: Self.legacyStoreName)
    }
    #endif

    private func update(store: String, shouldApply: Bool, config: FlintWebRestrictions) {
        if shouldApply {
            writer.apply(config, toStore: store)
        } else {
            writer.clear(store: store)
        }
    }
}

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
        self.init(writer: FlintManagedWebRestrictionWriter())
    }
}
#endif
