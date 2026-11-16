import XCTest
@testable import FlintCore

/// iOS 27 returns false from the deprecated `synchronize()` even after a successful App Group
/// write, which previously turned every rule/web/removal-guard read and save into
/// "unavailable". `flintSynchronize()` must credit only the App Group suite, identified by its
/// sentinel, so test suites that deliberately fail a flush keep the original failure path.
final class FlintUserDefaultsSynchronizeFallbackTests: XCTestCase {
    private final class FailingSynchronizeDefaults: UserDefaults {
        override func synchronize() -> Bool { false }
    }

    private final class RecordingSynchronizeDefaults: UserDefaults {
        var flushes = 0
        override func synchronize() -> Bool {
            flushes += 1
            return true
        }
    }

    private static let sentinelKey = "flint.defaults.appGroup"
    private static let sessionsKey = "flint.sessions"
    private var cleanups: [(String, UserDefaults)] = []

    override func tearDown() {
        for (name, defaults) in cleanups { defaults.removePersistentDomain(forName: name) }
        cleanups = []
        super.tearDown()
    }

    private func name(_ label: String) -> String {
        "FlintSynchronizeFallbackTests.\(label).\(UUID().uuidString)"
    }

    private func failing(_ label: String) throws -> FailingSynchronizeDefaults {
        let suite = name(label)
        let defaults = try XCTUnwrap(FailingSynchronizeDefaults(suiteName: suite))
        cleanups.append((suite, defaults))
        return defaults
    }

    private func recording(_ label: String) throws -> RecordingSynchronizeDefaults {
        let suite = name(label)
        let defaults = try XCTUnwrap(RecordingSynchronizeDefaults(suiteName: suite))
        cleanups.append((suite, defaults))
        return defaults
    }

    /// With the sentinel present, a false flush is treated as a successful App Group write.
    func testAppGroupSentinelMakesAFailedSynchronizeSuccessful() throws {
        let defaults = try failing("sentinel")
        defaults.set(true, forKey: Self.sentinelKey)
        XCTAssertFalse(defaults.synchronize(), "precondition: this fake always fails")
        XCTAssertTrue(defaults.flintSynchronize(),
            "the App Group sentinel turns an iOS 27 false flush into a usable write")
    }

    /// Without the sentinel a failed flush still fails: the deliberate fault-injection path
    /// that existing web/rule tests rely on is preserved.
    func testPlainSuiteStillReportsAFailedSynchronize() throws {
        let defaults = try failing("plain")
        XCTAssertFalse(defaults.flintSynchronize(), "only the App Group suite is exempted")
    }

    /// A successful flush is authoritative, consulted before the sentinel, and never retried.
    func testSuccessfulSynchronizeWinsAndIsNotRetried() throws {
        let defaults = try recording("success")
        XCTAssertFalse(defaults.bool(forKey: Self.sentinelKey))
        XCTAssertTrue(defaults.flintSynchronize())
        XCTAssertEqual(defaults.flushes, 1, "one underlying flush, not a retry loop")
    }

    /// After a false flush the sentinel path still exposes the write just made, which is the
    /// read-back freshness guarantee rule, web and removal-guard saves depend on.
    func testSentinelPathKeepsWriteVisibleToReadBack() throws {
        let defaults = try failing("visible")
        defaults.set(true, forKey: Self.sentinelKey)
        XCTAssertFalse(defaults.synchronize())
        let data = Data("payload".utf8)
        defaults.set(data, forKey: "flint.payload")
        XCTAssertTrue(defaults.flintSynchronize())
        XCTAssertEqual(defaults.data(forKey: "flint.payload"), data)
    }

    /// A migrated production call site: on iOS 27 the removal-guard preference must still save
    /// and read back through the App Group sentinel, and must still fail loudly without it.
    func testRemovalGuardStoreSavesAndReadsBackThroughTheSentinel() throws {
        typealias Preference = FlintRemovalGuardPreference
        let enabled = Preference(enabled: true, disclosedAt: Date(timeIntervalSince1970: 1_800_000_000),
                                 disclosureVersion: Preference.currentDisclosureVersion)

        let sentinelDefaults = try failing("removal-sentinel")
        sentinelDefaults.set(true, forKey: Self.sentinelKey)
        XCTAssertFalse(sentinelDefaults.synchronize(), "precondition: the flush keeps failing")
        let store = Preference.Store(defaults: sentinelDefaults)
        try store.save(enabled)
        XCTAssertEqual(store.load(), .value(enabled, currentDisclosureVersion: Preference.currentDisclosureVersion))
        XCTAssertTrue(store.load().captureForNewCommitment)

        let plainDefaults = try failing("removal-plain")
        let unflushed = Preference.Store(defaults: plainDefaults)
        XCTAssertEqual(unflushed.load(), .unavailable, "without the sentinel an unflushed read is unavailable")
        XCTAssertThrowsError(try unflushed.save(enabled)) {
            XCTAssertEqual($0 as? Preference.Error, .unavailable)
        }
    }

    // MARK: FlintGroupStore adopts the memoized App Group suite

    /// The production store must write the domain `UserDefaults.flintGroup` exposes instead of
    /// opening a second suite object, so the host app and extensions read one domain. Skipped
    /// when this test host has no App Group container.
    func testGroupStoreWritesTheMemoizedAppGroupDomain() throws {
        guard let shared = UserDefaults.flintGroup else {
            throw XCTSkip("test host has no App Group container; sharing cannot be observed")
        }
        XCTAssertTrue(shared.bool(forKey: Self.sentinelKey),
            "the memoized App Group instance carries the sentinel flintSynchronize relies on")
        let store = try XCTUnwrap(FlintGroupStore(), "the default store must exist when flintGroup does")
        let previous = shared.object(forKey: Self.sessionsKey)
        defer {
            if let previous { shared.set(previous, forKey: Self.sessionsKey) }
            else { shared.removeObject(forKey: Self.sessionsKey) }
        }
        let session = FlintSession(name: "Focus", breakLevel: .easy)
        store.saveSessions([session])
        XCTAssertTrue(shared.flintSynchronize())
        let raw = try XCTUnwrap(shared.object(forKey: Self.sessionsKey) as? Data,
            "the group store writes the same domain flintGroup exposes")
        XCTAssertEqual(try JSONDecoder().decode([FlintSession].self, from: raw), [session])
        XCTAssertEqual(store.loadSessions(), [session])
    }

    /// An explicitly named suite must never be redirected to the App Group domain.
    func testExplicitSuiteNameStillUsesItsOwnDomain() throws {
        let suite = name("explicit")
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        cleanups.append((suite, defaults))
        let before = UserDefaults.flintGroup?.object(forKey: Self.sessionsKey) as? Data
        let store = try XCTUnwrap(FlintGroupStore(suiteName: suite))
        store.saveSessions([])
        XCTAssertNotNil(defaults.object(forKey: Self.sessionsKey), "the named suite receives the write")
        XCTAssertEqual(UserDefaults.flintGroup?.object(forKey: Self.sessionsKey) as? Data, before,
            "a named suite never writes the App Group domain")
    }
}
