import XCTest
import SwiftUI
import UIKit
import FamilyControls
import FlintCore
@testable import Flint

final class FlintInsightsTests: XCTestCase {
    func testAuthorizationChangesAndSimulatorNeverImplyAnEmptyReport() {
        for isSimulator in [false, true] {
            XCTAssertEqual(InsightsAvailability.resolve(.notDetermined, isSimulator: isSimulator), .needsAuthorization)
            XCTAssertEqual(InsightsAvailability.resolve(.denied, isSimulator: isSimulator), .needsSettings)
        }
        XCTAssertEqual(InsightsAvailability.resolve(.approved, isSimulator: true), .simulator)
        XCTAssertEqual(InsightsAvailability.resolve(.approved, isSimulator: false), .report)
    }

    func testApprovedWithDataAccessStillShowsTheReport() {
        guard #available(iOS 26.4, *) else { return }
        // `approvedWithDataAccess` is still an approval; it must not fall into `.unavailable`,
        // which would strand an approved user on a screen their retry can never clear.
        XCTAssertEqual(InsightsAvailability.resolve(.approvedWithDataAccess, isSimulator: false), .report)
        XCTAssertEqual(InsightsAvailability.resolve(.approvedWithDataAccess, isSimulator: true), .simulator)
    }

    @MainActor
    func testSharedAuthorizationRefreshRemovesRevokedReportAndRestoresApprovedAvailability() {
        var status = AuthorizationStatus.approved
        let auth = AuthorizationModel(readStatus: { status }, request: {})

        auth.refresh()
        XCTAssertEqual(InsightsAvailability.resolve(auth.status, isSimulator: false), .report)
        XCTAssertEqual(InsightsAvailability.resolve(auth.status, isSimulator: true), .simulator)

        status = .denied
        auth.refresh()
        XCTAssertEqual(InsightsAvailability.resolve(auth.status, isSimulator: false), .needsSettings)
        XCTAssertEqual(InsightsAvailability.resolve(auth.status, isSimulator: true), .needsSettings)

        status = .approved
        auth.refresh()
        XCTAssertEqual(InsightsAvailability.resolve(auth.status, isSimulator: false), .report)
        XCTAssertEqual(InsightsAvailability.resolve(auth.status, isSimulator: true), .simulator)
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

    func testSubMinuteActivityDoesNotRenderAsZero() {
        XCTAssertEqual(TotalActivityContent.format(0), "0m")
        XCTAssertEqual(TotalActivityContent.format(0.5), "<1m")
        XCTAssertEqual(TotalActivityContent.format(1), "<1m")
        XCTAssertEqual(TotalActivityContent.format(59.9), "<1m")
        XCTAssertEqual(TotalActivityContent.format(60), "1m")
        XCTAssertEqual(TotalActivityContent.format(90), "1m")
        XCTAssertEqual(TotalActivityContent.format(3_600), "1h 0m")
        XCTAssertEqual(TotalActivityContent.format(5_400), "1h 30m")
    }

    @MainActor
    func testReportFontFallbacksKeepTheRequestedPointSize() throws {
        // Exercise the fallback directly: it must stay proportional to the requested sizes.
        // The old text-style fallback rendered the 52 pt total at ~28 pt and the 11 pt eyebrow
        // at ~17 pt, so a missing bundled face silently shrank the whole report.
        func lineHeight(_ font: Font) throws -> CGFloat {
            let renderer = ImageRenderer(content: Text("0m").font(font).foregroundStyle(FlintBrand.bone))
            return try XCTUnwrap(renderer.uiImage, "fallback render").size.height
        }
        let display = try lineHeight(ReportFont.fallbackDisplay(52))
        let text = try lineHeight(ReportFont.fallbackText(11, medium: false))
        XCTAssertGreaterThan(display / text, 3, "display fallback must keep its 52 pt size, not a text style")
    }

    @MainActor
    func testReportContentRendersAtStandardAndAccessibilityTextSizes() throws {
        let date = Date(timeIntervalSince1970: 1_790_000_000)
        let empty = ActivitySummary(days: [.init(start: date, duration: 0)])
        let activity = ActivitySummary(total: 5_400, days: [
            .init(start: date, duration: 1_800),
            .init(start: date.addingTimeInterval(86_400), duration: 3_600)
        ], apps: [.init(name: "Fixture app", duration: 5_400, pickups: 4)])
        for (name, summary) in [("no-data", ActivitySummary()), ("zero-usage", empty), ("activity-fixture", activity)] {
            for size in [DynamicTypeSize.large, .accessibility5] {
                // Render the production content at its natural height. ImageRenderer does
                // not capture ScrollView; a non-nil image alone can hide an empty rendering.
                let image = try reportImage(summary, at: size)
                let pixels = try XCTUnwrap(image.cgImage?.dataProvider?.data) as Data
                XCTAssertGreaterThan(Set(pixels).count, 16, "\(name) at \(size) must render more than a plain background")
                let attachment = XCTAttachment(image: image)
                attachment.name = "insights-report-\(name)-\(size)"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
    }

    @MainActor
    func testAccessibilityReportKeepsWeekAndAppsSections() throws {
        // A larger pixel count cannot tell a complete report from one whose sections were
        // clipped away, so compare layout heights: each section must add height at AX5.
        let date = Date(timeIntervalSince1970: 1_790_000_000)
        let activity = ActivitySummary(total: 5_400, days: [
            .init(start: date, duration: 1_800),
            .init(start: date.addingTimeInterval(86_400), duration: 3_600)
        ], apps: [.init(name: "Fixture app", duration: 5_400, pickups: 4)])
        let full = try reportImage(activity, at: .accessibility5).size.height
        let withoutApps = try reportImage(ActivitySummary(total: activity.total, days: activity.days),
                                          at: .accessibility5).size.height
        let singleDay = try reportImage(ActivitySummary(total: activity.total, days: [activity.days[0]],
                                                       apps: activity.apps), at: .accessibility5).size.height
        XCTAssertGreaterThan(full, withoutApps, "the most-reached-for section must render at AX5")
        XCTAssertGreaterThan(full, singleDay, "the week ridge and peak summary must render at AX5")
    }

    @MainActor
    private func reportImage(_ summary: ActivitySummary, at size: DynamicTypeSize) throws -> UIImage {
        let view = TotalActivityContent(summary: summary)
            .frame(width: 402, alignment: .topLeading)
            .background(FlintBrand.ground)
            .environment(\.dynamicTypeSize, size)
            .environment(\.colorScheme, .dark)
        return try XCTUnwrap(ImageRenderer(content: view).uiImage, "render at \(size)")
    }
}
