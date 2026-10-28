import Foundation
import Combine
import FlintCore

@MainActor
final class FocusHistoryModel: ObservableObject {
    static let shared: FocusHistoryModel = {
        #if DEBUG
        if let preview = FocusHistoryPreview.make() { return preview }
        #endif
        return FocusHistoryModel()
    }()

    @Published private(set) var summary: FlintHistorySummary?
    let isPreview: Bool
    let presentsCompletions: Bool
    let acknowledgementKey: String
    private let sink: FlintHistorySink
    private let clock: () -> Date
    private let calendar: Calendar
    private let activeSession: () -> FlintActiveSession?

    init(sink: FlintHistorySink = .shared, clock: @escaping () -> Date = Date.init,
         calendar: Calendar = .current,
         activeSession: @escaping () -> FlintActiveSession? = { FlintGroupStore()?.loadActiveSession() },
         isPreview: Bool = false, presentsCompletions: Bool = true,
         acknowledgementKey: String = "flint.history.lastAcknowledgedCompletion") {
        self.sink = sink; self.clock = clock; self.calendar = calendar; self.activeSession = activeSession
        self.isPreview = isPreview; self.presentsCompletions = presentsCompletions
        self.acknowledgementKey = acknowledgementKey
    }

    func refresh(completion: ((FlintHistorySummary) -> Void)? = nil) {
        let now = clock(), calendar = calendar, active = activeSession()
        sink.perform { [weak self] store in
            let snapshot = FlintHistoryReconciler.reconcile(store: store, activeSession: active, now: now, calendar: calendar)
            let summary = FlintHistoryAggregator.summarize(snapshot, activeSessionID: active?.id, now: now, calendar: calendar)
            DispatchQueue.main.async {
                guard let self else { return }
                self.summary = summary
                completion?(summary)
            }
        }
    }

    func clear(completion: @escaping (Bool, Bool) -> Void) {
        let now = clock(), active = activeSession()
        let keep = active.flatMap { session in session.endsAt.map { $0 <= now } == true ? nil : session.id }
        sink.perform { [weak self] store in
            let success = store.clear(keepingRunningSession: keep)
            DispatchQueue.main.async { self?.refresh(); completion(success, success && keep != nil) }
        }
    }

    func completionToPresent(in summary: FlintHistorySummary, defaults: UserDefaults = .standard) -> FlintHistorySessionSummary? {
        guard presentsCompletions, let latest = Self.latestCompletion(in: summary,
            acknowledgedID: defaults.string(forKey: acknowledgementKey)) else { return nil }
        let now = clock()
        guard calendar.isDate(latest.endedAt, inSameDayAs: now) ||
                (latest.endedAt <= now && now.timeIntervalSince(latest.endedAt) <= 12 * 3600) else {
            defaults.set(latest.id, forKey: acknowledgementKey)
            return nil
        }
        return latest
    }

    static func latestCompletion(in summary: FlintHistorySummary, acknowledgedID: String?) -> FlintHistorySessionSummary? {
        guard summary.availability != .unavailable,
              let latest = summary.sessions.filter({ $0.outcome == .completed })
                .max(by: { $0.endedAt == $1.endedAt ? $0.id < $1.id : $0.endedAt < $1.endedAt }),
              latest.id != acknowledgedID else { return nil }
        return latest
    }
}

enum FocusHistoryText {
    static let definition = "Counts focus sessions you start. Schedules and limits aren’t counted."
    static let degraded = "Some history couldn’t be read."

    static func unconfirmed(_ days: [FlintHistoryDay]) -> String? {
        let count = days.reduce(0) { $0 + $1.unconfirmed }
        guard count > 0 else { return nil }
        if count == 1 { return NSLocalizedString("1 session couldn’t be confirmed.", comment: "Unconfirmed focus history") }
        return String.localizedStringWithFormat(NSLocalizedString("%ld sessions couldn’t be confirmed.",
            comment: "Unconfirmed focus history count"), count)
    }

    static func duration(_ seconds: TimeInterval?) -> String {
        guard let seconds, seconds.isFinite else { return "No data" }
        if seconds > 0 && seconds < 60 { return "<1m" }
        let minutes = Int(max(0, seconds) / 60)
        let hours = minutes / 60, rest = minutes % 60
        return hours == 0 ? "\(minutes)m" : rest == 0 ? "\(hours)h" : "\(hours)h \(rest)m"
    }

    static func today(_ summary: FlintHistorySummary?) -> String {
        guard let summary else { return "Loading focus history…" }
        if summary.availability == .unavailable { return "Focus history unavailable" }
        if summary.availability == .empty { return "No focus sessions yet" }
        return duration(summary.days.last?.focusedSeconds)
    }

    static func total(_ days: [FlintHistoryDay]) -> TimeInterval? {
        let values = days.compactMap(\.focusedSeconds)
        return values.isEmpty ? nil : values.reduce(0, +)
    }

    static func weekLabel(_ days: [FlintHistoryDay]) -> String {
        guard let first = days.first(where: { $0.focusedSeconds != nil }) else { return "No data for this range" }
        if days.first?.start == first.start { return "Focused in the last 7 days" }
        return "Focused since \(first.start.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()))"
    }

    static func clearFeedback(success: Bool, keptRunningSession: Bool) -> String {
        guard success else { return "Focus history couldn’t be cleared. Try again." }
        return "Focus history cleared." + (keptRunningSession ? " Your running session was kept." : "")
    }
}

#if DEBUG
/// Simulator fixtures use a separate directory and the production append API. They never
/// grant Screen Time, affect enforcement, or read/write the real App Group history log.
@MainActor
private enum FocusHistoryPreview {
    static func make() -> FocusHistoryModel? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-FlintUIPreviewHistory") else { return nil }
        let mode = args.indices.contains(i + 1) ? args[i + 1] : "sample"
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        let now = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: today)!
        let first = calendar.date(byAdding: .day, value: -4, to: today)!
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("FlintHistoryPreview-\(UUID().uuidString)")
        let initial = FlintHistoryStore(directory: mode == "unavailable" ? nil : directory, clock: { first }, calendar: calendar)
        _ = initial.read()
        var active: FlintActiveSession?
        if mode != "empty" && mode != "unavailable" {
            for (offset, minutes) in [(-4, 30), (-3, 0), (-2, 50), (-1, 60), (0, 45)] where minutes > 0 {
                let day = calendar.date(byAdding: .day, value: offset, to: today)!
                let start = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: day)!
                let end = start.addingTimeInterval(Double(minutes * 60))
                let id = "preview-\(offset)"
                initial.append(.init(type: .started, session: id, at: start, plannedEnd: end,
                                     tier: "easy", source: "manual", name: "Preview · Deep study"))
                initial.append(.init(type: offset == -4 ? .endedEarly : .completed, session: id, at: end,
                                     route: offset == -4 ? "manual_stop" : nil))
            }
            if mode == "running" {
                active = FlintActiveSession(id: "preview-running", name: "Preview · Reading",
                    startedAt: now.addingTimeInterval(-600), endsAt: now.addingTimeInterval(1200),
                    breakLevel: .easy, monitorName: "preview-only")
                initial.append(FlintHistoryEvent(.started(active!, source: .manual, at: active!.startedAt)))
            }
        }
        let store = FlintHistoryStore(directory: mode == "unavailable" ? nil : directory, clock: { now }, calendar: calendar)
        return FocusHistoryModel(sink: FlintHistorySink(store: store), clock: { now }, calendar: calendar,
            activeSession: { active }, isPreview: true, presentsCompletions: mode == "complete",
            acknowledgementKey: "flint.history.previewAcknowledged.\(UUID().uuidString)")
    }
}
#endif
