import XCTest
@testable import FlintCore

final class FlintBreakAuthorizationTests: XCTestCase {
    private typealias Auth = FlintBreakAuthorization
    private typealias Target = FlintBreakScope.Target
    private let a = Target(kind: .application, token: Data("A".utf8))
    private let b = Target(kind: .application, token: Data("B".utf8))
    private let category = Target(kind: .category, token: Data("category".utf8))
    private let wall = Date(timeIntervalSince1970: 1_800_000_000)

    private func instant(_ mono: Double = 1000, boot: String? = "boot-1", wallShift: Double = 0) -> FlintLedgerInstant {
        .init(wall: wall.addingTimeInterval(mono - 1000 + wallShift), monotonic: mono, bootID: boot)
    }
    private func clock(_ now: FlintLedgerInstant) -> FlintClock {
        .init(now: { now.wall }, monotonic: { now.monotonic }, bootSessionID: { now.bootID },
              calendar: { Calendar.current })
    }
    private func request(_ id: String = "op", targets: Set<Target>? = nil) -> Auth.Request {
        .init(operationID: id, scope: .init(targets: targets ?? [a]))
    }
    private func contributor(_ id: String = "cause", tier: BreakLevel = .harder, count: Int = 0,
                             targets: Set<Target>? = nil, end: Double? = 10_000,
                             revision: Int = 1, pending: FlintLedgerState.PendingWait? = nil,
                             timeKnown: Bool = true) -> FlintBreakContributor {
        .init(cause: .init(causeID: id, occurrenceID: id + ".occurrence", revision: revision,
                          kind: .session, tier: tier, isActive: true),
              selection: .blockList(.init(targets: targets ?? [a, b])),
              logicalEnd: end.map { instant($0) }, timeKnown: timeKnown,
              harder: tier == .harder ? .init(committedCount: count, pending: pending) : nil)
    }
    private func pending(_ contributors: [FlintBreakContributor], request: Auth.Request,
                         at: FlintLedgerInstant = .init(wall: Date(timeIntervalSince1970: 1_800_000_000), monotonic: 1000, bootID: "boot-1")) -> [FlintBreakContributor] {
        let scope = Auth.waitScope(request: request, contributors: contributors)
        return contributors.map { item in
            guard let harder = item.harder else { return item }
            return .init(cause: item.cause, selection: item.selection, logicalEnd: item.logicalEnd,
                         timeKnown: item.timeKnown, harder: .init(committedCount: harder.committedCount,
                         pending: .init(operationID: request.operationID, kind: "break", scope: scope,
                                        requestedAt: at, wait: FlintProtectionPolicy.harderWait(forCount: harder.committedCount))))
        }
    }
    private func commit(_ contributors: [FlintBreakContributor], request: Auth.Request? = nil,
                        at: FlintLedgerInstant? = nil, grants: [FlintBreakGrant] = [],
                        confirmed: Bool = true) -> Auth.CommitResult {
        Auth.commitPlan(contributors: contributors, existingGrants: grants, request: request ?? self.request(),
                        clock: clock(at ?? instant()), grantID: "grant-" + (request?.operationID ?? "op"), confirmed: confirmed)
    }
    private func plan(_ result: Auth.CommitResult, file: StaticString = #filePath, line: UInt = #line) throws -> Auth.CommitPlan {
        guard case let .commit(plan) = result else { XCTFail("Expected plan, got \(result)", file: file, line: line); throw TestError.noPlan }
        return plan
    }
    private enum TestError: Error { case noPlan }

    func testPC18SuccessiveHarderBreaksEscalateAndEasyDoesNotCount() throws {
        var count = 0, now = instant(), grants: [FlintBreakGrant] = []
        for (index, delay) in [60.0, 300, 900, 900].enumerated() {
            let req = request("op-\(index)")
            let causes = [contributor(count: count)]
            XCTAssertEqual(Auth.decide(contributors: causes, existingGrants: grants, request: req, clock: clock(now)),
                           .wait(seconds: delay, harderCauses: [.init(causes[0].cause!)]))
            let waiting = pending(causes, request: req, at: now)
            now = instant(now.monotonic + delay)
            let accepted = try plan(commit(waiting, request: req, at: now, grants: grants))
            XCTAssertEqual(accepted.increments.count, 1)
            XCTAssertEqual(accepted.increments[0].expectedCount, count)
            count = accepted.increments[0].nextCount
            grants.append(accepted.grant)
            now = instant(now.monotonic + 300)
        }
        let easy = try plan(commit([contributor(tier: .easy, count: count)], request: request("easy"), at: now, grants: grants))
        XCTAssertTrue(easy.increments.isEmpty)
        XCTAssertEqual(count, 4)
    }

    func testPC19MaximumWaitAndEachHarderCauseCountedOnce() throws {
        let causes = [contributor("first"), contributor("second", count: 2), contributor("easy", tier: .easy)]
        XCTAssertEqual(Auth.decide(contributors: causes, existingGrants: [], request: request(), clock: clock(instant())),
                       .wait(seconds: 900, harderCauses: [.init(causes[0].cause!), .init(causes[1].cause!)]))
        let waiting = pending(causes, request: request())
        XCTAssertEqual(commit(waiting, at: instant(1899)), .waitNotMatured)
        let accepted = try plan(commit(waiting, at: instant(1900)))
        XCTAssertEqual(accepted.increments.map(\.nextCount), [1, 3])
        XCTAssertEqual(accepted.grant.causes.count, 3)
        XCTAssertEqual(commit(waiting, at: instant(1900), grants: [accepted.grant]), .replay(accepted.grant, remaining: 300))
        XCTAssertEqual(accepted.increments.map(\.ledgerKey), ["first", "second"])
    }

    func testHardcoreDeniesWithoutACommitPlan() {
        let causes = [contributor(tier: .easy), contributor("strict", tier: .hardcore)]
        XCTAssertEqual(Auth.decide(contributors: causes, existingGrants: [], request: request(), clock: clock(instant())), .deny(.hardcore))
        XCTAssertEqual(commit(causes), .denied(.hardcore))
        XCTAssertEqual(Auth.Denial.hardcore.message, "Hardcore allows no breaks — Emergency Pass is separate")
    }

    func testUnknownContributorCounterAndLogicalTimeDenyWithoutCharge() {
        let unknown = FlintBreakContributor(cause: nil, selection: nil, logicalEnd: nil, timeKnown: false)
        XCTAssertEqual(commit([contributor(tier: .easy), unknown]), .denied(.unavailable))
        XCTAssertEqual(commit([contributor(timeKnown: false)]), .denied(.unavailable))
        XCTAssertEqual(commit([contributor(end: nil)]), .denied(.unavailable))
        var finiteEasy = contributor(tier: .easy).cause!
        finiteEasy.end = wall.addingTimeInterval(100)
        XCTAssertEqual(commit([.init(cause: finiteEasy, selection: contributor().selection,
                                    logicalEnd: nil, timeKnown: true)]), .denied(.unavailable))
        XCTAssertNoThrow(try plan(commit([contributor(tier: .easy, end: nil)])))
        for count in [-1, Int.max] { XCTAssertEqual(commit([contributor(count: count)]), .denied(.unavailable)) }
        let missingCount = FlintBreakContributor(cause: contributor().cause, selection: contributor().selection,
                                                logicalEnd: instant(2000), timeKnown: true)
        XCTAssertEqual(commit([missingCount]), .denied(.unavailable))
        XCTAssertEqual(Auth.decide(contributors: nil, existingGrants: [], request: request(), clock: clock(instant())), .deny(.unavailable))
        XCTAssertEqual(Auth.decide(contributors: [], existingGrants: nil, request: request(), clock: clock(instant())), .deny(.unavailable))
    }

    func testDeadlineCappedByEarliestLogicalEndAndIgnoresWallEdits() throws {
        let causes = [contributor("first", tier: .easy, end: 1200), contributor("second", tier: .easy, end: 1100)]
        let accepted = try plan(commit(causes, at: instant(1000, wallShift: -86_400)))
        XCTAssertEqual(accepted.grant.deadline.monotonic, 1100)
        XCTAssertEqual(accepted.grant.deadline.wall, instant(1100, wallShift: -86_400).wall)
        XCTAssertEqual(accepted.grant.remaining(at: instant(1050, wallShift: 86_400)), 50)
        XCTAssertEqual(accepted.grant.remaining(at: instant(1099.9)), 0.1, accuracy: 0.00001)
        XCTAssertEqual(accepted.grant.remaining(at: instant(1100)), 0)
        XCTAssertEqual(accepted.grant.remaining(at: instant(1200)), 0)
    }

    func testPC24TargetAOnlyLeavesBOutsideGrant() throws {
        let cause = contributor(tier: .easy)
        let resolution = FlintBreakScopeResolution.resolve(request: request().scope, contributors: [cause])
        XCTAssertEqual(resolution.blocking[0].targets, [a])
        XCTAssertEqual(resolution.releasable.targets, [a])
        let grant = try plan(commit([cause])).grant
        XCTAssertTrue(grant.authorizes(cause.cause!, target: a, at: instant()))
        XCTAssertFalse(grant.authorizes(cause.cause!, target: b, at: instant()))
        XCTAssertFalse(grant.authorizes(contributor(tier: .easy, revision: 2).cause!, target: a, at: instant()))
        XCTAssertFalse(grant.authorizes(contributor(tier: .harder).cause!, target: a, at: instant()))
        XCTAssertFalse(grant.authorizes(contributor("new", tier: .easy).cause!, target: a, at: instant()))
    }

    func testPC24OpaqueCategoryMustBeExplicitAndIsReportedWhole() throws {
        let causes = [contributor(tier: .easy, targets: [category])]
        XCTAssertEqual(commit(causes), .denied(.unavailable), "cannot infer app A belongs to the category")
        let req = request(targets: [category])
        let resolution = FlintBreakScopeResolution.resolve(request: req.scope, contributors: causes)
        XCTAssertEqual(resolution.wholeCategories, [category])
        let grant = try plan(commit(causes, request: req)).grant
        XCTAssertEqual(grant.scope.wholeCategories, [category])
        XCTAssertFalse(grant.authorizes(causes[0].cause!, target: a, at: instant()))
        let independent = contributor("independent", tier: .hardcore, targets: [b])
        XCTAssertEqual(commit(causes + [independent], request: req), .denied(.hardcore))
    }

    func testReplayReturnsSameGrantEvenAfterExpiryOrStateUnavailable() throws {
        let grant = try plan(commit([contributor(tier: .easy)])).grant
        XCTAssertEqual(Auth.decide(contributors: nil, existingGrants: [grant], request: request(), clock: clock(instant(1100))), .replay(grant, remaining: 200))
        XCTAssertEqual(Auth.decide(contributors: nil, existingGrants: [grant], request: request(), clock: clock(instant(5000))), .replayExpired(grant))
        XCTAssertEqual(Auth.decide(contributors: nil, existingGrants: [grant], request: request(), clock: clock(instant(1100, boot: "new"))), .replayExpired(grant))
        XCTAssertEqual(Auth.commitPlan(contributors: nil, existingGrants: [grant], request: request(),
                                      clock: clock(instant(5000)), grantID: "new", confirmed: true), .replay(grant, remaining: 0))
        XCTAssertEqual(Auth.commitPlan(contributors: nil, existingGrants: [grant], request: request(),
                                      clock: clock(instant(1100, boot: "new")), grantID: "new", confirmed: true), .replay(grant, remaining: 0))
        XCTAssertEqual(grant.remaining(at: instant(5000)), 0)
    }

    func testNoStackingReturnsRemainingAndDisjointScopeCanProceed() throws {
        let causes = [contributor(tier: .easy)]
        let grant = try plan(commit(causes)).grant
        XCTAssertEqual(commit(causes, request: request("second"), at: instant(1020), grants: [grant]), .denied(.activeGrant(remaining: 280)))
        XCTAssertNoThrow(try plan(commit(causes, request: request("other", targets: [b]), grants: [grant])))
        XCTAssertNoThrow(try plan(commit(causes, request: request("after"), at: instant(1300), grants: [grant])))
    }

    func testRebootEndsAccessButSameBootProcessRestartKeepsRemaining() throws {
        let grant = try plan(commit([contributor(tier: .easy)])).grant
        XCTAssertEqual(grant.remaining(clock: clock(instant(1100))), 200)
        XCTAssertEqual(grant.remaining(at: instant(1100, boot: "boot-2")), 0)
        XCTAssertEqual(grant.remaining(at: instant(1100, boot: nil)), 0)
        XCTAssertEqual(grant.remaining(at: instant(999)), 0)
        XCTAssertEqual(commit([contributor(tier: .easy)], at: instant(1100, boot: nil)), .denied(.unavailable))
        XCTAssertEqual(commit([contributor(tier: .easy)], at: instant(1100, boot: "boot-2")), .denied(.unavailable))
    }

    func testWaitRequiresExactScopeRevisionOperationAndFinalConfirmation() {
        let causes = [contributor()]
        let waiting = pending(causes, request: request())
        XCTAssertEqual(commit(waiting, at: instant(1059.9)), .waitNotMatured)
        XCTAssertEqual(commit(waiting, at: instant(1060), confirmed: false), .confirmationRequired)
        XCTAssertEqual(commit(causes, at: instant(1060)), .pendingMismatch)
        XCTAssertEqual(commit(waiting, request: request("different"), at: instant(1060)), .pendingMismatch)
        XCTAssertEqual(commit(waiting, request: request(targets: [b]), at: instant(1060)), .pendingMismatch)
        let revised = contributor(revision: 2, pending: waiting[0].harder?.pending)
        XCTAssertEqual(commit([revised], at: instant(1060)), .pendingMismatch)
        let rebootedWait = pending(causes, request: request(), at: instant(100, boot: "previous"))
        XCTAssertEqual(commit(rebootedWait, at: instant(1060)), .pendingMismatch)
        XCTAssertEqual(commit([contributor(tier: .easy)], confirmed: false), .confirmationRequired)
    }

    func testDuplicateContributorsFailClosedInsteadOfCountingTwice() {
        XCTAssertEqual(commit([contributor(), contributor()]), .denied(.unavailable))
    }

    func testAllowListDoesNotOverrideAnotherCauseOrGrantDomains() throws {
        let allow = FlintBreakContributor(cause: contributor(tier: .easy).cause,
            selection: .allowList(allowedApplications: [b]), logicalEnd: instant(2000), timeKnown: true)
        XCTAssertNoThrow(try plan(commit([allow])))
        XCTAssertEqual(commit([allow], request: request(targets: [b])), .denied(.unavailable))
        XCTAssertEqual(commit([allow, contributor("strict", tier: .hardcore)]), .denied(.hardcore))
        XCTAssertEqual(commit([allow], request: request(targets: [category])), .denied(.unavailable))
    }

    func testFixtureDecodesRoundTripsAndOptionalAbsenceIsCompatible() throws {
        // Local verification forwards TEST_RUNNER_TZ to the test host, not just xcodebuild.
        if let requested = ProcessInfo.processInfo.environment["TZ"] {
            let zone = try XCTUnwrap(TimeZone(identifier: requested))
            XCTAssertEqual(TimeZone.current.secondsFromGMT(for: wall), zone.secondsFromGMT(for: wall))
            print("R23 timezone verified: \(requested) (Foundation: \(TimeZone.current.identifier))")
        }
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures/Breaks/ordinary-grant-v1.json")
        let bytes = try Data(contentsOf: url)
        let decoder = FlintProtectionLedger.makeDecoder()
        let grant = try decoder.decode(FlintBreakGrant.self, from: bytes)
        XCTAssertEqual(grant.version, 1)
        XCTAssertEqual(grant.operationID, "fixture-operation")
        XCTAssertEqual(grant.scope.targets, [a])
        XCTAssertEqual(grant.remaining(at: grant.acceptedAt), 300)
        let encoder = FlintProtectionLedger.makeEncoder()
        exportFixture("ordinary-grant-v1.json", bytes: try encoder.encode(grant))
        XCTAssertEqual(try decoder.decode(FlintBreakGrant.self, from: encoder.encode(grant)), grant)
        // Fixture bytes are exactly the ledger encoder's shape, not a separate ISO-8601 codec.
        XCTAssertEqual(try JSONSerialization.jsonObject(with: bytes) as? NSDictionary,
                       try JSONSerialization.jsonObject(with: encoder.encode(grant)) as? NSDictionary)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        object["version"] = 99
        XCTAssertThrowsError(try decoder.decode(FlintBreakGrant.self, from: JSONSerialization.data(withJSONObject: object)))
        object["version"] = 1; object.removeValue(forKey: "causes")
        XCTAssertThrowsError(try decoder.decode(FlintBreakGrant.self, from: JSONSerialization.data(withJSONObject: object)))
    }

    func testReplayRejectsDifferentRequestScope() throws {
        let grant = try plan(commit([contributor(tier: .easy)])).grant
        let changed = request(targets: [b])
        XCTAssertEqual(Auth.decide(contributors: nil, existingGrants: [grant], request: changed, clock: clock(instant())), .deny(.unavailable))
        XCTAssertEqual(commit([contributor(tier: .easy)], request: changed, grants: [grant]), .denied(.unavailable))
    }

    func testOutOfScopeHardcoreDoesNotDenyButUnknownTimeStillFailsClosed() throws {
        let causes = [contributor(tier: .easy, targets: [a]), contributor("strict", tier: .hardcore, targets: [b])]
        XCTAssertNoThrow(try plan(commit(causes)))
        let unknown = contributor("other", tier: .easy, targets: [b], timeKnown: false)
        XCTAssertEqual(commit([causes[0], unknown]), .denied(.unavailable))
    }

    func testOpaqueCategoryAndTokenCannotStack() throws {
        let categoryCause = contributor(tier: .easy, targets: [category])
        let grant = try plan(commit([categoryCause], request: request(targets: [category]))).grant
        XCTAssertEqual(commit([contributor(tier: .easy)], request: request("second"), grants: [grant]),
                       .denied(.activeGrant(remaining: 300)))
        XCTAssertEqual(Auth.Denial.activeGrant(remaining: 300).message, "A break is already active")
    }

    func testEmptyAndAggregateOversizedScopesAreUnavailable() {
        XCTAssertEqual(commit([contributor(tier: .easy)], request: request(targets: [])), .denied(.unavailable))
        XCTAssertTrue(Auth.waitScope(request: request(targets: []), contributors: []).isEmpty)
        let targets = Set((0..<5).map { Target(kind: .application, token: Data(repeating: UInt8($0), count: 65_536)) })
        XCTAssertEqual(commit([contributor(tier: .easy, targets: targets)], request: request(targets: targets)), .denied(.unavailable))
    }

    func testCorruptExistingGrantAndMalformedAllowListAreUnavailable() throws {
        let valid = try plan(commit([contributor(tier: .easy)])).grant
        let corrupt = FlintBreakGrant(id: valid.id, operationID: valid.operationID, scope: valid.scope,
                                     causes: valid.causes, acceptedAt: valid.acceptedAt, deadline: instant(1400))
        XCTAssertEqual(commit([contributor(tier: .easy)], request: request("new"), grants: [corrupt]), .denied(.unavailable))
        let malformed = FlintBreakContributor(cause: contributor(tier: .easy).cause,
            selection: .allowList(allowedApplications: [category]), logicalEnd: instant(2000), timeKnown: true)
        XCTAssertEqual(commit([malformed]), .denied(.unavailable))
    }

    func testWaitShorterThanPersistedCountRequiresIsUnavailable() {
        let causes = [contributor(count: 2)]
        var wait = pending(causes, request: request())[0].harder!.pending!
        wait.wait = 60
        XCTAssertEqual(commit([contributor(count: 2, pending: wait)], at: instant(1900)), .denied(.unavailable))
    }

    func testTwoRulesCanShareWindowIdentityButKeepDistinctLedgerKeys() throws {
        let first = contributor("rule-a")
        var secondCause = contributor("rule-b").cause!
        secondCause.occurrenceID = first.cause!.occurrenceID
        let second = FlintBreakContributor(cause: secondCause, selection: first.selection,
            logicalEnd: first.logicalEnd, timeKnown: true, harder: .init())
        let causes = pending([first, second], request: request())
        let accepted = try plan(commit(causes, at: instant(1060)))
        XCTAssertEqual(accepted.increments.map(\.ledgerKey), ["rule-a", "rule-b"])
    }

    /// Historical phase-1 fixture used a test-only array before there was a shipping field.
    /// Decode that array separately; phase 2 tests the actual additive object in the real ledger.
    private struct LedgerEnvelope: Codable {
        let state: FlintLedgerState
        let ordinaryGrants: [FlintBreakGrant]?
        enum CodingKeys: String, CodingKey { case ordinaryGrants = "phase1OrdinaryGrants" }
        init(from decoder: Decoder) throws {
            // The old prototype never shipped. Preserve its evidence under a test-only key,
            // rather than teaching production to mistake it for valid runtime authority.
            state = try FlintLedgerState(from: decoder)
            ordinaryGrants = try decoder.container(keyedBy: CodingKeys.self)
                .decodeIfPresent([FlintBreakGrant].self, forKey: .ordinaryGrants)
        }
        func encode(to encoder: Encoder) throws {
            try state.encode(to: encoder)
            var values = encoder.container(keyedBy: CodingKeys.self)
            try values.encodeIfPresent(ordinaryGrants, forKey: .ordinaryGrants)
        }
    }

    func testLedgerEnvelopeAndOldV2FixtureUseProductionCodec() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/Breaks")
        let decoder = FlintProtectionLedger.makeDecoder(), encoder = FlintProtectionLedger.makeEncoder()
        let oldBytes = try Data(contentsOf: root.appendingPathComponent("ledger-v2-without-breaks.json"))
        let old = try decoder.decode(LedgerEnvelope.self, from: oldBytes)
        XCTAssertEqual(old.state.version, 2)
        XCTAssertNil(old.ordinaryGrants)
        XCTAssertEqual(try decoder.decode(FlintLedgerState.self, from: oldBytes), old.state)
        let bytes = try Data(contentsOf: root.appendingPathComponent("ledger-v2-with-break.json"))
        let current = try decoder.decode(LedgerEnvelope.self, from: bytes)
        XCTAssertEqual(current.state, old.state)
        XCTAssertEqual(current.ordinaryGrants?.count, 1)
        let roundTrip = try decoder.decode(LedgerEnvelope.self, from: encoder.encode(current))
        XCTAssertEqual(roundTrip.ordinaryGrants, current.ordinaryGrants)
        XCTAssertEqual(roundTrip.state, current.state)
        exportFixture("ledger-v2-with-break.json", bytes: try encoder.encode(current))
        exportFixture("ledger-v2-without-breaks.json", bytes: try encoder.encode(old))
    }

    /// Optional fixture regeneration from the production codec; no filesystem mutation in tests.
    private func exportFixture(_ name: String, bytes: Data) {
        if ProcessInfo.processInfo.environment["R23_EXPORT_FIXTURES"] == "1" {
            print("R23_FIXTURE \(name) \(bytes.base64EncodedString())")
        }
    }
}
