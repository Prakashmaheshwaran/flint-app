import XCTest
@testable import FlintCore

final class FlintOpenCreditMigrationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_188_800)
    private var instant: FlintLedgerInstant { .init(wall: now, monotonic: 1000, bootID: "fixture-boot") }
    private var calendar: Calendar {
        var result = Calendar(identifier: .gregorian); result.timeZone = TimeZone(secondsFromGMT: 0)!; return result
    }

    private func imported(_ raw: FlintLegacyValue, keys: Set<String>? = ["app"], established: Bool = true) -> FlintLedgerState {
        var state = FlintLedgerState(initializedAt: now)
        state.importLegacyOpenCredits(raw, targetKeys: keys, established: established, at: instant, calendar: calendar)
        return state
    }
    private func legacy(_ counts: [String: Int], dayStart: Date? = nil) throws -> Data {
        try JSONEncoder().encode(FlintOpenLimitState(dayStart: dayStart ?? now, counts: counts))
    }

    func testValidLegacyFixtureRetainsOverCapCountsAndDayStartSemantics() throws {
        let old = now.addingTimeInterval(-86_400)
        let state = imported(.value(try legacy(["app": 9], dayStart: old)))
        XCTAssertEqual(state.openCredits.availability, .ready)
        XCTAssertEqual(state.openCredits.counters["app"]?.used, 9)
        XCTAssertEqual(state.openCredits.periodStart, calendar.startOfDay(for: old))
        XCTAssertEqual(state.openCreditPreview(target: "app", caps: [3], blockedBy: nil, at: instant).remaining, 0,
                       "a wall date alone must not reset the imported period")
    }

    func testLegacyImportIdempotenceDoesNotReplaceConsumption() throws {
        var state = imported(.value(try legacy(["app": 2])))
        state.openCredits.counters["app"]?.used = 3
        state.importLegacyOpenCredits(.value(try legacy([:])), targetKeys: ["app"], established: true,
                                      at: instant, calendar: calendar)
        XCTAssertEqual(state.openCredits.counters["app"]?.used, 3)
    }

    func testEstablishedAbsentMalformedNegativeFutureAndUnreadableFixturesStayUnavailable() throws {
        let fixtures: [FlintLegacyValue] = [
            .absent, .unreadable, .value("not bytes"), .value(Data("{truncated".utf8)),
            .value(try legacy(["app": -1])), .value(try legacy(["app": 1], dayStart: now.addingTimeInterval(100))),
            .value(Data(#"{"counts":{"app":2}}"#.utf8)),
        ]
        for raw in fixtures {
            let state = imported(raw)
            XCTAssertEqual(state.openCredits.availability, .unavailable)
            XCTAssertNil(state.openCreditPreview(target: "app", caps: [3], blockedBy: nil, at: instant).remaining)
        }
        XCTAssertEqual(imported(.absent, established: false).openCredits.availability, .ready)
        XCTAssertEqual(imported(.absent, keys: nil, established: false).openCredits.availability, .unavailable)
    }

    func testMalformedLegacyPeriodOnlyRecoversAtObservedMidnight() {
        var state = imported(.unreadable)
        var later = instant
        later.wall = now.addingTimeInterval(7 * 86_400) // clock edit alone is no reset
        XCTAssertTrue(state.reconcileOpenCredits(at: later, targetKeys: ["app"], reshield: { _ in }))
        XCTAssertEqual(state.openCredits.availability, .unavailable)
        later.monotonic += state.openCredits.resetRemaining
        XCTAssertTrue(state.reconcileOpenCredits(at: later, targetKeys: ["app"], reshield: { _ in }))
        XCTAssertEqual(state.openCredits.availability, .ready)
        XCTAssertEqual(state.openCredits.counters["app"]?.used, 0)
    }

    func testRotatedLegacyTokenIsRetainedAndReplacementGetsNoFreshPool() throws {
        let state = imported(.value(try legacy(["old-token": 2])), keys: ["replacement"])
        XCTAssertEqual(state.openCredits.counters["old-token"]?.used, 2)
        XCTAssertNil(state.openCredits.counters["replacement"])
        XCTAssertEqual(state.openCreditPreview(target: "replacement", caps: [3], blockedBy: nil, at: instant).blockedBy,
                       .identityNeedsReselection)
    }

    func testSavedEmptyLegacyRuleArrayIsEstablishedNotAFreshInstall() throws {
        let suite = "flint.tests.empty-open-setup.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        FlintOpenLimitRule.saveAll([], to: defaults)
        var state = FlintLedgerState(initializedAt: now)
        FlintOpenCredit.importLegacy(into: &state, defaults: defaults, at: instant, calendar: calendar)
        XCTAssertEqual(state.openCredits.availability, .unavailable,
                       "deleted legacy rules plus a missing counter cannot mint a new pool")
    }

    func testPassOnlyLedgerDoesNotInventAnEstablishedOpenSetup() throws {
        let suite = "flint.tests.established-open-migration.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        var state = FlintLedgerState(initializedAt: now)
        state.importLegacyPass(.absent, at: instant)
        FlintOpenCredit.importLegacy(into: &state, defaults: defaults, at: instant, calendar: calendar)
        XCTAssertEqual(state.openCredits.availability, .ready)
        XCTAssertFalse(state.openCredits.enrolled)
    }

    func testFirstEverOpenRuleAfterPassOnlyMigrationGetsItsFirstPoolImmediately() throws {
        let suite = "flint.tests.first-open.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        var state = FlintLedgerState(initializedAt: now)
        state.importLegacyPass(.absent, at: instant)
        FlintOpenCredit.importLegacy(into: &state, defaults: defaults, at: instant, calendar: calendar)
        XCTAssertTrue(FlintOpenLimitRule.saveAll([.init(id: "first-rule", name: "First", opensAllowed: 3)], to: defaults))
        XCTAssertTrue(state.reconcileOpenCredits(at: instant, targetKeys: ["first-target"], setupCalendar: calendar, reshield: { _ in }))
        XCTAssertEqual(state.openCreditPreview(target: "first-target", caps: [3], blockedBy: nil, at: instant).remaining, 3)
        XCTAssertEqual(state.openCredits.counters["first-target"]?.used, 0)
    }

    func testStaleLegacyDayStartWaitsForOneObservedBoundaryBeforeRefill() throws {
        var state = imported(.value(try legacy(["app": 9], dayStart: now.addingTimeInterval(-86_400))))
        XCTAssertTrue(state.reconcileOpenCredits(at: instant, targetKeys: ["app"], reshield: { _ in }))
        XCTAssertEqual(state.openCredits.counters["app"]?.used, 9)
        var reset = instant
        reset.monotonic += state.openCredits.resetRemaining
        reset.wall = now.addingTimeInterval(state.openCredits.resetRemaining)
        XCTAssertTrue(state.reconcileOpenCredits(at: reset, targetKeys: ["app"], reshield: { _ in }))
        XCTAssertEqual(state.openCreditPreview(target: "app", caps: [3], blockedBy: nil, at: reset).remaining, 3)
        XCTAssertTrue(state.reconcileOpenCredits(at: reset, targetKeys: ["app"], reshield: { _ in }))
        XCTAssertEqual(state.openCredits.counters["app"]?.used, 0)
    }

    func testQuotaZoneCapturedAtFirstTargetSetupThenSurvivesTravel() {
        var state = imported(.absent, keys: [], established: false)
        var setupZone = calendar; setupZone.timeZone = TimeZone(identifier: "America/New_York")!
        XCTAssertTrue(state.reconcileOpenCredits(at: instant, targetKeys: ["app"], setupCalendar: setupZone, reshield: { _ in }))
        XCTAssertEqual(state.openCredits.quotaZone, "America/New_York")
        XCTAssertTrue(state.reconcileOpenCredits(at: instant, targetKeys: ["app"], setupCalendar: calendar, reshield: { _ in }))
        XCTAssertEqual(state.openCredits.quotaZone, "America/New_York", "device travel does not move an established period")
    }

    func testV1LedgerFixtureUpgradesAdditivelyWithoutChangingPassOrHarder() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-v1-fixture-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Exact pre-credit schema fixture: optional nil fields were absent in v1.
        let fixture = Data(#"{"version":1,"initializedAt":1793188800,"migrations":["pass.v0"],"pass":{"usage":"used","cooldownRemaining":12345,"recoveryRemaining":0,"lastOperationID":"old-pass"},"harder":{"cause":{"committedCount":2}},"operations":[{"id":"old-pass","kind":"pass","result":"consumed","at":1793188800}]}"#.utf8)
        let file = directory.appendingPathComponent("ledger.json")
        try fixture.write(to: file)
        let bytes = try legacy(["app": 4])
        let clock = FlintClock(now: { self.now }, monotonic: { 1000 }, bootSessionID: { "fixture-boot" }, calendar: { self.calendar })
        let ledger = FlintProtectionLedger(directory: directory, clock: clock,
            initializedMarker: { self.now }, setInitializedMarker: { _ in }, migrate: { state, at in
                state.importLegacyOpenCredits(.value(bytes), targetKeys: ["app"], established: true, at: at, calendar: self.calendar)
            })
        let migrated = try ledger.read().get()
        XCTAssertEqual(migrated.version, 2)
        XCTAssertEqual(migrated.pass.lastOperationID, "old-pass")
        XCTAssertEqual(migrated.pass.cooldownRemaining, 12345)
        XCTAssertEqual(migrated.harder["cause"]?.committedCount, 2)
        XCTAssertEqual(migrated.openCredits.counters["app"]?.used, 4)
        XCTAssertEqual(try ledger.read().get(), migrated)
        let durable = try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self, from: Data(contentsOf: file))
        XCTAssertEqual(durable.version, 2)
    }

    func testMissingV2CreditFieldIsCorruptionNotV1Migration() throws {
        let state = imported(.absent, established: false)
        let bytes = try FlintProtectionLedger.makeEncoder().encode(state)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        object["openCredits"] = nil
        XCTAssertThrowsError(try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
                                                                         from: JSONSerialization.data(withJSONObject: object)))
    }

    func testEarlierV2MetadataOmissionsPreserveCounterPassAndQuota() throws {
        var source = imported(.value(try legacy(["app": 4])))
        source.importLegacyPass(.absent, at: instant)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: FlintProtectionLedger.makeEncoder().encode(source)) as? [String: Any])
        var quota = try XCTUnwrap(object["openCredits"] as? [String: Any])
        quota["previousAcceptedOperations"] = nil
        var counters = try XCTUnwrap(quota["counters"] as? [String: [String: Any]])
        counters["app"]?["lastSeen"] = nil
        quota["counters"] = counters
        object["openCredits"] = quota
        let decoded = try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
            from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(decoded.openCredits.previousAcceptedOperations, [])
        XCTAssertEqual(decoded.openCredits.counters["app"]?.lastSeen, source.openCredits.periodStart)
        XCTAssertEqual(decoded.openCredits.counters["app"]?.used, 4)
        XCTAssertEqual(decoded.pass, source.pass)
        XCTAssertEqual(decoded.openCredits.resetRemaining, source.openCredits.resetRemaining)
        XCTAssertEqual(decoded.openCreditPreview(target: "app", caps: [3], blockedBy: nil, at: instant).remaining, 0)
        XCTAssertTrue(decoded.openCredits.isValid)
    }

    func testRequiredV2QuotaAndCounterFieldsRemainStrict() throws {
        let source = imported(.value(try legacy(["app": 2])))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: FlintProtectionLedger.makeEncoder().encode(source)) as? [String: Any])
        let original = try XCTUnwrap(object["openCredits"] as? [String: Any])
        for key in ["availability", "resetRemaining", "counters", "knownTargets", "pendingTargets",
                    "grants", "carrierCheckpoints", "acceptedOperations", "enrolled"] {
            var fixture = object, quota = original
            quota[key] = nil
            fixture["openCredits"] = quota
            XCTAssertThrowsError(try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
                from: JSONSerialization.data(withJSONObject: fixture)), "missing \(key) must not default")
        }
        for key in ["targetKey", "periodStart", "used"] {
            var fixture = object, quota = original
            var counters = try XCTUnwrap(quota["counters"] as? [String: [String: Any]])
            counters["app"]?[key] = nil
            quota["counters"] = counters; fixture["openCredits"] = quota
            XCTAssertThrowsError(try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
                from: JSONSerialization.data(withJSONObject: fixture)), "missing counter \(key) must not default")
        }
    }

    func testMalformedAdditiveV2MetadataIsNotTreatedAsAbsent() throws {
        let source = imported(.value(try legacy(["app": 2])))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: FlintProtectionLedger.makeEncoder().encode(source)) as? [String: Any])
        var fixture = object
        var quota = try XCTUnwrap(fixture["openCredits"] as? [String: Any])
        quota["previousAcceptedOperations"] = "invalid"
        fixture["openCredits"] = quota
        XCTAssertThrowsError(try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
            from: JSONSerialization.data(withJSONObject: fixture)))
        fixture = object
        quota = try XCTUnwrap(fixture["openCredits"] as? [String: Any])
        var counters = try XCTUnwrap(quota["counters"] as? [String: [String: Any]])
        counters["app"]?["lastSeen"] = "invalid"
        quota["counters"] = counters; fixture["openCredits"] = quota
        XCTAssertThrowsError(try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
            from: JSONSerialization.data(withJSONObject: fixture)))
    }

    func testMigrationCrashCheckpointsAreAtomicAndIdempotent() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-import-crash-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let bytes = try legacy(["app": 2])
        let suite = "flint.tests.open-migration.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(bytes, forKey: FlintLedgerState.legacyOpenCreditKey)
        let clock = FlintClock(now: { self.now }, monotonic: { 1000 }, bootSessionID: { "fixture-boot" }, calendar: { self.calendar })
        var marker: Date?
        func handle() -> FlintProtectionLedger {
            FlintProtectionLedger(directory: directory, clock: clock, initializedMarker: { marker },
                setInitializedMarker: { marker = $0 }, migrate: { state, at in
                    let raw = defaults.object(forKey: FlintLedgerState.legacyOpenCreditKey).map(FlintLegacyValue.value) ?? .absent
                    state.importLegacyOpenCredits(raw, targetKeys: ["app"], established: true,
                                                  at: at, calendar: self.calendar)
                })
        }
        // Death before mutation/persist: migration-in-memory and marker are discarded.
        struct Crash: Error {}
        let aborted: Result<Void, FlintLedgerError> = handle().transaction { _ in throw Crash() }
        guard case .failure = aborted else { return XCTFail("expected abort") }
        XCTAssertNil(marker)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("ledger.json").path))
        XCTAssertEqual(try handle().read().get().openCredits.counters["app"]?.used, 2)
        // Death after atomic rename but before the external marker: existing file wins.
        marker = nil
        XCTAssertEqual(try handle().read().get().openCredits.counters["app"]?.used, 2)
        _ = handle().transaction { $0.openCredits.counters["app"]?.used = 3 }
        XCTAssertEqual(try handle().read().get().openCredits.counters["app"]?.used, 3)
        XCTAssertEqual(defaults.data(forKey: FlintLedgerState.legacyOpenCreditKey), bytes, "legacy source bytes remain unchanged")
    }
}
