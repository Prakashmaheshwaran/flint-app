import XCTest
import FlintCore

final class FlintProtectionStatusCurrentStateTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-10-28T15:00:00Z")!
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private func fixture() throws -> (UserDefaults, FlintGroupStore) {
        let suite = "FlintProtectionStatusCurrentStateTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let store = try XCTUnwrap(FlintGroupStore(suiteName: suite))
        store.saveRules([.init(id: "schedule", name: "Work", schedule: .init(startHour: 9, endHour: 17))])
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        return (defaults, store)
    }

    private func environment(store: FlintGroupStore? = nil) -> FlintProtectionStatus.ObservationEnvironment {
        .init(authorized: { true }, registeredActivities: { ["flint.rule.schedule"] },
              clock: .init(now: { self.now }, monotonic: { 100 }, bootSessionID: { "fixture-boot" },
                           calendar: { self.calendar }),
              loadActiveSession: { store?.loadActiveSession() },
              hasActiveSessionRecord: { store?.loadActiveSession() != nil },
              loadArmingHealth: { store?.loadArmingHealth() ?? .init() })
    }

    private var observations: [String: FlintProtectionStatus.RuleObservation] {
        ["schedule": .init(armed: true, observed: true)]
    }

    private var pass: FlintLedgerState.Pass {
        var value = FlintLedgerState.Pass()
        value.usage = .neverUsed
        return value
    }

    func testScheduleWithoutManualSessionHasNoApplicableDurationAnchorAndPublishesProtected() throws {
        let (defaults, _) = try fixture()
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults,
            ledgerPass: pass, ruleObservations: observations, quotaCalendar: calendar, environment: environment())
        XCTAssertNil(inputs.timeIntegrity)
        XCTAssertFalse(inputs.timeIntegrityApplies)
        XCTAssertEqual(FlintProtectionStatusBuilder.make(inputs).state, .protected)
        let snapshot = FlintProtectionStatus.publishFromCurrentState(now: now, defaults: defaults,
            ledgerPass: pass, ruleObservations: observations, quotaCalendar: calendar, writer: .monitor,
            environment: environment(), effects: .init(reloadTimelines: { _ in }, postDarwinNotification: { _ in }))
        XCTAssertEqual(snapshot.state, .protected)
        XCTAssertEqual(snapshot.headline?.id, "schedule")
        XCTAssertEqual(snapshot.writer, "monitor")
        XCTAssertEqual(FlintProtectionStatus.load(now: now, from: defaults), snapshot)
    }

    func testAForwardWallJumpKeepsTheSessionLiveWithItsLogicalEndPC54() throws {
        let (defaults, store) = try fixture()
        // One minute of real time has passed (monotonic 40 → 100); the wall clock reads 30
        // minutes later, so the recorded wall end already looks past.
        store.saveActiveSession(.init(id: "live", name: "Focus", startedAt: now.addingTimeInterval(-1860),
            endsAt: now.addingTimeInterval(-60), breakLevel: .hardcore, monitorName: "flint.session.live",
            monotonicStart: 40, bootSessionID: "fixture-boot", plannedDuration: 1800))
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults,
            ledgerPass: pass, ruleObservations: observations, quotaCalendar: calendar, environment: environment(store: store))
        XCTAssertEqual(try XCTUnwrap(inputs.activeSession?.logicalEnd).timeIntervalSince(now), 1740, accuracy: 1)
        XCTAssertTrue(inputs.timeIntegrityApplies, "a live session's clock verdict applies")
        let snapshot = FlintProtectionStatusBuilder.make(inputs)
        XCTAssertTrue(snapshot.degradedReasons.contains("registration_failed"),
                      "treated as running (here without a registration), not as over")
    }

    func testExpiredSessionDoesNotMakeScheduleTimeUncertain() throws {
        let (defaults, store) = try fixture()
        store.saveActiveSession(.init(id: "old", name: "Old", startedAt: now.addingTimeInterval(-120),
            endsAt: now.addingTimeInterval(-60), breakLevel: .harder, monitorName: "old-monitor"))
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults,
            ledgerPass: pass, ruleObservations: observations, quotaCalendar: calendar, environment: environment(store: store))
        let snapshot = FlintProtectionStatusBuilder.make(inputs)
        XCTAssertEqual(inputs.activeSession?.session.id, "old")
        XCTAssertFalse(inputs.timeIntegrityApplies)
        XCTAssertEqual(snapshot.state, .protected)
        XCTAssertNil(snapshot.session)
        XCTAssertEqual(snapshot.causes.map(\.id), ["schedule"])
        XCTAssertFalse(snapshot.degradedReasons.contains("time_uncertain"))
    }

    func testPublicGroupStoreLiveSessionRecordBridgesToCurrentInputs() throws {
        let (defaults, store) = try fixture()
        store.saveActiveSession(.init(id: "live", name: "Live", startedAt: now.addingTimeInterval(-60),
            endsAt: now.addingTimeInterval(600), breakLevel: .harder, monitorName: "live-monitor"))
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults,
            ledgerPass: pass, ruleObservations: observations, quotaCalendar: calendar, environment: environment(store: store))
        XCTAssertEqual(inputs.activeSession?.session.id, "live")
        XCTAssertTrue(inputs.timeIntegrityApplies)
    }

    func testRecentOrFutureRecordedClockAnomalyDegradesButOldDiagnosticDoesNot() throws {
        let (defaults, _) = try fixture()
        for age in [-60.0, 60.0, 3600.0] {
            let data = try JSONSerialization.data(withJSONObject: ["lastTimeAnomaly": [
                "kind": "wallAhead", "seconds": 30, "sessionID": "old",
                "detectedAt": now.addingTimeInterval(-age).timeIntervalSinceReferenceDate
            ]])
            let health = try JSONDecoder().decode(FlintSessionHealth.self, from: data)
            health.save(to: defaults)
            let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults,
                ledgerPass: pass, ruleObservations: observations, quotaCalendar: calendar, environment: environment())
            let result = FlintProtectionStatusBuilder.make(inputs)
            XCTAssertEqual(result.state, age < 300 ? .degraded : .protected)
            XCTAssertEqual(result.degradedReasons.contains("time_uncertain"), age < 300)
        }
    }

    func testMissingDefaultsIsUnavailableAndDoesNotPublishEffects() {
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: nil, ledgerPass: pass,
            ruleObservations: observations, quotaCalendar: calendar, environment: environment())
        XCTAssertEqual(FlintProtectionStatusBuilder.make(inputs).state, .unavailable)
        var calls = 0
        let result = FlintProtectionStatus.publishFromCurrentState(now: now, defaults: nil, ledgerPass: pass,
            quotaCalendar: calendar, environment: environment(),
            effects: .init(reloadTimelines: { _ in calls += 1 }, postDarwinNotification: { _ in calls += 1 }))
        XCTAssertEqual(result.state, .unavailable)
        XCTAssertEqual(calls, 0)
    }

    func testPublicSessionPresenceSeamPreservesUnreadableRatherThanNoSession() throws {
        let (defaults, _) = try fixture()
        var source = environment()
        source.hasActiveSessionRecord = { true }
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults, ledgerPass: pass,
            ruleObservations: observations, quotaCalendar: calendar, environment: source)
        XCTAssertTrue(inputs.recordUnreadable)
        XCTAssertEqual(FlintProtectionStatusBuilder.make(inputs).state, .unavailable)
    }

    func testTypedRuleLoadersPreserveCorruptionAsUnavailable() throws {
        for key in ["flint.rules", "flint.limits", "flint.openLimit.rules"] {
            let (defaults, _) = try fixture()
            defaults.set(Data("corrupt-fixture".utf8), forKey: key)
            let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults, ledgerPass: pass,
                ruleObservations: observations, quotaCalendar: calendar, environment: environment())
            XCTAssertTrue(inputs.recordUnreadable)
            XCTAssertEqual(FlintProtectionStatusBuilder.make(inputs).state, .unavailable)
        }
    }

    func testNativeSessionOriginIsProjectedWithoutOwnerIdentity() throws {
        let (defaults, store) = try fixture()
        store.saveActiveSession(.init(id: "live", name: "Live", startedAt: now.addingTimeInterval(-60),
            endsAt: now.addingTimeInterval(600), breakLevel: .harder, monitorName: "live-monitor",
            monotonicStart: 40, bootSessionID: "fixture-boot", contractVersion: 1,
            origin: "focus_filter", ownerID: "private-owner-sentinel"))
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults, ledgerPass: pass,
            ruleObservations: observations, sessionArmed: true, quotaCalendar: calendar, environment: environment(store: store))
        let result = FlintProtectionStatusBuilder.make(inputs)
        XCTAssertEqual(result.session?.origin, .focusFilter)
        let data = try JSONEncoder().encode(result)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("private-owner-sentinel"))
    }
}
