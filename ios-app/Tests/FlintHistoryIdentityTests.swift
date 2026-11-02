import XCTest
import FlintCore

final class FlintHistoryIdentityTests: XCTestCase {
    private let t = Date(timeIntervalSince1970: 1_800_000_000)
    private var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }
    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.removeItem(at: directory)
        }
    }
    private func session(_ id: String = "a", start: Double = 0, end: Double = 3600) -> FlintActiveSession {
        .init(id: id, name: "Focus", startedAt: t.addingTimeInterval(start),
              endsAt: t.addingTimeInterval(end), breakLevel: .easy, monitorName: "unused")
    }
    private func store() -> FlintHistoryStore {
        .init(directory: directory, clock: { self.t.addingTimeInterval(7000) }, calendar: utc)
    }
    private func summary(_ snapshot: FlintHistorySnapshot) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(snapshot, activeSessionID: nil,
            now: t.addingTimeInterval(7000), calendar: utc)
    }

    func testLifecycleRedeliveryMatchesFixedIdentityVectors() throws {
        let original = session()
        let restored = try JSONDecoder().decode(FlintActiveSession.self, from: JSONEncoder().encode(original))
        // Fixed vectors pin the identity across launches and implementation changes.
        let expected = [
            "lifecycle-v1-0e083ae7bc1a4ba4326fe072dbc5ec7efd8bd2da4021f619c9a5c320b1df81ee",
            "lifecycle-v1-6fd5876ee88b8d55dbdce3cfe8619da4e6841d34113805eed6ddaeb5c0c5e3da",
            "lifecycle-v1-611f637aee908c246d81aebfccf033ce30bc7728d9831b5d7481882918fac97f",
            "lifecycle-v1-892c2286c7924f4f02d0e81c6f416b13727923058f9311f3669d126009af9045"
        ]
        for s in [original, restored] {
            let events: [FlintSessionLifecycleEvent] = [
                .started(s, source: .manual, at: t),
                .ended(s, route: .completed, at: t.addingTimeInterval(3600)),
                .ended(s, route: .manualStop, at: t.addingTimeInterval(1800)),
                .superseded(s, by: session("b"), at: t.addingTimeInterval(1800))
            ]
            for (event, id) in zip(events, expected) {
                let first = FlintHistoryEvent(event), replay = FlintHistoryEvent(event)
                XCTAssertEqual(first.id, id)
                XCTAssertEqual(replay.id, id)
                XCTAssertEqual(first.id.utf8.count, 77)
                XCTAssertTrue(first.isSupported)
                XCTAssertLessThanOrEqual(try JSONEncoder().encode(first).count + 1, FlintHistoryStore.recordLimit)
                XCTAssertEqual(try JSONDecoder().decode(FlintHistoryEvent.self,
                    from: JSONEncoder().encode(first)), first)
            }
        }
    }

    func testLifecycleIdentityIgnoresDatesZonesSourcesAndAnnotations() {
        let original = session()
        let changed = FlintActiveSession(id: original.id, name: "Renamed", startedAt: t.addingTimeInterval(60),
            endsAt: t.addingTimeInterval(7200), breakLevel: .harder, monitorName: "different")
        let initial: [FlintSessionLifecycleEvent] = [
            .started(original, source: .manual, at: t),
            .ended(original, route: .completed, at: t.addingTimeInterval(3600)),
            .ended(original, route: .manualStop, at: t.addingTimeInterval(1800)),
            .superseded(original, by: session("b"), at: t.addingTimeInterval(1800))
        ]
        let replay: [FlintSessionLifecycleEvent] = [
            .started(changed, source: .focusFilter, at: t.addingTimeInterval(120)),
            .ended(changed, route: .completed, at: t.addingTimeInterval(7200)),
            .ended(changed, route: .manualStop, at: t.addingTimeInterval(2400)),
            .superseded(changed, by: session("b", start: 2400), at: t.addingTimeInterval(2400))
        ]
        for (first, retry) in zip(initial, replay) {
            let annotated = FlintSessionLifecycleEvent.annotated(retry, origin: "startup_reconciliation",
                reason: "recovered", observedAt: t.addingTimeInterval(8000),
                timeConfidence: .uncertain(reason: "reboot"))
            let nested = FlintSessionLifecycleEvent.annotated(annotated, origin: "monitor",
                reason: nil, observedAt: nil)
            XCTAssertEqual(FlintHistoryEvent(first, timeZone: utc.timeZone).id,
                FlintHistoryEvent(nested, timeZone: TimeZone(secondsFromGMT: -28800)!).id)
            XCTAssertEqual(FlintHistoryEvent(nested).timeConfidence, .uncertain(reason: "reboot"))
        }
    }

    func testSessionKindRouteAndReplacementSeparateIdentities() {
        var ids: [String] = []
        for s in [session("a"), session("b")] {
            ids.append(FlintHistoryEvent(.started(s, source: .manual, at: t)).id)
            for route in FlintSessionEndRoute.allCases {
                ids.append(FlintHistoryEvent(.ended(s, route: route, at: t)).id)
            }
            for next in [session("c"), session("d")] {
                ids.append(FlintHistoryEvent(.superseded(s, by: next, at: t)).id)
            }
        }
        XCTAssertEqual(Set(ids).count, ids.count)
        // Delimiters inside arbitrary IDs cannot shift field boundaries.
        let pairs = [("a|b", "c"), ("a", "b|c"), ("a:1:b", "c"), ("a", "1:b:c"),
                     ("é🪨", "b"), ("b", "é🪨"), (String(repeating: "é", count: 64), "b")]
        let pairIDs = pairs.map { FlintHistoryEvent(.superseded(session($0.0), by: session($0.1), at: t)).id }
        XCTAssertEqual(Set(pairIDs).count, pairs.count)
        XCTAssertTrue(pairIDs.allSatisfy { $0.utf8.count == 77 })
    }

    func testRepeatableOperationsAndGenericInitializerKeepTheirIDs() {
        let types: [FlintHistoryEventType] = [
            .started, .completed, .endedEarly, .superseded, .breakStarted, .breakEnded, .passUsed,
            .failedToArm, .rescheduled, .openIntercepted, .protectionDegraded, .protectionRestored,
            .accessStarted, .accessEnded
        ]
        for type in types {
            let first = FlintHistoryEvent(type: type, session: "a", at: t)
            XCTAssertNotEqual(first.id, FlintHistoryEvent(type: type, session: "a", at: t).id)
            XCTAssertEqual(FlintHistoryEvent(type: type, session: "a", at: t, id: "caller-operation").id,
                           "caller-operation")
        }
        let failure = FlintSessionLifecycleEvent.failedToArm(session(), reason: "registration_failed", at: t)
        XCTAssertNotEqual(FlintHistoryEvent(failure).id, FlintHistoryEvent(failure).id)
        let pass = FlintSessionLifecycleEvent.passUsed(session(), at: t)
        let firstPass = FlintHistoryEvent(pass), secondPass = FlintHistoryEvent(pass)
        XCTAssertNotEqual(firstPass.id, secondPass.id)
        XCTAssertNotEqual(firstPass.session, secondPass.session)
        let global = FlintSessionLifecycleEvent.globalPassUsed(operationID: "pass-operation",
            sessions: [session()], recoveryEnd: t.addingTimeInterval(60), at: t)
        XCTAssertEqual(FlintHistoryEvent(global).id, "pass-operation")
        XCTAssertEqual(FlintHistoryEvent(global).session, "pass-operation")
        XCTAssertEqual(FlintHistoryEvent(global).id, FlintHistoryEvent(global).id)
    }

    func testStoreReopenDeduplicatesLifecycleReplayAndPreservesIndependentOperations() {
        let a = session(), b = session("b", start: 4000, end: 5800)
        let c = session("c", start: 6000, end: 6900)
        let lifecycle: [FlintSessionLifecycleEvent] = [
            .started(a, source: .manual, at: t), .ended(a, route: .completed, at: t.addingTimeInterval(3600)),
            .started(b, source: .shortcut, at: b.startedAt), .ended(b, route: .shortcutStop, at: t.addingTimeInterval(4900)),
            .started(c, source: .manual, at: c.startedAt),
            .superseded(c, by: session("d", start: 6600, end: 10000), at: t.addingTimeInterval(6600))
        ]
        let passes: [FlintSessionLifecycleEvent] = [
            .globalPassUsed(operationID: "pass-1", sessions: [a], recoveryEnd: t.addingTimeInterval(1900), at: t.addingTimeInterval(1800)),
            .globalPassUsed(operationID: "pass-2", sessions: [a], recoveryEnd: t.addingTimeInterval(2200), at: t.addingTimeInterval(2100))
        ]
        let ingress = FlintHistoryStore(directory: directory, isHost: false)
        for event in lifecycle + passes {
            XCTAssertTrue(ingress.append(FlintHistoryEvent(event)))
            XCTAssertTrue(ingress.append(FlintHistoryEvent(event)))
        }
        for (start, end) in [(600.0, 900.0), (1200.0, 1500.0)] {
            XCTAssertTrue(ingress.append(.init(type: .breakStarted, session: a.id, at: t.addingTimeInterval(start))))
            XCTAssertTrue(ingress.append(.init(type: .breakEnded, session: a.id, at: t.addingTimeInterval(end))))
        }
        for _ in 0..<2 {
            XCTAssertTrue(ingress.append(.init(.failedToArm(a, reason: "registration_failed", at: t))))
        }
        let first = store().read()
        XCTAssertEqual(first.availability, .value)
        XCTAssertEqual(first.events.count, 14)
        // Reconstruct deliveries after the first durable drain, then reopen a new host.
        for event in lifecycle + passes {
            XCTAssertTrue(ingress.append(.init(.annotated(event, origin: "startup_reconciliation",
                reason: "retry", observedAt: t.addingTimeInterval(7000)))))
        }
        XCTAssertEqual(store().readOnlySnapshot().events, first.events)
        let reopened = store(), snapshot = reopened.read()
        XCTAssertEqual(snapshot.events, first.events)
        XCTAssertEqual(reopened.read().events, first.events)
        XCTAssertEqual(reopened.readOnlySnapshot().events, first.events)
        XCTAssertEqual(snapshot.events.filter { $0.type == .failedToArm }.count, 2)
        XCTAssertEqual(Set(snapshot.events.filter { $0.type == .passUsed }.map(\.id)), ["pass-1", "pass-2"])
        let result = summary(snapshot)
        XCTAssertEqual(result.sessions.count, 3)
        XCTAssertEqual(result.sessions.first { $0.id == "a" }?.focusedSeconds, 2800)
        XCTAssertEqual(result.sessions.first { $0.id == "b" }?.focusedSeconds, 900)
        XCTAssertEqual(result.sessions.first { $0.id == "c" }?.focusedSeconds, 600)
        XCTAssertEqual(result.focusedSeconds, 4300)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 1)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.endedEarly }, 2) // Includes superseded.
        XCTAssertEqual(result.sessions.filter { $0.outcome == .endedEarly }.count, 1)
        XCTAssertEqual(result.sessions.filter { $0.outcome == .superseded }.count, 1)
    }

    func testHistoricalRandomIDRowsRemainReadableWithoutRetroactiveIDDeduplication() {
        let s = session(), host = store()
        let stableStart = FlintHistoryEvent(.started(s, source: .manual, at: t))
        let stableEnd = FlintHistoryEvent(.ended(s, route: .completed, at: t.addingTimeInterval(3600)))
        var historicalStart = stableStart, historicalEnd = stableEnd
        historicalStart.id = UUID().uuidString; historicalEnd.id = UUID().uuidString
        for event in [historicalStart, historicalEnd, stableStart, stableEnd] {
            XCTAssertTrue(host.append(event))
        }
        let snapshot = store().read()
        XCTAssertEqual(snapshot.events.count, 4, "Old random IDs cannot match new deterministic IDs")
        XCTAssertTrue(snapshot.events.contains(historicalStart))
        XCTAssertTrue(snapshot.events.contains(historicalEnd))
        XCTAssertEqual(summary(snapshot).focusedSeconds, 3600)
        XCTAssertEqual(summary(snapshot).days.reduce(0) { $0 + $1.completed }, 1)
    }
}
