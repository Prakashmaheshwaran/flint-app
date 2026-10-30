import XCTest
@testable import FlintCore

private final class FrozenWebWriter: FlintWebRestrictionWriting {
    var stores: [String: FlintWebRestrictions] = [:]
    var applies = 0
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) { stores[storeName] = config; applies += 1 }
    func clear(store storeName: String) { stores.removeValue(forKey: storeName) }
}

final class FlintWebRestrictionOccurrenceTests: XCTestCase {
    private func health(_ rules: [FlintScheduleRule], observed: [String]? = nil) -> FlintArmingHealth {
        .init(domains: [
            "session": .init(enabled: 0, attempted: 0, armed: 0, failures: [], observedActivities: []),
            "schedules": .init(enabled: rules.count, attempted: rules.count, armed: rules.count, failures: [],
                               observedActivities: observed ?? rules.map(\.monitorName))
        ])
    }

    func testSavedLAOccurrenceSurvivesFullUTCHostReconcileAndRawFutureWeakeningR2N02R29() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = ISO8601DateFormatter().date(from: "2026-10-28T20:00:00Z")!
        var draft = h.schedule(tier: .hardcore)
        draft.protection = .init(timeZoneID: "America/Los_Angeles")
        let rule = h.install(draft)
        XCTAssertFalse(rule.schedule.isWindowActive(at: h.now, calendar: h.clock.calendar()), "Device UTC is outside 09–17")
        let config = FlintWebRestrictions(applyDuringSchedules: true, blockedDomains: ["blocked.example"], denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: h.defaults))
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = FrozenWebWriter()
        let callback = FlintWebRestrictionLifecycle(writer: writer, clock: h.clock, children: children)
        callback.reconcileRule(rule, config: config, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
        let host = FlintWebRestrictionLifecycle(writer: writer, clock: h.clock,
            children: .init(defaults: h.defaults)) // independent host/process projection
        func reconcile(_ globals: FlintWebRestrictions?) -> FlintArmingHealth.DomainReport {
            FlintWebRestrictionHostReconciler.reconcile(snapshot: .init(session: nil, rules: [rule]),
                config: globals, health: health([rule]), lifecycle: host, now: h.now, clock: h.clock)
        }
        XCTAssertEqual(reconcile(.default).armed, 1, "Counts derive from the frozen child")
        XCTAssertEqual(writer.stores[rule.storeName], config)
        h.now.addTimeInterval(86400)
        XCTAssertEqual(reconcile(.default).armed, 1, "Wall-only changes cannot expire the saved occurrence")
        XCTAssertEqual(writer.stores[rule.storeName], config)
        h.advance(4 * 3600) // LA's trusted 17:00 end
        XCTAssertEqual(reconcile(nil).armed, 0)
        XCTAssertTrue(writer.stores.isEmpty, "The last actual end clears even with unavailable global intent")
        guard case let .available(envelope) = children.read() else { return XCTFail() }
        XCTAssertTrue(envelope.children.isEmpty)
    }

    func testOneShotSavedWindowOverridesAnInactiveRecurringTemplateAndEligibilityCannotPrune() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var draft = h.schedule(tier: .hardcore)
        draft.schedule = .init(startHour: 1, endHour: 2)
        draft.oneShot = .init(start: h.now.addingTimeInterval(-60), end: h.now.addingTimeInterval(120))
        let rule = h.install(draft)
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = FrozenWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, clock: h.clock, children: children)
        lifecycle.reconcileRule(rule, config: config, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        let report = FlintWebRestrictionHostReconciler.reconcile(snapshot: .init(session: nil, rules: [rule]),
            config: .default, health: health([rule], observed: []), lifecycle: lifecycle, now: h.now, clock: h.clock)
        XCTAssertEqual(report.armed, 0, "An unverified registration is preserved, not counted as an apply")
        XCTAssertFalse(report.failures.isEmpty)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        guard case let .available(envelope) = children.read() else { return XCTFail() }
        XCTAssertEqual(envelope.children.count, 1)
        h.advance(120)
        _ = FlintWebRestrictionHostReconciler.reconcile(snapshot: .init(session: nil, rules: [rule]),
            config: .default, health: health([rule], observed: []), lifecycle: lifecycle, now: h.now, clock: h.clock)
        XCTAssertTrue(writer.stores.isEmpty)
        h.advance(86400)
        lifecycle.reconcileRule(rule, config: config, now: h.now, clock: h.clock)
        XCTAssertTrue(writer.stores.isEmpty, "A one-shot child cannot recur tomorrow")
    }

    func testOptInStrengtheningReachesLiveChildAndLaterRawOptOutCannotReleaseIt() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let writer = FrozenWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, clock: h.clock, children: .init(defaults: h.defaults))
        lifecycle.reconcileRule(rule, config: .default, now: h.now, clock: h.clock)
        XCTAssertTrue(writer.stores.isEmpty)
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        lifecycle.reconcileRule(rule, config: config, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        lifecycle.reconcileRule(rule, config: .default, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[rule.storeName], config)
    }

    func testPassClearsEveryStoreWithoutPruningFrozenRuleThenResumeRestoresItR29PC72() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let config = FlintWebRestrictions(applyDuringSchedules: true, denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: h.defaults))
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = FrozenWebWriter()
        let web = FlintWebRestrictionLifecycle(writer: writer, recoveryGate: {
            .read(ledger: h.ledger, at: .init(clock: h.clock))
        }, clock: h.clock, children: children)
        _ = try h.ledger.read().get()
        web.reconcileRule(rule, config: config, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
        let pass = FlintEmergencyPassController(environment: .init(ledger: { h.ledger }, clock: h.clock,
            scope: { state, at in try FlintEmergencyPassScope.load(state: state, at: at, defaults: h.defaults,
                calendar: h.clock.calendar(), hasThresholdShield: { _ in false }) },
            registerCarrier: { _, _, _ in }, terminate: { _, _ in }, release: { _ in writer.stores.removeAll() },
            resume: { state, at in try FlintEmergencyPassRuleEffects.resume(state: state, at: at,
                environment: h.environment, web: web, existingWebStores: { false }) }, emit: { _ in }))
        let preview = try pass.preview().get()
        _ = try pass.use(previewRevision: preview.revision, operationID: "frozen-pass").get()
        XCTAssertTrue(writer.stores.isEmpty)
        let frozen = children.read()
        _ = FlintWebRestrictionHostReconciler.reconcile(snapshot: .init(session: nil, rules: [rule]),
            config: .default, health: health([rule]), lifecycle: web, now: h.now, clock: h.clock)
        XCTAssertTrue(writer.stores.isEmpty)
        XCTAssertEqual(children.read(), frozen, "Recovery suspension is not the occurrence's end")
        _ = try pass.resumeNow().get()
        XCTAssertEqual(writer.stores[rule.storeName], config)
        XCTAssertEqual(h.reports["webRestrictions"]?.armed, 1, "Resume counts the frozen policy rather than raw opt-out")
        XCTAssertEqual(try h.ledger.peek().get().operations.filter { $0.kind == "pass" }.count, 1)
        h.advance(5 * 3600)
        web.reconcileRule(rule, config: .default, now: h.now, clock: h.clock)
        XCTAssertTrue(writer.stores.isEmpty)
        guard case let .available(ended) = children.read() else { return XCTFail() }
        XCTAssertTrue(ended.children.isEmpty)
    }

    func testUnknownSavedRulePolicyCannotAuthorizeDeviceCalendarCleanup() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let saved = h.install(h.schedule(tier: .hardcore))
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = FrozenWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, clock: h.clock, children: children)
        lifecycle.reconcileRule(saved, config: config, now: h.now, clock: h.clock)
        let frozen = children.read()
        var unknown = saved; unknown.protection = nil; unknown.enabled = false
        lifecycle.reconcileRule(unknown, config: .default, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[saved.storeName], config)
        let report = FlintWebRestrictionHostReconciler.reconcile(snapshot: .init(session: nil, rules: [unknown]),
            config: .default, health: health([unknown]), lifecycle: lifecycle, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[saved.storeName], config)
        XCTAssertFalse(report.failures.isEmpty)
        XCTAssertEqual(children.read(), frozen)
    }
}
