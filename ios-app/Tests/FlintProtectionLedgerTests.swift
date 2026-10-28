import XCTest
import FlintCore

/// The cross-process protection ledger (ADR-012 §8) and its pass / Harder / migration rules
/// (§4, §6, §9, §12). Case IDs refer to ADR-012 §14.
final class FlintProtectionLedgerTests: XCTestCase {

    private var directory: URL!
    private var marker: Date?
    private var now = Date(timeIntervalSince1970: 1_793_206_800)
    private var monotonic: TimeInterval = 10_000
    private var boot: String? = "boot-1"

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-ledger-\(UUID().uuidString)", isDirectory: true)
        marker = nil
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var clock: FlintClock {
        FlintClock(now: { [unowned self] in self.now }, monotonic: { [unowned self] in self.monotonic },
                   bootSessionID: { [unowned self] in self.boot }, calendar: { Calendar(identifier: .gregorian) })
    }

    private func ledger() -> FlintProtectionLedger {
        FlintProtectionLedger(directory: directory, clock: clock, lockTimeout: 5,
                              initializedMarker: { [unowned self] in self.marker },
                              setInitializedMarker: { [unowned self] in self.marker = $0 })
    }

    private var instant: FlintLedgerInstant { FlintLedgerInstant(clock: clock) }

    private func advance(_ seconds: TimeInterval) {
        now = now.addingTimeInterval(seconds)
        monotonic += seconds
    }

    private func state() throws -> FlintLedgerState { try ledger().read().get() }

    // MARK: Transaction boundary (§8)

    func testFirstReadInitializesAndSetsTheDurableMarker() throws {
        let initial = try state()
        XCTAssertEqual(initial.version, FlintLedgerState.currentVersion)
        XCTAssertNotNil(marker)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("ledger.json").path))
    }

    func testMutationsPersistAcrossInstances() throws {
        _ = ledger().transaction { $0.migrations.insert("x") }
        XCTAssertTrue(try state().migrations.contains("x"))
    }

    func testAThrowingTransactionWritesNothing() throws {
        _ = ledger().transaction { $0.migrations.insert("kept") }
        struct Abort: Error {}
        let result = ledger().transaction { state -> Void in
            state.migrations.insert("discarded")
            throw Abort()
        }
        guard case .failure(.unavailable) = result else { return XCTFail("expected unavailable, got \(result)") }
        XCTAssertEqual(try state().migrations, ["kept"])
    }

    func testConcurrentIncrementsFromSeparateHandlesAreSerialized() throws {
        _ = try state()
        // Each ledger instance opens its own lock file descriptor: flock excludes them exactly
        // as it does the host app and an extension.
        DispatchQueue.concurrentPerform(iterations: 40) { _ in
            let ledger = FlintProtectionLedger(directory: directory, clock: clock, lockTimeout: 10,
                                               initializedMarker: { Date() }, setInitializedMarker: { _ in })
            _ = ledger.transaction { $0.harder["c", default: .init()].committedCount += 1 }
        }
        XCTAssertEqual(try state().harder["c"]?.committedCount, 40, "no lost update")
    }

    func testNestedTransactionsFailInsteadOfDeadlocking() {
        let ledger = ledger()
        let outer = ledger.transaction { _ -> Result<Void, FlintLedgerError> in
            ledger.transaction { _ in }
        }
        guard case let .success(inner) = outer, case .failure(.unavailable) = inner else {
            return XCTFail("nested transaction must fail, got \(outer)")
        }
    }

    func testCorruptFileIsUnavailableQuarantinedAndNeverReplacedPC37() throws {
        _ = try state()
        let file = directory.appendingPathComponent("ledger.json")
        try Data("{ not json".utf8).write(to: file)
        guard case .failure(.unavailable) = ledger().read() else { return XCTFail("corrupt must be unavailable") }
        guard case .failure(.unavailable) = ledger().transaction({ $0.migrations.insert("x") }) else {
            return XCTFail("no write over corrupt state")
        }
        XCTAssertEqual(try Data(contentsOf: file), Data("{ not json".utf8), "original bytes untouched")
        let quarantined = try FileManager.default.contentsOfDirectory(
            atPath: directory.appendingPathComponent("quarantine").path)
        XCTAssertFalse(quarantined.isEmpty)
    }

    func testMissingAfterInitializationIsUnavailableNotAFreshInstall() throws {
        _ = try state()
        try FileManager.default.removeItem(at: directory.appendingPathComponent("ledger.json"))
        guard case .failure(.unavailable) = ledger().read() else {
            return XCTFail("a deleted ledger must not read as a new install")
        }
    }

    func testNewerSchemaIsUnavailable() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(#"{"version": 99}"#.utf8).write(to: directory.appendingPathComponent("ledger.json"))
        guard case let .failure(.unavailable(reason)) = ledger().read() else { return XCTFail("expected unavailable") }
        XCTAssertTrue(reason.contains("newer"))
    }

    // MARK: Emergency Pass (§6, §9)

    func testPassConsumptionIsIdempotentByOperationIDPC45() throws {
        var s = try state()
        let first = s.consumePass(operationID: "op-1", hasActiveCommitment: true, at: instant)
        XCTAssertEqual(try first.get().replayed, false)
        let retry = s.consumePass(operationID: "op-1", hasActiveCommitment: true, at: instant)
        XCTAssertEqual(try retry.get().replayed, true, "same operation returns the committed result")
        let second = s.consumePass(operationID: "op-2", hasActiveCommitment: true, at: instant)
        XCTAssertEqual(second, .failure(.coolingDown(remaining: FlintProtectionConstants.passPeriod)))
    }

    func testPassRenewsAfterRollingPeriodOnElapsedTimePC39() throws {
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        advance(604_799)
        XCTAssertEqual(s.consumePass(operationID: "b", hasActiveCommitment: true, at: instant),
                       .failure(.coolingDown(remaining: 1)))
        advance(1)
        XCTAssertEqual(try s.consumePass(operationID: "c", hasActiveCommitment: true, at: instant).get().replayed, false)
    }

    func testForwardWallJumpDoesNotShortenCooldownPC54() throws {
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        now = now.addingTimeInterval(30 * 86_400) // wall moved a month ahead, no real time passed
        XCTAssertEqual(s.consumePass(operationID: "b", hasActiveCommitment: true, at: instant),
                       .failure(.coolingDown(remaining: FlintProtectionConstants.passPeriod)))
    }

    func testRebootCreditsNoPoweredOffTimeAndEndsRecoveryPC59PC60() throws {
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        advance(1000)
        s.pass.checkpoint(at: instant)
        XCTAssertEqual(s.pass.cooldownRemaining, FlintProtectionConstants.passPeriod - 1000)
        XCTAssertTrue(s.pass.isRecovering)
        // Reboot: new boot id, monotonic restarts, wall says a week passed.
        boot = "boot-2"; monotonic = 50; now = now.addingTimeInterval(7 * 86_400)
        s.pass.checkpoint(at: instant)
        XCTAssertEqual(s.pass.cooldownRemaining, FlintProtectionConstants.passPeriod - 1000, "no unverified credit")
        XCTAssertFalse(s.pass.isRecovering, "recovery access ends after a restart")
        advance(100)
        s.pass.checkpoint(at: instant)
        XCTAssertEqual(s.pass.cooldownRemaining, FlintProtectionConstants.passPeriod - 1100)
    }

    func testRecoveryLastsSixtyMinutesAndResumeTruncatesWithoutRefund() throws {
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        advance(1800)
        s.pass.checkpoint(at: instant)
        XCTAssertEqual(s.pass.recoveryRemaining, 1800)
        s.endRecovery(at: instant)
        XCTAssertFalse(s.pass.isRecovering)
        XCTAssertEqual(s.consumePass(operationID: "b", hasActiveCommitment: true, at: instant),
                       .failure(.coolingDown(remaining: FlintProtectionConstants.passPeriod - 1800)), "no refund")
    }

    func testNoSpendWithoutAnActiveCommitment() throws {
        var s = try state()
        XCTAssertEqual(s.consumePass(operationID: "a", hasActiveCommitment: false, at: instant), .failure(.nothingToRecover))
        XCTAssertEqual(s.pass.usage, .neverUsed)
    }

    // MARK: Harder waits (§4, §9)

    func testHarderWaitLifecyclePC18PC20() throws {
        var s = try state()
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "stop", scope: ["s"], operationID: "w1", at: instant),
                       .waiting(remaining: 60))
        advance(30)
        s.cancelHarderWait(cause: "c")
        XCTAssertEqual(s.harderWaitStatus(cause: "c", at: instant), .none)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "stop", scope: ["s"], operationID: "w2", at: instant),
                       .waiting(remaining: 60), "PC-20: cancel restarts the full wait and spends nothing")
        advance(60)
        XCTAssertTrue(s.commitHarderWait(cause: "c", at: instant))
        XCTAssertEqual(s.harder["c"]?.committedCount, 1)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "break", scope: ["s"], operationID: "w3", at: instant),
                       .waiting(remaining: 300), "PC-18: second exception waits 300 s")
        advance(300); _ = s.commitHarderWait(cause: "c", at: instant)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "break", scope: ["s"], operationID: "w4", at: instant),
                       .waiting(remaining: 900))
        advance(900); _ = s.commitHarderWait(cause: "c", at: instant)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "break", scope: ["s"], operationID: "w5", at: instant),
                       .waiting(remaining: 900), "capped at 900")
    }

    func testCommitBeforeMaturityAndDuplicateCommitsDoNothing() throws {
        var s = try state()
        s.requestHarderWait(cause: "c", kind: "stop", scope: [], operationID: "w1", at: instant)
        advance(59)
        XCTAssertFalse(s.commitHarderWait(cause: "c", at: instant))
        advance(1)
        XCTAssertTrue(s.commitHarderWait(cause: "c", at: instant))
        XCTAssertFalse(s.commitHarderWait(cause: "c", at: instant), "nothing pending any more")
        XCTAssertEqual(s.harder["c"]?.committedCount, 1)
    }

    func testDifferentOperationNeedsANewWait() throws {
        var s = try state()
        s.requestHarderWait(cause: "c", kind: "stop", scope: ["a"], operationID: "w1", at: instant)
        advance(50)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "stop", scope: ["a", "b"], operationID: "w2", at: instant),
                       .waiting(remaining: 60), "changed scope restarts")
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "stop", scope: ["a", "b"], operationID: "w3", at: instant),
                       .waiting(remaining: 60), "same request resumes, not restarts")
    }

    func testRebootCancelsAPendingWaitWithoutCountingIt() throws {
        var s = try state()
        s.requestHarderWait(cause: "c", kind: "stop", scope: [], operationID: "w1", at: instant)
        boot = "boot-2"; monotonic = 10; now = now.addingTimeInterval(3600)
        XCTAssertEqual(s.harderWaitStatus(cause: "c", at: instant), .none)
        XCTAssertFalse(s.commitHarderWait(cause: "c", at: instant))
        XCTAssertEqual(s.harder["c"]?.committedCount ?? 0, 0)
    }

    func testPendingWaitSurvivesProcessDeathPC21() throws {
        _ = ledger().transaction { $0.requestHarderWait(cause: "c", kind: "stop", scope: [], operationID: "w", at: self.instant) }
        advance(45)
        let status = ledger().transaction { $0.harderWaitStatus(cause: "c", at: self.instant) }
        XCTAssertEqual(try status.get(), .waiting(remaining: 15))
    }

    // MARK: Legacy pass import (§12)

    func testLegacyPassImportPC69() throws {
        var s = try state()
        s.importLegacyPass(lastUsed: now.addingTimeInterval(-86_400).timeIntervalSince1970, at: instant)
        XCTAssertEqual(s.pass.usage, .used)
        XCTAssertEqual(s.pass.cooldownRemaining, FlintProtectionConstants.passPeriod - 86_400, "cooldown preserved")
        s.importLegacyPass(lastUsed: nil, at: instant)
        XCTAssertEqual(s.pass.usage, .used, "runs once")

        var fresh = FlintLedgerState(initializedAt: now)
        fresh.importLegacyPass(lastUsed: nil, at: instant)
        XCTAssertEqual(fresh.pass.usage, .neverUsed, "a validated absent key is the old unused state")

        var future = FlintLedgerState(initializedAt: now)
        future.importLegacyPass(lastUsed: now.addingTimeInterval(86_400).timeIntervalSince1970, at: instant)
        XCTAssertEqual(future.pass.usage, .unknown, "a future use is never a fresh pass")
        XCTAssertEqual(future.consumePass(operationID: "x", hasActiveCommitment: true, at: instant), .failure(.unavailable))

        var malformed = FlintLedgerState(initializedAt: now)
        malformed.importLegacyPass(lastUsed: "yesterday", at: instant)
        XCTAssertEqual(malformed.pass.usage, .unknown)

        var old = FlintLedgerState(initializedAt: now)
        old.importLegacyPass(lastUsed: now.addingTimeInterval(-30 * 86_400).timeIntervalSince1970, at: instant)
        XCTAssertEqual(try old.consumePass(operationID: "y", hasActiveCommitment: true, at: instant).get().replayed, false)
    }

    func testOperationMemoryIsBounded() {
        var s = FlintLedgerState(initializedAt: now)
        for index in 0..<(FlintLedgerState.operationMemory + 50) {
            s.remember(.init(id: "op\(index)", kind: "k", result: "r", at: now))
        }
        XCTAssertEqual(s.operations.count, FlintLedgerState.operationMemory)
        XCTAssertNil(s.operation("op0"))
        XCTAssertNotNil(s.operation("op\(FlintLedgerState.operationMemory + 49)"))
    }
}
