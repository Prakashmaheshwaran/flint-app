import XCTest
import FamilyControls
@testable import FlintCore

/// ADR-012 §9, PC-54/55/56/60. All progression is injected; no sleeps or real clock changes.
final class FlintRuleOccurrenceClockTests: XCTestCase {
    func testR07ForwardJumpOffDeleteQueueDoNotReleaseEarly_turnOff() throws { try exerciseR07(.off) }
    func testR07ForwardJumpOffDeleteQueueDoNotReleaseEarly_delete() throws { try exerciseR07(.delete) }
    func testR07ForwardJumpOffDeleteQueueDoNotReleaseEarly_previouslyQueuedOff() throws { try exerciseR07(.queuedOff) }

    private enum R07Operation: Equatable { case off, delete, queuedOff }
    private func exerciseR07(_ operation: R07Operation) throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = ISO8601DateFormatter().date(from: "2026-10-28T10:00:00Z")!
        let rule = h.install(h.schedule(tier: .hardcore)) // 09:00–17:00, observed at 10:00
        let arbiter = FlintRuleArbiter(environment: h.environment)
        func mutate(_ confirmation: FlintRuleConfirmation?) -> FlintRuleMutationResult {
            if operation == .delete {
                return arbiter.delete(FlintScheduleRule.self, id: rule.id, revision: rule.revision, confirmation: confirmation)
            }
            return arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
                revision: rule.revision, confirmation: confirmation)
        }
        if operation == .queuedOff {
            guard case let .deferRequired(ticket, end) = mutate(nil) else { return XCTFail() }
            XCTAssertEqual(mutate(ticket), .queued(until: end))
        }
        h.now = ISO8601DateFormatter().date(from: "2026-10-28T18:00:00Z")! // same boot; no elapsed advancement
        if operation != .queuedOff {
            guard case let .deferRequired(ticket, end) = mutate(nil) else { return XCTFail("R-07: active Hardcore must defer") }
            XCTAssertEqual(mutate(ticket), .queued(until: end))
        }
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertTrue(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled)
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
        XCTAssertNotNil(h.center.held[.init(rule.activityName)])
        h.advance(7 * 3600) // the real 17:00 boundary, seven observed hours after arming
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.cleared, [rule.storeName])
        XCTAssertEqual(h.center.stops.flatMap { $0 }, [rule.activityName])
        XCTAssertNil(h.center.held[.init(rule.activityName)])
        if operation == .delete { XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty) }
        else { XCTAssertFalse(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled) }
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.cleared, [rule.storeName])
        XCTAssertEqual(h.center.stops.flatMap { $0 }, [rule.activityName], "R-07: release exactly once")
    }

    func testHardcoreQueueCannotApplyAtForwardWallEndPC54() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let initial = h.install(h.schedule(tier: .hardcore))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, end) = arbiter.setEnabled(FlintScheduleRule.self,
            id: initial.id, enabled: false, revision: initial.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: initial.id, enabled: false,
            revision: initial.revision, confirmation: ticket), .queued(until: end))
        let queued = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        h.now.addTimeInterval(6 * 3600) // wall only, beyond the original 17:00 end
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertTrue(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled)
        XCTAssertEqual(queued.occurrence(at: h.now, clock: h.clock), initial.protection?.occurrence)
        XCTAssertNotNil(h.reports["ruleTime.schedules"]?.failures.first)
        h.advance(5 * 3600)
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertFalse(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled)
        let clears = h.cleared.count
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.cleared.count, clears, "one boundary acceptance")
    }

    func testBackwardJumpDoesNotExtendAndFutureJumpDoesNotStartPC55() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = h.install(h.schedule(tier: .hardcore))
        let rule = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self)?.first)
        h.now.addTimeInterval(-6 * 3600)
        XCTAssertNotNil(rule.occurrence(at: h.now, clock: h.clock))
        h.advance(5 * 3600)
        XCTAssertNil(rule.occurrence(at: h.now, clock: h.clock))

        var future = h.schedule(tier: .hardcore)
        future.oneShot = .init(start: h.now.addingTimeInterval(3600), end: h.now.addingTimeInterval(7200))
        FlintRuleOccurrenceClock.observe(&future, clock: h.clock)
        h.now.addTimeInterval(90 * 60)
        XCTAssertNil(future.occurrence(at: h.now, clock: h.clock))
        h.advance(3600)
        XCTAssertNotNil(future.occurrence(at: h.now, clock: h.clock))
    }

    func testQueueAcceptedAfterJumpKeepsFrozenEndAndDisplaysLogicalEstimatePC54() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        h.now.addTimeInterval(6 * 3600)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, displayedEnd) = arbiter.setEnabled(FlintScheduleRule.self,
            id: rule.id, enabled: false, revision: rule.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(displayedEnd, h.now.addingTimeInterval(5 * 3600))
        XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: ticket), .queued(until: displayedEnd))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.protection?.queuedChange?.window, rule.protection?.occurrence)
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertTrue(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled)
    }

    func testEachDiscontinuityHasOneRuleScopedHealthRecord() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = h.install(h.schedule(tier: .hardcore))
        h.now.addTimeInterval(6 * 3600)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        h.now.addTimeInterval(-12 * 3600)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        let notes = h.reports["ruleTime.schedules"]?.failures ?? []
        XCTAssertEqual(notes.count, 2)
        XCTAssertTrue(notes[0].reason.contains("wallAhead"))
        XCTAssertTrue(notes[1].reason.contains("wallBehind"))
    }

    func testUnknownBootIdentityStillUsesConservativeElapsedForSchedulesPC54PC55() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.schedule(tier: .hardcore)
        var clock = h.clock; clock.bootSessionID = { nil }
        FlintRuleOccurrenceClock.observe(&rule, clock: clock)
        let window = try XCTUnwrap(rule.occurrence(at: h.now, clock: clock))
        h.now.addTimeInterval(6 * 3600)
        XCTAssertEqual(rule.occurrence(at: h.now, clock: clock), window)
        h.advance(5 * 3600)
        XCTAssertNil(rule.occurrence(at: h.now, clock: clock))
    }

    func testDiagnosticZoneDoesNotInvalidatePolicyAndSubsecondReadsDoNotRewrite() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.install(h.schedule(tier: .hardcore))
        let anchor = try XCTUnwrap(rule.protection?.timeAnchor)
        rule.protection?.timeAnchor = .init(timeline: anchor.timeline, wall: anchor.wall,
            monotonic: anchor.monotonic, bootID: anchor.bootID, deviceZoneID: "unrecognized-diagnostic-zone")
        XCTAssertNil(rule.validationIssue)
        let source = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        h.advance(0.25)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        h.advance(0.25)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        h.advance(0.5)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        XCTAssertNotEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
    }

    func testFreezeCannotActivateSuccessorAcrossAnUnappliedDueQueue() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, end) = arbiter.setEnabled(FlintScheduleRule.self,
            id: rule.id, enabled: false, revision: rule.revision, confirmation: nil) else { return XCTFail() }
        _ = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false, revision: rule.revision, confirmation: ticket)
        h.advance(to: end.addingTimeInterval(86400))
        let rules = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self))
        let source = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        let frozen = try XCTUnwrap(h.environment.freezeOccurrences(rules))
        XCTAssertNil(frozen.first?.validationIssue)
        XCTAssertEqual(frozen.first?.protection?.occurrence, rule.protection?.occurrence)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertFalse(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled)
    }

    func testRepairAfterRebootIsEligibleAndSkippedOrUnsavedRepairsAreRecorded() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        h.now.addTimeInterval(-3600)
        let monitor = FlintRuleMonitor(environment: h.environment)
        _ = monitor.handle(.end, activity: rule.activityName)
        XCTAssertEqual(h.center.starts.count, 1)
        h.boot = "new-boot"; h.monotonic = 10
        _ = monitor.handle(.end, activity: rule.activityName)
        XCTAssertEqual(h.center.starts.count, 2)
        _ = monitor.handle(.end, activity: rule.activityName)
        XCTAssertEqual(h.center.starts.count, 2)
        XCTAssertTrue(h.reports["ruleTime.schedules"]?.failures.contains { $0.reason.contains("spacing/attempt limit") } == true)
        h.advance(15)
        _ = h.environment.observedRules(FlintScheduleRule.self)
        h.saveAllowed = false
        _ = monitor.handle(.end, activity: rule.activityName)
        XCTAssertEqual(h.center.starts.count, 2)
        XCTAssertTrue(h.reports["ruleTime.schedules"]?.failures.contains { $0.reason.contains("checkpoint could not be saved") } == true)
    }

    func testNativeWebAndHostHealthUseTheSameInjectedRuleClockAfterJump() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        h.now.addTimeInterval(6 * 3600)
        let writer = TrustedRuleWebWriter()
        let config = FlintWebRestrictions(enabled: false, applyDuringSchedules: true)
        FlintRuleShield.reconcileWeb(rule, config: config, at: h.now, writer: writer, clock: h.clock)
        XCTAssertEqual(writer.applied, [rule.storeName]); XCTAssertTrue(writer.cleared.isEmpty)
        let health = FlintArmingHealth(domains: [
            "session": .init(enabled: 0, attempted: 0, armed: 0, failures: []),
            "schedules": .init(enabled: 1, attempted: 1, armed: 1, failures: [], observedActivities: [rule.activityName])
        ])
        let report = FlintWebRestrictionHostReconciler.reconcile(activeSession: nil, hasActiveSessionRecord: false,
            rules: [rule], config: config, health: health, lifecycle: .init(writer: writer),
            now: h.now, calendar: h.clock.calendar(), clock: h.clock)
        XCTAssertEqual(report.armed, 1)
        h.advance(5 * 3600)
        var notes: [String] = []
        let cleared = writer.cleared.count
        FlintRuleShield.reconcileWeb(rule, config: config, at: h.now, writer: writer, clock: h.clock, record: { notes.append($0) })
        XCTAssertEqual(writer.cleared.count, cleared, "a nil projection is not standalone release authority")
        XCTAssertEqual(notes.count, 1)
    }

    func testExplicitDisplayDateIsUsedInsteadOfClocksWallSample() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let displayDate = h.now.addingTimeInterval(6 * 3600)
        XCTAssertEqual(rule.currentOccurrence(at: displayDate, clock: h.clock)?.end, displayDate.addingTimeInterval(5 * 3600))
    }

    func testOpenLimitCommitmentAndQueuedOffUseTrustedDailyBoundaryPC54PC60() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, end) = arbiter.setEnabled(FlintOpenLimitRule.self,
            id: rule.id, enabled: false, revision: rule.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(arbiter.setEnabled(FlintOpenLimitRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: ticket), .queued(until: end))
        h.now.addTimeInterval(86400)
        XCTAssertTrue(arbiter.applyQueued(FlintOpenLimitRule.self))
        XCTAssertTrue(try XCTUnwrap(h.load(FlintOpenLimitRule.self).first).enabled)
        h.boot = "reboot"; h.monotonic = 10
        XCTAssertTrue(arbiter.applyQueued(FlintOpenLimitRule.self))
        XCTAssertTrue(try XCTUnwrap(h.load(FlintOpenLimitRule.self).first).enabled)
        h.advance(12 * 3600)
        XCTAssertTrue(arbiter.applyQueued(FlintOpenLimitRule.self))
        XCTAssertFalse(try XCTUnwrap(h.load(FlintOpenLimitRule.self).first).enabled)
    }

    func testDuplicatePrematureCallbacksHaveBoundedPersistedRepairBudget() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        h.now.addTimeInterval(6 * 3600)
        let monitor = FlintRuleMonitor(environment: h.environment)
        for _ in 0..<10 { _ = monitor.handle(.end, activity: rule.activityName) }
        XCTAssertEqual(h.center.starts.count, 1)
        for _ in 0..<10 {
            h.advance(15)
            _ = monitor.handle(.end, activity: rule.activityName)
        }
        XCTAssertEqual(h.center.starts.count, 6)
        XCTAssertTrue(h.cleared.isEmpty)
    }

    func testExhaustedTimeLimitKeepsShieldThresholdAndBudgetUntilTrustedMidnightPC54PC55() throws {
        for jump in [86400.0, -86400.0] {
            let h = try RuleHarness(); defer { h.clean() }
            _ = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 10,
                breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
            let rule = try XCTUnwrap(h.environment.observedRules(FlintLimitRule.self)?.first)
            var env = thresholdEnvironment(h)
            env.timeLimitIsShielded = { limit, _ in
                FlintLimitThresholds.hasReached(limit, at: h.now, in: h.defaults, clock: h.clock)
            }
            let monitor = FlintRuleMonitor(environment: env)
            _ = monitor.handle(.threshold(rule.eventName), activity: rule.activityName)
            h.now.addTimeInterval(jump)
            _ = monitor.handle(.start, activity: rule.activityName)
            _ = monitor.handle(.end, activity: rule.activityName)
            XCTAssertTrue(h.cleared.isEmpty)
            XCTAssertTrue(FlintLimitThresholds.hasReached(rule, at: h.now, in: h.defaults, clock: h.clock))
            XCTAssertEqual(h.load(FlintLimitRule.self).first?.protection?.occurrence, rule.protection?.occurrence)
            XCTAssertFalse(h.center.starts.isEmpty, "premature callback repairs the carrier")
            XCTAssertTrue(h.reports["ruleTime.limits"]?.failures.contains { $0.reason.contains("premature") } == true)
            h.advance(12 * 3600)
            _ = monitor.handle(.start, activity: rule.activityName)
            XCTAssertFalse(FlintLimitThresholds.hasReached(rule, at: h.now, in: h.defaults, clock: h.clock))
            XCTAssertEqual(h.cleared, [rule.storeName])
            _ = monitor.handle(.start, activity: rule.activityName)
            _ = monitor.handle(.end, activity: rule.activityName)
            XCTAssertEqual(h.cleared, [rule.storeName], "duplicate boundaries cannot reset twice")
        }
    }

    func testFiniteRebootWallFallbackAndConservativeDailyResetPC60() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = h.install(h.schedule(tier: .hardcore))
        var rule = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self)?.first)
        h.boot = "second-boot"; h.monotonic = 100
        h.now.addTimeInterval(3600)
        XCTAssertEqual(FlintRuleOccurrenceClock.current(for: rule, clock: h.clock), rule.protection?.occurrence)
        FlintRuleOccurrenceClock.observe(&rule, clock: h.clock)
        h.advance(4 * 3600)
        XCTAssertNil(rule.occurrence(at: h.now, clock: h.clock))
        // A past wall end expires a finite restriction even when no cross-boot elapsed is known.
        h.boot = "third-boot"; h.monotonic = 1
        XCTAssertNil(rule.occurrence(at: h.now, clock: h.clock))

        _ = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 10,
            protection: .init(timeZoneID: "UTC")))
        var daily = try XCTUnwrap(h.environment.observedRules(FlintLimitRule.self)?.first)
        let window = try XCTUnwrap(daily.protection?.occurrence)
        let remaining = window.end.timeIntervalSince(h.now)
        h.boot = "fourth-boot"; h.monotonic = 1; h.now.addTimeInterval(86400)
        FlintRuleOccurrenceClock.observe(&daily, clock: h.clock)
        XCTAssertEqual(daily.occurrence(at: h.now, clock: h.clock), window)
        h.advance(remaining)
        XCTAssertNotEqual(daily.occurrence(at: h.now, clock: h.clock), window)
    }

    func testWebAndStatusAgreeWithArbiterAfterClockAndDeviceZoneChangePC54PC56() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = h.install(h.schedule(tier: .hardcore))
        let rule = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self)?.first)
        h.now.addTimeInterval(6 * 3600)
        var clock = h.clock
        clock.calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Asia/Tokyo")!; return c }
        let before = FlintWebRestrictions(enabled: false, applyDuringSchedules: true)
        var after = before; after.applyDuringSchedules = false
        let causes = FlintWebRestrictionEditor.affectedCauses(previous: before, candidate: after,
            snapshot: .init(session: nil, rules: [rule]), now: h.now, calendar: clock.calendar(), clock: clock)
        XCTAssertEqual(causes.count, 1)
        let status = FlintProtectionStatusBuilder.RuleInput.schedule(rule, armed: true, observed: true,
            now: h.now, calendar: clock.calendar(), clock: clock)
        XCTAssertTrue(status.isActive(at: h.now))
        XCTAssertEqual(status.currentWindow?.end, causes.first?.end)
        XCTAssertEqual(status.currentWindow?.end, h.now.addingTimeInterval(5 * 3600))
        guard case .deferRequired = FlintRuleArbiter(environment: h.environment).delete(FlintScheduleRule.self,
            id: rule.id, revision: rule.revision, confirmation: nil) else { return XCTFail() }
        h.advance(5 * 3600)
        XCTAssertTrue(FlintWebRestrictionEditor.affectedCauses(previous: before, candidate: after,
            snapshot: .init(session: nil, rules: [rule]), now: h.now, calendar: clock.calendar(), clock: clock).isEmpty)
        XCTAssertFalse(FlintProtectionStatusBuilder.RuleInput.schedule(rule, armed: true, observed: true,
            now: h.now, calendar: clock.calendar(), clock: clock).isActive(at: h.now))
    }

    func testLegacyOptionalAnchorAndDSTResolvedIdentityPC57() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var legacy = h.install(h.schedule(tier: .hardcore))
        legacy.protection?.timeAnchor = nil
        let bytes = try JSONEncoder().encode(legacy)
        XCTAssertFalse(String(decoding: bytes, as: UTF8.self).contains("timeAnchor"))
        let decoded = try JSONDecoder().decode(FlintScheduleRule.self, from: bytes)
        XCTAssertEqual(decoded.occurrence(at: h.now, clock: h.clock), legacy.protection?.occurrence)
        XCTAssertTrue(FlintDesiredRules.save([decoded], key: .schedules, to: h.defaults))
        XCTAssertNotNil(h.environment.observedRules(FlintScheduleRule.self)?.first?.protection?.timeAnchor)

        for (date, startHour, endHour, start, end) in [
            ("2026-03-08T07:45:00Z", 2, 4, "2026-03-08T07:00:00Z", "2026-03-08T08:00:00Z"),
            ("2026-11-01T06:15:00Z", 1, 2, "2026-11-01T05:00:00Z", "2026-11-01T07:00:00Z")
        ] {
            h.now = ISO8601DateFormatter().date(from: date)!
            var rule = h.schedule(tier: .hardcore)
            rule.protection?.timeZoneID = "America/New_York"
            rule.schedule = .init(startHour: startHour, endHour: endHour)
            FlintRuleOccurrenceClock.observe(&rule, clock: h.clock)
            let window = try XCTUnwrap(rule.occurrence(at: h.now, clock: h.clock))
            XCTAssertEqual(window.start, ISO8601DateFormatter().date(from: start))
            XCTAssertEqual(window.end, ISO8601DateFormatter().date(from: end))
            h.now.addTimeInterval(86400)
            XCTAssertEqual(rule.occurrence(at: h.now, clock: h.clock), window)
        }
    }

    private func thresholdEnvironment(_ h: RuleHarness) -> FlintRuleReloadEnvironment {
        var env = h.environment
        env.markLimitThreshold = { FlintLimitThresholds.markReached($0, at: $1, in: h.defaults, clock: h.clock) }
        env.clearLimitThreshold = { FlintLimitThresholds.clear($0, in: h.defaults) }
        return env
    }
}

private final class TrustedRuleWebWriter: FlintWebRestrictionWriting {
    var applied: [String] = []
    var cleared: [String] = []
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) { applied.append(storeName) }
    func clear(store storeName: String) { cleared.append(storeName) }
}
