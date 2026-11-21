import XCTest
import FlintCore
@testable import Flint

/// D1/D2: unreadable history must never be presented as a zero streak, and the badge label
/// must stay neutral when nothing could be read. A readable history with no completed days
/// is still a legitimate zero.
final class StreakPresentationPolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }

    private func summary(_ events: [FlintHistoryEvent] = [],
                         state: FlintHistoryAvailability = .value) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(
            .init(events: events,
                  historyStart: now.addingTimeInterval(-4 * 86_400),
                  availability: state),
            activeSessionID: nil, now: now, calendar: calendar)
    }

    private func completedSession(daysAgo: Int) -> [FlintHistoryEvent] {
        let end = now.addingTimeInterval(-Double(daysAgo) * 86_400)
        return [.init(type: .started, session: "s\(daysAgo)", at: end.addingTimeInterval(-1800),
                      plannedEnd: end, tier: "easy", source: "manual", name: "Focus"),
                .init(type: .completed, session: "s\(daysAgo)", at: end)]
    }

    // MARK: D1 — Insights card gating

    func testCardIsHiddenWithoutAReadableSummary() {
        XCTAssertFalse(StreakPresentationPolicy.showsCard(for: nil))
        XCTAssertFalse(StreakPresentationPolicy.showsCard(for: summary(state: .unavailable)))
    }

    func testCardShowsForReadableHistoryIncludingARealZero() {
        XCTAssertTrue(StreakPresentationPolicy.showsCard(for: summary(state: .empty)))
        XCTAssertTrue(StreakPresentationPolicy.showsCard(for: summary(completedSession(daysAgo: 0))))
        XCTAssertTrue(StreakPresentationPolicy.showsCard(for: summary(state: .degraded)))
    }

    // MARK: D1 — celebration sheet gating

    func testSheetDoesNotOpenOnUnreadableHistory() {
        XCTAssertFalse(StreakPresentationPolicy.showsSheet(for: nil))
        XCTAssertFalse(StreakPresentationPolicy.showsSheet(for: summary(state: .unavailable)))
        XCTAssertTrue(StreakPresentationPolicy.showsSheet(for: summary(state: .empty)))
        XCTAssertTrue(StreakPresentationPolicy.showsSheet(for: summary(completedSession(daysAgo: 0))))
    }

    // MARK: D2 — Today badge label

    func testBadgeIsNeutralWhenHistoryIsUnreadable() {
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: nil), "Focus streak")
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: summary(state: .unavailable)), "Focus streak")
    }

    func testBadgeReportsARealZeroAndARealStreak() {
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: summary(state: .empty)),
                       "No active focus streak")

        let one = summary(completedSession(daysAgo: 0))
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: one), "Focus streak, 1 day")

        let three = summary(completedSession(daysAgo: 0) + completedSession(daysAgo: 1)
            + completedSession(daysAgo: 2))
        XCTAssertEqual(three.streak, 3)
        XCTAssertEqual(StreakPresentationPolicy.badgeLabel(for: three), "Focus streak, 3 days")
    }
}
