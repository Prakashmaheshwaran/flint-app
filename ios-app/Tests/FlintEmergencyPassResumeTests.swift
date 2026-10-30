import XCTest
import FamilyControls
@testable import FlintCore

/// Exercises the shared rule registrars/writers through their injected environment. These
/// are adapter tests, not evidence that DeviceActivity delivers while a device is locked.
final class FlintEmergencyPassResumeTests: XCTestCase {
    func testEveryRuleWriterReportsSkippedApplicationIncludingPositiveOnlyFallback() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var environment = h.environment
        environment.applyRuleShield = { _, _ in false }
        environment.applyLimitShield = { _, _ in false }
        environment.applyOpenShield = { _ in false }
        environment.addRuleShield = { _, _ in false }
        environment.addLimitShield = { _, _ in false }
        environment.addOpenShield = { _ in false }
        for addingOnly in [false, true] {
            XCTAssertFalse(environment.applyProtection(h.schedule(tier: .hardcore), addingOnly: addingOnly))
            XCTAssertFalse(environment.applyProtection(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 0),
                addingOnly: addingOnly))
            XCTAssertFalse(environment.applyProtection(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3),
                addingOnly: addingOnly))
        }
        for domain in ["schedules", "limits", "openLimits"] {
            XCTAssertEqual(h.reports[domain]?.armed, 0)
            XCTAssertFalse(h.reports[domain]?.failures.isEmpty ?? true)
        }
    }

    func testSkippedNativeResumeRetainsOutboxUntilApplicationSucceeds() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = h.install(h.schedule(tier: .hardcore))
        var canApply = false
        var environment = h.environment
        environment.applyRuleShield = { _, _ in canApply }
        let controller = FlintEmergencyPassController(environment: .init(ledger: { h.ledger }, clock: h.clock,
            scope: { state, now in try FlintEmergencyPassScope.load(state: state, at: now,
                defaults: h.defaults, calendar: h.clock.calendar(), hasThresholdShield: { _ in false }) },
            registerCarrier: { _, _, _ in }, terminate: { _, _ in }, release: { _ in },
            resume: { state, now in try FlintEmergencyPassRuleEffects.resume(state: state, at: now,
                environment: environment, web: .init(writer: PassResumeWebWriter(), clock: h.clock),
                existingWebStores: { false }) }, emit: { _ in }))
        let preview = try controller.preview().get()
        _ = try controller.use(previewRevision: preview.revision, operationID: "native-resume").get()
        h.advance(3600)
        XCTAssertEqual(controller.reconcile(), .failure(.applying))
        XCTAssertEqual(try h.ledger.peek().get().emergencyRecovery?.resumePending, true)
        XCTAssertTrue(try controller.status().get().needsApplication)
        canApply = true
        XCTAssertFalse(try controller.reconcile().get().needsApplication)
        XCTAssertEqual(try h.ledger.peek().get().emergencyRecovery?.resumePending, false)
        XCTAssertEqual(try h.ledger.peek().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testRecoveryEndResumesOnlyNaturallyActiveRulesIncludingStartsInsidePC42() throws {
        let h = try RuleHarness(); defer { h.clean() }
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
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
            environment: h.environment, web: .init(writer: writer, clock: h.clock), existingWebStores: { false })
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
            at: .init(clock: h.clock), environment: h.environment, web: .init(writer: writer, clock: h.clock),
            existingWebStores: { false })) {
            XCTAssertEqual($0 as? FlintEmergencyPassError, .applying)
        }
        XCTAssertTrue(h.appliedSchedules.isEmpty)
        XCTAssertTrue(writer.applied.isEmpty)
        XCTAssertTrue(h.reports["schedules"]?.failures.contains { $0.activityName == rule.activityName } == true)
    }

    func testReachedTimeBudgetResumesFromDurableProofWithClearedNativeStoreAfterWallJump() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
        rule.selection.applicationTokens = [try fictionalRuleToken("pass-time")]
        let saved = h.install(rule)
        XCTAssertFalse(h.timeLimitExhausted, "native store is cleared by recovery")
        XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(rule: saved, at: .init(clock: h.clock), ledger: h.ledger))
        h.now.addTimeInterval(86400) // wall-only jump cannot discard the exhausted occurrence on resume
        try FlintEmergencyPassRuleEffects.resume(state: h.ledger.peek().get(), at: .init(clock: h.clock),
            environment: h.environment, web: .init(writer: PassResumeWebWriter(), clock: h.clock), existingWebStores: { false })
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
            environment: h.environment, web: .init(writer: PassResumeWebWriter(), clock: h.clock), existingWebStores: { false })
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
            environment: h.environment, web: .init(writer: PassResumeWebWriter(), clock: h.clock), existingWebStores: { false })
        XCTAssertEqual(h.appliedLimits.map(\.id), [saved.id])
        XCTAssertNotNil(try h.ledger.peek().get().passLimitThresholds?[saved.id])
    }

    func testUnavailableSafariIntentDoesNotBlockOtherResumeWritesAndReportsWebRecovery() throws {
        for bad in ["missing", "malformed", "wrong-type"] {
            let h = try RuleHarness(); defer { h.clean() }
            let schedule = h.install(h.schedule(tier: .hardcore))
            var time = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
            time.selection.applicationTokens = [try fictionalRuleToken("resume-unavailable-time")]
            let savedTime = h.install(time)
            XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(rule: savedTime, at: .init(clock: h.clock), ledger: h.ledger))
            var open = FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3, protection: .init(timeZoneID: "UTC"))
            open.selection.applicationTokens = [try fictionalRuleToken("resume-unavailable-open")]
            let savedOpen = h.install(open)
            h.defaults.set(true, forKey: FlintWebRestrictions.initializedMarkerKey)
            if bad == "malformed" { h.defaults.set(Data("{".utf8), forKey: "flint.webRestrictions") }
            if bad == "wrong-type" { h.defaults.set("unreadable", forKey: "flint.webRestrictions") }
            let writer = PassResumeWebWriter()
            try FlintEmergencyPassRuleEffects.resume(state: h.ledger.peek().get(), at: .init(clock: h.clock),
                environment: h.environment, web: .init(writer: writer, clock: h.clock), existingWebStores: { false })
            XCTAssertTrue(h.appliedSchedules.contains { $0.id == schedule.id }, bad)
            XCTAssertTrue(h.appliedLimits.contains { $0.id == savedTime.id }, bad)
            XCTAssertTrue(h.appliedOpens.contains { $0.id == savedOpen.id }, bad)
            XCTAssertTrue(writer.applied.isEmpty, bad)
            XCTAssertEqual(h.reports["webRestrictions"]?.armed, 0, bad)
            XCTAssertEqual(h.reports["webRestrictions"]?.failures.first?.activityName, "webRestrictions", bad)
        }
    }
}

private final class PassResumeWebWriter: FlintWebRestrictionWriting {
    var applied: [String] = []
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) { applied.append(storeName) }
    func clear(store storeName: String) {}
}
