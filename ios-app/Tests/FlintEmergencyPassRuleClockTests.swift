import XCTest
import FamilyControls
@testable import FlintCore

final class FlintEmergencyPassRuleClockTests: XCTestCase {
    private func scope(_ h: RuleHarness) throws -> FlintPassScope {
        try FlintEmergencyPassScope.load(state: h.ledger.read().get(), at: .init(clock: h.clock),
            defaults: h.defaults, calendar: h.clock.calendar(), migrating: false, hasThresholdShield: { _ in false })
    }

    func testRuleClockAndRepairCheckpointsDoNotStalePassScopePC54PC55() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let original = h.install(h.schedule(tier: .hardcore))
        let first = try scope(h)
        h.advance(2)
        var rule = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self)?.first)
        XCTAssertNotEqual(rule.protection?.timeAnchor, original.protection?.timeAnchor)
        XCTAssertEqual(try scope(h).revision, first.revision)
        let occurrenceID = try XCTUnwrap(rule.protection?.occurrence).identity
        rule.protection?.carrierRepair = .init(occurrenceID: occurrenceID,
            checkpoint: .init(clock: h.clock), attempts: 1)
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: h.defaults))
        XCTAssertEqual(try scope(h).revision, first.revision)
        h.now.addTimeInterval(6 * 3600)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        XCTAssertEqual(try scope(h).revision, first.revision)
        XCTAssertEqual(try scope(h).rules.first?.naturalEnd, first.rules.first?.naturalEnd)
    }

    func testEverySavedPolicyFieldStillInvalidatesPassScope() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let original = h.install(h.schedule(tier: .hardcore))
        let first = try scope(h).revision
        let changes: [(inout FlintScheduleRule) -> Void] = [
            { $0.selection = FamilyActivitySelection(includeEntireCategory: true) }, { $0.breakLevel = .easy },
            { $0.schedule.endHour = 18 }, { $0.enabled = false },
            { $0.protection?.revision += 1 }, { $0.protection?.timeZoneID = "America/New_York" }
        ]
        for change in changes {
            var rule = original; change(&rule)
            XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: h.defaults))
            XCTAssertNotEqual(try scope(h).revision, first)
        }
        let limit = h.install(FlintLimitRule(id: "budget", name: "Budget", thresholdMinutes: 30,
            protection: .init(timeZoneID: "UTC")))
        let beforeBudget = try scope(h).revision
        var changed = limit; changed.thresholdMinutes = 60
        XCTAssertTrue(FlintDesiredRules.save([changed], key: .limits, to: h.defaults))
        XCTAssertNotEqual(try scope(h).revision, beforeBudget)
    }

    func testFingerprintIncludesUnknownPolicyFieldsAndPreservesUnknownArrayOrder() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = h.install(h.schedule(tier: .hardcore))
        let baseline = try scope(h).revision
        var objects = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(
            h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue))) as? [[String: Any]])
        var protection = try XCTUnwrap(objects[0]["protection"] as? [String: Any])
        protection["futurePolicy"] = ["first", "second"]; objects[0]["protection"] = protection
        h.defaults.set(try JSONSerialization.data(withJSONObject: objects), forKey: FlintRuleStoreKey.schedules.rawValue)
        let first = try scope(h).revision
        XCTAssertNotEqual(first, baseline, "new policy fields are included by default")
        protection["futurePolicy"] = ["second", "first"]; objects[0]["protection"] = protection
        h.defaults.set(try JSONSerialization.data(withJSONObject: objects), forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertNotEqual(try scope(h).revision, first, "only known Set arrays are sorted")
    }

    func testPassPreviewConfirmedAfterWallJumpBindsSameRuleOccurrencePC54() {
        do {
            let h = try RuleHarness(); defer { h.clean() }
            // This exercises review of an existing commitment, with an already durable ledger.
            // Use its canonical encoder; fresh ledger initialization has separate coverage.
            var state = FlintLedgerState(initializedAt: h.now)
            state.importLegacyPass(.absent, at: .init(clock: h.clock))
            let directory = h.directory.appendingPathComponent("ledger", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FlintProtectionLedger.makeEncoder().encode(state).write(to: directory.appendingPathComponent(FlintProtectionLedger.fileName))
            h.marker = h.now
            let rule = h.install(h.schedule(tier: .hardcore))
            let controller = FlintEmergencyPassController(environment: .init(ledger: { h.ledger }, clock: h.clock,
                scope: { state, now in try FlintEmergencyPassScope.load(state: state, at: now,
                    defaults: h.defaults, calendar: h.clock.calendar(), hasThresholdShield: { _ in false }) },
                registerCarrier: { _, _, _ in }, terminate: { _, _ in }, release: { _ in }, resume: { _, _ in },
                disable: { _ in }, emit: { _ in }))
            let preview = try controller.preview().get()
            h.now.addTimeInterval(6 * 3600)
            _ = h.environment.observedRules(FlintScheduleRule.self)
            _ = try controller.use(previewRevision: preview.revision, operationID: "rule-clock-preview").get()
            let recovery = try XCTUnwrap(h.ledger.peek().get().emergencyRecovery)
            XCTAssertEqual(recovery.scope.rules.map(\.id), ["schedule:\(rule.id)"])
            XCTAssertEqual(recovery.scope.rules.first?.naturalEnd, rule.protection?.occurrence?.end)
            XCTAssertEqual(try h.ledger.peek().get().pass.usage, .used)
        } catch { XCTFail("Existing-rule preview failed: \(error)") }
    }

    func testKnownSetOrderDoesNotChangeScopePolicyFingerprint() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.schedule(tier: .hardcore); rule.schedule.daysOfWeek = [4, 5]
        _ = h.install(rule)
        let first = try scope(h).revision
        var objects = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(
            h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue))) as? [[String: Any]])
        var schedule = try XCTUnwrap(objects[0]["schedule"] as? [String: Any])
        for order in [[4, 5], [5, 4]] {
            schedule["daysOfWeek"] = order; objects[0]["schedule"] = schedule
            h.defaults.set(try JSONSerialization.data(withJSONObject: objects), forKey: FlintRuleStoreKey.schedules.rawValue)
            XCTAssertEqual(try scope(h).revision, first)
        }
    }

    func testThresholdProofAndPeerPruningUseTrustedDailyBoundaryPC54PC60() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let a = h.install(FlintLimitRule(id: "a", name: "A", thresholdMinutes: 30,
            protection: .init(timeZoneID: "UTC")))
        var b = FlintLimitRule(id: "b", name: "B", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
        FlintRuleOccurrenceClock.observe(&b, clock: h.clock)
        XCTAssertTrue(FlintDesiredRules.save([a, b], key: .limits, to: h.defaults))
        XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(activity: a.activityName, event: a.eventName,
            at: .init(clock: h.clock), defaults: h.defaults, ledger: h.ledger))
        h.now.addTimeInterval(86400)
        XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(activity: b.activityName, event: b.eventName,
            at: .init(clock: h.clock), defaults: h.defaults, ledger: h.ledger))
        XCTAssertEqual(try scope(h).rules.filter(\.wasEnforcing).count, 2)
        XCTAssertNotNil(try h.ledger.peek().get().passLimitThresholds?[a.id])
        XCTAssertTrue(FlintEmergencyPassRuleEffects.timeLimitReached(a, at: h.now, ledger: h.ledger, clock: h.clock))
        h.advance(12 * 3600)
        XCTAssertTrue(try scope(h).rules.allSatisfy { !$0.wasEnforcing })
        XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(activity: b.activityName, event: b.eventName,
            at: .init(clock: h.clock), defaults: h.defaults, ledger: h.ledger))
        XCTAssertNil(try h.ledger.peek().get().passLimitThresholds?[a.id])
        XCTAssertFalse(FlintEmergencyPassRuleEffects.timeLimitReached(a, at: h.now, ledger: h.ledger, clock: h.clock))
    }

    func testPrematureAndBusyRuleCallbacksPreserveRecoveryGateAfterJump() throws {
        for busy in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let rule = h.install(h.schedule(tier: .hardcore))
            var env = h.environment
            env.recoveryGate = { .suspended(until: h.now.addingTimeInterval(300)) }
            if busy { env.withTransitionBoundary = { _ in false } }
            h.now.addTimeInterval(6 * 3600)
            _ = FlintRuleMonitor(environment: env).handle(.start, activity: rule.activityName)
            XCTAssertTrue(h.appliedSchedules.isEmpty)
            XCTAssertTrue(h.cleared.isEmpty)
            XCTAssertNotNil(h.reports["schedules"]?.suspendedUntil)
            let limit = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                protection: .init(timeZoneID: "UTC")))
            env.timeLimitIsShielded = { _, _ in true }
            h.now.addTimeInterval(86400)
            _ = FlintRuleMonitor(environment: env).handle(.end, activity: limit.activityName)
            XCTAssertTrue(h.appliedLimits.isEmpty)
            XCTAssertTrue(h.cleared.isEmpty)
        }
    }
}
