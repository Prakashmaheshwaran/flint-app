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

    func testDuplicateDailyStartKeepsTimeBudgetAndUsesGrantAwareOpenWriterPC15PC16PC64() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let time = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 10,
            protection: .init(timeZoneID: "UTC")))
        let open = h.install(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        let monitor = FlintRuleMonitor(environment: h.environment)
        _ = monitor.handle(.start, activity: time.activityName)
        _ = monitor.handle(.start, activity: open.activityName)
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertEqual(h.appliedOpens, [open], "the B5 writer preserves valid bounded grants on every trigger")
        XCTAssertTrue(h.center.starts.isEmpty, "a duplicate callback must not re-register a daily budget")
        h.now = time.protection!.occurrence!.end
        _ = monitor.handle(.start, activity: time.activityName)
        _ = monitor.handle(.end, activity: time.activityName) // delayed previous-day end
        _ = monitor.handle(.start, activity: open.activityName)
        XCTAssertEqual(h.cleared, [time.storeName])
        XCTAssertEqual(h.appliedOpens.count, 2)
        XCTAssertEqual(h.appliedOpens.last?.activityName, open.activityName)
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

    func testBusyRepairOnlyUnionsShieldFieldsAndNeverWidensCategoryExceptions() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.schedule(tier: .hardcore); rule.allowListMode = false
        let oldApp: ApplicationToken = try fictionalRuleToken("old-app")
        let newApp: ApplicationToken = try fictionalRuleToken("new-app")
        let domain: WebDomainToken = try fictionalRuleToken("old-domain")
        let category: ActivityCategoryToken = try fictionalRuleToken("new-category")
        rule.selection.applicationTokens = [newApp]; rule.selection.categoryTokens = [category]
        let writer = RuleShieldWriter()
        writer.apps = [oldApp]; writer.domains = [domain]
        writer.appCategories = .all(except: [oldApp]); writer.domainCategories = .all()
        FlintRuleShield.add(rule, to: writer)
        XCTAssertEqual(writer.apps, [oldApp, newApp])
        XCTAssertEqual(writer.domains, [domain]); XCTAssertEqual(writer.domainCategories, .all(except: []))
        XCTAssertEqual(writer.appCategories, .all(except: []))
        XCTAssertFalse(writer.fields.contains("domains")); XCTAssertFalse(writer.fields.contains("domainCategories"))
    }

    func testNativeRuleWebChildUsesSavedZoneFrozenAndOneShotOccurrence() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = ISO8601DateFormatter().date(from: "2026-10-28T20:00:00Z")!
        let config = FlintWebRestrictions(enabled: false, applyDuringSchedules: true)
        for mode in ["zone", "frozen", "oneShot"] {
            var rule = h.schedule(tier: .hardcore)
            let window = FlintRuleWindow(start: h.now.addingTimeInterval(-3600), end: h.now.addingTimeInterval(3600))
            if mode == "zone" { rule.protection?.timeZoneID = "America/Los_Angeles" }
            if mode == "frozen" { rule.protection?.occurrence = window }
            if mode == "oneShot" { rule.oneShot = window }
            let writer = RuleWebWriter()
            FlintRuleShield.reconcileWeb(rule, config: config, at: h.now, writer: writer)
            XCTAssertEqual(writer.applied, [rule.storeName], mode)
            XCTAssertTrue(writer.cleared.isEmpty)
        }
    }

    func testBusyOpenAdditionsPreserveOnlyVerifiedLiveCreditsWithoutRemovingExistingFields() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let granted: ApplicationToken = try fictionalRuleToken("granted")
        let blocked: ApplicationToken = try fictionalRuleToken("blocked")
        var rule = FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3,
            protection: .init(timeZoneID: "UTC"))
        rule.selection.applicationTokens = [granted, blocked]
        let target = FlintOpenLimit.key(for: granted)
        let instant = FlintLedgerInstant(clock: h.clock)
        let snapshot = try XCTUnwrap(FlintOpenCreditRestoration.make(kind: .application,
            token: granted, storeNames: [rule.storeName]))
        let grant = FlintOpenCreditGrant(targetKey: target, operationID: "credit", grantedAt: instant,
            deadline: h.now.addingTimeInterval(300), duration: 300, coveredCauses: [rule.id],
            coveredFingerprints: [rule.id: try FlintOpenCredit.effectiveFingerprint(for: rule)],
            coveredScopes: [rule.id: try FlintOpenCredit.effectiveFingerprint(for: rule, includingCap: false)],
            coveredCaps: [rule.id: rule.opensAllowed], coveredActivities: [rule.id: rule.activityName], restoration: snapshot)
        XCTAssertTrue(grant.isValid)
        let live = FlintOpenCredit.shieldAdditions(for: rule, grants: [target: grant], at: instant, registered: { _ in true })
        XCTAssertEqual(live.selection.applicationTokens, [blocked])
        let writer = RuleShieldWriter(); writer.apps = [granted]
        FlintRuleShield.add(live, to: writer)
        XCTAssertEqual(writer.apps, [granted, blocked], "the fallback never releases an existing assertion")
        XCTAssertEqual(FlintOpenCredit.shieldAdditions(for: rule, grants: [target: grant], at: instant,
            registered: { _ in false }).selection.applicationTokens, [granted, blocked])
        h.advance(300)
        XCTAssertEqual(FlintOpenCredit.shieldAdditions(for: rule, grants: [target: grant],
            at: .init(clock: h.clock), registered: { _ in true }).selection.applicationTokens, [granted, blocked])
        rule.protection?.activityRevision += 1; rule.protection?.revision += 1
        XCTAssertEqual(FlintOpenCredit.shieldAdditions(for: rule, grants: [target: grant], at: instant,
            registered: { _ in true }).selection.applicationTokens, [granted, blocked])
    }
}

private final class RuleWebWriter: FlintWebRestrictionWriting {
    var applied: [String] = []
    var cleared: [String] = []
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) { applied.append(storeName) }
    func clear(store storeName: String) { cleared.append(storeName) }
}

private final class RuleShieldWriter: FlintRuleShieldReading {
    var apps: Set<ApplicationToken>?
    var appCategories: ShieldSettings.ActivityCategoryPolicy<Application>?
    var domains: Set<WebDomainToken>?
    var domainCategories: ShieldSettings.ActivityCategoryPolicy<WebDomain>?
    var fields: [String] = []
    var currentApplications: Set<ApplicationToken>? { apps }
    var currentApplicationCategories: ShieldSettings.ActivityCategoryPolicy<Application>? { appCategories }
    var currentWebDomains: Set<WebDomainToken>? { domains }
    var currentWebDomainCategories: ShieldSettings.ActivityCategoryPolicy<WebDomain>? { domainCategories }
    func applications(_ tokens: Set<ApplicationToken>?) { fields.append("apps"); apps = tokens }
    func applicationCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<Application>?) { fields.append("categories"); appCategories = policy }
    func webDomains(_ tokens: Set<WebDomainToken>?) { fields.append("domains"); domains = tokens }
    func webDomainCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?) { fields.append("domainCategories"); domainCategories = policy }
}
