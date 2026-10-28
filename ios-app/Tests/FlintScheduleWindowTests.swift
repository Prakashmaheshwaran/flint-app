import XCTest
import FlintCore

final class FlintScheduleWindowTests: XCTestCase {
    private func date(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }
    private func calendar(_ zone: String = "UTC") -> Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = TimeZone(identifier: zone)!
        return result
    }

    func testOvernightTailBelongsToSelectedStartWeekdayAndEndsExclusively() throws {
        let schedule = FlintSchedule(daysOfWeek: [2], startHour: 22, endHour: 7)
        let now = date("2026-10-27T02:00:00Z") // Tuesday, Monday's overnight tail
        let windows = try XCTUnwrap(FlintScheduleWindow.resolve(schedule, at: now, calendar: calendar()))
        XCTAssertEqual(windows.current?.start, date("2026-10-26T22:00:00Z"))
        XCTAssertEqual(windows.current?.end, date("2026-10-27T07:00:00Z"))
        XCTAssertEqual(windows.next?.start, date("2026-11-02T22:00:00Z"))
        XCTAssertNil(FlintScheduleWindow.resolve(schedule, at: date("2026-10-27T07:00:00Z"),
                                                calendar: calendar())?.current)
    }

    func testDSTMissingEndpointMovesByGapAndRepeatedEndUsesLaterInstant() throws {
        let zone = calendar("America/New_York")
        let spring = FlintSchedule(startHour: 1, endHour: 2, endMinute: 30)
        let first = try XCTUnwrap(FlintScheduleWindow.resolve(spring,
            at: date("2026-03-08T06:15:00Z"), calendar: zone))
        XCTAssertEqual(first.current?.end, date("2026-03-08T07:30:00Z"))
        let fall = FlintSchedule(startHour: 0, endHour: 1, endMinute: 30)
        let second = try XCTUnwrap(FlintScheduleWindow.resolve(fall,
            at: date("2026-11-01T05:45:00Z"), calendar: zone))
        XCTAssertEqual(second.current?.end, date("2026-11-01T06:30:00Z"))
    }

    func testInvalidScheduleIsUnavailable() {
        XCTAssertNil(FlintScheduleWindow.resolve(FlintSchedule(startHour: 25, endHour: 7),
                                                at: Date(), calendar: calendar()))
    }
}
