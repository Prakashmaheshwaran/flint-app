import CryptoKit
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
    /// Harder and Hardcore need a set end time; only Easy may run "until I stop" (ADR-012 §1).
    case needsFiniteEnd
    /// Another session is running and this request didn't ask to replace it; iOS Flint runs one
    /// session at a time, so nothing changed (ADR-012 §3 `capacity_unavailable`).
    case capacityUnavailable(runningName: String, until: Date?)
    /// The running session is Hardcore and the request would weaken it; nothing changed.
    case replacementDenied(until: Date?)
    /// The running session is Harder: a wait before replacing it is running. Ask again once it
    /// has passed to confirm.
    case replacementWaiting(remaining: TimeInterval)
    /// Protection state couldn't be read, so no weakening is allowed (ADR-012 §8).
    case protectionStateUnavailable
    /// A Harder wait has passed, but a background request (Shortcut, Focus) never completes it:
    /// the final confirmation happens in Flint (ADR-012 §2).
    case confirmInFlint
    /// The request needs the user's explicit confirmation first (a Hardcore start, or replacing
    /// the running session).
    case needsConfirmation

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
        case .needsFiniteEnd:
            return "Harder and Hardcore sessions need an end time. Choose how long it should run."
        case let .capacityUnavailable(running, until):
            return "“\(running)” is already running\(Self.untilText(until)). Flint runs one session at a time."
        case let .replacementDenied(until):
            return "The running Hardcore session can't be replaced by a weaker one\(Self.untilText(until)). "
                + "It ends on its own, or you can use the Emergency Pass in Flint."
        case let .replacementWaiting(remaining):
            return "The running session is Harder: wait \(Self.minutesText(remaining)), then ask again to replace it."
        case .protectionStateUnavailable:
            return "Flint couldn't read its protection state, so nothing was changed. Open Flint to check."
        case .confirmInFlint:
            return "The wait is over, but the running session is Harder: open Flint to confirm the change."
        case .needsConfirmation:
            return "Confirm this in Flint first."
        }
    }

    static func untilText(_ date: Date?) -> String {
        guard let date else { return "" }
        return " until \(date.formatted(date: .omitted, time: .shortened))"
    }

    static func minutesText(_ seconds: TimeInterval) -> String {
        let minutes = max(1, Int((seconds / 60).rounded(.up)))
        return minutes == 1 ? "1 minute" : "\(minutes) minutes"
    }
}

/// How a session's start request arrived (ADR-012 §11 `originRoute`). The route never changes
/// tier rights; it decides only who owns the session for an owner-ended stop.
public enum FlintSessionOrigin: String, Codable, Equatable, Sendable {
    case appUI = "app_ui"
    case shortcut
    case focusFilter = "focus_filter"
    case automation
}

/// What a stop request did (ADR-012 §2 stop row).
public enum FlintSessionStopOutcome: Equatable, Sendable {
    case stopped
    /// Harder: a persisted wait is running; `remaining` seconds until it can be confirmed.
    case waiting(remaining: TimeInterval)
    /// Harder: the wait has passed; ask again with `confirmed: true` to stop.
    case readyToConfirm
    /// Easy: the stop needs the user's explicit confirmation (`confirmed: true`).
    case needsConfirmation
    /// Not allowed now (Hardcore before its end); the pass is the only exit.
    case denied(FlintDenialReason, until: Date?)
    /// Nothing is running, or the session isn't owned by the requester.
    case notApplicable
    /// Protection state couldn't be read; nothing changed.
    case unavailable
}

/// Opaque target identities a selection blocks, so replacement dominance can be decided.
public struct FlintSessionTargets: Equatable {
    public var applications: Set<AnyHashable>
    public var categories: Set<AnyHashable>
    public var webDomains: Set<AnyHashable>

    public init(applications: Set<AnyHashable> = [], categories: Set<AnyHashable> = [], webDomains: Set<AnyHashable> = []) {
        self.applications = applications
        self.categories = categories
        self.webDomains = webDomains
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
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets
    /// A digest of the selection's tokens that is stable across launches (token encodings are);
    /// identifies a target set without storing tokens.
    func fingerprint(of selection: FamilyActivitySelection) -> String
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
    /// True when a session record is stored, even one that doesn't decode — so an unreadable
    /// record is never mistaken for "no session" (ADR-012 §8).
    func hasActiveSessionRecord() -> Bool
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
    /// Positive evidence the shared state was read, not an empty locked or unavailable
    /// container: only then is a missing record "no session" (ADR-012 §8).
    func isProtectionStateReadable() -> Bool
}

public extension FlintSessionStateStoring {
    func isProtectionStateReadable() -> Bool { true }
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
    /// Safari writes use the same "flint" owner; shield.clear() releases them on every end.
    public var webRestrictions: FlintSessionWebRestricting
    public var registrar: FlintSessionActivityRegistering
    public var isAuthorized: () -> Bool
    public var clock: FlintClock
    public var shortSessionStrategy: FlintSessionAutoClearPlan.ShortSessionStrategy
    /// Where `FlintSessionHealth` persists.
    public var healthDefaults: () -> UserDefaults?
    /// The cross-process protection ledger (Harder waits; ADR-012 §8). nil = unavailable.
    public var ledger: () -> FlintProtectionLedger?
    /// Re-publishes the shared status projection after a committed change (lanes K/H read it).
    public var publishStatus: () -> Void
    /// The cross-process session-transition lock (R-20). nil = no App Group container, where
    /// only the process-local lock applies (and no shared record can be read anyway).
    public var transitionLock: () -> FlintSessionTransitionLock?
    /// How long a monitor callback waits for that lock. Longer than an app-side wait: a skipped
    /// end leaves the block on until the host next reconciles, so the extension waits out
    /// another process's short transition rather than give up.
    public var monitorLockTimeout: TimeInterval

    public init(
        store: @escaping () -> FlintSessionStateStoring?,
        shield: FlintSessionShielding,
        webRestrictions: FlintSessionWebRestricting,
        registrar: FlintSessionActivityRegistering,
        isAuthorized: @escaping () -> Bool,
        clock: FlintClock,
        shortSessionStrategy: FlintSessionAutoClearPlan.ShortSessionStrategy = FlintSessionAutoClearPlan.shortSessionStrategy,
        healthDefaults: @escaping () -> UserDefaults?,
        ledger: @escaping () -> FlintProtectionLedger? = { nil },
        publishStatus: @escaping () -> Void = {},
        transitionLock: @escaping () -> FlintSessionTransitionLock? = { nil },
        monitorLockTimeout: TimeInterval = 6
    ) {
        self.store = store
        self.shield = shield
        self.webRestrictions = webRestrictions
        self.registrar = registrar
        self.isAuthorized = isAuthorized
        self.clock = clock
        self.shortSessionStrategy = shortSessionStrategy
        self.healthDefaults = healthDefaults
        self.ledger = ledger
        self.publishStatus = publishStatus
        self.transitionLock = transitionLock
        self.monitorLockTimeout = monitorLockTimeout
    }

    public static var live: FlintSessionEnvironment {
        FlintSessionEnvironment(
            store: { FlintGroupStore() },
            shield: LiveSessionShield(),
            webRestrictions: FlintSessionWebRestrictionAdapter(
                writer: FlintManagedWebRestrictionWriter(), configuration: { .loadForEnforcement() }
            ),
            registrar: LiveSessionRegistrar(),
            isAuthorized: { AuthorizationCenter.shared.authorizationStatus == .approved },
            clock: .system,
            healthDefaults: { .flintGroup },
            ledger: { FlintProtectionLedger.appGroup() },
            publishStatus: { FlintProtectionStatus.publishAfterCommit() },
            transitionLock: { FlintSessionTransitionLock.appGroup() }
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
    /// Another process held the session-transition lock: nothing was checked or changed; the
    /// next reconcile repeats it.
    public var transitionBusy = false
    /// A session record is stored but can't be read: its shield is kept, nothing is released.
    public var recordUnreadable = false
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
    /// filter can all reach the session at once. Across processes (host vs. the monitor
    /// extension) every transition also holds `FlintSessionTransitionLock` and reads the record
    /// inside it, so an old registration's callback can't clear a replacement (R-20).
    private static let lock = NSRecursiveLock()
    private static var transitionDepth = 0

    /// Take the cross-process transition lock for the outermost transition in this process (the
    /// caller holds `Self.lock`). nil = another process holds it past the timeout: the caller
    /// changes nothing.
    private func beginTransition(timeout: TimeInterval? = nil) -> FlintSessionTransitionLock.Hold? {
        if Self.transitionDepth > 0 { Self.transitionDepth += 1; return .nested }
        guard let lock = env.transitionLock() else { Self.transitionDepth += 1; return .nested }
        guard let hold = lock.acquire(timeout: timeout) else { return nil }
        Self.transitionDepth += 1
        return hold
    }

    private func endTransition(_ hold: FlintSessionTransitionLock.Hold) {
        Self.transitionDepth -= 1
        hold.end()
    }

    /// Run `body` as one session transition: the in-process lock plus the cross-process
    /// `FlintSessionTransitionLock`, re-entrant within this process — so other authorities (the
    /// Emergency Pass, rule edits) can hold the transition around their own read-decide-persist
    /// and still call into this controller. nil = another process held it past the timeout and
    /// `body` didn't run. Lock order: this transition, then any ledger transaction.
    public func withTransition<T>(_ body: () throws -> T) rethrows -> T? {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard let transition = beginTransition() else { return nil }
        defer { endTransition(transition) }
        return try body()
    }

    /// Whether the session's commitment is over by its logical (monotonic-governed) time.
    private func isOver(_ session: FlintActiveSession) -> Bool {
        session.isLogicallyExpired(clock: env.clock)
    }

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

    /// Start an immediate block. `duration <= 0` means open-ended (until manually stopped),
    /// which only Easy may use (ADR-012 §1).
    ///
    /// While another session runs: `replacing: false` (an additive start, e.g. a Focus turning on)
    /// is refused with `capacityUnavailable` — iOS Flint runs one session at a time; with
    /// `replacing: true` the replacement is decided by ADR-012 §2/§3 against the running
    /// session's tier: a dominating replacement is allowed; a weaker one is allowed for Easy,
    /// waits for Harder (the wait persists; ask again after it to replace), and is denied for
    /// Hardcore. A replacement without the running session's own frozen targets can't prove
    /// dominance and counts as weakening. `confirmed` is the user's explicit confirmation of the
    /// new rights (a Hardcore start needs it, including via automation — §2); a background
    /// origin never completes a Harder wait (`confirmInFlint`).
    @discardableResult
    public func startBlockNow(
        selection: FamilyActivitySelection,
        duration: TimeInterval,
        breakLevel: BreakLevel,
        name: String = "Focus",
        source: FlintSessionSource = .manual,
        origin: FlintSessionOrigin,
        ownerID: String? = nil,
        requestID: String? = nil,
        replacing: Bool = true,
        confirmed: Bool = false
    ) throws -> FlintActiveSession {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard let transition = beginTransition() else { throw FlintSessionError.protectionStateUnavailable }
        defer { endTransition(transition) }

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

        let kind: FlintCauseState.Kind = source == .pomodoro ? .pomodoro : .session
        switch FlintProtectionPolicy.issue(forNew: kind, tier: breakLevel, duration: duration > 0 ? duration : nil) {
        case .needsFiniteEnd?: throw FlintSessionError.needsFiniteEnd
        case .tooLong?: throw FlintSessionError.durationTooLong(maximumMinutes: Int(FlintSessionAutoClearPlan.maximumDuration / 60))
        case .nonPositive?: throw FlintSessionError.durationTooShort(minimumMinutes: 1)
        case nil: break
        }

        if case .allow(confirm: true) = FlintProtectionPolicy.decideStart(
            kind: kind, newTier: breakLevel, duration: duration > 0 ? duration : nil, capacityAvailable: true),
            !confirmed {
            throw FlintSessionError.needsConfirmation
        }

        // Sample wall and monotonic time together: the planned duration is measured from this
        // anchor, so the logical end equals the (whole-second) wall end in a steady clock.
        let wallNow = env.clock.now()
        let monotonicNow = env.clock.monotonic()
        let now = FlintSessionAutoClearPlan.wholeSecond(atOrAfter: wallNow)
        let endsAt = duration > 0 ? now.addingTimeInterval(duration.rounded(.up)) : nil

        // Arbitrate against a running session before anything changes (ADR-012 §2/§3). A record
        // that exists but can't be read is unknown protection: never overwrite it (§8).
        let current = store.loadActiveSession()
        if current == nil, store.hasActiveSessionRecord() { throw FlintSessionError.protectionStateUnavailable }
        // A retried request (double tap, intent retry) returns the session it already started:
        // no new id, deadline or registration (PC-03).
        if let requestID, let current, current.startRequestID == requestID, !isOver(current) { return current }
        if let running = current, !isOver(running) {
            guard replacing else {
                throw FlintSessionError.capacityUnavailable(runningName: running.name, until: running.endsAt)
            }
            let targets = env.shield.targets(of: selection)
            let new = FlintProtectionSnapshot(mode: .blockList(
                applications: targets.applications, categories: targets.categories,
                webDomains: targets.webDomains), tier: breakLevel, end: endsAt)
            let cause = causeState(of: running, at: now)
            // Dominance needs the running session's own frozen targets. Without them (a session
            // from an older build, or a lost snapshot) it can't be proven — the picker selection
            // is the user's current draft, not what is blocked — so the replacement is weakening.
            let decision: FlintProtectionDecision
            if let oldSelection = store.loadSessionSelection(sessionID: running.id) {
                decision = FlintProtectionPolicy.decideReplace(new, over: snapshot(of: running, selection: oldSelection), for: cause)
            } else {
                decision = FlintProtectionPolicy.decide(.replaceWeaker, for: cause)
            }
            switch decision {
            case let .allow(confirm):
                if confirm, !confirmed { throw FlintSessionError.needsConfirmation }
            case .notApplicable:
                break
            case .deny, .deferUntil:
                // (`decide` never defers a replacement; treated as a denial if it ever did.)
                throw FlintSessionError.replacementDenied(until: running.endsAt)
            case .wait:
                let scope = Self.replacementScope(tier: breakLevel, duration: duration,
                                                  targets: env.shield.fingerprint(of: selection))
                switch harderGate(for: running, kind: "replace", scope: scope,
                                  confirmed: confirmed && origin == .appUI) {
                case .proceed: break
                case let .waiting(remaining): throw FlintSessionError.replacementWaiting(remaining: remaining)
                case .ready:
                    throw origin == .appUI ? FlintSessionError.replacementWaiting(remaining: 0) : FlintSessionError.confirmInFlint
                case .unavailable: throw FlintSessionError.protectionStateUnavailable
                }
            }
        }

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
            monotonicStart: monotonicNow,
            bootSessionID: env.clock.bootSessionID(),
            autoClearTimeZoneID: plan?.timeZoneIdentifier,
            autoClearUTCOffset: plan?.utcOffsetAtEnd,
            usesEndWarning: plan.map(\.usesEndWarning),
            contractVersion: FlintActiveSession.currentContractVersion,
            origin: origin.rawValue,
            ownerID: ownerID,
            plannedDuration: endsAt.map { $0.timeIntervalSince(wallNow) },
            startRequestID: requestID
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
                if !isOver(previous) { rearm(previous, in: store, now: now, reason: .repair) }
            } else {
                store.clearActiveSession()
            }
            throw error
        }

        // The auto-end is armed and the shield succeeded. Safari belongs to this same cause's
        // store, so every existing whole-store teardown releases it without a second clear.
        env.webRestrictions.reconcile(session, clock: env.clock)

        // Uninstall guard: a Hardcore block also denies app removal (deleting Flint is otherwise
        // the one-tap escape). Same store as the shield, so every teardown path drops both
        // together via clearAllSettings(); non-Hardcore starts assert nil, so replacing a
        // Hardcore session can't leave the guard dangling.
        env.shield.setDenyAppRemoval(breakLevel == .hardcore)

        FlintSessionHealth.update(in: env.healthDefaults()) {
            $0.lastArmFailure = nil
            $0.lastTimeAnomaly = nil
            $0.unauthorizedSince = nil
            $0.lastRefusal = nil
        }
        FlintSessionLifecycle.didStart(session, replacing: previous, source: source, at: now)
        env.publishStatus()
        return session
    }

    // MARK: Stop

    /// Whether an in-app stop is possible at all (immediately for Easy, after the persisted wait
    /// for Harder). Hardcore can't be stopped early — expiry or an Emergency Pass are its exits.
    public func canStop(_ session: FlintActiveSession) -> Bool {
        switch session.breakLevel {
        case .easy, .harder:
            return true
        case .hardcore:
            return isOver(session)
        }
    }

    /// Ask to end the running session early, through ADR-012 §2's stop row for its current tier.
    /// `route == .focusFilterOff` is an owner-ended request and only applies to a session the
    /// Focus filter owns. Easy needs `confirmed` (an explicit tap or an intent); Harder starts or
    /// resumes the persisted 60/300/900 s wait and stops only when asked again with `confirmed`
    /// after it has passed; Hardcore is denied. A session already past its end just completes.
    @discardableResult
    public func requestStop(route: FlintSessionEndRoute = .manualStop, confirmed: Bool) -> FlintSessionStopOutcome {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard let transition = beginTransition() else { return .unavailable }
        defer { endTransition(transition) }
        let store = env.store()
        let now = env.clock.now()
        guard let session = store?.loadActiveSession() else {
            if store?.hasActiveSessionRecord() == true { return .unavailable }
            env.shield.clear()
            return .notApplicable
        }
        if isOver(session) {
            end(session, route: .completed, at: now, in: store)
            return .stopped
        }
        let ownerEnded = route == .focusFilterOff
        if ownerEnded, !isOwnedByFocusFilter(session) { return .notApplicable }

        switch FlintProtectionPolicy.decide(ownerEnded ? .ownerEnded : .stopEarly, for: causeState(of: session, at: now)) {
        case let .allow(confirm):
            if confirm, !confirmed { return .needsConfirmation }
            end(session, route: route, at: now, in: store)
            return .stopped
        case .wait:
            // Only the in-app stop completes a wait; every other route (Shortcut, Focus off, any
            // later one) may start or resume it, never finish it (§2 "no automatic completion").
            switch harderGate(for: session, kind: "stop", scope: [], confirmed: confirmed && route == .manualStop) {
            case .proceed:
                end(session, route: route == .manualStop ? .harderWait : route, at: now, in: store)
                return .stopped
            case let .waiting(remaining): return .waiting(remaining: remaining)
            case .ready: return .readyToConfirm
            case .unavailable: return .unavailable
            }
        case let .deny(reason):
            return .denied(reason, until: session.endsAt)
        case .deferUntil, .notApplicable:
            return .notApplicable
        }
    }

    /// Cancel a pending Harder stop wait ("I'm good, go back"): spends nothing, keeps the count.
    public func cancelPendingStop() {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard let transition = beginTransition() else { return }
        defer { endTransition(transition) }
        guard let session = env.store()?.loadActiveSession() else { return }
        _ = env.ledger()?.transaction { $0.cancelHarderWait(cause: session.id) }
    }

    /// Unconditional teardown, kept for tests and for paths that already passed the policy. App
    /// entry points use `requestStop(route:confirmed:)`.
    public func stop(route: FlintSessionEndRoute = .manualStop) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard let transition = beginTransition() else { return }
        defer { endTransition(transition) }
        let store = env.store()
        guard let session = store?.loadActiveSession() else {
            env.shield.clear()
            return
        }
        let now = env.clock.now()
        end(session, route: isOver(session) ? .completed : route, at: now, in: store)
    }

    /// Called when a timed session reaches its end (or the app notices it expired): clean up.
    public func finishIfExpired() {
        Self.lock.lock(); defer { Self.lock.unlock() }
        guard let transition = beginTransition() else { return }
        defer { endTransition(transition) }
        let store = env.store()
        let now = env.clock.now()
        guard let session = store?.loadActiveSession(), isOver(session) else { return }
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
        // The session's Harder count ends with it (a new session starts from zero, §4); its
        // committed-operation records stay in the ledger's bounded memory.
        _ = env.ledger()?.transaction { $0.resetHarder(cause: session.id) }
        env.publishStatus()
    }

    // MARK: Policy adapters (ADR-012)

    /// The session as the policy sees it. Sessions saved before the contract keep their legacy
    /// rights until they end (§12).
    private func causeState(of session: FlintActiveSession, at now: Date) -> FlintCauseState {
        let count = (try? env.ledger()?.read().get())?.harder[session.id]?.committedCount ?? 0
        return FlintCauseState(
            causeID: session.id,
            kind: .session,
            tier: session.breakLevel,
            isActive: !isOver(session),
            end: session.endsAt,
            harderCount: count,
            legacyRights: session.contractVersion == nil
        )
    }

    private func snapshot(of session: FlintActiveSession, selection: FamilyActivitySelection?) -> FlintProtectionSnapshot<AnyHashable> {
        let targets = selection.map(env.shield.targets(of:)) ?? FlintSessionTargets()
        return FlintProtectionSnapshot(mode: .blockList(applications: targets.applications,
                                                        categories: targets.categories,
                                                        webDomains: targets.webDomains),
                                       tier: session.breakLevel, end: session.endsAt)
    }

    /// The Focus filter owns sessions it started; older builds marked them only by name.
    public func isOwnedByFocusFilter(_ session: FlintActiveSession) -> Bool {
        if let origin = session.origin {
            return origin == FlintSessionOrigin.focusFilter.rawValue
                && (session.ownerID ?? Self.focusFilterOwnerID) == Self.focusFilterOwnerID
        }
        return session.name == Self.legacyFocusFilterName
    }

    /// The owner id the Focus filter stamps on the sessions it starts (ADR-012 §3).
    public static let focusFilterOwnerID = "focus-filter"

    /// The name older builds gave Focus-filter sessions (their only ownership mark).
    public static let legacyFocusFilterName = "Focus Filter"

    /// What a replacement request is, as a stable scope: a different request — other targets,
    /// tier or duration — needs a new wait (ADR-012 §4). `targets` is the shield's launch-stable
    /// fingerprint of the requested selection.
    static func replacementScope(tier: BreakLevel, duration: TimeInterval, targets: String) -> [String] {
        ["tier:\(tier.rawValue)", "duration:\(Int(duration))", "targets:\(targets)"]
    }

    private enum HarderGate: Equatable {
        case proceed
        case waiting(TimeInterval)
        case ready
        case unavailable
    }

    /// The persisted Harder wait for one weakening operation (ADR-012 §4) in one ledger
    /// transaction: start or resume it, and commit it — counting it once — only when it has
    /// passed and the caller confirmed. A committed operation is remembered by id, so a crash
    /// between the commit and the teardown still proceeds on the next request.
    private func harderGate(for session: FlintActiveSession, kind: String, scope: [String], confirmed: Bool) -> HarderGate {
        guard let ledger = env.ledger() else { return .unavailable }
        let operationID = "\(kind).\(session.id).\(scope.joined(separator: ","))"
        let clock = env.clock
        let result = ledger.transaction { state -> HarderGate in
            let now = FlintLedgerInstant(clock: clock)
            if state.hasCommittedHarderWait(cause: session.id, operationID: operationID) { return .proceed }
            switch state.requestHarderWait(cause: session.id, kind: kind, scope: scope,
                                           operationID: operationID, at: now) {
            case let .waiting(remaining):
                return .waiting(remaining)
            case .matured:
                guard confirmed else { return .ready }
                switch state.commitHarderWait(cause: session.id, operationID: operationID, at: now) {
                case .committed, .alreadyCommitted: return .proceed
                case .notMatured, .nothingPending: return .unavailable
                }
            case .none:
                return .unavailable
            }
        }
        return (try? result.get()) ?? .unavailable
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
        guard let transition = beginTransition() else {
            var busy = FlintSessionReconcileReport(outcome: .running)
            busy.transitionBusy = true
            return busy
        }
        defer { endTransition(transition) }
        let store = env.store()
        let now = env.clock.now()
        guard let session = store?.loadActiveSession() else {
            // A stored record that doesn't decode is not "no session": keep the last-good shield
            // and report it rather than releasing protection on corrupt state (ADR-012 §8).
            if store?.hasActiveSessionRecord() == true {
                FlintSessionHealth.update(in: env.healthDefaults()) {
                    $0.lastArmFailure = .init(sessionName: "Focus session", reason: "the running session's record can't be read", at: now)
                }
                return FlintSessionReconcileReport(outcome: .running, rearmFailed: true, recordUnreadable: true)
            }
            // Nothing else writes the session's store, and every start/stop in this process
            // holds the lock — so with no record, whatever is left on it is stale.
            env.shield.clear()
            store?.clearSessionSelection()
            FlintSessionHealth.update(in: env.healthDefaults()) { $0.unauthorizedSince = nil }
            return FlintSessionReconcileReport(outcome: .noSession)
        }

        let verdict = FlintTimeIntegrity.verdict(for: session, clock: env.clock)
        recordTimeVerdict(verdict, for: session, at: now)

        if isOver(session) {
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

        if let store, session.endsAt != nil {
            let zoneMoved = !FlintSessionAutoClearPlan.isCurrent(for: session, in: env.clock.calendar())
            let missing = !env.registrar.registeredActivityNames().contains(session.monitorName)
            // A same-boot wall-clock change moved the registered wall end away from the logical
            // end: re-register so iOS ends it at the right moment (ADR-012 §9).
            var drifted = false
            if let wallEnd = session.endsAt, let logicalEnd = session.logicalEnd(clock: env.clock) {
                drifted = abs(wallEnd.timeIntervalSince(logicalEnd)) > FlintTimeIntegrity.discontinuityThreshold
            }
            if zoneMoved || missing || drifted {
                if zoneMoved { recordAnomaly(.timeZoneChanged, seconds: 0, session: session, at: now) }
                switch rearm(session, in: store, now: now, reason: .repair) {
                case .rearmed: report.rearmed = true
                case .failed: report.rearmFailed = true
                case .deferred: break
                }
            }
        }

        // Re-read after registration repair and right before writing: the monitor may have
        // ended this session. Never re-apply Safari without a dependable end for a timed cause.
        if let store, let current = store.loadActiveSession(), current.id == session.id,
           let selection = sessionSelection(for: current, in: store) {
            try? env.shield.applyBlockList(selection)
            if current.endsAt == nil || env.registrar.registeredActivityNames().contains(current.monitorName) {
                env.webRestrictions.reconcile(current, clock: env.clock)
            }
        }

        env.shield.setDenyAppRemoval(FlintUninstallGuard.shouldDeny(for: session, clock: env.clock))
        return report
    }

    /// Launch-time reconciliation kept for existing callers; `reconcile()` now does the full job.
    public func reconcileUninstallGuard() {
        reconcile()
    }

    /// `clockChange`: the interval ended early because the wall clock moved ahead — not iOS
    /// misfiring, so it neither waits out `repairInterval` nor spends `maximumRearms`.
    enum RearmReason { case repair, prematureEnd, clockChange }
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
        guard let logicalEnd = session.logicalEnd(clock: env.clock), logicalEnd > now else { return .failed }
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
            // A backward jump (now < last) isn't deferred: the end must move with it now. The
            // re-arm stamps `lastRearmAt = now`, so repairs after it are spaced again.
            if let last = session.lastRearmAt, now.timeIntervalSince(last) < Self.repairInterval, now >= last {
                return .deferred
            }
        case .clockChange:
            break
        }
        let start = FlintSessionAutoClearPlan.wholeSecond(atOrAfter: now)
        // Register the logical end: after a same-boot wall-clock change it differs from the
        // recorded wall end, which then moves with it (ADR-012 §9: remaining time is unchanged).
        let end = FlintSessionAutoClearPlan.wholeSecond(atOrAfter: logicalEnd)
        let reanchored = session.endsAt.map { abs($0.timeIntervalSince(end)) > FlintTimeIntegrity.discontinuityThreshold } ?? false
        let plan = FlintSessionAutoClearPlan.make(start: start, end: max(end, start), calendar: env.clock.calendar(),
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
                                                countsTowardLimit: reason == .prematureEnd,
                                                endsAt: reanchored ? end : nil))
        if session.monitorName != name { env.registrar.stopMonitoring([session.monitorName]) }
        // The running session is armed again: an earlier refusal is no longer current.
        // (Callers publish: the monitor callback, the reconcile, the start that repaired it.)
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
        // Still busy after the longer wait: change nothing. The host's next reconcile finds the
        // registration missing or drifted and repairs it; shields stay on meanwhile.
        guard let transition = beginTransition(timeout: env.monitorLockTimeout) else { return }
        defer { endTransition(transition) }
        // The monitor's callbacks are the app-closed path: always republish what they changed
        // (unchanged content skips the widget reload).
        defer { env.publishStatus() }
        let store = env.store()
        let now = env.clock.now()
        let session = store?.loadActiveSession()

        guard let session, session.monitorName == activity else {
            // No session at its own interval end: clear what is left of a session whose record
            // was lost, as older builds did. A *different* active session means this callback is
            // from a replaced registration: never touch the newer session.
            if event == .intervalDidEnd {
                // An unreadable record is unknown protection, not "no session": keep its shield
                // (ADR-012 §8; R-20). Only a record proven absent — in a store proven readable
                // (not a locked or missing container that reads empty) — leaves a stale shield.
                if session == nil, let store, store.isProtectionStateReadable(), !store.hasActiveSessionRecord() {
                    env.shield.clear()
                }
                env.registrar.stopMonitoring([activity])
            }
            return
        }

        switch event {
        case .intervalDidStart:
            if let store, let selection = sessionSelection(for: session, in: store) {
                try? env.shield.applyBlockList(selection)
                env.webRestrictions.reconcile(session, clock: env.clock)
            }
            env.shield.setDenyAppRemoval(FlintUninstallGuard.shouldDeny(for: session, clock: env.clock))

        case .intervalWillEndWarning, .intervalDidEnd:
            let endsAt = session.endsAt ?? now
            // Logical time decides (ADR-012 §9): a same-boot wall-clock jump can't end it early.
            if session.isLogicallyExpired(clock: env.clock, slack: FlintTimeIntegrity.endSlack) {
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
                // The interval is over, but the session isn't: keep the shield, re-register. A
                // forward wall-clock jump explains it; otherwise iOS ended it early.
                let verdict = FlintTimeIntegrity.verdict(for: session, clock: env.clock)
                if case .wallAhead = verdict {
                    recordTimeVerdict(verdict, for: session, at: now)
                    rearm(session, in: store, now: now, reason: .clockChange)
                } else {
                    recordAnomaly(.prematureEnd, seconds: session.logicalRemaining(clock: env.clock) ?? 0,
                                  session: session, at: now)
                    rearm(session, in: store, now: now, reason: .prematureEnd)
                }
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
        guard let transition = beginTransition() else { return false }
        defer { endTransition(transition) }
        let store = env.store()
        let now = env.clock.now()
        guard let session = store?.loadActiveSession(), session.breakLevel == .hardcore,
              !isOver(session) else {
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

    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets {
        FlintSessionTargets(
            applications: Set(selection.applicationTokens.map(AnyHashable.init)),
            categories: Set(selection.categoryTokens.map(AnyHashable.init)),
            webDomains: Set(selection.webDomainTokens.map(AnyHashable.init))
        )
    }

    func fingerprint(of selection: FamilyActivitySelection) -> String {
        let encoder = JSONEncoder()
        func encoded<T: Encodable>(_ tokens: Set<T>, _ tag: String) -> [String] {
            tokens.map { tag + ((try? encoder.encode($0))?.base64EncodedString() ?? "?") }
        }
        let parts = (encoded(selection.applicationTokens, "a:") + encoded(selection.categoryTokens, "c:")
                     + encoded(selection.webDomainTokens, "w:")).sorted()
        let digest = SHA256.hash(data: Data(parts.joined(separator: "\n").utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
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
