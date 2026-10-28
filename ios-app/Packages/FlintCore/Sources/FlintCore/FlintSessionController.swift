import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

public enum FlintSessionError: Error, LocalizedError, Equatable {
    case appGroupUnavailable
    case notAuthorized
    case emptySelection
    case tooManyTokens(count: Int, cap: Int)
    case durationTooShort(minimumMinutes: Int)
    case durationTooLong(maximumMinutes: Int)
    /// iOS refused the auto-clear registration, so the session could not end on its own.
    case armingFailed(reason: String)

    public var errorDescription: String? {
        switch self {
        case .appGroupUnavailable: return "Flint's shared storage is unavailable (App Group misconfigured)."
        case .notAuthorized: return "Allow Screen Time access for Flint first — without it iOS won't block anything."
        case .emptySelection: return "Pick at least one app or website to block first."
        case let .tooManyTokens(count, cap): return "Too many items selected (\(count)). The system limit is \(cap) — use a category instead."
        case let .durationTooShort(minimum): return "Sessions need to be at least \(minimum) \(minimum == 1 ? "minute" : "minutes")."
        case let .durationTooLong(maximum): return "Sessions can run for up to \(maximum / 60) hours \(maximum % 60) minutes."
        case .armingFailed:
            return "iOS wouldn't schedule this session's end, so it wasn't started. Try again, or remove "
                + "a schedule or limit to free a slot."
        }
    }
}

// MARK: - Seams

/// Token counts the start path validates before any OS write. Opaque tokens themselves only
/// ever reach the shield seam.
public struct FlintSelectionCounts: Equatable, Sendable {
    public var applications: Int
    public var categories: Int
    public var webDomains: Int

    public init(applications: Int = 0, categories: Int = 0, webDomains: Int = 0) {
        self.applications = applications
        self.categories = categories
        self.webDomains = webDomains
    }

    public var total: Int { applications + categories + webDomains }
}

/// The session's `ManagedSettingsStore` ("flint"): shield, uninstall guard, teardown.
public protocol FlintSessionShielding: AnyObject {
    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts
    func applyBlockList(_ selection: FamilyActivitySelection) throws
    func setDenyAppRemoval(_ deny: Bool)
    /// Drops everything on the store — shield, uninstall guard and any other session setting.
    func clear()
}

/// `DeviceActivityCenter` for the session's one-shot auto-clear.
public protocol FlintSessionActivityRegistering: AnyObject {
    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws
    func stopMonitoring(_ names: [String])
    /// Names iOS currently holds — a real observation, not Flint's own bookkeeping.
    func registeredActivityNames() -> Set<String>
}

/// The App Group state a session reads and writes.
public protocol FlintSessionStateStoring: AnyObject {
    func loadActiveSession() -> FlintActiveSession?
    func saveActiveSession(_ session: FlintActiveSession)
    func clearActiveSession()
    /// The picker's last selection (Today's default for the next start).
    func saveSelection(_ selection: FamilyActivitySelection)
    func loadSelection() -> FamilyActivitySelection?
    /// The running session's own frozen targets, keyed by session id, so editing the picker or a
    /// saved group never changes what an already-running session blocks.
    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String)
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection?
    func clearSessionSelection()
    func emergencyPassLastUsed() -> Date?
    func recordEmergencyPassUse(_ date: Date)
}

extension FlintGroupStore: FlintSessionStateStoring {
    private struct SessionSelection: Codable {
        let sessionID: String
        let selection: FamilyActivitySelection
    }

    /// Written to the App Group suite itself (FlintGroupStore's own suite is private to its
    /// file); production stores always use that suite.
    private static let sessionSelectionKey = "flint.activeSession.selection"

    public func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) {
        UserDefaults.flintGroup?.set(
            try? JSONEncoder().encode(SessionSelection(sessionID: sessionID, selection: selection)),
            forKey: Self.sessionSelectionKey
        )
    }

    public func clearSessionSelection() {
        UserDefaults.flintGroup?.removeObject(forKey: Self.sessionSelectionKey)
    }

    public func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? {
        guard let data = UserDefaults.flintGroup?.data(forKey: Self.sessionSelectionKey),
              let saved = try? JSONDecoder().decode(SessionSelection.self, from: data),
              saved.sessionID == sessionID else { return nil }
        return saved.selection
    }
}

/// Everything `FlintSessionController` touches outside its own logic.
public struct FlintSessionEnvironment {
    /// nil means the App Group is unavailable.
    public var store: () -> FlintSessionStateStoring?
    public var shield: FlintSessionShielding
    public var registrar: FlintSessionActivityRegistering
    public var isAuthorized: () -> Bool
    public var clock: FlintClock
    public var shortSessionStrategy: FlintSessionAutoClearPlan.ShortSessionStrategy
    /// Where `FlintSessionHealth` persists.
    public var healthDefaults: () -> UserDefaults?

    public init(
        store: @escaping () -> FlintSessionStateStoring?,
        shield: FlintSessionShielding,
        registrar: FlintSessionActivityRegistering,
        isAuthorized: @escaping () -> Bool,
        clock: FlintClock,
        shortSessionStrategy: FlintSessionAutoClearPlan.ShortSessionStrategy = FlintSessionAutoClearPlan.shortSessionStrategy,
        healthDefaults: @escaping () -> UserDefaults?
    ) {
        self.store = store
        self.shield = shield
        self.registrar = registrar
        self.isAuthorized = isAuthorized
        self.clock = clock
        self.shortSessionStrategy = shortSessionStrategy
        self.healthDefaults = healthDefaults
    }

    public static var live: FlintSessionEnvironment {
        FlintSessionEnvironment(
            store: { FlintGroupStore() },
            shield: LiveSessionShield(),
            registrar: LiveSessionRegistrar(),
            isAuthorized: { AuthorizationCenter.shared.authorizationStatus == .approved },
            clock: .system,
            healthDefaults: { .flintGroup }
        )
    }
}

/// What the monitor extension saw for a session activity.
public enum FlintSessionMonitorEvent: Equatable, Sendable {
    case intervalDidStart
    case intervalWillEndWarning
    case intervalDidEnd
}

/// What `reconcile()` found and did.
public struct FlintSessionReconcileReport: Equatable, Sendable {
    public enum Outcome: Equatable, Sendable {
        case noSession
        case finishedExpired
        case running
    }

    public var outcome: Outcome
    /// The auto-clear was re-registered (time-zone change or iOS no longer held it).
    public var rearmed = false
    /// Re-registration was needed but iOS refused it (or the re-registration limit was hit).
    public var rearmFailed = false
    /// A session is recorded as running but Screen Time authorization is not approved.
    public var notAuthorized = false
    public var timeVerdict: FlintTimeIntegrity.Verdict = .unverifiable
}

// MARK: - Controller

/// Orchestrates a "Block Now" session end-to-end: validate, register the `DeviceActivity`
/// auto-clear that ends the session while Flint is closed, apply shields, persist the
/// `FlintActiveSession`.
///
/// Ordering is the safety property: the auto-clear is registered **before** anything changes,
/// so if iOS refuses it (or Screen Time isn't authorized) no shield is applied and no session
/// is persisted — the UI can never show a block that has no out-of-app end. The new session is
/// then persisted before the previous registration is stopped and before the shield is written,
/// so the monitor extension (another process) always finds a record matching whatever shield
/// and registration exist. Each registration gets a unique activity name, so a late callback
/// for a replaced session can't end the new one.
public final class FlintSessionController {

    /// Prefix of every session activity name; also the exact name older builds registered.
    public static let monitorName = "flint.session"

    /// Re-registrations allowed per session after its start (time-zone changes, an end iOS
    /// delivered early, a registration iOS dropped). Each one briefly holds an extra slot in the
    /// shared DeviceActivity pool, so a device that keeps misfiring can't drain it.
    public static let maximumRearms = 3

    /// Serializes start/stop/reconcile within a process: the UI, App Intents and the Focus
    /// filter can all reach the session at once. (The monitor extension is a separate process;
    /// its decisions re-read state and tolerate a concurrent host write.)
    private static let lock = NSRecursiveLock()

    private let env: FlintSessionEnvironment

    public init(environment: FlintSessionEnvironment = .live) {
        self.env = environment
    }

    public var activeSession: FlintActiveSession? {
        env.store()?.loadActiveSession()
    }

    /// True for `flint.session` and every `flint.session.<token>` registration.
    public static func isSessionActivity(_ name: String) -> Bool {
        name == monitorName || name.hasPrefix(monitorName + ".")
    }

    static func makeMonitorName() -> String {
        let token = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16).lowercased()
        return "\(monitorName).\(token)"
    }

    // MARK: Start

    /// Start an immediate block. `duration <= 0` means open-ended (until manually stopped).
    @discardableResult
    public func startBlockNow(
        selection: FamilyActivitySelection,
        duration: TimeInterval,
        breakLevel: BreakLevel,
        name: String = "Focus",
        source: FlintSessionSource = .manual
    ) throws -> FlintActiveSession {
        Self.lock.lock(); defer { Self.lock.unlock() }

        guard let store = env.store() else { throw FlintSessionError.appGroupUnavailable }
        guard env.isAuthorized() else { throw FlintSessionError.notAuthorized }

        let counts = env.shield.counts(of: selection)
        guard counts.total > 0 else { throw FlintSessionError.emptySelection }
        if counts.applications > FlintShieldLimits.tokenCap {
            throw FlintSessionError.tooManyTokens(count: counts.applications, cap: FlintShieldLimits.tokenCap)
        }
        if counts.webDomains > FlintShieldLimits.tokenCap {
            throw FlintSessionError.tooManyTokens(count: counts.webDomains, cap: FlintShieldLimits.tokenCap)
        }
        if duration > 0 {
            switch FlintSessionAutoClearPlan.issue(forDuration: duration, strategy: env.shortSessionStrategy) {
            case .tooShort?:
                let floor = env.shortSessionStrategy == .floor
                    ? FlintSessionAutoClearPlan.minimumInterval : FlintSessionAutoClearPlan.minimumDuration
                throw FlintSessionError.durationTooShort(minimumMinutes: Int(floor / 60))
            case .tooLong?:
                throw FlintSessionError.durationTooLong(maximumMinutes: Int(FlintSessionAutoClearPlan.maximumDuration / 60))
            case nil:
                break
            }
        }

        let now = FlintSessionAutoClearPlan.wholeSecond(atOrAfter: env.clock.now())
        let endsAt = duration > 0 ? now.addingTimeInterval(duration.rounded(.up)) : nil
        let monitorName = Self.makeMonitorName()
        let plan = endsAt.map {
            FlintSessionAutoClearPlan.make(start: now, end: $0, calendar: env.clock.calendar(),
                                           strategy: env.shortSessionStrategy)
        }
        let session = FlintActiveSession(
            name: name,
            startedAt: now,
            endsAt: endsAt,
            breakLevel: breakLevel,
            monitorName: monitorName,
            monotonicStart: env.clock.monotonic(),
            bootSessionID: env.clock.bootSessionID(),
            autoClearTimeZoneID: plan?.timeZoneIdentifier,
            autoClearUTCOffset: plan?.utcOffsetAtEnd,
            usesEndWarning: plan.map(\.usesEndWarning)
        )
        let previous = store.loadActiveSession()
        let previousSelection = previous.flatMap { store.loadSessionSelection(sessionID: $0.id) }

        // 1. The out-of-app end, before anything changes.
        if let plan {
            do {
                try env.registrar.startMonitoring(monitorName, plan: plan)
            } catch {
                let reason = String(describing: error)
                FlintSessionHealth.update(in: env.healthDefaults()) {
                    $0.lastArmFailure = .init(sessionName: name, reason: reason, at: now)
                }
                FlintSessionLifecycle.didFailToArm(session, reason: "monitor_registration_failed", at: now)
                throw FlintSessionError.armingFailed(reason: reason)
            }
        }

        // 2. Persist before anything else changes, so the monitor always finds the record that
        //    matches the registration and shield it can see. The id-keyed target snapshot goes
        //    first: a monitor that already sees the new record can only find its own targets.
        store.saveSessionSelection(selection, sessionID: session.id)
        store.saveActiveSession(session)
        store.saveSelection(selection)

        // 3. The previous session's end can no longer fire against this one.
        if let previous, previous.monitorName != monitorName {
            env.registrar.stopMonitoring([previous.monitorName])
        }

        // 4. Shield immediately so the block takes effect now, not at the monitor's callback.
        do {
            try env.shield.applyBlockList(selection)
        } catch {
            // Unreachable after the count checks above, but never leave a half-started session:
            // drop the new registration and give the previous session back its record and end.
            if plan != nil { env.registrar.stopMonitoring([monitorName]) }
            if let previous {
                if let previousSelection { store.saveSessionSelection(previousSelection, sessionID: previous.id) }
                store.saveActiveSession(previous)
                if !previous.isExpired(at: now) { rearm(previous, in: store, now: now, reason: .repair) }
            } else {
                store.clearActiveSession()
            }
            throw error
        }

        // Uninstall guard: a Hardcore block also denies app removal (deleting Flint is otherwise
        // the one-tap escape). Same store as the shield, so every teardown path drops both
        // together via clearAllSettings(); non-Hardcore starts assert nil, so replacing a
        // Hardcore session can't leave the guard dangling.
        env.shield.setDenyAppRemoval(breakLevel == .hardcore)

        FlintSessionHealth.update(in: env.healthDefaults()) {
            $0.lastArmFailure = nil
            $0.lastTimeAnomaly = nil
            $0.unauthorizedSince = nil
        }
        FlintSessionLifecycle.didStart(session, replacing: previous, source: source, at: now)
        return session
    }

    // MARK: Stop

    /// Whether the active session is allowed to be stopped right now.
    /// Hardcore can't be stopped early (until it expires, or an Emergency Pass is spent).
    public func canStop(_ session: FlintActiveSession) -> Bool {
        switch session.breakLevel {
        case .easy, .harder:
            return true
        case .hardcore:
            return session.isExpired(at: env.clock.now())
        }
    }

    /// Tear down the active session: clear shields — which also drops the Hardcore uninstall
    /// guard living on the same store — stop its auto-clear, forget the session. A session past
    /// its planned end is reported as completed whatever route ended it.
    public func stop(route: FlintSessionEndRoute = .manualStop) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let store = env.store()
        guard let session = store?.loadActiveSession() else {
            env.shield.clear()
            return
        }
        let now = env.clock.now()
        end(session, route: session.isExpired(at: now) ? .completed : route, at: now, in: store)
    }

    /// Called when a timed session reaches its end (or the app notices it expired): clean up.
    public func finishIfExpired() {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let store = env.store()
        let now = env.clock.now()
        guard let session = store?.loadActiveSession(), session.isExpired(at: now) else { return }
        end(session, route: .completed, at: now, in: store)
    }

    private func end(
        _ session: FlintActiveSession,
        route: FlintSessionEndRoute,
        at now: Date,
        in store: FlintSessionStateStoring?
    ) {
        env.shield.clear()
        FlintSessionLifecycle.didEnd(session, route: route, at: now)
        // Forget the record before stopping its registration: iOS may deliver intervalDidEnd
        // for a stopped activity, and it must then find no matching session — never a live
        // one to "complete" or re-arm.
        store?.clearActiveSession()
        store?.clearSessionSelection()
        env.registrar.stopMonitoring([session.monitorName])
    }

    // MARK: Reconcile (launch; foreground and clock/time-zone changes once B3 wires them)

    /// Bring the session's out-of-app state back in line with its record: record any wall-clock
    /// skew first (a forward jump that ages the session out must not look like a clean
    /// completion), finish an expired session; for a running one, re-apply its shield and
    /// uninstall guard (idempotent — repairs a crash between record and shield), re-register its
    /// auto-clear when iOS no longer holds it or the time zone moved its wall-clock end, and flag
    /// it when Screen Time authorization is missing. Never extends or shortens `endsAt`.
    @discardableResult
    public func reconcile() -> FlintSessionReconcileReport {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let store = env.store()
        let now = env.clock.now()
        guard let session = store?.loadActiveSession() else {
            // Nothing else writes the session's store, and every start/stop in this process
            // holds the lock — so with no record, whatever is left on it is stale.
            env.shield.clear()
            store?.clearSessionSelection()
            FlintSessionHealth.update(in: env.healthDefaults()) { $0.unauthorizedSince = nil }
            return FlintSessionReconcileReport(outcome: .noSession)
        }

        let verdict = FlintTimeIntegrity.verdict(for: session, clock: env.clock)
        recordTimeVerdict(verdict, for: session, at: now)

        if session.isExpired(at: now) {
            end(session, route: .completed, at: now, in: store)
            FlintSessionHealth.update(in: env.healthDefaults()) { $0.unauthorizedSince = nil }
            var report = FlintSessionReconcileReport(outcome: .finishedExpired)
            report.timeVerdict = verdict
            return report
        }

        var report = FlintSessionReconcileReport(outcome: .running)
        report.timeVerdict = verdict

        // Not destructive: the status can read stale right after launch, and ending a Hardcore
        // session on a misread would be a bypass. Surface it instead.
        report.notAuthorized = !env.isAuthorized()
        FlintSessionHealth.update(in: env.healthDefaults()) {
            $0.unauthorizedSince = report.notAuthorized ? ($0.unauthorizedSince ?? now) : nil
        }

        // Re-read right before writing: the monitor (another process) may have just ended this
        // session, and re-shielding after that would leave a shield nothing ever clears.
        if let store, store.loadActiveSession()?.id == session.id,
           let selection = sessionSelection(for: session, in: store) {
            try? env.shield.applyBlockList(selection)
        }

        if let store, session.endsAt != nil {
            let zoneMoved = !FlintSessionAutoClearPlan.isCurrent(for: session, in: env.clock.calendar())
            let missing = !env.registrar.registeredActivityNames().contains(session.monitorName)
            if zoneMoved || missing {
                if zoneMoved { recordAnomaly(.timeZoneChanged, seconds: 0, session: session, at: now) }
                switch rearm(session, in: store, now: now, reason: .repair) {
                case .rearmed: report.rearmed = true
                case .failed: report.rearmFailed = true
                case .deferred: break
                }
            }
        }

        env.shield.setDenyAppRemoval(FlintUninstallGuard.shouldDeny(for: session, now: now))
        return report
    }

    /// Launch-time reconciliation kept for existing callers; `reconcile()` now does the full job.
    public func reconcileUninstallGuard() {
        reconcile()
    }

    enum RearmReason { case repair, prematureEnd }
    enum RearmOutcome { case rearmed, failed, deferred }

    /// Minimum spacing between repair re-registrations of one session (launch, foreground and
    /// time notifications can arrive in bursts).
    public static let repairInterval: TimeInterval = 60

    /// The session's own frozen targets; sessions saved by older builds have no snapshot and
    /// fall back to the picker selection they were started from.
    private func sessionSelection(for session: FlintActiveSession, in store: FlintSessionStateStoring) -> FamilyActivitySelection? {
        store.loadSessionSelection(sessionID: session.id) ?? store.loadSelection()
    }

    /// Re-register `session`'s auto-clear for its remaining time under a fresh name: register
    /// the new name, persist it, then stop the old one — a callback from the old registration
    /// can then only arrive as a mismatch and is ignored.
    ///
    /// Repairs (registration missing, zone moved) are never refused while the session is live,
    /// only spaced by `repairInterval`; ends iOS delivered early are bounded by `maximumRearms`,
    /// so a device that keeps misfiring can't drain the shared registration pool.
    @discardableResult
    private func rearm(
        _ session: FlintActiveSession,
        in store: FlintSessionStateStoring,
        now: Date,
        reason: RearmReason
    ) -> RearmOutcome {
        guard let endsAt = session.endsAt, endsAt > now else { return .failed }
        switch reason {
        case .prematureEnd:
            guard (session.rearmCount ?? 0) < Self.maximumRearms else {
                FlintSessionHealth.update(in: env.healthDefaults()) {
                    $0.lastArmFailure = .init(sessionName: session.name,
                                              reason: "iOS kept ending this session early; its end is no longer re-registered",
                                              at: now)
                }
                return .failed
            }
        case .repair:
            if let last = session.lastRearmAt, now.timeIntervalSince(last) < Self.repairInterval, now >= last {
                return .deferred
            }
        }
        let start = FlintSessionAutoClearPlan.wholeSecond(atOrAfter: now)
        let plan = FlintSessionAutoClearPlan.make(start: start, end: max(endsAt, start), calendar: env.clock.calendar(),
                                                  strategy: .endWarning)
        let name = Self.makeMonitorName()
        do {
            try env.registrar.startMonitoring(name, plan: plan)
        } catch {
            let message = String(describing: error)
            FlintSessionHealth.update(in: env.healthDefaults()) {
                $0.lastArmFailure = .init(sessionName: session.name, reason: message, at: now)
            }
            FlintSessionLifecycle.didFailToArm(session, reason: "monitor_registration_failed", at: now)
            return .failed
        }
        store.saveActiveSession(session.rearmed(monitorName: name, plan: plan, at: now,
                                                countsTowardLimit: reason == .prematureEnd))
        if session.monitorName != name { env.registrar.stopMonitoring([session.monitorName]) }
        // The running session is armed again: an earlier refusal is no longer current.
        FlintSessionHealth.update(in: env.healthDefaults()) { $0.lastArmFailure = nil }
        return .rearmed
    }

    private func recordTimeVerdict(_ verdict: FlintTimeIntegrity.Verdict, for session: FlintActiveSession, at now: Date) {
        switch verdict {
        case let .wallAhead(seconds): recordAnomaly(.wallAhead, seconds: seconds, session: session, at: now)
        case let .wallBehind(seconds): recordAnomaly(.wallBehind, seconds: seconds, session: session, at: now)
        case .consistent, .unverifiable: break
        }
    }

    private func recordAnomaly(
        _ kind: FlintSessionHealth.TimeAnomaly.Kind,
        seconds: TimeInterval,
        session: FlintActiveSession,
        at now: Date
    ) {
        FlintSessionHealth.update(in: env.healthDefaults()) {
            $0.lastTimeAnomaly = .init(kind: kind, seconds: seconds, sessionID: session.id, detectedAt: now)
        }
    }

    // MARK: Monitor extension entry point

    /// The monitor extension's whole session branch. Only the registration currently recorded
    /// on the active session can affect it:
    ///  - start: re-apply the saved selection's shield (the host already applied it; this keeps
    ///    the block if the host died mid-start) and the uninstall guard;
    ///  - end warning / interval end at `endsAt` (whole-second slack): end it as completed;
    ///  - interval end before `endsAt` (e.g. a time-zone change shifted the wall-clock end): keep
    ///    the block and re-register its end — never drop protection early; an early warning is
    ///    ignored because the interval end still follows;
    ///  - any other session name is a replaced registration: deregistered at its end, ignored.
    public func handleMonitorEvent(_ event: FlintSessionMonitorEvent, activity: String) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let store = env.store()
        let now = env.clock.now()
        let session = store?.loadActiveSession()

        guard let session, session.monitorName == activity else {
            // No session at its own interval end: clear what is left of a session whose record
            // was lost, as older builds did. A *different* active session means this callback is
            // from a replaced registration: never touch the newer session.
            if event == .intervalDidEnd {
                if session == nil { env.shield.clear() }
                env.registrar.stopMonitoring([activity])
            }
            return
        }

        switch event {
        case .intervalDidStart:
            if let store, let selection = sessionSelection(for: session, in: store) {
                try? env.shield.applyBlockList(selection)
            }
            env.shield.setDenyAppRemoval(FlintUninstallGuard.shouldDeny(for: session, now: now))

        case .intervalWillEndWarning, .intervalDidEnd:
            let endsAt = session.endsAt ?? now
            if now.addingTimeInterval(FlintTimeIntegrity.endSlack) >= endsAt {
                recordTimeVerdict(FlintTimeIntegrity.verdict(for: session, clock: env.clock), for: session, at: now)
                env.shield.clear()
                // Report a completion only for an on-time observation of the logical deadline.
                // A short session's padded carrier end after a missed warning is recovery, not a
                // successful short session (ADR-012 §4): record the adapter failure and leave the
                // outcome to focus history's reconciliation; so is any other late observation.
                let lateness = now.timeIntervalSince(endsAt)
                if event == .intervalDidEnd, session.usesEndWarning == true, lateness > FlintTimeIntegrity.onTimeWindow {
                    recordAnomaly(.missedEndWarning, seconds: lateness, session: session, at: now)
                }
                if abs(lateness) <= FlintTimeIntegrity.onTimeWindow {
                    FlintSessionLifecycle.didEnd(session, route: .completed, at: endsAt)
                }
                store?.clearActiveSession()
                store?.clearSessionSelection()
                env.registrar.stopMonitoring([activity])
            } else if event == .intervalDidEnd, let store {
                // The interval is over, but the session isn't: keep the shield, re-register.
                recordAnomaly(.prematureEnd, seconds: endsAt.timeIntervalSince(now), session: session, at: now)
                rearm(session, in: store, now: now, reason: .prematureEnd)
            }
            // An early warning is ignored: the interval end that follows still ends the session.
        }
    }

    // MARK: Emergency pass (free, one per week)

    public func emergencyPassAvailable(now: Date? = nil) -> Bool {
        FlintEmergencyPass.isAvailable(lastUsed: env.store()?.emergencyPassLastUsed(), now: now ?? env.clock.now())
    }

    /// Spend the weekly Emergency Pass to end a running Hardcore session early. Returns false if
    /// there's no eligible Hardcore session or the pass isn't available yet.
    @discardableResult
    public func useEmergencyPass() -> Bool {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let store = env.store()
        let now = env.clock.now()
        guard let session = store?.loadActiveSession(), session.breakLevel == .hardcore,
              !session.isExpired(at: now) else {
            return false
        }
        guard emergencyPassAvailable(now: now) else { return false }
        store?.recordEmergencyPassUse(now)
        FlintSessionLifecycle.didUsePass(session, at: now)
        end(session, route: .emergencyPass, at: now, in: store)
        return true
    }
}

// MARK: - Live adapters

final class LiveSessionShield: FlintSessionShielding {
    private let store = FlintShieldStore(named: "flint")

    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts {
        FlintSelectionCounts(
            applications: selection.applicationTokens.count,
            categories: selection.categoryTokens.count,
            webDomains: selection.webDomainTokens.count
        )
    }

    func applyBlockList(_ selection: FamilyActivitySelection) throws {
        try store.applyBlockList(
            applications: selection.applicationTokens,
            categories: selection.categoryTokens,
            webDomains: selection.webDomainTokens
        )
    }

    func setDenyAppRemoval(_ deny: Bool) { store.setDenyAppRemoval(deny) }

    func clear() { store.clear() }
}

final class LiveSessionRegistrar: FlintSessionActivityRegistering {
    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        let schedule = DeviceActivitySchedule(
            intervalStart: plan.intervalStart,
            intervalEnd: plan.intervalEnd,
            repeats: false,
            warningTime: plan.warningTime
        )
        try FlintScheduling.startMonitoring(name, during: schedule)
    }

    func stopMonitoring(_ names: [String]) { FlintScheduling.stopMonitoring(names) }

    func registeredActivityNames() -> Set<String> {
        Set(DeviceActivityCenter().activities.map(\.rawValue))
    }
}
#endif
