import XCTest
@testable import FlintCore

private final class RuleChildWriter: FlintWebRestrictionWriting {
    var stores: [String: FlintWebRestrictions] = [:]
    var writes = 0
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) { stores[storeName] = config; writes += 1 }
    func clear(store storeName: String) { stores.removeValue(forKey: storeName); writes += 1 }
}

final class FlintWebRestrictionRuleAdapterTests: XCTestCase {
    func testPositiveOrIndeterminateNativeProbeFreezesUnavailableInsteadOfOptOut() throws {
        for evidence: Bool? in [true, nil] {
            let h = try RuleHarness(); defer { h.clean() }
            let rule = h.install(h.schedule(tier: .hardcore))
            var probes = 0
            var environment = h.environment
            environment.existingWebStores = { probes += 1; return evidence }
            XCTAssertNotNil(environment.freezeOccurrences([rule]))
            XCTAssertEqual(probes, 1)
            let children = FlintWebRestrictionChildren(defaults: h.defaults)
            let cause = FlintWebRestrictionChildren.Cause.rule(rule,
                window: try XCTUnwrap(rule.occurrence(at: h.now, clock: h.clock)))
            let frozen = children.read()
            XCTAssertNil(children.frozen(cause, fallback: .default))
            guard case let .available(envelope) = frozen else { return XCTFail("Unavailable intent must be explicitly frozen") }
            XCTAssertEqual(envelope.children.count, 1)
            XCTAssertTrue(children.needsRecovery)
            let config = FlintWebRestrictions(applyDuringSchedules: true)
            XCTAssertTrue(config.save(to: h.defaults))
            environment.existingWebStores = { XCTFail("Present key bypasses only the native evidence query"); return false }
            XCTAssertNotNil(environment.freezeOccurrences([rule]))
            XCTAssertEqual(children.read(), frozen, "An intervening raw edit cannot replace unavailable freeze-time policy")
            let writer = RuleChildWriter(); writer.stores[rule.storeName] = config
            var notes: [String] = []
            FlintWebRestrictionRuleAdapter.reconcile(rule, read: .available(config), owners: [rule], children: children,
                writer: writer, clock: h.clock, record: { notes.append($0) })
            XCTAssertEqual(writer.stores[rule.storeName], config)
            XCTAssertEqual(writer.writes, 0)
            XCTAssertFalse(notes.isEmpty)
        }
    }

    func testNativeReadUsesFrozenPolicyAndRejectsAStaleOwnerSnapshot() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let config = FlintWebRestrictions(applyDuringSchedules: true, blockedDomains: ["blocked.example"])
        XCTAssertTrue(config.save(to: h.defaults))
        XCTAssertTrue(FlintWebRestrictionRuleAdapter.freeze([rule], environment: h.environment, existingWebStores: { false }))
        XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = RuleChildWriter()
        var notes: [String] = []
        FlintWebRestrictionRuleAdapter.reconcile(rule, read: .available(.default), owners: [rule], children: children,
            writer: writer, clock: h.clock, record: { notes.append($0) })
        XCTAssertEqual(writer.stores[rule.storeName], config)
        var newer = rule; newer.protection?.revision += 1
        for owners: [FlintScheduleRule]? in [[newer], nil] {
            FlintWebRestrictionRuleAdapter.reconcile(rule, read: .available(.default), owners: owners, children: children,
                writer: writer, clock: h.clock, record: { notes.append($0) })
        }
        XCTAssertEqual(writer.writes, 1)
        XCTAssertEqual(notes.count, 2)
        XCTAssertEqual(writer.stores[rule.storeName], config)
    }

    func testBusyAndUnavailableNativeReadsPreserveEveryKeyForFullAndPositiveWriters() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let config = FlintWebRestrictions(applyDuringSchedules: true, denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: h.defaults))
        XCTAssertTrue(FlintWebRestrictionRuleAdapter.freeze([rule], environment: h.environment, existingWebStores: { false }))
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = RuleChildWriter(); writer.stores[rule.storeName] = config
        var notes: [String] = []
        for addingOnly in [false, true] {
            for read: FlintWebRestrictionRead in [.busy, .unavailable] {
                FlintWebRestrictionRuleAdapter.reconcile(rule, read: read, owners: [rule], children: children,
                    writer: writer, clock: h.clock, addingOnly: addingOnly, record: { notes.append($0) })
            }
        }
        XCTAssertEqual(writer.writes, 0)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        XCTAssertEqual(notes.count, 4)
        XCTAssertTrue(notes.contains { $0.contains("busy") })
        XCTAssertTrue(notes.contains { $0.contains("unavailable") })
    }

    func testPositiveOnlyWriterCannotCreateAChildAfterMissingFreeze() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let writer = RuleChildWriter()
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        var notes: [String] = []
        FlintWebRestrictionRuleAdapter.reconcile(rule, read: .available(.init(applyDuringSchedules: true)),
            owners: [rule], children: children, writer: writer, clock: h.clock, addingOnly: true, record: { notes.append($0) })
        XCTAssertEqual(writer.writes, 0)
        XCTAssertEqual(children.read(), .legacy)
        XCTAssertEqual(notes.count, 1)
    }

    func testApplyLockedAndAddLockedDeferBusyOrUnavailableWithoutWebWrites() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let config = FlintWebRestrictions(applyDuringSchedules: true, denyExplicitMedia: true)
        let writer = RuleChildWriter(); writer.stores[rule.storeName] = config
        let children = FlintWebRestrictionChildren.isolated()
        var shieldWrites = 0, notes: [String] = []
        for read: FlintWebRestrictionRead in [.busy, .unavailable] {
            FlintRuleShield.applyLocked(rule, clock: h.clock, applyShield: { shieldWrites += 1 }, readWeb: { read },
                webOwners: { XCTFail("An unavailable read needs no owner/native probe"); return nil }, webChildren: children,
                webWriter: writer, recordWeb: { notes.append($0) })
            FlintRuleShield.addLocked(rule, clock: h.clock, addShield: { shieldWrites += 1 }, readWeb: { read },
                webOwners: { XCTFail("An unavailable read needs no owner/native probe"); return nil }, webChildren: children,
                webWriter: writer, recordWeb: { notes.append($0) })
        }
        XCTAssertEqual(shieldWrites, 4, "Web availability cannot prevent app protection")
        XCTAssertEqual(writer.writes, 0)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        XCTAssertEqual(notes.count, 4)
    }

    func testFailedFirstArmAndInterveningRawEditKeepFreezeTimeChildOnFirstNativeApply() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let config = FlintWebRestrictions(applyDuringSchedules: true, blockedDomains: ["blocked.example"])
        XCTAssertTrue(config.save(to: h.defaults))
        let rule = h.install(h.schedule(tier: .hardcore))
        h.center.held.removeValue(forKey: .init(rule.activityName))
        h.center.refused.insert(rule.activityName)
        let writer = RuleChildWriter()
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        var environment = h.environment
        environment.applyRuleShield = { incoming, clock in
            FlintRuleShield.applyLocked(incoming, clock: clock, applyShield: {}, readWeb: {
                FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }).map(FlintWebRestrictionRead.available) ?? .unavailable
            }, webOwners: { h.load(FlintScheduleRule.self) }, webChildren: children, webWriter: writer,
            recordWeb: { XCTFail($0) })
            return true
        }
        _ = FlintSchedulesController(environment: environment).reload()
        XCTAssertTrue(writer.stores.isEmpty)
        let frozen = children.read()
        guard case let .available(envelope) = frozen else { return XCTFail("Freeze must precede the rejected first arm") }
        XCTAssertEqual(envelope.children.map(\.config), [config])
        XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
        h.center.refused.remove(rule.activityName)
        _ = FlintSchedulesController(environment: environment).reload()
        XCTAssertEqual(writer.stores[rule.storeName], config)
        XCTAssertEqual(children.read(), frozen)
    }

    func testNativeDeferralNotesReachWebsiteHealthAsWellAsRuleDiagnostics() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let group = try XCTUnwrap(FlintGroupStore(suiteName: h.suite))
        FlintWebRestrictionRuleAdapter.record(rule, note: "Safari reconciliation deferred: busy", in: group)
        let health = group.loadArmingHealth()
        for domain in ["webRestrictions", "ruleTime.schedules"] {
            XCTAssertEqual(health.domains[domain]?.failures.first?.activityName, rule.activityName)
            XCTAssertTrue(health.domains[domain]?.failures.first?.reason.contains("busy") == true)
        }
    }
}
