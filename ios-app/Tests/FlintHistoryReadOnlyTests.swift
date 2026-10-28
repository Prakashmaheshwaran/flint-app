import XCTest
import FlintCore

final class FlintHistoryReadOnlyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var directory: URL!
    override func setUp() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }
    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
    private func store(host: Bool = true) -> FlintHistoryStore {
        FlintHistoryStore(directory: directory, clock: { self.now }, isHost: host)
    }
    private func event(_ type: FlintHistoryEventType = .started, session: String = "a", seconds: Double = 0) -> FlintHistoryEvent {
        .init(type: type, session: session, at: now.addingTimeInterval(seconds),
              plannedEnd: type == .started ? now.addingTimeInterval(3600) : nil,
              tier: type == .started ? "easy" : nil, source: type == .started ? "manual" : nil,
              name: type == .started ? "Read" : nil)
    }
    private struct Entry: Equatable { let modified: Date?; let bytes: Data? }
    private func inventory() throws -> [String: Entry] {
        let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)!
        var result: [String: Entry] = [:]
        for case let url as URL in enumerator {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            result[String(url.path.dropFirst(directory.path.count))] = Entry(
                modified: attributes[.modificationDate] as? Date,
                bytes: attributes[.type] as? FileAttributeType == .typeRegular ? try Data(contentsOf: url) : nil)
        }
        let root = try FileManager.default.attributesOfItem(atPath: directory.path)
        result["/"] = Entry(modified: root[.modificationDate] as? Date, bytes: nil)
        return result
    }
    func testExtensionReaderSeesUndrainedIngressWithoutAnyFilesystemChanges() throws {
        _ = store().read()
        let start = event(), end = event(.completed, seconds: 3600)
        let writer = store(host: false)
        XCTAssertTrue(writer.append(start)); XCTAssertTrue(writer.append(end))
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.events, [start, end])
        XCTAssertEqual(snapshot.availability, .value)
        XCTAssertEqual(snapshot.historyStart, now)
        XCTAssertEqual(try inventory(), before)
    }
    func testDuplicateMainAndInboxEventIsReturnedOnce() throws {
        let start = event()
        XCTAssertTrue(store().append(start)); _ = store().read()
        XCTAssertTrue(store(host: false).append(start))
        let before = try inventory()
        XCTAssertEqual(store(host: false).readOnlySnapshot().events, [start])
        XCTAssertEqual(try inventory(), before)
    }
    func testPendingClearFiltersOldGenerationButKeepsRunningAndNewInboxEvents() throws {
        let kept = event(session: "running", seconds: -120)
        let old = event(session: "old", seconds: -60)
        store().append(kept); store().append(old); _ = store().read()
        let metadata: [String: Any] = ["v": 1, "createdAt": FlintHistoryEvent.milliseconds(now.addingTimeInterval(-120)),
            "clearedAt": FlintHistoryEvent.milliseconds(now), "pendingClear": true, "keepSession": "running"]
        try JSONSerialization.data(withJSONObject: metadata).write(to: directory.appendingPathComponent("metadata.json"))
        let fresh = event(session: "new", seconds: 5)
        store(host: false).append(old); store(host: false).append(fresh)
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.events, [kept, fresh])
        XCTAssertEqual(snapshot.historyStart, now)
        XCTAssertEqual(snapshot.availability, .degraded)
        XCTAssertEqual(try inventory(), before)
    }
    func testMissingMetadataNeverInventsHistoryStartOrRepairsFiles() throws {
        let start = event()
        XCTAssertTrue(store(host: false).append(start))
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.events, [start]); XCTAssertNil(snapshot.historyStart)
        XCTAssertEqual(snapshot.availability, .degraded)
        XCTAssertEqual(try inventory(), before)
    }
    func testCorruptionAndOldStagingAreSkippedWithoutQuarantineOrCleanup() throws {
        let start = event()
        store().append(start); _ = store().read()
        let handle = try FileHandle(forWritingTo: directory.appendingPathComponent("events.jsonl"))
        try handle.seekToEnd(); try handle.write(contentsOf: Data("bad\ntruncated".utf8)); try handle.close()
        try Data("broken".utf8).write(to: directory.appendingPathComponent("metadata.json"))
        let temp = directory.appendingPathComponent("inbox/.tmp-abandoned")
        try Data("partial".utf8).write(to: temp)
        try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(-3600)], ofItemAtPath: temp.path)
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.events, [start]); XCTAssertNil(snapshot.historyStart)
        XCTAssertEqual(snapshot.availability, .degraded)
        XCTAssertEqual(try inventory(), before)
    }
    func testOversizeMainReturnsUnavailableWithoutPartialInboxResults() throws {
        _ = store().read()
        try Data(repeating: 65, count: FlintHistoryStore.hardLimit + 1)
            .write(to: directory.appendingPathComponent("events.jsonl"))
        let start = event(); store(host: false).append(start)
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertTrue(snapshot.events.isEmpty); XCTAssertEqual(snapshot.availability, .unavailable)
        XCTAssertEqual(try inventory(), before)
    }
    func testMissingContainerIsUnavailableWithoutCreatingDirectories() {
        XCTAssertEqual(store(host: false).readOnlySnapshot().availability, .unavailable)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        XCTAssertEqual(FlintHistoryStore(directory: nil).readOnlySnapshot().availability, .unavailable)
    }

    // Stress fixtures exercise the published operation/allocation bounds, not a
    // WidgetKit RSS or wall-clock SLA. The reader retains no future-envelope rows.
    func testDenseFutureMainAtFiveMiBIgnores655360Envelopes() throws {
        _ = store().read()
        let row = "{\"v\":2}\n"
        try Data(String(repeating: row, count: FlintHistoryStore.hardLimit / row.utf8.count).utf8)
            .write(to: directory.appendingPathComponent("events.jsonl"))
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.availability, .empty); XCTAssertTrue(snapshot.events.isEmpty)
        XCTAssertEqual(snapshot.historyStart, now)
    }

    func testDenseUnknownMainIgnoresEnvelopesAndKeepsSupportedIngress() throws {
        _ = store().read()
        let row = "{\"v\":1,\"type\":\"future_kind\"}\n"
        try Data(String(repeating: row, count: FlintHistoryStore.hardLimit / row.utf8.count).utf8)
            .write(to: directory.appendingPathComponent("events.jsonl"))
        let start = event(); XCTAssertTrue(store(host: false).append(start))
        XCTAssertEqual(store(host: false).readOnlySnapshot().events, [start])
    }

    func testSupportedRecordBudgetAcceptsTwentyThousandAndRejectsDenseMain() throws {
        _ = store().read()
        let row = "{\"v\":1,\"type\":\"completed\",\"session\":\"a\",\"at\":1800000000000,\"tz\":\"UTC\",\"id\":\"same\"}\n"
        let log = directory.appendingPathComponent("events.jsonl")
        try Data(String(repeating: row, count: FlintHistoryStore.readOnlyRecordLimit).utf8).write(to: log)
        let atLimit = store(host: false).readOnlySnapshot()
        XCTAssertEqual(atLimit.availability, .empty); XCTAssertEqual(atLimit.events.count, 1)
        try Data(String(repeating: row, count: FlintHistoryStore.hardLimit / row.utf8.count).utf8).write(to: log)
        let overLimit = store(host: false).readOnlySnapshot()
        XCTAssertEqual(overLimit.availability, .unavailable); XCTAssertTrue(overLimit.events.isEmpty)
    }

    func testNearCapInboxExhaustsDecodedByteBudgetWithoutPartialHistory() throws {
        _ = store().read()
        let inbox = directory.appendingPathComponent("inbox")
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        var large = event(.completed)
        large.reason = String(repeating: "x", count: 16_000)
        var data = try JSONEncoder().encode(large); data.append(10)
        XCTAssertLessThanOrEqual(data.count, FlintHistoryStore.recordLimit)
        XCTAssertGreaterThan(data.count, 16_000)
        for index in 0..<FlintHistoryStore.inboxLimit {
            try data.write(to: inbox.appendingPathComponent("0-\(index).json"))
        }
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.availability, .unavailable); XCTAssertTrue(snapshot.events.isEmpty)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path).count, FlintHistoryStore.inboxLimit)
    }

    func testEveryReadBoundExhaustionIsUnavailable() throws {
        _ = store().read()
        let log = directory.appendingPathComponent("events.jsonl")
        try Data(repeating: 65, count: FlintHistoryStore.recordLimit).write(to: log)
        XCTAssertEqual(store(host: false).readOnlySnapshot().availability, .unavailable)
        try Data().write(to: log)
        let metadata = directory.appendingPathComponent("metadata.json")
        let original = try Data(contentsOf: metadata)
        try Data(repeating: 32, count: 65_537).write(to: metadata)
        XCTAssertEqual(store(host: false).readOnlySnapshot().availability, .unavailable)
        try original.write(to: metadata)
        let inbox = directory.appendingPathComponent("inbox")
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        for index in 0...FlintHistoryStore.inboxLimit {
            try Data("{\"v\":2}\n".utf8).write(to: inbox.appendingPathComponent("0-\(index).json"))
        }
        XCTAssertEqual(store(host: false).readOnlySnapshot().availability, .unavailable)
    }

    private func unreadableMainFixture() throws -> URL {
        _ = store().read()
        // An invalid file kind deterministically makes Data(contentsOf:) fail on
        // both macOS and Simulator, without depending on the runner's Unix UID.
        let log = directory.appendingPathComponent("events.jsonl")
        try FileManager.default.createDirectory(at: log, withIntermediateDirectories: true)
        try Data("preserve me".utf8).write(to: log.appendingPathComponent("evidence"))
        return log
    }

    func testQuarantinePublishesLossIntentBeforeRemovingUnreadableMain() throws {
        let log = try unreadableMainFixture()
        let moved = expectation(description: "quarantine moved")
        let finished = expectation(description: "host recovery returned")
        let resume = DispatchSemaphore(value: 0)
        let host = FlintHistoryStore(directory: directory, clock: { self.now }, quarantineMoveHook: {
            moved.fulfill(); _ = resume.wait(timeout: .now() + 5)
        })
        DispatchQueue.global().async {
            XCTAssertEqual(host.read().availability, .unavailable); finished.fulfill()
        }
        wait(for: [moved], timeout: 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: log.path))
        let during = store(host: false).readOnlySnapshot()
        XCTAssertEqual(during.availability, .unavailable)
        XCTAssertTrue(FlintHistoryAggregator.summarize(during, activeSessionID: nil, now: now)
            .days.allSatisfy { $0.focusedSeconds == nil })
        resume.signal(); wait(for: [finished], timeout: 2)
        XCTAssertEqual(store(host: false).readOnlySnapshot().availability, .unavailable)
    }

    func testFailedLossIntentPreservesUnreadableMain() throws {
        let log = try unreadableMainFixture()
        let host = FlintHistoryStore(directory: directory, clock: { self.now }, metadataWriteHook: {
            throw CocoaError(.fileWriteNoPermission)
        })
        XCTAssertEqual(host.read().availability, .unavailable)
        XCTAssertEqual(try Data(contentsOf: log.appendingPathComponent("evidence")), Data("preserve me".utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("quarantine").path))
        XCTAssertEqual(store(host: false).readOnlySnapshot().availability, .unavailable)
    }

    func testMissingMetadataAndUnreadableMainRecoverBoundaryFromOlderInbox() throws {
        try assertOlderInboxRecovery(corruptMetadata: false)
    }

    func testCorruptMetadataAndUnreadableMainRecoverBoundaryFromOlderInbox() throws {
        try assertOlderInboxRecovery(corruptMetadata: true)
    }

    private func assertOlderInboxRecovery(corruptMetadata: Bool) throws {
        _ = try unreadableMainFixture()
        let metadata = directory.appendingPathComponent("metadata.json")
        if corruptMetadata { try Data("broken".utf8).write(to: metadata) }
        else { try FileManager.default.removeItem(at: metadata) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let yesterday = calendar.startOfDay(for: now).addingTimeInterval(-86_400)
        let startAt = yesterday.addingTimeInterval(12 * 3600)
        let endAt = startAt.addingTimeInterval(1200)
        let start = FlintHistoryEvent(type: .started, session: "yesterday", at: startAt,
            plannedEnd: endAt, tier: "easy", source: "manual", name: "Read")
        let end = FlintHistoryEvent(type: .completed, session: "yesterday", at: endAt)
        let writer = store(host: false)
        XCTAssertTrue(writer.append(start)); XCTAssertTrue(writer.append(end))
        // A fresh host instance on each pass also verifies persisted reconstruction.
        for _ in 0..<2 {
            let host = FlintHistoryStore(directory: directory, clock: { self.now }, calendar: calendar)
            for snapshot in [host.read(), store(host: false).readOnlySnapshot()] {
                XCTAssertEqual(snapshot.historyStart, startAt)
                XCTAssertEqual(snapshot.events, [start, end])
                XCTAssertEqual(snapshot.availability, .degraded)
                let summary = FlintHistoryAggregator.summarize(snapshot, activeSessionID: nil, now: now, calendar: calendar)
                XCTAssertEqual(summary.days.first(where: { $0.start == yesterday })?.focusedSeconds, 1200)
                XCTAssertEqual(summary.sessions.first?.focusedSeconds, 1200)
            }
        }
    }

    func testCommittedCompactionNotifiesOutsideLockEvenWhenMetadataSaveFails() {
        let expired = event(session: "expired", seconds: -401 * 86_400), recent = event(session: "recent")
        let setup = store(); setup.append(expired); setup.append(recent); _ = setup.read()
        var failMetadata = false
        let host = FlintHistoryStore(directory: directory, clock: { self.now },
            maintenanceHook: { failMetadata = true }, metadataWriteHook: {
                if failMetadata { throw CocoaError(.fileWriteNoPermission) }
            })
        let delivered = expectation(description: "committed replacement signalled outside mutex")
        delivered.assertForOverFulfill = true
        let finished = expectation(description: "maintenance returned")
        var reasons: [String] = []
        let token = NotificationCenter.default.addObserver(forName: FlintHistoryStore.didChangeNotification,
                                                            object: nil, queue: nil) { note in
            guard let changed = note.object as? FlintHistoryStore, changed === host else { return }
            reasons.append(note.userInfo?["reason"] as? String ?? "missing")
            XCTAssertEqual(host.read().events, [recent])
            delivered.fulfill()
        }
        defer { NotificationCenter.default.removeObserver(token) }
        DispatchQueue.global().async {
            XCTAssertFalse(host.compactIfNeeded()); finished.fulfill()
        }
        wait(for: [delivered, finished], timeout: 2)
        XCTAssertEqual(reasons, ["compact"])
        XCTAssertEqual(store(host: false).readOnlySnapshot().events, [recent])
    }
    func testNothingSalvageableIsUnavailableRatherThanAnObservedZero() throws {
        _ = store().read()
        try Data("corrupt\ntruncated".utf8).write(to: directory.appendingPathComponent("events.jsonl"))
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.availability, .unavailable)
        let summary = FlintHistoryAggregator.summarize(snapshot, activeSessionID: nil, now: now)
        XCTAssertTrue(summary.days.allSatisfy { $0.focusedSeconds == nil })
        XCTAssertEqual(try inventory(), before)
    }
    func testMetadataOnlyDamageIsDegradedWithoutInventingObservedDays() throws {
        _ = store().read()
        try Data("corrupt".utf8).write(to: directory.appendingPathComponent("metadata.json"))
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.availability, .degraded); XCTAssertNil(snapshot.historyStart)
        let summary = FlintHistoryAggregator.summarize(snapshot, activeSessionID: nil, now: now)
        XCTAssertTrue(summary.days.allSatisfy { $0.focusedSeconds == nil })
        XCTAssertEqual(try inventory(), before)
    }
    func testObservedEmptyStoreRemainsEmptyAndNonmutating() throws {
        _ = store().read()
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.availability, .empty); XCTAssertEqual(snapshot.historyStart, now)
        XCTAssertTrue(snapshot.events.isEmpty); XCTAssertEqual(try inventory(), before)
    }
    func testSummaryFixturesHavePublicInitializersAndPreserveAbsentVersusZero() {
        let session = FlintHistorySessionSummary(id: "a", name: "Read", startedAt: now,
            endedAt: now.addingTimeInterval(60), outcome: .completed, inferred: false, route: nil,
            intervals: [.init(start: now, duration: 60)])
        let absent = FlintHistoryDay(start: now, focusedSeconds: nil)
        let zero = FlintHistoryDay(start: now, focusedSeconds: 0, completed: 0, endedEarly: 1, inProgress: true)
        let summary = FlintHistorySummary(availability: .value, sessions: [session], days: [absent, zero], streak: 1)
        XCTAssertNil(summary.days[0].focusedSeconds); XCTAssertEqual(summary.days[1].focusedSeconds, 0)
        XCTAssertEqual(summary.sessions[0].focusedSeconds, 60); XCTAssertTrue(summary.days[1].inProgress)
    }

    func testClearPostsOnceAfterUnlockWithClearReason() {
        let host = store()
        host.append(event()); _ = host.read()
        let delivered = expectation(description: "clear notification outside mutex")
        delivered.assertForOverFulfill = true
        var reasons: [String] = []
        let token = NotificationCenter.default.addObserver(forName: FlintHistoryStore.didChangeNotification,
                                                            object: nil, queue: nil) { note in
            guard let changed = note.object as? FlintHistoryStore, changed === host else { return }
            reasons.append(note.userInfo?["reason"] as? String ?? "missing")
            // Re-entry would deadlock if the notification were posted under the mutex.
            XCTAssertEqual(host.read().availability, .empty)
            delivered.fulfill()
        }
        defer { NotificationCenter.default.removeObserver(token) }
        DispatchQueue.global().async { XCTAssertTrue(host.clear(keepingRunningSession: nil)) }
        wait(for: [delivered], timeout: 2)
        XCTAssertEqual(reasons, ["clear"])
    }

    func testNoOpReadAndReadOnlySnapshotPostNothing() {
        let host = store(); _ = host.read()
        var reasons: [String] = []
        let token = NotificationCenter.default.addObserver(forName: FlintHistoryStore.didChangeNotification,
                                                            object: nil, queue: nil) { note in
            guard let changed = note.object as? FlintHistoryStore, changed === host else { return }
            reasons.append(note.userInfo?["reason"] as? String ?? "missing")
        }
        defer { NotificationCenter.default.removeObserver(token) }
        _ = host.read(); _ = host.readOnlySnapshot(); _ = host.read()
        XCTAssertTrue(reasons.isEmpty)
    }

    func testExtensionDoesNotPostAndHostDrainPostsOnlyForNewRecords() {
        let host = store(), extensionStore = store(host: false)
        _ = host.read()
        var reasons: [String] = []
        let token = NotificationCenter.default.addObserver(forName: FlintHistoryStore.didChangeNotification,
                                                            object: nil, queue: nil) { note in
            guard let changed = note.object as? FlintHistoryStore,
                  changed === host || changed === extensionStore else { return }
            reasons.append(note.userInfo?["reason"] as? String ?? "missing")
        }
        defer { NotificationCenter.default.removeObserver(token) }
        let start = event()
        extensionStore.append(start); _ = extensionStore.readOnlySnapshot()
        XCTAssertFalse(extensionStore.clear(keepingRunningSession: nil))
        XCTAssertFalse(extensionStore.compactIfNeeded())
        XCTAssertTrue(reasons.isEmpty)
        _ = host.read(); _ = host.read()
        extensionStore.append(start); _ = host.read() // duplicate ingress is not a new record
        XCTAssertEqual(reasons, ["drain"])
    }

    func testCompactionPostsOnlyWhenCommittedRatherThanDailyNoOp() {
        let host = store(); host.append(event()); _ = host.read()
        var reasons: [String] = []
        let token = NotificationCenter.default.addObserver(forName: FlintHistoryStore.didChangeNotification,
                                                            object: nil, queue: nil) { note in
            guard let changed = note.object as? FlintHistoryStore, changed === host else { return }
            reasons.append(note.userInfo?["reason"] as? String ?? "missing")
        }
        defer { NotificationCenter.default.removeObserver(token) }
        XCTAssertTrue(host.compactIfNeeded()); XCTAssertTrue(host.compactIfNeeded())
        XCTAssertEqual(reasons, ["compact"])
    }

    func testReconcilerNotifiesOnlyAfterNewInferredRecordsArePublished() {
        let host = store(); host.append(event()); _ = host.read()
        var inferredSnapshots: [[FlintHistoryEvent]] = []
        let token = NotificationCenter.default.addObserver(forName: FlintHistoryStore.didChangeNotification,
                                                            object: nil, queue: nil) { note in
            guard let changed = note.object as? FlintHistoryStore, changed === host,
                  note.userInfo?["reason"] as? String == "reconcile" else { return }
            inferredSnapshots.append(host.readOnlySnapshot().events)
        }
        defer { NotificationCenter.default.removeObserver(token) }
        _ = FlintHistoryReconciler.reconcile(store: host, activeSession: nil, now: now.addingTimeInterval(4000))
        _ = FlintHistoryReconciler.reconcile(store: host, activeSession: nil, now: now.addingTimeInterval(5000))
        XCTAssertEqual(inferredSnapshots.count, 1)
        XCTAssertEqual(inferredSnapshots.first?.last?.type, .completed)
        XCTAssertEqual(inferredSnapshots.first?.last?.inferred, true)
    }
}
