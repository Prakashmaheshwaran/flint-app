import Foundation

extension FlintProtectionStatus.Writer {
    /// The process that is publishing: the host app, or one of the two extensions that commit
    /// enforcement changes. Read-only extensions (widgets, shield configuration, report) never
    /// publish; if one ever does, it's labelled by bundle rather than guessed.
    public static var current: Self { writer(forBundleID: Bundle.main.bundleIdentifier,
                                             isExtension: Bundle.main.bundleURL.pathExtension == "appex") }

    static func writer(forBundleID id: String?, isExtension: Bool) -> Self {
        guard isExtension, let id else { return .host }
        if id.hasSuffix(".shield-action") { return .shieldAction }
        if id.hasSuffix(".monitor") { return .monitor }
        return .host
    }
}

extension FlintProtectionStatus {
    /// Re-publish the shared projection after a committed enforcement change — the one call
    /// every commit point makes. Cheap and non-throwing; reads the ledger with `peek()` (no
    /// lock), so it is safe right after a ledger transaction and inside extensions. When the
    /// projection's content is unchanged it only refreshes the stored blob and skips the widget
    /// reload and change notification (WidgetKit budgets extension reloads).
    @discardableResult
    public static func publishAfterCommit(
        writer: Writer = .current,
        now: Date = Date(),
        defaults: UserDefaults? = .flintGroup,
        ledger: () -> FlintProtectionLedger? = { FlintProtectionLedger.appGroup() },
        environment: ObservationEnvironment = .system,
        sessionTargets: (String) -> Bool = { FlintGroupStore()?.loadSessionSelection(sessionID: $0) != nil },
        effects: PublicationEffects = .system
    ) -> FlintProtectionStatusSnapshot {
        let pass = ledger().flatMap { try? $0.peek().get() }?.pass
        let active = environment.loadActiveSession()
        // An open-ended Easy session has no out-of-app end to register; it counts as armed only
        // while its own frozen target snapshot exists (what the shield is applied from).
        var armed: Bool?
        if let active, active.endsAt == nil {
            armed = sessionTargets(active.id)
        }
        // Forget threshold marks of limits that were disabled, deleted or re-armed with another
        // threshold since they fired: their shield was cleared or replaced (ADR-012 §8).
        if let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults) {
            FlintLimitThresholds.prune(keeping: limits, in: defaults)
        }
        let inputs = currentInputs(now: now, defaults: defaults, ledgerPass: pass,
                                   thresholdReached: FlintLimitThresholds.reachedToday(now: now, in: defaults),
                                   sessionArmed: armed,
                                   sessionKind: active?.name == pomodoroSessionName ? .pomodoro : .session,
                                   environment: environment)
        let snapshot = FlintProtectionStatusBuilder.make(inputs).withWriter(writer.rawValue)
        // Compare with whatever is stored, valid or not: an unchanged unavailable projection
        // must not reload widgets either.
        let changed = storedSnapshot(in: defaults).map { !$0.hasSameContent(as: snapshot) } ?? true
        publish(snapshot, to: defaults, writer: writer, effects: changed ? effects : .silent)
        return snapshot
    }

    static func storedSnapshot(in defaults: UserDefaults?) -> FlintProtectionStatusSnapshot? {
        guard let data = defaults?.data(forKey: storageKey) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return try? decoder.decode(FlintProtectionStatusSnapshot.self, from: data)
    }

    /// The name the Pomodoro paths give their sessions (the session record has no kind field).
    static let pomodoroSessionName = "Pomodoro"
}

extension FlintProtectionStatus.PublicationEffects {
    /// Write without reloading widgets or notifying observers.
    public static var silent: Self { Self(reloadTimelines: { _ in }, postDarwinNotification: { _ in }) }
}

extension FlintProtectionStatusSnapshot {
    /// Equal apart from when and by whom it was observed — and from a validity extension of
    /// under an hour. With no nearer boundary, validUntil is the observedAt + 24 h cap and moves
    /// with every publication; readers only need a nudge when it moves earlier or far enough to
    /// matter. An earlier validUntil always counts as a change.
    func hasSameContent(as other: FlintProtectionStatusSnapshot) -> Bool {
        guard version == other.version && state == other.state
            && session == other.session && headline == other.headline && causes == other.causes
            && nextOccurrence == other.nextOccurrence && pass == other.pass
            && degradedReasons == other.degradedReasons else { return false }
        switch (validUntil, other.validUntil) {
        case (nil, nil): return true
        case let (stored?, new?): return new >= stored && new.timeIntervalSince(stored) < Self.validityNudge
        default: return false
        }
    }

    static let validityNudge: TimeInterval = 3600
}

/// Time Limits whose daily threshold the monitor saw today (ADR-012 §8: a limit's enforcement
/// is claimed only when known). Written by the DeviceActivityMonitor's threshold callback,
/// cleared by that limit's new-day interval start; absent = unknown, never "not reached".
public enum FlintLimitThresholds {
    static let key = "flint.limits.thresholdReached"

    public static func markReached(_ limit: FlintLimitRule, at date: Date = Date(), in defaults: UserDefaults? = .flintGroup) {
        guard let defaults else { return }
        var all = load(from: defaults)
        all[limit.id] = date.timeIntervalSince1970
        defaults.set(all, forKey: key)
        var thresholds = (defaults.dictionary(forKey: thresholdKey) as? [String: Int]) ?? [:]
        thresholds[limit.id] = limit.thresholdMinutes
        defaults.set(thresholds, forKey: thresholdKey)
    }

    /// Keep marks only for limits still enabled with the threshold that fired.
    static func prune(keeping limits: [FlintLimitRule], in defaults: UserDefaults?) {
        guard let defaults else { return }
        let thresholds = (defaults.dictionary(forKey: thresholdKey) as? [String: Int]) ?? [:]
        let valid = Set(limits.filter { $0.enabled && thresholds[$0.id] == $0.thresholdMinutes }.map(\.id))
        let all = load(from: defaults)
        let kept = all.filter { valid.contains($0.key) }
        if kept.count != all.count { defaults.set(kept, forKey: key) }
    }

    static let thresholdKey = "flint.limits.thresholdReached.minutes"

    public static func clear(_ limitID: String, in defaults: UserDefaults? = .flintGroup) {
        guard let defaults else { return }
        var all = load(from: defaults)
        all[limitID] = nil
        defaults.set(all, forKey: key)
    }

    /// Limits reached on the current calendar day.
    public static func reachedToday(now: Date, calendar: Calendar = .current,
                                    in defaults: UserDefaults? = .flintGroup) -> [String: Bool] {
        guard let defaults else { return [:] }
        return load(from: defaults).reduce(into: [:]) { result, entry in
            if calendar.isDate(Date(timeIntervalSince1970: entry.value), inSameDayAs: now) { result[entry.key] = true }
        }
    }

    private static func load(from defaults: UserDefaults) -> [String: TimeInterval] {
        (defaults.dictionary(forKey: key) as? [String: TimeInterval]) ?? [:]
    }
}
