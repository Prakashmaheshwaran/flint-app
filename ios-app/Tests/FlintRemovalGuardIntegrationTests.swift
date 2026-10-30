import XCTest
import FamilyControls
import DeviceActivity
@testable import FlintCore

final class FlintRemovalGuardIntegrationTests: XCTestCase {
    func testPC78SessionAndScheduleEndIndependently() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn()
        let rule = try h.createSchedule()
        let session = try h.start(duration: 300)
        XCTAssertEqual(session.removalGuard, true)
        XCTAssertEqual(rule.protection?.removalGuard, true)
        XCTAssertEqual(h.derive().decision, .deny)
        h.rules.advance(300)
        h.controller().handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNil(h.store.session)
        XCTAssertEqual(h.guardValue, true, "Ending one owner must retain the schedule guard")
        h.rules.advance(to: try XCTUnwrap(rule.protection?.occurrence?.end))
        XCTAssertTrue(FlintRuleMonitor(environment: h.ruleEnvironment).handle(.end, activity: rule.activityName))
        XCTAssertNil(h.guardValue)
        XCTAssertTrue(h.events.allSatisfy { !$0.hasPrefix("write:flint:") })
    }

    func testInactiveTemplateNeverArmsAndCapturesOnlyAtAcceptedStart() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        var future = h.rules.schedule(tier: .hardcore)
        future.oneShot = .init(start: h.rules.now.addingTimeInterval(600), end: h.rules.now.addingTimeInterval(1200))
        let rule = try h.save(future)
        try h.optIn()
        XCTAssertNil(rule.protection?.removalGuard)
        XCTAssertEqual(h.derive().decision, .release)
        h.rules.advance(600)
        XCTAssertTrue(FlintRuleMonitor(environment: h.ruleEnvironment).handle(.start, activity: rule.activityName))
        XCTAssertEqual(h.rules.load(FlintScheduleRule.self).first?.protection?.removalGuard, true)
        XCTAssertEqual(h.guardValue, true)
    }

    func testOptInDoesNotBackfillMigratedSessionOrRunningOccurrence() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        let old = h.rules.install(h.rules.schedule(tier: .hardcore))
        let session = FlintActiveSession(id: "legacy", name: "Legacy", startedAt: h.rules.now,
            endsAt: h.rules.now.addingTimeInterval(300), breakLevel: .hardcore, monitorName: "flint.session.legacy")
        h.store.session = session
        try h.rules.ledger.transaction { $0.sessionAuthority?.adopt(session) }.get()
        try h.optIn()
        h.legacyValue = true
        XCTAssertEqual(h.derive().decision, .release)
        XCTAssertNil(h.legacyValue)
        XCTAssertTrue(FlintRuleMonitor(environment: h.ruleEnvironment).handle(.start, activity: old.activityName))
        XCTAssertNil(h.rules.load(FlintScheduleRule.self).first?.protection?.removalGuard)
        XCTAssertNil(h.guardValue)
        XCTAssertNil(h.store.session?.removalGuard)
    }

    func testPendingOffPersistsDoesNotChangeCaptureAndCannotBeExtendedByNewOwners() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn()
        let session = try h.start(duration: 600)
        let settings = FlintRemovalGuardSettings(environment: h.environment)
        guard case .deferred = settings.request(enabled: false) else { return XCTFail("Must defer") }
        let loaded = try XCTUnwrap(h.rules.defaults.data(forKey: FlintRemovalGuardPreference.key))
        let pending = try FlintRemovalGuardPreference.decode(loaded)
        XCTAssertTrue(pending.enabled)
        XCTAssertNotNil(pending.deferredOffUntil)
        XCTAssertFalse(FlintRemovalGuardSettings.capture(defaults: h.rules.defaults))
        XCTAssertEqual(h.store.session?.removalGuard, true)
        XCTAssertEqual(h.derive().decision, .deny)
        let nextRule = try h.createSchedule()
        XCTAssertEqual(nextRule.protection?.removalGuard, false)
        h.rules.advance(600)
        h.controller().handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        guard case let .value(saved, _) = h.preference.load() else { return XCTFail() }
        XCTAssertFalse(saved.enabled)
        XCTAssertNil(saved.deferredOffUntil)
        XCTAssertNil(h.guardValue)
    }

    func testDeferredOffRefusesReplacementWithoutExtendingAndReenableAllowsIt() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn()
        let old = try h.start(duration: 300)
        guard case .deferred = FlintRemovalGuardSettings(environment: h.environment).request(enabled: false) else { return XCTFail() }
        for origin in [FlintSessionOrigin.appUI, .shortcut, .focusFilter, .automation] {
            XCTAssertThrowsError(try h.controller().startBlockNow(selection: .init(), duration: 600,
                breakLevel: .hardcore, origin: origin, confirmed: true)) {
                XCTAssertEqual($0 as? FlintSessionError, .replacementDenied(until: old.endsAt, guardOffPending: true))
                XCTAssertTrue($0.localizedDescription.contains("turn Restrict app deletion"))
            }
        }
        XCTAssertEqual(h.store.session?.id, old.id)
        XCTAssertEqual(h.guardValue, true)
        XCTAssertEqual(FlintRemovalGuardSettings(environment: h.environment).request(enabled: true, disclosureAccepted: true), .saved)
        let replacement = try h.start(duration: 600)
        XCTAssertEqual(replacement.removalGuard, true)
        XCTAssertEqual(h.guardValue, true)
    }

    func testSessionAfterDeferredOffDeadlineCapturesOff() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn(); _ = try h.start(duration: 300)
        guard case .deferred = FlintRemovalGuardSettings(environment: h.environment).request(enabled: false) else { return XCTFail() }
        h.rules.advance(300)
        _ = h.controller().reconcile()
        let next = try h.start(duration: 600)
        XCTAssertEqual(next.removalGuard, false)
        XCTAssertNil(h.guardValue)
    }

    func testPassEndsDeferredSessionAndAllowsNewOffCaptureAfterResume() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn(); _ = try h.start(duration: 600)
        guard case .deferred = FlintRemovalGuardSettings(environment: h.environment).request(enabled: false) else { return XCTFail() }
        XCTAssertEqual(h.guardValue, true)
        let pass = h.passController()
        _ = try pass.use(previewRevision: pass.preview().get().revision, operationID: "session-pass").get()
        XCTAssertNil(h.store.session); XCTAssertNil(h.guardValue)
        _ = try pass.resumeNow().get()
        XCTAssertEqual(try h.start(duration: 600).removalGuard, false)
        XCTAssertNil(h.guardValue)
    }

    func testFailedPassGuardApplyRetainsLegacyThenReplaysCommittedRecovery() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn(); _ = try h.createSchedule()
        let pass = h.passController()
        let preview = try pass.preview().get()
        h.migrated = false; h.legacyValue = true; h.events = []; h.failure = "write"
        XCTAssertThrowsError(try pass.use(previewRevision: preview.revision, operationID: "failed-apply").get())
        XCTAssertEqual(h.legacyValue, true); XCTAssertFalse(h.migrated)
        XCTAssertFalse(h.events.contains("legacy")); XCTAssertFalse(h.events.contains("marker"))
        h.failure = "none"; h.events = []
        _ = try pass.reconcile().get()
        XCTAssertEqual(h.events.prefix(3), ["write:flint.guard:nil", "legacy", "marker"])
        XCTAssertNil(h.legacyValue); XCTAssertNil(h.guardValue)
        XCTAssertEqual(try h.rules.ledger.read().get().pass.lastOperationID, "failed-apply")
    }

    func testFailedOrPartialDomainAndActivityReadsKeepWithoutMigration() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn(); _ = try h.createSchedule()
        let state = try h.rules.ledger.read().get()
        let schedules = h.rules.load(FlintScheduleRule.self)
        let activities = Set(h.rules.center.activities.map(\.rawValue))
        for domain in 0..<6 {
            var env = h.environment
            env.inventory = { clock in
                FlintRemovalGuardInventory.project(session: nil, recordPresent: domain == 0,
                    state: domain == 1 ? nil : state, schedules: domain == 2 ? nil : schedules,
                    limits: domain == 3 ? nil : [], openLimits: domain == 4 ? nil : [],
                    activities: domain == 5 ? nil : activities, clock: clock)
            }
            h.events = []; h.migrated = false; h.legacyValue = true
            XCTAssertEqual(FlintRemovalGuardReconciler(environment: env).reconcile().decision, .keep)
            XCTAssertTrue(h.events.isEmpty)
            XCTAssertEqual(h.legacyValue, true); XCTAssertFalse(h.migrated)
        }
        h.rules.defaults.set(Data("corrupt".utf8), forKey: FlintRuleStoreKey.limits.rawValue)
        h.events = []
        XCTAssertEqual(h.derive().decision, .keep)
        XCTAssertTrue(h.events.isEmpty, "Live collector propagates a failed sibling domain")
    }

    func testPositivelyAbsentRegistrationReleasesButUnavailableDoesNot() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn(); _ = try h.createSchedule()
        XCTAssertEqual(h.derive().decision, .deny)
        h.rules.center.held = [:]
        XCTAssertEqual(h.derive().decision, .release)
        XCTAssertNil(h.guardValue)
    }

    func testMigrationOrdersDerivedWriteOldClearMarkerAndRetriesEveryInterruption() throws {
        for failure in ["write", "legacy", "marker", "none"] {
            let h = try RemovalGuardHarness(); defer { h.clean() }
            h.failure = failure; h.legacyValue = true
            let report = h.derive()
            XCTAssertEqual(report.decision, .release)
            XCTAssertEqual(report.migrated, failure == "none")
            XCTAssertEqual(h.events.first, "write:flint.guard:nil")
            if failure == "write" { XCTAssertEqual(h.legacyValue, true); XCTAssertEqual(h.events.count, 1) }
            else { XCTAssertEqual(h.events[1], "legacy") }
            if failure != "none" { XCTAssertFalse(h.migrated) }
            h.failure = "none"; h.events = []
            XCTAssertTrue(h.derive().migrated)
            XCTAssertNil(h.guardValue); XCTAssertNil(h.legacyValue)
            if failure == "none" { XCTAssertEqual(h.events, ["write:flint.guard:nil"]) }
            else { XCTAssertEqual(h.events, ["write:flint.guard:nil", "legacy", "marker"]) }
        }
    }

    func testPC46CommittedPassAllowsReleaseSuppressesArmingAndResumeDerivesRemainingOwner() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn(); _ = try h.createSchedule()
        XCTAssertEqual(h.derive().decision, .deny)
        let pass = h.passController()
        let preview = try pass.preview().get()
        h.migrated = false; h.legacyValue = true; h.events = []
        _ = try pass.use(previewRevision: preview.revision, operationID: "guard-pass").get()
        XCTAssertTrue(FlintPassRecoveryGate.read(ledger: h.rules.ledger, at: .init(clock: h.rules.clock)).isRecovering)
        XCTAssertEqual(h.events.prefix(3), ["write:flint.guard:nil", "legacy", "marker"])
        XCTAssertNil(h.guardValue); XCTAssertNil(h.legacyValue)
        var second = h.rules.schedule(tier: .hardcore); second.id = "new-in-recovery"
        _ = try h.save(second)
        XCTAssertEqual(h.derive().decision, .release, "Even a new occurrence is covered by committed recovery")
        _ = try pass.resumeNow().get()
        XCTAssertEqual(h.derive().decision, .deny)
    }

    func testInterruptedStartRecoveryDerivesOnlyAfterDurableApplication() throws {
        for failRecovery in [false, true] {
            let h = try RemovalGuardHarness(); defer { h.clean() }
            try h.optIn()
            XCTAssertThrowsError(try h.start(duration: 600, afterCommit: { throw RemovalGuardHarness.Failure.injected }))
            XCTAssertTrue(h.events.isEmpty)
            h.shield.failApply = failRecovery
            _ = h.controller().reconcile()
            if failRecovery {
                XCTAssertTrue(h.events.isEmpty, "Installing/failed recovery is unknown; no native guard write")
                XCTAssertFalse(h.migrated)
            } else {
                XCTAssertEqual(h.guardValue, true)
                XCTAssertEqual(try h.rules.ledger.read().get().sessionAuthority?.latest?.phase, .applied)
            }
        }
    }

    func testRuleCreateEditOffEachDerivesOnceAfterCommit() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn()
        var calls = 0
        h.rules.onGuardReconcile = {
            calls += 1
            let saved = h.rules.load(FlintScheduleRule.self)
            XCTAssertEqual(saved.count, 1)
            if let saved = saved.first, saved.enabled {
                XCTAssertNotNil(h.rules.center.held[.init(saved.activityName)])
            }
        }
        var rule = try h.createSchedule(tier: .easy)
        XCTAssertEqual(calls, 1)
        rule.name = "Changed"
        _ = try h.save(rule)
        XCTAssertEqual(calls, 2)
        rule = try XCTUnwrap(h.rules.load(FlintScheduleRule.self).first)
        let arbiter = FlintRuleArbiter(environment: h.ruleEnvironment)
        guard case let .confirmationRequired(ticket, _) = arbiter.setEnabled(FlintScheduleRule.self,
            id: rule.id, enabled: false, revision: rule.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: ticket), .applied)
        XCTAssertEqual(calls, 3)
    }

    func testSettingsDisclosureAuthorizationAndUnknownState() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        let settings = FlintRemovalGuardSettings(environment: h.environment)
        XCTAssertFalse(settings.status().enabled)
        XCTAssertEqual(settings.request(enabled: true), .disclosureRequired)
        XCTAssertEqual(h.preference.load(), .absent)
        XCTAssertEqual(settings.request(enabled: true, disclosureAccepted: true), .saved)
        XCTAssertEqual(settings.status().label, "Requested")
        _ = try h.createSchedule()
        XCTAssertTrue(settings.status().label.hasPrefix("On until "))
        h.authorized = false
        XCTAssertEqual(settings.status().label, "Unavailable (Screen Time not authorized)")
        h.rules.defaults.set(Data("bad".utf8), forKey: FlintRemovalGuardPreference.key)
        XCTAssertEqual(settings.request(enabled: false), .unavailable)
        XCTAssertFalse(FlintRemovalGuardSettings.capture(defaults: h.rules.defaults))
    }

    func testAdditiveRuleAndSessionCodecAndRearmPreserveCapturedValue() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        var metadata = FlintRuleProtection(timeZoneID: "UTC")
        metadata.occurrence = .init(start: h.rules.now, end: h.rules.now.addingTimeInterval(600))
        var old = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(metadata)) as? [String: Any])
        old.removeValue(forKey: "removalGuard")
        let decoded = try JSONDecoder().decode(FlintRuleProtection.self, from: JSONSerialization.data(withJSONObject: old))
        XCTAssertNil(decoded.removalGuard); XCTAssertTrue(decoded.isValid)
        metadata.removalGuard = true
        XCTAssertEqual(try JSONDecoder().decode(FlintRuleProtection.self, from: JSONEncoder().encode(metadata)), metadata)
        try h.optIn()
        let session = try h.start(duration: 600)
        let plan = FlintSessionAutoClearPlan.make(start: h.rules.now, end: h.rules.now.addingTimeInterval(600), calendar: h.rules.clock.calendar())
        XCTAssertEqual(session.rearmed(monitorName: "new", plan: plan, at: h.rules.now, countsTowardLimit: true).removalGuard, true)
        XCTAssertEqual(session.checkpointed(clock: h.rules.clock).removalGuard, true)
        XCTAssertEqual(try JSONDecoder().decode(FlintActiveSession.self, from: JSONEncoder().encode(session)), session)
    }

    func testQueuedDailyChangeCapturesCurrentPreferenceOnlyAtBoundary() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn()
        var initial = FlintLimitRule(id: "daily", name: "Daily", thresholdMinutes: 30, breakLevel: .hardcore,
            protection: .init(timeZoneID: "UTC"))
        initial.protection?.removalGuard = true
        let old = h.rules.install(initial)
        var change = old; change.thresholdMinutes = 60
        let arbiter = FlintRuleArbiter(environment: h.ruleEnvironment)
        guard case let .deferRequired(ticket, end) = arbiter.upsert(change) else { return XCTFail() }
        XCTAssertEqual(arbiter.upsert(change, confirmation: ticket), .queued(until: end))
        try h.preference.save(.init()) // Simulates future intent changing; never rewrites the old capture.
        XCTAssertEqual(h.rules.load(FlintLimitRule.self).first?.protection?.removalGuard, true)
        h.rules.advance(to: end)
        XCTAssertTrue(arbiter.applyQueued(FlintLimitRule.self))
        let next = try XCTUnwrap(h.rules.load(FlintLimitRule.self).first)
        XCTAssertEqual(next.thresholdMinutes, 60)
        XCTAssertEqual(next.protection?.removalGuard, false)
        XCTAssertNotEqual(next.protection?.occurrence?.identity, old.protection?.occurrence?.identity)
        XCTAssertNil(h.guardValue)
    }

    func testSameOccurrenceEditsPreserveNilAndTrueRatherThanBackfillingPreference() throws {
        for captured in [nil, true] as [Bool?] {
            let h = try RemovalGuardHarness(); defer { h.clean() }
            var initial = h.rules.schedule(tier: .hardcore)
            initial.protection?.removalGuard = captured
            var old = h.rules.install(initial)
            try h.optIn()
            old.schedule.endHour = 18
            let next = try h.save(old)
            XCTAssertEqual(next.protection?.removalGuard, captured)
            XCTAssertEqual(next.protection?.occurrence?.identity, old.protection?.occurrence?.identity)
        }
    }

    func testClockDiscontinuityAndRebootWaitForOwnerCheckpointThenExpireBoundedly() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        try h.optIn(); let session = try h.start(duration: 600)
        h.rules.now.addTimeInterval(-3600)
        XCTAssertEqual(h.derive().decision, .deny)
        h.rules.boot = "new-boot"; h.rules.monotonic = 10
        h.events = []
        XCTAssertEqual(h.derive().decision, .keep)
        XCTAssertTrue(h.events.isEmpty, "An uncheckpointed fallback cannot re-arm")
        _ = h.controller().reconcile()
        XCTAssertEqual(h.guardValue, true)
        h.rules.advance(600)
        h.controller().handleMonitorEvent(.intervalDidEnd, activity: h.store.session?.monitorName ?? session.monitorName)
        XCTAssertNil(h.guardValue)
    }

    func testOpenEndedLegacyCannotArmAndBusyAuthorityCannotWriteOrShowOff() throws {
        let h = try RemovalGuardHarness(); defer { h.clean() }
        let legacy = FlintActiveSession(name: "Legacy", startedAt: h.rules.now, endsAt: nil,
            breakLevel: .hardcore, monitorName: "flint.session.legacy")
        h.store.session = legacy
        try h.rules.ledger.transaction { $0.sessionAuthority?.adopt(legacy) }.get()
        try h.optIn()
        XCTAssertEqual(h.derive().decision, .release)
        var busy = h.environment; busy.withAuthority = { _ in false }
        h.events = []
        XCTAssertEqual(FlintRemovalGuardReconciler(environment: busy).reconcile().decision, .keep)
        XCTAssertTrue(h.events.isEmpty)
        XCTAssertTrue(FlintRemovalGuardSettings(environment: busy).status().unavailable)
    }
}

private final class RemovalGuardHarness {
    enum Failure: Error { case injected }
    let rules: RuleHarness
    let store = RemovalSessionStore()
    let shield = RemovalSessionShield()
    var events: [String] = []
    var guardValue: Bool?
    var legacyValue: Bool?
    var migrated = false
    var authorized = true
    var failure = "none"
    var preference: FlintRemovalGuardPreference.Store { .init(defaults: rules.defaults) }
    init() throws {
        rules = try RuleHarness()
        store.defaults = rules.defaults
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: rules.environment))
        _ = try rules.ledger.read().get()
    }
    func clean() { rules.onGuardReconcile = nil; rules.clean() }
    var environment: FlintRemovalGuardReconciler.Environment {
        .init(clock: rules.clock, withAuthority: { body in
            FlintRuleStoreLock.withLock(environment: self.rules.environment) { body(); return true } ?? false
        }, inventory: { clock in
            FlintRemovalGuardInventory.load(environment: self.rules.environment, store: self.store, clock: clock)
        }, isAuthorized: { self.authorized }, write: { name, value in
            self.events.append("write:\(name):\(value == true ? "true" : "nil")")
            if self.failure == "write" { throw Failure.injected }
            if value == true {
                let phase = try self.rules.ledger.peek().get().sessionAuthority?.latest?.phase
                XCTAssertNotEqual(phase, .installing)
            }
            self.guardValue = value
        }, migrationComplete: { self.migrated }, clearLegacy: {
            self.events.append("legacy")
            if self.failure == "legacy" { throw Failure.injected }
            self.legacyValue = nil
        }, markMigration: {
            self.events.append("marker")
            if self.failure == "marker" { throw Failure.injected }
            self.migrated = true
        }, preference: preference)
    }
    @discardableResult func derive() -> FlintRemovalGuardReconciler.Report {
        FlintRemovalGuardReconciler(environment: environment).reconcile()
    }
    func optIn() throws {
        try preference.save(.init(enabled: true, disclosedAt: rules.now, disclosureVersion: 1))
    }
    var ruleEnvironment: FlintRuleReloadEnvironment {
        var env = rules.environment
        let recorder = env.reconcileRemovalGuard
        env.reconcileRemovalGuard = { recorder(); _ = self.derive() }
        env.recoveryGate = { .read(ledger: self.rules.ledger, at: .init(clock: self.rules.clock)) }
        return env
    }
    func save(_ rule: FlintScheduleRule) throws -> FlintScheduleRule {
        let arbiter = FlintRuleArbiter(environment: ruleEnvironment)
        let result = arbiter.upsert(rule)
        if case let .confirmationRequired(ticket, _) = result { XCTAssertEqual(arbiter.upsert(rule, confirmation: ticket), .applied) }
        else { XCTAssertEqual(result, .applied) }
        return try XCTUnwrap(rules.load(FlintScheduleRule.self).first { $0.id == rule.id })
    }
    func createSchedule(tier: BreakLevel = .hardcore) throws -> FlintScheduleRule { try save(rules.schedule(tier: tier)) }
    func controller(afterCommit: @escaping () throws -> Void = {}) -> FlintSessionController {
        FlintSessionController(environment: .init(store: { self.store }, shield: shield,
            webRestrictions: RemovalSessionWeb(), registrar: RemovalSessionRegistrar(center: rules.center),
            isAuthorized: { self.authorized }, clock: rules.clock, healthDefaults: { self.rules.defaults },
            ledger: { self.rules.ledger }, transitionLock: { .init(directory: self.rules.directory, timeout: 0.2) },
            recoveryGate: { .read(ledger: self.rules.ledger, at: .init(clock: self.rules.clock)) },
            afterStartCommit: afterCommit, captureRemovalGuard: { FlintRemovalGuardSettings.capture(defaults: self.rules.defaults) },
            removalGuardOffPending: {
                guard case let .value(value, _) = self.preference.load() else { return false }
                return value.deferredOffUntil != nil
            },
            reconcileRemovalGuard: { _ = self.derive() }))
    }
    func start(duration: TimeInterval, afterCommit: @escaping () throws -> Void = {}) throws -> FlintActiveSession {
        try controller(afterCommit: afterCommit).startBlockNow(selection: .init(), duration: duration,
            breakLevel: .hardcore, origin: .appUI, confirmed: true)
    }
    func passController() -> FlintEmergencyPassController {
        FlintEmergencyPassController(environment: .init(ledger: { self.rules.ledger }, clock: rules.clock,
            scope: { state, now in
                try FlintEmergencyPassScope.load(state: state, at: now, defaults: self.rules.defaults,
                    calendar: self.rules.clock.calendar())
            }, registerCarrier: { _, _, _ in }, stopCarrier: { _ in }, terminate: { sessions, acceptedAt in
                for session in sessions {
                    guard self.controller().endCommittedEmergencyPass(session, at: acceptedAt) else { throw Failure.injected }
                }
            },
            release: { _ in
                XCTAssertTrue(FlintPassRecoveryGate.read(ledger: self.rules.ledger, at: .init(clock: self.rules.clock)).isRecovering)
                let report = self.derive()
                guard report.applied && report.migrated else { throw Failure.injected }
            }, resume: { _, _ in _ = self.derive() }, emit: { _ in }))
    }
}

private final class RemovalSessionStore: FlintSessionStateStoring {
    var defaults: UserDefaults?
    var session: FlintActiveSession?
    var selection: FamilyActivitySelection?
    func loadActiveSession() -> FlintActiveSession? { session }
    func hasActiveSessionRecord() -> Bool { session != nil }
    func saveActiveSession(_ session: FlintActiveSession) {
        self.session = session
        defaults?.set(try? JSONEncoder().encode(session), forKey: "flint.activeSession")
    }
    func clearActiveSession() { session = nil; defaults?.removeObject(forKey: "flint.activeSession") }
    func saveSelection(_ selection: FamilyActivitySelection) { self.selection = selection }
    func loadSelection() -> FamilyActivitySelection? { selection }
    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) {
        struct Snapshot: Encodable { let sessionID: String; let selection: FamilyActivitySelection }
        self.selection = selection
        defaults?.set(try? JSONEncoder().encode(Snapshot(sessionID: sessionID, selection: selection)), forKey: "flint.activeSession.selection")
    }
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? { selection }
    func clearSessionSelection() { selection = nil; defaults?.removeObject(forKey: "flint.activeSession.selection") }
}
private final class RemovalSessionShield: FlintSessionShielding {
    var failApply = false
    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { .init(applications: 1) }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets { .init(applications: ["A"]) }
    func fingerprint(of selection: FamilyActivitySelection) -> String { "A" }
    func applyBlockList(_ selection: FamilyActivitySelection) throws { if failApply { throw RemovalGuardHarness.Failure.injected } }
    func clear() {}
}
private struct RemovalSessionWeb: FlintSessionWebRestricting { func reconcile(_ session: FlintActiveSession, clock: FlintClock) {} }
private final class RemovalSessionRegistrar: FlintSessionActivityRegistering {
    let center: FakeActivityCenter
    init(center: FakeActivityCenter) { self.center = center }
    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        try center.startMonitoring(.init(name), during: .init(intervalStart: plan.intervalStart,
            intervalEnd: plan.intervalEnd, repeats: false, warningTime: plan.warningTime), events: [:])
    }
    func stopMonitoring(_ names: [String]) { center.stopMonitoring(names.map { .init($0) }) }
    func registeredActivityNames() -> Set<String> { Set(center.activities.map(\.rawValue)) }
}
