import XCTest
import FamilyControls
@testable import FlintCore

final class FlintSelectionValidationTests: XCTestCase {
    func testAppAndWebsiteCapsAreIndependentAt50And51() {
        XCTAssertNil(FlintSelectionValidation.issue(applications: 50, webDomains: 50, categories: 0))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 51, webDomains: 0, categories: 0),
                       .tooManyTokens(count: 51, cap: 50))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 0, webDomains: 51, categories: 0),
                       .tooManyTokens(count: 51, cap: 50))
    }

    func testCategoriesDoNotConsumeTheTokenCap() {
        XCTAssertNil(FlintSelectionValidation.issue(applications: 0, webDomains: 0, categories: 100))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 0, webDomains: 0, categories: 100,
                                                     supportsCategories: false), .emptySelection)
        XCTAssertNil(FlintSelectionValidation.issue(applications: 1, webDomains: 0, categories: 100,
                                                   supportsCategories: false))
    }

    func testEmptySelectionIsOnlyAllowedForAnAllowList() {
        XCTAssertEqual(FlintSelectionValidation.issue(for: FamilyActivitySelection()), .emptySelection)
        XCTAssertNil(FlintSelectionValidation.issue(for: FamilyActivitySelection(), requiresSelection: false))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 51, webDomains: 0, categories: 0,
                                                     requiresSelection: false), .tooManyTokens(count: 51, cap: 50))
    }

    func testSelectionCopyMatchesSessionErrors() {
        XCTAssertEqual(FlintSelectionIssue.emptySelection.message, FlintSessionError.emptySelection.errorDescription)
        XCTAssertEqual(FlintSelectionIssue.tooManyTokens(count: 51, cap: 50).message,
                       FlintSessionError.tooManyTokens(count: 51, cap: 50).errorDescription)
    }

    func testScheduleOverCapRemainsEnabledButNotAttemptedInHealth() {
        let rules = [
            FlintScheduleRule(id: "bad", name: "Bad", schedule: .init(startHour: 9, endHour: 17)),
            FlintScheduleRule(id: "off", name: "Off", schedule: .init(startHour: 9, endHour: 17), enabled: false)
        ]
        let plan = FlintSchedulesController.armPlan(for: rules, selectionIssue: { _ in self.overCap })
        XCTAssertTrue(plan.arm.isEmpty)
        XCTAssertEqual(plan.failures.map(\.ruleID), ["bad"])
        XCTAssertEqual(plan.failures.first?.selectionIssue, overCap)
        let report = FlintSchedulesController.armingHealthReport(
            enabled: plan.arm.count + plan.failures.count, attempted: 0, armed: 0,
            validationFailures: plan.failures, registrationFailures: [])
        assertRejected(report, activity: rules[0].monitorName)
    }

    func testTimeLimitOverCapRemainsEnabledButNotAttemptedInHealth() {
        let rules = [FlintLimitRule(id: "bad", name: "Bad", thresholdMinutes: 30),
                     FlintLimitRule(id: "off", name: "Off", thresholdMinutes: 30, enabled: false)]
        let plan = FlintLimitsController.armPlan(for: rules, selectionIssue: { _ in self.overCap })
        XCTAssertTrue(plan.arm.isEmpty)
        assertRejected(.init(enabled: plan.arm.count + plan.failures.count, attempted: 0,
                             armed: 0, failures: plan.failures), activity: rules[0].activityName)
    }

    func testOpenLimitOverCapRemainsEnabledButNotAttemptedInHealth() {
        let rules = [FlintOpenLimitRule(id: "bad", name: "Bad", opensAllowed: 3),
                     FlintOpenLimitRule(id: "off", name: "Off", opensAllowed: 3, enabled: false)]
        let plan = FlintOpenLimitsController.armPlan(for: rules, selectionIssue: { _ in self.overCap })
        XCTAssertTrue(plan.arm.isEmpty)
        assertRejected(.init(enabled: plan.arm.count + plan.failures.count, attempted: 0,
                             armed: 0, failures: plan.failures), activity: rules[0].activityName)
    }

    func testProductionArmPlansRejectEmptySavedRules() {
        XCTAssertEqual(FlintSchedulesController.armPlan(for: [
            .init(name: "Empty", schedule: .init(startHour: 9, endHour: 17))
        ]).failures.first?.selectionIssue, .emptySelection)
        XCTAssertEqual(FlintLimitsController.armPlan(for: [
            .init(name: "Empty", thresholdMinutes: 30)
        ]).failures.first?.reason, FlintSelectionIssue.emptySelection.message)
        XCTAssertEqual(FlintOpenLimitsController.armPlan(for: [
            .init(name: "Empty", opensAllowed: 3)
        ]).failures.first?.reason, FlintSelectionIssue.emptySelection.message)
    }

    private var overCap: FlintSelectionIssue? {
        FlintSelectionValidation.issue(applications: 0, webDomains: 51, categories: 0)
    }

    private func assertRejected(_ report: FlintArmingHealth.DomainReport, activity: String,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(report.enabled, 1, file: file, line: line)
        XCTAssertEqual(report.attempted, 0, file: file, line: line)
        XCTAssertEqual(report.armed, 0, file: file, line: line)
        XCTAssertEqual(report.failures, [.init(activityName: activity, reason: overCap!.message)],
                       file: file, line: line)
    }
}
