import XCTest
import FamilyControls
@testable import FlintCore

final class FlintRuleThresholdTests: XCTestCase {
    func testSavedQuotaZoneControlsMarkAcrossDisplayMidnightAndSavedMidnight() throws {
        for zone in ["UTC", "America/New_York"] {
            let h = try RuleHarness(); defer { h.clean() }
            h.now = ISO8601DateFormatter().date(from: "2026-10-28T23:45:00Z")!
            let rule = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                protection: .init(timeZoneID: zone)))
            FlintLimitThresholds.markReached(rule, at: h.now, in: h.defaults)
            h.advance(1800)
            var display = Calendar(identifier: .gregorian)
            display.timeZone = TimeZone(identifier: zone == "UTC" ? "America/Los_Angeles" : "UTC")!
            XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, calendar: display, in: h.defaults),
                zone == "UTC" ? [:] : [rule.id: true], "saved zone controls the period, not the display calendar")
        }
    }

    func testMarkDoesNotCrossNativeRevisionOrChangedSelectionEvenAtSameQuota() throws {
        for changeRevision in [true, false] {
            let h = try RuleHarness(); defer { h.clean() }
            var rule = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                protection: .init(timeZoneID: "UTC")))
            FlintLimitThresholds.markReached(rule, at: h.now, in: h.defaults)
            XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [rule.id: true])
            if changeRevision { rule.protection?.revision = 2; rule.protection?.activityRevision = 2 }
            else { rule.selection.applicationTokens = [try fictionalRuleToken("new-threshold-target")] }
            XCTAssertTrue(FlintDesiredRules.save([rule], key: .limits, to: h.defaults))
            XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [:])
        }
    }

    func testRenameAndFutureOnlyQueuePreserveCurrentObservation() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        FlintLimitThresholds.markReached(old, at: h.now, in: h.defaults)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        var renamed = old; renamed.name = "Renamed"
        XCTAssertEqual(arbiter.upsert(renamed), .applied)
        var future = try XCTUnwrap(h.load(FlintLimitRule.self).first); future.thresholdMinutes = 60
        guard case let .deferRequired(ticket, end) = arbiter.upsert(future) else { return XCTFail() }
        XCTAssertEqual(arbiter.upsert(future, confirmation: ticket), .queued(until: end))
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [old.id: true])
        let current = try XCTUnwrap(h.load(FlintLimitRule.self).first)
        FlintLimitThresholds.prune(keeping: [current], in: h.defaults)
        XCTAssertTrue(FlintLimitThresholds.hasReached(current, at: h.now, in: h.defaults))
    }

    func testSameIDRecreationCannotInheritObservation() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            protection: .init(timeZoneID: "UTC")))
        FlintLimitThresholds.markReached(old, at: h.now, in: h.defaults)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .confirmationRequired(ticket, _) = arbiter.delete(FlintLimitRule.self,
            id: old.id, revision: old.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(arbiter.delete(FlintLimitRule.self, id: old.id, revision: old.revision, confirmation: ticket), .applied)
        XCTAssertEqual(arbiter.upsert(old, creating: true), .applied)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [:])
    }

    func testLegacyUnboundAndCorruptMarksRemainUnknownAndPreserveBytes() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            protection: .init(timeZoneID: "UTC")))
        h.defaults.set([rule.id: h.now.timeIntervalSince1970], forKey: FlintLimitThresholds.key)
        h.defaults.set([rule.id: 30], forKey: FlintLimitThresholds.thresholdKey)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [:])
        let corrupt = Data("{unreadable".utf8)
        h.defaults.set(corrupt, forKey: FlintLimitThresholds.observationKey)
        FlintLimitThresholds.markReached(rule, at: h.now, in: h.defaults)
        FlintLimitThresholds.clear(rule.id, in: h.defaults)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [:])
        XCTAssertEqual(h.defaults.data(forKey: FlintLimitThresholds.observationKey), corrupt)
    }

    func testValidatedThresholdCallbackMarksAndNewPeriodStartClears() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            protection: .init(timeZoneID: "UTC")))
        var env = h.environment
        env.markLimitThreshold = { FlintLimitThresholds.markReached($0, at: h.now, in: h.defaults) }
        env.clearLimitThreshold = { FlintLimitThresholds.clear($0, in: h.defaults) }
        let monitor = FlintRuleMonitor(environment: env)
        _ = monitor.handle(.threshold(rule.eventName), activity: "flint.limit.other.r1")
        _ = monitor.handle(.threshold("flint.limitEvent.time.r99"), activity: rule.activityName)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [:])
        _ = monitor.handle(.threshold(rule.eventName), activity: rule.activityName)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [rule.id: true])
        h.now = try XCTUnwrap(rule.protection?.occurrence?.end)
        _ = monitor.handle(.start, activity: rule.activityName)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [:])
        XCTAssertEqual(h.cleared, [rule.storeName])
    }

    func testFailedNewPeriodSaveCannotResetBudgetOrShield() throws {
        for thresholdFirst in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let rule = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                protection: .init(timeZoneID: "UTC")))
            let source = h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue)
            h.now = try XCTUnwrap(rule.protection?.occurrence?.end); h.saveAllowed = false
            _ = FlintRuleMonitor(environment: h.environment).handle(
                thresholdFirst ? .threshold(rule.eventName) : .start, activity: rule.activityName)
            XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.appliedLimits.isEmpty)
            XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue), source)
            XCTAssertNotNil(h.reports["limits"]?.failures.first)
        }
    }

    func testThresholdBeforeStartAndDelayedOldEndCannotResetNewPeriodR21PC64PC66() throws {
        for endFirst in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let rule = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                protection: .init(timeZoneID: "UTC")))
            h.now = try XCTUnwrap(rule.protection?.occurrence?.end).addingTimeInterval(60)
            var env = h.environment
            env.markLimitThreshold = { FlintLimitThresholds.markReached($0, at: h.now, in: h.defaults) }
            env.clearLimitThreshold = { FlintLimitThresholds.clear($0, in: h.defaults) }
            let monitor = FlintRuleMonitor(environment: env)
            _ = monitor.handle(.threshold(rule.eventName), activity: rule.activityName)
            let current = try XCTUnwrap(h.load(FlintLimitRule.self).first)
            XCTAssertEqual(current.protection?.occurrence, current.templateOccurrence(at: h.now))
            _ = monitor.handle(endFirst ? .end : .start, activity: rule.activityName)
            _ = monitor.handle(endFirst ? .start : .end, activity: rule.activityName)
            XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [rule.id: true])
            XCTAssertEqual(h.appliedLimits.count, 1)
            XCTAssertTrue(h.cleared.isEmpty, "late daily boundaries cannot release today's exhausted protection")
        }
    }

    func testOnlyCommittedMutationsPublishAndZeroBudgetIsMarkedAfterSave() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var publications = 0
        var env = h.environment
        env.publishStatus = { publications += 1 }
        env.markLimitThreshold = { FlintLimitThresholds.markReached($0, at: h.now, in: h.defaults) }
        let arbiter = FlintRuleArbiter(environment: env)
        let zero = FlintLimitRule(id: "zero", name: "Zero", thresholdMinutes: 0,
            protection: .init(timeZoneID: "UTC"))
        XCTAssertEqual(arbiter.upsert(zero, creating: true), .applied)
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: h.now, in: h.defaults), [zero.id: true])
        let saved = try XCTUnwrap(h.load(FlintLimitRule.self).first)
        h.saveAllowed = false
        var rename = saved; rename.name = "Uncommitted"
        XCTAssertEqual(arbiter.upsert(rename), .unavailable)
        XCTAssertEqual(publications, 1, "failed commits never publish a successful mutation")
    }
}
