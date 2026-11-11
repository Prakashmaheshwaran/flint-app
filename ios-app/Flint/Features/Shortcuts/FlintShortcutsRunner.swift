#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import Foundation
import FamilyControls
import FlintCore

extension AppIntent {
    /// Ask the user to confirm a start that needs it (a Hardcore start, or replacing the running
    /// session — ADR-012 §2), with the custom dialog where iOS supports one. Throws if declined.
    func confirmFlintStart(_ dialog: IntentDialog) async throws {
        if #available(iOS 18.0, *) {
            try await requestConfirmation(actionName: .start, dialog: dialog)
        } else {
            try await requestConfirmation()
        }
    }

    /// Start `request`, asking for confirmation first for Hardcore and again if the start would
    /// replace the running session.
    func startConfirmingIfNeeded(_ start: (_ confirmed: Bool) throws -> FlintActiveSession,
                                 hardcore: Bool, minutes: Int, replaying: Bool = false) async throws -> FlintActiveSession {
        if replaying { return try start(true) }
        if hardcore {
            try await confirmFlintStart("Start a Hardcore block for \(minutes) minutes? You can't end it early — it ends on its own, or with your Emergency Pass in Flint.")
        }
        do {
            return try start(hardcore)
        } catch FlintSessionError.needsConfirmation {
            try await confirmFlintStart("Replace the session that's running now with this one?")
            return try start(true)
        }
    }
}

/// User-facing failures the Shortcuts/Siri intents can hit *before* the OS ever applies a shield.
/// `FlintSessionController` raises its own `FlintSessionError` (empty selection, token cap) once a
/// selection is in hand; these cover what happens earlier — nothing picked yet, or nothing running
/// to stop. App Intents surfaces `errorDescription` to the user as spoken/printed text.
enum FlintShortcutsError: Error, LocalizedError {
    case noSelectionAvailable
    case noActiveSession
    case hardcoreLocked
    /// Harder: a wait started (or is still running) before the session can be ended.
    case waitRunning(remaining: TimeInterval)
    /// Harder: the wait has passed; the final confirmation happens in Flint (ADR-012 §2).
    case confirmInFlint
    case stateUnavailable
    /// A Pomodoro chain (2+ rounds) outside the plan bounds (design §2).
    case invalidPomodoroPlan

    var errorDescription: String? {
        switch self {
        case .noSelectionAvailable:
            return "Open Flint and choose the apps or websites to block first — Shortcuts reuses your saved selection."
        case .noActiveSession:
            return "Nothing is being blocked right now."
        case .hardcoreLocked:
            return "This is a Hardcore session — it can't be stopped early. It ends on its own, or use the Emergency Pass in Flint."
        case let .waitRunning(remaining):
            let minutes = max(1, Int((remaining / 60).rounded(.up)))
            return "This is a Harder session, so it didn't end yet: a \(minutes)-minute wait is running. After it, open Flint to end the session."
        case .confirmInFlint:
            return "The wait is over, but a Harder session only ends after you confirm in Flint. Open Flint to end it."
        case .stateUnavailable:
            return "Flint couldn't read its protection state, so nothing was changed. Open Flint to check."
        case .invalidPomodoroPlan:
            return "That Pomodoro can't start: focus must be 5–120 minutes in steps of 5, rests 1–60 minutes, and 2–8 rounds. A long rest needs both its length (5–60 minutes, at least the normal rest) and how often it comes (every 2 rounds up to one fewer than the total). Use 1 round for a single focus session."
        }
    }
}

/// The thin seam between the App Intents (Shortcuts / Siri) and `FlintCore`. The intents stay
/// declarative; all the "resolve a selection, then drive `FlintSessionController`" logic lives here
/// so Start Session, Start Pomodoro, and the Focus Filter (I-FOCUSFILTER, later) share one path.
///
/// Resolution mirrors `FlintSessionRequest`'s contract: a non-nil `appGroupID` blocks that saved
/// group's selection; nil falls back to the user's current/last selection in the App Group.
enum FlintShortcutsRunner {

    struct PreparedStart {
        let request: FlintSessionRequest
        let selection: FamilyActivitySelection
        let source: FlintSessionSource
        let token: FlintSessionStartToken
        let replaying: Bool
        /// The frozen Pomodoro plan, when this start is a whole chain (F01).
        var pomodoro: FlintPomodoroPlan? = nil
    }

    /// The selection a request refers to, or nil if there's nothing saved to block.
    static func resolveSelection(for request: FlintSessionRequest) -> FamilyActivitySelection? {
        let store = FlintGroupStore()
        if let id = request.appGroupID {
            return store?.loadAppGroups().first(where: { $0.id == id })?.selection
        }
        return store?.loadSelection()
    }

    /// Start a block from a portable `FlintSessionRequest`. Throws `.noSelectionAvailable` when the
    /// request resolves to nothing, so the user gets a useful prompt instead of a generic failure.
    static func prepare(_ request: FlintSessionRequest, source: FlintSessionSource = .shortcut,
                        requestID: String? = nil, pomodoro: FlintPomodoroPlan? = nil,
                        controller: FlintSessionController = FlintSessionController(),
                        selectionResolver: ((FlintSessionRequest) -> FamilyActivitySelection?)? = nil) throws -> PreparedStart {
        guard let selection = (selectionResolver ?? resolveSelection)(request) else {
            throw FlintShortcutsError.noSelectionAvailable
        }
        let token = try controller.prepareStartToken(selection: selection, duration: request.duration,
            breakLevel: request.breakLevel, name: request.name, source: source, origin: .shortcut,
            route: source == .pomodoro ? "shortcut.pomodoro" : "shortcut.session", operationID: requestID,
            pomodoro: pomodoro)
        return PreparedStart(request: request, selection: selection, source: source, token: token,
            replaying: controller.acceptedStartResult(token.operationID) != nil, pomodoro: pomodoro)
    }

    @discardableResult
    static func start(_ prepared: PreparedStart, confirmed: Bool = false,
                      controller: FlintSessionController = FlintSessionController()) throws -> FlintActiveSession {
        try controller.startBlockNow(selection: prepared.selection, duration: prepared.request.duration,
            breakLevel: prepared.request.breakLevel, name: prepared.request.name, source: prepared.source,
            origin: .shortcut, requestID: prepared.token.operationID,
            expectedRevision: prepared.token.expectedRevision, confirmed: confirmed,
            pomodoro: prepared.pomodoro)
    }

    @discardableResult
    static func start(_ request: FlintSessionRequest, source: FlintSessionSource = .shortcut,
                      confirmed: Bool = false, requestID: String? = nil,
                      controller: FlintSessionController = FlintSessionController(),
                      selectionResolver: ((FlintSessionRequest) -> FamilyActivitySelection?)? = nil) throws -> FlintActiveSession {
        let prepared = try prepare(request, source: source, requestID: requestID,
            controller: controller, selectionResolver: selectionResolver)
        return try start(prepared, confirmed: confirmed, controller: controller)
    }

    /// Start a whole Pomodoro chain: one parent commitment spanning every round and its planned
    /// rests, with the plan frozen at acceptance (design §1/§2). The parent's duration is the
    /// plan's total, never a single round.
    @discardableResult
    static func startPomodoro(
        plan: FlintPomodoroPlan,
        appGroupID: String?,
        breakLevel: BreakLevel,
        confirmed: Bool = false,
        requestID: String? = nil,
        controller: FlintSessionController = FlintSessionController(),
        selectionResolver: ((FlintSessionRequest) -> FamilyActivitySelection?)? = nil
    ) throws -> FlintActiveSession {
        let request = pomodoroRequest(plan, appGroupID: appGroupID, breakLevel: breakLevel)
        let prepared = try prepare(request, source: .pomodoro, requestID: requestID, pomodoro: plan,
            controller: controller, selectionResolver: selectionResolver)
        return try start(prepared, confirmed: confirmed, controller: controller)
    }

    /// A saved cadence starts its whole chain when it forms a valid plan; a single-round cadence
    /// keeps the legacy one-round behavior and an out-of-bounds chain is refused.
    @discardableResult
    static func startPomodoro(
        _ config: FlintPomodoroConfig,
        appGroupID: String?,
        breakLevel: BreakLevel,
        confirmed: Bool = false,
        requestID: String? = nil,
        controller: FlintSessionController = FlintSessionController(),
        selectionResolver: ((FlintSessionRequest) -> FamilyActivitySelection?)? = nil
    ) throws -> FlintActiveSession {
        guard let plan = FlintPomodoroPlan(config: config) else {
            // Only a single round runs as an ordinary focus session; a chain outside the plan
            // bounds is refused with its limits instead of silently running as one round.
            guard config.rounds <= 1 else { throw FlintShortcutsError.invalidPomodoroPlan }
            let request = pomodoroRoundRequest(config, appGroupID: appGroupID, breakLevel: breakLevel)
            return try start(request, source: .pomodoro, confirmed: confirmed, requestID: requestID,
                controller: controller, selectionResolver: selectionResolver)
        }
        return try startPomodoro(plan: plan, appGroupID: appGroupID, breakLevel: breakLevel,
            confirmed: confirmed, requestID: requestID, controller: controller,
            selectionResolver: selectionResolver)
    }

    /// The portable request a whole chain starts: the parent's duration is the plan's total.
    static func pomodoroRequest(_ plan: FlintPomodoroPlan, appGroupID: String?,
                               breakLevel: BreakLevel) -> FlintSessionRequest {
        FlintSessionRequest(
            appGroupID: appGroupID,
            durationMinutes: Int(plan.totalDuration / 60),
            breakLevel: breakLevel,
            name: "Pomodoro"
        )
    }

    /// The legacy one-round request: a single focus session of `focusMinutes`.
    static func pomodoroRoundRequest(_ config: FlintPomodoroConfig, appGroupID: String?,
                                    breakLevel: BreakLevel) -> FlintSessionRequest {
        FlintSessionRequest(
            appGroupID: appGroupID,
            durationMinutes: max(1, config.focusMinutes),
            breakLevel: breakLevel,
            name: "Pomodoro"
        )
    }

    /// Stop the running session through the same tier rules as the app (ADR-012 §2, PC-07):
    /// Easy stops (running the shortcut is the explicit request); Harder starts or resumes its
    /// persisted wait and never completes from here — the final confirmation is in Flint;
    /// Hardcore is refused.
    static func stopActiveSession(controller: FlintSessionController = FlintSessionController()) throws {
        switch controller.requestStop(route: .shortcutStop, confirmed: true) {
        case .stopped: return
        case let .waiting(remaining): throw FlintShortcutsError.waitRunning(remaining: remaining)
        case .readyToConfirm, .needsConfirmation: throw FlintShortcutsError.confirmInFlint
        case .denied: throw FlintShortcutsError.hardcoreLocked
        case .notApplicable: throw FlintShortcutsError.noActiveSession
        case .unavailable: throw FlintShortcutsError.stateUnavailable
        }
    }
}
#endif
