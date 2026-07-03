import XCTest
@testable import FlintCore

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
}
