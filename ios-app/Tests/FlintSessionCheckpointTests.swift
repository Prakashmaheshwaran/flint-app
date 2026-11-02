import XCTest
@testable import FlintCore

final class FlintSessionCheckpointTests: XCTestCase {
    private var wall = Date(timeIntervalSince1970: 1_800_000_000)
    private var monotonic: TimeInterval = 50_000
    private var boot = "boot-1"
    private var clock: FlintClock {
        FlintClock(now: { self.wall }, monotonic: { self.monotonic },
                   bootSessionID: { self.boot }, calendar: { .current })
    }

    private func session(duration: TimeInterval = 3600) -> FlintActiveSession {
        FlintActiveSession(name: "Focus", startedAt: wall, endsAt: wall.addingTimeInterval(duration),
            breakLevel: .hardcore, monitorName: "flint.session.checkpoint", monotonicStart: monotonic,
            bootSessionID: boot, plannedDuration: duration)
    }

    private func advance(_ seconds: TimeInterval) { wall.addTimeInterval(seconds); monotonic += seconds }
    private func reboot(_ id: String) { boot = id; monotonic = 10 }

    func testFirstNewBootObservationReanchorsBeforeItsForwardJumpR05PC54PC58() throws {
        var saved = session().checkpointed(clock: clock)
        advance(600)
        saved = saved.checkpointed(clock: clock)
        reboot("boot-2")
        saved = saved.checkpointed(clock: clock)
        XCTAssertEqual(saved.remainingCheckpoint?.bootID, "boot-2")
        XCTAssertEqual(saved.remainingCheckpoint?.remaining, 3000)
        XCTAssertEqual(saved.remainingCheckpoint?.timeUncertain, true)
        wall.addTimeInterval(2 * 3600)
        monotonic += 20
        XCTAssertEqual(saved.logicalRemaining(clock: clock), 2980)
        XCTAssertFalse(saved.isLogicallyExpired(clock: clock))
    }

    func testRollbackNearExpiryCannotRestoreMoreThanSavedRemainingR05PC55() throws {
        var saved = session().checkpointed(clock: clock)
        advance(3590)
        saved = saved.checkpointed(clock: clock)
        wall.addTimeInterval(-3600)
        reboot("boot-2")
        saved = saved.checkpointed(clock: clock)
        XCTAssertEqual(saved.remainingCheckpoint?.remaining, 10)
        advance(9)
        wall.addTimeInterval(-3600)
        XCTAssertEqual(saved.logicalRemaining(clock: clock), 1)
        advance(1)
        XCTAssertTrue(saved.isLogicallyExpired(clock: clock))
    }

    func testRebootUncertaintyRemainsVisibleAfterTheCurrentBootAnchorIsSavedPC58() {
        var saved = session().checkpointed(clock: clock)
        advance(600)
        saved = saved.checkpointed(clock: clock)
        reboot("boot-2")
        saved = saved.checkpointed(clock: clock)
        advance(10)
        XCTAssertEqual(FlintTimeIntegrity.verdict(for: saved, clock: clock), .unverifiable)
        wall.addTimeInterval(3600)
        XCTAssertEqual(FlintTimeIntegrity.verdict(for: saved, clock: clock), .wallAhead(seconds: 3600),
                       "a later observed same-boot anomaly is still reported")
    }

    func testDoubleRebootNeverRestoresAnEarlierCheckpointR05PC58() throws {
        var saved = session(duration: 60).checkpointed(clock: clock)
        advance(40)
        saved = saved.checkpointed(clock: clock)
        reboot("boot-2")
        wall.addTimeInterval(-3600)
        saved = saved.checkpointed(clock: clock)
        advance(15)
        saved = saved.checkpointed(clock: clock)
        reboot("boot-3")
        wall.addTimeInterval(-3600)
        saved = saved.checkpointed(clock: clock)
        XCTAssertEqual(saved.remainingCheckpoint?.remaining, 5)
        advance(5)
        XCTAssertTrue(saved.isLogicallyExpired(clock: clock))
    }

    func testLegacyRecordWithoutCheckpointUsesBoundedFallbackThenCurrentBoot() throws {
        let old = Data("""
        {"id":"old","name":"Focus","startedAt":0,"endsAt":600,
         "breakLevel":"hardcore","monitorName":"flint.session"}
        """.utf8)
        var saved = try JSONDecoder().decode(FlintActiveSession.self, from: old)
        XCTAssertNil(saved.remainingCheckpoint)
        XCTAssertNil(saved.naturalEndObservedAt)
        wall = Date(timeIntervalSinceReferenceDate: 590)
        saved = saved.checkpointed(clock: clock)
        XCTAssertEqual(saved.logicalRemaining(clock: clock), 10)
        wall.addTimeInterval(3600)
        monotonic += 1
        XCTAssertEqual(saved.logicalRemaining(clock: clock), 9)
    }

    func testLegacyStartAnchorsGetCheckpointedWithoutRestartingTheirDuration() {
        var saved = session()
        advance(3595)
        saved = saved.checkpointed(clock: clock)
        XCTAssertEqual(saved.remainingCheckpoint?.remaining, 5)
        XCTAssertEqual(saved.remainingCheckpoint?.timeUncertain, false)
    }

    func testRearmPreservesTheNewBootCheckpointR05() throws {
        var saved = session().checkpointed(clock: clock)
        advance(600)
        saved = saved.checkpointed(clock: clock)
        reboot("boot-2")
        saved = saved.checkpointed(clock: clock)
        let plan = FlintSessionAutoClearPlan.make(start: wall, end: wall.addingTimeInterval(3000),
                                                  calendar: .current, strategy: .endWarning)
        saved = saved.rearmed(monitorName: "flint.session.rearmed", plan: plan, at: wall,
                              countsTowardLimit: false)
        wall.addTimeInterval(3600)
        monotonic += 10
        XCTAssertEqual(saved.logicalRemaining(clock: clock), 2990)
    }

    func testLegacyDurationIsFrozenBeforeRollbackRearmMovesItsWallEndR05PC55PC58() {
        var saved = FlintActiveSession(name: "Legacy", startedAt: wall, endsAt: wall.addingTimeInterval(60),
            breakLevel: .hardcore, monitorName: "flint.session.legacy", monotonicStart: monotonic, bootSessionID: boot)
        advance(55)
        saved = saved.checkpointed(clock: clock)
        reboot("boot-2"); wall.addTimeInterval(-3600)
        saved = saved.checkpointed(clock: clock)
        let end = wall.addingTimeInterval(5)
        let plan = FlintSessionAutoClearPlan.make(start: wall, end: end, calendar: .current)
        saved = saved.rearmed(monitorName: "flint.session.repaired", plan: plan, at: wall,
            countsTowardLimit: false, endsAt: end).checkpointed(clock: clock)
        XCTAssertEqual(saved.plannedDuration, 60)
        XCTAssertEqual(saved.logicalRemaining(clock: clock), 5)
        advance(4)
        XCTAssertFalse(saved.isLogicallyExpired(clock: clock))
        advance(1)
        XCTAssertTrue(saved.isLogicallyExpired(clock: clock))
    }

    func testLateCompletionInstantSurvivesRepeatedCheckpointsF2() {
        var saved = session(duration: 60).checkpointed(clock: clock)
        advance(67)
        saved = saved.checkpointed(clock: clock)
        XCTAssertEqual(saved.logicalOverrun(clock: clock), 7)
        advance(3)
        saved = saved.checkpointed(clock: clock)
        XCTAssertEqual(saved.logicalOverrun(clock: clock), 10)
        XCTAssertEqual(wall.addingTimeInterval(-(saved.logicalOverrun(clock: clock) ?? 0)), saved.endsAt)
    }

    func testCheckpointRoundTripAndFiniteBounds() throws {
        let saved = session(duration: 60).checkpointed(clock: clock)
        let decoded = try JSONDecoder().decode(FlintActiveSession.self, from: JSONEncoder().encode(saved))
        XCTAssertEqual(decoded, saved)
        XCTAssertTrue(try XCTUnwrap(decoded.remainingCheckpoint).isValid)
        reboot("boot-2")
        wall.addTimeInterval(-3600)
        XCTAssertEqual(decoded.logicalRemaining(clock: clock), 60)
    }

    func testCheckpointSamplesMonotonicOnceWithoutExtendingTheDeadline() throws {
        let original = session(duration: 60)
        var reads = 0
        let samplingClock = FlintClock(now: { self.wall.addingTimeInterval(10) }, monotonic: {
            defer { reads += 1 }
            return 50_010 + Double(reads)
        }, bootSessionID: { "boot-1" }, calendar: { .current })
        let saved = try XCTUnwrap(original.checkpointed(clock: samplingClock).remainingCheckpoint)
        XCTAssertEqual(reads, 1)
        XCTAssertEqual(saved.monotonic + saved.remaining, 50_060)
    }

    func testOpenEndedSessionNeverAcquiresAFiniteCheckpoint() {
        let open = FlintActiveSession(name: "Easy", startedAt: wall, endsAt: nil,
                                       breakLevel: .easy, monitorName: "flint.session.open")
        XCTAssertEqual(open.checkpointed(clock: clock), open)
        XCTAssertNil(open.logicalRemaining(clock: clock))
    }
}
