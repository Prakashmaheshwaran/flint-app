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
