import XCTest
@testable import FlintCore

final class FlintSessionAuthorityTests: XCTestCase {
    private let wall = Date(timeIntervalSince1970: 1_800_000_000)
    private var instant: FlintLedgerInstant { .init(wall: wall, monotonic: 50_000, bootID: "boot") }
    private func session(_ id: String) -> FlintActiveSession {
        FlintActiveSession(id: id, name: "Focus", startedAt: wall, endsAt: wall.addingTimeInterval(1800),
            breakLevel: .hardcore, monitorName: "flint.session.\(id)", monotonicStart: 50_000,
            bootSessionID: "boot", plannedDuration: 1800, startRequestID: "request.\(id)")
    }
    private func install(_ id: String, in authority: inout FlintSessionAuthority) throws {
        try authority.install(session(id), selection: Data("selection".utf8), source: .manual,
            operationID: "request.\(id)", fingerprint: "parameters", expectedRevision: authority.revision, at: instant)
        try authority.markApplied(session(id))
    }

    func testMissingActiveKeyCannotProveIdleWithoutLatestTombstoneR20PC64PC66() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        XCTAssertTrue(authority.provesIdle)
        try install("X", in: &authority)
        XCTAssertFalse(authority.provesIdle)
        try authority.terminate(session("X"), outcome: .completed, route: "completed", at: wall)
        XCTAssertTrue(authority.provesIdle)
        try install("Y", in: &authority)
        XCTAssertFalse(authority.provesIdle, "losing Y's defaults key cannot manufacture its tombstone")
        XCTAssertThrowsError(try authority.terminate(session("X"), outcome: .completed, route: "completed", at: wall)) {
            XCTAssertEqual($0 as? FlintSessionAuthorityError, .generationMismatch)
        }
        XCTAssertFalse(authority.provesIdle)
        XCTAssertEqual(authority.latest?.session.id, "Y")
    }

    func testInstallingStateRoundTripsEverythingNeededForInterruptedSessionSaveR25() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        try authority.install(session("X"), selection: Data("frozen selection".utf8), source: .shortcut,
            operationID: "request.X", fingerprint: "parameters", expectedRevision: 0, at: instant)
        let recovered = try JSONDecoder().decode(FlintSessionAuthority.self, from: JSONEncoder().encode(authority))
        XCTAssertEqual(recovered.latest?.phase, .installing)
        XCTAssertEqual(recovered.latest?.selection, Data("frozen selection".utf8))
        XCTAssertEqual(recovered.latest?.session, session("X"))
        XCTAssertEqual(recovered.acceptedStart("request.X")?.sessionID, "X")
        try authority.markApplied(session("X"))
        XCTAssertNil(authority.latest?.selection, "applied state cannot recover an unexplained missing key")
    }

    func testTerminalAcceptedResultSurvivesSerializationAndCannotBeAcceptedAgainPC03() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        try install("X", in: &authority)
        try authority.terminate(session("X"), outcome: .stopped, route: "manual_stop", at: wall)
        authority = try JSONDecoder().decode(FlintSessionAuthority.self, from: JSONEncoder().encode(authority))
        XCTAssertEqual(authority.acceptedStart("request.X")?.outcome, .stopped)
        XCTAssertEqual(authority.acceptedStart("request.X")?.endedAt, wall)
        XCTAssertEqual(authority.acceptedStart("request.X")?.endRoute, "manual_stop")
        XCTAssertThrowsError(try authority.install(session("X"), selection: Data(), source: .manual,
            operationID: "request.X", fingerprint: "parameters", expectedRevision: authority.revision, at: instant)) {
            XCTAssertEqual($0 as? FlintSessionAuthorityError, .alreadyAccepted)
        }
        XCTAssertTrue(authority.provesIdle)
    }

    func testConflictingOperationReuseIsRefusedPC03() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        try install("X", in: &authority)
        XCTAssertThrowsError(try authority.install(session("Y"), selection: Data(), source: .manual,
            operationID: "request.X", fingerprint: "different", expectedRevision: authority.revision, at: instant)) {
            XCTAssertEqual($0 as? FlintSessionAuthorityError, .conflictingReuse)
        }
        XCTAssertEqual(authority.latest?.session.id, "X")
    }

    func testExpectedRevisionIsCheckedAtInstallCommitPC10() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        let displayed = authority.revision
        try install("X", in: &authority)
        XCTAssertThrowsError(try authority.install(session("Y"), selection: Data(), source: .manual,
            operationID: "request.Y", fingerprint: "parameters", expectedRevision: displayed, at: instant)) {
            XCTAssertEqual($0 as? FlintSessionAuthorityError, .staleRevision)
        }
        XCTAssertNil(authority.acceptedStart("request.Y"))
    }

    func testShortcutHostRecreationReusesDurableTokenForTwoMinutes() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        let first = authority.requestToken(route: "shortcut", fingerprint: "parameters", at: instant)
        authority = try JSONDecoder().decode(FlintSessionAuthority.self, from: JSONEncoder().encode(authority))
        var later = instant
        later.monotonic += 119
        later.wall.addTimeInterval(3600) // monotonic retry age, no wall-clock invalidation
        XCTAssertEqual(authority.requestToken(route: "shortcut", fingerprint: "parameters", at: later), first)
        later.monotonic += 2
        XCTAssertNotEqual(authority.requestToken(route: "shortcut", fingerprint: "parameters", at: later), first)
    }

    func testTerminalOutcomeEndsHeuristicShortcutWindowImmediately() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        let token = authority.requestToken(route: "shortcut", fingerprint: "parameters", at: instant)
        let active = session("X")
        try authority.install(active, selection: Data(), source: .shortcut, operationID: token.operationID,
            fingerprint: "parameters", expectedRevision: token.expectedRevision, at: instant)
        try authority.markApplied(active)
        try authority.terminate(active, outcome: .stopped, route: "shortcut_stop", at: wall)
        XCTAssertNotEqual(authority.requestToken(route: "shortcut", fingerprint: "parameters", at: instant), token)
    }

    func testDuplicateFocusActivationKeepsTokenAfterEndUntilFocusOff() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        let token = authority.requestToken(route: "focus.activation", fingerprint: "parameters",
                                            at: instant, untilDeactivation: true)
        let active = session("X")
        try authority.install(active, selection: Data(), source: .focusFilter, operationID: token.operationID,
            fingerprint: "parameters", expectedRevision: token.expectedRevision, at: instant)
        try authority.markApplied(active)
        try authority.terminate(active, outcome: .completed, route: "completed", at: wall)
        var later = instant
        later.monotonic += 3600
        XCTAssertEqual(authority.requestToken(route: "focus.activation", fingerprint: "parameters",
                                               at: later, untilDeactivation: true), token)
        authority.deactivate(route: "focus.activation")
        XCTAssertNotEqual(authority.requestToken(route: "focus.activation", fingerprint: "parameters",
                                                  at: later, untilDeactivation: true), token)
    }

    func testRearmedGenerationRejectsPriorMonitorTombstonePC66() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        let old = session("X")
        authority.adopt(old)
        let updated = FlintActiveSession(id: old.id, name: old.name, startedAt: old.startedAt,
            endsAt: old.endsAt, breakLevel: old.breakLevel, monitorName: "flint.session.new")
        try authority.rearm(updated)
        XCTAssertThrowsError(try authority.terminate(old, outcome: .completed, route: "completed", at: wall))
        XCTAssertFalse(authority.provesIdle)
        try authority.terminate(updated, outcome: .completed, route: "completed", at: wall)
        XCTAssertTrue(authority.provesIdle)
        XCTAssertFalse(try authority.terminate(updated, outcome: .completed, route: "completed", at: wall))
    }

    func testAcceptedStartMemoryIsBoundedAndPrunedIDsCanStartFresh() throws {
        var authority = FlintSessionAuthority(initializedAt: wall)
        for index in 0...FlintSessionAuthority.startMemory { try install("\(index)", in: &authority) }
        XCTAssertEqual(authority.acceptedStarts.count, FlintSessionAuthority.startMemory)
        XCTAssertNil(authority.acceptedStart("request.0"))
        try install("0", in: &authority)
        XCTAssertEqual(authority.acceptedStarts.count, FlintSessionAuthority.startMemory)
        XCTAssertEqual(authority.latest?.session.id, "0")
    }

    func testFingerprintBindsEveryStartParameter() {
        let original = FlintSessionStartParameters(duration: 1800, tier: .easy, selection: "opaque",
            name: "Focus", source: .shortcut, origin: "shortcut", ownerID: nil, replacing: true)
        let changes: [(inout FlintSessionStartParameters) -> Void] = [
            { $0.duration += 60 }, { $0.tier = .hardcore }, { $0.selection = "other" },
            { $0.name = "Other" }, { $0.source = .pomodoro }, { $0.origin = "app_ui" },
            { $0.ownerID = "focus" }, { $0.replacing = false }
        ]
        for change in changes {
            var changed = original
            change(&changed)
            XCTAssertNotEqual(original.fingerprint, changed.fingerprint)
        }
    }
}
