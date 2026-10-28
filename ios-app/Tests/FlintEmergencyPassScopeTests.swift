import XCTest
import FamilyControls
@testable import FlintCore

final class FlintEmergencyPassScopeTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!
    private var directory: URL!
    private var marker: Date?
    private let now = Date(timeIntervalSince1970: 1_793_206_800)
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }
    override func setUp() {
        suite = "FlintPassScopeTests.\(UUID())"
        defaults = UserDefaults(suiteName: suite)!
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
    }
    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: directory)
    }

    private var clock: FlintClock {
        .init(now: { self.now }, monotonic: { 1060 }, bootSessionID: { "boot" }, calendar: { self.calendar })
    }
    private func ledger() -> FlintProtectionLedger {
        .init(directory: directory, clock: clock, initializedMarker: { self.marker },
            setInitializedMarker: { self.marker = $0 }, migrate: { $0.importLegacyPass(.absent, at: $1) })
    }
    private enum Capture: Error { case pending }

    /// Obtain a real sealed capability through consumption + the exact disable transaction.
    /// Throwing from the fake effect simulates a crash before application/acknowledgement.
    private func authorization(for rules: [FlintPassCommitment]) throws -> FlintPassDisableAuthorization {
        var captured: FlintPassDisableAuthorization?
        let controller = FlintEmergencyPassController(environment: .init(ledger: { self.ledger() }, clock: clock,
            scope: { state, now in try FlintEmergencyPassScope.load(state: state, at: now,
                defaults: self.defaults, calendar: self.calendar, hasThresholdShield: { _ in false }) },
            registerCarrier: { _, _, _ in }, terminate: { _, _ in }, release: { _ in }, resume: { _, _ in },
            disable: { captured = $0; throw Capture.pending }, emit: { _ in }))
        let preview = try controller.preview().get()
        _ = try controller.use(previewRevision: preview.revision, operationID: "scope-test-pass").get()
        let current = try controller.currentScope().get()
        XCTAssertEqual(controller.disableCommitments(previewRevision: current.revision,
            ruleIDs: Set(rules.map(\.id))), .failure(.applying))
        return try XCTUnwrap(captured)
    }

    private func schedule(_ id: String, from start: Date, until end: Date, sleep: Bool = false) -> FlintScheduleRule {
        .init(id: id, name: sleep ? "Sleep" : id,
              schedule: .init(startHour: 8, endHour: 18),
              protection: .init(timeZoneID: "UTC"), oneShot: .init(start: start, end: end))
    }

    private func load(at date: Date? = nil, state: FlintLedgerState? = nil) throws -> FlintPassScope {
        try FlintEmergencyPassScope.load(state: state ?? .init(initializedAt: now),
            at: .init(wall: date ?? now, monotonic: 1060, bootID: "boot"),
            defaults: defaults, calendar: calendar, hasThresholdShield: { _ in false })
    }

    func testRulesOnlyScopeIncludesSleepOneShotAndBothKindsOfLimitPC41() throws {
        let schedules = [schedule("one-shot", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(1800)),
                         schedule("sleep.night", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200), sleep: true)]
        XCTAssertTrue(FlintDesiredRules.save(schedules, key: .schedules, to: defaults))
        XCTAssertTrue(FlintDesiredRules.save([FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30)], key: .limits, to: defaults))
        XCTAssertTrue(FlintDesiredRules.save([FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3)], key: .openLimits, to: defaults))
        let scope = try load()
        XCTAssertTrue(scope.sessions.isEmpty)
        XCTAssertTrue(scope.hasActiveCommitment)
        XCTAssertEqual(Set(scope.rules.map(\.kind)), [.sleep, .oneShot, .timeLimit, .openLimit])
        XCTAssertTrue(scope.stores.contains("flint.sleep.night"))
        XCTAssertEqual(scope.rules.first { $0.id == "schedule:one-shot" }?.naturalEnd, schedules[0].oneShot?.end)
    }

    func testExpiredOccurrenceNeverReplaysAfterRecoveryPC42() throws {
        let expiresInside = schedule("ends-inside", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(900))
        let continues = schedule("continues", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200))
        XCTAssertTrue(FlintDesiredRules.save([expiresInside, continues], key: .schedules, to: defaults))
        XCTAssertEqual(try load().rules.count, 2)
        let resumed = try load(at: now.addingTimeInterval(3600))
        XCTAssertEqual(resumed.rules.map(\.id), ["schedule:continues"])
        XCTAssertEqual(resumed.rules.first?.naturalEnd, continues.oneShot?.end)
    }

    func testNewOccurrenceBeginningDuringRecoveryIsOnlyResumedIfStillActivePC42() throws {
        let future = schedule("starts-inside", from: now.addingTimeInterval(900), until: now.addingTimeInterval(7200))
        let missed = schedule("entirely-inside", from: now.addingTimeInterval(900), until: now.addingTimeInterval(2700))
        XCTAssertTrue(FlintDesiredRules.save([future, missed], key: .schedules, to: defaults))
        XCTAssertTrue(try load().rules.isEmpty)
        XCTAssertEqual(try load(at: now.addingTimeInterval(3600)).rules.map(\.id), ["schedule:starts-inside"])
    }

    func testScopeRevisionChangesForSavedEditAndNaturalBoundaryButNotDisplayTick() throws {
        var rule = schedule("one", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(1800))
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        let first = try load().revision
        XCTAssertEqual(try load(at: now.addingTimeInterval(2)).revision, first)
        rule.name = "Different confirmed scope"
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        XCTAssertNotEqual(try load().revision, first)
        XCTAssertNotEqual(try load().revision, try load(at: now.addingTimeInterval(1800)).revision)
    }

    func testMalformedAndDuplicateRulesMakeTheEntireScopeUnavailable() throws {
        defaults.set(Data("malformed".utf8), forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertThrowsError(try load())
        let duplicate = schedule("same", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(1800))
        defaults.set(try JSONEncoder().encode([duplicate, duplicate]), forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertThrowsError(try load())
    }

    func testScopeUsesMonotonicSessionExpiryAfterWallJumpPC54PC55() throws {
        let session = FlintActiveSession(id: "session", name: "Timer", startedAt: now.addingTimeInterval(-60),
            endsAt: now.addingTimeInterval(1800), breakLevel: .harder, monitorName: "session",
            monotonicStart: 1000, bootSessionID: "boot")
        defaults.set(try JSONEncoder().encode(session), forKey: "flint.activeSession")
        XCTAssertEqual(try load(at: now.addingTimeInterval(86400)).sessions.map(\.id), [session.id])
        XCTAssertEqual(try load(at: now.addingTimeInterval(-86400)).sessions.map(\.id), [session.id])
    }

    func testUnreadableModernSessionScopeCannotSpendUsingOnlyItsName() throws {
        let active = FlintActiveSession(id: "modern", name: "Focus", startedAt: now.addingTimeInterval(-60),
            endsAt: now.addingTimeInterval(1800), breakLevel: .hardcore, monitorName: "flint.session.modern",
            contractVersion: FlintActiveSession.currentContractVersion)
        defaults.set(try JSONEncoder().encode(active), forKey: "flint.activeSession")
        XCTAssertThrowsError(try load())
        defaults.set(Data("malformed".utf8), forKey: "flint.activeSession.selection")
        XCTAssertThrowsError(try load())
    }

    func testTimeLimitThresholdObservedDuringRecoveryKeepsItsNaturalEnd() throws {
        let limit = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                                  protection: .init(timeZoneID: "UTC"))
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        var state = FlintLedgerState(initializedAt: now)
        let day = try XCTUnwrap(calendar.dateInterval(of: .day, for: now))
        state.passLimitThresholds = [limit.id: try FlintEmergencyPassScope.thresholdRecord(limit, window: day)]
        XCTAssertEqual(try load(state: state).rules.first?.wasEnforcing, true)
        XCTAssertEqual(try load(at: day.end.addingTimeInterval(1), state: state).rules.first?.wasEnforcing, false)
    }

    func testThresholdDoesNotCarryIntoChangedBudgetOrRegistration() throws {
        var limit = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                                  protection: .init(timeZoneID: "UTC"))
        let day = try XCTUnwrap(calendar.dateInterval(of: .day, for: now))
        var state = FlintLedgerState(initializedAt: now)
        state.passLimitThresholds = [limit.id: try FlintEmergencyPassScope.thresholdRecord(limit, window: day)]
        limit.thresholdMinutes = 60
        limit.protection?.revision = 2
        limit.protection?.activityRevision = 2
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        XCTAssertEqual(try load(state: state).rules.first?.wasEnforcing, false)
    }

    func testExhaustedTimeLimitRetainsItsThresholdAcrossTierAndBudgetStrengthening() throws {
        var limit = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                                  protection: .init(timeZoneID: "UTC"))
        let day = try XCTUnwrap(calendar.dateInterval(of: .day, for: now))
        var state = FlintLedgerState(initializedAt: now)
        state.passLimitThresholds = [limit.id: try FlintEmergencyPassScope.thresholdRecord(limit, window: day)]
        limit.breakLevel = .hardcore
        limit.thresholdMinutes = 15
        limit.protection?.revision = 2
        limit.protection?.activityRevision = 2
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        XCTAssertEqual(try load(state: state).rules.first?.wasEnforcing, true)
    }

    func testPassAuthorizedDisableCancelsQueueAndSleepMaterializerIdempotently() throws {
        var rule = schedule("sleep.night", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200))
        rule.protection?.queuedChange = .init(action: .turnOff, baseRevision: 1,
            window: try XCTUnwrap(rule.oneShot), snapshot: try JSONEncoder().encode(rule), fingerprint: "reviewed")
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        defaults.set(try JSONEncoder().encode(FlintSleepConfig(enabled: true, sleepAssist: .fullAssist)), forKey: "flint.sleepConfig")
        let reviewed = try load().rules
        let authorization = try authorization(for: reviewed)
        try FlintEmergencyPassRuleEffects.applyCommittedDisable(authorization, ledger: ledger(), defaults: defaults)
        let disabled = try XCTUnwrap(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults)?.first)
        XCTAssertFalse(disabled.enabled)
        XCTAssertNil(disabled.protection?.queuedChange)
        XCTAssertNil(disabled.protection?.occurrence)
        XCTAssertEqual(disabled.protection?.revision, 2)
        XCTAssertEqual(FlintSleepConfig.load(from: defaults).sleepAssist, .off)
        XCTAssertEqual(FlintSleepConfig.load(from: defaults).revision, 2)
        try FlintEmergencyPassRuleEffects.applyCommittedDisable(authorization, ledger: ledger(), defaults: defaults)
        XCTAssertEqual(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults)?.first, disabled)
        XCTAssertEqual(FlintSleepConfig.load(from: defaults).revision, 2)
    }

    func testOldDisableCannotDisableAnEnabledReplacementRevision() throws {
        var rule = schedule("one", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200))
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        let reviewed = try load().rules
        let authorization = try authorization(for: reviewed)
        rule.protection?.revision = 2
        rule.protection?.activityRevision = 2
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        XCTAssertThrowsError(try FlintEmergencyPassRuleEffects.applyCommittedDisable(authorization, ledger: ledger(), defaults: defaults))
        XCTAssertEqual(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults)?.first, rule)
    }

    func testThresholdObservationRejectsOldActivityOrEventAndPreservesCurrentBudget() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pass-threshold-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        var marker: Date?
        let clock = FlintClock(now: { self.now }, monotonic: { 1000 }, bootSessionID: { "boot" }, calendar: { self.calendar })
        let ledger = FlintProtectionLedger(directory: directory, clock: clock,
            initializedMarker: { marker }, setInitializedMarker: { marker = $0 },
            migrate: { $0.importLegacyPass(.absent, at: $1) })
        var limit = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                                  protection: .init(timeZoneID: "UTC"))
        let previousActivity = limit.activityName
        let previousEvent = limit.eventName
        limit.thresholdMinutes = 60
        limit.protection?.revision = 2
        limit.protection?.activityRevision = 2
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        let instant = FlintLedgerInstant(clock: clock)
        XCTAssertFalse(FlintEmergencyPassRuleEffects.noteThreshold(activity: previousActivity,
            event: previousEvent, at: instant, defaults: defaults, ledger: ledger))
        XCTAssertFalse(FlintEmergencyPassRuleEffects.noteThreshold(activity: limit.activityName,
            event: previousEvent, at: instant, defaults: defaults, ledger: ledger))
        XCTAssertNil(try ledger.read().get().passLimitThresholds?[limit.id])
        XCTAssertTrue(FlintEmergencyPassRuleEffects.noteThreshold(activity: limit.activityName,
            event: limit.eventName, at: instant, defaults: defaults, ledger: ledger))
        let state = try ledger.peek().get()
        XCTAssertEqual(state.passLimitThresholds?[limit.id]?.thresholdMinutes, 60)
        XCTAssertEqual(state.passLimitThresholds?[limit.id]?.window, calendar.dateInterval(of: .day, for: now))
        XCTAssertEqual(state.pass.usage, .neverUsed, "observing usage cannot spend a pass")
        XCTAssertEqual(try load(state: state).rules.first?.wasEnforcing, true)
    }

    func testThresholdObservationDoesNotMigrateOrReplaceUnreadableRules() {
        let raw = Data("unreadable rules".utf8)
        defaults.set(raw, forKey: FlintRuleStoreKey.limits.rawValue)
        XCTAssertFalse(FlintEmergencyPassRuleEffects.noteThreshold(activity: "flint.limit.time.r1",
            event: "limit.time.r1", at: .init(wall: now, monotonic: 1000, bootID: "boot"),
            defaults: defaults, ledger: nil))
        XCTAssertEqual(defaults.data(forKey: FlintRuleStoreKey.limits.rawValue), raw)
        XCTAssertNil(defaults.object(forKey: FlintRuleStoreKey.limits.rawValue + ".quarantine.adr012"))
    }

    func testExactThresholdObservationSurvivesUnavailableLedgerWithoutAuthorizingAPass() throws {
        var limit = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                                  protection: .init(timeZoneID: "UTC"))
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        XCTAssertFalse(FlintEmergencyPassRuleEffects.noteThreshold(activity: limit.activityName, event: limit.eventName,
            at: .init(clock: clock), defaults: defaults, ledger: nil))
        XCTAssertTrue(FlintLimitThresholds.hasReached(limit, at: now, in: defaults))
        XCTAssertEqual(try load().rules.first?.wasEnforcing, true)
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
        limit.thresholdMinutes = 60
        limit.protection?.revision = 2; limit.protection?.activityRevision = 2
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        XCTAssertEqual(try load().rules.first?.wasEnforcing, false, "old evidence cannot exhaust a replacement budget")
    }

    func testUnknownOrMissingEstablishedSleepConfigClosesScopeWithoutOverwritingIt() throws {
        let rule = schedule("sleep.night", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200))
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        defaults.set(try JSONEncoder().encode(FlintSleepConfig(enabled: true, sleepAssist: .fullAssist)), forKey: "flint.sleepConfig")
        let authorization = try authorization(for: load().rules)
        var fields = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(FlintSleepConfig())) as? [String: Any])
        fields["contractVersion"] = 99
        fields["futureMeaningfulState"] = true
        let raw = try JSONSerialization.data(withJSONObject: fields)
        defaults.set(raw, forKey: "flint.sleepConfig")
        XCTAssertThrowsError(try load())
        XCTAssertThrowsError(try FlintEmergencyPassRuleEffects.applyCommittedDisable(authorization, ledger: ledger(), defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: "flint.sleepConfig"), raw)
        defaults.removeObject(forKey: "flint.sleepConfig")
        defaults.set(1, forKey: "flint.sleepConfig.initialized")
        XCTAssertThrowsError(try load())
        XCTAssertNil(defaults.object(forKey: "flint.sleepConfig"))
    }

    func testOrdinaryCallerWithoutCommittedPassCapabilityCannotDisableSleep() throws {
        let rule = schedule("sleep.night", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200))
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        let config = try JSONEncoder().encode(FlintSleepConfig(enabled: true, sleepAssist: .fullAssist))
        defaults.set(config, forKey: "flint.sleepConfig")
        let saved = defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertThrowsError(try FlintEmergencyPassRuleEffects.applyCommittedDisable(nil, ledger: ledger(), defaults: defaults)) {
            XCTAssertEqual($0 as? FlintEmergencyPassError, .unavailable)
        }
        XCTAssertEqual(defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), saved)
        XCTAssertEqual(defaults.data(forKey: "flint.sleepConfig"), config)
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
    }

    func testAcknowledgedOrAlteredDisableListRejectsAnOldCapability() throws {
        let rule = schedule("one", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200))
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        let authorization = try authorization(for: load().rules)
        let original = defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        _ = try ledger().transaction { $0.emergencyRecovery?.disabledRules = [] }.get()
        XCTAssertThrowsError(try FlintEmergencyPassRuleEffects.applyCommittedDisable(authorization, ledger: ledger(), defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), original)
    }

    func testDisableCapabilityIsBoundToItsCommittedPassOperationAndScope() throws {
        let rule = schedule("one", from: now.addingTimeInterval(-300), until: now.addingTimeInterval(7200))
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: defaults))
        let permit = try authorization(for: load().rules)
        let original = defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        let oldScope = try XCTUnwrap(ledger().peek().get().emergencyRecovery?.scope)
        _ = try ledger().transaction { state in
            // Preserve a structurally valid record while changing its bound review revision.
            let altered = FlintPassScope(revision: "different-review", sessions: oldScope.sessions,
                rules: oldScope.rules, stores: oldScope.stores)
            let old = state.emergencyRecovery!
            var recovery = FlintPassRecovery(operationID: old.operationID, at: old.acceptedAt,
                scope: altered, carrierName: old.carrierName)
            recovery.disabledRules = old.disabledRules
            state.emergencyRecovery = recovery
        }.get()
        XCTAssertThrowsError(try FlintEmergencyPassRuleEffects.applyCommittedDisable(permit, ledger: ledger(), defaults: defaults))
        _ = try ledger().transaction { state in
            let old = state.emergencyRecovery!
            var replacement = FlintPassRecovery(operationID: "different-pass", at: old.acceptedAt,
                scope: oldScope, carrierName: old.carrierName)
            replacement.disabledRules = old.disabledRules
            state.pass.lastOperationID = replacement.operationID
            state.emergencyRecovery = replacement
        }.get()
        XCTAssertThrowsError(try FlintEmergencyPassRuleEffects.applyCommittedDisable(permit, ledger: ledger(), defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), original)
    }
}
