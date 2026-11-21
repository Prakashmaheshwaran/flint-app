import XCTest
import FlintCore
@testable import Flint

/// D3: the model resolves its calendar on every refresh, so a time-zone change rebuckets
/// history without a relaunch while stored completion timestamps stay untouched.
@MainActor
final class StreakTimezoneRebucketTests: XCTestCase {

    private var newYork: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }

    private var tokyo: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        return calendar
    }

    func testRefreshRebucketsWhenTheCalendarChanges() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }

        let ny = newYork
        func at(_ hour: Int, in calendar: Calendar) -> Date {
            let base = calendar.date(from: DateComponents(year: 2026, month: 10, day: 28))!
            return calendar.date(bySettingHour: hour, minute: 0, second: 0, of: base)!
        }
        // NY 2026-10-28 01:00 and 20:00 are both 10-28 in New York but 10-28 14:00 and
        // 10-29 09:00 in Tokyo: one local day vs two.
        let firstEnd = at(1, in: ny)
        let secondEnd = at(20, in: ny)
        let now = at(21, in: ny).addingTimeInterval(1800)

        let store = FlintHistoryStore(directory: directory, clock: { now }, calendar: ny)
        for (index, end) in [firstEnd, secondEnd].enumerated() {
            XCTAssertTrue(store.append(.init(type: .started, session: "s\(index)",
                                             at: end.addingTimeInterval(-1800), plannedEnd: end,
                                             tier: "easy", source: "manual", name: "Focus")))
            XCTAssertTrue(store.append(.init(type: .completed, session: "s\(index)", at: end)))
        }
        let written = store.read().events.map(\.at)

        var activeCalendar = ny
        let sink = FlintHistorySink(store: store, isExtension: false, activity: { $0(false) })
        let model = FocusHistoryModel(sink: sink, clock: { now },
                                      calendarProvider: { activeCalendar },
                                      activeSession: { nil })

        let inNewYork: FlintHistorySummary = await withCheckedContinuation { continuation in
            model.refresh { continuation.resume(returning: $0) }
        }
        XCTAssertEqual(inNewYork.streak, 1, "both completions are the same New York day")
        XCTAssertEqual(inNewYork.days.last?.completed, 2)

        activeCalendar = tokyo
        let inTokyo: FlintHistorySummary = await withCheckedContinuation { continuation in
            model.refresh { continuation.resume(returning: $0) }
        }
        XCTAssertEqual(inTokyo.streak, 2, "the 20:00 completion is the next Tokyo day")

        XCTAssertEqual(store.read().events.map(\.at), written,
                       "rebucketing must not rewrite stored completion timestamps")
    }
}
