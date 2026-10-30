import XCTest
import FamilyControls
@testable import FlintCore

/// Actual status/pass-scope readers, with local records and injected clocks. No OS effects.
final class FlintSessionAuthorityProjectionTests: XCTestCase {
    private let initial = Date(timeIntervalSince1970: 1_793_206_800)
    private var wall: Date!
    private var monotonic: TimeInterval = 1000
    private var boot = "boot-1"
    private var suite: String!
    private var defaults: UserDefaults!
    private var group: FlintGroupStore!
    private var state: FlintLedgerState!

    override func setUp() {
        super.setUp()
        wall = initial; monotonic = 1000; boot = "boot-1"
        suite = "FlintSessionAuthorityProjectionTests.\(UUID())"
        defaults = UserDefaults(suiteName: suite)!
        group = FlintGroupStore(suiteName: suite)!
        state = .init(initializedAt: initial)
        state.importLegacyPass(.absent, at: .init(clock: clock))
        defaults.set(initial, forKey: FlintProtectionLedger.initializedMarkerKey)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private var clock: FlintClock {
        .init(now: { self.wall }, monotonic: { self.monotonic },
            bootSessionID: { self.boot }, calendar: { self.calendar })
    }

    private func session(name: String = "Focus") -> FlintActiveSession {
        .init(id: "Y", name: name, startedAt: initial, endsAt: initial.addingTimeInterval(1800),
            breakLevel: .hardcore, monitorName: "flint.session.Y", monotonicStart: 1000,
            bootSessionID: "boot-1", contractVersion: FlintActiveSession.currentContractVersion,
            plannedDuration: 1800, startRequestID: "start.Y")
    }

    private struct Targets: Codable {
        let sessionID: String
        let selection: FamilyActivitySelection
    }

    @discardableResult
    private func install() throws -> FlintActiveSession {
        let value = session().checkpointed(clock: clock)
        let selection = FamilyActivitySelection()
        try state.sessionAuthority?.install(value, selection: JSONEncoder().encode(selection), source: .manual,
            operationID: "start.Y", fingerprint: "parameters", expectedRevision: 0, at: .init(clock: clock))
        try state.sessionAuthority?.markApplied(value)
        group.saveActiveSession(value)
        defaults.set(try JSONEncoder().encode(Targets(sessionID: value.id, selection: selection)),
            forKey: "flint.activeSession.selection")
        return value
    }

    private func inputs(ledgerAvailable: Bool = true) -> FlintProtectionStatusBuilder.Inputs {
        FlintProtectionStatus.currentInputs(now: wall, defaults: defaults, quotaCalendar: calendar,
            environment: .init(authorized: { true }, registeredActivities: { ["flint.session.Y"] }, clock: clock,
                loadActiveSession: { self.group.loadActiveSession() },
                hasActiveSessionRecord: { self.group.hasActiveSessionRecord() },
                loadArmingHealth: { .init() }, loadLedgerState: { ledgerAvailable ? self.state : nil }))
    }

    private func scope() throws -> FlintPassScope {
        try FlintEmergencyPassScope.load(state: state, at: .init(clock: clock), defaults: defaults,
            calendar: calendar, migrating: false, hasThresholdShield: { _ in false })
    }

    func testMissingActiveKeyWithMarkerAndLiveGenerationMakesBothReadersUnavailableR20PC64PC66() throws {
        try install()
        group.clearActiveSession()
        XCTAssertTrue(group.isProtectionStateReadable())
        let observed = inputs()
        XCTAssertTrue(observed.recordUnreadable)
        XCTAssertEqual(FlintProtectionStatusBuilder.make(observed).state, .unavailable)
        XCTAssertThrowsError(try scope())
        XCTAssertNil(group.loadActiveSession(), "readers must not repair or release protection")
        XCTAssertFalse(try XCTUnwrap(state.sessionAuthority).provesIdle)
    }

    func testMissingLedgerNeverProjectsIdleOrProtection() throws {
        let observed = inputs(ledgerAvailable: false)
        XCTAssertTrue(observed.recordUnreadable)
        XCTAssertEqual(FlintProtectionStatusBuilder.make(observed).state, .unavailable)
    }

    func testLatestTombstoneSuppressesAResidualProjectionInBothReadersR20() throws {
        let value = try install()
        try state.sessionAuthority?.terminate(value, outcome: .completed, route: "completed", at: wall)
        let observed = inputs()
        XCTAssertFalse(observed.recordUnreadable)
        XCTAssertNil(observed.activeSession)
        XCTAssertTrue(try scope().sessions.isEmpty)
        XCTAssertEqual(group.loadActiveSession(), value, "read-only projection keeps source bytes")
    }

    func testLaterLedgerCheckpointWinsAnOlderProjectionAfterRebootR05PC54PC55() throws {
        let original = try install()
        wall = wall.addingTimeInterval(120); boot = "boot-2"; monotonic = 10
        let saved = original.checkpointed(clock: clock)
        try state.sessionAuthority?.checkpoint(saved) // crash before UserDefaults save
        wall = wall.addingTimeInterval(3600); monotonic += 1
        let observed = inputs()
        XCTAssertFalse(observed.recordUnreadable)
        XCTAssertEqual(observed.activeSession?.session.remainingCheckpoint, saved.remainingCheckpoint)
        XCTAssertEqual(observed.activeSession?.logicalEnd?.timeIntervalSince(wall), 1679)
        XCTAssertEqual(try scope().sessions.first?.remainingCheckpoint, saved.remainingCheckpoint)
        XCTAssertEqual(group.loadActiveSession(), original)
    }

    func testPostRebootUncertaintyPersistsInStatusAfterClockBecomesSteadyR05PC58() throws {
        let original = try install()
        wall = wall.addingTimeInterval(120); boot = "boot-2"; monotonic = 10
        let saved = original.checkpointed(clock: clock)
        try state.sessionAuthority?.checkpoint(saved)
        group.saveActiveSession(saved)
        wall = wall.addingTimeInterval(30); monotonic += 30
        let observed = inputs()
        XCTAssertEqual(observed.timeIntegrity, .unverifiable)
        XCTAssertTrue(FlintProtectionStatusBuilder.make(observed).degradedReasons.contains("time_uncertain"))
    }

    func testCurrentBootCheckpointWinsOlderBootProjectionDespiteWallRollbackR05PC55() throws {
        let original = try install()
        wall = wall.addingTimeInterval(1790); monotonic += 1790
        let nearExpiry = original.checkpointed(clock: clock)
        try state.sessionAuthority?.checkpoint(nearExpiry)
        wall = wall.addingTimeInterval(-3600); boot = "boot-2"; monotonic = 10
        let saved = nearExpiry.checkpointed(clock: clock)
        try state.sessionAuthority?.checkpoint(saved)
        wall = wall.addingTimeInterval(2); monotonic += 2
        let observed = inputs()
        XCTAssertFalse(observed.recordUnreadable)
        XCTAssertEqual(observed.activeSession?.session.remainingCheckpoint, saved.remainingCheckpoint)
        XCTAssertEqual(observed.activeSession?.logicalEnd?.timeIntervalSince(wall), 8)
        XCTAssertEqual(try scope().sessions.first?.logicalRemaining(clock: clock), 8)
        XCTAssertEqual(group.loadActiveSession(), original)
    }

    func testCheckpointOnlyAndNaturalEndObservationDoNotChangePassRevisionPC03PC10() throws {
        let original = try install()
        let first = try scope().revision
        wall = wall.addingTimeInterval(30); monotonic += 30
        let saved = original.checkpointed(clock: clock)
        try state.sessionAuthority?.checkpoint(saved)
        group.saveActiveSession(saved)
        XCTAssertEqual(try scope().revision, first)
        wall = initial.addingTimeInterval(1801); monotonic = 2801
        let expired = saved.checkpointed(clock: clock)
        try state.sessionAuthority?.checkpoint(expired)
        group.saveActiveSession(expired)
        let beforeObservation = try scope().revision
        let observed = expired.checkpointed(clock: clock, naturalEndObservedAt: wall)
        try state.sessionAuthority?.checkpoint(observed)
        group.saveActiveSession(observed)
        XCTAssertEqual(try scope().revision, beforeObservation)
    }

    func testRealCommitmentChangeChangesPassRevisionPC03PC10() throws {
        try install()
        let first = try scope().revision
        let edited = session(name: "Changed scope").checkpointed(clock: clock)
        try state.sessionAuthority?.checkpoint(edited)
        group.saveActiveSession(edited)
        XCTAssertNotEqual(try scope().revision, first)
    }
}
