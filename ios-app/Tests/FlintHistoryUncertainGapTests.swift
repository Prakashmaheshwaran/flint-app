import XCTest
import FlintCore

final class FlintHistoryUncertainGapTests: XCTestCase {
    private let begin = ISO8601DateFormatter().date(from: "2026-10-28T23:50:00Z")!
    private let uncertain = FlintEventTimeConfidence.uncertain(reason: "same_boot_drift")
    private func event(_ type: FlintHistoryEventType, _ minutes: Double, session: String = "session-a") -> FlintHistoryEvent {
        .init(type: type, session: session, at: begin.addingTimeInterval(minutes * 60), timeConfidence: .certain)
    }
    private func start(_ session: String = "session-a") -> FlintHistoryEvent {
        .init(type: .started, session: session, at: begin, plannedEnd: begin.addingTimeInterval(1200),
              tier: "easy", source: "manual", name: "Focus", timeConfidence: .certain)
    }
    private func pause(_ kind: String? = "ordinary", at: Double = 10, until: Double? = 15) -> FlintHistoryEvent {
        .init(type: .breakStarted, session: "session-a", at: begin.addingTimeInterval(at * 60),
              breakEnd: until.map { begin.addingTimeInterval($0 * 60) },
              observedAt: begin.addingTimeInterval(10_800), kind: kind, grant: "g", timeConfidence: uncertain)
    }
    private func rows(_ gaps: [FlintHistoryEvent], anchors: [Double] = [20]) -> [FlintHistoryEvent] {
        [start()] + anchors.map { event(.openIntercepted, $0) } + gaps + [event(.completed, 20)]
    }
    private func summary(_ rows: [FlintHistoryEvent]) -> FlintHistorySummary {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return FlintHistoryAggregator.summarize(.init(events: rows, historyStart: begin, availability: .value),
            activeSessionID: nil, now: begin.addingTimeInterval(1800), calendar: calendar)
    }
    private func assertUnconfirmed(_ rows: [FlintHistoryEvent], minutes: Double,
                                   file: StaticString = #filePath, line: UInt = #line) {
        let result = summary(rows)
        XCTAssertEqual(result.sessions.first?.outcome, .unconfirmed, file: file, line: line)
        XCTAssertEqual(result.sessions.first?.focusedSeconds, minutes * 60, file: file, line: line)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.unconfirmed }, 1, file: file, line: line)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed + $1.endedEarly }, 0, file: file, line: line)
        XCTAssertEqual(result.streak, 0, file: file, line: line)
    }

    func testLateUncertainOrdinaryBreakDiscountsTwentyMinuteWitnessWithoutInventingPlacement() {
        let gap = pause()
        assertUnconfirmed(rows([gap]), minutes: 15)
        XCTAssertEqual(gap.timeConfidence, uncertain)
        XCTAssertEqual(gap.observedAt, FlintHistoryEvent.milliseconds(begin.addingTimeInterval(10_800)))
    }

    func testPlannedRestWithoutTrustedLengthUsesWholeCertainAnchorBracket() {
        assertUnconfirmed(rows([pause("planned")], anchors: [8, 17, 20]), minutes: 11)
    }

    func testUnboundedCoverageGapAndUnplaceableBreakCannotInventFocus() {
        var gap = event(.protectionDegraded, 10); gap.timeConfidence = uncertain
        assertUnconfirmed(rows([gap]), minutes: 0)
        assertUnconfirmed(rows([pause("planned", at: 120, until: nil)]), minutes: 0)
    }

    func testUncertainGlobalPassDiscountsReferencedSessionAndNeverCountsCompletion() {
        var pass = event(.passUsed, 10, session: "operation"); pass.timeConfidence = uncertain
        pass.sessions = ["session-a"]; pass.recoveryEnd = event(.completed, 70).at
        assertUnconfirmed(rows([pass]), minutes: 0)
    }

    func testMixedCertainAndUncertainCutsSubtractBeforeUnion() {
        var fixed = event(.breakStarted, 2); fixed.grant = "fixed"; fixed.breakEnd = event(.breakEnded, 4).at
        var close = event(.breakEnded, 4); close.grant = "fixed"
        assertUnconfirmed(rows([fixed, close, pause("planned")], anchors: [8, 17, 20]), minutes: 9)
    }

    func testOverlappingUnboundedGapsUnionTheirLossWindow() {
        let first = pause("planned")
        var other = first; other.id = "other"; other.grant = "other"
        assertUnconfirmed(rows([first, other]), minutes: 0)
    }

    func testIndependentCertainSessionFillsUncertainOverlap() {
        let result = summary(rows([pause()]) + [start("session-b"), event(.completed, 20, session: "session-b")])
        XCTAssertEqual(result.focusedSeconds, 1200)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.unconfirmed }, 1)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 1)
    }

    func testMidnightDaysEachDiscountWorstPossiblePlacementRatherThanInventingAGapLocation() {
        let result = summary(rows([pause()]))
        let days = result.days.filter { ($0.focusedSeconds ?? 0) > 0 }
        XCTAssertEqual(days.map(\.focusedSeconds), [300, 300])
        XCTAssertEqual(result.sessions.first?.focusedSeconds, 900)
        XCTAssertEqual(days.last?.unconfirmed, 1)
    }

    func testUncertainBreakEndCannotUseEarlierPlannedReturnAsCertainCoverage() {
        var fixed = event(.breakStarted, 10); fixed.kind = "planned"; fixed.grant = "g"
        fixed.breakEnd = event(.breakEnded, 15).at
        var close = event(.breakEnded, 15); close.grant = "g"; close.timeConfidence = uncertain
        assertUnconfirmed(rows([fixed, close]), minutes: 10)
    }

    func testUncertainRawStampCannotCollapsePlacementAtTrustedAnchor() {
        for at in [-120.0, 0, 20, 120] { assertUnconfirmed(rows([pause(at: at, until: at)]), minutes: 15) }
    }

    func testGrantLinkedUncertainEndPairsAcrossBackwardOrPostterminalRawStamps() {
        var fixed = event(.breakStarted, 10); fixed.kind = "planned"; fixed.grant = "g"
        fixed.breakEnd = event(.breakEnded, 15).at
        for at in [-120.0, 120] {
            var close = event(.breakEnded, at); close.grant = "g"; close.timeConfidence = uncertain
            assertUnconfirmed(rows([fixed, close]), minutes: 10)
        }
    }
}
