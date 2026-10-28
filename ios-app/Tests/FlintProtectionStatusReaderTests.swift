import XCTest
import FlintCore

final class FlintProtectionStatusReaderTests: XCTestCase {
    typealias Snapshot = FlintProtectionStatusSnapshot
    private let now = Date(timeIntervalSince1970: 1_793_188_800)

    private func fixture(version: Int = 1, observedAt: Date? = nil, validUntil: Date? = nil,
                         state: Snapshot.State = .protected) -> Snapshot {
        let end = now.addingTimeInterval(600)
        let cause = Snapshot.Cause(id: "session", kind: .session, displayName: "Study",
                                  tier: .harder, end: end, enforcing: true)
        return Snapshot(version: version, observedAt: observedAt ?? now, validUntil: validUntil ?? end,
            state: state, session: .init(id: "session", kind: .session, name: "Study", tier: .harder,
                startedAt: now.addingTimeInterval(-60), logicalEnd: end, origin: .appUI, armed: true),
            headline: cause, causes: [cause], nextOccurrence: nil,
            pass: .init(eligibility: .available), degradedReasons: [])
    }

    private func encode(_ snapshot: Snapshot) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        return try encoder.encode(snapshot)
    }

    func testSecondsSince1970RoundTripAndAgreedConstants() throws {
        let snapshot = fixture()
        let data = try encode(snapshot)
        XCTAssertEqual(FlintProtectionStatus.decode(data, now: now), snapshot)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["observedAt"] as? Double, now.timeIntervalSince1970)
        XCTAssertEqual(FlintProtectionStatus.storageKey, "flint.status.v1")
        XCTAssertEqual(FlintProtectionStatus.changedNotification, "com.flint.peakfocus.status.changed")
        XCTAssertEqual(FlintProtectionStatus.widgetKind, "FlintProtectionWidget")
    }

    func testMissingCorruptUnsupportedFutureExpiredAndUnavailableReturnNil() throws {
        let name = "FlintProtectionStatusReaderTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        XCTAssertNil(FlintProtectionStatus.load(now: now, from: nil))
        XCTAssertNil(FlintProtectionStatus.load(now: now, from: defaults))
        defaults.set(Data("broken".utf8), forKey: FlintProtectionStatus.storageKey)
        XCTAssertNil(FlintProtectionStatus.load(now: now, from: defaults))
        for snapshot in [fixture(version: 2), fixture(observedAt: now.addingTimeInterval(1)),
                         fixture(validUntil: now.addingTimeInterval(-1)), fixture(state: .unavailable)] {
            defaults.set(try encode(snapshot), forKey: FlintProtectionStatus.storageKey)
            XCTAssertNil(FlintProtectionStatus.load(now: now, from: defaults))
        }
        let snapshot = fixture()
        defaults.set(try encode(snapshot), forKey: FlintProtectionStatus.storageKey)
        XCTAssertEqual(FlintProtectionStatus.load(now: now, from: defaults), snapshot)
    }

    func testEveryUsableStateAndOptionalExpiry() throws {
        for state in [Snapshot.State.protected, .notProtected, .degraded, .suspended] {
            let snapshot = fixture(state: state)
            XCTAssertEqual(FlintProtectionStatus.decode(try encode(snapshot), now: now)?.state, state)
        }
        let data = try encode(fixture())
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        json.removeValue(forKey: "validUntil")
        XCTAssertNotNil(FlintProtectionStatus.decode(try JSONSerialization.data(withJSONObject: json), now: now))
        XCTAssertNotNil(FlintProtectionStatus.decode(data, now: now.addingTimeInterval(600)))
        XCTAssertNil(FlintProtectionStatus.decode(data, now: now.addingTimeInterval(601)))
    }

    func testAgreedWireEnumValues() throws {
        XCTAssertEqual(Snapshot.State.notProtected.rawValue, "not_protected")
        XCTAssertEqual(Snapshot.Pass.Eligibility.coolingDown.rawValue, "cooling_down")
        XCTAssertEqual(Snapshot.Pass.Eligibility.nothingToRecover.rawValue, "nothing_to_recover")
        XCTAssertEqual(Snapshot.Session.Origin.appUI.rawValue, "app_ui")
        XCTAssertEqual(Snapshot.Session.Origin.focusFilter.rawValue, "focus_filter")
        for kind in [Snapshot.Kind.session, .pomodoro, .recurringSchedule, .oneShotSchedule,
                     .sleep, .timeLimit, .openLimit] {
            XCTAssertEqual(try JSONDecoder().decode(Snapshot.Kind.self, from: JSONEncoder().encode(kind)), kind)
        }
    }
}
