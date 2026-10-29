import XCTest
@testable import FlintCore

/// The active-session record's presence check must see unreadable state in the store's own
/// suite (ADR-012 §8), never fall back to "no session".
final class FlintGroupStoreSessionRecordTests: XCTestCase {
    private var suiteName: String!
    private var store: FlintGroupStore!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "FlintGroupStoreSessionRecordTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        store = FlintGroupStore(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testPresenceFollowsTheStoresOwnSuite() {
        XCTAssertFalse(store.hasActiveSessionRecord())
        let session = FlintActiveSession(name: "Focus", startedAt: Date(), endsAt: Date().addingTimeInterval(600),
                                         breakLevel: .easy, monitorName: "flint.session.0000000000000000")
        store.saveActiveSession(session)
        XCTAssertTrue(store.hasActiveSessionRecord())
        XCTAssertEqual(store.loadActiveSession(), session)
        store.clearActiveSession()
        XCTAssertFalse(store.hasActiveSessionRecord())
    }

    func testUndecodableOrWrongTypeRecordsAreStillPresent() {
        defaults.set(Data("not json".utf8), forKey: "flint.activeSession")
        XCTAssertNil(store.loadActiveSession())
        XCTAssertTrue(store.hasActiveSessionRecord(), "corrupt data is unreadable, not absent")

        defaults.set("a string", forKey: "flint.activeSession")
        XCTAssertNil(store.loadActiveSession())
        XCTAssertTrue(store.hasActiveSessionRecord(), "a wrong-type value is unreadable, not absent")
    }
}
