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
        let loaded = ledger().flatMap { try? $0.peek().get() }
        let state = loaded.flatMap { $0.hasValidPassRecovery ? $0 : nil }
        let instant = FlintLedgerInstant(wall: now, monotonic: environment.clock.monotonic(),
            bootID: environment.clock.bootSessionID())
        let recovering = state?.pass.status(at: instant, hasActiveCommitment: true).isRecovering ?? false
        let recoveryApplying = state?.emergencyRecovery?.needsApplication(isRecovering: recovering) ?? false
        let active = environment.loadActiveSession()
        // An open-ended Easy session has no out-of-app end to register; it counts as armed only
        // while its own frozen target snapshot exists (what the shield is applied from).
        var armed: Bool?
        if let active, active.endsAt == nil {
            armed = sessionTargets(active.id)
        }
        // Forget threshold marks of limits that were disabled, deleted or re-armed with another
        // threshold since they fired: their shield was cleared or replaced (ADR-012 §8).
        if let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults, at: now, migrating: false) {
            FlintLimitThresholds.prune(keeping: limits, in: defaults)
        }
        var observation = environment
        observation.loadLedgerState = { loaded }
        let inputs = currentInputs(now: now, defaults: defaults, ledgerPass: state?.pass,
                                   thresholdReached: FlintLimitThresholds.reachedToday(now: now, in: defaults),
                                   sessionArmed: armed,
                                   sessionKind: active?.name == pomodoroSessionName ? .pomodoro : .session,
                                   environment: observation, recoveryApplying: recoveryApplying)
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
    static let thresholdKey = "flint.limits.thresholdReached.minutes"
    static let observationKey = key + ".adr012"

    private struct Mark: Codable {
        let schema: Int
        let activity: String
        let fingerprint: String
        let window: FlintRuleWindow
        let reachedAt: Date
        var isValid: Bool {
            schema == 1 && !activity.isEmpty && fingerprint.count == 64
                && window.isValid && window.contains(reachedAt)
        }
    }

    public static func markReached(_ limit: FlintLimitRule, at date: Date = Date(), in defaults: UserDefaults? = .flintGroup) {
        guard let defaults, limit.validationIssue == nil, let window = limit.occurrence(at: date),
              let fingerprint = fingerprint(limit), var all = load(from: defaults) else { return }
        all[limit.id] = .init(schema: 1, activity: limit.activityName, fingerprint: fingerprint,
            window: window, reachedAt: date)
        save(all, to: defaults)
    }

    /// A rename or future-only queue keeps its observation. A changed native incarnation /
    /// revision, effective selection, quota or saved zone never inherits the old mark.
    static func prune(keeping limits: [FlintLimitRule], in defaults: UserDefaults?) {
        guard let defaults, Set(limits.map(\.id)).count == limits.count,
              let all = load(from: defaults) else { return }
        let kept = all.filter { entry in
            limits.contains { $0.id == entry.key && matches(entry.value, rule: $0) }
        }
        if kept.count != all.count { save(kept, to: defaults) }
    }

    public static func clear(_ limitID: String, in defaults: UserDefaults? = .flintGroup) {
        guard let defaults, var all = load(from: defaults) else { return }
        all[limitID] = nil
        save(all, to: defaults)
        // Old unbound marks are never accepted as observations, but clear this rule's legacy
        // cache too so an older reader cannot project it after a daily reset.
        if var legacy = defaults.dictionary(forKey: key) { legacy[limitID] = nil; defaults.set(legacy, forKey: key) }
        if var legacy = defaults.dictionary(forKey: thresholdKey) { legacy[limitID] = nil; defaults.set(legacy, forKey: thresholdKey) }
    }

    /// Calendar is retained for source compatibility; each rule's saved zone is authoritative.
    /// Missing/legacy-unbound/corrupt observations remain unknown, never an inferred false.
    public static func reachedToday(now: Date, calendar _: Calendar = .current,
                                    in defaults: UserDefaults? = .flintGroup) -> [String: Bool] {
        guard let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults, at: now, migrating: false) else { return [:] }
        return limits.reduce(into: [:]) { result, limit in
            if hasReached(limit, at: now, in: defaults) { result[limit.id] = true }
        }
    }

    static func hasReached(_ limit: FlintLimitRule, at now: Date, in defaults: UserDefaults?) -> Bool {
        guard let defaults, let mark = load(from: defaults)?[limit.id], matches(mark, rule: limit),
              mark.reachedAt <= now, let current = limit.occurrence(at: now), current == mark.window else { return false }
        return true
    }

    private static func matches(_ mark: Mark, rule: FlintLimitRule) -> Bool {
        rule.enabled && rule.validationIssue == nil && mark.activity == rule.activityName
            && mark.fingerprint == fingerprint(rule)
    }

    private static func fingerprint(_ rule: FlintLimitRule) -> String? {
        var effective = rule
        effective.name = ""; effective.enabled = true; effective.breakLevel = .easy
        return try? effective.fingerprint(action: "time.threshold")
    }

    private static func load(from defaults: UserDefaults) -> [String: Mark]? {
        guard let raw = defaults.object(forKey: observationKey) else { return [:] }
        guard let data = raw as? Data, let decoded = try? JSONDecoder().decode([String: Mark].self, from: data),
              decoded.allSatisfy({ !$0.key.isEmpty && $0.value.isValid }) else { return nil }
        return decoded
    }

    private static func save(_ marks: [String: Mark], to defaults: UserDefaults) {
        if let data = try? JSONEncoder().encode(marks) { defaults.set(data, forKey: observationKey) }
    }
}
