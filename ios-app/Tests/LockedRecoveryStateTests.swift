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

    func testAWallJumpKeepsTheBlockAndShowsItsLogicalEnd() {
        var wall = now
        var monotonic: TimeInterval = 100
        let clock = FlintClock(now: { wall }, monotonic: { monotonic }, bootSessionID: { "boot" },
                               calendar: { .current })
        let active = FlintActiveSession(name: "Focus", startedAt: now, endsAt: now.addingTimeInterval(1800),
                                        breakLevel: .hardcore, monitorName: "flint.session.0000000000000000",
                                        monotonicStart: 100, bootSessionID: "boot", plannedDuration: 1800)
        wall = now.addingTimeInterval(3600)
        monotonic += 600
        let state = LockedRecoveryState.make(active: active, hasRecord: true, passAvailable: true,
                                             passLastUsed: nil, now: wall, clock: clock)
        guard case let .session(tier, until) = state.running else { return XCTFail("still blocked") }
        XCTAssertEqual(tier, .hardcore)
        XCTAssertEqual(until?.timeIntervalSince(wall) ?? 0, 1200, accuracy: 1)
        XCTAssertTrue(state.canUsePass)
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
