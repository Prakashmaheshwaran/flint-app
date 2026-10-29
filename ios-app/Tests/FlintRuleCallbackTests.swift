import XCTest
import FamilyControls
import ManagedSettings
@testable import FlintCore

final class FlintRuleCallbackTests: XCTestCase {
    func testOldRevisionAndPrematureCurrentEndCannotClearExtendedRuleR21PC64PC66() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        var next = old; next.schedule.endHour = 18
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .confirmationRequired(ticket, _) = arbiter.upsert(next) else { return XCTFail() }
        XCTAssertEqual(arbiter.upsert(next, confirmation: ticket), .applied)
        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(saved.protection?.incarnation, old.protection?.incarnation)
        XCTAssertNotEqual(saved.monitorName, old.monitorName)
        h.now = old.protection!.occurrence!.end
        let monitor = FlintRuleMonitor(environment: h.environment)
        _ = monitor.handle(.end, activity: old.monitorName)
        _ = monitor.handle(.end, activity: saved.monitorName)
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.flatMap { $0 }.contains(old.monitorName))
        h.now.addTimeInterval(3600)
        _ = monitor.handle(.end, activity: saved.monitorName)
        XCTAssertEqual(h.cleared, [saved.storeName])
    }

    func testThresholdRequiresMatchingActivityAndEventRevisionR21PC66() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let limit = h.install(FlintLimitRule(id: "limit", name: "Time", thresholdMinutes: 30,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        let monitor = FlintRuleMonitor(environment: h.environment)
        _ = monitor.handle(.threshold(limit.eventName), activity: "flint.limit.other.r1")
        _ = monitor.handle(.threshold("flint.limitEvent.limit.r99"), activity: limit.activityName)
        _ = monitor.handle(.threshold("flint.limitEvent.limit"), activity: limit.activityName)
        XCTAssertTrue(h.appliedLimits.isEmpty)
        _ = monitor.handle(.threshold(limit.eventName), activity: limit.activityName)
        XCTAssertEqual(h.appliedLimits, [limit])
    }

    func testDuplicateDailyStartCannotResetUsedTimeOrRevokeOpenCreditPC15PC16PC64() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let time = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 10,
            protection: .init(timeZoneID: "UTC")))
        let open = h.install(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        let monitor = FlintRuleMonitor(environment: h.environment)
        _ = monitor.handle(.start, activity: time.activityName)
        _ = monitor.handle(.start, activity: open.activityName)
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.appliedOpens.isEmpty)
        h.now = time.protection!.occurrence!.end
        _ = monitor.handle(.start, activity: time.activityName)
        _ = monitor.handle(.end, activity: time.activityName) // delayed previous-day end
        _ = monitor.handle(.start, activity: open.activityName)
        XCTAssertEqual(h.cleared, [time.storeName])
        XCTAssertEqual(h.appliedOpens.count, 1)
    }

    func testUnavailableDomainCallbackPreservesRegistrationAndShield() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        h.defaults.removeObject(forKey: FlintRuleStoreKey.schedules.rawValue)
        _ = FlintRuleMonitor(environment: h.environment).handle(.end, activity: rule.monitorName)
        XCTAssertTrue(h.center.stops.isEmpty); XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertNotNil(h.reports["schedules"]?.failures.first)
    }

    func testModeFlipsAssignEveryOwnedFieldAndEraseStaleExceptionsR35PC11PC65() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.schedule(tier: .easy)
        let app: ApplicationToken = try fictionalRuleToken("mode-app")
        let domain: WebDomainToken = try fictionalRuleToken("mode-domain")
        let category: ActivityCategoryToken = try fictionalRuleToken("mode-category")
        rule.selection.applicationTokens = [app]
        rule.selection.webDomainTokens = [domain]
        rule.selection.categoryTokens = [category]
        let writer = RuleShieldWriter()
        // Simulate previously explicit app/domain fields, even with opaque tokens unavailable.
        writer.apps = []; writer.domains = []
        FlintRuleShield.apply(rule, to: writer) // block -> allow
        XCTAssertNil(writer.apps); XCTAssertNil(writer.domains)
        XCTAssertEqual(writer.appCategories, .all(except: [app]))
        XCTAssertEqual(writer.domainCategories, .all(except: []))
        XCTAssertEqual(writer.fields, ["apps", "categories", "domains", "domainCategories"])
        writer.fields = []
        rule.allowListMode = false
        FlintRuleShield.apply(rule, to: writer) // allow -> block
        XCTAssertEqual(writer.appCategories, .specific([category])); XCTAssertNil(writer.domainCategories)
        XCTAssertEqual(writer.apps, [app]); XCTAssertEqual(writer.domains, [domain])
        XCTAssertEqual(writer.fields, ["apps", "categories", "domains", "domainCategories"])
    }
}

private final class RuleShieldWriter: FlintRuleShieldWriting {
    var apps: Set<ApplicationToken>?
    var appCategories: ShieldSettings.ActivityCategoryPolicy<Application>?
    var domains: Set<WebDomainToken>?
    var domainCategories: ShieldSettings.ActivityCategoryPolicy<WebDomain>?
    var fields: [String] = []
    func applications(_ tokens: Set<ApplicationToken>?) { fields.append("apps"); apps = tokens }
    func applicationCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<Application>?) { fields.append("categories"); appCategories = policy }
    func webDomains(_ tokens: Set<WebDomainToken>?) { fields.append("domains"); domains = tokens }
    func webDomainCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?) { fields.append("domainCategories"); domainCategories = policy }
}
