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

    func testCompletionPreservesExplicitLogicalEndAfterBackwardWallJump() {
        let s = session("rollback", start: 0, end: 3600)
        let logicalEnd = t0.addingTimeInterval(1800)
        FlintSessionLifecycle.didEnd(s, route: .completed, at: logicalEnd.addingTimeInterval(5),
                                    logicalEnd: logicalEnd)
        XCTAssertEqual(sink.events.map(\.unannotated), [.ended(s, route: .completed, at: logicalEnd)])
        XCTAssertEqual(FlintHistoryEvent(sink.events[0]).date, logicalEnd)
        XCTAssertEqual(FlintHistoryEvent(sink.events[0]).observedAt,
                       FlintHistoryEvent.milliseconds(logicalEnd.addingTimeInterval(5)))
    }

    func testExplicitLogicalEndCannotCompleteEarlyAndKeepsObservedMetadata() {
        let s = session("forward", start: 0, end: 1800)
        let logicalEnd = t0.addingTimeInterval(5400)
        FlintSessionLifecycle.didEnd(s, route: .completed, at: logicalEnd.addingTimeInterval(-1),
                                    logicalEnd: logicalEnd)
        XCTAssertTrue(sink.events.isEmpty)
        let observed = logicalEnd.addingTimeInterval(5)
        FlintSessionLifecycle.didEnd(s, route: .completed, at: observed, origin: "monitor",
                                    reason: "deadline", logicalEnd: logicalEnd)
        let record = FlintHistoryEvent(sink.events[0])
        XCTAssertEqual(record.date, logicalEnd)
        XCTAssertEqual(record.observedAt, FlintHistoryEvent.milliseconds(observed))
        XCTAssertEqual(record.originRoute, "monitor")
        XCTAssertEqual(record.reason, "deadline")
    }

    func testExplicitObservationIsPreservedWhenLogicalCompletionIsDeliveredLater() {
        let s = session("delayed", start: 0, end: 3600)
        let logicalEnd = t0.addingTimeInterval(1800), observed = t0.addingTimeInterval(1805)
        FlintSessionLifecycle.didEnd(s, route: .completed, at: observed.addingTimeInterval(10),
                                    origin: "monitor", observedAt: observed, logicalEnd: logicalEnd)
        let record = FlintHistoryEvent(sink.events[0])
        XCTAssertEqual(record.date, logicalEnd)
        XCTAssertEqual(record.observedAt, FlintHistoryEvent.milliseconds(observed))
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

    func testOriginalExactFunctionReferencesAndGlobalPassOverloadRemainCompatible() {
        let start: (FlintActiveSession, FlintActiveSession?, FlintSessionSource, Date) -> Void =
            FlintSessionLifecycle.didStart(_:replacing:source:at:)
        let end: (FlintActiveSession, FlintSessionEndRoute, Date) -> Void = FlintSessionLifecycle.didEnd(_:route:at:)
        let pass: (FlintActiveSession, Date) -> Void = FlintSessionLifecycle.didUsePass(_:at:)
        let failure: (FlintActiveSession, String, Date) -> Void = FlintSessionLifecycle.didFailToArm(_:reason:at:)
        let globalPass: (String, [FlintActiveSession], Date?, Date) -> Void =
            FlintSessionLifecycle.didUsePass(operationID:sessions:recoveryEnd:at:)
        let s = session("a", start: 0, end: 1800)
        start(s, nil, .manual, t0); end(s, .manualStop, t0); pass(s, t0)
        failure(s, "registration_failed", t0); globalPass("operation", [s], nil, t0)
        XCTAssertEqual(sink.events.count, 5)
        FlintSessionLifecycle.didStart(s, replacing: nil, source: .manual)
        FlintSessionLifecycle.didEnd(s, route: .manualStop)
        FlintSessionLifecycle.didUsePass(s)
        FlintSessionLifecycle.didFailToArm(s, reason: "registration_failed")
        FlintSessionLifecycle.didUsePass(operationID: "operation2", sessions: [s], recoveryEnd: nil)
        XCTAssertEqual(sink.events.count, 10)
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
