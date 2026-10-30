import XCTest
import FamilyControls
@testable import FlintCore

final class FlintRuleStatusAdapterTests: XCTestCase {
    func testSavedZoneAndFrozenEndOverrideDisplayCalendar() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.install(h.schedule(tier: .hardcore))
        let frozen = try XCTUnwrap(rule.protection?.occurrence)
        rule.protection?.occurrence = .init(start: frozen.start, end: frozen.end.addingTimeInterval(3600))
        var display = Calendar(identifier: .gregorian); display.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        let input = FlintProtectionStatusBuilder.RuleInput.schedule(rule, armed: true, observed: true, now: h.now, calendar: display, clock: h.clock)
        XCTAssertEqual(input.currentWindow?.start, frozen.start)
        XCTAssertEqual(input.currentWindow?.end, frozen.end.addingTimeInterval(3600))
        XCTAssertEqual(rule.currentOccurrence(at: h.now, clock: h.clock)?.end, frozen.end.addingTimeInterval(3600))
    }

    func testOneShotProjectsFutureOnceAndNeverAsRecurring() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.schedule(tier: .hardcore)
        let oneShot = FlintRuleWindow(start: h.now.addingTimeInterval(86400), end: h.now.addingTimeInterval(90000))
        rule.oneShot = oneShot
        let future = FlintProtectionStatusBuilder.RuleInput.schedule(rule, armed: true, observed: true, now: h.now, calendar: h.clock.calendar())
        XCTAssertEqual(future.kind, .oneShotSchedule); XCTAssertNil(future.currentWindow)
        XCTAssertEqual(future.nextWindow?.start, oneShot.start)
        let ended = FlintProtectionStatusBuilder.RuleInput.schedule(rule, armed: true, observed: true,
            now: oneShot.end.addingTimeInterval(86400 * 7), calendar: h.clock.calendar())
        XCTAssertNil(ended.currentWindow); XCTAssertNil(ended.nextWindow)
    }

    func testCurrentInputsDoNotMigrateOrInitializeLegacyRuleStores() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.schedule(tier: .hardcore); rule.protection = nil
        let bytes = try JSONEncoder().encode([rule])
        h.defaults.set(bytes, forKey: FlintRuleStoreKey.schedules.rawValue)
        let environment = FlintProtectionStatus.ObservationEnvironment(authorized: { true },
            registeredActivities: { [] }, clock: h.clock, loadActiveSession: { nil },
            hasActiveSessionRecord: { false }, loadArmingHealth: { .init() })
        _ = FlintProtectionStatus.currentInputs(now: h.now, defaults: h.defaults, pass: .init(eligibility: .available),
            quotaCalendar: h.clock.calendar(), environment: environment)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), bytes)
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.marker))
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.original))
    }

    func testLimitHeadlineUsesItsSavedPeriodEnd() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let window = FlintRuleWindow(start: h.now.addingTimeInterval(-3600), end: h.now.addingTimeInterval(7200))
        let input = FlintProtectionStatusBuilder.RuleInput(id: "limit", kind: .timeLimit, displayName: "Time", tier: .hardcore,
            armed: true, observed: true, currentWindow: .init(start: window.start, end: window.end),
            active: true, thresholdReached: true)
        let snapshot = FlintProtectionStatusBuilder.make(.init(now: h.now, quotaCalendar: h.clock.calendar(), rules: [input],
            authorized: true, pass: .init(eligibility: .available), timeIntegrity: nil, timeIntegrityApplies: false))
        XCTAssertEqual(snapshot.causes.first?.end, window.end)
    }
}
