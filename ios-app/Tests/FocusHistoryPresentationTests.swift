import XCTest
import FlintCore
@testable import Flint

@MainActor
final class FocusHistoryPresentationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    func testDurationPreservesSubminuteAndAbsentVersusZero() {
        XCTAssertEqual(FocusHistoryText.duration(nil), "No data")
        XCTAssertEqual(FocusHistoryText.duration(0), "0m")
        XCTAssertEqual(FocusHistoryText.duration(59), "<1m")
        XCTAssertEqual(FocusHistoryText.duration(3600), "1h")
        XCTAssertEqual(FocusHistoryText.duration(3660), "1h 1m")
    }
    func testTodayEmptyUnavailableAndDegradedStates() {
        XCTAssertEqual(FocusHistoryText.today(nil), "Loading focus history…")
        XCTAssertEqual(FocusHistoryText.today(summary([], state: .empty)), "No focus sessions yet")
        XCTAssertEqual(FocusHistoryText.today(summary([], state: .unavailable)), "Focus history unavailable")
        XCTAssertEqual(FocusHistoryText.today(summary([], state: .degraded)), "0m")
    }
    func testCompletionIsRecoveredAndAcknowledgedOnlyOnce() {
        let events = session("old", end: now.addingTimeInterval(-3600)) + session("latest", end: now)
        let result = summary(events)
        XCTAssertEqual(FocusHistoryModel.latestCompletion(in: result, acknowledgedID: nil)?.id, "latest")
        XCTAssertEqual(FocusHistoryModel.latestCompletion(in: result, acknowledgedID: "old")?.id, "latest")
        XCTAssertNil(FocusHistoryModel.latestCompletion(in: result, acknowledgedID: "latest"))
        XCTAssertNil(FocusHistoryModel.latestCompletion(in: summary(events, state: .unavailable), acknowledgedID: nil))
    }
    func testEarlyEndsDoNotPresentCompletion() {
        let events = [FlintHistoryEvent(type: .started, session: "a", at: now.addingTimeInterval(-1800),
                                       plannedEnd: now, tier: "easy", source: "manual", name: "Read"),
                      FlintHistoryEvent(type: .endedEarly, session: "a", at: now.addingTimeInterval(-900), route: "manual_stop")]
        XCTAssertNil(FocusHistoryModel.latestCompletion(in: summary(events), acknowledgedID: nil))
    }
    func testR17UnconfirmedCopyAndNoCompletionPresentation() {
        let events = [FlintHistoryEvent(type: .started, session: "a", at: now.addingTimeInterval(-3600),
            plannedEnd: now.addingTimeInterval(-60), tier: "easy", source: "manual", name: "Read")]
        let result = summary(events)
        XCTAssertEqual(FocusHistoryText.today(result), "0m")
        XCTAssertEqual(FocusHistoryText.unconfirmed(result.days), "1 session couldn’t be confirmed.")
        XCTAssertEqual(FocusHistoryText.unconfirmed([.init(start: now, focusedSeconds: 0, unconfirmed: 2)]), "2 sessions couldn’t be confirmed.")
        XCTAssertNil(FocusHistoryText.unconfirmed([]))
        XCTAssertNil(FocusHistoryModel.latestCompletion(in: result, acknowledgedID: nil))
    }
    func testStaleCompletionIsSilentlyAcknowledgedAndStaysDismissedAfterRestart() {
        let suite = "FocusHistoryPresentation.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let clock = calendar.startOfDay(for: now).addingTimeInterval(8 * 3600)
        let old = summary(session("old", end: clock.addingTimeInterval(-13 * 3600)))
        func model() -> FocusHistoryModel {
            FocusHistoryModel(sink: .init(store: .init(directory: nil)), clock: { clock }, calendar: calendar,
                              acknowledgementKey: "acknowledged")
        }
        XCTAssertNil(model().completionToPresent(in: old, defaults: defaults))
        XCTAssertEqual(defaults.string(forKey: "acknowledged"), "old")
        XCTAssertNil(model().completionToPresent(in: old, defaults: defaults))
        let recent = summary(session("recent", end: clock.addingTimeInterval(-11 * 3600)))
        XCTAssertEqual(model().completionToPresent(in: recent, defaults: defaults)?.id, "recent")
        let todayClock = calendar.startOfDay(for: clock).addingTimeInterval(23 * 3600)
        let today = summary(session("today", end: todayClock.addingTimeInterval(-20 * 3600)))
        let todayModel = FocusHistoryModel(sink: .init(store: .init(directory: nil)), clock: { todayClock }, calendar: calendar)
        XCTAssertEqual(todayModel.completionToPresent(in: today, defaults: defaults)?.id, "today")
    }
    func testPartialWeekCaptionAndAbsentTotalDoNotClaimSevenObservedDays() {
        let partial = summary([])
        XCTAssertTrue(FocusHistoryText.weekLabel(partial.days).hasPrefix("Focused since "))
        XCTAssertNil(FocusHistoryText.total(summary([], state: .unavailable).days))
        XCTAssertEqual(FocusHistoryText.total(partial.days), 0)
        let full = FlintHistoryAggregator.summarize(.init(events: [], historyStart: now.addingTimeInterval(-8 * 86400), availability: .empty),
            activeSessionID: nil, now: now)
        XCTAssertEqual(FocusHistoryText.weekLabel(full.days), "Focused in the last 7 days")
    }
    func testClearFeedbackOnlyClaimsKeptSessionWhenThereWasOne() {
        XCTAssertEqual(FocusHistoryText.clearFeedback(success: true, keptRunningSession: false), "Focus history cleared.")
        XCTAssertEqual(FocusHistoryText.clearFeedback(success: true, keptRunningSession: true), "Focus history cleared. Your running session was kept.")
        XCTAssertFalse(FocusHistoryText.clearFeedback(success: false, keptRunningSession: true).contains("was kept"))
    }
    func testClearKeepsRunningSessionAfterSameBootForwardWallJump() async throws {
        try await assertClearUsesLogicalExpiry(wallOffset: 3600, elapsed: 600, keepsRunning: true)
    }
    func testClearDropsLogicallyExpiredSessionDespiteFutureWallDeadline() async throws {
        try await assertClearUsesLogicalExpiry(wallOffset: 600, elapsed: 1800, keepsRunning: false)
    }
    private func assertClearUsesLogicalExpiry(wallOffset: TimeInterval, elapsed: TimeInterval,
                                             keepsRunning: Bool) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let wall = now.addingTimeInterval(wallOffset)
        let sessionClock = FlintClock(now: { wall }, monotonic: { 100 + elapsed },
                                      bootSessionID: { "same-boot" }, calendar: { .current })
        let active = FlintActiveSession(id: "running", name: "Focus", startedAt: now,
            endsAt: now.addingTimeInterval(1800), breakLevel: .easy, monitorName: "test",
            monotonicStart: 100, bootSessionID: "same-boot", plannedDuration: 1800)
        let store = FlintHistoryStore(directory: directory, clock: { wall })
        XCTAssertTrue(store.append(.init(.started(active, source: .manual, at: now))))
        XCTAssertTrue(store.append(.init(type: .started, session: "old", at: now.addingTimeInterval(-3600),
                                        tier: "easy", source: "manual", name: "Old")))
        let sink = FlintHistorySink(store: store, isExtension: false, activity: { $0(false) })
        let model = FocusHistoryModel(sink: sink, clock: { wall }, sessionClock: sessionClock,
                                      activeSession: { active })
        let (success, kept) = await withCheckedContinuation { continuation in
            model.clear { success, kept in
                continuation.resume(returning: (success, kept))
            }
        }
        XCTAssertTrue(success)
        XCTAssertEqual(kept, keepsRunning)
        // Await the actual commit and subsequent ordered refresh, rather than racing
        // XCTest's wall-clock expectation deadlines on the main actor. This also
        // finishes fixture IO before its directory is removed.
        let _: FlintHistorySummary = await withCheckedContinuation { continuation in
            model.refresh { continuation.resume(returning: $0) }
        }
        XCTAssertEqual(Set(store.read().events.map(\.session)), keepsRunning ? ["running"] : [])
    }
    func testRunningSummaryIsLabelledAndNeverPresentedAsComplete() {
        let event = FlintHistoryEvent(type: .started, session: "a", at: now.addingTimeInterval(-300),
                                     plannedEnd: now.addingTimeInterval(600), tier: "easy", source: "manual", name: "Read")
        let result = FlintHistoryAggregator.summarize(.init(events: [event], historyStart: now.addingTimeInterval(-3600), availability: .value), activeSessionID: "a", now: now)
        XCTAssertTrue(result.days.last!.inProgress)
        XCTAssertEqual(FocusHistoryText.today(result), "5m")
        XCTAssertNil(FocusHistoryModel.latestCompletion(in: result, acknowledgedID: nil))
    }
    func testFocusStreakPresentationHiddenUnavailableAndEmpty() {
        XCTAssertEqual(FocusStreakPresentation.make(nil), .hidden)
        XCTAssertEqual(FocusStreakPresentation.make(summary([], state: .unavailable)), .hidden)
        XCTAssertEqual(FocusStreakPresentation.make(summary([], state: .empty)), .empty)
        let earlyOnly = [FlintHistoryEvent(type: .started, session: "a", at: now.addingTimeInterval(-1800),
                                           plannedEnd: now, tier: "easy", source: "manual", name: "Read"),
                         FlintHistoryEvent(type: .endedEarly, session: "a", at: now.addingTimeInterval(-900), route: "manual_stop")]
        XCTAssertEqual(FocusStreakPresentation.make(summary(earlyOnly)), .empty)
    }
    func testFocusStreakPresentationActiveStripAndCopy() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let midnight = calendar.startOfDay(for: now)
        var events: [FlintHistoryEvent] = []
        for offset in [-3, -2, -1, 0] {
            let start = calendar.date(byAdding: .day, value: offset, to: midnight)!
            events += absoluteSession(start: start, end: start.addingTimeInterval(600), id: "\(offset)")
        }
        let result = FlintHistoryAggregator.summarize(
            .init(events: events, historyStart: calendar.date(byAdding: .day, value: -2, to: midnight), availability: .value),
            activeSessionID: nil, now: midnight.addingTimeInterval(120), calendar: calendar)
        guard case .visible(let streak, let caption, let best, let bestCaption, let weekCompleted,
                            let weekCaption, let days, let footnote, let isActive, let statusLine,
                            let completedToday, _) =
                FocusStreakPresentation.make(result, calendar: calendar) else {
            return XCTFail("expected visible streak presentation")
        }
        XCTAssertEqual(streak, 3)
        XCTAssertTrue(isActive)
        XCTAssertTrue(completedToday)
        XCTAssertEqual(statusLine, FocusHistoryText.streakCompletedToday)
        XCTAssertEqual(caption, FocusHistoryText.streakDaysInARow(3))
        XCTAssertEqual(best, 3)
        XCTAssertEqual(bestCaption, FocusHistoryText.bestRecordedStreak(3))
        XCTAssertEqual(weekCompleted, 3)
        XCTAssertEqual(weekCaption, FocusHistoryText.streakWeekCompleted(3))
        XCTAssertEqual(footnote, FocusHistoryText.streakFootnote)
        XCTAssertEqual(days.filter { $0.kind == .unavailable }.count, 4)
        XCTAssertEqual(days.filter { $0.kind == .completed }.count, 3)
        XCTAssertEqual(days.last?.isToday, true)
        XCTAssertEqual(FocusHistoryText.streakDaysInARow(1), "1 day in a row")
        XCTAssertEqual(FocusHistoryText.streakWeekCompleted(1), "1 completed focus day this week")
    }
    func testFocusStreakPresentationTodayIncompleteUsesYesterdayAnchor() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let midnight = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: midnight)!
        let events = absoluteSession(start: yesterday, end: yesterday.addingTimeInterval(600), id: "y")
        let result = FlintHistoryAggregator.summarize(
            .init(events: events, historyStart: yesterday, availability: .value),
            activeSessionID: nil, now: midnight.addingTimeInterval(120), calendar: calendar)
        guard case .visible(let streak, _, _, _, _, _, let days, _, let isActive, let statusLine,
                            let completedToday, _) =
                FocusStreakPresentation.make(result, calendar: calendar) else {
            return XCTFail("expected visible streak presentation")
        }
        XCTAssertEqual(streak, 1)
        XCTAssertTrue(isActive)
        XCTAssertFalse(completedToday)
        XCTAssertEqual(statusLine, FocusHistoryText.streakNotCompletedToday)
        XCTAssertEqual(days.last?.kind, .incomplete)
        XCTAssertEqual(days.last?.isToday, true)
        XCTAssertEqual(days.dropLast().last?.kind, .completed)
    }
    func testFocusStreakPresentationBrokenShowsRestartWithoutShaming() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let midnight = calendar.startOfDay(for: now)
        let older = calendar.date(byAdding: .day, value: -3, to: midnight)!
        let events = absoluteSession(start: older, end: older.addingTimeInterval(600), id: "old")
        let result = FlintHistoryAggregator.summarize(
            .init(events: events, historyStart: calendar.date(byAdding: .day, value: -10, to: midnight), availability: .value),
            activeSessionID: nil, now: midnight.addingTimeInterval(120), calendar: calendar)
        guard case .visible(let streak, let caption, let best, let bestCaption, _, _, _, let footnote,
                            let isActive, let statusLine, let completedToday, _) =
                FocusStreakPresentation.make(result, calendar: calendar) else {
            return XCTFail("expected visible zero streak")
        }
        XCTAssertEqual(streak, 0)
        XCTAssertFalse(isActive)
        XCTAssertFalse(completedToday)
        XCTAssertNil(statusLine)
        XCTAssertEqual(caption, FocusHistoryText.streakNoActive)
        XCTAssertEqual(footnote, FocusHistoryText.streakRestart)
        XCTAssertEqual(best, 1)
        XCTAssertEqual(bestCaption, FocusHistoryText.bestRecordedStreak(1))
    }
    private func absoluteSession(start: Date, end: Date, id: String) -> [FlintHistoryEvent] {
        [.init(type: .started, session: id, at: start, plannedEnd: end, tier: "easy", source: "manual", name: "Focus"),
         .init(type: .completed, session: id, at: end)]
    }
    private func session(_ id: String, end: Date) -> [FlintHistoryEvent] {
        [.init(type: .started, session: id, at: end.addingTimeInterval(-1800), plannedEnd: end,
               tier: "easy", source: "manual", name: "Read"), .init(type: .completed, session: id, at: end)]
    }
    private func summary(_ events: [FlintHistoryEvent], state: FlintHistoryAvailability = .value) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(.init(events: events, historyStart: now.addingTimeInterval(-86400), availability: state),
                                        activeSessionID: nil, now: now)
    }
}
