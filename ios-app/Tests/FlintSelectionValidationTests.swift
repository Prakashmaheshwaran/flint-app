import XCTest
import FamilyControls
@testable import FlintCore

final class FlintSelectionValidationTests: XCTestCase {
    func testFlintValidatorAccepts50AndRejects51InEachIndividualTokenSet() {
        XCTAssertNil(FlintSelectionValidation.issue(applications: 50, webDomains: 50, categories: 0))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 51, webDomains: 0, categories: 0),
                       .tooManyTokens(count: 51, cap: 50))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 0, webDomains: 51, categories: 0),
                       .tooManyTokens(count: 51, cap: 50))
    }

    func testCategoriesDoNotConsumeTheTokenCap() {
        XCTAssertNil(FlintSelectionValidation.issue(applications: 0, webDomains: 0, categories: 100))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 0, webDomains: 0, categories: 100,
                                                     context: .openLimit), .emptySelection)
        XCTAssertNil(FlintSelectionValidation.issue(applications: 1, webDomains: 0, categories: 100,
                                                   context: .openLimit))
    }

    func testEmptySelectionIsOnlyAllowedForAnAllowList() {
        XCTAssertEqual(FlintSelectionValidation.issue(for: FamilyActivitySelection()), .emptySelection)
        XCTAssertNil(FlintSelectionValidation.issue(for: FamilyActivitySelection(), context: .allowList))
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 51, webDomains: 0, categories: 0,
                                                     context: .allowList), .tooManyTokens(count: 51, cap: 50))
        XCTAssertNil(FlintSelectionValidation.issue(applications: 50, webDomains: 51, categories: 100,
                                                   context: .allowList), "allow-list writes only app exceptions")
        let plan = FlintSchedulesController.armPlan(for: [
            .init(name: "Allow nothing extra", schedule: .init(startHour: 9, endHour: 17), allowListMode: true)
        ])
        XCTAssertEqual(plan.arm.count, 1)
        XCTAssertTrue(plan.failures.isEmpty)
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
        XCTAssertEqual(plan.storesToClear, [rules[0].storeName])
        XCTAssertEqual(plan.enabled, 1)
        let report = FlintSchedulesController.armingHealthReport(
            enabled: plan.enabled, attempted: 0, armed: 0,
            validationFailures: plan.failures, registrationFailures: [])
        assertRejected(report, activity: rules[0].monitorName)
    }

    func testTimeLimitOverCapRemainsEnabledButNotAttemptedInHealth() {
        let rules = [FlintLimitRule(id: "bad", name: "Bad", thresholdMinutes: 30),
                     FlintLimitRule(id: "off", name: "Off", thresholdMinutes: 30, enabled: false)]
        let plan = FlintLimitsController.armPlan(for: rules, selectionIssue: { _ in self.overCap })
        XCTAssertTrue(plan.arm.isEmpty)
        XCTAssertEqual(plan.storesToClear, [rules[0].storeName])
        XCTAssertEqual(plan.enabled, 1)
        assertRejected(.init(enabled: plan.enabled, attempted: 0,
                             armed: 0, failures: plan.failures), activity: rules[0].activityName)
    }

    func testOpenLimitOverCapRemainsEnabledButNotAttemptedInHealth() {
        let rules = [FlintOpenLimitRule(id: "bad", name: "Bad", opensAllowed: 3),
                     FlintOpenLimitRule(id: "off", name: "Off", opensAllowed: 3, enabled: false)]
        let plan = FlintOpenLimitsController.armPlan(for: rules, selectionIssue: { _ in self.overCap })
        XCTAssertTrue(plan.arm.isEmpty)
        XCTAssertEqual(plan.storesToClear, [rules[0].storeName])
        XCTAssertEqual(plan.enabled, 1)
        assertRejected(.init(enabled: plan.enabled, attempted: 0,
                             armed: 0, failures: plan.failures), activity: rules[0].activityName,
                       context: .openLimit)
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

    func testContextSpecificRemediesDoNotSuggestUnsupportedCategories() {
        let issue = FlintSelectionIssue.tooManyTokens(count: 51, cap: 50)
        XCTAssertTrue(issue.message.contains("use a category instead"))
        XCTAssertFalse(issue.message(for: .allowList).contains("category"))
        XCTAssertFalse(issue.message(for: .openLimit).contains("use a category"))
        XCTAssertTrue(issue.message(for: .openLimit).contains("cannot be open-limited"))
    }

    func testInvalidWindowAlsoClearsTheOldScheduleStore() {
        let rule = FlintScheduleRule(id: "invalid", name: "Invalid", schedule: .init(startHour: 9, endHour: 9))
        let plan = FlintSchedulesController.armPlan(for: [rule])
        XCTAssertEqual(plan.storesToClear, [rule.storeName])
        XCTAssertEqual(plan.enabled, 1)
    }

    func testEnabledCountsComeFromTheProductionPlanForEveryDomain() {
        let schedules = FlintSchedulesController.armPlan(for: [
            .init(name: "Good", schedule: .init(startHour: 9, endHour: 17), allowListMode: true),
            .init(name: "Invalid", schedule: .init(startHour: 9, endHour: 9)),
            .init(name: "Off", schedule: .init(startHour: 9, endHour: 9), enabled: false)
        ])
        XCTAssertEqual(schedules.enabled, 2)
        let limits = FlintLimitsController.armPlan(for: [
            .init(id: "good", name: "Good", thresholdMinutes: 30),
            .init(id: "bad", name: "Bad", thresholdMinutes: 30),
            .init(id: "off", name: "Off", thresholdMinutes: 30, enabled: false)
        ], selectionIssue: { $0.id == "good" ? nil : .emptySelection })
        XCTAssertEqual(limits.enabled, 2)
        let opens = FlintOpenLimitsController.armPlan(for: [
            .init(id: "good", name: "Good", opensAllowed: 3),
            .init(id: "bad", name: "Bad", opensAllowed: 3),
            .init(id: "off", name: "Off", opensAllowed: 3, enabled: false)
        ], selectionIssue: { $0.id == "good" ? nil : .emptySelection })
        XCTAssertEqual(opens.enabled, 2)
    }

    private func assertRejected(_ report: FlintArmingHealth.DomainReport, activity: String,
                                context: FlintSelectionContext = .blockList,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(report.enabled, 1, file: file, line: line)
        XCTAssertEqual(report.attempted, 0, file: file, line: line)
        XCTAssertEqual(report.armed, 0, file: file, line: line)
        XCTAssertEqual(report.failures, [.init(activityName: activity, reason: overCap!.message(for: context))],
                       file: file, line: line)
    }
}
