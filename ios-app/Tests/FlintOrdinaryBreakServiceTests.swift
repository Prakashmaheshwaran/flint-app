import XCTest
@testable import FlintCore

final class FlintOrdinaryBreakServiceTests: XCTestCase {
    private typealias Target = FlintBreakScope.Target
    private let a = Target(kind: .application, token: Data("A".utf8))
    private let b = Target(kind: .application, token: Data("B".utf8))
    private var directory: URL!
    private var marker: Date?
    private var wall = Date(timeIntervalSince1970: 1_800_000_000)
    private var mono: Double = 1000
    private var boot: String? = "boot-1"
    private struct Cause {
        var id: String
        var tier: BreakLevel = .harder
        var targets: Set<Target>
        var end: Double = 10_000
        var revision = 0
        var active = true
    }
    private var causes: [Cause] = []
    private var carriers: Set<String> = []
    private var stopped: [String] = []
    private var registrationFails = false
    private var applyFails = false
    private var unavailable = false
    private var transitionHeld = false
    private var order: [String] = []
    private var applied: Set<Target> = []
    private var afterRegistration: (() throws -> Void)?

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-ordinary-\(UUID().uuidString)")
        marker = nil; wall = Date(timeIntervalSince1970: 1_800_000_000); mono = 1000; boot = "boot-1"
        causes = [.init(id: "session", targets: [a, b])]
        carriers = []; stopped = []; order = []; applied = []
        registrationFails = false; applyFails = false; unavailable = false; transitionHeld = false; afterRegistration = nil
    }
    override func tearDown() { try? FileManager.default.removeItem(at: directory); super.tearDown() }
    private var clock: FlintClock { .init(now: { self.wall }, monotonic: { self.mono }, bootSessionID: { self.boot }, calendar: { Calendar.current }) }
    private var now: FlintLedgerInstant { .init(clock: clock) }
    private var ledger: FlintProtectionLedger {
        .init(directory: directory, clock: clock, initializedMarker: { self.marker }, setInitializedMarker: { self.marker = $0 })
    }
    private var scope: FlintBreakScope { .init(targets: [a]) }
    private func context(_ state: FlintLedgerState) -> FlintBreakContext? {
        if unavailable { return nil }
        return .init(owners: causes.map { cause in
            .init(.init(cause: .init(causeID: cause.id, occurrenceID: "occurrence-" + cause.id,
                revision: cause.revision, kind: .session, tier: cause.tier, isActive: cause.active),
                selection: .blockList(.init(targets: cause.targets)),
                logicalEnd: .init(wall: wall.addingTimeInterval(cause.end - mono), monotonic: cause.end, bootID: boot),
                timeKnown: true, harder: state.harder[cause.id] ?? .init()), storeName: "store-" + cause.id)
        })
    }
    private var service: FlintOrdinaryBreakService {
        .init(ledger: ledger, clock: clock, transition: { body in
            XCTAssertFalse(self.transitionHeld); self.transitionHeld = true
            defer { self.transitionHeld = false }; body(); return true
        }, context: { state, _ in self.context(state) }, register: { record, _ in
            XCTAssertTrue(self.transitionHeld)
            self.order.append("register")
            if self.registrationFails { throw FlintOrdinaryBreakError.unavailable }
            self.carriers.insert(record.activityName)
            try self.afterRegistration?()
        }, registered: { self.carriers.contains($0) }, stop: {
            self.carriers.remove($0); self.stopped.append($0)
        }, apply: { records, context, now in
            XCTAssertTrue(self.transitionHeld)
            if self.applyFails { throw FlintOrdinaryBreakError.unavailable }
            self.order.append("apply")
            self.applied = records.reduce(into: []) { $0.formUnion(context?.authorizedTargets(for: $1, at: now) ?? []) }
            let durable = try self.ledger.peek().get()
            XCTAssertEqual(durable.ordinaryGrants?.records.map { $0.grant.id }, records.map { $0.grant.id })
        })
    }
    private func advance(_ seconds: Double, wallDelta: Double? = nil) {
        mono += seconds; wall.addTimeInterval(wallDelta ?? seconds)
    }
    private func request(_ scope: FlintBreakScope? = nil) throws -> FlintBreakAuthorization.Request {
        switch service.request(scope: scope ?? self.scope) {
        case let .waiting(request, _, _), let .confirmation(request): return request
        default: XCTFail("No request"); throw FlintOrdinaryBreakError.unavailable
        }
    }
    private func accept(_ request: FlintBreakAuthorization.Request) throws -> FlintOrdinaryGrantState.Record {
        guard case let .active(record, _) = service.confirm(request) else {
            XCTFail("No active grant"); throw FlintOrdinaryBreakError.unavailable
        }
        return record
    }
    private func lastReceipt() throws -> FlintOrdinaryGrantState.Receipt {
        try FlintProtectionLedger.makeDecoder().decode(FlintOrdinaryGrantState.Receipt.self,
            from: XCTUnwrap(ledger.peek().get().ordinaryGrants?.receipts.last))
    }

    func testPC18PersistedWaitsEscalateAndResumeDoesNotRefund() throws {
        for (index, wait) in [60.0, 300, 900, 900].enumerated() {
            let result = service.request(scope: scope)
            guard case let .waiting(request, remaining, total) = result else { return XCTFail("Expected wait") }
            XCTAssertEqual(remaining, wait); XCTAssertEqual(total, wait)
            advance(wait)
            let record = try accept(request)
            XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, index + 1)
            XCTAssertTrue(service.resumeNow(grantID: record.grant.id))
            XCTAssertTrue(applied.isEmpty)
            XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, index + 1)
        }
    }

    func testPC19MaximumWaitAndAtomicPerCauseChargeUsingCauseIDs() throws {
        causes.append(.init(id: "second", targets: [a]))
        try ledger.transaction { $0.harder["second"] = .init(committedCount: 2) }.get()
        let request = try request()
        advance(899)
        XCTAssertEqual(service.confirm(request), .refused(.waitNotMatured))
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 0)
        advance(1)
        let record = try accept(request)
        let state = try ledger.peek().get()
        XCTAssertEqual(state.harder["session"]?.committedCount, 1)
        XCTAssertEqual(state.harder["second"]?.committedCount, 3)
        XCTAssertTrue(state.hasCommittedHarderWait(cause: "session", operationID: request.operationID))
        XCTAssertTrue(state.hasCommittedHarderWait(cause: "second", operationID: request.operationID))
        XCTAssertNil(state.harder["occurrence-second"])
        XCTAssertEqual(try accept(request).grant.id, record.grant.id)
        XCTAssertEqual(try ledger.peek().get().harder["second"]?.committedCount, 3)
    }

    func testMismatchedSecondPendingAbortsWithoutChargingFirst() throws {
        causes.append(.init(id: "second", targets: [a]))
        let request = try request(); advance(60)
        try ledger.transaction { $0.harder["second"]?.pending?.scope = ["changed"] }.get()
        XCTAssertEqual(service.confirm(request), .refused(.pendingMismatch))
        let state = try ledger.peek().get()
        XCTAssertEqual(state.harder["session"]?.committedCount, 0)
        XCTAssertEqual(state.harder["second"]?.committedCount, 0)
        XCTAssertTrue(carriers.isEmpty)
    }

    func testRegistrationFailureDoesNotChargeAndStopsPartialRegistration() throws {
        let request = try request(); advance(60); registrationFails = true
        XCTAssertEqual(service.confirm(request), .refused(.unavailable))
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 0)
        XCTAssertTrue(try ledger.peek().get().ordinaryGrants!.records.isEmpty)
        XCTAssertEqual(stopped.count, 1); XCTAssertTrue(applied.isEmpty)
    }

    func testRegisteredCarrierIsStoppedWhenDurableCommitFails() throws {
        let request = try request(); advance(60)
        let backup = directory.appendingPathExtension("backup")
        afterRegistration = {
            try FileManager.default.moveItem(at: self.directory, to: backup)
            try Data("not a directory".utf8).write(to: self.directory)
        }
        defer {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.moveItem(at: backup, to: directory)
        }
        XCTAssertEqual(service.confirm(request), .refused(.unavailable))
        XCTAssertEqual(stopped.count, 1); XCTAssertTrue(carriers.isEmpty); XCTAssertTrue(applied.isEmpty)
        let preserved = try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
            from: Data(contentsOf: backup.appendingPathComponent(FlintProtectionLedger.fileName)))
        XCTAssertEqual(preserved.harder["session"]?.committedCount, 0)
    }

    func testPC22EarlyExactLateCallbacksAndWallClockEdits() throws {
        causes[0].tier = .easy
        let record = try accept(request())
        XCTAssertEqual(order, ["register", "apply"])
        advance(299, wallDelta: 90_000)
        XCTAssertTrue(service.reconcile(activity: record.activityName, callback: .warning))
        XCTAssertEqual(applied, [a])
        XCTAssertNil(try ledger.peek().get().ordinaryGrants?.records[0].endReason)
        advance(1, wallDelta: -90_000)
        XCTAssertTrue(service.reconcile(activity: record.activityName, callback: .warning))
        XCTAssertTrue(applied.isEmpty)
        advance(600)
        XCTAssertTrue(service.reconcile(activity: record.activityName, callback: .end))
        XCTAssertEqual(try lastReceipt().reason, .deadline)
        let events = try ledger.peek().get().ordinaryGrants!.eventOutbox.map {
            try FlintProtectionLedger.makeDecoder().decode(FlintOrdinaryGrantState.EventIntent.self, from: $0)
        }
        XCTAssertEqual(events.filter { $0.kind == .ended }.count, 1)
    }

    func testPC24AndPC26OnlyUnauthorizedTargetsAreReshielded() throws {
        causes[0].tier = .easy
        let record = try accept(request(.init(targets: [a, b])))
        XCTAssertEqual(applied, [a, b])
        causes.append(.init(id: "new-hardcore", tier: .hardcore, targets: [a]))
        XCTAssertTrue(service.reconcile())
        XCTAssertEqual(applied, [b])
        XCTAssertEqual(try ledger.peek().get().ordinaryGrants?.records[0].releasedTargets, [b])
        causes.removeLast()
        XCTAssertTrue(service.reconcile())
        XCTAssertEqual(applied, [b], "A previously invalidated portion cannot resurrect")
        XCTAssertTrue(service.resumeNow(grantID: record.grant.id)); XCTAssertTrue(applied.isEmpty)
    }

    func testNewEasyContributorAndRevisionZeroEndChangeRevokeAccess() throws {
        causes[0].tier = .easy
        _ = try accept(request())
        causes[0].end += 100 // sessions keep revision 0
        XCTAssertTrue(service.reconcile())
        XCTAssertTrue(applied.isEmpty)
        XCTAssertEqual(try lastReceipt().reason, .supersession)
        _ = try accept(request())
        causes.append(.init(id: "new-easy", tier: .easy, targets: [a]))
        XCTAssertTrue(service.reconcile())
        XCTAssertTrue(applied.isEmpty)
        XCTAssertEqual(try lastReceipt().reason, .newCommitment)
    }

    func testHardcoreUnknownAndChangedWaitEndDenyWithoutCharge() throws {
        causes[0].tier = .hardcore
        XCTAssertEqual(service.request(scope: scope), .refused(.hardcore))
        causes[0].tier = .harder
        let request = try request(); advance(60)
        causes[0].end += 1
        XCTAssertEqual(service.confirm(request), .refused(.pendingMismatch))
        unavailable = true
        XCTAssertEqual(service.confirm(request), .refused(.unavailable))
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 0)
    }

    func testProcessDeathRetainsDeadlineRebootEndsAndReplayNeverRenews() throws {
        let request = try request(); advance(60)
        let record = try accept(request)
        advance(100)
        XCTAssertTrue(service.reconcile()) // a new service instance, same ledger
        XCTAssertEqual(try accept(request).remaining(at: now), 200)
        boot = "boot-2"; mono = 5000
        XCTAssertTrue(service.reconcile())
        XCTAssertTrue(applied.isEmpty)
        XCTAssertEqual(service.confirm(request), .ended(.reboot))
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 1)
        XCTAssertEqual(try lastReceipt().grantID, record.grant.id)
    }

    func testUnknownBootEndsReleaseWithoutLosingConsumption() throws {
        let request = try request(); advance(60)
        _ = try accept(request); boot = nil
        XCTAssertTrue(service.reconcile())
        XCTAssertTrue(applied.isEmpty)
        XCTAssertEqual(try lastReceipt().reason, .reboot)
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 1)
        let events = try ledger.peek().get().ordinaryGrants!.eventOutbox.map {
            try FlintProtectionLedger.makeDecoder().decode(FlintOrdinaryGrantState.EventIntent.self, from: $0)
        }
        XCTAssertEqual(events.last?.timeConfidence, .uncertain(reason: "reboot_or_unknown_boot"))
    }

    func testLateDeadlineRetainsLogicalEventTimeAndRecordsRecovery() throws {
        causes[0].tier = .easy
        let record = try accept(request())
        advance(900, wallDelta: 90_000)
        XCTAssertTrue(service.reconcile(activity: record.activityName, callback: .end))
        let state = try ledger.peek().get()
        let end = try state.ordinaryGrants!.eventOutbox.map {
            try FlintProtectionLedger.makeDecoder().decode(FlintOrdinaryGrantState.EventIntent.self, from: $0)
        }.last!
        XCTAssertEqual(end.at, record.grant.deadline.wall)
        XCTAssertEqual(end.observedAt, wall)
        XCTAssertEqual(end.timeConfidence, .certain)
        XCTAssertEqual(state.operation(record.grant.id + ".break.expiry")?.result, "missed_warning_recovered")
    }

    func testApplicationFailureKeepsAcceptedGrantAndRetriesWithoutCharging() throws {
        let request = try request(); advance(60); applyFails = true
        XCTAssertEqual(service.confirm(request), .refused(.unavailable))
        let accepted = try ledger.peek().get().ordinaryGrants!.records[0]
        XCTAssertTrue(accepted.applicationPending)
        applyFails = false; advance(50)
        XCTAssertEqual(try accept(request).grant.id, accepted.grant.id)
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 1)
    }

    func testPassCancelsOrdinaryGrantAndKeepsItsChargeAndFrozenEndIntent() throws {
        let request = try request(); advance(60)
        let record = try accept(request)
        var state = try ledger.peek().get()
        state.emergencyRecovery = .init(operationID: "pass", at: now,
            scope: .init(revision: "fixture", sessions: [], rules: [], stores: ["flint"]), carrierName: "fixture")
        let stopped = state.cancelAccessGrantsForPass()
        XCTAssertEqual(stopped, [record.activityName])
        XCTAssertEqual(state.ordinaryGrants?.records[0].endReason, .pass)
        XCTAssertEqual(state.ordinaryGrants?.records[0].events.last?.focusSessionIDs, ["session"])
        XCTAssertEqual(state.harder["session"]?.committedCount, 1)
    }

    func testNoStackingCancellationAndMissingCarrierRepair() throws {
        let request = try request(); advance(30)
        service.cancel(request)
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 0)
        let next = try self.request(); XCTAssertNotEqual(next.operationID, request.operationID)
        advance(60); let record = try accept(next)
        guard case let .active(existing, _) = service.request(scope: scope) else { return XCTFail("Expected live break") }
        XCTAssertEqual(existing.grant.id, record.grant.id)
        carriers = []; registrationFails = true
        XCTAssertTrue(service.reconcile())
        XCTAssertTrue(applied.isEmpty)
        XCTAssertEqual(try ledger.peek().get().harder["session"]?.committedCount, 1)
    }

    func testEventIntentSurvivesCrashAndUsesFrozenSessionIDsUntilPositiveAck() throws {
        causes[0].tier = .easy
        let record = try accept(request())
        var persistedRows = Set<String>()
        XCTAssertTrue(service.drainEvents { event, session in
            persistedRows.insert(event.stableID(for: session)); return false // append happened, ack lost
        })
        XCTAssertFalse(try ledger.peek().get().ordinaryGrants!.records[0].events[0].isAcknowledged)
        causes[0].id = "replacement"
        XCTAssertTrue(service.reconcile())
        XCTAssertTrue(service.drainEvents { event, session in
            XCTAssertEqual(session, "session")
            persistedRows.insert(event.stableID(for: session)); return true
        })
        XCTAssertEqual(persistedRows, [record.grant.id + ".break_started.session", record.grant.id + ".break_ended.session"])
        XCTAssertTrue(try ledger.peek().get().ordinaryGrants!.records.isEmpty)
        XCTAssertTrue(service.drainEvents { _, _ in XCTFail("Acknowledged event replayed"); return true })
        XCTAssertEqual(service.confirm(.init(operationID: record.grant.operationID, scope: scope)), .ended(.supersession))
    }

    func testOldV2AndAdditiveV2V3RoundTripUseProductionCodec() throws {
        let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/Breaks/ledger-v2-without-breaks.json")
        let legacy = try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self, from: Data(contentsOf: fixture))
        XCTAssertNil(legacy.ordinaryGrants)
        causes[0].tier = .easy; _ = try accept(request())
        var state = try ledger.peek().get()
        for version in [2, 3] {
            state.version = version
            let roundtrip = try FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self,
                from: FlintProtectionLedger.makeEncoder().encode(state))
            XCTAssertEqual(roundtrip, state)
        }
    }

    func testCorruptAuditIsIsolatedAndOldGenerationCannotReplayAfterPruning() throws {
        var grants = FlintOrdinaryGrantState()
        let old = try grants.newOperationID()
        grants.acceptedOperations = Set((0..<FlintOrdinaryGrantState.maximumOperations).map { _ in grants.generation + ":" + UUID().uuidString })
        grants.acceptedOperations.removeFirst(); grants.acceptedOperations.insert(old)
        grants.receipts = [Data("corrupt retained audit".utf8)]
        grants.eventOutbox = [Data("corrupt retained event".utf8)]
        XCTAssertTrue(grants.isValid)
        _ = try grants.newOperationID()
        XCTAssertTrue(grants.previousAcceptedOperations.contains(old))
        XCTAssertFalse(grants.validOperation(old, generation: grants.generation))
        try ledger.transaction { $0.ordinaryGrants = grants }.get()
        XCTAssertEqual(try ledger.read().get().ordinaryGrants?.receipts, grants.receipts)
        XCTAssertTrue(service.drainEvents { _, _ in XCTFail("Invalid event emitted"); return true })
        XCTAssertEqual(try ledger.peek().get().ordinaryGrants?.eventOutbox, grants.eventOutbox)
        XCTAssertEqual(service.request(scope: scope, operationID: old), .refused(.expiredRequest))
    }

    func testCapacityAndAggregateBounds() throws {
        causes[0].tier = .easy
        let record = try accept(request())
        var grants = try ledger.peek().get().ordinaryGrants!
        grants.receipts = Array(repeating: Data(count: 40_000), count: FlintOrdinaryGrantState.maximumReceipts)
        XCTAssertFalse(grants.isValid)
        grants = try ledger.peek().get().ordinaryGrants!
        grants.records = Array(repeating: record, count: FlintOrdinaryGrantState.maximumGrants + 1)
        XCTAssertFalse(grants.isValid)
    }
}
