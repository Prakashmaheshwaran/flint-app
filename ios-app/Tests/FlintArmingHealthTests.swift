import XCTest
import FlintCore

private struct LegacyArmingDomainReport: Codable {
    let attempted: Int
    let failures: [FlintArmingHealth.Failure]
    let updatedAt: Date
}

/// Pure arming-health accounting: domain merges, the near-cap warning boundary, and the
/// App-Group round-trip. (The DeviceActivity registrations whose outcomes this ledger records
/// are device-gated, like every shield.)
final class FlintArmingHealthTests: XCTestCase {

    func testEmptyLedgerIsHealthyAndQuiet() {
        let health = FlintArmingHealth()
        XCTAssertTrue(health.isHealthy)
        XCTAssertEqual(health.enabledTotal, 0)
        XCTAssertEqual(health.attemptedTotal, 0)
        XCTAssertEqual(health.armedTotal, 0)
        XCTAssertEqual(health.failures, [])
        XCTAssertFalse(health.isNearCap)
    }

    func testReplacingOneDomainKeepsTheOthers() {
        let health = FlintArmingHealth()
            .replacing("schedules", with: .init(enabled: 5, attempted: 4, armed: 4, failures: []))
            .replacing("limits", with: .init(enabled: 2, attempted: 2, armed: 2, failures: []))
        XCTAssertEqual(health.enabledTotal, 7)
        XCTAssertEqual(health.attemptedTotal, 6)
        XCTAssertEqual(health.armedTotal, 6)

        let updated = health.replacing(
            "schedules", with: .init(enabled: 1, attempted: 1, armed: 1, failures: []))
        XCTAssertEqual(updated.enabledTotal, 3)
        XCTAssertEqual(updated.attemptedTotal, 3, "schedules replaced, limits kept")
        XCTAssertEqual(updated.armedTotal, 3)
        XCTAssertEqual(updated.domains["limits"]?.attempted, 2)
    }

    func testFailuresFlattenSortedByDomainAndFlipHealth() {
        let health = FlintArmingHealth()
            .replacing("schedules", with: .init(
                enabled: 3,
                attempted: 3,
                armed: 2,
                failures: [.init(activityName: "flint.rule.x", reason: "excessiveActivities")]))
            .replacing("limits", with: .init(
                enabled: 1,
                attempted: 1,
                armed: 0,
                failures: [.init(activityName: "flint.limit.y", reason: "excessiveActivities")]))
        XCTAssertFalse(health.isHealthy)
        XCTAssertEqual(health.failures.map(\.activityName),
                       ["flint.limit.y", "flint.rule.x"],
                       "stable order: domains sorted by name")
    }

    func testNearCapWarnsWithHeadroomBeforeTheObservedCap() {
        // Warn at cap − headroom (20 − 3 = 17), not before: one slot covers the session's
        // untracked auto-clear and two provide margin around the empirical OS threshold.
        let below = FlintArmingHealth().replacing(
            "schedules", with: .init(enabled: 16, attempted: 16, armed: 16, failures: []))
        XCTAssertFalse(below.isNearCap)

        let atThreshold = FlintArmingHealth().replacing(
            "schedules", with: .init(enabled: 17, attempted: 17, armed: 17, failures: []))
        XCTAssertTrue(atThreshold.isNearCap)

        // Counts sum across domains before comparing against the cap.
        let summed = FlintArmingHealth()
            .replacing("schedules", with: .init(enabled: 9, attempted: 9, armed: 9, failures: []))
            .replacing("limits", with: .init(enabled: 5, attempted: 5, armed: 5, failures: []))
            .replacing("openLimits", with: .init(enabled: 3, attempted: 3, armed: 3, failures: []))
        XCTAssertTrue(summed.isNearCap)

        // Locally invalid rules are enabled but consume no OS slots.
        let validationOnly = FlintArmingHealth().replacing(
            "schedules", with: .init(
                enabled: 20,
                attempted: 0,
                armed: 0,
                failures: [.init(activityName: "flint.rule.bad", reason: "invalid")]
            ))
        XCTAssertFalse(validationOnly.isNearCap)
    }

    func testCodableRoundTrip() throws {
        let health = FlintArmingHealth()
            .replacing("openLimits", with: .init(
                enabled: 2,
                attempted: 2,
                armed: 1,
                failures: [.init(activityName: "flint.openLimit.z", reason: "unauthorized")]))
        let data = try JSONEncoder().encode(health)
        XCTAssertEqual(try JSONDecoder().decode(FlintArmingHealth.self, from: data), health)
    }

    func testLegacyDomainReportDecodesIntoTheFullFunnel() throws {
        let legacy = LegacyArmingDomainReport(
            attempted: 3,
            failures: [.init(activityName: "flint.rule.x", reason: "excessiveActivities")],
            updatedAt: Date(timeIntervalSinceReferenceDate: 123)
        )

        let report = try JSONDecoder().decode(
            FlintArmingHealth.DomainReport.self,
            from: JSONEncoder().encode(legacy)
        )
        XCTAssertEqual(report.enabled, 3)
        XCTAssertEqual(report.attempted, 3)
        XCTAssertEqual(report.armed, 2)
        XCTAssertEqual(report.failures, legacy.failures)
        XCTAssertEqual(report.updatedAt, legacy.updatedAt)
    }

    func testRecordMergesThroughGroupStoreAndClearsStaleFailures() throws {
        let suiteName = "flint.tests.armingHealth"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = try XCTUnwrap(FlintGroupStore(suiteName: suiteName))

        FlintArmingHealth.record(
            domain: "schedules", enabled: 4, attempted: 3, armed: 2,
            failures: [.init(activityName: "flint.rule.x", reason: "excessiveActivities")],
            in: store)
        FlintArmingHealth.record(
            domain: "limits", enabled: 2, attempted: 2, armed: 2, failures: [], in: store)

        let loaded = store.loadArmingHealth()
        XCTAssertEqual(loaded.enabledTotal, 6)
        XCTAssertEqual(loaded.attemptedTotal, 5)
        XCTAssertEqual(loaded.armedTotal, 4)
        XCTAssertEqual(loaded.failures.map(\.activityName), ["flint.rule.x"])
        XCTAssertFalse(loaded.isHealthy)

        // A clean re-arm of the same domain clears its stale failures — health recovers.
        FlintArmingHealth.record(
            domain: "schedules", enabled: 3, attempted: 3, armed: 3, failures: [], in: store)
        XCTAssertTrue(store.loadArmingHealth().isHealthy)
        XCTAssertEqual(store.loadArmingHealth().enabledTotal, 5)
        XCTAssertEqual(store.loadArmingHealth().attemptedTotal, 5)
        XCTAssertEqual(store.loadArmingHealth().armedTotal, 5)
    }

    func testRecordWithNilStoreIsANoOp() {
        FlintArmingHealth.record(
            domain: "schedules", enabled: 1, attempted: 1, armed: 1, failures: [], in: nil)
        // Nothing to assert beyond "didn't crash" — recording must never break arming.
    }
}
