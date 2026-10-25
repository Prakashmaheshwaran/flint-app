import XCTest
import SwiftUI
import FamilyControls
import FlintCore

final class FlintInsightsTests: XCTestCase {
    func testAuthorizationChangesAndSimulatorNeverImplyAnEmptyReport() {
        for status in [AuthorizationStatus.notDetermined, .denied] {
            XCTAssertEqual(InsightsAvailability.resolve(status, isSimulator: false), .needsAuthorization)
            XCTAssertEqual(InsightsAvailability.resolve(status, isSimulator: true), .needsAuthorization)
        }
        XCTAssertEqual(InsightsAvailability.resolve(.approved, isSimulator: true), .simulator)
        XCTAssertEqual(InsightsAvailability.resolve(.approved, isSimulator: false), .report)
        // A subsequent refresh after revocation must remove the report again.
        XCTAssertEqual(InsightsAvailability.resolve(.denied, isSimulator: false), .needsAuthorization)
    }

    func testRangesUseCalendarDaysIncludingDaylightSavingTime() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let date = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 12)))
        let today = InsightsRange.today.dateInterval(containing: date, calendar: calendar)
        let week = InsightsRange.week.dateInterval(containing: date, calendar: calendar)
        XCTAssertEqual(today.duration, 25 * 60 * 60)
        XCTAssertEqual(week.end, today.end)
        XCTAssertEqual(calendar.dateComponents([.day], from: week.start, to: week.end).day, 7)
        XCTAssertEqual(calendar.dateComponents([.day], from: week.start, to: today.start).day, 6)
        XCTAssertTrue(today.contains(date))
    }

    func testNoReturnedSegmentsAreNotReportedAsZeroUsage() {
        XCTAssertEqual(ActivitySummary().contentState, .noData)
    }

    func testZeroUsageForReturnedDayAndWeekSegmentsIsEmpty() {
        let date = Date(timeIntervalSince1970: 1_790_000_000)
        for count in [1, 7] {
            let days = (0..<count).map { ActivitySummary.Day(start: date.addingTimeInterval(Double($0) * 86_400), duration: 0) }
            let summary = ActivitySummary(total: 0, days: days, apps: [.init(name: "Fixture", duration: 0, pickups: 0)])
            XCTAssertEqual(summary.contentState, .empty)
        }
    }

    func testPartialOrSubMinuteActivityIsNeverHiddenByEmptyState() {
        let date = Date(timeIntervalSince1970: 1_790_000_000)
        let summaries = [
            ActivitySummary(total: 0.5),
            ActivitySummary(days: [.init(start: date, duration: 1)]),
            ActivitySummary(apps: [.init(name: "Fixture", duration: 1, pickups: 0)]),
            ActivitySummary(apps: [.init(name: "Fixture", duration: 0, pickups: 1)]),
            ActivitySummary(total: 3_600, days: [.init(start: date, duration: 3_600)])
        ]
        for summary in summaries {
            XCTAssertEqual(summary.contentState, .activity)
        }
    }

    @MainActor
    func testReportStatesRenderAtStandardAndAccessibilityTextSizes() throws {
        let date = Date(timeIntervalSince1970: 1_790_000_000)
        let empty = ActivitySummary(days: [.init(start: date, duration: 0)])
        let activity = ActivitySummary(total: 5_400, days: [
            .init(start: date, duration: 1_800),
            .init(start: date.addingTimeInterval(86_400), duration: 3_600)
        ], apps: [.init(name: "Fixture app", duration: 5_400, pickups: 4)])
        for (name, summary) in [("no-data", ActivitySummary()), ("zero-usage", empty), ("activity-fixture", activity)] {
            for size in [DynamicTypeSize.large, .accessibility5] {
                let view = TotalActivityView(summary: summary)
                    .frame(width: 402, height: 700)
                    .background(FlintBrand.ground)
                    .environment(\.dynamicTypeSize, size)
                    .environment(\.colorScheme, .dark)
                let renderer = ImageRenderer(content: view)
                let image = try XCTUnwrap(renderer.uiImage, "\(name) at \(size)")
                let attachment = XCTAttachment(image: image)
                attachment.name = "insights-report-\(name)-\(size)"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
    }
}
