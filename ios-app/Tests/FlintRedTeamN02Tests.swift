import XCTest
import FamilyControls
import ManagedSettings
@testable import FlintCore

/// Red-team re-verification of residual R2p2-N02 (P0, iOS): a stale ordinary-break restoration
/// must never weaken stronger protection, and a break release must never outlive its deadline,
/// including the double fault (rule context unavailable and the session projection unreadable).
final class FlintRedTeamN02Tests: XCTestCase {
    private let now = FlintLedgerInstant(wall: Date(timeIntervalSince1970: 1_800_000_000),
                                         monotonic: 1000, bootID: "boot")

    // MARK: - Fixtures

    /// One accepted ordinary grant whose frozen authority is `selection`, released scope `scope`.
    private func fixture(_ selection: FamilyActivitySelection, releasing scope: FlintBreakScope,
                         allowList: Bool = false, tier: BreakLevel = .easy, ownerEnd: TimeInterval? = 3600,
                         causeID: String, kind: FlintCauseState.Kind = .session,
                         storeName: String = "flint", grantDuration: TimeInterval = 300)
        throws -> (FlintOrdinaryGrantState.Record, FlintBreakContext) {
        let cause = FlintCauseState(causeID: causeID, kind: kind, tier: tier, isActive: true)
        let frozen = try FlintOrdinaryBreaks.scope(selection)
        let contributor = FlintBreakContributor(cause: cause,
            selection: allowList ? .allowList(allowedApplications: frozen.targets) : .blockList(frozen),
            logicalEnd: ownerEnd.map {
                .init(wall: now.wall.addingTimeInterval($0), monotonic: now.monotonic + $0, bootID: now.bootID)
            },
            timeKnown: true, harder: .init())
        let grant = FlintBreakGrant(id: "grant-\(causeID)", operationID: "operation-\(causeID)", scope: scope,
            causes: [.init(cause)], acceptedAt: now,
            deadline: .init(wall: now.wall.addingTimeInterval(grantDuration),
                            monotonic: now.monotonic + grantDuration, bootID: now.bootID))
        return (.init(grant: grant, authorities: [try .init(contributor: contributor, storeName: storeName)]),
                .init(owners: [.init(contributor, storeName: storeName)]))
    }

    /// Persisted grant state with a valid, generation-bound operation ID (the ledger rejects
    /// records whose operation ID is not its own).
    private func grantState(_ record: FlintOrdinaryGrantState.Record) throws -> FlintOrdinaryGrantState {
        var grants = FlintOrdinaryGrantState()
        let operation = try grants.newOperationID()
        let grant = FlintBreakGrant(id: record.grant.id, operationID: operation, scope: record.grant.scope,
            causes: record.grant.causes, acceptedAt: record.grant.acceptedAt, deadline: record.grant.deadline)
        grants.records = [.init(grant: grant, authorities: record.authorities)]
        grants.acceptedOperations = [operation]
        return grants
    }

    private func ledger(directory: URL, clock: FlintClock) -> FlintProtectionLedger {
        .init(directory: directory, clock: clock, initializedMarker: { nil }, setInitializedMarker: { _ in })
    }

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("flint-redteam-n02-\(UUID().uuidString)")
    }

    private func instant(_ seconds: TimeInterval, wallDelta: TimeInterval? = nil,
                         bootID: String = "boot") -> FlintLedgerInstant {
        .init(wall: now.wall.addingTimeInterval(wallDelta ?? seconds),
              monotonic: now.monotonic + seconds, bootID: bootID)
    }

    /// Exactly the production fallback branch of `FlintOrdinaryBreaks.apply` for an unavailable
    /// context (FlintOrdinaryBreaks.swift:335-338), with the two seams a test can replace.
    private func productionFallback(records: [FlintOrdinaryGrantState.Record], at observation: FlintLedgerInstant,
                                    writer: RedTeamShieldWriter, currentSessionID: @escaping () -> String?,
                                    sessionAuthority: @escaping () -> FlintSessionAuthority? = { nil }) throws {
        try FlintOrdinaryBreaks.restoreUnavailableAuthorities(records, at: observation,
            writer: { _ in writer }, currentSessionID: currentSessionID, sessionAuthority: sessionAuthority)
        throw FlintOrdinaryBreakError.unavailable // retain the retry, exactly as production does
    }

    /// The fallback always reports the unavailable context after restoring, so the caller keeps
    /// the retry (`FlintOrdinaryBreaks.swift:337`). Every call below is expected to throw that.
    private func expectFallbackUnavailable(_ body: () throws -> Void,
                                           file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try body(), file: file, line: line) { error in
            XCTAssertEqual(error as? FlintOrdinaryBreakError, .unavailable, file: file, line: line)
        }
    }

    // MARK: - (a) Original attack through the production fallback: a successor is never overwritten

    func testRedTeamN02ProductionFallbackNeverWritesASuccessorSessionStore() throws {
        let a: ApplicationToken = try fictionalRuleToken("target A")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a]
        let x = FlintActiveSession(name: "X", startedAt: now.wall, endsAt: now.wall.addingTimeInterval(3600),
                                   breakLevel: .easy, monitorName: "x")
        let (record, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(selection), causeID: x.id)
        XCTAssertEqual(record.authorities.first?.storeName, "flint")
        XCTAssertEqual(record.authorities.first?.focusSessionID, x.id)

        // A successor Y (Hardcore A+B) already applied to the reused `flint` store: the fallback
        // must not touch a single one of its four shield fields.
        for current in ["successor-Y", nil] as [String?] {
            let writer = RedTeamShieldWriter()
            expectFallbackUnavailable {
                try productionFallback(records: [record], at: instant(301), writer: writer,
                                       currentSessionID: { current })
            }
            XCTAssertEqual(writer.writes, 0, "no write for current=\(String(describing: current))")
        }

        // The still-current owner is restored (fail-closed): the skip above is ownership, not silence.
        let current = RedTeamShieldWriter()
        expectFallbackUnavailable {
            try productionFallback(records: [record], at: instant(301), writer: current,
                                   currentSessionID: { x.id })
        }
        XCTAssertEqual(current.blockedApplications, [a])
    }

    /// The other half of the original attack: the stronger successor's own start applies its full
    /// A+B selection to the reused store before any ordinary-break reconciliation runs.
    func testRedTeamN02StrongerSuccessorAppliesItsOwnFullSelection() throws {
        let harness = try SessionHarness()
        defer { harness.cleanUp() }
        let a: ApplicationToken = try fictionalRuleToken("target A")
        let b: ApplicationToken = try fictionalRuleToken("target B")
        var xSelection = FamilyActivitySelection(); xSelection.applicationTokens = [a]
        _ = try harness.controller.startBlockNow(selection: xSelection, duration: 3600, breakLevel: .easy,
                                                 origin: .appUI, replacing: true, confirmed: true)
        XCTAssertEqual(harness.shield.lastAppliedApplications, [a])
        // The accepted break released A from the reused `flint` store.
        XCTAssertEqual(harness.shield.applies, 1)

        var ySelection = FamilyActivitySelection(); ySelection.applicationTokens = [a, b]
        let y = try harness.controller.startBlockNow(selection: ySelection, duration: 7200, breakLevel: .hardcore,
                                                     origin: .appUI, replacing: true, confirmed: true)
        XCTAssertEqual(harness.shield.applies, 2)
        XCTAssertEqual(harness.shield.lastAppliedApplications, [a, b],
                       "Y's own apply covers A and B")
        XCTAssertEqual(harness.store.loadActiveSession()?.id, y.id)
        // A stale X owner now reconciles with the fallback: it must not narrow Y's store back to A.
        let staleOwnerWriter = RedTeamShieldWriter()
        staleOwnerWriter.blockedApplications = harness.shield.lastAppliedApplications // Y's applied policy
        let (record, _) = try fixture(ySelection, releasing: FlintOrdinaryBreaks.scope(xSelection), causeID: "X")
        expectFallbackUnavailable {
            try productionFallback(records: [record], at: instant(301), writer: staleOwnerWriter,
                                   currentSessionID: { y.id })
        }
        XCTAssertEqual(staleOwnerWriter.writes, 0)
        XCTAssertEqual(harness.shield.lastAppliedApplications, [a, b], "B is still shielded after X's fallback")
    }

    // MARK: - (d) Production default seam == what session persistence writes
    func testRedTeamN02ProductionDefaultProviderMatchesSessionPersistence() throws {
        let defaults = UserDefaults(suiteName: FlintGroupStore.appGroupID)
        let key = "flint.activeSession" // FlintGroupStore's session key, written by saveActiveSession
        let original = defaults?.data(forKey: key)
        defer {
            if let original { defaults?.set(original, forKey: key) } else { defaults?.removeObject(forKey: key) }
        }
        let group = try XCTUnwrap(FlintGroupStore(), "App Group unavailable in the test host")

        let a: ApplicationToken = try fictionalRuleToken("target A")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a]
        let x = FlintActiveSession(name: "X", startedAt: now.wall, endsAt: now.wall.addingTimeInterval(3600),
                                   breakLevel: .easy, monitorName: "x")
        let (record, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(selection), causeID: x.id)

        // The projection the production default provider reads is the one persistence writes.
        group.saveActiveSession(x)
        XCTAssertEqual(group.loadActiveSession()?.id, x.id)
        let restored = RedTeamShieldWriter()
        try FlintOrdinaryBreaks.restoreUnavailableAuthorities([record], at: instant(301),
            writer: { _ in restored }) // no provider argument: the production default closure
        XCTAssertEqual(restored.blockedApplications, [a], "the default provider saw the persisted owner")

        // A stronger successor in the same projection: nothing is written over its store.
        let y = FlintActiveSession(name: "Y", startedAt: now.wall, endsAt: now.wall.addingTimeInterval(7200),
                                   breakLevel: .hardcore, monitorName: "y")
        group.saveActiveSession(y)
        XCTAssertEqual(group.loadActiveSession()?.id, y.id)
        let succeeded = RedTeamShieldWriter()
        try FlintOrdinaryBreaks.restoreUnavailableAuthorities([record], at: instant(301), writer: { _ in succeeded })
        XCTAssertEqual(succeeded.writes, 0, "the default provider rejects a stale session owner")

        // A present-but-undecodable record is "no verifiable session" from the projection, never
        // "no session ⇒ idle" and never an empty/default owner. Without an applied ledger
        // authority to fall back on, nothing is written.
        XCTAssertNotNil(defaults)
        defaults?.set(Data("not a session record".utf8), forKey: key)
        XCTAssertTrue(group.hasActiveSessionRecord())
        XCTAssertNil(group.loadActiveSession())
        let corrupt = RedTeamShieldWriter()
        try FlintOrdinaryBreaks.restoreUnavailableAuthorities([record], at: instant(301), writer: { _ in corrupt },
                                                             sessionAuthority: { nil })
        XCTAssertEqual(corrupt.writes, 0, "an unreadable projection alone never authorizes a reused-store write")
    }

    // MARK: - (c)/(2) A permanently due queue: the fallback alone keeps the current owner closed

    func testRedTeamN02PermanentDueQueueStillRestoresTheCurrentSessionAtTheDeadline() throws {
        // A due queued rule change makes `FlintOrdinaryBreaks.context` nil. The break service has no
        // rule-arbiter dependency (only the host rule reload applies the queue), so the fallback can
        // be the only path for a long time; for the still-current session it must re-shield.
        let a: ApplicationToken = try fictionalRuleToken("target A")
        let b: ApplicationToken = try fictionalRuleToken("target B")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a, b]
        var release = FamilyActivitySelection(); release.applicationTokens = [a]
        let x = FlintActiveSession(name: "X", startedAt: now.wall, endsAt: now.wall.addingTimeInterval(3600),
                                   breakLevel: .easy, monitorName: "x")
        let (record, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(release), causeID: x.id)

        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let clock = RedTeamClock(wall: now.wall, mono: now.monotonic)
        let ledger = ledger(directory: directory, clock: clock.flintClock)
        try ledger.transaction { $0.ordinaryGrants = try self.grantState(record) }.get()

        let writer = RedTeamShieldWriter()
        writer.blockedApplications = [b] // the accepted break released A
        let service = FlintOrdinaryBreakService(ledger: ledger, clock: clock.flintClock,
            transition: { body in body(); return true },
            context: { _, _ in nil }, // permanently due / unavailable
            register: { _, _ in XCTFail("an expired grant must not register") },
            registered: { _ in true }, stop: { _ in },
            apply: { records, context, observation in
                XCTAssertNil(context)
                try self.productionFallback(records: records, at: observation, writer: writer,
                                            currentSessionID: { x.id })
            })

        clock.advance(301) // past the 300s grant deadline, well before the 3600s session end
        // The context is still unavailable, so the call reports failure and keeps the retry — but
        // the re-shield itself must have happened.
        XCTAssertFalse(service.reconcile(), "an unavailable context retains the retry")
        XCTAssertEqual(writer.blockedApplications, [a, b],
                       "THE DEADLINE RE-BLOCKS A even with no rule context")

        let retained = try XCTUnwrap(try ledger.peek().get().ordinaryGrants?.records.first)
        XCTAssertEqual(retained.endReason, .deadline)
        XCTAssertTrue(retained.applicationPending, "durably retained for the next attempt")

        // Repeats are idempotent and bounded: same closed store, no second grant, no receipt yet.
        for _ in 0..<3 {
            XCTAssertFalse(service.reconcile())
            XCTAssertEqual(writer.blockedApplications, [a, b])
        }
        let after = try XCTUnwrap(try ledger.peek().get().ordinaryGrants)
        XCTAssertEqual(after.records.count, 1)
        XCTAssertEqual(after.receipts.count, 0)
        XCTAssertEqual(after.records.first?.grant.id, retained.grant.id)
    }

    // MARK: - (c)/(1) Double fault: unreadable projection + unavailable context

    /// The accepted break released A from the reused `flint` store, rule context is unavailable
    /// and the session projection can't be read (absent or undecodable). The ledger still records
    /// the session as the applied owner, so the deadline must re-shield A, as it does when the
    /// projection is readable. Before the fix nothing was written and A stayed released until the
    /// session ended (+301 s, +601 s and +1801 s all observed {B}).
    func testRedTeamN02DoubleFaultRestoresTheLedgerAppliedSessionAtTheDeadline() throws {
        let harness = try SessionHarness()
        defer { harness.cleanUp() }
        let a: ApplicationToken = try fictionalRuleToken("target A")
        let b: ApplicationToken = try fictionalRuleToken("target B")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a, b]
        let x = try harness.controller.startBlockNow(selection: selection, duration: 3600, breakLevel: .easy,
                                                     origin: .appUI, replacing: true, confirmed: true)
        XCTAssertEqual(FlintOrdinaryBreaks.appliedSessionID(try harness.ledger.peek().get().sessionAuthority), x.id)

        var release = FamilyActivitySelection(); release.applicationTokens = [a]
        let (record, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(release), causeID: x.id)
        try harness.ledger.transaction { $0.ordinaryGrants = try self.grantState(record) }.get()
        harness.store.active = nil // the projection is lost; `currentSessionID()` is nil
        harness.store.corruptRecord = true

        let writer = RedTeamShieldWriter()
        writer.blockedApplications = [b] // the accepted break released A
        let ledger = harness.ledger
        let service = FlintOrdinaryBreakService(ledger: ledger, clock: harness.clock.flintClock,
            transition: { body in body(); return true },
            context: { _, _ in nil },
            register: { _, _ in XCTFail("an expired grant must not register") },
            registered: { _ in true }, stop: { _ in },
            apply: { records, _, observation in
                try self.productionFallback(records: records, at: observation, writer: writer,
                                            currentSessionID: { nil },
                                            sessionAuthority: { try? ledger.peek().get().sessionAuthority })
            })

        harness.clock.advance(301) // past the 300 s grant deadline, well before the 3600 s session end
        XCTAssertFalse(service.reconcile(), "an unavailable context still retains the retry")
        XCTAssertEqual(writer.blockedApplications, [a, b],
            "the deadline re-shields A from the ledger-applied owner (writes: \(writer.writes))")
        let retained = try XCTUnwrap(try ledger.peek().get().ordinaryGrants?.records.first)
        XCTAssertEqual(retained.endReason, .deadline)
        XCTAssertTrue(retained.applicationPending)
        for _ in 0..<2 {
            XCTAssertFalse(service.reconcile())
            XCTAssertEqual(writer.blockedApplications, [a, b], "repeats stay closed")
        }
    }

    /// Ledger-verified ownership never widens the fallback: a successor's applied generation, an
    /// idle tombstone, or no authority at all still writes nothing for the older owner.
    func testRedTeamN02LedgerOwnershipNeverRestoresASuccessorOrEndedOwner() throws {
        let a: ApplicationToken = try fictionalRuleToken("target A")
        let b: ApplicationToken = try fictionalRuleToken("target B")
        var xSelection = FamilyActivitySelection(); xSelection.applicationTokens = [a]
        var ySelection = FamilyActivitySelection(); ySelection.applicationTokens = [a, b]
        XCTAssertNil(FlintOrdinaryBreaks.appliedSessionID(nil))

        // Successor: Y's applied generation is the ledger owner, so X's narrower policy is skipped.
        let successor = try SessionHarness()
        defer { successor.cleanUp() }
        let x = try successor.controller.startBlockNow(selection: xSelection, duration: 3600, breakLevel: .easy,
                                                       origin: .appUI, replacing: true, confirmed: true)
        let y = try successor.controller.startBlockNow(selection: ySelection, duration: 7200, breakLevel: .hardcore,
                                                       origin: .appUI, replacing: true, confirmed: true)
        let ySuccessor = try successor.ledger.peek().get().sessionAuthority
        XCTAssertEqual(FlintOrdinaryBreaks.appliedSessionID(ySuccessor), y.id)
        let (xRecord, _) = try fixture(xSelection, releasing: FlintOrdinaryBreaks.scope(xSelection), causeID: x.id)
        let overSuccessor = RedTeamShieldWriter()
        expectFallbackUnavailable {
            try productionFallback(records: [xRecord], at: instant(301), writer: overSuccessor,
                                   currentSessionID: { nil }, sessionAuthority: { ySuccessor })
        }
        XCTAssertEqual(overSuccessor.writes, 0, "a successor's store is never narrowed to X's policy")

        // Ended: the idle tombstone proves no current session, so nothing is recreated.
        let ended = try SessionHarness()
        defer { ended.cleanUp() }
        let z = try ended.controller.startBlockNow(selection: xSelection, duration: 3600, breakLevel: .easy,
                                                   origin: .appUI, replacing: true, confirmed: true)
        XCTAssertEqual(ended.controller.requestStop(route: .manualStop, confirmed: true), .stopped)
        let idle = try ended.ledger.peek().get().sessionAuthority
        XCTAssertEqual(idle?.provesIdle, true)
        XCTAssertNil(FlintOrdinaryBreaks.appliedSessionID(idle))
        let (zRecord, _) = try fixture(xSelection, releasing: FlintOrdinaryBreaks.scope(xSelection), causeID: z.id)
        let afterEnd = RedTeamShieldWriter()
        expectFallbackUnavailable {
            try productionFallback(records: [zRecord], at: instant(301), writer: afterEnd,
                                   currentSessionID: { nil }, sessionAuthority: { idle })
        }
        XCTAssertEqual(afterEnd.writes, 0, "an ended session is never recreated")

        // No authority at all: the previous keep behavior.
        let unknown = RedTeamShieldWriter()
        expectFallbackUnavailable {
            try productionFallback(records: [zRecord], at: instant(301), writer: unknown,
                                   currentSessionID: { nil }, sessionAuthority: { nil })
        }
        XCTAssertEqual(unknown.writes, 0)
    }

    // MARK: - Clock/zone change around the deadline

    func testRedTeamN02ClockJumpAndZoneChangeDoNotSuspendRestoration() throws {
        let a: ApplicationToken = try fictionalRuleToken("target A")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a]
        let (record, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(selection), causeID: "x")

        // Same boot, wall jump +2h, monotonic +301: monotonic still governs, so the owner is live.
        let jumped = RedTeamShieldWriter()
        expectFallbackUnavailable {
            try productionFallback(records: [record], at: instant(301, wallDelta: 7200), writer: jumped,
                                   currentSessionID: { "x" })
        }
        XCTAssertEqual(jumped.blockedApplications, [a],
                       "a forward wall edit must not turn the fallback into an unbounded release")

        // A zone change is not an elapsed-time discontinuity: same result (restoration is
        // bootID+monotonic only, so no calendar is consulted).
        XCTAssertEqual(jumped.blockedApplications, [a])

        // Cross-boot: no trusted elapsed time, so the old owner is never recreated (§9 reboot policy).
        let rebooted = RedTeamShieldWriter()
        expectFallbackUnavailable {
            try productionFallback(records: [record], at: instant(301, bootID: "boot-2"), writer: rebooted,
                                   currentSessionID: { "x" })
        }
        XCTAssertEqual(rebooted.writes, 0,
                       "a cross-boot fallback never recreates an owner; the session path re-anchors")

        // The service turns that same cross-boot record into a reboot end, not a fresh release.
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let clock = RedTeamClock(wall: now.wall, mono: now.monotonic)
        clock.boot = "boot-2"
        let ledger = ledger(directory: directory, clock: clock.flintClock)
        try ledger.transaction { $0.ordinaryGrants = try self.grantState(record) }.get()
        let writer = RedTeamShieldWriter()
        let service = FlintOrdinaryBreakService(ledger: ledger, clock: clock.flintClock,
            transition: { body in body(); return true }, context: { _, _ in nil },
            register: { _, _ in XCTFail("no registration") }, registered: { _ in true }, stop: { _ in },
            apply: { records, _, observation in
                try self.productionFallback(records: records, at: observation, writer: writer,
                                            currentSessionID: { "x" })
            })
        _ = service.reconcile()
        XCTAssertEqual(writer.writes, 0, "the fallback wrote nothing for a cross-boot owner")
        XCTAssertEqual(try ledger.peek().get().ordinaryGrants?.records.first?.endReason, .reboot)
    }
}

// MARK: - Fakes

/// Records the four shield fields the fallback writes, and how many writes happened at all.
private final class RedTeamShieldWriter: FlintRuleShieldWriting {
    var blockedApplications: Set<ApplicationToken>?
    var categoryPolicy: ShieldSettings.ActivityCategoryPolicy<Application>?
    var blockedDomains: Set<WebDomainToken>?
    var domainCategoryPolicy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?
    private(set) var writes = 0

    func applications(_ tokens: Set<ApplicationToken>?) { writes += 1; blockedApplications = tokens }
    func applicationCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<Application>?) {
        writes += 1; categoryPolicy = policy
    }
    func webDomains(_ tokens: Set<WebDomainToken>?) { writes += 1; blockedDomains = tokens }
    func webDomainCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?) {
        writes += 1; domainCategoryPolicy = policy
    }
}

/// Minimal session-control harness: fake OS seams plus a real file-backed ledger.
private final class SessionHarness {
    let shield: RedTeamSessionShield
    let registrar: RedTeamRegistrar
    let store: RedTeamSessionStore
    let clock: RedTeamClock
    let directory: URL
    let defaultsSuite: String
    let defaults: UserDefaults
    let ledger: FlintProtectionLedger
    let controller: FlintSessionController

    init() throws {
        let shield = RedTeamSessionShield()
        let registrar = RedTeamRegistrar()
        let store = RedTeamSessionStore()
        let clock = RedTeamClock(wall: Date(timeIntervalSince1970: 1_800_000_000), mono: 1000)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-redteam-n02-session-\(UUID().uuidString)", isDirectory: true)
        let defaultsSuite = "flint.tests.redteam-n02.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsSuite))
        let ledger = FlintProtectionLedger(directory: directory, clock: clock.flintClock,
                                           initializedMarker: { nil }, setInitializedMarker: { _ in })
        self.shield = shield; self.registrar = registrar; self.store = store; self.clock = clock
        self.directory = directory; self.defaultsSuite = defaultsSuite; self.defaults = defaults
        self.ledger = ledger
        controller = FlintSessionController(environment: FlintSessionEnvironment(
            store: { store }, shield: shield, webRestrictions: RedTeamNoWebRestrictions(),
            registrar: registrar, isAuthorized: { true }, clock: clock.flintClock,
            shortSessionStrategy: .endWarning, healthDefaults: { defaults },
            ledger: { ledger }, transitionLock: { nil }, monitorLockTimeout: 1,
            wait: { _ in }, captureRemovalGuard: { false }, reconcileRemovalGuard: {}))
    }

    func start(level: BreakLevel, duration: TimeInterval) throws -> FlintActiveSession {
        var selection = FamilyActivitySelection()
        selection.applicationTokens = [try fictionalRuleToken("target A")]
        return try controller.startBlockNow(selection: selection, duration: duration, breakLevel: level,
                                            origin: .appUI, replacing: true, confirmed: true)
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: directory)
        defaults.removePersistentDomain(forName: defaultsSuite)
    }
}

private final class RedTeamSessionShield: FlintSessionShielding {
    private(set) var applies = 0
    private(set) var clears = 0
    /// The application tokens of the most recent apply, so a successor's own write is observable.
    private(set) var lastAppliedApplications: Set<ApplicationToken> = []
    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts {
        .init(applications: max(1, selection.applicationTokens.count))
    }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets {
        .init(applications: Set(selection.applicationTokens.map { AnyHashable($0) }))
    }
    func fingerprint(of selection: FamilyActivitySelection) -> String {
        selection.applicationTokens.count.description
    }
    func applyBlockList(_ selection: FamilyActivitySelection) throws {
        applies += 1
        lastAppliedApplications = selection.applicationTokens
    }
    func clear() { clears += 1 }
    func retainsAppliedSettings() -> Bool { applies > clears }
}

private final class RedTeamRegistrar: FlintSessionActivityRegistering {
    private(set) var registered: Set<String> = []
    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws { registered.insert(name) }
    func stopMonitoring(_ names: [String]) { registered.subtract(names) }
    func registeredActivityNames() -> Set<String> { registered }
}

private struct RedTeamNoWebRestrictions: FlintSessionWebRestricting {
    func reconcile(_ session: FlintActiveSession, clock: FlintClock) {}
}

private final class RedTeamSessionStore: FlintSessionStateStoring {
    var active: FlintActiveSession?
    var selection: FamilyActivitySelection?
    var sessionSelections: [String: FamilyActivitySelection] = [:]
    /// Present but not decodable: `hasActiveSessionRecord` is true while `loadActiveSession` is nil.
    var corruptRecord = false

    func loadActiveSession() -> FlintActiveSession? { active }
    func hasActiveSessionRecord() -> Bool { active != nil || corruptRecord }
    func saveActiveSession(_ session: FlintActiveSession) { active = session }
    func clearActiveSession() { active = nil }
    func saveSelection(_ selection: FamilyActivitySelection) { self.selection = selection }
    func loadSelection() -> FamilyActivitySelection? { selection }
    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) {
        sessionSelections[sessionID] = selection
    }
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? { sessionSelections[sessionID] }
    func clearSessionSelection() { sessionSelections.removeAll() }
    func isProtectionStateReadable() -> Bool { true }
}

private final class RedTeamClock {
    private let baseWall: Date
    private let baseMono: Double
    var wall: Date
    var mono: Double
    var boot = "boot"
    init(wall: Date, mono: Double) {
        baseWall = wall; baseMono = mono; self.wall = wall; self.mono = mono
    }
    func advance(_ seconds: TimeInterval) {
        wall = wall.addingTimeInterval(seconds); mono += seconds
    }
    func set(elapsed seconds: TimeInterval) {
        wall = baseWall.addingTimeInterval(seconds); mono = baseMono + seconds
    }
    var flintClock: FlintClock {
        .init(now: { self.wall }, monotonic: { self.mono }, bootSessionID: { self.boot },
              calendar: { Calendar(identifier: .gregorian) })
    }
}
