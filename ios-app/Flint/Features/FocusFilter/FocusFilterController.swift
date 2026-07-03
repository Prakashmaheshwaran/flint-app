import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import FlintCore

/// Bridges Flint's iOS Focus Filter (`FlintFocusFilter` / `SetFocusFilterIntent`) to the real
/// blocking engine. The intent's `perform()` is the only caller on device; this type owns the
/// "turn a `FlintSessionRequest` into a running block, then tear it down again" glue so the intent
/// stays a thin shell over `FlintSessionController`.
///
/// One-directional by design (see `FlintFocusFilterConfig`): a Focus turning **on** starts an
/// open-ended block; turning it **off** stops *the block this filter started* — identified by
/// `sessionName` so the filter never disturbs a manual "Block Now" or a hand-started session.
struct FocusFilterController {
    /// Tags the active session as Focus-Filter-owned so `deactivate()` only stops blocks this
    /// controller started. Matches the name on `FlintFocusFilterConfig.sessionRequest`.
    static let sessionName = "Focus Filter"

    private let controller = FlintSessionController()

    init() {}

    /// Start (or keep) the block described by `request`. Idempotent: if our Focus-Filter session is
    /// already running, it's left alone (re-applying would reset the start time). Resolves
    /// `appGroupID` to a saved preset's selection, falling back to the last-used selection, and
    /// no-ops — rather than throwing — when there's nothing to block (an empty preset shouldn't make
    /// turning a Focus on fail).
    @discardableResult
    func activate(_ request: FlintSessionRequest) -> Bool {
        if let active = controller.activeSession, active.name == Self.sessionName { return true }

        guard let selection = resolveSelection(for: request.appGroupID),
              selectionCount(selection) > 0 else { return false }

        do {
            _ = try controller.startBlockNow(
                selection: selection,
                duration: request.duration,   // 0 = open-ended; the Focus owns the lifetime
                breakLevel: request.breakLevel,
                name: Self.sessionName
            )
            return true
        } catch {
            return false
        }
    }

    /// Stop the Focus-Filter block when the Focus ends. Only touches a session this controller
    /// started. Note this stops *even a Hardcore* Focus-Filter block: turning the Focus off is the
    /// block's legitimate end (the Focus is its timer). Hardcore still blocks the in-app Stop button
    /// via `FlintSessionController.canStop` — only the system Focus-off can end it here.
    func deactivate() {
        guard let active = controller.activeSession, active.name == Self.sessionName else { return }
        controller.stop()
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
