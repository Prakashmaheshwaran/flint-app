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

    private func ledger(lockTimeout: TimeInterval = 5, legacy: FlintLegacyValue = .absent) -> FlintProtectionLedger {
        FlintProtectionLedger(directory: directory, clock: clock, lockTimeout: lockTimeout,
                              initializedMarker: { [unowned self] in self.marker },
                              setInitializedMarker: { [unowned self] in self.marker = $0 },
                              migrate: { state, now in state.importLegacyPass(legacy, at: now) })
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
        let migrations = try state().migrations
        XCTAssertTrue(migrations.contains("kept"))
        XCTAssertFalse(migrations.contains("discarded"))
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
        XCTAssertTrue(s.pass.status(at: instant, hasActiveCommitment: true).isRecovering)
        // Reboot: new boot id, monotonic restarts, wall says a week passed.
        boot = "boot-2"; monotonic = 50; now = now.addingTimeInterval(7 * 86_400)
        s.pass.checkpoint(at: instant)
        XCTAssertEqual(s.pass.cooldownRemaining, FlintProtectionConstants.passPeriod - 1000, "no unverified credit")
        XCTAssertFalse(s.pass.status(at: instant, hasActiveCommitment: true).isRecovering, "recovery access ends after a restart")
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
        XCTAssertFalse(s.pass.status(at: instant, hasActiveCommitment: true).isRecovering)
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
        XCTAssertEqual(s.commitHarderWait(cause: "c", operationID: "w2", at: instant), .committed)
        XCTAssertEqual(s.harder["c"]?.committedCount, 1)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "break", scope: ["s"], operationID: "w3", at: instant),
                       .waiting(remaining: 300), "PC-18: second exception waits 300 s")
        advance(300); _ = s.commitHarderWait(cause: "c", operationID: "w3", at: instant)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "break", scope: ["s"], operationID: "w4", at: instant),
                       .waiting(remaining: 900))
        advance(900); _ = s.commitHarderWait(cause: "c", operationID: "w4", at: instant)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "break", scope: ["s"], operationID: "w5", at: instant),
                       .waiting(remaining: 900), "capped at 900")
    }

    func testCommitBeforeMaturityAndDuplicateCommitsDoNothing() throws {
        var s = try state()
        s.requestHarderWait(cause: "c", kind: "stop", scope: [], operationID: "w1", at: instant)
        advance(59)
        XCTAssertEqual(s.commitHarderWait(cause: "c", operationID: "w1", at: instant), .notMatured)
        advance(1)
        XCTAssertEqual(s.commitHarderWait(cause: "c", operationID: "w1", at: instant), .committed)
        XCTAssertEqual(s.commitHarderWait(cause: "c", operationID: "w1", at: instant), .alreadyCommitted,
                       "§8: an ambiguous retry finds the commit instead of counting again")
        XCTAssertEqual(s.harder["c"]?.committedCount, 1)
        XCTAssertEqual(s.commitHarderWait(cause: "c", operationID: "other", at: instant), .nothingPending)
    }

    func testChangedScopeRestartsAndAResumedWaitAdoptsTheNewID() throws {
        var s = try state()
        s.requestHarderWait(cause: "c", kind: "stop", scope: ["a"], operationID: "w1", at: instant)
        advance(50)
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "stop", scope: ["a", "b"], operationID: "w2", at: instant),
                       .waiting(remaining: 60), "changed scope restarts")
        XCTAssertEqual(s.requestHarderWait(cause: "c", kind: "stop", scope: ["a", "b"], operationID: "w3", at: instant),
                       .waiting(remaining: 60), "same request resumes, not restarts")
        advance(60)
        XCTAssertEqual(s.commitHarderWait(cause: "c", operationID: "w3", at: instant), .committed,
                       "the resumed wait is committed under the caller's id")
    }

    func testRebootCancelsAPendingWaitWithoutCountingIt() throws {
        var s = try state()
        s.requestHarderWait(cause: "c", kind: "stop", scope: [], operationID: "w1", at: instant)
        boot = "boot-2"; monotonic = 10; now = now.addingTimeInterval(3600)
        XCTAssertEqual(s.harderWaitStatus(cause: "c", at: instant), .none)
        XCTAssertEqual(s.commitHarderWait(cause: "c", operationID: "w1", at: instant), .nothingPending)
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
        var s = FlintLedgerState(initializedAt: now)
        XCTAssertEqual(s.consumePass(operationID: "early", hasActiveCommitment: true, at: instant), .failure(.unavailable),
                       "no pass before the one-time import has run")
        s.importLegacyPass(.value(now.addingTimeInterval(-86_400).timeIntervalSince1970), at: instant)
        XCTAssertEqual(s.pass.usage, .used)
        XCTAssertEqual(s.pass.cooldownRemaining, FlintProtectionConstants.passPeriod - 86_400, "cooldown preserved")
        s.importLegacyPass(.absent, at: instant)
        XCTAssertEqual(s.pass.usage, .used, "runs once")

        var fresh = FlintLedgerState(initializedAt: now)
        fresh.importLegacyPass(.absent, at: instant)
        XCTAssertEqual(fresh.pass.usage, .neverUsed, "a validated absent key is the old unused state")

        var unreadable = FlintLedgerState(initializedAt: now)
        unreadable.importLegacyPass(.unreadable, at: instant)
        XCTAssertEqual(unreadable.pass.usage, .unknown, "a partial/unreadable read is never a fresh pass")

        var future = FlintLedgerState(initializedAt: now)
        future.importLegacyPass(.value(now.addingTimeInterval(86_400).timeIntervalSince1970), at: instant)
        XCTAssertEqual(future.pass.usage, .unknown, "a future use is never a fresh pass")
        XCTAssertEqual(future.consumePass(operationID: "x", hasActiveCommitment: true, at: instant), .failure(.unavailable))

        var malformed = FlintLedgerState(initializedAt: now)
        malformed.importLegacyPass(.value("yesterday"), at: instant)
        XCTAssertEqual(malformed.pass.usage, .unknown)

        var old = FlintLedgerState(initializedAt: now)
        old.importLegacyPass(.value(now.addingTimeInterval(-30 * 86_400).timeIntervalSince1970), at: instant)
        XCTAssertEqual(try old.consumePass(operationID: "y", hasActiveCommitment: true, at: instant).get().replayed, false)
    }

    func testLegacyImportReadsTheRealUserDefaultsBridging() throws {
        let suite = "flint.tests.ledger.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(now.addingTimeInterval(-3600).timeIntervalSince1970, forKey: FlintLedgerState.legacyPassKey)
        var s = FlintLedgerState(initializedAt: now)
        s.importLegacyPass(defaults.object(forKey: FlintLedgerState.legacyPassKey).map(FlintLegacyValue.value) ?? .absent, at: instant)
        XCTAssertEqual(s.pass.usage, .used)
        XCTAssertEqual(s.pass.cooldownRemaining, FlintProtectionConstants.passPeriod - 3600)
    }

    func testAFreshLedgerOffersNoPassUntilTheImportRuns() throws {
        let noImport = FlintProtectionLedger(directory: directory, clock: clock, initializedMarker: { nil }, setInitializedMarker: { _ in })
        var s = try noImport.read().get()
        XCTAssertEqual(s.consumePass(operationID: "x", hasActiveCommitment: true, at: instant), .failure(.unavailable))
        _ = ledger(legacy: .absent).read()
        s = try state()
        XCTAssertEqual(try s.consumePass(operationID: "x", hasActiveCommitment: true, at: instant).get().replayed, false)
    }

    // MARK: Review follow-ups (§8, §9)

    func testWithoutABootIDCooldownsUseMonotonicAndReleasesUseTheLargerDelta() {
        boot = nil
        let start = instant
        advance(100)
        XCTAssertEqual(start.elapsed(until: instant), 100, "steady clocks: credited")
        now = now.addingTimeInterval(3600)
        XCTAssertEqual(start.elapsed(until: instant), 100, "a forward wall jump credits a cooldown nothing extra")
        XCTAssertEqual(start.elapsedForRelease(until: instant), 3700, "…but ends a release early")
        now = now.addingTimeInterval(-3600 - 3600)
        XCTAssertEqual(start.elapsed(until: instant), 100)
        XCTAssertNil(start.elapsedForRelease(until: instant), "a backward wall jump ends a release rather than extending it (PC-55)")
        monotonic = 1
        XCTAssertNil(start.elapsed(until: instant), "monotonic below the anchor proves a reboot")
        XCTAssertNil(start.elapsedForRelease(until: instant))
    }

    func testABackwardClockJumpEndsRecoveryWithoutABootID() throws {
        boot = nil
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        advance(600)
        XCTAssertEqual(s.pass.status(at: instant, hasActiveCommitment: true).recoveryRemaining, 3000)
        s.pass.checkpoint(at: instant)
        now = now.addingTimeInterval(-3600)
        advance(600)
        XCTAssertEqual(s.pass.status(at: instant, hasActiveCommitment: true).recoveryRemaining, 0)
    }

    func testBootlessRebootPlusClockRollbackEndsRecoveryPC55() throws {
        boot = nil
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        advance(50)
        s.pass.checkpoint(at: instant)
        // Unreported reboot (uptime restarts but already exceeds the anchor) + clock set back.
        monotonic += 50
        now = now.addingTimeInterval(-30 * 86_400)
        XCTAssertEqual(s.pass.status(at: instant, hasActiveCommitment: true).recoveryRemaining, 0)
        s.pass.checkpoint(at: instant)
        XCTAssertGreaterThan(s.pass.cooldownRemaining, 0)
    }

    func testASameBootForwardWallJumpLeavesRecoveryUnchangedPC54() throws {
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        advance(600)
        now = now.addingTimeInterval(2 * 3600)
        XCTAssertEqual(s.pass.status(at: instant, hasActiveCommitment: true).recoveryRemaining, 3000)
    }

    func testOneMissingBootIDStillCountsAsTheSameBootCandidate() {
        let start = instant
        boot = nil
        advance(100)
        XCTAssertEqual(start.elapsed(until: instant), 100)
        XCTAssertEqual(start.elapsedForRelease(until: instant), 100)
    }

    func testOneOperationAcrossABatchIncrementsEachCauseOncePC19() throws {
        var s = try state()
        s.requestHarderWait(cause: "A", kind: "break", scope: ["t"], operationID: "batch", at: instant)
        s.requestHarderWait(cause: "B", kind: "break", scope: ["t"], operationID: "batch", at: instant)
        advance(60)
        XCTAssertEqual(s.commitHarderWait(cause: "A", operationID: "batch", at: instant), .committed)
        XCTAssertEqual(s.commitHarderWait(cause: "B", operationID: "batch", at: instant), .committed)
        XCTAssertEqual(s.commitHarderWait(cause: "A", operationID: "batch", at: instant), .alreadyCommitted)
        XCTAssertEqual(s.harder["A"]?.committedCount, 1)
        XCTAssertEqual(s.harder["B"]?.committedCount, 1)
    }

    func testPassAndWaitsStillProgressWithoutABootID() throws {
        boot = nil
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        s.requestHarderWait(cause: "c", kind: "stop", scope: [], operationID: "w", at: instant)
        advance(604_800)
        XCTAssertEqual(s.harderWaitStatus(cause: "c", at: instant), .matured)
        XCTAssertEqual(try s.consumePass(operationID: "b", hasActiveCommitment: true, at: instant).get().replayed, false,
                       "the cooldown decays on the conservative timeline instead of never")
    }

    func testAnOlderPassOperationReplaysInsteadOfReportingACooldown() throws {
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        advance(604_800)
        _ = s.consumePass(operationID: "b", hasActiveCommitment: true, at: instant)
        XCTAssertEqual(try s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant).get().replayed, true)
    }

    func testPassStatusDoesNotMutateTheRecord() throws {
        var s = try state()
        _ = s.consumePass(operationID: "a", hasActiveCommitment: true, at: instant)
        let stored = s.pass
        advance(1800)
        XCTAssertEqual(s.pass.status(at: instant, hasActiveCommitment: true).recoveryRemaining, 1800)
        XCTAssertEqual(s.pass, stored)
    }

    func testALockHeldElsewhereIsBusyNotUnavailable() throws {
        _ = try state()
        let path = directory.appendingPathComponent("ledger.lock").path
        let holder = open(path, O_RDWR)
        XCTAssertGreaterThanOrEqual(holder, 0)
        XCTAssertEqual(flock(holder, LOCK_EX), 0)
        defer { flock(holder, LOCK_UN); close(holder) }
        // A clock whose monotonic reading advances on every read makes the deadline deterministic.
        var ticks: TimeInterval = 0
        let ticking = FlintClock(now: { Date() }, monotonic: { ticks += 0.25; return ticks },
                                 bootSessionID: { "boot-1" }, calendar: { Calendar(identifier: .gregorian) })
        let contended = FlintProtectionLedger(directory: directory, clock: ticking, lockTimeout: 1,
                                              initializedMarker: { Date() }, setInitializedMarker: { _ in })
        guard case .failure(.busy) = contended.read() else { return XCTFail("expected busy") }
    }

    func testNestedTransactionsReportNested() {
        let ledger = ledger()
        let outer = ledger.transaction { _ -> Result<Void, FlintLedgerError> in ledger.transaction { _ in } }
        guard case let .success(inner) = outer, case .failure(.nested) = inner else {
            return XCTFail("nested transaction must report .nested, got \(outer)")
        }
    }

    func testNewerSchemaIsNotQuarantinedAndQuarantineIsBounded() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("ledger.json")
        try Data(#"{"version": 99}"#.utf8).write(to: file)
        _ = ledger().read()
        let quarantine = directory.appendingPathComponent("quarantine")
        XCTAssertFalse(FileManager.default.fileExists(atPath: quarantine.path), "a newer schema is left alone")
        for index in 0..<5 {
            try Data("corrupt \(index)".utf8).write(to: file)
            now = now.addingTimeInterval(1)
            _ = ledger().read()
        }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: quarantine.path).count, 3)
    }

    func testHarderEntriesArePruned() {
        var s = FlintLedgerState(initializedAt: now)
        for index in 0..<100 { s.harder["old\(index)"] = .init(committedCount: 1) }
        s.requestHarderWait(cause: "pending", kind: "stop", scope: [], operationID: "w", at: instant)
        s.pruneHarder(keeping: ["old7"], limit: 10)
        XCTAssertLessThanOrEqual(s.harder.count, 10)
        XCTAssertNotNil(s.harder["old7"])
        XCTAssertNotNil(s.harder["pending"])
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
