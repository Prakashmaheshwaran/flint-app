import XCTest
@testable import FlintCore

/// ADR-012 §6 recovery drain. On a fresh install the rule domains are initialized only after
/// this policy gate succeeds, so a drain with nothing to recover must not demand a readable
/// scope (that would deadlock initialization). An accepted but unfinished installation is
/// different: its scope must never be inferred.
final class FlintEmergencyPassFreshInstallDrainTests: XCTestCase {
    private var directory: URL!
    private var marker: Date?
    private var now = Date(timeIntervalSince1970: 1_793_206_800)
    private var boot: String? = "boot-a"
    private var scopeReads = 0

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-pass-fresh-\(UUID())")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var clock: FlintClock {
        .init(now: { self.now }, monotonic: { 9_000 }, bootSessionID: { self.boot },
              calendar: { Calendar(identifier: .gregorian) })
    }

    private func ledger() -> FlintProtectionLedger {
        .init(directory: directory, clock: clock, initializedMarker: { self.marker },
              setInitializedMarker: { self.marker = $0 },
              migrate: { $0.importLegacyPass(.absent, at: $1) })
    }

    private enum Fault: Error { case injected }

    /// Only the seams the drain needs; every OS effect is a hard failure, and a scope read is
    /// counted before failing so "was the scope consulted?" is observable.
    private func controller() -> FlintEmergencyPassController {
        .init(environment: .init(ledger: { self.ledger() }, clock: clock,
            scope: { _, _ in
                self.scopeReads += 1
                throw Fault.injected // this test never authorizes a spend
            },
            registerCarrier: { _, _, _ in throw Fault.injected },
            terminate: { _, _ in throw Fault.injected },
            release: { _ in throw Fault.injected },
            resume: { _, _ in throw Fault.injected },
            disable: { _ in throw Fault.injected },
            emit: { _ in XCTFail("nothing to emit without a committed recovery") }))
    }

    /// One committed accepted start, left at `.installing` as after a crash before application.
    private func installPendingStart() throws {
        let result: Result<Void, FlintLedgerError> = ledger().transaction { state in
            var authority = try XCTUnwrap(state.sessionAuthority)
            try authority.install(Self.session(startingAt: self.now), selection: Data("pick".utf8),
                source: .manual, operationID: "pending-start", fingerprint: "parameters",
                expectedRevision: authority.revision, at: .init(clock: self.clock))
            state.sessionAuthority = authority
        }
        try result.get()
    }

    /// An idle ledger drains to a successful, inactive projection without reading the scope,
    /// even when every scope read would fail.
    func testFreshInstallDrainDoesNotRequireReadableScope() throws {
        let status = try controller().reconcile().get()
        XCTAssertEqual(scopeReads, 0, "initialization must not be blocked on a rule scope read")
        XCTAssertEqual(status.pass.eligibility, .nothingToRecover)
        XCTAssertEqual(status.pass.recoveryRemaining, 0)
        XCTAssertFalse(status.isAvailable)
        XCTAssertNil(status.recovery)
        // A migration ran on the fresh ledger, so this is a real install, not a phantom one.
        XCTAssertNotNil(marker)
    }

    /// A pending accepted installation keeps the conservative failure, decided from the ledger
    /// alone: no scope read may convert it into an inferred recovery.
    func testPendingInstallationRefusesWithoutInferringScope() throws {
        try installPendingStart()
        scopeReads = 0
        XCTAssertEqual(controller().reconcile(), .failure(.scopeUnavailable),
            "an accepted but unfinished installation must not have its scope inferred")
        XCTAssertEqual(scopeReads, 0, "the refusal is decided from the ledger, not a scope read")
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)
    }

    /// Once the accepted start has applied, the same ledger drains again without a scope read.
    func testAppliedSessionDrainReturnsToTheIdlePath() throws {
        try installPendingStart()
        let applied: Result<Void, FlintLedgerError> = ledger().transaction { state in
            var authority = try XCTUnwrap(state.sessionAuthority)
            let latest = try XCTUnwrap(authority.latest)
            try authority.markApplied(latest.session)
            state.sessionAuthority = authority
        }
        try applied.get()
        let status = try controller().reconcile().get()
        XCTAssertEqual(scopeReads, 0)
        XCTAssertEqual(status.pass.eligibility, .nothingToRecover)
    }

    private static func session(startingAt start: Date) -> FlintActiveSession {
        .init(id: "s", name: "Focus", startedAt: start, endsAt: start.addingTimeInterval(1_800),
              breakLevel: .hardcore, monitorName: "flint.session.pending",
              monotonicStart: 9_000, bootSessionID: "boot-a", plannedDuration: 1_800,
              startRequestID: "pending-start")
    }
}
