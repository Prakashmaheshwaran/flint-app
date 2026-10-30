import XCTest
import FlintCore

final class FlintHistoryTimeConfidenceTests: XCTestCase {
    private let begin = Date(timeIntervalSince1970: 1_800_000_000)
    private var calendar: Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = TimeZone(secondsFromGMT: 0)!
        return result
    }
    private let uncertain = FlintEventTimeConfidence.uncertain(reason: "clock_discontinuity")
    private func event(_ type: FlintHistoryEventType, _ seconds: TimeInterval,
                       confidence: FlintEventTimeConfidence? = nil) -> FlintHistoryEvent {
        .init(type: type, session: "session-a", at: begin.addingTimeInterval(seconds),
              timeConfidence: confidence)
    }
    private func started(_ confidence: FlintEventTimeConfidence? = nil) -> FlintHistoryEvent {
        .init(type: .started, session: "session-a", at: begin, plannedEnd: begin.addingTimeInterval(600),
              tier: "easy", source: "manual", name: "Focus", timeConfidence: confidence)
    }
    private func summary(_ records: [FlintHistoryEvent], now: TimeInterval = 10_800,
                         active: Bool = false) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(.init(events: records, historyStart: begin.addingTimeInterval(-86400),
            availability: .value), activeSessionID: active ? "session-a" : nil,
            now: begin.addingTimeInterval(now), calendar: calendar)
    }
    private func assertUnconfirmed(_ records: [FlintHistoryEvent], focused: TimeInterval = 0,
                                   file: StaticString = #filePath, line: UInt = #line) {
        let result = summary(records)
        XCTAssertEqual(result.sessions.first?.outcome, .unconfirmed, file: file, line: line)
        XCTAssertEqual(result.focusedSeconds, focused, file: file, line: line)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed + $1.endedEarly }, 0, file: file, line: line)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.unconfirmed }, 1, file: file, line: line)
        XCTAssertEqual(result.streak, 0, file: file, line: line)
    }

    func testLegacyAbsentAndNullConfidenceRemainReadableWithoutAddingWireFields() throws {
        let encoded = try JSONEncoder().encode(event(.completed, 600))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertNil(object["timeConfidence"])
        XCTAssertNil(try JSONDecoder().decode(FlintHistoryEvent.self, from: encoded).timeConfidence)
        object["timeConfidence"] = NSNull()
        let decoded = try JSONDecoder().decode(FlintHistoryEvent.self,
            from: JSONSerialization.data(withJSONObject: object))
        XCTAssertNil(decoded.timeConfidence)
        XCTAssertEqual(summary([started(), decoded]).sessions.first?.outcome, .completed)
        XCTAssertEqual(summary([started(), decoded]).focusedSeconds, 600)
    }

    func testConfidenceWireIsAdditiveAndOlderReadersCanIgnoreIt() throws {
        struct OldReader: Decodable { let v: Int; let type: String; let session: String; let at: Int64 }
        for confidence in [FlintEventTimeConfidence.certain, uncertain] {
            let record = event(.completed, 600, confidence: confidence)
            let data = try JSONEncoder().encode(record)
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            let field = try XCTUnwrap(object["timeConfidence"] as? [String: String])
            XCTAssertEqual(field["kind"], confidence == .certain ? "certain" : "uncertain")
            XCTAssertEqual(try JSONDecoder().decode(FlintHistoryEvent.self, from: data).timeConfidence, confidence)
            XCTAssertEqual(try JSONDecoder().decode(OldReader.self, from: data).v, 1)
        }
    }

    func testFutureAndMalformedConfidenceCannotBecomeLegacyCertainty() throws {
        let values: [Any] = [["kind": "future"], ["kind": "uncertain"],
                             ["kind": "uncertain", "reason": 1], [:], false, [], "certain"]
        for value in values {
            var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(event(.completed, 600))) as? [String: Any])
            object["timeConfidence"] = value
            let decoded = try JSONDecoder().decode(FlintHistoryEvent.self,
                from: JSONSerialization.data(withJSONObject: object))
            XCTAssertEqual(decoded.timeConfidence, .uncertain(reason: "unsupported_time_confidence"))
            assertUnconfirmed([started(), decoded])
        }
    }

    func testConfidenceAndFutureFieldsSurviveStoreCompactionAndReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FlintHistoryStore(directory: directory, clock: { self.begin.addingTimeInterval(10_800) })
        XCTAssertTrue(store.append(started())); _ = store.read()
        let data = try JSONEncoder().encode(event(.completed, 600, confidence: uncertain))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object["future"] = ["nested": true]
        let raw = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) + Data([10])
        let log = directory.appendingPathComponent("events.jsonl")
        let handle = try FileHandle(forWritingTo: log)
        try handle.seekToEnd(); try handle.write(contentsOf: raw); try handle.close()
        XCTAssertTrue(store.compactIfNeeded())
        let reopened = FlintHistoryStore(directory: directory, clock: { self.begin.addingTimeInterval(10_800) })
        XCTAssertEqual(reopened.read().events.last?.timeConfidence, uncertain)
        XCTAssertTrue(try Data(contentsOf: log).range(of: raw) != nil)
        assertUnconfirmed(reopened.read().events)
    }

    func testForwardClockJumpWitnessCannotTurnTwoMinutesIntoWholePlan() {
        for kind in [FlintHistoryEventType.openIntercepted, .breakStarted, .breakEnded, .protectionDegraded, .protectionRestored] {
            for inferred in [false, true] {
                var jump = event(kind, 7260); jump.inferred = inferred
                jump.observedAt = FlintHistoryEvent.milliseconds(begin.addingTimeInterval(120))
                jump.breakEnd = FlintHistoryEvent.milliseconds(begin.addingTimeInterval(7500))
                assertUnconfirmed([started(), jump])
                assertUnconfirmed([started(), event(.openIntercepted, 60), jump], focused: 60)
            }
        }
    }

    func testRawEarlyTerminalsAfterPlanCannotClampIntoFullFocus() {
        let confidences: [FlintEventTimeConfidence?] = [nil, .certain, uncertain]
        for confidence in confidences {
            var end = event(.endedEarly, 7260, confidence: confidence); end.route = "emergency_pass"
            assertUnconfirmed([started(), end])
            assertUnconfirmed([started(), event(.openIntercepted, 120), end], focused: 120)
        }
    }

    func testTerminalAtOrBeforeAnchorAndPrematureCompletionCannotConfirm() {
        for kind in [FlintHistoryEventType.completed, .endedEarly, .superseded] {
            for seconds in [-60.0, 0.0] {
                var end = event(kind, seconds); end.route = "manual_stop"; end.by = "next"
                assertUnconfirmed([started(), end])
                assertUnconfirmed([started(), event(.openIntercepted, 60), end], focused: 60)
            }
        }
        for confidence in [nil, .certain, uncertain] as [FlintEventTimeConfidence?] {
            assertUnconfirmed([started(), event(.completed, 120, confidence: confidence)])
        }
    }

    func testBackwardWitnessAndNonpositivePlanCannotCreateCredit() {
        assertUnconfirmed([started(), event(.openIntercepted, -60), event(.completed, 600)])
        for seconds in [-60.0, 0.0] {
            var start = started(); start.plannedEnd = FlintHistoryEvent.milliseconds(begin.addingTimeInterval(seconds))
            assertUnconfirmed([start, event(.openIntercepted, 60), event(.completed, 600)])
        }
    }

    func testExplicitUncertainTerminalRetainsOnlyPriorTrustedCoverage() {
        for kind in [FlintHistoryEventType.completed, .endedEarly, .superseded] {
            var end = event(kind, kind == .completed ? 600 : 300, confidence: uncertain)
            end.route = "manual_stop"; end.by = "next"
            assertUnconfirmed([started(), end])
            assertUnconfirmed([started(), event(.openIntercepted, 120), end], focused: 120)
        }
    }

    func testUncertainStartPlanAndTransitionCannotExtendPrefix() {
        assertUnconfirmed([started(uncertain), event(.openIntercepted, 120), event(.completed, 600)])
        var plan = event(.rescheduled, 60, confidence: uncertain); plan.plannedEnd = started().plannedEnd
        assertUnconfirmed([started(), plan, event(.openIntercepted, 120), event(.completed, 600)])
        for confidence in [nil, uncertain] as [FlintEventTimeConfidence?] {
            var gap = event(.protectionDegraded, 180, confidence: confidence)
            gap.reason = confidence == nil ? "time_uncertain" : "permission"
            assertUnconfirmed([started(), event(.openIntercepted, 120), gap,
                               event(.protectionRestored, 240), event(.completed, 600)], focused: 120)
        }
    }

    func testInferredLateObservationIsDistinctFromUncertainLogicalTime() {
        for confidence in [nil, .certain] as [FlintEventTimeConfidence?] {
            var end = event(.completed, 600, confidence: confidence)
            end.inferred = true; end.observedAt = event(.completed, 7260).at
            let result = summary([started(), end])
            XCTAssertEqual(result.sessions.first?.outcome, .completed)
            XCTAssertEqual(result.focusedSeconds, 600)
            XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 1)
            var uncertainEnd = end; uncertainEnd.reason = "time_uncertain"
            assertUnconfirmed([started(), uncertainEnd])
        }
        assertUnconfirmed([started(), event(.completed, 7260)])
        XCTAssertEqual(summary([started(), event(.completed, 7260, confidence: .certain)]).focusedSeconds, 600)
    }

    func testReaderClockRollbackCannotInvalidateAcceptedLogicalCompletion() {
        var end = event(.completed, 600); end.inferred = true; end.observedAt = event(.completed, 660).at
        let result = summary([started(), end], now: 60)
        XCTAssertEqual(result.sessions.first?.outcome, .completed)
        XCTAssertEqual(result.focusedSeconds, 600)
    }

    func testLegacyCertainEarlyTiesAndRunningCreditRemainCompatible() {
        for confidence in [nil, .certain] as [FlintEventTimeConfidence?] {
            for seconds in [120.0, 600.0] {
                var end = event(.endedEarly, seconds, confidence: confidence); end.route = "manual_stop"
                XCTAssertEqual(summary([started(), end]).focusedSeconds, seconds)
                XCTAssertEqual(summary([started(), end]).sessions.first?.outcome, .endedEarly)
            }
        }
        assertUnconfirmed([started(), event(.openIntercepted, 120)], focused: 120)
        XCTAssertEqual(summary([started()], now: 120, active: true).focusedSeconds, 120)
    }

    func testBreakReplayUsesLogicalGrantPairingAndRejectsContradictions() {
        var pause = event(.breakStarted, 120); pause.grant = "g"; pause.breakEnd = event(.breakEnded, 300).at
        var resume = event(.breakEnded, 240); resume.grant = "g"
        for phases in [[pause, resume], [resume, pause]] {
            let result = summary([started()] + phases + [event(.completed, 600)])
            XCTAssertEqual(result.sessions.first?.outcome, .completed)
            XCTAssertEqual(result.focusedSeconds, 480)
        }
        var backwards = resume; backwards.at = event(.breakEnded, 60).at
        assertUnconfirmed([started(), backwards, pause, event(.completed, 600)])
        var wrong = resume; wrong.grant = "wrong"
        assertUnconfirmed([started(), wrong, pause, event(.completed, 600)], focused: 120)
    }

    func testEffectivePlanRevisionKeepsObservationTimeSeparate() {
        var extensionPlan = event(.rescheduled, 120)
        extensionPlan.plannedEnd = event(.completed, 1200).at; extensionPlan.observedAt = event(.rescheduled, 180).at
        var logicalPlan = event(.rescheduled, 900)
        logicalPlan.plannedEnd = event(.completed, 900).at
        logicalPlan.observedAt = event(.rescheduled, 7260).at; logicalPlan.inferred = true
        var end = event(.completed, 900); end.observedAt = logicalPlan.observedAt; end.inferred = true
        let result = summary([started(), extensionPlan, logicalPlan, end])
        XCTAssertEqual(result.sessions.first?.outcome, .completed)
        XCTAssertEqual(result.focusedSeconds, 900)
    }

    func testLaterCleanupDoesNotTaintTerminalAndUncertainPassCannotConfirm() {
        let result = summary([started(), event(.completed, 600), event(.breakEnded, 7260, confidence: uncertain)])
        XCTAssertEqual(result.sessions.first?.outcome, .completed)
        XCTAssertEqual(result.focusedSeconds, 600)
        var pass = event(.passUsed, 180, confidence: uncertain); pass.session = "operation"
        pass.sessions = ["session-a"]; pass.recoveryEnd = event(.passUsed, 240).at
        assertUnconfirmed([started(), event(.openIntercepted, 120), pass, event(.completed, 600)], focused: 120)
    }
}
