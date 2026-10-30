import XCTest
import FamilyControls
@testable import FlintCore

final class FlintEmergencyPassSuspensionTests: XCTestCase {
    private func confirmAndSave<Rule: FlintGatedRule>(_ rule: Rule, environment: FlintRuleReloadEnvironment) {
        let arbiter = FlintRuleArbiter(environment: environment)
        guard case let .confirmationRequired(ticket, _) = arbiter.upsert(rule) else {
            XCTFail("Expected an exact final confirmation before saving the strengthened scope"); return
        }
        XCTAssertEqual(arbiter.upsert(rule, confirmation: ticket), .applied)
    }

    func testRuleCallbacksReloadsAndStrengtheningSkipEveryApplyDuringRecoveryPC41PC42() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let work = h.install(h.schedule(tier: .easy))
        var time = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 0, protection: .init(timeZoneID: "UTC"))
        time.selection.applicationTokens = [try fictionalRuleToken("recovery-time")]
        let limit = h.install(time)
        var open = FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3, protection: .init(timeZoneID: "UTC"))
        open.selection.applicationTokens = [try fictionalRuleToken("recovery-open")]
        let opens = h.install(open)
        var env = h.environment
        let until = h.now.addingTimeInterval(3600)
        env.recoveryGate = { .suspended(until: until) }
        let monitor = FlintRuleMonitor(environment: env)
        for name in [work.activityName, limit.activityName, opens.activityName] {
            XCTAssertTrue(monitor.handle(.start, activity: name))
        }
        XCTAssertTrue(monitor.handle(.threshold(limit.eventName), activity: limit.activityName))
        var stronger = work; stronger.schedule.endHour = 18
        confirmAndSave(stronger, environment: env)
        var fewer = opens; fewer.opensAllowed = 2
        confirmAndSave(fewer, environment: env)
        var broaderTime = limit
        broaderTime.selection.applicationTokens.insert(try fictionalRuleToken("recovery-time-more"))
        confirmAndSave(broaderTime, environment: env)
        FlintSchedulesController(environment: env).reload()
        FlintLimitsController(environment: env).reload()
        FlintOpenLimitsController(environment: env).reload(reapplyShields: false)
        XCTAssertTrue(h.appliedSchedules.isEmpty)
        XCTAssertTrue(h.appliedLimits.isEmpty)
        XCTAssertTrue(h.appliedOpens.isEmpty)
        for domain in ["schedules", "limits", "openLimits"] {
            XCTAssertEqual(h.reports[domain]?.suspendedUntil, until)
            XCTAssertEqual(h.reports[domain]?.armed, 0)
        }
        XCTAssertEqual(h.load(FlintLimitRule.self).first?.thresholdMinutes, 0)
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.schedule.endHour, 18)

        // Sleep is the same shared schedule authority; its start and strengthening cannot
        // bypass the gate by using the special ID.
        var sleep = h.schedule(tier: .easy); sleep.id = "sleep.night"
        let savedSleep = h.install(sleep)
        XCTAssertTrue(monitor.handle(.start, activity: savedSleep.activityName))
        var strongerSleep = savedSleep; strongerSleep.schedule.endHour = 19
        confirmAndSave(strongerSleep, environment: env)
        XCTAssertTrue(h.appliedSchedules.isEmpty)
    }

    func testBusyAddOnlyCallbacksStaySuspendedButObserveExactThresholdBeforeLockPC42() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let work = h.install(h.schedule(tier: .hardcore))
        var time = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
        time.selection.applicationTokens = [try fictionalRuleToken("busy-recovery-time")]
        let limit = h.install(time)
        var open = FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3, protection: .init(timeZoneID: "UTC"))
        open.selection.applicationTokens = [try fictionalRuleToken("busy-recovery-open")]
        let opens = h.install(open)
        _ = try h.ledger.read().get()
        var observed = false
        var env = h.environment
        env.recoveryGate = { .suspended(until: h.now.addingTimeInterval(3600)) }
        env.observeLimitThreshold = { activity, event in
            observed = FlintEmergencyPassRuleEffects.noteThreshold(activity: activity, event: event,
                at: .init(clock: h.clock), defaults: h.defaults, ledger: h.ledger)
        }
        env.withTransitionBoundary = { _ in
            XCTAssertTrue(observed, "positive threshold observation precedes even a busy transition")
            return false
        }
        let monitor = FlintRuleMonitor(environment: env)
        XCTAssertTrue(monitor.handle(.threshold(limit.eventName), activity: limit.activityName))
        XCTAssertTrue(FlintLimitThresholds.hasReached(limit, at: h.now, in: h.defaults))
        XCTAssertNotNil(try h.ledger.peek().get().passLimitThresholds?[limit.id])
        XCTAssertTrue(FlintEmergencyPassRuleEffects.timeLimitReached(limit, at: h.now, ledger: h.ledger))
        for name in [work.activityName, opens.activityName] { XCTAssertTrue(monitor.handle(.start, activity: name)) }
        XCTAssertTrue(h.appliedSchedules.isEmpty)
        XCTAssertTrue(h.appliedLimits.isEmpty)
        XCTAssertTrue(h.appliedOpens.isEmpty)
        XCTAssertTrue(h.cleared.isEmpty)
    }

    func testUnavailableRecoveryAuthorityDoesNotApplyOrClaimArmed() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let work = h.install(h.schedule(tier: .easy))
        var env = h.environment; env.recoveryGate = { .unavailable }
        XCTAssertTrue(FlintRuleMonitor(environment: env).handle(.start, activity: work.activityName))
        FlintSchedulesController(environment: env).reload()
        XCTAssertTrue(h.appliedSchedules.isEmpty)
        XCTAssertEqual(h.reports["schedules"]?.armed, 0)
        XCTAssertFalse(h.reports["schedules"]?.failures.isEmpty ?? true)
    }

    func testCarrierAndResumeNowReapplyNativeRuleAdaptersWithoutAnotherSpendPC42PC45() throws {
        for resumeNow in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let work = h.install(h.schedule(tier: .hardcore))
            XCTAssertTrue(FlintWebRestrictions().save(to: h.defaults))
            var env = h.environment
            env.recoveryGate = { FlintPassRecoveryGate.read(ledger: h.ledger, at: .init(clock: h.clock)) }
            let applying = env
            var carriers: [String] = []
            var resumes = 0
            let pass = FlintEmergencyPassController(environment: .init(ledger: { h.ledger }, clock: h.clock,
                scope: { state, at in
                    try FlintEmergencyPassScope.load(state: state, at: at, defaults: h.defaults,
                        calendar: h.clock.calendar(), hasThresholdShield: { _ in false })
                }, registerCarrier: { name, _, _ in carriers.append(name) }, terminate: { _, _ in },
                release: { _ in }, resume: { state, at in
                    resumes += 1
                    try FlintEmergencyPassRuleEffects.resume(state: state, at: at, environment: applying,
                        web: .init(writer: RecoveryNoWebWriter(), clock: h.clock))
                }, emit: { _ in }))
            let preview = try pass.preview().get()
            _ = try pass.use(previewRevision: preview.revision, operationID: "global-recovery").get()
            XCTAssertTrue(FlintRuleMonitor(environment: applying).handle(.start, activity: work.activityName))
            XCTAssertTrue(h.appliedSchedules.isEmpty)
            if resumeNow { _ = try pass.resumeNow().get() }
            else {
                h.advance(3600)
                pass.handleRecoveryBoundary(activity: try XCTUnwrap(carriers.last))
            }
            XCTAssertEqual(resumes, 1)
            XCTAssertEqual(h.appliedSchedules.map(\.id), [work.id])
            let state = try h.ledger.peek().get()
            XCTAssertEqual(state.pass.lastOperationID, "global-recovery")
            XCTAssertEqual(state.pass.usage, .used)
            XCTAssertFalse(try XCTUnwrap(state.emergencyRecovery).resumePending)
            XCTAssertNil(h.reports["schedules"]?.suspendedUntil)
            XCTAssertEqual(h.load(FlintScheduleRule.self).first?.enabled, true)
        }
    }
}

private struct RecoveryNoWebWriter: FlintWebRestrictionWriting {
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) {}
    func clear(store storeName: String) {}
}
