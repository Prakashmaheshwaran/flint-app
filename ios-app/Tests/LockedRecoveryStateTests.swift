import XCTest
import FlintCore
@testable import Flint

/// PC-50: with the app PIN on, the locked gate still reaches the pass and the OS recovery route
/// and reveals nothing private.
final class LockedRecoveryStateTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_206_800)

    private func session(_ tier: BreakLevel, minutes: Double? = 60) -> FlintActiveSession {
        FlintActiveSession(name: "Private session name", startedAt: now.addingTimeInterval(-60),
                           endsAt: minutes.map { now.addingTimeInterval($0 * 60) }, breakLevel: tier,
                           monitorName: "flint.session.0000000000000000")
    }

    func testARunningHardcoreBlockOffersThePassWithoutItsName() {
        let state = LockedRecoveryState.make(active: session(.hardcore), hasRecord: true, passAvailable: true,
                                             passLastUsed: nil, now: now)
        XCTAssertTrue(state.canUsePass)
        XCTAssertTrue(state.headline.hasPrefix("A Hardcore block is running until"))
        for text in [state.headline, state.passLine] {
            XCTAssertFalse(text.contains("Private session name"), "the PIN protects session names")
        }
    }

    func testThePassIsOfferedOnlyWhenItCanEndSomething() {
        let harder = LockedRecoveryState.make(active: session(.harder), hasRecord: true, passAvailable: true,
                                              passLastUsed: nil, now: now)
        XCTAssertFalse(harder.canUsePass, "the app ends Harder after its wait; the pass is for Hardcore")
        let idle = LockedRecoveryState.make(active: nil, hasRecord: false, passAvailable: true,
                                            passLastUsed: nil, now: now)
        XCTAssertEqual(idle.running, .none)
        XCTAssertFalse(idle.canUsePass)
        XCTAssertEqual(idle.passLine, "Emergency Pass: available for a running Hardcore block",
                       "with nothing running the line isn't read as an action")
        let used = LockedRecoveryState.make(active: session(.hardcore), hasRecord: true, passAvailable: false,
                                            passLastUsed: now.addingTimeInterval(-3600), now: now)
        XCTAssertFalse(used.canUsePass)
        XCTAssertEqual(used.nextPass, now.addingTimeInterval(-3600 + FlintEmergencyPass.period))
        XCTAssertTrue(used.passLine.hasPrefix("Emergency Pass: available again"))
    }

    func testAnUnreadableRecordIsNotNothingRunning() {
        let state = LockedRecoveryState.make(active: nil, hasRecord: true, passAvailable: true,
                                             passLastUsed: nil, now: now)
        XCTAssertEqual(state.running, .unreadable)
        XCTAssertFalse(state.canUsePass)
    }

    func testAnExpiredRecordReadsAsNothingRunning() {
        let expired = session(.hardcore, minutes: -1)
        let state = LockedRecoveryState.make(active: expired, hasRecord: true, passAvailable: true,
                                             passLastUsed: nil, now: now)
        XCTAssertEqual(state.running, .none)
    }
}
