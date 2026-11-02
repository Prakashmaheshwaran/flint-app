import XCTest
@testable import FlintCore

final class FlintRuleStoreKeyTests: XCTestCase {
    func testLegacyKeysAndStrictReadersStayCompatibleAcrossAllRuleWriters() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let defaults = h.defaults
        let group = try XCTUnwrap(FlintGroupStore(suiteName: h.suite))
        let schedule = FlintScheduleRule(id: "schedule", name: "Existing schedule",
            schedule: .init(startHour: 9, endHour: 17), allowListMode: true)
        let limit = FlintLimitRule(id: "limit", name: "Existing limit", thresholdMinutes: 30)
        let open = FlintOpenLimitRule(id: "open", name: "Existing open limit", opensAllowed: 2)
        // Existing installs use these literal keys. A rename must not appear as empty intent.
        defaults.set(try JSONEncoder().encode([schedule]), forKey: "flint.rules")
        defaults.set(try JSONEncoder().encode([limit]), forKey: "flint.limits")
        defaults.set(try JSONEncoder().encode([open]), forKey: "flint.openLimit.rules")
        XCTAssertEqual(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults, recoveryDirectory: h.recoveryDirectory), [schedule])
        XCTAssertEqual(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults, recoveryDirectory: h.recoveryDirectory), [limit])
        XCTAssertEqual(FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults, recoveryDirectory: h.recoveryDirectory), [open])
        XCTAssertEqual(group.loadRules(), [schedule])
        XCTAssertEqual(group.loadLimits(), [limit])
        XCTAssertEqual(FlintOpenLimitRule.loadAll(from: defaults), [open])
        XCTAssertTrue(group.saveRules([]))
        XCTAssertTrue(group.saveLimits([]))
        XCTAssertTrue(FlintOpenLimitRule.saveAll([], to: defaults))
        XCTAssertEqual(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults, recoveryDirectory: h.recoveryDirectory), [])
        XCTAssertEqual(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults, recoveryDirectory: h.recoveryDirectory), [])
        XCTAssertEqual(FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults, recoveryDirectory: h.recoveryDirectory), [])
        XCTAssertTrue(FlintDesiredRules.save([schedule], key: .schedules, to: defaults))
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        XCTAssertTrue(FlintDesiredRules.save([open], key: .openLimits, to: defaults))
        XCTAssertEqual(group.loadRules(), [schedule])
        XCTAssertEqual(group.loadLimits(), [limit])
        XCTAssertEqual(FlintOpenLimitRule.loadAll(from: defaults), [open])
    }
}
