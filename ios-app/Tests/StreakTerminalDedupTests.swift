import XCTest
import FlintCore
@testable import Flint

/// D6: terminal-event edge cases driven through the production store and aggregator, so the
/// assertions describe the streak the app would actually read.
final class StreakTerminalDedupTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var start: Date { now.addingTimeInterval(-3600) }

    private func withStore(_ body: (FlintHistoryStore) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: nil)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(FlintHistoryStore(directory: directory, clock: { self.now }, calendar: calendar))
    }

    private func started(id: String = "start") -> FlintHistoryEvent {
        FlintHistoryEvent(type: .started, session: "s", at: start, id: id, plannedEnd: now,
                          tier: "easy", source: "manual", name: "Focus")
    }

    private func terminal(_ type: FlintHistoryEventType, id: String, at: Date? = nil,
                          route: String? = nil) -> FlintHistoryEvent {
        FlintHistoryEvent(type: type, session: "s", at: at ?? now, id: id, route: route)
    }

    private func summary(_ store: FlintHistoryStore) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(store.read(), activeSessionID: nil, now: now, calendar: calendar)
    }

    func testDuplicateTerminalDeliveryCreditsOneDay() throws {
        try withStore { store in
            store.append(started())
            store.append(terminal(.completed, id: "completed"))
            store.append(terminal(.completed, id: "completed")) // same event id re-delivered

            let snapshot = store.read()
            XCTAssertEqual(snapshot.events.filter { $0.type == .completed }.count, 1)
            let result = summary(store)
            XCTAssertEqual(result.days.last?.completed, 1)
            XCTAssertEqual(result.streak, 1)
        }
    }

    func testLaterTerminalCannotReplaceTheStoredOutcome() throws {
        try withStore { store in
            store.append(started())
            store.append(terminal(.completed, id: "completed", at: now))
            store.append(terminal(.endedEarly, id: "early", at: now.addingTimeInterval(60),
                                  route: "manual_stop"))

            let result = summary(store)
            XCTAssertEqual(result.days.last?.completed, 1)
            XCTAssertEqual(result.days.last?.endedEarly, 0)
            XCTAssertEqual(result.streak, 1)
        }
    }

    func testEarlyEndFirstKeepsTheEarlyEndAgainstALaterCompletion() throws {
        try withStore { store in
            store.append(started())
            store.append(terminal(.endedEarly, id: "early", at: now, route: "manual_stop"))
            store.append(terminal(.completed, id: "completed", at: now.addingTimeInterval(60)))

            let result = summary(store)
            XCTAssertEqual(result.days.last?.completed, 0)
            XCTAssertEqual(result.days.last?.endedEarly, 1)
            XCTAssertEqual(result.streak, 0)
        }
    }

    func testCancelledSessionDoesNotCreditADay() throws {
        try withStore { store in
            store.append(started())
            store.append(terminal(.endedEarly, id: "cancel", route: "manual_stop"))

            let result = summary(store)
            XCTAssertEqual(result.days.last?.completed, 0)
            XCTAssertEqual(result.streak, 0)
        }
    }

    func testSupersededSessionDoesNotCreditADay() throws {
        try withStore { store in
            store.append(started())
            store.append(terminal(.superseded, id: "superseded"))

            let result = summary(store)
            XCTAssertEqual(result.days.last?.completed, 0)
            XCTAssertEqual(result.streak, 0)
            XCTAssertEqual(result.bestRecordedStreak, 0)
        }
    }
}
