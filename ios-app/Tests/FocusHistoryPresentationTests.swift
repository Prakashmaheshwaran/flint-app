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
    func testRunningSummaryIsLabelledAndNeverPresentedAsComplete() {
        let event = FlintHistoryEvent(type: .started, session: "a", at: now.addingTimeInterval(-300),
                                     plannedEnd: now.addingTimeInterval(600), tier: "easy", source: "manual", name: "Read")
        let result = FlintHistoryAggregator.summarize(.init(events: [event], historyStart: now.addingTimeInterval(-3600), availability: .value), activeSessionID: "a", now: now)
        XCTAssertTrue(result.days.last!.inProgress)
        XCTAssertEqual(FocusHistoryText.today(result), "5m")
        XCTAssertNil(FocusHistoryModel.latestCompletion(in: result, acknowledgedID: nil))
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
