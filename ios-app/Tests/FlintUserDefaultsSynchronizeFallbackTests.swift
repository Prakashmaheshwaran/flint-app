import XCTest
@testable import FlintCore

/// iOS 27 returns false from the deprecated `synchronize()` even after a successful App Group
/// write, which previously turned every rule/web/removal-guard read and save into
/// "unavailable". The exemption must stay narrow — only the App Group instance, only on the
/// release that misreports, and only when cfprefsd confirms the App Group domain — so the
/// security-sensitive stores' fail-closed read-back checks still catch a real failure.
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

    // MARK: The exemption rule

    /// A false flush is resolved only when cfprefsd confirms the App Group domain.
    func testAppGroupInstanceIsExemptOnlyWhenTheDomainFlushIsConfirmed() {
        let version = UserDefaults.flintFalseFlushMajorVersion
        XCTAssertTrue(UserDefaults.flintSynchronizeDecision(
            flush: false, osMajorVersion: version, isAppGroupInstance: true, appGroupFlush: { true }))
        XCTAssertFalse(UserDefaults.flintSynchronizeDecision(
            flush: false, osMajorVersion: version, isAppGroupInstance: true, appGroupFlush: { false }),
            "an unconfirmed domain flush is a real failure and must not be masked")
    }

    /// A missing App Group container (missing entitlement) is a real failure: nothing to exempt.
    func testMissingContainerIsNeverExempted() {
        XCTAssertFalse(UserDefaults.flintSynchronizeDecision(
            flush: false, osMajorVersion: UserDefaults.flintFalseFlushMajorVersion,
            isAppGroupInstance: true, containerAvailable: false, appGroupFlush: { true }))
    }

    /// Older releases keep `synchronize()`'s answer, so pre-iOS-27 behavior is unchanged.
    func testOlderReleaseKeepsTheFailure() {
        XCTAssertFalse(UserDefaults.flintSynchronizeDecision(
            flush: false, osMajorVersion: UserDefaults.flintFalseFlushMajorVersion - 1,
            isAppGroupInstance: true, appGroupFlush: { true }))
    }

    /// Success is authoritative and consulted first, so healthy hosts never need the exemption.
    func testReportedSuccessIsAuthoritative() {
        XCTAssertTrue(UserDefaults.flintSynchronizeDecision(
            flush: true, osMajorVersion: UserDefaults.flintFalseFlushMajorVersion,
            isAppGroupInstance: false, appGroupFlush: { false }))
        XCTAssertTrue(UserDefaults.flintSynchronizeDecision(
            flush: true, osMajorVersion: 16, isAppGroupInstance: false, appGroupFlush: { false }))
    }

    // MARK: Ordinary suites keep their failure

    /// A failing suite without the exemption still reports failure, so the fault-injection
    /// suites keep proving that a real failure surfaces.
    func testFailingSuiteStillReportsAFailedSynchronize() throws {
        let defaults = try failing("plain")
        XCTAssertFalse(defaults.flintSynchronize(), "only the App Group instance is exempted")
    }

    /// A suite cannot buy the exemption by writing a sentinel-looking key into its own domain:
    /// identity decides, not a flag.
    func testSuiteCannotClaimTheExemptionByWritingItself() throws {
        let disguised = try failing("disguised")
        disguised.set(true, forKey: "flint.defaults.appGroup")
        XCTAssertFalse(disguised.flintSynchronize(),
            "a sentinel-looking key in an arbitrary domain never grants the exemption")
    }

    /// A successful flush is attempted exactly once, with no retry loop.
    func testSuccessfulSynchronizeIsNotRetried() throws {
        let defaults = try recording("success")
        XCTAssertTrue(defaults.flintSynchronize())
        XCTAssertEqual(defaults.flushes, 1)
    }

    /// The production confirmation really asks cfprefsd about the App Group domain.
    func testAppGroupDomainIsConfirmedByCfprefsd() throws {
        guard UserDefaults.flintGroup != nil else {
            throw XCTSkip("test host has no App Group container")
        }
        XCTAssertTrue(CFPreferencesAppSynchronize(FlintGroupStore.appGroupID as CFString),
            "an available App Group container must confirm its own domain")
    }

    // MARK: FlintGroupStore adopts the memoized App Group suite

    /// The production store must write the domain `UserDefaults.flintGroup` exposes instead of
    /// opening a second suite object, so the host app and extensions read one domain. Skipped
    /// when this test host has no App Group container.
    func testGroupStoreWritesTheMemoizedAppGroupDomain() throws {
        guard let shared = UserDefaults.flintGroup else {
            throw XCTSkip("test host has no App Group container; sharing cannot be observed")
        }
        let store = try XCTUnwrap(FlintGroupStore(), "the default store must exist when flintGroup does")
        let previous = shared.object(forKey: Self.sessionsKey)
        defer {
            if let previous { shared.set(previous, forKey: Self.sessionsKey) }
            else { shared.removeObject(forKey: Self.sessionsKey) }
        }
        let session = FlintSession(name: "Focus", breakLevel: .easy)
        store.saveSessions([session])
        XCTAssertEqual(store.loadSessions(), [session])
        let raw = try XCTUnwrap(shared.object(forKey: Self.sessionsKey) as? Data,
            "the group store writes the same domain flintGroup exposes")
        XCTAssertEqual(try JSONDecoder().decode([FlintSession].self, from: raw), [session])
    }

    /// The initialization receipt's never-used proof write goes through the same seam: the exact
    /// sequence `prepareLegacyDomains` performs must read back on the App Group domain, so an
    /// upgrading install no longer fails initialization on iOS 27.
    func testNeverUsedProofReadsBackOnTheAppGroupDomain() throws {
        guard let shared = UserDefaults.flintGroup else {
            throw XCTSkip("test host has no App Group container")
        }
        let key = FlintRuleStoreKey.openLimits.neverUsed
        let previous = shared.object(forKey: key)
        defer {
            if let previous { shared.set(previous, forKey: key) }
            else { shared.removeObject(forKey: key) }
        }
        shared.set(FlintRuleInitialization.neverUsedProof, forKey: key)
        XCTAssertTrue(shared.flintSynchronize(),
            "the receipt write must flush successfully, or initialization refuses to start")
        XCTAssertEqual(shared.data(forKey: key), FlintRuleInitialization.neverUsedProof)
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

    /// A migrated production call site: the removal-guard preference round-trips through the
    /// real App Group domain, and a store whose flush genuinely fails still reports unavailable
    /// so the anti-removal preference can never claim success when nothing landed.
    func testRemovalGuardStoreRoundTripsAndStillFailsLoudly() throws {
        typealias Preference = FlintRemovalGuardPreference
        let enabled = Preference(enabled: true, disclosedAt: Date(timeIntervalSince1970: 1_800_000_000),
                                 disclosureVersion: Preference.currentDisclosureVersion)
        guard let shared = UserDefaults.flintGroup else {
            throw XCTSkip("test host has no App Group container")
        }
        let previous = shared.object(forKey: Preference.key)
        defer {
            if let previous { shared.set(previous, forKey: Preference.key) }
            else { shared.removeObject(forKey: Preference.key) }
        }
        let store = Preference.Store(defaults: shared)
        try store.save(enabled)
        XCTAssertEqual(store.load(), .value(enabled, currentDisclosureVersion: Preference.currentDisclosureVersion))
        XCTAssertTrue(store.load().captureForNewCommitment)

        let unflushed = Preference.Store(defaults: try failing("removal-plain"))
        XCTAssertEqual(unflushed.load(), .unavailable, "without the exemption a read is unavailable")
        XCTAssertThrowsError(try unflushed.save(enabled)) {
            XCTAssertEqual($0 as? Preference.Error, .unavailable)
        }
    }
}
