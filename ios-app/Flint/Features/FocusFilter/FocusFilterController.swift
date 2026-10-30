import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import FlintCore

/// Bridges Flint's iOS Focus Filter (`FlintFocusFilter` / `SetFocusFilterIntent`) to the real
/// blocking engine. The intent's `perform()` is the only caller on device; this type owns the
/// "turn a `FlintSessionRequest` into a running block, then tear it down again" glue so the intent
/// stays a thin shell over `FlintSessionController`.
///
/// One-directional by design (see `FlintFocusFilterConfig`): a Focus turning **on** starts a
/// block; turning it **off** asks to end *the block this filter owns* — identified by its origin,
/// so the filter never disturbs a manual "Block Now" or a hand-started session.
struct FocusFilterController {
    /// The session name this filter gives its blocks (older builds identified them by it).
    static let sessionName = FlintSessionController.legacyFocusFilterName
    /// The Focus filter's owner id on the sessions it starts (ADR-012 §3 ownership).
    static let ownerID = FlintSessionController.focusFilterOwnerID
    static let startRoute = "focus-filter.activation"

    private let controller: FlintSessionController
    private let selectionResolver: ((String?) -> FamilyActivitySelection?)?
    private let prepareRecovery: (String) -> Result<Void, FlintEmergencyPassError>
    private let acknowledgeOff: (String) -> Result<Void, FlintEmergencyPassError>

    init(controller: FlintSessionController = FlintSessionController(),
         selectionResolver: ((String?) -> FamilyActivitySelection?)? = nil,
         prepareRecovery: @escaping (String) -> Result<Void, FlintEmergencyPassError> = {
             FlintEmergencyPassController().prepareForNewSession(focusOwnerID: $0)
         }, acknowledgeOff: @escaping (String) -> Result<Void, FlintEmergencyPassError> = {
             FlintEmergencyPassController().acknowledgeFocusOff(ownerID: $0)
         }) {
        self.controller = controller; self.selectionResolver = selectionResolver
        self.prepareRecovery = prepareRecovery; self.acknowledgeOff = acknowledgeOff
    }

    /// Start the block described by `request` beside — never over — a running session: iOS Flint
    /// runs one session at a time, so a Focus turning on while another session runs starts
    /// nothing (ADR-012 §3, "A Focus toggle is not authority over another session"). Idempotent
    /// when its own block already runs. A refusal is recorded for Settings, never silent.
    @discardableResult
    func activate(_ request: FlintSessionRequest) -> Bool {
        controller.withTransition { activateLocked(request) } ?? false
    }

    private func activateLocked(_ request: FlintSessionRequest) -> Bool {
        // Check before the idempotent-owned-session return: a committed pass may still need
        // to project its termination, and a still-on filter must not resurrect that block.
        if case let .failure(error) = prepareRecovery(Self.ownerID) {
            FlintSessionHealth.recordRefusal(action: .start, name: Self.sessionName, reason: error.localizedDescription)
            return false
        }
        guard let selection = resolveSelection(for: request.appGroupID) else {
            FlintSessionHealth.recordRefusal(action: .start, name: Self.sessionName, reason: "Nothing is selected to block.")
            return false
        }

        do {
            let token = try controller.prepareStartToken(selection: selection, duration: request.duration,
                breakLevel: request.breakLevel, name: Self.sessionName, source: .focusFilter,
                origin: .focusFilter, ownerID: Self.ownerID, replacing: false,
                route: Self.startRoute, untilDeactivation: true)
            _ = try controller.startBlockNow(
                selection: selection,
                duration: request.duration,   // 0 = while the Focus is on (Easy only)
                breakLevel: request.breakLevel,
                name: Self.sessionName,
                source: .focusFilter,
                origin: .focusFilter,
                ownerID: Self.ownerID,
                requestID: token.operationID,
                expectedRevision: token.expectedRevision,
                replacing: false,
                // The tier was chosen — with its disclosure — in the Focus filter's settings;
                // that choice is the confirmation of its rights (ADR-012 §2).
                confirmed: true
            )
            return true
        } catch {
            FlintSessionHealth.recordRefusal(
                action: .start,
                name: Self.sessionName,
                reason: (error as? LocalizedError)?.errorDescription ?? String(describing: error)
            )
            return false
        }
    }

    /// The Focus turned off: an owner-ended stop request for the block this filter owns, under
    /// the block's tier (ADR-012 §1, PC-08). Easy follows the Focus off; Harder starts its
    /// persisted wait (finish ending it in Flint after the wait); a finite Hardcore block keeps
    /// running until its end. Blocks from an older build keep their old Focus-off exit (§12).
    func deactivate() {
        _ = controller.withTransition {
            guard case .success = acknowledgeOff(Self.ownerID) else {
                FlintSessionHealth.recordRefusal(action: .stop, name: Self.sessionName,
                    reason: "Focus turned off, but Flint couldn't save its recovery state. Open Flint to check.")
                return
            }
            controller.deactivateStartRoute(Self.startRoute)
            deactivateLocked()
        }
    }

    private func deactivateLocked() {
        let outcome = controller.requestStop(route: .focusFilterOff, confirmed: true)
        let reason: String
        switch outcome {
        case .stopped, .notApplicable:
            return
        case let .waiting(remaining):
            reason = "Focus turned off, but this is a Harder block: a \(Self.minutes(remaining)) wait started. Finish ending it in Flint."
        case .readyToConfirm, .needsConfirmation:
            reason = "Focus turned off. The wait is over — open Flint to end this Harder block."
        case let .denied(_, until):
            reason = "Focus turned off, but this Hardcore block keeps running\(until.map { " until \($0.formatted(date: .omitted, time: .shortened))" } ?? "")."
        case .unavailable:
            reason = "Focus turned off, but Flint couldn't read its protection state, so nothing changed."
        }
        FlintSessionHealth.recordRefusal(action: .stop, name: Self.sessionName, reason: reason)
    }

    private static func minutes(_ seconds: TimeInterval) -> String {
        let minutes = max(1, Int((seconds / 60).rounded(.up)))
        return minutes == 1 ? "1-minute" : "\(minutes)-minute"
    }

    // MARK: Selection resolution

    /// Saved preset by id → its selection; otherwise the user's last Block-Now selection.
    private func resolveSelection(for appGroupID: String?) -> FamilyActivitySelection? {
        if let selectionResolver { return selectionResolver(appGroupID) }
        guard let store = FlintGroupStore() else { return nil }
        if let id = appGroupID, let group = store.loadAppGroups().first(where: { $0.id == id }) {
            return group.selection
        }
        return store.loadSelection()
    }

}
#endif
