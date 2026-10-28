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
        XCTAssertEqual(controller.save(draft, confirmation: ticket), .queued(until: end))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.enabled, true)
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.activityName, active.activityName)
        h.now = end
        _ = FlintRuleMonitor(environment: h.environment).handle(.end, activity: active.activityName)
        XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty)
    }

    func testSleepTierIncreaseNeedsConfirmationAndStaleConfigIsRefused() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var config = FlintSleepConfig(enabled: true, schedule: .init(startHour: 22, endHour: 7), sleepAssist: .windDown,
            nightBreakLevel: .hardcore)
        let controller = FlintSleepRulesController(environment: h.environment)
        guard case let .confirmationRequired(ticket, raisesTier: true) = controller.save(config) else { return XCTFail() }
        XCTAssertEqual(controller.save(config, confirmation: ticket), .applied)
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
}
