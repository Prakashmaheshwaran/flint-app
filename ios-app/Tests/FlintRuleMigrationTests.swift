import XCTest
import FamilyControls
@testable import FlintCore

final class FlintRuleMigrationTests: XCTestCase {
    /// Fixtures omit only Apple's opaque `selection` representation. Insert the SDK's encoded
    /// empty selection, keeping all legacy scalar/version/tier fields exactly as the fixture.
    private func fixture(_ name: String, change: (inout [String: Any]) -> Void = { _ in }) throws -> Data {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures/Rules/\(name).json")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        json["selection"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(FamilyActivitySelection()))
        change(&json)
        return try JSONSerialization.data(withJSONObject: [json], options: [.sortedKeys])
    }

    func testTierlessTimeLimitUsesDocumentedOldEasyAndFlagsReviewIdempotentlyPC70() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let source = try fixture("legacy-time-tierless")
        h.defaults.set(source, forKey: FlintRuleStoreKey.limits.rawValue)
        let first = try XCTUnwrap(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults, at: h.now))
        XCTAssertEqual(first[0].breakLevel, .easy)
        XCTAssertEqual(first[0].protection?.requiresTierReview, true)
        XCTAssertEqual(first[0].protection?.contractVersion, 12)
        XCTAssertEqual(first[0].protection?.revision, 1)
        XCTAssertNotNil(first[0].protection.flatMap { UUID(uuidString: $0.incarnation) })
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.original), source)
        let migratedBytes = h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue)
        XCTAssertEqual(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults, at: h.now), first)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue), migratedBytes)
    }

    func testMalformedMissingCurrentAndUnknownTierNeverDefaultEasyPC71() throws {
        for tier: Any in [NSNull(), "unknown", 2] {
            let h = try RuleHarness(); defer { h.clean() }
            let source = try fixture("legacy-time-tierless") { $0["breakLevel"] = tier }
            h.defaults.set(source, forKey: FlintRuleStoreKey.limits.rawValue)
            XCTAssertNil(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults, at: h.now))
            XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue), source)
            XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.limits.marker))
        }
        let h = try RuleHarness(); defer { h.clean() }
        var rule = FlintLimitRule(id: "partial", name: "Partial", thresholdMinutes: 3)
        rule.tierNeedsReview = nil
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(rule)) as? [String: Any])
        object.removeValue(forKey: "breakLevel")
        h.defaults.set(try JSONSerialization.data(withJSONObject: [object]), forKey: FlintRuleStoreKey.limits.rawValue)
        XCTAssertNil(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults))
    }

    func testPartialCurrentMetadataCannotMintAnIncarnationDuringReadPC71() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.schedule(tier: .hardcore)
        var fields = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(rule)) as? [String: Any])
        var metadata = try XCTUnwrap(fields["protection"] as? [String: Any])
        metadata.removeValue(forKey: "incarnation"); fields["protection"] = metadata
        let source = try JSONSerialization.data(withJSONObject: [fields])
        h.defaults.set(source, forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertNil(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: h.defaults))
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
    }

    func testUnrepresentableBudgetAndRevisionAreUnavailableWithoutTrappingPC71() throws {
        let h = try RuleHarness(); defer { h.clean() }
        for invalidRevision in [false, true] {
            var rule = FlintLimitRule(id: "invalid", name: "Invalid", thresholdMinutes: invalidRevision ? 30 : Int.max)
            if invalidRevision { rule.protection?.revision = Int.max }
            let source = try JSONEncoder().encode([rule])
            h.defaults.set(source, forKey: FlintRuleStoreKey.limits.rawValue)
            XCTAssertNil(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults))
            XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue), source)
        }
    }

    func testLegacyActiveSleepKeepsOldRightsOnlyThroughCapturedOccurrencePC14PC70() throws {
        let h = try RuleHarness(); defer { h.clean() }
        // Legacy rules used the device zone. Put the fixture at local noon on both UTC CI
        // and the developer's device zone before capturing its grandfathered occurrence.
        h.now = Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: h.now)!
        let source = try fixture("legacy-sleep-hardcore")
        h.defaults.set(source, forKey: FlintRuleStoreKey.schedules.rawValue)
        let first = try XCTUnwrap(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: h.defaults, at: h.now)?.first)
        XCTAssertEqual(first.breakLevel, .hardcore)
        XCTAssertEqual(first.monitorName, "flint.rule.sleep.night", "already registered legacy names remain recognized")
        let grandfatherEnd = try XCTUnwrap(first.protection?.legacyRightsUntil)
        var edit = first; edit.breakLevel = .easy
        guard case .confirmationRequired = FlintRuleArbiter(environment: h.environment).upsert(edit) else { return XCTFail("legacy edit rights retained") }
        h.advance(86400)
        let second = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(second.protection?.legacyRightsUntil, grandfatherEnd, "migration cannot re-grant legacy rights")
        edit = second; edit.breakLevel = .easy
        guard case .deferRequired = FlintRuleArbiter(environment: h.environment).upsert(edit) else { return XCTFail("later occurrence uses ADR-012") }
    }

    func testExistingExplicitOpenTierSurvivesMigrationPC70() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let source = try fixture("legacy-open-hardcore")
        h.defaults.set(source, forKey: FlintRuleStoreKey.openLimits.rawValue)
        let rule = try XCTUnwrap(h.load(FlintOpenLimitRule.self).first)
        XCTAssertEqual(rule.breakLevel, .hardcore)
        XCTAssertFalse(rule.protection?.requiresTierReview ?? true)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.openLimits.original), source)
    }

    func testLegacyRightsCannotReviveAfterOffAndNewEnablePC70() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: h.now)!
        h.defaults.set(try fixture("legacy-sleep-hardcore"), forKey: FlintRuleStoreKey.schedules.rawValue)
        let old = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNotNil(old.protection?.legacyRightsUntil)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .confirmationRequired(off, _) = arbiter.setEnabled(FlintScheduleRule.self,
            id: old.id, enabled: false, revision: old.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: false,
            revision: old.revision, confirmation: off), .applied)
        let disabled = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNil(disabled.protection?.legacyRightsUntil)
        guard case let .confirmationRequired(on, _) = arbiter.setEnabled(FlintScheduleRule.self,
            id: old.id, enabled: true, revision: disabled.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: true,
            revision: disabled.revision, confirmation: on), .applied)
        let enabled = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNil(enabled.protection?.legacyRightsUntil)
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: old.id,
            enabled: false, revision: enabled.revision, confirmation: nil) else { return XCTFail() }
    }

    func testDeleteOnlyRuleKeyAfterInitializationIsUnavailableR22PC37PC62() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.marker))
        h.defaults.removeObject(forKey: FlintRuleStoreKey.schedules.rawValue)
        let controller = FlintSchedulesController(environment: h.environment)
        _ = controller.reload()
        XCTAssertEqual(controller.delete(rule.id), .unavailable)
        XCTAssertEqual(controller.setEnabled(rule.id, false), .unavailable)
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.starts.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
        XCTAssertTrue(h.reports["schedules"]?.failures.first?.reason.contains("unavailable") == true)
    }

    func testAbsentFreshKeyCanInitializeButPartialUnknownMarkerCannotR22PC70PC71() throws {
        let h = try RuleHarness(); defer { h.clean() }
        XCTAssertEqual(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults), [])
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.limits.marker))
        for marker in [Data("{partial".utf8), Data("{\"schema\":99}".utf8),
                       Data("{\"schema\":1,\"future\":true}".utf8)] {
            h.defaults.set(marker, forKey: FlintRuleStoreKey.limits.marker)
            XCTAssertNil(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults))
        }
    }

    func testDeletedCorruptPreMigrationBlobCannotBecomeFreshEmptyR22PC71() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let key = FlintRuleStoreKey.limits
        let source = Data("{unreadable".utf8)
        h.defaults.set(source, forKey: key.rawValue)
        XCTAssertNil(FlintDesiredRules.load(FlintLimitRule.self, key: key, from: h.defaults))
        h.defaults.removeObject(forKey: key.rawValue)
        XCTAssertNil(FlintDesiredRules.load(FlintLimitRule.self, key: key, from: h.defaults))
        XCTAssertEqual(h.defaults.data(forKey: key.rawValue + ".quarantine.adr012"), source)
    }

    func testUnknownRuleSchemaFieldIsUnavailableInsteadOfLegacyDefaultPC71() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let data = try fixture("legacy-time-tierless") { $0["contractVersion"] = 99 }
        h.defaults.set(data, forKey: FlintRuleStoreKey.limits.rawValue)
        XCTAssertNil(FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: h.defaults))
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue), data)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue + ".quarantine.adr012"), data)
    }

    func testCrashAfterBackupOrMigratedBlobFinishesIdempotentlyPC70() throws {
        for afterBlob in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let source = try fixture("legacy-open-hardcore")
            let key = FlintRuleStoreKey.openLimits
            h.defaults.set(source, forKey: key.rawValue)
            h.defaults.set(source, forKey: key.original) // crash after durable original backup
            if afterBlob {
                _ = h.load(FlintOpenLimitRule.self)
                h.defaults.removeObject(forKey: key.marker) // crash after blob before marker
            }
            let first = h.load(FlintOpenLimitRule.self)
            let bytes = h.defaults.data(forKey: key.rawValue)
            h.advance(86400)
            XCTAssertEqual(h.load(FlintOpenLimitRule.self), first)
            XCTAssertEqual(h.defaults.data(forKey: key.rawValue), bytes)
            XCTAssertEqual(h.defaults.data(forKey: key.original), source)
        }
    }

    func testDuplicateIDsMakeEachWholeDomainUnavailableWithoutDroppingRecordsR31PC71() throws {
        let h = try RuleHarness(); defer { h.clean() }
        try duplicates(h.schedule(tier: .hardcore), h: h)
        try duplicates(FlintLimitRule(id: "same", name: "Time", thresholdMinutes: 2), h: h)
        try duplicates(FlintOpenLimitRule(id: "same", name: "Open", opensAllowed: 2), h: h)
        _ = FlintSchedulesController(environment: h.environment).reload()
        FlintLimitsController(environment: h.environment).reload()
        FlintOpenLimitsController(environment: h.environment).reload()
        XCTAssertTrue(h.center.starts.isEmpty); XCTAssertTrue(h.center.stops.isEmpty); XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertEqual(Set(h.reports.keys), ["schedules", "limits", "openLimits"])
    }

    private func duplicates<Rule: FlintGatedRule>(_ rule: Rule, h: RuleHarness) throws {
        let bytes = try JSONEncoder().encode([rule, rule])
        h.defaults.set(bytes, forKey: Rule.domain.rawValue)
        XCTAssertNil(FlintDesiredRules.load(Rule.self, key: Rule.domain, from: h.defaults))
        XCTAssertEqual(h.defaults.data(forKey: Rule.domain.rawValue), bytes)
        XCTAssertFalse(FlintDesiredRules.save([rule, rule], key: Rule.domain, to: h.defaults))
    }
}
