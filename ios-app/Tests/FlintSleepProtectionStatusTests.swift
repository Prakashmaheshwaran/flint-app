import XCTest
import FlintCore
@testable import Flint

final class FlintSleepProtectionStatusTests: XCTestCase {
    func testFailedSleepRuleNeverLooksArmedDespiteAUsableWindow() {
        let name = "flint.rule.sleep.night"
        let now = Date()
        let report = FlintArmingHealth.DomainReport(enabled: 1, attempted: 0, armed: 0,
            failures: [.init(activityName: name, reason: "Too many allowed apps")], updatedAt: now)
        XCTAssertEqual(FlintSleepProtectionStatus.issue(activityNames: [name], report: report,
            authorized: true, now: now), "Too many allowed apps")
    }

    func testUncheckedOrUnauthorizedSleepDoesNotLookRegistered() {
        let name = "flint.rule.sleep.night"
        let now = Date()
        XCTAssertNotNil(FlintSleepProtectionStatus.issue(activityNames: [name], report: nil,
                                                       authorized: true, now: now))
        let report = FlintArmingHealth.DomainReport(enabled: 1, attempted: 1, armed: 1,
                                                   failures: [], updatedAt: now, observedActivities: [name])
        XCTAssertNotNil(FlintSleepProtectionStatus.issue(activityNames: [name], report: report,
                                                       authorized: false, now: now))
        XCTAssertNil(FlintSleepProtectionStatus.issue(activityNames: [name], report: report,
                                                    authorized: true, now: now))
    }

    func testStaleAndEmptySleepStatus() {
        let now = Date()
        let name = "flint.rule.sleep.night"
        let stale = FlintArmingHealth.DomainReport(enabled: 1, attempted: 1, armed: 1,
            failures: [], updatedAt: now.addingTimeInterval(-24 * 3600 - 1), observedActivities: [name])
        XCTAssertNotNil(FlintSleepProtectionStatus.issue(activityNames: [name], report: stale,
                                                       authorized: true, now: now))
        XCTAssertNil(FlintSleepProtectionStatus.issue(activityNames: [], report: nil,
                                                    authorized: false, now: now))
    }
}
