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
        let state = LockedRecoveryState.make(active: active, hasRecord: true, pass: pass(), now: wall, clock: clock)
        guard case let .session(tier, until) = state.running else { return XCTFail("still blocked") }
        XCTAssertEqual(tier, .hardcore)
        XCTAssertEqual(until?.timeIntervalSince(wall) ?? 0, 1200, accuracy: 1)
        XCTAssertTrue(state.canUsePass)
    }

    private func pass(active: Bool = true, remaining: TimeInterval? = nil) -> FlintPassStatus {
        var record = FlintLedgerState.Pass()
        record.usage = remaining == nil ? .neverUsed : .used
        record.cooldownRemaining = remaining ?? 0
        let instant = FlintLedgerInstant(wall: now, monotonic: 100, bootID: "boot")
        record.checkpoint = instant
        return record.status(at: instant, hasActiveCommitment: active)
    }

    func testARunningHardcoreBlockOffersThePassWithoutItsName() {
        let state = LockedRecoveryState.make(active: session(.hardcore), hasRecord: true, pass: pass(), now: now)
        XCTAssertTrue(state.canUsePass)
        XCTAssertTrue(state.headline.hasPrefix("A Hardcore block is running until"))
        for text in [state.headline, state.passLine] {
            XCTAssertFalse(text.contains("Private session name"), "the PIN protects session names")
        }
    }

    func testThePassIsOfferedOnlyWhenItCanEndSomething() {
        let harder = LockedRecoveryState.make(active: session(.harder), hasRecord: true, pass: pass(), now: now)
        XCTAssertTrue(harder.canUsePass, "global recovery covers every tier")
        let idle = LockedRecoveryState.make(active: nil, hasRecord: false, pass: pass(active: false), now: now)
        XCTAssertEqual(idle.running, .none)
        XCTAssertFalse(idle.canUsePass)
        XCTAssertEqual(idle.passLine, "Emergency Pass: Available",
                       "with nothing running the line isn't read as an action")
        let next = now.addingTimeInterval(604800 - 3600)
        let used = LockedRecoveryState.make(active: session(.hardcore), hasRecord: true,
                                            pass: pass(remaining: 604800 - 3600), nextPass: next, now: now)
        XCTAssertFalse(used.canUsePass)
        XCTAssertEqual(used.nextPass, next)
        XCTAssertTrue(used.passLine.hasPrefix("Next pass:"))
    }

    func testAnUnreadableRecordIsNotNothingRunning() {
        let state = LockedRecoveryState.make(active: nil, hasRecord: true, pass: pass(), now: now)
        XCTAssertEqual(state.running, .unreadable)
        XCTAssertFalse(state.canUsePass)
    }

    func testAnExpiredRecordReadsAsNothingRunning() {
        let expired = session(.hardcore, minutes: -1)
        let state = LockedRecoveryState.make(active: expired, hasRecord: true, pass: pass(active: false), now: now)
        XCTAssertEqual(state.running, .none)
    }

    func testLockedRecoveryUsesLogicalEndAfterSameBootClockJump() {
        let active = FlintActiveSession(name: "Private", startedAt: now, endsAt: now.addingTimeInterval(3600),
            breakLevel: .hardcore, monitorName: "session", monotonicStart: 1000, bootSessionID: "boot")
        let shifted = now.addingTimeInterval(86400)
        let clock = FlintClock(now: { shifted }, monotonic: { 1060 }, bootSessionID: { "boot" }, calendar: { .current })
        let state = LockedRecoveryState.make(active: active, hasRecord: true, pass: pass(), now: shifted, clock: clock)
        XCTAssertEqual(state.running, .session(tier: .hardcore, until: shifted.addingTimeInterval(3540)))
        XCTAssertTrue(state.canUsePass)
        XCTAssertFalse(state.headline.contains("Private"))
    }

    func testRulesOnlyRecoveryAndUnavailableStateStayReachableAndPrivate() {
        let rulesOnly = LockedRecoveryState.make(active: nil, hasRecord: false, pass: pass(), now: now)
        XCTAssertTrue(rulesOnly.canUsePass)
        XCTAssertEqual(rulesOnly.headline, "Personal commitments are active.")
        let unavailable = LockedRecoveryState.make(active: nil, hasRecord: true, pass: nil, now: now)
        XCTAssertFalse(unavailable.canUsePass)
        XCTAssertEqual(unavailable.passLine, "Protection state unavailable")
    }

    func testPendingRecoveryWorkIsNotAdvertisedAsAnotherAvailablePass() {
        let pending = LockedRecoveryState.make(active: nil, hasRecord: false, pass: pass(),
            needsApplication: true, now: now)
        XCTAssertFalse(pending.canUsePass)
        XCTAssertEqual(pending.passLine, "Saved recovery needs to finish applying")
    }
}
