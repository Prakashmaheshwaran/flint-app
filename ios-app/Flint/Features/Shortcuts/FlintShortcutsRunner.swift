#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import Foundation
import FamilyControls
import FlintCore

/// User-facing failures the Shortcuts/Siri intents can hit *before* the OS ever applies a shield.
/// `FlintSessionController` raises its own `FlintSessionError` (empty selection, token cap) once a
/// selection is in hand; these cover what happens earlier — nothing picked yet, or nothing running
/// to stop. App Intents surfaces `errorDescription` to the user as spoken/printed text.
enum FlintShortcutsError: Error, LocalizedError {
    case noSelectionAvailable
    case noActiveSession
    case hardcoreLocked

    var errorDescription: String? {
        switch self {
        case .noSelectionAvailable:
            return "Open Flint and choose the apps or websites to block first — Shortcuts reuses your saved selection."
        case .noActiveSession:
            return "Nothing is being blocked right now."
        case .hardcoreLocked:
            return "This is a Hardcore session — it can't be stopped until it ends."
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
    @discardableResult
    static func start(_ request: FlintSessionRequest) throws -> FlintActiveSession {
        guard let selection = resolveSelection(for: request) else {
            throw FlintShortcutsError.noSelectionAvailable
        }
        return try FlintSessionController().startBlockNow(
            selection: selection,
            duration: request.duration,
            breakLevel: request.breakLevel,
            name: request.name
        )
    }

    /// Start a Pomodoro by kicking off its **first focus round** as a timed block and persisting the
    /// chosen cadence so the app reflects "a Pomodoro is running." The OS schedule clears the shield
    /// when the focus round ends. Honest scope note: chaining the remaining rounds (break → next
    /// focus, automatically) is on-device app/monitor work and is intentionally *not* driven from
    /// this background intent in v1 — see `Shortcuts/README.md`.
    @discardableResult
    static func startPomodoro(
        _ config: FlintPomodoroConfig,
        appGroupID: String?,
        breakLevel: BreakLevel
    ) throws -> FlintActiveSession {
        config.save()
        let request = FlintSessionRequest(
            appGroupID: appGroupID,
            durationMinutes: max(1, config.focusMinutes),
            breakLevel: breakLevel,
            name: "Pomodoro"
        )
        return try start(request)
    }

    /// Stop the running session, honoring Hardcore (which can't be ended early until it expires).
    static func stopActiveSession() throws {
        let controller = FlintSessionController()
        guard let active = controller.activeSession else { throw FlintShortcutsError.noActiveSession }
        guard controller.canStop(active) else { throw FlintShortcutsError.hardcoreLocked }
        controller.stop()
    }
}
#endif
