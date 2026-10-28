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
    func testOversizeMainIsBoundedAndInboxRemainsReadable() throws {
        _ = store().read()
        try Data(repeating: 65, count: FlintHistoryStore.hardLimit + 1)
            .write(to: directory.appendingPathComponent("events.jsonl"))
        let start = event(); store(host: false).append(start)
        let before = try inventory()
        let snapshot = store(host: false).readOnlySnapshot()
        XCTAssertEqual(snapshot.events, [start]); XCTAssertEqual(snapshot.availability, .degraded)
        XCTAssertEqual(try inventory(), before)
    }
    func testMissingContainerIsUnavailableWithoutCreatingDirectories() {
        XCTAssertEqual(store(host: false).readOnlySnapshot().availability, .unavailable)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        XCTAssertEqual(FlintHistoryStore(directory: nil).readOnlySnapshot().availability, .unavailable)
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
