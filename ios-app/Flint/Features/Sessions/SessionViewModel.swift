import Foundation
import FamilyControls
import FlintCore

/// Drives the Block Now screen: holds the picker selection, duration, break level, and the live
/// countdown for an active session.
@MainActor
final class SessionViewModel: ObservableObject {
    @Published var selection = FamilyActivitySelection()
    @Published var durationMinutes: Int = 25
    @Published var breakLevel: BreakLevel = .easy
    @Published private(set) var active: FlintActiveSession?
    @Published private(set) var remaining: TimeInterval = 0
    @Published private(set) var savedGroups: [FlintAppGroup] = []
    @Published var errorText: String?

    private let controller = FlintSessionController()
    private var timer: Timer?

    init() {
        if let saved = FlintGroupStore()?.loadSelection() { selection = saved }
        savedGroups = FlintGroupStore()?.loadAppGroups() ?? []
        active = controller.activeSession
        startTicking()
    }

    // MARK: Saved app groups (presets)

    func saveSelectionAsGroup(name: String) {
        guard selectionCount > 0, let store = FlintGroupStore() else { return }
        var groups = store.loadAppGroups()
        groups.append(FlintAppGroup(name: name.isEmpty ? "Group" : name, selection: selection))
        store.saveAppGroups(groups)
        savedGroups = groups
    }

    func applyGroup(_ group: FlintAppGroup) {
        selection = group.selection
        persistSelection()
    }

    func deleteGroup(_ id: String) {
        guard let store = FlintGroupStore() else { return }
        var groups = store.loadAppGroups()
        groups.removeAll { $0.id == id }
        store.saveAppGroups(groups)
        savedGroups = groups
    }

    var selectionCount: Int {
        selection.applicationTokens.count
            + selection.categoryTokens.count
            + selection.webDomainTokens.count
    }

    var canStopActive: Bool {
        guard let active else { return false }
        return controller.canStop(active)
    }

    func persistSelection() {
        FlintGroupStore()?.saveSelection(selection)
    }

    func start() {
        errorText = nil
        do {
            active = try controller.startBlockNow(
                selection: selection,
                duration: TimeInterval(durationMinutes * 60),
                breakLevel: breakLevel,
                name: "Focus"
            )
            startTicking()
        } catch {
            errorText = (error as? LocalizedError)?.errorDescription ?? "\(error)"
        }
    }

    func stop() {
        guard let active else { return }
        guard controller.canStop(active) else {
            errorText = "Hardcore session — it can't be stopped until it ends."
            return
        }
        controller.stop()
        self.active = nil
        remaining = 0
        timer?.invalidate()
    }

    var emergencyPassAvailable: Bool { controller.emergencyPassAvailable() }

    func useEmergencyPass() {
        errorText = nil
        if controller.useEmergencyPass() {
            active = nil
            remaining = 0
            timer?.invalidate()
        } else {
            errorText = "Emergency Pass isn't available yet — one per week."
        }
    }

    private func startTicking() {
        timer?.invalidate()
        guard let active, active.endsAt != nil else { remaining = 0; return }
        tick()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        guard let active else { remaining = 0; return }
        remaining = active.remaining
        if active.isExpired {
            // On a real device the monitor clears the shield; mirror that here for the UI and as
            // a fallback (the Simulator doesn't run the monitor extension).
            controller.finishIfExpired()
            self.active = nil
            remaining = 0
            timer?.invalidate()
        }
    }
}
