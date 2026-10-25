import Foundation
import FamilyControls
import FlintCore

/// Drives the Today tab: holds the picker selection, duration, break level, and the live
/// countdown for an active session — and the "complete" moment when one runs to its end.
@MainActor
final class SessionViewModel: ObservableObject {
    /// A session that ran to its end, shown once on the Complete screen.
    struct Finished: Equatable {
        let name: String
        let minutes: Int
    }

    @Published var selection = FamilyActivitySelection()
    @Published var durationMinutes: Int = 45
    @Published var breakLevel: BreakLevel = .harder
    @Published private(set) var active: FlintActiveSession?
    @Published private(set) var remaining: TimeInterval = 0
    @Published private(set) var savedGroups: [FlintAppGroup] = []
    @Published var finished: Finished?
    @Published var errorText: String?

    private let controller = FlintSessionController()
    private var timer: Timer?

    init() {
        if let saved = FlintGroupStore()?.loadSelection() { selection = saved }
        savedGroups = FlintGroupStore()?.loadAppGroups() ?? []
        active = controller.activeSession
        #if DEBUG
        // `-FlintUIPreviewSession <easy|harder|hardcore>` shows a running session in the Simulator,
        // which can't grant Screen Time. Nothing is shielded; the UI only.
        let args = ProcessInfo.processInfo.arguments
        // UI-only failure fixture; does not start or authorize a session.
        if let i = args.firstIndex(of: "-FlintUIPreviewSessionError"), args.indices.contains(i + 1) {
            errorText = args[i + 1]
        }
        if let i = args.firstIndex(of: "-FlintUIPreviewSession"), args.indices.contains(i + 1),
           let level = BreakLevel(rawValue: args[i + 1]) {
            active = FlintActiveSession(name: "Deep work", startedAt: Date().addingTimeInterval(-13 * 60),
                                        endsAt: Date().addingTimeInterval(32 * 60 + 18), breakLevel: level,
                                        monitorName: "preview")
        }
        #endif
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

    /// "7 apps · 2 sites" — what the selection blocks, in the design's words.
    var selectionSummary: String {
        var parts: [String] = []
        let apps = selection.applicationTokens.count
        let categories = selection.categoryTokens.count
        let sites = selection.webDomainTokens.count
        if apps > 0 { parts.append("\(apps) \(apps == 1 ? "app" : "apps")") }
        if categories > 0 { parts.append("\(categories) \(categories == 1 ? "category" : "categories")") }
        if sites > 0 { parts.append("\(sites) \(sites == 1 ? "site" : "sites")") }
        return parts.joined(separator: " · ")
    }

    var canStopActive: Bool {
        guard let active else { return false }
        return controller.canStop(active)
    }

    /// 0…1 through the active session; drives the ember along its arc.
    var progress: Double {
        guard let active, let endsAt = active.endsAt else { return 0 }
        let total = endsAt.timeIntervalSince(active.startedAt)
        guard total > 0 else { return 1 }
        return min(1, max(0, 1 - remaining / total))
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
            if let endsAt = active.endsAt {
                let minutes = Int((endsAt.timeIntervalSince(active.startedAt) / 60).rounded())
                finished = Finished(name: active.name, minutes: minutes)
            }
            self.active = nil
            remaining = 0
            timer?.invalidate()
        }
    }
}
