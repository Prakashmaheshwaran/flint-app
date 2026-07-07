import XCTest
import FlintCore

/// Pure arming-health accounting: domain merges, the near-cap warning boundary, and the
/// App-Group round-trip. (The DeviceActivity registrations whose outcomes this ledger records
/// are device-gated, like every shield.)
final class FlintArmingHealthTests: XCTestCase {

    func testEmptyLedgerIsHealthyAndQuiet() {
        let health = FlintArmingHealth()
        XCTAssertTrue(health.isHealthy)
        XCTAssertEqual(health.attemptedTotal, 0)
        XCTAssertEqual(health.failures, [])
        XCTAssertFalse(health.isNearCap)
    }

    func testReplacingOneDomainKeepsTheOthers() {
        let health = FlintArmingHealth()
            .replacing("schedules", with: .init(attempted: 4, failures: []))
            .replacing("limits", with: .init(attempted: 2, failures: []))
        XCTAssertEqual(health.attemptedTotal, 6)

        let updated = health.replacing("schedules", with: .init(attempted: 1, failures: []))
        XCTAssertEqual(updated.attemptedTotal, 3, "schedules replaced, limits kept")
        XCTAssertEqual(updated.domains["limits"]?.attempted, 2)
    }

    func testFailuresFlattenSortedByDomainAndFlipHealth() {
        let health = FlintArmingHealth()
            .replacing("schedules", with: .init(
                attempted: 3,
                failures: [.init(activityName: "flint.rule.x", reason: "excessiveActivities")]))
            .replacing("limits", with: .init(
                attempted: 1,
                failures: [.init(activityName: "flint.limit.y", reason: "excessiveActivities")]))
        XCTAssertFalse(health.isHealthy)
        XCTAssertEqual(health.failures.map(\.activityName),
                       ["flint.limit.y", "flint.rule.x"],
                       "stable order: domains sorted by name")
    }

    func testNearCapWarnsWithHeadroomBeforeTheObservedCap() {
        // Warn at cap − headroom (20 − 3 = 17), not before — the session's auto-clear and
        // Sleep windows hold slots outside the recorded domains.
        let below = FlintArmingHealth().replacing("schedules", with: .init(attempted: 16, failures: []))
        XCTAssertFalse(below.isNearCap)

        let atThreshold = FlintArmingHealth().replacing("schedules", with: .init(attempted: 17, failures: []))
        XCTAssertTrue(atThreshold.isNearCap)

        // Counts sum across domains before comparing against the cap.
        let summed = FlintArmingHealth()
            .replacing("schedules", with: .init(attempted: 9, failures: []))
            .replacing("limits", with: .init(attempted: 5, failures: []))
            .replacing("openLimits", with: .init(attempted: 3, failures: []))
        XCTAssertTrue(summed.isNearCap)
    }

    func testCodableRoundTrip() throws {
        let health = FlintArmingHealth()
            .replacing("openLimits", with: .init(
                attempted: 2,
                failures: [.init(activityName: "flint.openLimit.z", reason: "unauthorized")]))
        let data = try JSONEncoder().encode(health)
        XCTAssertEqual(try JSONDecoder().decode(FlintArmingHealth.self, from: data), health)
    }

    func testRecordMergesThroughGroupStoreAndClearsStaleFailures() throws {
        let suiteName = "flint.tests.armingHealth"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = try XCTUnwrap(FlintGroupStore(suiteName: suiteName))

        FlintArmingHealth.record(
            domain: "schedules", attempted: 3,
            failures: [.init(activityName: "flint.rule.x", reason: "excessiveActivities")],
            in: store)
        FlintArmingHealth.record(domain: "limits", attempted: 2, failures: [], in: store)

        let loaded = store.loadArmingHealth()
        XCTAssertEqual(loaded.attemptedTotal, 5)
        XCTAssertEqual(loaded.failures.map(\.activityName), ["flint.rule.x"])
        XCTAssertFalse(loaded.isHealthy)

        // A clean re-arm of the same domain clears its stale failures — health recovers.
        FlintArmingHealth.record(domain: "schedules", attempted: 3, failures: [], in: store)
        XCTAssertTrue(store.loadArmingHealth().isHealthy)
        XCTAssertEqual(store.loadArmingHealth().attemptedTotal, 5)
    }

    func testRecordWithNilStoreIsANoOp() {
        FlintArmingHealth.record(domain: "schedules", attempted: 1, failures: [], in: nil)
        // Nothing to assert beyond "didn't crash" — recording must never break arming.
    }
}
