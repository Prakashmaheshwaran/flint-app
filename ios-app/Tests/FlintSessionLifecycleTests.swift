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
}
