import XCTest
import FlintCore

final class FlintProtectionLedgerPeekTests: XCTestCase {
    private var directory: URL!
    private let now = Date(timeIntervalSince1970: 1_793_206_800)

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-ledger-peek-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var file: URL { directory.appendingPathComponent(FlintProtectionLedger.fileName) }
    private var instant: FlintLedgerInstant { .init(wall: now, monotonic: 100, bootID: "boot") }

    private func ledger(initialized: Bool = true,
                        migrate: @escaping (inout FlintLedgerState, FlintLedgerInstant) -> Void = { _, _ in }) -> FlintProtectionLedger {
        let date = now
        return FlintProtectionLedger(directory: directory,
            clock: FlintClock(now: { date }, monotonic: { ProcessInfo.processInfo.systemUptime },
                              bootSessionID: { "boot" }, calendar: { .current }),
            lockTimeout: 5, initializedMarker: { initialized ? date : nil },
            setInitializedMarker: { _ in XCTFail("peek must not set the initialization marker") },
            migrate: migrate)
    }

    private func write(_ state: FlintLedgerState) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        try write(encoder.encode(state))
    }

    private func write(_ bytes: Data) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try bytes.write(to: file)
    }

    private func modificationDate() throws -> Date {
        try XCTUnwrap(FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate] as? Date)
    }

    func testPeekLeavesBytesModificationTimeAndDirectoryUnchanged() throws {
        var state = FlintLedgerState(initializedAt: now)
        state.pass.usage = .used
        state.pass.cooldownRemaining = 500
        state.pass.checkpoint = instant
        try write(state)
        let bytes = try Data(contentsOf: file)
        let modified = try modificationDate()
        let entries = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        let reader = ledger(migrate: { _, _ in XCTFail("peek must not migrate") })

        XCTAssertEqual(try reader.peek().get(), state)
        XCTAssertEqual(try reader.peek().get(), state)
        XCTAssertEqual(try Data(contentsOf: file), bytes)
        XCTAssertEqual(try modificationDate(), modified)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), entries,
                       "peek must not create a lock or checkpoint file")
    }

    func testMissingLedgerNeverCreatesStateOrDirectory() {
        XCTAssertEqual(ledger(initialized: false).peek(), .failure(.unavailable("ledger is not initialized")))
        XCTAssertEqual(ledger().peek(), .failure(.unavailable("ledger missing after initialization")))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }

    func testCorruptPeekIsUnavailableWithoutQuarantineOrMutation() throws {
        let bytes = Data("broken JSON".utf8)
        try write(bytes)
        let modified = try modificationDate()
        XCTAssertEqual(ledger().peek(), .failure(.unavailable("ledger is corrupt")))
        XCTAssertEqual(try Data(contentsOf: file), bytes)
        XCTAssertEqual(try modificationDate(), modified)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), [FlintProtectionLedger.fileName])
    }

    func testNewerSchemaPeekIsUnavailableWithoutQuarantine() throws {
        var state = FlintLedgerState(initializedAt: now)
        state.version = FlintLedgerState.currentVersion + 1
        try write(state)
        let bytes = try Data(contentsOf: file)
        XCTAssertEqual(ledger().peek(), .failure(.unavailable("ledger schema \(state.version) is newer than this build")))
        XCTAssertEqual(try Data(contentsOf: file), bytes)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), [FlintProtectionLedger.fileName])
    }

    func testV3FileWithStrippedAuthorityStaysUnavailableWithoutResetPC02PC66() throws {
        let root = directory!
        defer { directory = root }
        for stripMarker in [false, true] {
            directory = root.appendingPathComponent(stripMarker ? "schema-only" : "marked")
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .secondsSince1970
            var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(
                FlintLedgerState(initializedAt: now))) as? [String: Any])
            object.removeValue(forKey: "sessionAuthority")
            if stripMarker { object.removeValue(forKey: "sessionAuthorityVersion") }
            let bytes = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
            try write(bytes)
            let reader = ledger(migrate: { _, _ in XCTFail("corrupt v3 must not migrate or initialize") })
            XCTAssertEqual(reader.peek(), .failure(.unavailable("ledger is corrupt")))
            XCTAssertEqual(try Data(contentsOf: file), bytes)
            XCTAssertEqual(reader.read(), .failure(.unavailable("ledger is corrupt")))
            XCTAssertEqual(try Data(contentsOf: file), bytes, "transactional read must preserve the source file")
            let quarantine = directory.appendingPathComponent("quarantine")
            let retained = try FileManager.default.contentsOfDirectory(at: quarantine,
                includingPropertiesForKeys: nil)
            XCTAssertTrue(try retained.contains { try Data(contentsOf: $0) == bytes },
                "quarantine retains the exact corrupt document for recovery")
        }
    }

    func testUnimportedAndUnknownPassRemainUnavailableWhenProjected() throws {
        for usage in [FlintLedgerState.Pass.Usage.pendingImport, .unknown] {
            var state = FlintLedgerState(initializedAt: now)
            state.pass.usage = usage
            try write(state)
            let observed = try ledger().peek().get()
            let pass = FlintProtectionStatusSnapshot.Pass(observed, at: instant, hasActiveCommitment: true)
            XCTAssertEqual(pass.eligibility, .unavailable)
            XCTAssertNil(pass.nextAvailableAt)
            XCTAssertNil(pass.recoveryUntil)
        }
    }

    func testPeekInsideTransactionReadsPreviousCommitWithoutLockingOrNesting() throws {
        var state = FlintLedgerState(initializedAt: now)
        state.harder["count"] = .init(committedCount: 1)
        try write(state)
        let store = ledger()
        try store.transaction { changing in
            changing.harder["count"] = .init(committedCount: 2)
            XCTAssertEqual(try store.peek().get(), state, "uncommitted changes are invisible")
        }.get()
        XCTAssertEqual(try store.peek().get().harder["count"]?.committedCount, 2)
    }

    func testConcurrentTransactionalWritesNeverProduceTornPeek() throws {
        var state = FlintLedgerState(initializedAt: now)
        state.harder["first"] = .init()
        state.harder["second"] = .init()
        try write(state)
        let store = ledger()
        let observations = Observations()
        DispatchQueue.concurrentPerform(iterations: 4) { role in
            if role == 0 {
                for count in 1...40 {
                    if case .failure(let error) = store.transaction({ changing in
                        changing.harder["first"] = .init(committedCount: count)
                        changing.harder["second"] = .init(committedCount: count)
                    }) { observations.record("write failed: \(error)") }
                }
            } else {
                for _ in 0..<200 {
                    switch store.peek() {
                    case .success(let observed):
                        if observed.harder["first"]?.committedCount != observed.harder["second"]?.committedCount {
                            observations.record("torn committed document")
                        }
                    case .failure(let error): observations.record("peek failed: \(error)")
                    }
                }
            }
        }
        XCTAssertEqual(observations.failures, [])
        XCTAssertEqual(try store.peek().get().harder["first"]?.committedCount, 40)
    }

    private final class Observations: @unchecked Sendable {
        private let lock = NSLock()
        private var messages: [String] = []
        var failures: [String] {
            lock.lock()
            defer { lock.unlock() }
            return messages
        }
        func record(_ message: String) {
            lock.lock()
            defer { lock.unlock() }
            messages.append(message)
        }
    }
}
