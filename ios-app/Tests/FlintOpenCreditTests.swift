import XCTest
@testable import FlintCore

/// These tests use the real flock/atomic ledger and the production adapter ordering, with
/// fake native stores and registrar. They do not establish physical Screen Time delivery.
final class FlintOpenCreditTests: XCTestCase {
    private var directory: URL!
    private var marker: Date?
    private var now = Date(timeIntervalSince1970: 1_793_188_800) // whole-second fixture
    private var monotonic: TimeInterval = 10_000
    private var boot: String? = "boot-a"
    private var caps = ["open-a": 3]
    private var targets: Set<String> = ["app"]
    private var block: FlintOpenCreditBlock?
    private var unavailableContext = false
    private var refuses = false
    private var registrations: [String: TimeInterval] = [:]
    private var released: [String] = []
    private var shielded: [String] = []
    private var order: [String] = []

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-open-\(UUID().uuidString)")
        marker = nil
        now = ISO8601DateFormatter().date(from: "2026-10-28T12:00:00Z")!
        monotonic = 10_000; boot = "boot-a"; caps = ["open-a": 3]; targets = ["app"]
        block = nil; unavailableContext = false; refuses = false
        registrations = [:]; released = []; shielded = []; order = []
    }
    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian); value.timeZone = TimeZone(secondsFromGMT: 0)!; return value
    }
    private var clock: FlintClock {
        FlintClock(now: { self.now }, monotonic: { self.monotonic }, bootSessionID: { self.boot }, calendar: { self.calendar })
    }
    private var instant: FlintLedgerInstant { FlintLedgerInstant(clock: clock) }
    private func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds); monotonic += seconds }
    private func ledger(importing legacy: FlintLegacyValue = .absent, established: Bool = false) -> FlintProtectionLedger {
        FlintProtectionLedger(directory: directory, clock: clock, lockTimeout: 10,
            initializedMarker: { self.marker }, setInitializedMarker: { self.marker = $0 },
            migrate: { state, at in
                state.importLegacyOpenCredits(legacy, targetKeys: self.targets, established: established,
                                              at: at, calendar: self.calendar)
            })
    }
    private func service(_ ledger: FlintProtectionLedger? = nil) -> FlintOpenCreditService {
        FlintOpenCreditService(ledger: ledger ?? self.ledger(), clock: clock, context: {
            self.unavailableContext ? nil : .init(coveringCaps: self.caps, targetKeys: self.targets, blockedBy: self.block)
        }, register: { grant, remaining in
            self.order.append("register")
            if self.refuses { throw FlintOpenCreditRefusal.denied(.registrationFailed) }
            self.registrations[grant.activityName] = remaining
        }, registered: { self.registrations[$0] != nil }, release: { grant in
            self.order.append("release")
            self.released.append(grant.targetKey)
            // A separate transaction would be nested here, so inspect durable bytes directly.
            let bytes = try! Data(contentsOf: self.directory.appendingPathComponent("ledger.json"))
            let durable = try! FlintProtectionLedger.makeDecoder().decode(FlintLedgerState.self, from: bytes)
            XCTAssertEqual(durable.openCredits.grants[grant.targetKey], grant, "persist before native release")
            XCTAssertNotNil(self.registrations[grant.activityName], "expiry registered before native release")
        }, reshield: { grant in self.shielded.append(grant.targetKey); self.order.append("reshield") },
        stop: { self.registrations[$0] = nil })
    }
    private func accepted(_ op: String = UUID().uuidString, using service: FlintOpenCreditService? = nil) throws -> FlintOpenCreditReceipt {
        try (service ?? self.service()).accept(target: "app", operationID: op).get()
    }
    private func state() throws -> FlintLedgerState { try ledger().read().get() }
    private func denied(_ expected: FlintOpenCreditBlock, op: String = UUID().uuidString, using service: FlintOpenCreditService? = nil) {
        XCTAssertEqual((service ?? self.service()).accept(target: "app", operationID: op), .failure(.denied(expected)))
    }

    func testPC28ThreeBoundedCreditsThenFourthDenied() throws {
        for index in 1...3 {
            let receipt = try accepted("op-\(index)")
            XCTAssertEqual(receipt.grant.duration, 300)
            XCTAssertEqual(receipt.remaining, 3 - index)
            advance(300)
            XCTAssertTrue(service().reconcile(activity: receipt.grant.activityName, callback: .warning))
        }
        denied(.exhausted)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 3)
        XCTAssertEqual(shielded.count, 3)
    }

    func testPC29ConcurrentDistinctRequestsWithOneLeftChargeOnce() throws {
        caps = ["open-a": 1]
        _ = try state()
        let lock = NSLock()
        var receipts: [FlintOpenCreditReceipt] = []
        DispatchQueue.concurrentPerform(iterations: 12) { index in
            let result = self.service().accept(target: "app", operationID: "race-\(index)")
            lock.lock(); defer { lock.unlock() }
            if case let .success(receipt) = result { receipts.append(receipt) }
        }
        XCTAssertEqual(receipts.count, 12)
        XCTAssertEqual(Set(receipts.map { $0.grant.operationID }).count, 1)
        XCTAssertEqual(receipts.filter { !$0.replayed }.count, 1)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
        XCTAssertEqual(registrations.count, 1)
    }

    func testPC30LastCreditRemainsLiveAtZeroRemaining() throws {
        caps = ["open-a": 1]
        let receipt = try accepted()
        XCTAssertEqual(receipt.remaining, 0)
        advance(299)
        XCTAssertTrue(service().reconcile(activity: receipt.grant.activityName, callback: .start))
        XCTAssertTrue(shielded.isEmpty)
        XCTAssertEqual(service().preview(target: "app").grant?.remaining(at: instant), 1)
        advance(1)
        _ = service().reconcile(activity: receipt.grant.activityName, callback: .warning)
        XCTAssertEqual(shielded, ["app"])
    }

    func testPC23PC31RepeatedRequestsReturnUnchangedGrantAcrossProcessRestart() throws {
        let first = try accepted("first")
        advance(100)
        let next = try accepted("different-surface", using: service(ledger()))
        XCTAssertTrue(next.replayed)
        XCTAssertEqual(first.grant, next.grant)
        XCTAssertEqual(next.grant.remaining(at: instant), 200)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
    }

    func testPC23ExpiredOperationCannotRecreateAccess() throws {
        let receipt = try accepted("once")
        advance(300)
        _ = service().reconcile(activity: receipt.grant.activityName, callback: .warning)
        denied(.staleOperation, op: "once")
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
    }

    func testPC32SessionScheduleAndExhaustedTimeLimitDenyWithoutCharge() throws {
        for _ in ["session", "schedule", "exhausted-time-limit"] {
            block = .otherContributor
            denied(.otherContributor)
        }
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 0)
        XCTAssertTrue(registrations.isEmpty); XCTAssertTrue(released.isEmpty)
    }

    func testPC33UnprovenCategoryOverlapDeniesWithoutCharge() throws {
        block = .categoryOverlap
        denied(.categoryOverlap)
        XCTAssertEqual(service().preview(target: "app").blockedBy, .categoryOverlap)
        XCTAssertEqual(FlintOpenCreditBlock.categoryOverlap.message,
                       "Another category rule may still block this app; no credit used.")
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 0)
        XCTAssertTrue(released.isEmpty)
    }

    func testPC34SharedMinimumCapAndRenameNeverReset() throws {
        caps = ["open-a": 2, "open-b": 5]
        let first = try accepted()
        XCTAssertEqual(first.grant.coveredCauses, ["open-a", "open-b"])
        XCTAssertEqual(service().preview(target: "app").cap, 2)
        advance(300); _ = service().reconcile()
        caps = ["renamed-a": 2, "open-b": 5]
        XCTAssertEqual(try accepted().remaining, 0)
        XCTAssertEqual(try state().openCredits.counters.count, 1)
    }

    func testPC35MidnightClipsAndReshieldsBeforeNewPeriodAcceptance() throws {
        now = ISO8601DateFormatter().date(from: "2026-10-28T23:58:00Z")!
        let first = try accepted()
        XCTAssertEqual(first.grant.duration, 120)
        XCTAssertEqual(first.grant.deadline, calendar.dateInterval(of: .day, for: now)?.end)
        advance(120)
        let second = try accepted()
        XCTAssertEqual(second.grant.duration, 300)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
        let reshield = try XCTUnwrap(order.firstIndex(of: "reshield"))
        XCTAssertLessThan(reshield, order.lastIndex(of: "register")!)
        XCTAssertNotEqual(first.grant.activityName, second.grant.activityName)
    }

    func testPC36OffOnDeleteRecreateRetainsTargetCount() throws {
        let first = try accepted()
        advance(300); _ = service().reconcile(activity: first.grant.activityName, callback: .warning)
        caps = [:]
        denied(.notOpenLimited)
        caps = ["recreated": 3]
        XCTAssertEqual(try accepted().remaining, 1)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 2)
    }

    func testPC37MissingInitializedCounterNeverBecomesZero() throws {
        _ = try state()
        _ = ledger().transaction { $0.openCredits.counters["app"] = nil }
        denied(.unavailable)
        XCTAssertNil(service().preview(target: "app").remaining)
        XCTAssertTrue(released.isEmpty)
    }

    func testPC37CorruptNegativeCounterAndUnavailableContextDeny() throws {
        _ = try state()
        _ = ledger().transaction { $0.openCredits.counters["app"]?.used = -1 }
        denied(.unavailable)
        unavailableContext = true
        denied(.unavailable)
        XCTAssertTrue(registrations.isEmpty)
    }

    func testPC37UnknownSchemaAndDeletedInitializedLedgerDeny() throws {
        _ = try state()
        let file = directory.appendingPathComponent("ledger.json")
        try Data(#"{"version":999}"#.utf8).write(to: file)
        denied(.unavailable)
        try FileManager.default.removeItem(at: file)
        denied(.unavailable)
        XCTAssertTrue(released.isEmpty)
    }

    func testPC37BusyLedgerDeniesWithoutReleaseOrDefault() throws {
        _ = try state()
        let fd = open(directory.appendingPathComponent("ledger.lock").path, O_RDWR)
        XCTAssertGreaterThanOrEqual(fd, 0); XCTAssertEqual(flock(fd, LOCK_EX), 0)
        defer { flock(fd, LOCK_UN); close(fd) }
        var ticks = 0.0
        let clock = FlintClock(now: { self.now }, monotonic: { ticks += 1; return ticks },
                               bootSessionID: { self.boot }, calendar: { self.calendar })
        let busy = FlintProtectionLedger(directory: directory, clock: clock, lockTimeout: 0,
                                          initializedMarker: { self.marker }, setInitializedMarker: { _ in })
        denied(.unavailable, using: service(busy))
        XCTAssertTrue(released.isEmpty)
    }

    func testPC38RotatedIdentityKeepsOldCountAndNoMidPeriodPool() throws {
        _ = try accepted()
        targets = ["rotated"]
        XCTAssertEqual(service().accept(target: "rotated", operationID: "rotate"), .failure(.denied(.identityNeedsReselection)))
        let stored = try state()
        XCTAssertEqual(stored.openCredits.counters["app"]?.used, 1)
        XCTAssertNil(stored.openCredits.counters["rotated"])
        XCTAssertTrue(stored.openCredits.pendingTargets.contains("rotated"))
    }

    func testNonpositiveCapDoesNotChargeOrRegister() throws {
        for cap in [0, -2] { caps = ["open-a": cap]; denied(.exhausted) }
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 0)
        XCTAssertTrue(registrations.isEmpty)
    }

    func testCategoriesWithoutIndividualTargetsNeverReceiveCredits() throws {
        caps = [:]; targets = []
        denied(.notOpenLimited)
        XCTAssertTrue(try state().openCredits.counters.isEmpty)
        XCTAssertTrue(registrations.isEmpty); XCTAssertTrue(released.isEmpty)
    }

    func testCommitFailureAfterRegistrationDoesNotReleaseOrPersistACharge() throws {
        _ = try state()
        let file = directory.appendingPathComponent("ledger.json")
        let saved = directory.appendingPathComponent("before.json")
        let failing = FlintOpenCreditService(ledger: ledger(), clock: clock,
            context: { .init(coveringCaps: self.caps, targetKeys: self.targets) },
            register: { grant, remaining in
                self.registrations[grant.activityName] = remaining
                try FileManager.default.moveItem(at: file, to: saved)
                try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
            }, registered: { self.registrations[$0] != nil }, release: { _ in XCTFail("no durable commit") },
            reshield: { _ in }, stop: { self.registrations[$0] = nil })
        denied(.unavailable, using: failing)
        XCTAssertTrue(registrations.isEmpty)
        try FileManager.default.removeItem(at: file)
        try FileManager.default.moveItem(at: saved, to: file)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 0)
        XCTAssertTrue(try state().openCredits.grants.isEmpty)
    }

    func testCommittedGrantRecoversAfterDeathBeforeNativeReleaseWithoutSecondCharge() throws {
        let result = ledger().transaction { state in
            state.acceptOpenCredit(target: "app", operationID: "crash", coveringCaps: self.caps,
                targetKeys: self.targets, at: self.instant, blocking: { nil }, register: { grant in
                    self.registrations[grant.activityName] = grant.duration
                }, reshield: { _ in })
        }
        let first = try result.get().get()
        XCTAssertTrue(released.isEmpty)
        advance(50)
        let retry = try accepted("crash")
        XCTAssertTrue(retry.replayed)
        XCTAssertEqual(retry.grant, first.grant)
        XCTAssertEqual(retry.grant.remaining(at: instant), 250)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
    }

    func testPC75RegistrationFailureSpendsNothingAndNeverReleases() throws {
        refuses = true
        denied(.registrationFailed)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 0)
        XCTAssertTrue(try state().openCredits.grants.isEmpty)
        XCTAssertTrue(released.isEmpty)
    }

    func testPC76EarlyDuplicateAndMissedWarningsAreIdempotent() throws {
        let first = try accepted()
        advance(100)
        _ = service().reconcile(activity: first.grant.activityName, callback: .warning)
        XCTAssertTrue(shielded.isEmpty)
        XCTAssertEqual(registrations[first.grant.activityName], 200)
        advance(800)
        _ = service().reconcile(activity: first.grant.activityName, callback: .end)
        _ = service().reconcile(activity: first.grant.activityName, callback: .warning)
        XCTAssertEqual(shielded, ["app"])
        XCTAssertEqual(try state().operation(first.grant.operationID + ".expiry")?.result, "missed_warning_recovered")
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
    }

    func testMissingCarrierRepairFailureClosesWithoutRefund() throws {
        _ = try accepted()
        registrations = [:]; refuses = true
        _ = service().reconcile()
        XCTAssertEqual(shielded, ["app"])
        XCTAssertTrue(try state().openCredits.grants.isEmpty)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
    }

    func testPC54PC55WallJumpsDoNotRefreshOrExtendCredits() throws {
        let first = try accepted()
        advance(100); now = now.addingTimeInterval(3 * 86_400)
        _ = service().reconcile()
        XCTAssertEqual(service().preview(target: "app").grant?.remaining(at: instant), 200)
        now = now.addingTimeInterval(-6 * 86_400)
        _ = service().reconcile()
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
        XCTAssertEqual(first.grant.remaining(at: instant), 200)
        XCTAssertEqual(try state().openCredits.resetRemaining, 12 * 3600 - 100)
    }

    func testPC59PC60RebootClosesAccessAndPreservesQuotaRemaining() throws {
        _ = try accepted()
        advance(100); _ = service().reconcile()
        let reset = try state().openCredits.resetRemaining
        boot = "boot-b"; monotonic = 10; now = now.addingTimeInterval(5 * 86_400)
        _ = service().reconcile()
        XCTAssertEqual(shielded, ["app"])
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 1)
        XCTAssertEqual(try state().openCredits.resetRemaining, reset)
    }

    func testSavedQuotaZoneAndDSTBoundaryUseElapsedTime() throws {
        var zone = calendar; zone.timeZone = TimeZone(identifier: "America/New_York")!
        now = ISO8601DateFormatter().date(from: "2026-11-01T04:00:00Z")! // fall-back midnight
        var stored = FlintLedgerState(initializedAt: now)
        stored.importLegacyOpenCredits(.absent, targetKeys: ["app"], established: false, at: instant, calendar: zone)
        XCTAssertEqual(stored.openCredits.resetRemaining, 25 * 3600)
        XCTAssertEqual(stored.openCredits.quotaZone, "America/New_York")
    }

    func testCarrierMathAndUniqueNames() throws {
        for remaining in [1, 120, 300] {
            let plan = FlintOpenCreditCarrierPlan(start: now, remaining: Double(remaining))
            XCTAssertEqual(plan.warningTime.second, 900 - remaining)
            XCTAssertEqual(calendar.date(from: plan.intervalEnd)!.timeIntervalSince(calendar.date(from: plan.intervalStart)!), 900)
        }
        let grant = try accepted().grant
        XCTAssertTrue(FlintOpenCredit.isCarrier(grant.activityName))
        XCTAssertEqual(grant.activityName.count, "flint.open.".count + 16)
        XCTAssertFalse(FlintOpenCredit.isCarrier("flint.openLimit.rule"))
        XCTAssertFalse(FlintOpenCredit.isCarrier("flint.open.unknown"))
    }

    func testPreviewNeverSpendsRegistersOrReleases() throws {
        XCTAssertEqual(service().preview(target: "app").remaining, 3)
        XCTAssertEqual(service().preview(target: "app").cap, 3)
        XCTAssertTrue(registrations.isEmpty); XCTAssertTrue(released.isEmpty)
        XCTAssertEqual(try state().openCredits.counters["app"]?.used, 0)
    }
}
