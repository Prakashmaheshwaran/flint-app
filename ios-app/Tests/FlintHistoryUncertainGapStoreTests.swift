import XCTest
import FlintCore

final class FlintHistoryUncertainGapStoreTests: XCTestCase {
    func testAfterClearNewSessionAdmitsUncertainLossWhileClearedReplayStaysAbsent() {
        let begin = Date(timeIntervalSince1970: 1_800_000_000), deadline = Date(timeIntervalSince1970: 1_800_001_200)
        for kind in [FlintHistoryEventType.protectionDegraded, .breakStarted, .passUsed] {
            for field in ["past", "future", "observation"] {
                for commitStart in [false, true] {
                    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                    defer { try? FileManager.default.removeItem(at: directory) }
                    let clearAt = begin.addingTimeInterval(-60)
                    let store = FlintHistoryStore(directory: directory, clock: { clearAt })
                    XCTAssertTrue(store.append(.init(type: .started, session: "old", at: begin.addingTimeInterval(-120),
                        plannedEnd: begin.addingTimeInterval(-90), tier: "easy", source: "manual", name: "Old")))
                    _ = store.read()
                    XCTAssertTrue(store.clear(keepingRunningSession: nil))
                    for row in [FlintHistoryEvent(type: .started, session: "new", at: begin, plannedEnd: deadline,
                                                 tier: "easy", source: "manual", name: "New", timeConfidence: .certain),
                                .init(type: .openIntercepted, session: "new", at: deadline, timeConfidence: .certain),
                                .init(type: .completed, session: "new", at: deadline, timeConfidence: .certain)] {
                        XCTAssertTrue(store.append(row))
                    }
                    if commitStart { XCTAssertEqual(store.read().events.count, 3) }
                    var gap = FlintHistoryEvent(type: kind, session: kind == .passUsed ? "operation" : "new",
                        at: begin.addingTimeInterval(600), breakEnd: begin.addingTimeInterval(900), kind: "ordinary",
                        sessions: kind == .passUsed ? ["new"] : nil, recoveryEnd: begin.addingTimeInterval(4200),
                        timeConfidence: .uncertain(reason: "clock_discontinuity"))
                    if field == "past" { gap.at = .min }
                    if field == "future" { gap.at = .max }
                    if field == "observation" { gap.observedAt = .max }
                    XCTAssertTrue(store.append(gap))
                    for snapshot in [store.readOnlySnapshot(), store.read()] {
                        XCTAssertEqual(snapshot.events.count, 4)
                        XCTAssertTrue(snapshot.events.contains(gap))
                        XCTAssertEqual(snapshot.historyStart, clearAt)
                        let session = FlintHistoryAggregator.sessionSummaries(snapshot.events, activeSessionID: nil, now: deadline).first
                        XCTAssertEqual(session?.outcome, .unconfirmed)
                        XCTAssertLessThanOrEqual(session?.focusedSeconds ?? .infinity, kind == .breakStarted ? 900 : 0)
                    }
                    var replay = gap; replay.id = UUID().uuidString
                    replay.session = kind == .passUsed ? "old-operation" : "old"
                    replay.sessions = kind == .passUsed ? ["old"] : nil
                    XCTAssertTrue(store.append(replay))
                    for snapshot in [store.readOnlySnapshot(), store.read()] {
                        XCTAssertEqual(snapshot.events.count, 4)
                        XCTAssertFalse(snapshot.events.contains(replay))
                        XCTAssertFalse(snapshot.events.contains { $0.session == "old" })
                    }
                    XCTAssertTrue(store.compactIfNeeded())
                    let reopened = FlintHistoryStore(directory: directory, clock: { deadline })
                    XCTAssertTrue(reopened.read().events.contains(gap))
                    XCTAssertEqual(reopened.read().events.count, 4)
                }
            }
        }
    }

    func testHostAndReadOnlyReadersRetainUncertainLossWithUnsupportedRawDates() throws {
        let begin = Date(timeIntervalSince1970: 1_800_000_000)
        let deadline = begin.addingTimeInterval(1200)
        for field in ["past", "future", "observation"] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: directory) }
            let store = FlintHistoryStore(directory: directory, clock: { deadline })
            for row in [FlintHistoryEvent(type: .started, session: "a", at: begin, plannedEnd: deadline,
                                         tier: "easy", source: "manual", name: "Focus", timeConfidence: .certain),
                        .init(type: .openIntercepted, session: "a", at: deadline, timeConfidence: .certain),
                        .init(type: .completed, session: "a", at: deadline, timeConfidence: .certain)] {
                XCTAssertTrue(store.append(row))
            }
            XCTAssertEqual(store.read().events.count, 3)
            var gap = FlintHistoryEvent(type: .protectionDegraded, session: "a", at: begin.addingTimeInterval(600),
                                       timeConfidence: .uncertain(reason: "clock_discontinuity"))
            if field == "past" { gap.at = .min }
            if field == "future" { gap.at = .max }
            if field == "observation" { gap.observedAt = .max }
            XCTAssertTrue(store.append(gap))
            for snapshot in [store.readOnlySnapshot(), store.read()] {
                XCTAssertEqual(snapshot.events.count, 4)
                XCTAssertTrue(snapshot.events.contains(gap))
                let session = FlintHistoryAggregator.sessionSummaries(snapshot.events, activeSessionID: nil, now: deadline).first
                XCTAssertEqual(session?.outcome, .unconfirmed)
                XCTAssertEqual(session?.focusedSeconds, 0)
                XCTAssertEqual(snapshot.historyStart, begin)
            }
            XCTAssertTrue(store.compactIfNeeded())
            let reopened = FlintHistoryStore(directory: directory, clock: { deadline })
            XCTAssertTrue(reopened.read().events.contains(gap))
            XCTAssertEqual(reopened.read().historyStart, begin)
        }
    }
}
