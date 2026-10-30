import XCTest
@testable import FlintCore

final class FlintSleepRuleArbiterTests: XCTestCase {
    func testNewWindDownEasyAndFullAssistHarderDefaultsPC82() throws {
        for (assist, expected) in [(FlintSleepAssistLevel.windDown, BreakLevel.easy), (.fullAssist, .harder)] {
            let h = try RuleHarness(); defer { h.clean() }
            let config = FlintSleepConfig(enabled: true, schedule: .init(startHour: 22, endHour: 7), sleepAssist: assist)
            XCTAssertEqual(FlintSleepRulesController(environment: h.environment).save(config), .applied)
            XCTAssertEqual(h.load(FlintScheduleRule.self).first?.breakLevel, expected)
        }
    }

    func testSleepSaveIsAtomicBatchAndCannotBypassActiveHardcorePC11PC14() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var night = h.schedule(tier: .hardcore); night.id = FlintSleepRulesController.nightID
        let active = h.install(night)
        let config = FlintSleepConfig(enabled: true, schedule: night.schedule, sleepAssist: .fullAssist,
            nightBreakLevel: .hardcore)
        h.defaults.set(try JSONEncoder().encode(config), forKey: FlintSleepRulesController.key)
        var draft = config; draft.enabled = false
        let controller = FlintSleepRulesController(environment: h.environment)
        guard case let .deferRequired(ticket, end) = controller.save(draft) else { return XCTFail() }
        XCTAssertEqual(FlintSleepConfig.load(from: h.defaults), config, "unconfirmed draft cannot persist")
        XCTAssertEqual(controller.save(draft, confirmation: ticket, origin: .interactive), .queued(until: end))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.enabled, true)
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.activityName, active.activityName)
        h.advance(to: end)
        _ = FlintRuleMonitor(environment: h.environment).handle(.end, activity: active.activityName)
        XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty)
    }

    func testSleepTierIncreaseNeedsConfirmationAndStaleConfigIsRefused() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var config = FlintSleepConfig(enabled: true, schedule: .init(startHour: 22, endHour: 7), sleepAssist: .windDown,
            nightBreakLevel: .hardcore)
        let controller = FlintSleepRulesController(environment: h.environment)
        guard case let .confirmationRequired(ticket, raisesTier: true) = controller.save(config) else { return XCTFail() }
        XCTAssertEqual(controller.save(config, confirmation: ticket, origin: .interactive), .applied)
        config.nightBreakLevel = .easy
        config.revision = 99
        XCTAssertEqual(controller.save(config), .staleRevision)
    }

    func testMalformedSleepConfigIsUnavailableAndCannotDeleteRunningWindows() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var night = h.schedule(tier: .hardcore); night.id = FlintSleepRulesController.nightID
        _ = h.install(night)
        h.defaults.set(Data("{partial".utf8), forKey: FlintSleepRulesController.key)
        XCTAssertEqual(FlintSleepRulesController(environment: h.environment).save(.default), .unavailable)
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
    }

    /// R-27: a tier raised in Rules is the tier in force. A Sleep draft adopts it, so the next
    /// Sleep save (here a bedtime change) keeps it instead of reverting to the stored tier.
    func testTierChangedInRulesSurvivesTheNextSleepSaveR27() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let controller = FlintSleepRulesController(environment: h.environment)
        let config = FlintSleepConfig(enabled: true, schedule: .init(startHour: 22, endHour: 7), sleepAssist: .windDown)
        XCTAssertEqual(controller.save(config), .applied)
        var night = try XCTUnwrap(h.load(FlintScheduleRule.self).first { $0.id == FlintSleepRulesController.nightID })
        XCTAssertEqual(night.breakLevel, .easy)

        // Raise it in Rules; the saved Sleep config still says Easy.
        night.breakLevel = .harder
        let arbiter = FlintRuleArbiter(environment: h.environment)
        var result = arbiter.upsert(night)
        if case let .confirmationRequired(ticket, _) = result { result = arbiter.upsert(night, confirmation: ticket) }
        XCTAssertEqual(result, .applied)
        let stored = FlintSleepConfig.load(from: h.defaults)
        XCTAssertEqual(stored.nightBreakLevel, .easy)

        var draft = FlintSleepRulesController.adoptingTiersInForce(stored, rules: h.load(FlintScheduleRule.self))
        XCTAssertEqual(draft.nightBreakLevel, .harder, "the draft shows the tier in force")
        draft.schedule = .init(startHour: 23, endHour: 7)
        var saved = controller.save(draft)
        if case let .confirmationRequired(ticket, _) = saved { saved = controller.save(draft, confirmation: ticket, origin: .interactive) }
        XCTAssertEqual(saved, .applied)
        XCTAssertEqual(h.load(FlintScheduleRule.self).first { $0.id == FlintSleepRulesController.nightID }?.breakLevel, .harder,
                       "a Sleep save never reverts a tier changed in Rules")
    }

    /// A window with a queued change keeps the draft's tier: that is the queued intent.
    func testQueuedSleepChangeKeepsTheDraftTierR27() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var night = h.schedule(tier: .hardcore); night.id = FlintSleepRulesController.nightID
        _ = h.install(night)
        let config = FlintSleepConfig(enabled: true, schedule: night.schedule, sleepAssist: .fullAssist,
            nightBreakLevel: .hardcore)
        h.defaults.set(try JSONEncoder().encode(config), forKey: FlintSleepRulesController.key)
        var draft = config; draft.nightBreakLevel = .easy
        let controller = FlintSleepRulesController(environment: h.environment)
        guard case let .deferRequired(ticket, end) = controller.save(draft) else { return XCTFail() }
        XCTAssertEqual(controller.save(draft, confirmation: ticket, origin: .interactive), .queued(until: end))
        let rules = h.load(FlintScheduleRule.self)
        XCTAssertNotNil(rules.first?.protection?.queuedChange)
        let reopened = FlintSleepRulesController.adoptingTiersInForce(FlintSleepConfig.load(from: h.defaults), rules: rules)
        XCTAssertEqual(reopened.nightBreakLevel, .easy, "the queued weakening is not undone by reopening Sleep")
    }
}

