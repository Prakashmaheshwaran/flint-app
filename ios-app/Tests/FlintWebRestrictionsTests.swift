import XCTest
@testable import FlintCore

private final class FailingWebDefaults: UserDefaults {
    var failFlush = false
    var successfulFlushesBeforeFailure: Int?
    override func synchronize() -> Bool {
        if failFlush { return false }
        if let remaining = successfulFlushesBeforeFailure {
            guard remaining > 0 else { successfulFlushesBeforeFailure = nil; return false }
            successfulFlushesBeforeFailure = remaining - 1
        }
        return super.synchronize()
    }
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

        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default, "empty store → default")

        let config = FlintWebRestrictions(enabled: true, blockedDomains: ["x.com"], denyExplicitMedia: true)
        config.save(to: defaults)
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), config)
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

    func testUnconfiguredLegacyInstallMigratesOptOutDespiteOtherEstablishedDomains() throws {
        let evidence = ["flint.activeSession", FlintProtectionLedger.initializedMarkerKey]
        for key in evidence {
            let suite = "test.flint.web.established.\(UUID().uuidString)"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            // Presence is evidence even if another domain's value itself is unreadable.
            defaults.set("established", forKey: key)
            XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default, key)
            XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
            defaults.removeObject(forKey: "flint.webRestrictions")
            XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), key)
        }
    }

    func testExistingNativeWebStoreMakesMissingDomainUnavailable() throws {
        let suite = "test.flint.web.native-evidence.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { true }))
        XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        XCTAssertNil(defaults.object(forKey: "flint.webRestrictions"))
        var probes = 0
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: {
            probes += 1; return false
        }))
        XCTAssertEqual(probes, 0, "Positive native evidence latches unavailable intent")
    }

    func testValidatedEmptyRuleDomainAllowsLegacyOptOutMigration() throws {
        let suite = "test.flint.web.empty-rules.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertTrue(FlintDesiredRules.save([FlintScheduleRule](), key: .schedules, to: defaults))
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default)
    }

    func testUnreadableRuleDomainCannotAuthorizeMissingWebMigration() throws {
        for kind in ["malformed", "future", "quarantined", "lost"] {
            let suite = "test.flint.web.unknown-rules.\(UUID().uuidString)"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            switch kind {
            case "malformed": defaults.set(Data("{".utf8), forKey: FlintRuleStoreKey.schedules.rawValue)
            case "future": defaults.set(Data("[{\"futureSchema\":999}]".utf8), forKey: FlintRuleStoreKey.schedules.rawValue)
            case "quarantined":
                defaults.set("unreadable recovery reference", forKey: FlintRuleStoreKey.schedules.rawValue + ".quarantine.adr012")
                defaults.set(Data("[]".utf8), forKey: FlintRuleStoreKey.schedules.rawValue)
            default:
                XCTAssertTrue(FlintDesiredRules.save([FlintScheduleRule](), key: .schedules, to: defaults))
                defaults.removeObject(forKey: FlintRuleStoreKey.schedules.rawValue)
            }
            XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), kind)
            XCTAssertNil(defaults.object(forKey: "flint.webRestrictions"), kind)
            XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey), kind)
        }
    }

    func testWebStoreNamesIncludeSavedLegacyAndProtectedNativeScheduleOwners() {
        let rule = FlintScheduleRule(id: "saved", name: "Saved", schedule: .init(startHour: 9, endHour: 17))
        let protected = FlintRuleActivity.name(prefix: FlintRuleStoreKey.schedules.prefix, id: "sleep.night",
            protection: .init(timeZoneID: "UTC", incarnation: "12345678-1234-1234-1234-123456789abc"))
        let names = FlintWebRestrictions.webStoreNames(rules: [rule], activityNames: [
            FlintRuleStoreKey.schedules.prefix + "legacy", protected,
            FlintRuleStoreKey.limits.prefix + "time", "flint.session.old"
        ])
        XCTAssertEqual(names, ["flint", "flint.web", "flint.saved", "flint.legacy", "flint.sleep.night"])
    }

    func testBusyReadDoesNotLoadOrInitializeAndCanRetry() {
        var reads = 0
        let load = { reads += 1; return FlintWebRestrictions.default as FlintWebRestrictions? }
        let contended: (TimeInterval?, () -> Void) -> Bool = { timeout, body in
            if timeout == 0 { return false } // UI skips; the ordinary enforcement wait acquires.
            body(); return true
        }
        XCTAssertEqual(FlintWebRestrictions.read(uiRead: true, load: load, transition: contended), .busy)
        XCTAssertEqual(reads, 0)
        XCTAssertEqual(FlintWebRestrictions.read(uiRead: false, load: load, transition: contended),
                       .available(.default))
        XCTAssertEqual(reads, 1)
        XCTAssertEqual(FlintWebRestrictions.read(uiRead: false, load: { nil }, transition: contended),
                       .unavailable)
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
        XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey),
                        "Even a failed rollback flush must not restore fresh-install semantics")
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
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), previous)
    }

    func testFailedFreshInitializationIsUnavailableAndCanRetryWhileStillFresh() throws {
        // First flush is the validated read; fail either the config or marker commit next.
        for successfulFlushes in [1, 2] {
            let suite = "test.flint.web.failed-init.\(UUID().uuidString)"
            let defaults = try XCTUnwrap(FailingWebDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            defaults.successfulFlushesBeforeFailure = successfulFlushes
            XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }))
            XCTAssertNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
            XCTAssertNil(defaults.object(forKey: "flint.webRestrictions"))
            XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default)
        }
    }

    func testFailedRollbackFlushLatchesMissingIntentUntilExplicitRecovery() throws {
        let suite = "test.flint.web.failed-rollback.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(FailingWebDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.failFlush = true // The policy write, rollback and marker flush all fail.
        XCTAssertFalse(FlintWebRestrictions(enabled: true).save(to: defaults))
        XCTAssertNil(defaults.object(forKey: "flint.webRestrictions"))
        XCTAssertNotNil(defaults.object(forKey: FlintWebRestrictions.initializedMarkerKey))
        defaults.failFlush = false
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }))
        let recovered = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
        XCTAssertTrue(recovered.save(to: defaults))
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), recovered)
    }
}
