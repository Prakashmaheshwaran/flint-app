import Combine
import Foundation

/// UI access to Flint only; this does not change OS Screen Time enforcement.
/// One instance is shared by the scene's gate and Settings. Inactive scenes are obscured;
/// only actual backgrounding re-locks and invalidates pending PIN-change authentication.
@MainActor
public final class FlintAppLock: ObservableObject {
    public enum Phase: Sendable { case active, inactive, background }

    public struct State: Equatable {
        public fileprivate(set) var pinIsSet: Bool
        public fileprivate(set) var isLocked: Bool
        public fileprivate(set) var phase: Phase = .inactive

        public var isProtected: Bool { pinIsSet && (isLocked || phase != .active) }
    }

    @Published public private(set) var state: State
    public var pinIsSet: Bool { state.pinIsSet }
    public var isLocked: Bool { state.isLocked }

    private struct Record: Equatable {
        let hash: String
        let salt: String
    }

    private let store: FlintGroupStore?
    private var knownRecord: Record?
    private var changeAuthorization: Record?

    public init(store: FlintGroupStore? = FlintGroupStore()) {
        self.store = store
        let record = store?.pinRecord().map { Record(hash: $0.hash, salt: $0.salt) }
        knownRecord = record
        state = State(pinIsSet: record != nil, isLocked: record != nil)
    }

    public func transition(to phase: Phase) {
        refresh()
        var next = state
        next.phase = phase
        if phase == .background {
            next.isLocked = next.pinIsSet
            changeAuthorization = nil
        }
        state = next
    }

    /// Applies a lifecycle report after cross-checking it against the scene's live
    /// activation state. UIKit and SwiftUI delivery can coalesce or lag, so a
    /// `.background` report may arrive after the scene has already reactivated.
    /// Adopting it would set `isLocked` and leave the gate in `.background`, where it
    /// renders only the ground and `unlock` refuses — a blank, non-interactive screen
    /// on a foregrounded app. A genuine background always reports the scene as
    /// `.background`, so only a demonstrably-active scene rejects the report.
    public func transition(to phase: Phase, ifSceneIs observed: Phase) {
        guard phase == .background, observed == .active else {
            transition(to: phase)
            return
        }
        // Reconcile to the live foreground phase; an earlier stale report may already
        // have stranded the gate. Lock state is preserved — the app is still the same
        // protected instance — but it becomes unlockable again. Authorization is not
        // expired because no backgrounding actually occurred.
        refresh()
        var next = state
        next.phase = .active
        state = next
    }

    /// A changed/removed record cannot leave stale authentication or a stale gate behind.
    public func refresh() {
        let record = currentRecord
        guard record != knownRecord else { return }
        knownRecord = record
        changeAuthorization = nil
        state = State(pinIsSet: record != nil, isLocked: record != nil, phase: state.phase)
    }

    public func unlock(with pin: String) -> Bool {
        refresh()
        guard state.phase == .active, FlintPIN.verify(pin, store) else { return false }
        state.isLocked = false
        return true
    }

    public func authorizePINChange(currentPIN: String) -> Bool {
        refresh()
        changeAuthorization = nil
        guard canEdit, FlintPIN.verify(currentPIN, store) else { return false }
        changeAuthorization = knownRecord
        return true
    }

    public func cancelPINChange() {
        changeAuthorization = nil
    }

    /// New-PIN format/confirmation belongs to the setup form and FlintPIN's storage policy.
    /// Existing records may only be replaced using current-PIN authentication in this visit.
    @discardableResult
    public func savePIN(_ pin: String) -> Bool {
        refresh()
        guard canEdit, !pin.isEmpty,
              !pinIsSet || changeAuthorization == knownRecord else { return false }
        let previous = knownRecord
        FlintPIN.set(pin, store)
        guard let saved = currentRecord, saved != previous,
              FlintPIN.verify(pin, store) else { return false }
        knownRecord = saved
        changeAuthorization = nil
        state = State(pinIsSet: true, isLocked: false, phase: state.phase)
        return true
    }

    public func removePIN(currentPIN: String) -> Bool {
        refresh()
        guard canEdit, FlintPIN.verify(currentPIN, store) else { return false }
        FlintPIN.clear(store)
        refresh()
        return !pinIsSet
    }

    private var canEdit: Bool { state.phase == .active && !state.isLocked }
    private var currentRecord: Record? {
        store?.pinRecord().map { Record(hash: $0.hash, salt: $0.salt) }
    }
}
