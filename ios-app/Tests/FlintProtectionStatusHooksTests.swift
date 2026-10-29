import XCTest
@testable import FlintCore

/// The commit-point publisher: it writes the projection every time, but reloads widgets and
/// notifies observers only when the content changed.
final class FlintProtectionStatusHooksTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var reloads = 0
    private var notifications = 0
    private var active: FlintActiveSession?

    override func setUp() {
        super.setUp()
        suiteName = "FlintProtectionStatusHooksTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        reloads = 0
        notifications = 0
        active = nil
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private var effects: FlintProtectionStatus.PublicationEffects {
        .init(reloadTimelines: { [unowned self] _ in reloads += 1 },
              postDarwinNotification: { [unowned self] _ in notifications += 1 })
    }

    private var environment: FlintProtectionStatus.ObservationEnvironment {
        .init(authorized: { true }, registeredActivities: { [] }, clock: .system,
              loadActiveSession: { [unowned self] in active }, hasActiveSessionRecord: { [unowned self] in active != nil },
              loadArmingHealth: { .init() })
    }

    @discardableResult
    private func publish(at now: Date, writer: FlintProtectionStatus.Writer = .host) -> FlintProtectionStatusSnapshot {
        FlintProtectionStatus.publishAfterCommit(writer: writer, now: now, defaults: defaults,
                                                 ledger: { nil }, environment: environment, effects: effects)
    }

    func testUnchangedContentRefreshesTheBlobWithoutReloadingWidgets() throws {
        let now = Date(timeIntervalSince1970: 1_793_206_800)
        publish(at: now)
        XCTAssertEqual(reloads, 1)
        XCTAssertEqual(notifications, 1)
        publish(at: now.addingTimeInterval(30), writer: .monitor)
        XCTAssertEqual(reloads, 1, "same content: no widget reload")
        XCTAssertEqual(notifications, 1)
        let stored = try XCTUnwrap(FlintProtectionStatus.storedSnapshot(in: defaults))
        XCTAssertEqual(stored.observedAt, now.addingTimeInterval(30), "the blob itself is refreshed")
        XCTAssertEqual(stored.writer, "monitor")
        publish(at: now.addingTimeInterval(2 * 3600))
        XCTAssertEqual(reloads, 2, "a validity extension of an hour or more nudges readers")
    }

    func testChangedContentReloadsWidgetsAndNotifies() {
        let now = Date(timeIntervalSince1970: 1_793_206_800)
        publish(at: now)
        active = FlintActiveSession(name: "Focus", startedAt: now, endsAt: nil, breakLevel: .easy,
                                    monitorName: "flint.session.0000000000000000")
        publish(at: now.addingTimeInterval(5))
        XCTAssertEqual(reloads, 2)
        XCTAssertEqual(notifications, 2)
    }

    func testNoLedgerMeansNoClaimOfProtection() {
        let snapshot = publish(at: Date(timeIntervalSince1970: 1_793_206_800))
        XCTAssertEqual(snapshot.state, .unavailable, "an unknown pass (possible recovery) proves nothing")
        XCTAssertNil(FlintProtectionStatus.load(now: snapshot.observedAt, from: defaults))
    }

    func testTheHostIsTheWriterOutsideAnExtension() {
        XCTAssertEqual(FlintProtectionStatus.Writer.current, .host)
        typealias W = FlintProtectionStatus.Writer
        XCTAssertEqual(W.writer(forBundleID: "com.flint.peakfocus.shield-action", isExtension: true), .shieldAction)
        XCTAssertEqual(W.writer(forBundleID: "com.flint.peakfocus.monitor", isExtension: true), .monitor)
        XCTAssertEqual(W.writer(forBundleID: "com.flint.peakfocus.widgets", isExtension: true), .host,
                       "read-only extensions aren't labelled as an enforcement writer")
    }

    func testThresholdsAreKnownOnlyForToday() {
        let now = Date(timeIntervalSince1970: 1_793_206_800)
        let a = FlintLimitRule(id: "limit-a", name: "A", thresholdMinutes: 30)
        let b = FlintLimitRule(id: "limit-b", name: "B", thresholdMinutes: 30)
        XCTAssertTrue(FlintDesiredRules.save([a, b], key: .limits, to: defaults))
        FlintLimitThresholds.markReached(a, at: now, in: defaults)
        FlintLimitThresholds.markReached(b, at: now.addingTimeInterval(-86_400 * 2), in: defaults)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: now, calendar: calendar, in: defaults), ["limit-a": true])
        FlintLimitThresholds.clear("limit-a", in: defaults)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: now, calendar: calendar, in: defaults), [:])
    }

    func testAMarkDoesNotOutliveADisableOrAThresholdChange() {
        let now = Date(timeIntervalSince1970: 1_793_206_800)
        var limit = FlintLimitRule(id: "limit-a", name: "A", thresholdMinutes: 30)
        XCTAssertTrue(FlintDesiredRules.save([limit], key: .limits, to: defaults))
        FlintLimitThresholds.markReached(limit, at: now, in: defaults)
        limit.enabled = false
        FlintLimitThresholds.prune(keeping: [limit], in: defaults)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: now, in: defaults), [:], "disabled: its shield was cleared")
        limit.enabled = true
        FlintLimitThresholds.markReached(limit, at: now, in: defaults)
        limit.thresholdMinutes = 60
        FlintLimitThresholds.prune(keeping: [limit], in: defaults)
        XCTAssertEqual(FlintLimitThresholds.reachedToday(now: now, in: defaults), [:], "a new threshold hasn't fired")
    }

    func testAnOpenEndedSessionIsArmedOnlyWithItsTargets() {
        let now = Date(timeIntervalSince1970: 1_793_206_800)
        active = FlintActiveSession(name: "Focus", startedAt: now, endsAt: nil, breakLevel: .easy,
                                    monitorName: "flint.session.0000000000000000")
        let withoutTargets = FlintProtectionStatus.publishAfterCommit(
            writer: .host, now: now, defaults: defaults, ledger: { nil }, environment: environment,
            sessionTargets: { _ in false }, effects: effects)
        XCTAssertNil(withoutTargets.session, "no frozen targets: not shown as an armed session")
    }
}
