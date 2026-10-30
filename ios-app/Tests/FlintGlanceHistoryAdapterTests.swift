import XCTest
import FlintCore
@testable import Flint

final class FlintGlanceHistoryAdapterTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private func project(_ availability: FlintHistoryAvailability, events: [FlintHistoryEvent] = [],
                         activeID: String? = nil, historyStart: Date? = nil) -> FlintGlanceInput.History {
        let snapshot = FlintHistorySnapshot(events: events, historyStart: historyStart ?? now.addingTimeInterval(-3600),
                                             availability: availability)
        let summary = FlintHistoryAggregator.summarize(snapshot, activeSessionID: activeID,
                                                        now: now, calendar: calendar)
        return FlintGlanceHistoryAdapter.project(summary, at: now, calendar: calendar)
    }

    func testPublicAvailabilityPreservesUnavailableEmptyAndRealZero() {
        XCTAssertEqual(project(.unavailable).availability, .unavailable)
        XCTAssertNil(project(.unavailable).seconds)
        XCTAssertEqual(project(.empty).availability, .empty)
        XCTAssertEqual(project(.value).seconds, 0)
        XCTAssertEqual(project(.degraded).availability, .degraded)
    }

    func testRealPublicAggregationAndPartialDataRemainDistinct() {
        let start = FlintHistoryEvent(type: .started, session: "fixture", at: now.addingTimeInterval(-120),
            plannedEnd: now, tier: "easy", source: "manual", name: "Focus")
        let end = FlintHistoryEvent(type: .completed, session: "fixture", at: now)
        let value = project(.degraded, events: [start, end])
        XCTAssertEqual(value.availability, .degraded)
        XCTAssertEqual(value.seconds, 120)
        XCTAssertFalse(value.inProgress)
    }

    func testRunningIntervalsUseFOutcomeAndRemainAsOfRead() {
        let start = FlintHistoryEvent(type: .started, session: "fixture", at: now.addingTimeInterval(-120),
            plannedEnd: now.addingTimeInterval(600), tier: "harder", source: "manual", name: "Focus")
        let value = project(.value, events: [start], activeID: "fixture")
        XCTAssertEqual(value.seconds, 120)
        XCTAssertTrue(value.inProgress)
        XCTAssertEqual(value.readAt, now)
        XCTAssertEqual(project(.value, events: [start], activeID: nil).seconds, 0,
                       "Unknown active identity must not fabricate running focus")
    }

    func testDayBeforeHistoryStartIsNoDataNotZero() {
        XCTAssertNil(project(.value, historyStart: now.addingTimeInterval(86400)).seconds)
    }
    func testR17WidgetAndLiveActivityAggregateNeverInflatesExpiredStart() {
        let start = FlintHistoryEvent(type: .started, session: "killed", at: now.addingTimeInterval(-3600),
            plannedEnd: now.addingTimeInterval(-60), tier: "easy", source: "manual", name: "Focus")
        let reading = project(.value, events: [start], activeID: "killed")
        XCTAssertEqual(reading.seconds, 0)
        XCTAssertFalse(reading.inProgress)
        XCTAssertEqual(project(.value, events: [start, .init(type: .completed, session: "killed", at: now.addingTimeInterval(-60))]).seconds, 3540)
    }
}
