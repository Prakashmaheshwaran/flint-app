import XCTest
import FamilyControls
import FlintCore

/// `DeviceActivityCenter.startMonitoring` throws on a window it won't accept, and Flint arms with
/// `try?` — so an unregistrable window used to save, show an ON toggle, and block nothing. These
/// tests pin the contract that now decides, before arming, which windows iOS will take.
final class FlintScheduleValidationTests: XCTestCase {

    // MARK: Window arithmetic

    func testMinuteOfDayAccessors() {
        let schedule = FlintSchedule(startHour: 9, startMinute: 30, endHour: 17, endMinute: 45)
        XCTAssertEqual(schedule.startMinuteOfDay, 9 * 60 + 30)
        XCTAssertEqual(schedule.endMinuteOfDay, 17 * 60 + 45)
    }

    func testSameDayWindowIsValidAndNotOvernight() {
        let schedule = FlintSchedule(startHour: 9, endHour: 17)
        XCTAssertEqual(schedule.issues, [])
        XCTAssertTrue(schedule.isValid)
        XCTAssertFalse(schedule.isOvernight)
        XCTAssertEqual(schedule.windowMinutes, 8 * 60)
    }

    func testOvernightWindowIsValidAndWrapsPastMidnight() {
        // 22:00 → 07:00 is the bedtime shape: DeviceActivity reads an earlier intervalEnd as
        // ending the next day, so this must register, not be mistaken for a negative window.
        let schedule = FlintSchedule(startHour: 22, endHour: 7)
        XCTAssertEqual(schedule.issues, [])
        XCTAssertTrue(schedule.isOvernight)
        XCTAssertEqual(schedule.windowMinutes, 9 * 60)
    }

    func testWidestDailyWindowIsValid() {
        // The "Social detox" preset's always-on adaptation. 00:00–23:59, not 00:00–00:00.
        let schedule = FlintSchedule(startHour: 0, startMinute: 0, endHour: 23, endMinute: 59)
        XCTAssertEqual(schedule.issues, [])
        XCTAssertFalse(schedule.isOvernight)
        XCTAssertEqual(schedule.windowMinutes, 24 * 60 - 1)
    }

    // MARK: Rejections

    func testZeroLengthWindowIsRejected() {
        // There is no 24-hour window: start == end is a degenerate interval, not "all day".
        let schedule = FlintSchedule(startHour: 9, startMinute: 0, endHour: 9, endMinute: 0)
        XCTAssertEqual(schedule.issues, [.zeroLengthWindow])
        XCTAssertEqual(schedule.windowMinutes, 0)
        XCTAssertFalse(schedule.isOvernight, "start == end never wraps")
    }

    func testWindowShorterThanFifteenMinutesIsRejectedAtTheBoundary() {
        let tooShort = FlintSchedule(startHour: 9, startMinute: 0, endHour: 9, endMinute: 14)
        XCTAssertEqual(tooShort.issues, [.windowTooShort])

        let exactlyMinimum = FlintSchedule(startHour: 9, startMinute: 0, endHour: 9, endMinute: 15)
        XCTAssertEqual(exactlyMinimum.issues, [], "15 minutes is DeviceActivity's floor, inclusive")
        XCTAssertEqual(exactlyMinimum.windowMinutes, FlintSchedule.minimumWindowMinutes)
    }

    func testShortWindowIsMeasuredAcrossMidnightNotAsNegativeTime() {
        // 23:50 → 00:05 is fifteen real minutes. Naive `end - start` would call it -1425.
        let schedule = FlintSchedule(startHour: 23, startMinute: 50, endHour: 0, endMinute: 5)
        XCTAssertEqual(schedule.windowMinutes, 15)
        XCTAssertEqual(schedule.issues, [])

        let oneMinuteShorter = FlintSchedule(startHour: 23, startMinute: 51, endHour: 0, endMinute: 5)
        XCTAssertEqual(oneMinuteShorter.issues, [.windowTooShort])
    }

    func testOutOfRangeEndpointsAreRejectedWithoutComputingALength() {
        // Windows are decoded from the App Group; a truncated or hand-edited payload can carry
        // an hour of 30. Report the endpoint, not a meaningless duration derived from it.
        for schedule in [
            FlintSchedule(startHour: 30, endHour: 7),
            FlintSchedule(startHour: -1, endHour: 7),
            FlintSchedule(startHour: 9, startMinute: 60, endHour: 17),
            FlintSchedule(startHour: 9, endHour: 24),
            FlintSchedule(startHour: 9, endHour: 17, endMinute: -5),
        ] {
            XCTAssertEqual(schedule.issues, [.invalidTime])
            XCTAssertEqual(schedule.windowMinutes, 0)
            XCTAssertFalse(schedule.isOvernight)
            XCTAssertFalse(schedule.hasValidEndpoints)
        }
    }

    func testDaysOutsideCalendarNumberingAreRejected() {
        // Calendar weekdays are 1 (Sunday) … 7 (Saturday). An 0/8 slips past `appliesOn`, which
        // then never matches — the rule would sit enabled and never fire.
        XCTAssertEqual(FlintSchedule(daysOfWeek: [0], startHour: 9, endHour: 17).issues, [.invalidDays])
        XCTAssertEqual(FlintSchedule(daysOfWeek: [8], startHour: 9, endHour: 17).issues, [.invalidDays])
        XCTAssertEqual(FlintSchedule(daysOfWeek: [2, 9], startHour: 9, endHour: 17).issues, [.invalidDays])
        XCTAssertEqual(FlintSchedule(daysOfWeek: [1, 7], startHour: 9, endHour: 17).issues, [])
        XCTAssertEqual(FlintSchedule(startHour: 9, endHour: 17).issues, [], "empty = every day")
    }

    func testTimeAndDayProblemsAreReportedTogetherTimeFirst() {
        // The two are independent dimensions; the editor shows the first, so time leads.
        let schedule = FlintSchedule(daysOfWeek: [8], startHour: 30, endHour: 7)
        XCTAssertEqual(schedule.issues, [.invalidTime, .invalidDays])
    }

    func testOnlyOneTimeIssueIsReportedAtATime() {
        // A window can't be both zero-length and too short — the length check is an else-if chain.
        let zero = FlintSchedule(startHour: 9, endHour: 9)
        XCTAssertEqual(zero.issues.count, 1)
    }

    func testEveryIssueHasANonEmptyUserFacingMessage() {
        for issue in FlintScheduleIssue.allCases {
            XCTAssertFalse(issue.message.isEmpty, "\(issue.rawValue) must be explainable to a user")
        }
        XCTAssertTrue(
            FlintScheduleIssue.windowTooShort.message.contains("\(FlintSchedule.minimumWindowMinutes)"),
            "the too-short message must name the actual floor"
        )
    }

    // MARK: Arm plan — what reload() hands DeviceActivity

    func testArmPlanArmsOnlyEnabledRulesWithRegistrableWindows() {
        let good = FlintScheduleRule(id: "ok", name: "Work", schedule: FlintSchedule(startHour: 9, endHour: 17))
        let bad = FlintScheduleRule(id: "bad", name: "Broken", schedule: FlintSchedule(startHour: 9, endHour: 9))

        let plan = FlintSchedulesController.armPlan(for: [good, bad])
        XCTAssertEqual(plan.arm.map(\.id), ["ok"])
        XCTAssertEqual(plan.failures, [
            FlintScheduleArmFailure(ruleID: "bad", ruleName: "Broken", issue: .zeroLengthWindow),
        ])
    }

    func testArmPlanIgnoresDisabledRulesEvenWhenTheirWindowIsInvalid() {
        // A rule the user switched off isn't lying to anyone — it must not raise a warning.
        let off = FlintScheduleRule(
            id: "off", name: "Off", schedule: FlintSchedule(startHour: 9, endHour: 9), enabled: false
        )
        let plan = FlintSchedulesController.armPlan(for: [off])
        XCTAssertTrue(plan.arm.isEmpty)
        XCTAssertTrue(plan.failures.isEmpty)
    }

    func testArmPlanReportsTheFirstIssueForARuleBrokenTwoWays() {
        let rule = FlintScheduleRule(
            id: "x", name: "Two ways", schedule: FlintSchedule(daysOfWeek: [8], startHour: 30, endHour: 7)
        )
        XCTAssertEqual(FlintSchedulesController.armPlan(for: [rule]).failures.first?.issue, .invalidTime)
    }

    func testArmFailureFallsBackToAnOSRefusalMessage() {
        // issue == nil means the window passed our checks and DeviceActivityCenter still threw.
        let failure = FlintScheduleArmFailure(ruleID: "x", ruleName: "Work", issue: nil)
        XCTAssertFalse(failure.message.isEmpty)
        XCTAssertNotEqual(failure.message, FlintScheduleIssue.invalidTime.message)
    }
}
