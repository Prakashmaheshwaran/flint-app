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
        // R-26 / PC-53: a new or edited allow list must name the apps to keep.
        XCTAssertEqual(FlintSelectionValidation.issue(for: FamilyActivitySelection(), context: .allowList), .emptyAllowList)
        XCTAssertNil(FlintSelectionValidation.armIssue(for: FamilyActivitySelection(), context: .allowList),
                     "an already saved empty allow list keeps arming (§12)")
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

    /// R-26 (ADR-012 §7, PC-53): Flint can't prove which system apps an `.all(except:)` shield
    /// leaves reachable, so the broadest unverifiable allow list is refused at selection time.
    /// A saved one from an earlier build keeps running (§12) with a needs-attention note.
    func testEmptyAllowListIsRefusedForNewSelectionsButKeepsArmingWhenSaved() throws {
        XCTAssertEqual(FlintSelectionIssue.emptyAllowList.message,
                       "Choose the apps to keep available, for example Phone and Messages.")
        XCTAssertEqual(FlintSelectionValidation.issue(applications: 0, webDomains: 3, categories: 2, context: .allowList),
                       .emptyAllowList, "websites and categories are never allow-list exceptions")
        XCTAssertNil(FlintSelectionValidation.issue(applications: 1, webDomains: 0, categories: 0, context: .allowList))
        XCTAssertEqual(FlintSelectionValidation.armIssue(applications: 51, webDomains: 0, categories: 0, context: .allowList),
                       .tooManyTokens(count: 51, cap: 50), "only the empty-allow refusal is relaxed for saved rules")
        XCTAssertTrue(FlintSelectionValidation.needsAllowedApps(applications: 0, allowList: true))
        XCTAssertFalse(FlintSelectionValidation.needsAllowedApps(applications: 1, allowList: true))
        XCTAssertFalse(FlintSelectionValidation.needsAllowedApps(applications: 0, allowList: false))

        // A main-shaped saved Sleep rule: an allow list that keeps no app.
        let json = try RuleFixture.object("legacy-sleep-hardcore")
        let saved = try JSONDecoder().decode(FlintScheduleRule.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertTrue(saved.allowListMode)
        let plan = FlintSchedulesController.armPlan(for: [saved])
        XCTAssertEqual(plan.arm.map(\.id), [saved.id], "not silently disabled or torn down")
        XCTAssertTrue(plan.failures.isEmpty)
        XCTAssertTrue(plan.storesToClear.isEmpty)
        XCTAssertEqual(FlintSelectionValidation.issue(for: saved.selection, context: .allowList), .emptyAllowList,
                       "its next edit must choose the apps to keep")
    }

    /// The production arbiter refuses the empty allow list on create and on any enabled edit,
    /// while a saved one stays installed and can still be turned off.
    func testArbiterRefusesNewAndEditedEmptyAllowListButAllowsTurningItOff() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var env = h.environment
        env.validateSelection = { FlintSelectionValidation.issue(for: $0, context: $1) }
        let arbiter = FlintRuleArbiter(environment: env)
        let message = FlintSelectionIssue.emptyAllowList.message

        var fresh = h.schedule(tier: .easy); fresh.id = "new-allow"
        XCTAssertEqual(arbiter.upsert(fresh, creating: true), .invalid(message))

        let saved = h.install(h.schedule(tier: .easy))
        XCTAssertTrue(saved.allowListMode)
        XCTAssertTrue(saved.selection.applicationTokens.isEmpty)
        var retimed = saved; retimed.schedule = .init(startHour: 10, endHour: 18)
        XCTAssertEqual(arbiter.upsert(retimed), .invalid(message), "the next edit must choose the apps to keep")
        // Turning it off takes the ordinary Easy path; the selection never blocks it.
        assertOrdinaryOutcome(arbiter.setEnabled(FlintScheduleRule.self, id: saved.id, enabled: false,
                                                 revision: saved.revision, confirmation: nil))
    }

    /// Turning only one Sleep window off still saves the other, so it needs the apps to keep;
    /// turning Sleep off removes both and is never blocked (documented in R26-03).
    func testSleepPartialTurnOffNeedsAppsButFullOffDoesNot() throws {
        let h = try RuleHarness(); defer { h.clean() }
        // Saved by an earlier build with nothing chosen (the harness doesn't validate selections).
        let earlier = FlintSleepRulesController(environment: h.environment)
        let both = FlintSleepConfig(enabled: true, schedule: .init(startHour: 22, endHour: 7),
            sleepAssist: .windDown, morningAssist: .fullAssist)
        var result = earlier.save(both)
        if case let .confirmationRequired(ticket, _) = result {
            result = earlier.save(both, confirmation: ticket, origin: .interactive)
        }
        XCTAssertEqual(result, .applied)
        XCTAssertEqual(Set(h.load(FlintScheduleRule.self).map(\.id)),
                       [FlintSleepRulesController.nightID, FlintSleepRulesController.morningID])
        let saved = FlintSleepConfig.load(from: h.defaults)

        var env = h.environment
        env.validateSelection = { FlintSelectionValidation.issue(for: $0, context: $1) }
        let controller = FlintSleepRulesController(environment: env)
        var nightOff = saved; nightOff.sleepAssist = .off
        XCTAssertEqual(controller.save(nightOff), .invalid(FlintSleepRulesController.allowedAppsRequired))
        XCTAssertEqual(h.load(FlintScheduleRule.self).count, 2, "nothing changes on a refused save")
        var off = saved; off.enabled = false
        assertOrdinaryOutcome(controller.save(off))
    }

    private func assertOrdinaryOutcome(_ result: FlintRuleMutationResult, line: UInt = #line) {
        switch result {
        case .applied, .confirmationRequired: break
        default: XCTFail("expected the ordinary tier outcome, got \(result)", line: line)
        }
    }

    /// The production environment carries the refusal; RuleHarness stubs it away.
    func testLiveRuleEnvironmentRefusesAnEmptyAllowList() {
        XCTAssertEqual(FlintRuleReloadEnvironment.live.validateSelection(FamilyActivitySelection(), .allowList),
                       .emptyAllowList)
        XCTAssertEqual(FlintRuleReloadEnvironment.live.validateSelection(FamilyActivitySelection(), .blockList),
                       .emptySelection)
    }

    /// Sleep has no editor pre-gate, so the controller refusal must name where to choose apps.
    /// Turning Sleep off removes its windows and is never blocked by the selection.
    func testSleepRefusesAnEmptyAllowListButCanStillBeTurnedOff() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var env = h.environment
        env.validateSelection = { FlintSelectionValidation.issue(for: $0, context: $1) }
        let controller = FlintSleepRulesController(environment: env)
        for assist in [FlintSleepAssistLevel.windDown, .fullAssist] {
            let config = FlintSleepConfig(enabled: true, schedule: .init(startHour: 22, endHour: 7), sleepAssist: assist)
            XCTAssertEqual(controller.save(config), .invalid(FlintSleepRulesController.allowedAppsRequired))
            XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty, "nothing is written")
            XCTAssertNil(h.defaults.object(forKey: FlintSleepRulesController.key))
        }
        let morningOnly = FlintSleepConfig(enabled: true, schedule: .init(startHour: 22, endHour: 7),
            morningAssist: .fullAssist)
        XCTAssertEqual(controller.save(morningOnly), .invalid(FlintSleepRulesController.allowedAppsRequired))

        // A Sleep config saved by an earlier build with no allowed app.
        var night = h.schedule(tier: .easy); night.id = FlintSleepRulesController.nightID
        _ = h.install(night)
        let saved = FlintSleepConfig(enabled: true, schedule: night.schedule, sleepAssist: .windDown,
            nightBreakLevel: .easy)
        h.defaults.set(try JSONEncoder().encode(saved), forKey: FlintSleepRulesController.key)
        var retimed = saved; retimed.schedule = .init(startHour: 23, endHour: 7)
        XCTAssertEqual(controller.save(retimed), .invalid(FlintSleepRulesController.allowedAppsRequired))
        var off = saved; off.enabled = false
        assertOrdinaryOutcome(controller.save(off))
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
