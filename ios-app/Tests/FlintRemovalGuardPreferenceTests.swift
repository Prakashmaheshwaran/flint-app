import XCTest
@testable import FlintCore

final class FlintRemovalGuardPreferenceTests: XCTestCase {
    private typealias Preference = FlintRemovalGuardPreference
    private let disclosed = Date(timeIntervalSince1970: 1_800_000_000)
    private var enabled: Preference { .init(enabled: true, disclosedAt: disclosed, disclosureVersion: 1) }

    func testPC79AbsentPreferenceIsOffForNewCapturesWithoutInitializingStorage() {
        var writes = 0
        let store = Preference.Store(read: { nil }, write: { _ in writes += 1 })
        XCTAssertEqual(store.load(), .absent)
        XCTAssertFalse(store.load().captureForNewCommitment)
        XCTAssertFalse(Preference().enabled)
        XCTAssertEqual(writes, 0)
    }

    func testProductionCodecRoundTripAndUnixDisclosureDate() throws {
        for preference in [Preference(), enabled, Preference(enabled: false, disclosedAt: disclosed, disclosureVersion: 1)] {
            let bytes = try preference.encoded()
            XCTAssertEqual(try Preference.decode(bytes), preference)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
            XCTAssertEqual(json["version"] as? Int, 1)
            XCTAssertEqual(json["enabled"] as? Bool, preference.enabled)
            if preference.disclosedAt != nil { XCTAssertEqual(json["disclosedAt"] as? Double, 1_800_000_000) }
        }
    }

    func testValidatedSaveAndReadBackThroughProductionCodec() throws {
        var bytes: Data?
        let store = Preference.Store(read: { bytes }, write: { bytes = $0 })
        try store.save(enabled)
        XCTAssertEqual(store.load(), .value(enabled))
        XCTAssertTrue(store.load().captureForNewCommitment)
        XCTAssertEqual(try Preference.decode(XCTUnwrap(bytes)), enabled)
        try store.save(Preference())
        XCTAssertEqual(store.load(), .value(Preference()))
        XCTAssertFalse(store.load().captureForNewCommitment)
    }

    func testInvalidDisclosureRejectedBeforeWrite() {
        var writes = 0
        let store = Preference.Store(read: { nil }, write: { _ in writes += 1 })
        let invalid: [Preference] = [.init(enabled: true),
                                    .init(enabled: true, disclosedAt: disclosed),
                                    .init(enabled: true, disclosedAt: disclosed, disclosureVersion: 2),
                                    .init(disclosureVersion: 1),
                                    .init(enabled: true, disclosedAt: Date(timeIntervalSince1970: .infinity), disclosureVersion: 1)]
        for preference in invalid {
            XCTAssertThrowsError(try store.save(preference))
            XCTAssertFalse(Preference.ReadResult.value(preference).captureForNewCommitment)
        }
        XCTAssertEqual(writes, 0)
    }

    func testCorruptOrFuturePreferenceStaysUnavailableAndPreservesOriginalBytes() throws {
        let original = try enabled.encoded()
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: original) as? [String: Any])
        var fixtures = [Data("bad JSON".utf8)]
        for (key, value) in [("version", 2 as Any), ("disclosureVersion", 0 as Any),
                             ("enabled", "yes" as Any), ("disclosedAt", NSNull() as Any)] {
            var changed = json
            changed[key] = value
            fixtures.append(try JSONSerialization.data(withJSONObject: changed))
        }
        json.removeValue(forKey: "version")
        fixtures.append(try JSONSerialization.data(withJSONObject: json))
        for originalBytes in fixtures {
            var bytes = originalBytes
            let store = Preference.Store(read: { bytes }, write: { bytes = $0 })
            XCTAssertEqual(store.load(), .unavailable)
            XCTAssertFalse(store.load().captureForNewCommitment)
            XCTAssertThrowsError(try store.save(Preference()))
            XCTAssertEqual(bytes, originalBytes)
        }
    }

    func testIOFailureAndAmbiguousWriteNeverReportSuccessOrRetry() throws {
        enum Failure: Error { case io }
        let unreadable = Preference.Store(read: { throw Failure.io }, write: { _ in XCTFail("Must not write") })
        XCTAssertEqual(unreadable.load(), .unavailable)
        XCTAssertFalse(unreadable.load().captureForNewCommitment)
        XCTAssertThrowsError(try unreadable.save(enabled))
        let failure = Preference.Store(read: { nil }, write: { _ in throw Failure.io })
        XCTAssertThrowsError(try failure.save(enabled))
        var writes = 0
        let dropped = Preference.Store(read: { nil }, write: { _ in writes += 1 })
        XCTAssertThrowsError(try dropped.save(enabled)) { XCTAssertEqual($0 as? Preference.Error, .readBackFailed) }
        XCTAssertEqual(writes, 1)
        let old = try Preference().encoded()
        let mismatched = Preference.Store(read: { old }, write: { _ in writes += 1 })
        XCTAssertThrowsError(try mismatched.save(enabled)) { XCTAssertEqual($0 as? Preference.Error, .readBackFailed) }
        XCTAssertEqual(writes, 2)
    }

    func testAppGroupKeyAdapterPreservesUnrelatedDataAndRejectsWrongType() throws {
        let suite = "FlintRemovalGuardPreferenceTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = try XCTUnwrap(Preference.Store(suiteName: suite))
        defaults.set("preserved", forKey: "unrelated")
        try store.save(enabled)
        XCTAssertEqual(try Preference.decode(XCTUnwrap(defaults.data(forKey: Preference.key))), enabled)
        XCTAssertEqual(defaults.string(forKey: "unrelated"), "preserved")
        defaults.set(true, forKey: Preference.key)
        XCTAssertEqual(store.load(), .unavailable)
        XCTAssertFalse(store.load().captureForNewCommitment)
        XCTAssertThrowsError(try store.save(Preference()))
        XCTAssertEqual(defaults.object(forKey: Preference.key) as? Bool, true)
    }
}
