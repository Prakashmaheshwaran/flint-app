import XCTest
@testable import FlintCore

private final class FailingWebDefaults: UserDefaults {
    var failFlush = false
    override func synchronize() -> Bool { failFlush ? false : super.synchronize() }
}

final class FlintWebRestrictionsTests: XCTestCase {

    // MARK: Codable

    func testRoundTrips() throws {
        let config = FlintWebRestrictions(
            enabled: true,
            filter: .allowedOnly,
            blockedDomains: ["reddit.com"],
            allowedDomains: ["wikipedia.org", "apple.com"],
            denyExplicitMedia: true
        )
        let data = try JSONEncoder().encode(config)
        XCTAssertEqual(config, try JSONDecoder().decode(FlintWebRestrictions.self, from: data))
    }

    func testDefaultsAreOptIn() {
        let d = FlintWebRestrictions.default
        XCTAssertFalse(d.enabled, "Web restrictions are opt-in")
        XCTAssertEqual(d.filter, .limitAdult, "Adult filter is the mode you get when you opt in")
        XCTAssertFalse(d.disablesPrivateBrowsing, "Disabled config leaves Private Browsing alone")
    }

    func testFilterModesAreCodableAndRoundTrip() throws {
        for mode in FlintWebFilter.allCases {
            let data = try JSONEncoder().encode(mode)
            XCTAssertEqual(mode, try JSONDecoder().decode(FlintWebFilter.self, from: data))
            XCTAssertFalse(mode.title.isEmpty)
            XCTAssertFalse(mode.detail.isEmpty)
        }
    }

    // MARK: Private-Browsing lockdown (honest derived facts)

    func testEnablingDisablesPrivateBrowsingAndLocksHistory() {
        var config = FlintWebRestrictions()
        XCTAssertFalse(config.disablesPrivateBrowsing)
        XCTAssertFalse(config.locksHistoryClearing)

        config.enabled = true
        // True for *either* mode — any active web filter is what disables Private Browsing on iOS.
        XCTAssertTrue(config.disablesPrivateBrowsing)
        XCTAssertTrue(config.locksHistoryClearing)

        config.filter = .allowedOnly
        XCTAssertTrue(config.disablesPrivateBrowsing)
        XCTAssertTrue(config.locksHistoryClearing)
    }

    // MARK: Domain normalization

    func testNormalizesSchemeWwwPathQueryAndCase() {
        XCTAssertEqual(FlintWebRestrictions.normalizedDomain("https://www.Reddit.com/r/all?x=1"), "reddit.com")
        XCTAssertEqual(FlintWebRestrictions.normalizedDomain("  HTTP://Example.COM  "), "example.com")
        XCTAssertEqual(FlintWebRestrictions.normalizedDomain("news.ycombinator.com"), "news.ycombinator.com")
        XCTAssertEqual(FlintWebRestrictions.normalizedDomain("user:pass@site.org:8080/path#frag"), "site.org")
        XCTAssertEqual(FlintWebRestrictions.normalizedDomain("sub.www.co.uk"), "sub.www.co.uk",
                       "only a leading www. is stripped")
    }

    func testRejectsNonHostnames() {
        for bad in ["", "   ", "localhost", "noTLD", "192.168.0.1", "site..com", ".com",
                    "site.", "-bad.com", "bad-.com", "site.c", "exa mple.com", "site.123"] {
            XCTAssertNil(FlintWebRestrictions.normalizedDomain(bad), "\(bad) should be rejected")
        }
    }

    func testNormalizedListDedupesAndDropsInvalidPreservingOrder() {
        let cleaned = FlintWebRestrictions.normalizedDomains([
            "https://www.reddit.com", "Reddit.com", "twitter.com", "not a domain", "twitter.com/home",
        ])
        XCTAssertEqual(cleaned, ["reddit.com", "twitter.com"])
    }

    func testAddHelpersNormalizeAndRejectDuplicates() {
        var config = FlintWebRestrictions(enabled: true, filter: .limitAdult)

        XCTAssertEqual(config.addBlockedDomain("https://www.YouTube.com/feed"), "youtube.com")
        XCTAssertNil(config.addBlockedDomain("youtube.com"), "duplicate after normalization is rejected")
        XCTAssertNil(config.addBlockedDomain("garbage"), "invalid host is rejected")
        XCTAssertEqual(config.normalizedBlockedDomains, ["youtube.com"])

        XCTAssertEqual(config.addAllowedDomain("Khanacademy.org"), "khanacademy.org")
        XCTAssertEqual(config.normalizedAllowedDomains, ["khanacademy.org"])
    }

    // MARK: App Group persistence (isolated suite, not the real group)

    func testSaveLoadThroughDefaults() throws {
        let suite = "test.flint.webrestrictions.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        XCTAssertEqual(FlintWebRestrictions.load(from: defaults), .default, "empty store → default")

        let config = FlintWebRestrictions(enabled: true, blockedDomains: ["x.com"], denyExplicitMedia: true)
        config.save(to: defaults)
        XCTAssertEqual(FlintWebRestrictions.load(from: defaults), config)
    }

    func testFreshDefaultStaysReadableAfterLedgerInitialization() throws {
        let suite = "test.flint.web.fresh.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default)
        XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        defaults.set(Date(), forKey: FlintProtectionLedger.initializedMarkerKey)
        let reopened = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: reopened, existingWebStores: { false }), .default)
    }

    func testSuccessfulSaveMarksDomainAndMissingKeyStaysUnavailableAcrossReloads() throws {
        let suite = "test.flint.web.presence.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertTrue(FlintWebRestrictions(enabled: true).save(to: defaults))
        XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        defaults.removeObject(forKey: "flint.webRestrictions")
        let reopened = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: reopened, existingWebStores: { false }))
        XCTAssertNil(reopened.object(forKey: "flint.webRestrictions"), "unavailable is never rewritten as opt-out")
    }

    func testLegacyPresentKeyBackfillsMarkerWithoutChangingItsBytes() throws {
        for bytes in [try JSONEncoder().encode(FlintWebRestrictions(enabled: true)), Data("{broken".utf8)] {
            let suite = "test.flint.web.backfill.\(UUID().uuidString)"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            defaults.set(bytes, forKey: "flint.webRestrictions")
            _ = FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false })
            XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
            XCTAssertEqual(defaults.data(forKey: "flint.webRestrictions"), bytes)
            defaults.removeObject(forKey: "flint.webRestrictions")
            XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }))
        }
    }

    func testEstablishedEvidenceWithoutWebMarkerMakesMissingDomainUnavailable() throws {
        let evidence = ["flint.activeSession", FlintProtectionLedger.initializedMarkerKey]
            + FlintRuleStoreKey.allCases.map(\.marker)
        for key in evidence {
            let suite = "test.flint.web.established.\(UUID().uuidString)"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            // Presence is evidence even if another domain's value itself is unreadable.
            defaults.set("established", forKey: key)
            XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), key)
            XCTAssertNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        }
    }

    func testExistingNativeWebStoreMakesMissingDomainUnavailable() throws {
        let suite = "test.flint.web.native-evidence.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { true }))
    }

    func testInvalidSaveCannotInitializeOrReplaceTheDomain() throws {
        let suite = "test.flint.web.invalid-save.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let invalid = FlintWebRestrictions(blockedDomains: (0...FlintShieldLimits.tokenCap).map { "s\($0).example" })
        XCTAssertFalse(invalid.save(to: defaults))
        XCTAssertNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        XCTAssertNil(defaults.object(forKey: "flint.webRestrictions"))
    }

    func testFailedFlushPreservesSavedPolicyAndDoesNotInitializeFreshDomain() throws {
        let suite = "test.flint.web.failed-save.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(FailingWebDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.failFlush = true
        XCTAssertFalse(FlintWebRestrictions.default.save(to: defaults))
        XCTAssertNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        XCTAssertNil(defaults.object(forKey: "flint.webRestrictions"))
        defaults.failFlush = false
        let previous = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
        XCTAssertTrue(previous.save(to: defaults))
        let bytes = defaults.data(forKey: "flint.webRestrictions")
        defaults.failFlush = true
        XCTAssertFalse(FlintWebRestrictions.default.save(to: defaults))
        XCTAssertEqual(defaults.data(forKey: "flint.webRestrictions"), bytes)
        XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }))
        defaults.failFlush = false
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults), previous)
    }

    func testFailedFreshInitializationIsUnavailableAndCanRetryWhileStillFresh() throws {
        let suite = "test.flint.web.failed-init.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(FailingWebDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.failFlush = true
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }))
        XCTAssertNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        XCTAssertNil(defaults.object(forKey: "flint.webRestrictions"))
        defaults.failFlush = false
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default)
    }
}
