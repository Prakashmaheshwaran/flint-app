import XCTest
import FamilyControls
@testable import FlintCore

final class FlintWebReconcileEligibilityTests: XCTestCase {
    func testFailedAndUnobservedSchedulesAreRetainedForWebCleanup() {
        let rules = [rule("ok"), rule("failed"), rule("unobserved"), rule("off", enabled: false)]
        let report = FlintArmingHealth.DomainReport(enabled: 3, attempted: 3, armed: 2,
            failures: [.init(activityName: rules[1].monitorName, reason: "refused")],
            observedActivities: [rules[0].monitorName])
        let eligible = FlintWebReconcileEligibility.rules(rules, report: report)
        XCTAssertEqual(eligible.map(\.id), rules.map(\.id), "retain every cause so stale web keys are cleared")
        XCTAssertEqual(eligible.map(\.enabled), [true, false, false, false])
        XCTAssertEqual(rules.map(\.enabled), [true, true, true, false], "never rewrite persisted intent")
    }

    func testMissingLegacyObservationDoesNotReapplyScheduleWebKeys() {
        XCTAssertFalse(FlintWebReconcileEligibility.rules([rule("x")], report: nil)[0].enabled)
        let legacy = FlintArmingHealth.DomainReport(enabled: 1, attempted: 1, armed: 1, failures: [])
        XCTAssertFalse(FlintWebReconcileEligibility.rules([rule("x")], report: legacy)[0].enabled)
    }

    func testTimedSessionNeedsItsOwnObservedAutoEndName() {
        let session = session(endsAt: Date().addingTimeInterval(3600))
        XCTAssertNil(FlintWebReconcileEligibility.session(session, report: nil))
        let other = FlintArmingHealth.DomainReport(enabled: 1, attempted: 1, armed: 1, failures: [],
                                                    observedActivities: ["flint.session.old"])
        XCTAssertNil(FlintWebReconcileEligibility.session(session, report: other))
        let current = FlintArmingHealth.DomainReport(enabled: 1, attempted: 1, armed: 1, failures: [],
                                                      observedActivities: [session.monitorName])
        XCTAssertEqual(FlintWebReconcileEligibility.session(session, report: current), session)
    }

    func testOpenEndedSessionDoesNotRequireAnAutoEndRegistration() {
        let session = session(endsAt: nil)
        XCTAssertEqual(FlintWebReconcileEligibility.session(session, report: nil), session)
        XCTAssertNil(FlintWebReconcileEligibility.session(nil, report: nil))
    }

    private func rule(_ id: String, enabled: Bool = true) -> FlintScheduleRule {
        .init(id: id, name: id, schedule: .init(startHour: 9, endHour: 17), allowListMode: true, enabled: enabled)
    }

    private func session(endsAt: Date?) -> FlintActiveSession {
        .init(name: "Focus", startedAt: Date(), endsAt: endsAt, breakLevel: .easy, monitorName: "flint.session.current")
    }
}
