import XCTest
import FlintCore
@testable import Flint

/// The Help screen's Emergency Pass line follows the rolling seven-day policy, never a week key.
final class HelpPassStatusTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testNeverUsedIsAvailable() {
        var pass = FlintLedgerState.Pass()
        pass.usage = .neverUsed
        XCTAssertEqual(HelpPassStatus.status(pass: pass, at: instant()), .available)
        XCTAssertEqual(HelpPassStatus.status(pass: nil, at: instant()), .unknown)
        XCTAssertEqual(HelpPassStatus.status(pass: .init(), at: instant()), .unknown)
    }

    func testUsedRecentlyShowsNextEligibilitySevenDaysAfterUse() {
        XCTAssertEqual(HelpPassStatus.status(pass: spent(remaining: 6 * 86400), at: instant()),
                       .next(now.addingTimeInterval(6 * 86400)))
    }

    func testOneSecondBeforeAndAtSevenDays() {
        XCTAssertEqual(HelpPassStatus.status(pass: spent(remaining: 1), at: instant()),
                       .next(now.addingTimeInterval(1)))
        XCTAssertEqual(HelpPassStatus.status(pass: spent(remaining: 0), at: instant()), .available)
    }

    private func instant() -> FlintLedgerInstant { .init(wall: now, monotonic: 100, bootID: "boot") }
    private func spent(remaining: TimeInterval) -> FlintLedgerState.Pass {
        var pass = FlintLedgerState.Pass()
        pass.usage = .used; pass.cooldownRemaining = remaining; pass.checkpoint = instant()
        return pass
    }
}
