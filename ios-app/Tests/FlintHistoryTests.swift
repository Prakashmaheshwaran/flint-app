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
    func testFH05ExpiredMissingTerminalRemainsUnconfirmedWithoutPersistingAnEnd() {
        let s = store(); s.append(event(.started, 0))
        let snapshot = FlintHistoryReconciler.reconcile(store: s, activeSession: nil, now: t.addingTimeInterval(4000), calendar: utc)
        XCTAssertEqual(snapshot.events.count, 1)
        let result = summary(snapshot.events)
        XCTAssertEqual(result.sessions.first?.outcome, .unconfirmed)
        XCTAssertEqual(result.focusedSeconds, 0)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 0)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.unconfirmed }, 1)
        XCTAssertEqual(result.streak, 0)
        _ = FlintHistoryReconciler.reconcile(store: s, activeSession: nil, now: t.addingTimeInterval(5000), calendar: utc)
        XCTAssertEqual(s.read().events.count, 1)
    }
    func testFH06MissingActiveSessionCreditsZeroAndLeavesOutboxRecoveryOpen() {
        let s = store(); s.append(event(.started, 0))
        let snapshot = FlintHistoryReconciler.reconcile(store: s, activeSession: nil, now: t.addingTimeInterval(300), calendar: utc)
        XCTAssertEqual(snapshot.events.count, 1)
        XCTAssertEqual(summary(snapshot.events, now: t.addingTimeInterval(300)).sessions.first?.outcome, .unconfirmed)
        XCTAssertEqual(summary(snapshot.events).focusedSeconds, 0)
        XCTAssertEqual(summary([event(.started, 0, end: nil)]).focusedSeconds, 0)
    }
    func testR17KilledSessionOnlyCreditsPositiveCoverageAndSubtractsBreaks() {
        let events = [event(.started, 0), event(.breakStarted, 600), event(.breakEnded, 900),
                      event(.protectionDegraded, 1200), event(.protectionRestored, 1800)]
        let result = summary(events)
        XCTAssertEqual(result.sessions[0].outcome, .unconfirmed)
        XCTAssertEqual(result.focusedSeconds, 900)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 0)
        // A stale active ID after the deadline cannot turn the plan into proof.
        XCTAssertEqual(summary([event(.started, 0)], active: "a").focusedSeconds, 0)
    }
    func testR17LateMonitorOutboxResolvesWithoutGuessedTerminalWinning() {
        let host = store(); XCTAssertTrue(host.append(event(.started, 0)))
        for seconds in [300.0, 4000.0, 5000.0] {
            let snapshot = FlintHistoryReconciler.reconcile(store: host, activeSession: nil,
                now: t.addingTimeInterval(seconds), calendar: utc)
            XCTAssertEqual(snapshot.events.count, 1)
        }
        let monitor = FlintHistoryStore(directory: directory, clock: { self.t.addingTimeInterval(5000) }, isHost: false)
        var terminal = event(.completed, 3600, inferred: true)
        terminal.originRoute = "monitor"; terminal.observedAt = FlintHistoryEvent.milliseconds(t.addingTimeInterval(5000))
        XCTAssertTrue(monitor.append(terminal)); XCTAssertTrue(monitor.append(terminal))
        let snapshot = FlintHistoryReconciler.reconcile(store: host, activeSession: nil,
            now: t.addingTimeInterval(5000), calendar: utc)
        let result = summary(snapshot.events)
        XCTAssertEqual(result.focusedSeconds, 3600)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.completed }, 1)
        XCTAssertEqual(result.days.reduce(0) { $0 + $1.unconfirmed }, 0)
    }
    func testR17UnconfirmedDSTAndMidnightStayZeroUntilLogicalTerminalArrives() {
        var c = utc; c.timeZone = TimeZone(identifier: "America/New_York")!
        for (month, day, startHour, endHour) in [(3, 8, 1, 3), (11, 1, 0, 2), (3, 8, 23, 24), (11, 1, 23, 24)] {
            let start = c.date(from: DateComponents(year: 2026, month: month, day: day, hour: startHour, minute: 30))!
            let end = endHour == 24 ? start.addingTimeInterval(3600) :
                c.date(from: DateComponents(year: 2026, month: month, day: day, hour: endHour, minute: 30))!
            let events = absoluteSession(start: start, end: end)
            let unknown = summary([events[0]], now: end.addingTimeInterval(3600), calendar: c, start: start)
            XCTAssertEqual(unknown.focusedSeconds, 0)
            XCTAssertEqual(unknown.days.reduce(0) { $0 + $1.unconfirmed }, 1)
            XCTAssertEqual(unknown.days.reduce(0) { $0 + $1.completed }, 0)
            let confirmed = summary(events, now: end.addingTimeInterval(3600), calendar: c, start: start)
            XCTAssertEqual(confirmed.focusedSeconds, end.timeIntervalSince(start))
            if endHour == 24 { XCTAssertEqual(confirmed.days.suffix(2).compactMap(\.focusedSeconds), [1800, 1800]) }
        }
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
    func testFH08DSTNextMidnightSplitsCorrectly() {
        var c = utc; c.timeZone = TimeZone(identifier: "America/New_York")!
        for (month, day) in [(3, 8), (11, 1)] {
            let start = c.date(from: DateComponents(year: 2026, month: month, day: day, hour: 23, minute: 30))!
            let end = start.addingTimeInterval(3600)
            let result = summary(absoluteSession(start: start, end: end), now: end, calendar: c, start: start)
            XCTAssertEqual(result.days.suffix(2).compactMap(\.focusedSeconds), [1800, 1800])
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
        // The second break's observed end must not extend the first break.
        XCTAssertEqual(summary(events).focusedSeconds, 2400)
        XCTAssertEqual(summary([event(.started, 0), pause, event(.completed, 3600)]).focusedSeconds, 3000)
    }
    func testFH12CorruptAndTruncatedLinesQuarantineOnceAndSalvage() throws {
        let s = store(); s.append(event(.started, 0)); s.append(event(.completed, 3600))
        _ = s.read()
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
    func testFH20RescheduledDeadlineControlsInferenceAndClamping() {
        var extended = event(.rescheduled, 1800); extended.plannedEnd = event(.completed, 7200).at
        let running = summary([event(.started, 0), extended], now: t.addingTimeInterval(4000), active: "a")
        XCTAssertEqual(running.sessions[0].outcome, .running)
        XCTAssertEqual(running.focusedSeconds, 4000)
        let unconfirmed = summary([event(.started, 0), extended], now: t.addingTimeInterval(8000))
        XCTAssertEqual(unconfirmed.sessions[0].outcome, .unconfirmed)
        XCTAssertEqual(unconfirmed.focusedSeconds, 0, "A reschedule is a plan, not coverage evidence")
        XCTAssertEqual(unconfirmed.days.reduce(0) { $0 + $1.completed }, 0)
        let completed = summary([event(.started, 0), extended, event(.completed, 7200)], now: t.addingTimeInterval(8000))
        XCTAssertEqual(completed.sessions[0].outcome, .completed)
        XCTAssertEqual(completed.focusedSeconds, 7200)
        var shortened = event(.rescheduled, 600); shortened.plannedEnd = event(.completed, 1200).at
        XCTAssertEqual(summary([event(.started, 0), shortened, event(.completed, 3600)]).focusedSeconds, 1200)
        var afterTerminal = event(.rescheduled, 4000); afterTerminal.plannedEnd = event(.completed, 5000).at
        XCTAssertEqual(summary([event(.started, 0), event(.completed, 3600), afterTerminal]).focusedSeconds, 3600)
        let openEnded = event(.rescheduled, 1800)
        XCTAssertEqual(summary([event(.started, 0), openEnded], now: t.addingTimeInterval(4000), active: "a").sessions[0].outcome, .running)
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
    func testFH21GlobalPassAndCoverageGapsSubtractWithoutCountingAccess() {
        let events = [event(.started, 0), event(.completed, 3600),
                      event(.started, 0, id: "b"), event(.completed, 3600, id: "b"),
                      FlintHistoryEvent(type: .passUsed, session: "pass-operation", at: t.addingTimeInterval(600),
                        sessions: ["a", "b"], recoveryEnd: t.addingTimeInterval(1200)),
                      event(.protectionDegraded, 1800), event(.protectionRestored, 2400),
                      event(.accessStarted, 0, id: "access-operation"), event(.accessEnded, 3600, id: "access-operation")]
        let result = summary(events)
        XCTAssertEqual(result.sessions.count, 2)
        XCTAssertEqual(result.sessions.first { $0.id == "a" }?.focusedSeconds, 2400)
        XCTAssertEqual(result.sessions.first { $0.id == "b" }?.focusedSeconds, 3000)
        XCTAssertEqual(result.focusedSeconds, 3000) // b still provides focus during a's gap
        XCTAssertEqual(summary([event(.started, 0), event(.protectionDegraded, 600), event(.completed, 3600)]).focusedSeconds, 600)
    }
    func testClearKeepsPassEvidenceForRunningSession() {
        let s = store(); s.append(event(.started, 0))
        let pass = FlintHistoryEvent(type: .passUsed, session: "operation", at: t.addingTimeInterval(300),
                                    sessions: ["a"], recoveryEnd: t.addingTimeInterval(600))
        s.append(pass)
        XCTAssertTrue(store(now: t.addingTimeInterval(900)).clear(keepingRunningSession: "a"))
        XCTAssertTrue(s.read().events.contains(pass))
        XCTAssertEqual(summary(s.read().events, now: t.addingTimeInterval(900), active: "a").focusedSeconds, 600)
    }
    func testCompactionKeepsPassEvidenceConnectedToRunningSession() {
        let old = t.addingTimeInterval(-401 * 86400)
        let start = FlintHistoryEvent(type: .started, session: "running", at: old,
            tier: "easy", source: "manual", name: "Focus")
        let pass = FlintHistoryEvent(type: .passUsed, session: "operation", at: old.addingTimeInterval(60),
                                    sessions: ["running"], recoveryEnd: old.addingTimeInterval(120))
        let s = FlintHistoryStore(directory: directory, clock: { self.t }, calendar: utc, currentSessionID: { "running" })
        s.append(start); s.append(pass)
        XCTAssertTrue(s.compactIfNeeded())
        XCTAssertEqual(s.read().events, [start, pass])
        XCTAssertEqual(summary(s.read().events, now: old.addingTimeInterval(300), active: "running", start: old).focusedSeconds, 240)
    }
    func testCompletionSinkRejectsBeforeLogicalDeadline() {
        let s = store(), session = FlintActiveSession(id: "a", name: "Focus", startedAt: t,
            endsAt: t.addingTimeInterval(3600), breakLevel: .easy, monitorName: "unused")
        let sink = FlintHistorySink(store: s, isExtension: true, clock: { self.t })
        sink.handle(.ended(session, route: .completed, at: t.addingTimeInterval(3600)))
        XCTAssertTrue(s.read().events.isEmpty)
        let after = FlintHistorySink(store: s, isExtension: true, clock: { self.t.addingTimeInterval(3600) })
        after.handle(.ended(session, route: .completed, at: t.addingTimeInterval(3600)))
        XCTAssertEqual(s.read().events.first?.type, .completed)
    }
    func testRunningUsesNowAndIsLabelledInProgress() {
        let result = summary([event(.started, 0)], now: t.addingTimeInterval(300), active: "a")
        XCTAssertEqual(result.focusedSeconds, 300)
        XCTAssertTrue(result.sessions[0].isInProgress)
        XCTAssertTrue(result.days.last!.inProgress)
    }
    func testAppendAfterTornTailDoesNotLoseNextRecord() throws {
        let s = store(); s.append(event(.started, 0))
        _ = s.read()
        let handle = try FileHandle(forWritingTo: directory.appendingPathComponent("events.jsonl"))
        try handle.seekToEnd(); try handle.write(contentsOf: Data("{\"torn\":".utf8)); try handle.close()
        XCTAssertTrue(s.append(event(.completed, 3600)))
        XCTAssertEqual(s.read().events.count, 2)
        XCTAssertEqual(s.read().availability, .degraded)
    }
    func testMalformedMetadataIsQuarantinedAndRecovered() throws {
        let s = store(); s.append(event(.started, -100))
        _ = s.read()
        try Data("broken".utf8).write(to: directory.appendingPathComponent("metadata.json"))
        XCTAssertTrue(s.append(event(.completed, 3600))) // append before recovery must not move historyStart
        XCTAssertEqual(s.read().availability, .degraded)
        XCTAssertEqual(s.read().historyStart, t.addingTimeInterval(-100))
        XCTAssertEqual(s.read().events.count, 2)
    }
    func testMissingMetadataRecoversExistingEvents() throws {
        let s = store(); s.append(event(.started, -100))
        _ = s.read()
        try FileManager.default.removeItem(at: directory.appendingPathComponent("metadata.json"))
        XCTAssertTrue(s.append(event(.completed, 3600)))
        XCTAssertEqual(s.read().availability, .degraded)
        XCTAssertEqual(s.read().historyStart, t.addingTimeInterval(-100))
    }
    func testFirstHostReadIncludesEarlierExtensionInboxInHistoryStart() {
        let extensionStore = FlintHistoryStore(directory: directory, isHost: false)
        XCTAssertTrue(extensionStore.append(event(.started, -86400)))
        XCTAssertTrue(extensionStore.append(event(.completed, -82800)))
        let snapshot = store().read()
        XCTAssertEqual(snapshot.historyStart, t.addingTimeInterval(-86400))
        XCTAssertEqual(snapshot.availability, .value)
        XCTAssertEqual(summary(snapshot.events, start: snapshot.historyStart).days.dropLast().last?.focusedSeconds, 3600)
    }
    func testFH13FutureMinimalEnvelopeSurvivesCompaction() throws {
        let s = store(); _ = s.read()
        let raw = "{\"v\":9,\"session\":\"new\",\"at\":1800000000000,\"type\":\"started\"}\n{\"v\":9,\"unrecognized\":true}\n{\"v\":1,\"type\":\"new_kind\"}\n"
        try Data(raw.utf8).write(to: directory.appendingPathComponent("events.jsonl"))
        XCTAssertTrue(s.compactIfNeeded())
        XCTAssertEqual(try String(contentsOf: directory.appendingPathComponent("events.jsonl"), encoding: .utf8), raw)
        XCTAssertEqual(s.read().availability, .empty)
    }
    func testPendingClearPreservesExtensionTerminalBeforeHostRecovery() throws {
        let s = store(); s.append(event(.started, 0)); s.append(event(.started, 0, id: "b"))
        _ = s.read()
        let metadata = "{\"v\":1,\"createdAt\":1800000000000,\"clearedAt\":1800000001000,\"degraded\":false,\"pendingClear\":true,\"keepSession\":\"b\"}"
        try Data(metadata.utf8).write(to: directory.appendingPathComponent("metadata.json"))
        let extensionStore = FlintHistoryStore(directory: directory, isHost: false)
        let terminal = event(.completed, 3600, id: "b")
        XCTAssertTrue(extensionStore.append(terminal))
        let recovered = s.read()
        XCTAssertEqual(recovered.events.map(\.session), ["b", "b"])
        XCTAssertEqual(recovered.events.last, terminal)
        XCTAssertNil(recovered.events.last?.inferred)
    }
    func testPendingClearMetadataCommitFailureKeepsPostClearInboxAcrossRestart() throws {
        let s = store(); s.append(event(.started, 0, id: "b")); _ = s.read()
        let metadataURL = directory.appendingPathComponent("metadata.json")
        let metadata = "{\"v\":1,\"createdAt\":1800000000000,\"clearedAt\":1800000001000,\"pendingClear\":true,\"keepSession\":\"b\"}"
        try Data(metadata.utf8).write(to: metadataURL)
        let c = [event(.started, 2, id: "c"), event(.completed, 3600, id: "c")]
        let extensionStore = FlintHistoryStore(directory: directory, isHost: false)
        for record in c { XCTAssertTrue(extensionStore.append(record)) }
        for _ in 0..<2 {
            // New instances model restart; only metadata replacement fails, log writes succeed.
            let failing = FlintHistoryStore(directory: directory, clock: { self.t }, metadataWriteHook: {
                throw CocoaError(.fileWriteNoPermission)
            })
            let snapshot = failing.read()
            XCTAssertEqual(snapshot.availability, .degraded)
            XCTAssertEqual(snapshot.events.filter { $0.session == "c" }, c)
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.appendingPathComponent("inbox").path).count, 2)
            let durable = try JSONSerialization.jsonObject(with: Data(contentsOf: metadataURL)) as! [String: Any]
            XCTAssertEqual(durable["pendingClear"] as? Bool, true)
        }
        XCTAssertEqual(store().read().events.filter { $0.session == "c" }, c)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.appendingPathComponent("inbox").path).count, 0)
        XCTAssertEqual(store().read().events.filter { $0.session == "c" }, c)
    }
    func testOrphanTempsDoNotConsumeInboxCapacityAndHostReclaimsOnlyOldFiles() throws {
        let s = store(); _ = s.read()
        let inbox = directory.appendingPathComponent("inbox")
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        for i in 0..<FlintHistoryStore.inboxLimit {
            let file = inbox.appendingPathComponent(".tmp-orphan-\(i)")
            try Data("partial".utf8).write(to: file)
            try FileManager.default.setAttributes([.modificationDate: t.addingTimeInterval(-601)], ofItemAtPath: file.path)
        }
        let rootOrphan = directory.appendingPathComponent(".tmp-old-root")
        let recent = inbox.appendingPathComponent(".tmp-in-flight")
        try Data("partial".utf8).write(to: rootOrphan); try Data("partial".utf8).write(to: recent)
        try FileManager.default.setAttributes([.modificationDate: t.addingTimeInterval(-601)], ofItemAtPath: rootOrphan.path)
        try FileManager.default.setAttributes([.modificationDate: t.addingTimeInterval(-599)], ofItemAtPath: recent.path)
        let terminal = event(.completed, 3600)
        XCTAssertTrue(FlintHistoryStore(directory: directory, isHost: false).append(terminal))
        XCTAssertEqual(s.read().events, [terminal])
        XCTAssertFalse(FileManager.default.fileExists(atPath: rootOrphan.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path), [".tmp-in-flight"])
        XCTAssertTrue(s.append(event(.started, 0)))
    }
    func testUnreadableRegularMetadataIsQuarantinedAndClearRecovers() throws {
        let s = store(); s.append(event(.started, -100)); _ = s.read()
        let metadata = directory.appendingPathComponent("metadata.json")
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: metadata.path)
        XCTAssertThrowsError(try Data(contentsOf: metadata))
        let snapshot = s.read()
        XCTAssertEqual(snapshot.availability, .degraded)
        XCTAssertEqual(snapshot.historyStart, t.addingTimeInterval(-100))
        XCTAssertEqual(snapshot.events.count, 1)
        XCTAssertTrue(s.clear(keepingRunningSession: nil))
        XCTAssertEqual(s.read().availability, .empty)
    }
    func testHostIngressSurvivesActivityExpirationBeforeMaintenanceBegins() throws {
        let expired = expectation(description: "activity expired before work")
        let sink = FlintHistorySink(store: store(), isExtension: false, activity: { callback in
            callback(true); expired.fulfill()
        })
        let session = FlintActiveSession(id: "host", name: "Focus", startedAt: t, endsAt: nil,
                                         breakLevel: .easy, monitorName: "unused")
        sink.handle(.ended(session, route: .manualStop, at: t.addingTimeInterval(60)))
        let inbox = try FileManager.default.contentsOfDirectory(at: directory.appendingPathComponent("inbox"), includingPropertiesForKeys: nil)
        let exact = try JSONDecoder().decode(FlintHistoryEvent.self, from: Data(contentsOf: XCTUnwrap(inbox.first)))
        XCTAssertEqual(exact.type, .endedEarly); XCTAssertEqual(exact.route, "manual_stop")
        sink.perform { _ in XCTFail("expired maintenance must not begin") }
        wait(for: [expired], timeout: 10)
        XCTAssertEqual(store().read().events, [exact])
    }
    func testExtensionTerminalSurvivesSlowHostMaintenanceWithoutRetry() {
        let entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let completed = expectation(description: "maintenance completes")
        let s = FlintHistoryStore(directory: directory, clock: { self.t }, calendar: utc, maintenanceHook: {
            entered.signal(); _ = release.wait(timeout: .now() + 5)
        })
        s.append(event(.started, 0)); _ = s.read()
        DispatchQueue.global().async { _ = s.compactIfNeeded(); completed.fulfill() }
        XCTAssertEqual(entered.wait(timeout: .now() + 10), .success)
        let begin = ProcessInfo.processInfo.systemUptime
        let terminal = event(.completed, 3600)
        XCTAssertTrue(FlintHistoryStore(directory: directory, isHost: false).append(terminal))
        // Maintenance holds for up to 5 s; publication must not wait for it. The bound
        // leaves headroom for fsync on loaded CI runners.
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - begin, 2)
        release.signal(); wait(for: [completed], timeout: 10)
        XCTAssertTrue(s.read().events.contains(terminal))
    }
    func testExtensionSinkAppendsBeforeReturning() {
        let s = store(), sink = FlintHistorySink(store: store(), isExtension: true)
        let session = FlintActiveSession(id: "sync", name: "Focus", startedAt: t, endsAt: nil,
                                         breakLevel: .easy, monitorName: "unused")
        sink.handle(.started(session, source: .shortcut, at: t))
        XCTAssertEqual(s.read().events.first?.session, "sync")
    }
    func testQuarantineKeepsAtMostThreeCopies() throws {
        let s = store(); s.append(event(.started, 0))
        _ = s.read()
        for i in 0..<5 {
            let handle = try FileHandle(forWritingTo: directory.appendingPathComponent("events.jsonl"))
            try handle.seekToEnd(); try handle.write(contentsOf: Data("bad-\(i)\n".utf8)); try handle.close()
            _ = s.read()
        }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.appendingPathComponent("quarantine").path).count, 3)
    }
    func testNearCapMaintenanceCreatesHeadroomAndKeepsWholeSessions() throws {
        let s = FlintHistoryStore(directory: directory, clock: { self.t }, calendar: utc,
                                  currentSessionID: { "new" })
        _ = s.read()
        let url = directory.appendingPathComponent("events.jsonl")
        var data = Data()
        for index in 0..<300 {
            for type in [FlintHistoryEventType.started, .completed] {
                var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(event(type, Double(index), id: "s\(index)"))) as! [String: Any]
                object["futureExtra"] = String(repeating: "x", count: 8400)
                data.append(try JSONSerialization.data(withJSONObject: object)); data.append(10)
            }
        }
        XCTAssertGreaterThan(data.count, FlintHistoryStore.compactedLimit)
        XCTAssertLessThan(data.count, FlintHistoryStore.hardLimit)
        let prefix = "{\"v\":9,\"session\":\"extra\",\"at\":1799999999000,\"padding\":\""
        let suffix = "\"}\n"
        let padding = FlintHistoryStore.hardLimit - 100 - data.count - prefix.utf8.count - suffix.utf8.count
        data.append(Data((prefix + String(repeating: "x", count: padding) + suffix).utf8))
        try data.write(to: url)
        // The next host drain must create space even before daily maintenance is called.
        XCTAssertTrue(s.append(event(.started, 0, id: "new")))
        XCTAssertTrue(s.read().events.contains { $0.session == "new" })
        XCTAssertTrue(s.compactIfNeeded())
        XCTAssertLessThanOrEqual(try Data(contentsOf: url).count, FlintHistoryStore.compactedLimit)
        let terminal = event(.completed, 3600, id: "new")
        XCTAssertTrue(s.append(terminal))
        let snapshot = s.read()
        XCTAssertTrue(snapshot.events.contains(terminal))
        XCTAssertTrue(Dictionary(grouping: snapshot.events, by: \.session).values.allSatisfy { $0.count == 2 })
    }
    func testOversizedAndUnreadableMetadataRecoverWithoutBlockingClear() throws {
        let s = store(); s.append(event(.started, -300)); _ = s.read()
        let url = directory.appendingPathComponent("metadata.json")
        try Data(repeating: 0, count: 70_000).write(to: url)
        XCTAssertTrue(s.append(event(.completed, 3600)))
        XCTAssertEqual(s.read().availability, .degraded)
        XCTAssertEqual(s.read().historyStart, t.addingTimeInterval(-300))
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        XCTAssertEqual(s.read().availability, .degraded)
        XCTAssertTrue(s.clear(keepingRunningSession: nil))
        XCTAssertEqual(s.read().availability, .empty)
    }
    func testFailedQuarantineAndMetadataWriteStillReturnSalvagedEvents() throws {
        let s = store(); s.append(event(.started, 0)); s.append(event(.completed, 3600)); _ = s.read()
        let url = directory.appendingPathComponent("events.jsonl")
        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd(); try handle.write(contentsOf: Data("bad\n".utf8)); try handle.close()
        try Data("cannot create directory here".utf8).write(to: directory.appendingPathComponent("quarantine"))
        try Data("damaged metadata".utf8).write(to: directory.appendingPathComponent("metadata.json"))
        let before = try Data(contentsOf: url)
        let result = s.read()
        XCTAssertEqual(result.availability, .degraded)
        XCTAssertEqual(result.events.count, 2)
        XCTAssertFalse(s.compactIfNeeded())
        XCTAssertEqual(try Data(contentsOf: url), before)
    }
    func testInboxDrainDeduplicatesAfterInterruptedDeletion() throws {
        let s = store(), record = event(.started, 0)
        s.append(record); s.append(record)
        let inbox = directory.appendingPathComponent("inbox")
        let files = try FileManager.default.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil)
        let copy = try Data(contentsOf: files[0])
        XCTAssertEqual(s.read().events, [record])
        try copy.write(to: files[0]) // crash after durable append, before unlink
        XCTAssertEqual(s.read().events, [record])
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path).count, 0)
    }
    func testInboxCapRecordsDiagnosticAndNeverTouchesMetadataInExtension() throws {
        let s = FlintHistoryStore(directory: directory, isHost: false)
        for index in 0..<FlintHistoryStore.inboxLimit { XCTAssertTrue(s.append(event(.passUsed, Double(index)))) }
        XCTAssertFalse(s.append(event(.completed, 3600)))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("metadata.json").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("events.jsonl").path))
        XCTAssertEqual(store().read().availability, .degraded)
        XCTAssertTrue(s.append(event(.completed, 3600)))
    }
    func testFH11BreakPairingExplicitMissingOverlappingAndClipped() {
        var a = event(.breakStarted, 600); a.breakEnd = event(.breakEnded, 1200).at
        XCTAssertEqual(summary([event(.started, 0), a, event(.breakEnded, 900), event(.completed, 3600)]).focusedSeconds, 3300)
        XCTAssertEqual(summary([event(.started, 0), a, event(.breakEnded, 1500), event(.completed, 3600)]).focusedSeconds, 2700)
        var b = event(.breakStarted, 900); b.breakEnd = event(.breakEnded, 1800).at
        XCTAssertEqual(summary([event(.started, 0), a, b, event(.completed, 3600)]).focusedSeconds, 2400)
        var clipped = event(.breakStarted, -300); clipped.breakEnd = event(.breakEnded, 600).at
        XCTAssertEqual(summary([clipped, event(.started, 0), event(.breakStarted, 3000), event(.completed, 3600)]).focusedSeconds, 2400)
    }
    func testFH09HistoricalBucketsMoveWhenZoneChanges() {
        let start = utc.date(from: DateComponents(year: 2026, month: 10, day: 28, hour: 1))!
        let end = start.addingTimeInterval(3600), fixedNow = start.addingTimeInterval(48 * 3600)
        var west = utc; west.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        for (calendar, expectedDay) in [(utc, 28), (west, 27)] {
            let result = summary(absoluteSession(start: start, end: end), now: fixedNow, calendar: calendar, start: start.addingTimeInterval(-86400))
            let nonzero = result.days.filter { ($0.focusedSeconds ?? 0) > 0 }
            XCTAssertEqual(nonzero.count, 1)
            XCTAssertEqual(calendar.component(.day, from: nonzero[0].start), expectedDay)
        }
    }
    private func absoluteSession(start: Date, end: Date, id: String = "a") -> [FlintHistoryEvent] {
        [.init(type: .started, session: id, at: start, plannedEnd: end, tier: "easy", source: "manual", name: "Focus"),
         .init(type: .completed, session: id, at: end)]
    }
}
