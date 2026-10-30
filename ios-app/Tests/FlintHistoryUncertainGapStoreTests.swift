import XCTest
import FlintCore

final class FlintHistoryUncertainGapStoreTests: XCTestCase {
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
