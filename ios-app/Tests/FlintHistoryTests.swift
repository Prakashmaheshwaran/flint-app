import XCTest
import FlintCore

final class FlintHistoryTests: XCTestCase {
    private let t = Date(timeIntervalSince1970: 1_800_000_000)
    private var utc: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(secondsFromGMT: 0)!; return c }
    private var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }
    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
    private func store(now: Date? = nil) -> FlintHistoryStore {
        FlintHistoryStore(directory: directory, clock: { now ?? self.t }, calendar: utc)
    }
    private func event(_ type: FlintHistoryEventType, _ seconds: Double, id: String = "a", end: Double? = 3600,
                       inferred: Bool? = nil, route: String? = nil) -> FlintHistoryEvent {
        FlintHistoryEvent(type: type, session: id, at: t.addingTimeInterval(seconds), inferred: inferred,
                          plannedEnd: type == .started ? end.map { t.addingTimeInterval($0) } : nil,
                          tier: type == .started ? "easy" : nil, source: type == .started ? "manual" : nil,
                          name: type == .started ? "Focus" : nil, route: route)
    }
    private func summary(_ events: [FlintHistoryEvent], now: Date? = nil, active: String? = nil,
                         calendar: Calendar? = nil, start: Date? = nil) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(.init(events: events, historyStart: start ?? t.addingTimeInterval(-864000),
            availability: events.isEmpty ? .empty : .value), activeSessionID: active,
            now: now ?? t.addingTimeInterval(4000), calendar: calendar ?? utc, dayCount: 7)
    }
    func testFH01CompletedCreditsPlannedDuration() {
        let result = summary([event(.started, 0), event(.completed, 3600)])
        XCTAssertEqual(result.sessions.first?.outcome, .completed)
        XCTAssertEqual(result.focusedSeconds, 3600)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 1)
    }
    func testFH02EarlyEndCreditsHalf() {
        let result = summary([event(.started, 0), event(.endedEarly, 1800, route: "manual_stop")])
        XCTAssertEqual(result.focusedSeconds, 1800)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.endedEarly }, 1)
    }
    func testFH03DuplicateCompletionCountsOnce() {
        let result = summary([event(.started, 0), event(.completed, 3600), event(.completed, 3600, inferred: true)])
        XCTAssertEqual(result.focusedSeconds, 3600)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 1)
    }
    func testFH04FirstTerminalWinsAndFileOrderBreaksTies() {
        XCTAssertEqual(summary([event(.started, 0), event(.completed, 3600), event(.endedEarly, 3700)]).sessions[0].outcome, .completed)
        XCTAssertEqual(summary([event(.started, 0), event(.endedEarly, 3600), event(.completed, 3600)]).sessions[0].outcome, .endedEarly)
    }
    func testFH05ExpiredMissingTerminalIsPersistedAsInferredCompletion() {
        let s = store(); s.append(event(.started, 0))
        let snapshot = FlintHistoryReconciler.reconcile(store: s, activeSession: nil, now: t.addingTimeInterval(4000), calendar: utc)
        XCTAssertEqual(snapshot.events.last?.type, .completed)
        XCTAssertEqual(snapshot.events.last?.inferred, true)
        XCTAssertEqual(snapshot.events.last?.at, event(.completed, 3600).at)
        _ = FlintHistoryReconciler.reconcile(store: s, activeSession: nil, now: t.addingTimeInterval(5000), calendar: utc)
        XCTAssertEqual(s.read().events.count, 2)
    }
    func testFH06MissingActiveSessionCreditsZeroAndPersistsUnknown() {
        let s = store(); s.append(event(.started, 0))
        let snapshot = FlintHistoryReconciler.reconcile(store: s, activeSession: nil, now: t.addingTimeInterval(300), calendar: utc)
        XCTAssertEqual(snapshot.events.last?.route, "unknown")
        XCTAssertEqual(snapshot.events.last?.inferred, true)
        XCTAssertEqual(summary(snapshot.events).focusedSeconds, 0)
        XCTAssertEqual(summary([event(.started, 0, end: nil)]).focusedSeconds, 0)
    }
    func testFH07MidnightSplitsTimeAndCountsOnEndDay() {
        let start = utc.date(from: DateComponents(year: 2026, month: 10, day: 27, hour: 23, minute: 30))!
        let events = absoluteSession(start: start, end: start.addingTimeInterval(3600))
        let result = summary(events, now: start.addingTimeInterval(4000), start: start)
        XCTAssertEqual(result.days.suffix(2).compactMap(\.focusedSeconds), [1800, 1800])
        XCTAssertEqual(result.days.last?.completed, 1)
    }
    func testFH08DSTUsesCalendarDays() {
        var c = utc; c.timeZone = TimeZone(identifier: "America/New_York")!
        for (month, day, startHour, endHour, duration, dayLength) in [(3, 8, 1, 3, 3600.0, 82800.0), (11, 1, 0, 2, 10800.0, 90000.0)] {
            let start = c.date(from: DateComponents(year: 2026, month: month, day: day, hour: startHour, minute: 30))!
            let end = c.date(from: DateComponents(year: 2026, month: month, day: day, hour: endHour, minute: 30))!
            let result = summary(absoluteSession(start: start, end: end), now: end, calendar: c, start: start)
            XCTAssertEqual(result.focusedSeconds, duration)
            let dayStart = c.startOfDay(for: start)
            XCTAssertEqual(c.date(byAdding: .day, value: 1, to: dayStart)!.timeIntervalSince(dayStart), dayLength)
        }
    }
    func testFH09TravelRebucketsInCurrentZone() {
        let start = utc.date(from: DateComponents(year: 2026, month: 10, day: 28, hour: 1))!
        let end = start.addingTimeInterval(3600)
        var west = utc; west.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        let eastResult = summary(absoluteSession(start: start, end: end), now: end, start: start)
        let westResult = summary(absoluteSession(start: start, end: end), now: end, calendar: west, start: start)
        XCTAssertEqual(utc.component(.day, from: eastResult.days.last!.start), 28)
        XCTAssertEqual(west.component(.day, from: westResult.days.last!.start), 27)
        XCTAssertEqual(westResult.focusedSeconds, 3600)
    }
    func testFH10OverlappingSessionsAreUnioned() {
        let result = summary([event(.started, 0), event(.completed, 3600), event(.started, 1800, id: "b", end: 5400), event(.completed, 5400, id: "b")], now: t.addingTimeInterval(6000))
        XCTAssertEqual(result.focusedSeconds, 5400)
    }
    func testFH11BreaksSubtractClippedUnionIncludingPlannedEnd() {
        var pause = event(.breakStarted, 600); pause.breakEnd = event(.breakEnded, 1200).at
        let events = [event(.started, 0), pause, event(.breakStarted, 1800), event(.breakEnded, 2400), event(.completed, 4000)]
        // First break ends at the next observed break_ended, per the wire contract.
        XCTAssertEqual(summary(events).focusedSeconds, 1800)
        XCTAssertEqual(summary([event(.started, 0), pause, event(.completed, 3600)]).focusedSeconds, 3000)
    }
    func testFH12CorruptAndTruncatedLinesQuarantineOnceAndSalvage() throws {
        let s = store(); s.append(event(.started, 0)); s.append(event(.completed, 3600))
        let url = directory.appendingPathComponent("events.jsonl")
        let handle = try FileHandle(forWritingTo: url); try handle.seekToEnd()
        try handle.write(contentsOf: Data("bad\n{\"v\":1".utf8)); try handle.close()
        XCTAssertEqual(s.read().availability, .degraded)
        XCTAssertEqual(s.read().events.count, 2)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.appendingPathComponent("quarantine").path).count, 1)
        XCTAssertTrue(s.compactIfNeeded())
        XCTAssertEqual(s.read().events.count, 2)
        XCTAssertEqual(s.read().availability, .degraded)
    }
    func testFH13FutureRecordsRetainUnknownFieldsOnCompaction() throws {
        let s = store(); _ = s.read()
        let raw = "{\"v\":2,\"id\":\"x\",\"session\":\"future\",\"type\":\"started\",\"at\":1800000000000,\"tz\":\"UTC\",\"newField\":{\"nested\":true}}\n{\"v\":1,\"id\":\"y\",\"session\":\"future\",\"type\":\"future_kind\",\"at\":1800000000000,\"tz\":\"UTC\"}\n"
        try Data(raw.utf8).write(to: directory.appendingPathComponent("events.jsonl"))
        XCTAssertTrue(s.compactIfNeeded())
        XCTAssertEqual(try String(contentsOf: directory.appendingPathComponent("events.jsonl"), encoding: .utf8), raw)
        XCTAssertTrue(summary(s.read().events).sessions.isEmpty)
    }
    func testFH14RetentionKeepsWholeSessionAtBoundary() {
        let s = store()
        let cutoff = utc.date(byAdding: .day, value: -400, to: t)!
        for (id, offset) in [("old", -1.0), ("boundary", 0.0)] {
            s.append(.init(type: .started, session: id, at: cutoff.addingTimeInterval(-3600), tier: "easy", source: "manual", name: "Focus"))
            s.append(.init(type: .completed, session: id, at: cutoff.addingTimeInterval(offset)))
        }
        XCTAssertTrue(s.compactIfNeeded())
        XCTAssertEqual(s.read().events.map(\.session), ["boundary", "boundary"])
    }
    func testFH15ClearKeepsRunningAndMovesHistoryStart() {
        let s = store(); s.append(event(.started, 0)); s.append(event(.started, 60, id: "b"))
        let later = store(now: t.addingTimeInterval(120))
        XCTAssertTrue(later.clear(keepingRunningSession: "b"))
        XCTAssertEqual(later.read().events.map(\.session), ["b"])
        XCTAssertEqual(later.read().historyStart, t.addingTimeInterval(120))
        later.append(event(.completed, 3600, id: "b"))
        XCTAssertEqual(later.read().events.count, 2)
        XCTAssertTrue(later.clear(keepingRunningSession: nil))
        XCTAssertEqual(later.read().availability, .empty)
    }
    func testFH16AbsentEmptyZeroUnavailableRemainDistinct() {
        let result = summary([], start: utc.startOfDay(for: t))
        XCTAssertEqual(result.availability, .empty)
        XCTAssertNil(result.days.first?.focusedSeconds)
        XCTAssertEqual(result.days.last?.focusedSeconds, 0)
        let missing = FlintHistoryStore(directory: nil).read()
        XCTAssertEqual(missing.availability, .unavailable)
        XCTAssertNil(FlintHistoryAggregator.summarize(missing, activeSessionID: nil, now: t).days.last?.focusedSeconds)
    }
    func testFH17SupersededCreditsUntilReplacement() {
        let result = summary([event(.started, 0), event(.superseded, 1200), event(.started, 1200, id: "b"), event(.completed, 3600, id: "b")])
        XCTAssertEqual(result.sessions[0].focusedSeconds, 1200)
        XCTAssertEqual(result.sessions[0].outcome, .superseded)
        XCTAssertEqual(result.focusedSeconds, 3600)
    }
    func testFH18BackwardsClockAndOrphansCreditZero() {
        XCTAssertEqual(summary([event(.started, 0), event(.endedEarly, -300)]).focusedSeconds, 0)
        XCTAssertTrue(summary([event(.completed, 3600)]).sessions.isEmpty)
    }
    func testFH19StreakEndsTodayOrYesterday() {
        let midnight = utc.startOfDay(for: t)
        var events: [FlintHistoryEvent] = []
        for offset in [-2, -1, 0] {
            let start = utc.date(byAdding: .day, value: offset, to: midnight)!
            events += absoluteSession(start: start, end: start.addingTimeInterval(60), id: String(offset))
        }
        XCTAssertEqual(summary(events, now: midnight.addingTimeInterval(120)).streak, 3)
        XCTAssertEqual(summary(Array(events.dropLast(2)), now: midnight.addingTimeInterval(120)).streak, 2)
        XCTAssertEqual(summary(events, now: midnight.addingTimeInterval(120), start: midnight).streak, 1)
    }
    func testStoreConcurrentInstancesProduceCompleteLinesAndStableMetadata() {
        let a = store(), b = store(); let created = a.read().historyStart
        DispatchQueue.concurrentPerform(iterations: 200) { i in
            XCTAssertTrue((i % 2 == 0 ? a : b).append(self.event(.passUsed, Double(i), id: String(i))))
        }
        XCTAssertEqual(a.read().events.count, 200)
        XCTAssertEqual(Set(a.read().events.map(\.id)).count, 200)
        XCTAssertEqual(a.read().historyStart, created)
        XCTAssertNotEqual(a.read().availability, .degraded)
    }
    func testStoreRoundTripAndLifecycleMapping() throws {
        let session = FlintActiveSession(id: "a", name: String(repeating: "x", count: 100), startedAt: t,
            endsAt: t.addingTimeInterval(3600), breakLevel: .harder, monitorName: "not-recorded")
        let start = FlintHistoryEvent(.started(session, source: .focusFilter, at: t.addingTimeInterval(1)))
        let s = store(); XCTAssertTrue(s.append(start)); XCTAssertEqual(s.read().events, [start])
        XCTAssertEqual(start.at, FlintHistoryEvent.milliseconds(t)); XCTAssertEqual(start.name?.count, 64)
        XCTAssertEqual(start.source, "focus_filter")
        let json = try String(contentsOf: directory.appendingPathComponent("events.jsonl"), encoding: .utf8)
        XCTAssertFalse(json.contains("not-recorded"))
        XCTAssertEqual(FlintHistoryEvent(.ended(session, route: .emergencyPass, at: t)).route, "emergency_pass")
        XCTAssertEqual(FlintHistoryEvent(.failedToArm(session, reason: "monitor_registration_failed", at: t)).type, .failedToArm)
    }
    func testRunningUsesNowAndIsLabelledInProgress() {
        let result = summary([event(.started, 0)], now: t.addingTimeInterval(300), active: "a")
        XCTAssertEqual(result.focusedSeconds, 300)
        XCTAssertTrue(result.sessions[0].isInProgress)
        XCTAssertTrue(result.days.last!.inProgress)
    }
    func testAppendAfterTornTailDoesNotLoseNextRecord() throws {
        let s = store(); s.append(event(.started, 0))
        let handle = try FileHandle(forWritingTo: directory.appendingPathComponent("events.jsonl"))
        try handle.seekToEnd(); try handle.write(contentsOf: Data("{\"torn\":".utf8)); try handle.close()
        XCTAssertTrue(s.append(event(.completed, 3600)))
        XCTAssertEqual(s.read().events.count, 2)
        XCTAssertEqual(s.read().availability, .degraded)
    }
    func testMalformedMetadataIsUnavailableRatherThanReset() throws {
        let s = store(); _ = s.read()
        try Data("broken".utf8).write(to: directory.appendingPathComponent("metadata.json"))
        XCTAssertEqual(s.read().availability, .unavailable)
        XCTAssertFalse(s.append(event(.started, 0)))
    }
    func testHardCapRejectsGrowthWithoutDiscardingExistingData() throws {
        let s = store(); _ = s.read()
        let url = directory.appendingPathComponent("events.jsonl")
        try Data(repeating: 32, count: FlintHistoryStore.hardLimit).write(to: url)
        XCTAssertFalse(s.append(event(.started, 0)))
        XCTAssertEqual(try Data(contentsOf: url).count, FlintHistoryStore.hardLimit)
    }
    private func absoluteSession(start: Date, end: Date, id: String = "a") -> [FlintHistoryEvent] {
        [.init(type: .started, session: id, at: start, plannedEnd: end, tier: "easy", source: "manual", name: "Focus"),
         .init(type: .completed, session: id, at: end)]
    }
}
