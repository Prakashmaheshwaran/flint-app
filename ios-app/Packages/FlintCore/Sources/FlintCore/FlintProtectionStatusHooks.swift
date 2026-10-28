import Foundation

extension FlintProtectionStatus.Writer {
    /// The process that is publishing: the host app, or one of Flint's app extensions.
    public static var current: Self {
        guard Bundle.main.bundleURL.pathExtension == "appex" else { return .host }
        let id = Bundle.main.bundleIdentifier ?? ""
        return id.localizedCaseInsensitiveContains("ShieldAction") ? .shieldAction : .monitor
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
        effects: PublicationEffects = .system
    ) -> FlintProtectionStatusSnapshot {
        let pass = ledger().flatMap { try? $0.peek().get() }?.pass
        let active = environment.loadActiveSession()
        // An open-ended Easy session has no out-of-app end to register; its shield is its
        // whole enforcement, so it counts as armed while it runs.
        let armed: Bool? = active.map { $0.endsAt == nil } == true ? true : nil
        let inputs = currentInputs(now: now, defaults: defaults, ledgerPass: pass, sessionArmed: armed,
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
