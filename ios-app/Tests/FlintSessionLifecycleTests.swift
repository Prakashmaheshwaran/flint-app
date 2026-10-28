import XCTest
import FlintCore

final class FlintSessionLifecycleTests: XCTestCase {

    private final class RecordingSink: FlintSessionEventSink {
        var events: [FlintSessionLifecycleEvent] = []
        func handle(_ event: FlintSessionLifecycleEvent) { events.append(event) }
    }

    private var sink: RecordingSink!
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() {
        super.setUp()
        sink = RecordingSink()
        FlintSessionLifecycle.register(sink)
    }

    override func tearDown() {
        FlintSessionLifecycle.unregister(sink)
        sink = nil
        super.tearDown()
    }

    private func session(_ id: String, start: TimeInterval, end: TimeInterval?) -> FlintActiveSession {
        FlintActiveSession(
            id: id, name: "Focus",
            startedAt: t0.addingTimeInterval(start),
            endsAt: end.map { t0.addingTimeInterval($0) },
            breakLevel: .harder, monitorName: "flint.session"
        )
    }

    func testStartWithoutPreviousEmitsOnlyStarted() {
        let s = session("a", start: 0, end: 1800)
        FlintSessionLifecycle.didStart(s, replacing: nil, source: .manual, at: t0)
        XCTAssertEqual(sink.events, [.started(s, source: .manual, at: t0)])
    }

    func testStartOverRunningSessionReportsSupersede() {
        let old = session("old", start: -600, end: 1200)
        let new = session("new", start: 0, end: 1800)
        FlintSessionLifecycle.didStart(new, replacing: old, source: .shortcut, at: t0)
        XCTAssertEqual(sink.events, [
            .superseded(old, by: new, at: t0),
            .started(new, source: .shortcut, at: t0),
        ])
    }

    func testStartOverExpiredSessionReportsItsCompletionAtPlannedEnd() {
        let old = session("old", start: -1800, end: -60)
        let new = session("new", start: 0, end: 1800)
        FlintSessionLifecycle.didStart(new, replacing: old, source: .manual, at: t0)
        XCTAssertEqual(sink.events, [
            .ended(old, route: .completed, at: t0.addingTimeInterval(-60)),
            .started(new, source: .manual, at: t0),
        ])
    }

    func testRestartingSameSessionIdIsNotASupersede() {
        let s = session("same", start: 0, end: 1800)
        FlintSessionLifecycle.didStart(s, replacing: s, source: .focusFilter, at: t0)
        XCTAssertEqual(sink.events, [.started(s, source: .focusFilter, at: t0)])
    }

    func testCompletionIsRecordedAtPlannedEndEvenWhenObservedLate() {
        let s = session("a", start: 0, end: 1800)
        FlintSessionLifecycle.didEnd(s, route: .completed, at: t0.addingTimeInterval(4000))
        XCTAssertEqual(sink.events, [.ended(s, route: .completed, at: t0.addingTimeInterval(1800))])
    }

    func testEarlyEndKeepsObservedTime() {
        let s = session("a", start: 0, end: 1800)
        FlintSessionLifecycle.didUsePass(s, at: t0.addingTimeInterval(600))
        FlintSessionLifecycle.didEnd(s, route: .emergencyPass, at: t0.addingTimeInterval(600))
        XCTAssertEqual(sink.events, [
            .passUsed(s, at: t0.addingTimeInterval(600)),
            .ended(s, route: .emergencyPass, at: t0.addingTimeInterval(600)),
        ])
        XCTAssertTrue(FlintSessionEndRoute.emergencyPass.isEarly)
        XCTAssertFalse(FlintSessionEndRoute.completed.isEarly)
    }

    func testDuplicateRegistrationDeliversOnceAndUnregisterStops() {
        FlintSessionLifecycle.register(sink)
        let s = session("a", start: 0, end: nil)
        FlintSessionLifecycle.didFailToArm(s, reason: "monitor_registration_failed", at: t0)
        XCTAssertEqual(sink.events.count, 1)
        FlintSessionLifecycle.unregister(sink)
        FlintSessionLifecycle.didEnd(s, route: .manualStop, at: t0)
        XCTAssertEqual(sink.events.count, 1)
        FlintSessionLifecycle.register(sink) // tearDown unregisters
    }

    func testWireNamesMatchFocusHistoryContract() {
        XCTAssertEqual(FlintSessionSource.focusFilter.rawValue, "focus_filter")
        XCTAssertEqual(FlintSessionEndRoute.manualStop.rawValue, "manual_stop")
        XCTAssertEqual(FlintSessionEndRoute.harderWait.rawValue, "harder_wait")
        XCTAssertEqual(FlintSessionEndRoute.emergencyPass.rawValue, "emergency_pass")
        XCTAssertEqual(FlintSessionEndRoute.shortcutStop.rawValue, "shortcut_stop")
        XCTAssertEqual(FlintSessionEndRoute.focusFilterOff.rawValue, "focus_filter_off")
    }

    func testGlobalPassCarriesOperationAndRecoveryWithoutPerSessionCopies() {
        let a = session("a", start: 0, end: 1800), b = session("b", start: 0, end: 3600)
        FlintSessionLifecycle.didUsePass(operationID: "operation", sessions: [a, b],
            recoveryEnd: t0.addingTimeInterval(300), at: t0, origin: "shield_action", reason: "emergency_pass")
        XCTAssertEqual(sink.events.count, 1)
        let record = FlintHistoryEvent(sink.events[0])
        XCTAssertEqual(record.session, "operation")
        XCTAssertEqual(record.sessions, ["a", "b"])
        XCTAssertEqual(record.recoveryEnd, FlintHistoryEvent.milliseconds(t0.addingTimeInterval(300)))
        XCTAssertEqual(record.originRoute, "shield_action")
        XCTAssertEqual(record.reason, "emergency_pass")
    }
    func testCompletionBeforeDeadlineDoesNotEmit() {
        FlintSessionLifecycle.didEnd(session("early", start: 0, end: 1800), route: .completed,
                                     at: t0.addingTimeInterval(1799))
        XCTAssertTrue(sink.events.isEmpty)
    }

    func testLegacyPassHasUnknownRecoveryAndAdditiveMetadataStaysOptional() {
        let s = session("a", start: 0, end: 1800)
        FlintSessionLifecycle.didUsePass(s, at: t0)
        let record = FlintHistoryEvent(sink.events[0])
        XCTAssertTrue(record.session.hasPrefix("pass-"))
        XCTAssertEqual(record.sessions, ["a"])
        XCTAssertNil(record.recoveryEnd)
        FlintSessionLifecycle.didEnd(s, route: .manualStop, at: t0,
                                    origin: "app_ui", reason: "stop", observedAt: t0.addingTimeInterval(1))
        let end = FlintHistoryEvent(sink.events[1])
        XCTAssertEqual(end.route, "manual_stop")
        XCTAssertEqual(end.originRoute, "app_ui")
        XCTAssertEqual(end.reason, "stop")
        XCTAssertEqual(end.observedAt, FlintHistoryEvent.milliseconds(t0.addingTimeInterval(1)))
    }
}
