import XCTest
@testable import Flint

/// The Help screen's Emergency Pass line follows the rolling seven-day policy, never a week key.
final class HelpPassStatusTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testNeverUsedIsAvailable() {
        XCTAssertEqual(HelpPassStatus.status(lastUsed: nil, now: now), .available)
    }

    func testUsedRecentlyShowsNextEligibilitySevenDaysAfterUse() {
        let used = now.addingTimeInterval(-24 * 60 * 60)
        XCTAssertEqual(HelpPassStatus.status(lastUsed: used, now: now),
                       .next(used.addingTimeInterval(7 * 24 * 60 * 60)))
    }

    func testOneSecondBeforeAndAtSevenDays() {
        let period: TimeInterval = 7 * 24 * 60 * 60
        XCTAssertEqual(HelpPassStatus.status(lastUsed: now.addingTimeInterval(-(period - 1)), now: now),
                       .next(now.addingTimeInterval(1)))
        XCTAssertEqual(HelpPassStatus.status(lastUsed: now.addingTimeInterval(-period), now: now), .available)
    }
}
