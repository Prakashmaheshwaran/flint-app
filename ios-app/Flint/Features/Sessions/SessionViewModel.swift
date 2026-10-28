import Foundation
import FamilyControls
import FlintCore
import Combine
import UIKit

/// Drives the Today tab: holds the picker selection, duration, break level, and the live
/// countdown for an active session — and the "complete" moment when one runs to its end.
@MainActor
final class SessionViewModel: ObservableObject {
    /// A session that ran to its end, shown once on the Complete screen.
    struct Finished: Equatable {
        let id: String
        let name: String
        let focusedSeconds: TimeInterval
        let todaySeconds: TimeInterval?
        let isPreview: Bool
        let historyDegraded: Bool
        // Compatibility for RootView's existing presentation identity.
        var minutes: Int { Int(max(0, focusedSeconds) / 60) }
    }

    @Published var selection = FamilyActivitySelection()
    @Published var durationMinutes: Int = 45
    @Published var breakLevel: BreakLevel = .harder
    @Published private(set) var active: FlintActiveSession?
    @Published private(set) var remaining: TimeInterval = 0
    @Published private(set) var savedGroups: [FlintAppGroup] = []
    @Published var finished: Finished? {
        didSet {
            if finished == nil, let oldValue {
                UserDefaults.standard.set(oldValue.id, forKey: FocusHistoryModel.shared.acknowledgementKey)
            }
        }
    }
    @Published var errorText: String?

    private let controller = FlintSessionController()
    private var timer: Timer?
    /// The saved group last applied, so a start from an unchanged preset is reported as one.
    private var appliedGroupSelection: FamilyActivitySelection?
    private var completionForeground: AnyCancellable?

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
        refreshCompletion()
        completionForeground = NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in Task { @MainActor in self?.refreshCompletion() } }
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
        appliedGroupSelection = group.selection
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
                name: "Focus",
                source: appliedGroupSelection == selection ? .preset : .manual
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
        // In-app, Harder only stops after its wait (SessionView's BreakWaitView).
        controller.stop(route: active.breakLevel == .harder ? .harderWait : .manualStop)
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
            refreshCompletion()
        }
    }

    /// Reconstructs completion after process death; acknowledgement lives only in host defaults.
    private func refreshCompletion() {
        let history = FocusHistoryModel.shared
        history.refresh { [weak self] summary in
            guard let self, self.active == nil, self.finished == nil, history.presentsCompletions,
                  let session = FocusHistoryModel.latestCompletion(in: summary,
                    acknowledgedID: UserDefaults.standard.string(forKey: history.acknowledgementKey)) else { return }
            self.finished = Finished(id: session.id, name: session.name, focusedSeconds: session.focusedSeconds,
                                     todaySeconds: summary.days.last?.focusedSeconds, isPreview: history.isPreview,
                                     historyDegraded: summary.availability == .degraded)
        }
    }
}
