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

    private let controller = FlintSessionController()

    init() {}

    /// Start the block described by `request` beside — never over — a running session: iOS Flint
    /// runs one session at a time, so a Focus turning on while another session runs starts
    /// nothing (ADR-012 §3, "A Focus toggle is not authority over another session"). Idempotent
    /// when its own block already runs. A refusal is recorded for Settings, never silent.
    @discardableResult
    func activate(_ request: FlintSessionRequest) -> Bool {
        if let active = controller.activeSession, controller.isOwnedByFocusFilter(active) { return true }

        guard let selection = resolveSelection(for: request.appGroupID),
              selectionCount(selection) > 0 else {
            FlintSessionHealth.recordRefusal(action: .start, name: Self.sessionName, reason: "Nothing is selected to block.")
            return false
        }

        do {
            _ = try controller.startBlockNow(
                selection: selection,
                duration: request.duration,   // 0 = while the Focus is on (Easy only)
                breakLevel: request.breakLevel,
                name: Self.sessionName,
                source: .focusFilter,
                origin: .focusFilter,
                ownerID: Self.ownerID,
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
        guard let store = FlintGroupStore() else { return nil }
        if let id = appGroupID, let group = store.loadAppGroups().first(where: { $0.id == id }) {
            return group.selection
        }
        return store.loadSelection()
    }

    private func selectionCount(_ s: FamilyActivitySelection) -> Int {
        s.applicationTokens.count + s.categoryTokens.count + s.webDomainTokens.count
    }
}
#endif
