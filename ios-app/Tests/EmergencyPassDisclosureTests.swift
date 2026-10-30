import XCTest
import FlintCore
@testable import Flint

final class EmergencyPassDisclosureTests: XCTestCase {
    func testPINRecoveryDisclosuresNeverIncludePrivateNames() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let session = FlintActiveSession(name: "private session name", startedAt: now,
            endsAt: now.addingTimeInterval(1800), breakLevel: .hardcore, monitorName: "session")
        let rule = FlintPassCommitment(id: "private-id", name: "private rule name", kind: .sleep,
            naturalStart: now, naturalEnd: now.addingTimeInterval(3600),
            storeName: "flint.private-id", activityName: "private-activity", tier: .harder)
        let sessionLine = EmergencyPassDisclosure.session(session, redacted: true)
        let ruleLine = EmergencyPassDisclosure.rule(rule, redacted: true)
        XCTAssertTrue(sessionLine.contains("Hardcore"))
        XCTAssertTrue(ruleLine.contains("Harder"))
        for line in [sessionLine, ruleLine] {
            XCTAssertFalse(line.contains("private"))
            XCTAssertFalse(line.contains("Sleep"))
        }
        XCTAssertEqual(EmergencyPassDisclosure.session(session, redacted: false), session.name)
        XCTAssertTrue(EmergencyPassDisclosure.rule(rule, redacted: false).contains(rule.name))
    }

    func testPINPassDisclosureUsesLogicalEndAfterWallClockJump() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let now = start.addingTimeInterval(86400)
        let session = FlintActiveSession(name: "private session", startedAt: start,
            endsAt: start.addingTimeInterval(1800), breakLevel: .hardcore, monitorName: "session",
            monotonicStart: 100, bootSessionID: "boot")
        let clock = FlintClock(now: { now }, monotonic: { 160 }, bootSessionID: { "boot" }, calendar: { .current })
        let expected = FlintEmergencyPassStatus.dateText(now.addingTimeInterval(1740))
        XCTAssertEqual(EmergencyPassDisclosure.session(session, redacted: true, clock: clock), "Hardcore · \(expected)")
    }
}
