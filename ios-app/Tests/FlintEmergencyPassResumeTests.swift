import XCTest
import FamilyControls
@testable import FlintCore

/// Exercises the shared rule registrars/writers through their injected environment. These
/// are adapter tests, not evidence that DeviceActivity delivers while a device is locked.
final class FlintEmergencyPassResumeTests: XCTestCase {
    func testRecoveryEndResumesOnlyNaturallyActiveRulesIncludingStartsInsidePC42() throws {
        let h = try RuleHarness(); defer { h.clean() }
        func rule(_ id: String, start: TimeInterval, end: TimeInterval) -> FlintScheduleRule {
            .init(id: id, name: id, schedule: .init(startHour: 9, endHour: 17), allowListMode: true,
                protection: .init(timeZoneID: "UTC"),
                oneShot: .init(start: h.now.addingTimeInterval(start), end: h.now.addingTimeInterval(end)))
        }
        let rules = [rule("ends-inside", start: -300, end: 900), rule("continues", start: -300, end: 7200),
                     rule("starts-inside", start: 900, end: 7200), rule("missed", start: 900, end: 2700)]
        XCTAssertTrue(FlintDesiredRules.save(rules, key: .schedules, to: h.defaults))
        XCTAssertTrue(FlintWebRestrictions(applyDuringSchedules: true).save(to: h.defaults))
        let writer = PassResumeWebWriter()
        h.advance(3600)
        try FlintEmergencyPassRuleEffects.resume(state: h.ledger.read().get(), at: .init(clock: h.clock),
            environment: h.environment, web: .init(writer: writer, clock: h.clock))
        XCTAssertEqual(Set(h.appliedSchedules.map(\.id)), ["continues", "starts-inside"])
        XCTAssertEqual(Set(writer.applied), ["flint.continues", "flint.starts-inside"])
        XCTAssertTrue(h.cleared.contains("flint.ends-inside"))
        XCTAssertTrue(h.cleared.contains("flint.missed"))
        XCTAssertFalse(h.center.starts.contains { $0.contains("ends-inside") || $0.contains("missed") })
        XCTAssertEqual(h.load(FlintScheduleRule.self).map(\.oneShot), rules.map(\.oneShot), "natural ends must not move")
    }

    func testResumeRegistrationFailureNeverReportsTheCauseAsReapplied() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        h.center.held.removeValue(forKey: .init(rule.activityName))
        h.center.refused.insert(rule.activityName)
        let writer = PassResumeWebWriter()
        XCTAssertThrowsError(try FlintEmergencyPassRuleEffects.resume(state: h.ledger.read().get(),
            at: .init(clock: h.clock), environment: h.environment, web: .init(writer: writer, clock: h.clock))) {
            XCTAssertEqual($0 as? FlintEmergencyPassError, .applying)
        }
        XCTAssertTrue(h.appliedSchedules.isEmpty)
        XCTAssertTrue(writer.applied.isEmpty)
        XCTAssertTrue(h.reports["schedules"]?.failures.contains { $0.activityName == rule.activityName } == true)
    }

    func testReachedTimeBudgetResumesFromDurableProofWithClearedNativeStore() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
        rule.selection.applicationTokens = [try fictionalRuleToken("pass-time")]
        let saved = h.install(rule)
        XCTAssertFalse(h.timeLimitExhausted, "native store is cleared by recovery")
        XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(rule: saved, at: .init(clock: h.clock), ledger: h.ledger))
        try FlintEmergencyPassRuleEffects.resume(state: h.ledger.peek().get(), at: .init(clock: h.clock),
            environment: h.environment, web: .init(writer: PassResumeWebWriter(), clock: h.clock))
        XCTAssertEqual(h.appliedLimits.map(\.id), [saved.id])
        XCTAssertEqual(h.load(FlintLimitRule.self).first?.thresholdMinutes, 30)
    }

    func testUnspentTimeBudgetStaysUnshieldedWhileOpenLimitResumes() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var time = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
        time.selection.applicationTokens = [try fictionalRuleToken("pass-time-unspent")]
        _ = h.install(time)
        var open = FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3, protection: .init(timeZoneID: "UTC"))
        open.selection.applicationTokens = [try fictionalRuleToken("pass-open")]
        let saved = h.install(open)
        try FlintEmergencyPassRuleEffects.resume(state: h.ledger.read().get(), at: .init(clock: h.clock),
            environment: h.environment, web: .init(writer: PassResumeWebWriter(), clock: h.clock))
        XCTAssertTrue(h.appliedLimits.isEmpty)
        // B5 reload and the fresh recovery derivation may both idempotently write this cause.
        XCTAssertEqual(Set(h.appliedOpens.map(\.id)), [saved.id])
        XCTAssertEqual(h.load(FlintOpenLimitRule.self).first?.opensAllowed, 3)
    }

    func testThresholdObservedDuringRegistrationIsReadBeforeNativeResume() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
        rule.selection.applicationTokens = [try fictionalRuleToken("pass-time-during-registration")]
        let saved = h.install(rule)
        let beforeObservation = try h.ledger.read().get()
        h.center.held.removeValue(forKey: .init(saved.activityName))
        h.center.onStart = { name in
            guard name == saved.activityName else { return }
            XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(activity: name, event: saved.eventName,
                at: .init(clock: h.clock), defaults: h.defaults, ledger: h.ledger))
        }
        try FlintEmergencyPassRuleEffects.resume(state: beforeObservation, at: .init(clock: h.clock),
            environment: h.environment, web: .init(writer: PassResumeWebWriter(), clock: h.clock))
        XCTAssertEqual(h.appliedLimits.map(\.id), [saved.id])
        XCTAssertNotNil(try h.ledger.peek().get().passLimitThresholds?[saved.id])
    }
}

private final class PassResumeWebWriter: FlintWebRestrictionWriting {
    var applied: [String] = []
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) { applied.append(storeName) }
    func clear(store storeName: String) {}
}
