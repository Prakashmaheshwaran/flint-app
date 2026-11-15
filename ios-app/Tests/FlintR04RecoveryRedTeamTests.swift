import XCTest
import FamilyControls
@testable import FlintCore

/// Red-team R-04 (P1, iOS): adversarial re-verification of the retained-predecessor recovery
/// merged in PR #251 (`FlintSessionPersistence.finishInstallation`).
///
/// Unlike `FlintSessionControllerTests.testOpenEndedTargetlessPredecessorIsRestoredFromTheUnappliedReplacementR04`,
/// this suite reproduces the *actual* crash state of `startBlockNow`: the replacement's id-keyed
/// target snapshot (`flint.activeSession.selection`) and the picker draft (`flint.selection`)
/// are written immediately BEFORE the session projection, which is written immediately before
/// the native apply. A process death in that window therefore leaves all three keys, and the
/// production store keeps the snapshot under a single key with the session id inside.
///
/// Both directions of R-04 are attacked:
///  - the restored predecessor must be reachable by ordinary stop AND by Emergency Pass scope;
///  - the recovery must not invent authority (no re-applied/widened targets, no resurrection of
///    an ended predecessor, no foreign/mismatched projection adopted as the predecessor).
final class FlintR04RecoveryRedTeamTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: R04Store!
    private var shield: R04Shield!
    private var registrar: R04Registrar!
    private var clock: R04Clock!
    private var events: R04Events!
    private var ledgerDirectory: URL!
    private var marker: Date?
    private var releasedStores: [String] = []

    private let replacementRequestID = "r04-replacement"
    /// Fake shield's two target sets: `selectionA` = {A, B}, `selectionB` = {A} only.
    private let selectionA = FamilyActivitySelection()
    private let selectionB = FamilyActivitySelection(includeEntireCategory: true)

    private var utcCalendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    override func setUp() {
        super.setUp()
        suiteName = "flint.r04.redteam.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        // The three rule domains a healthy global recovery scope needs ([] = configured empty).
        for key in FlintRuleStoreKey.allCases { defaults.set(Data("[]".utf8), forKey: key.rawValue) }
        store = R04Store(defaults: defaults)
        shield = R04Shield()
        registrar = R04Registrar()
        clock = R04Clock()
        events = R04Events()
        releasedStores = []
        marker = nil
        FlintSessionLifecycle.register(events)
        ledgerDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-r04-ledger-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        FlintSessionLifecycle.unregister(events)
        try? FileManager.default.removeItem(at: ledgerDirectory)
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    // MARK: - Harness (real ledger + production key layout)

    private func ledger() -> FlintProtectionLedger {
        let defaults = self.defaults!
        return FlintProtectionLedger(
            directory: ledgerDirectory,
            clock: clock.flintClock,
            initializedMarker: { self.marker },
            setInitializedMarker: { date in
                self.marker = date
                defaults.set(date.timeIntervalSince1970, forKey: FlintProtectionLedger.initializedMarkerKey)
            },
            migrate: { state, now in state.importLegacyPass(.absent, at: now) })
    }

    private func sessionController(afterStartCommit: @escaping () throws -> Void = {},
                                   afterStartApplied: @escaping () throws -> Void = {},
                                   recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing })
        -> FlintSessionController {
        FlintSessionController(environment: FlintSessionEnvironment(
            store: { self.store },
            shield: shield,
            webRestrictions: R04Web(),
            registrar: registrar,
            isAuthorized: { true },
            clock: clock.flintClock,
            shortSessionStrategy: .endWarning,
            healthDefaults: { self.defaults },
            ledger: { self.ledger() },
            publishStatus: {},
            transitionLock: { FlintSessionTransitionLock(directory: self.ledgerDirectory, timeout: 0.2) },
            monitorLockTimeout: 1,
            wait: { seconds in self.clock.advance(seconds) },
            reconcileEmergencyPass: { true },
            prepareNewSession: { _ in .success(()) },
            recoveryGate: recoveryGate,
            afterStartCommit: afterStartCommit,
            afterStartApplied: afterStartApplied,
            reconcileOrdinaryBreaks: {},
            captureRemovalGuard: { false },
            removalGuardOffPending: { false },
            reconcileRemovalGuard: {}))
    }

    private func passController() -> FlintEmergencyPassController {
        let session = sessionController()
        return FlintEmergencyPassController(environment: .init(
            ledger: { self.ledger() },
            clock: clock.flintClock,
            scope: { state, now in try self.scope(state: state, at: now) },
            registerCarrier: { _, _, _ in },
            terminate: { sessions, at in
                for ended in sessions {
                    guard session.endCommittedEmergencyPass(ended, at: at) else {
                        throw FlintEmergencyPassError.applying
                    }
                }
            },
            release: { scope in self.releasedStores += scope.stores },
            resume: { _, _ in },
            emit: { _ in },
            prepareAcceptedSession: { session.prepareAcceptedInstallationForScope() },
            prepareScope: {},
            readScope: { state, now in try self.scope(state: state, at: now) }))
    }

    private func scope(state: FlintLedgerState, at now: FlintLedgerInstant) throws -> FlintPassScope {
        try FlintEmergencyPassScope.load(state: state, at: now, defaults: defaults,
            calendar: utcCalendar, hasThresholdShield: { _ in false })
    }

    private enum R04Crash: Error { case afterAcceptance }

    /// The documented crash: a targetless pre-ADR-012 predecessor X is the adopted owner, a
    /// finite replacement Y is accepted, then the process dies after Y's target snapshot,
    /// projection and picker draft were saved but before Y reached the native shield.
    @discardableResult
    private func seedCrashAfterReplacementProjection(
        predecessorEndsAt: Date?,
        predecessorLevel: BreakLevel = .easy,
        replacementLevel: BreakLevel = .easy,
        replacementMinutes: Double = 1
    ) throws -> (predecessor: FlintActiveSession, replacement: FlintActiveSession) {
        _ = try ledger().transaction { state in
            state.importLegacyPass(.absent, at: .init(clock: self.clock.flintClock))
        }.get()
        // A targetless legacy session: no contract version, no frozen selection, no generation.
        let predecessor = FlintActiveSession(id: "legacy.r04", name: "Legacy", startedAt: clock.now,
            endsAt: predecessorEndsAt, breakLevel: predecessorLevel, monitorName: "flint.session.legacy")
        store.saveActiveSession(predecessor)
        registrar.registered.insert(predecessor.monitorName)
        shield.retainsSettings = true // it owns the last-good native "flint" store

        XCTAssertThrowsError(try sessionController(afterStartCommit: { throw R04Crash.afterAcceptance })
            .startBlockNow(selection: selectionA, duration: replacementMinutes * 60,
                breakLevel: replacementLevel, origin: .appUI,
                requestID: replacementRequestID, confirmed: true)) { error in
            XCTAssertTrue(error is R04Crash, "expected the injected crash, got \(error)")
        }
        let replacement = try XCTUnwrap(try ledger().peek().get().sessionAuthority?.latest?.session)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.previousSession?.id,
                       predecessor.id, "precondition: X is the recorded predecessor of Y")
        XCTAssertEqual(shield.applyCount, 0, "precondition: Y never reached the native shield")

        // startBlockNow's writes after the ledger commit and before the native apply, in order.
        store.saveSessionSelection(selectionA, sessionID: replacement.id)
        store.saveActiveSession(replacement)
        store.saveSelection(selectionA)
        XCTAssertEqual(store.loadActiveSession()?.id, replacement.id)
        return (predecessor, replacement)
    }

    /// Models a replacement whose target-snapshot/draft writes did not land (both `try?` writes).
    private func forgetReplacementTargetWrites() {
        store.clearSessionSelection()
        store.clearPickerDraft()
    }

    private func staleSelectionSessionID() -> String? {
        store.sessionSelectionOwner()
    }

    private var health: FlintSessionHealth { FlintSessionHealth.load(from: defaults) }

    // MARK: - Direction 1: the restored predecessor must stay reachable

    /// R-04 / PC-41 / §6. In the real post-crash state the pass scope still carries Y's target
    /// snapshot, so the global recovery scope refuses the restored predecessor.
    func testStaleReplacementSelectionRefusesEmergencyPassScopeAfterRecoveryRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        clock.advance(75) // the replacement expired; the legacy predecessor never does

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id,
            "precondition: R-04 restored the recorded predecessor")

        let state = try ledger().peek().get()
        let loaded = Result { try scope(state: state, at: .init(clock: clock.flintClock)) }
        switch loaded {
        case let .failure(error):
            XCTFail("R-04/PC-41: after the documented crash the Emergency Pass scope refuses the "
                + "restored predecessor (\(error)). The store still carries the replacement's target "
                + "snapshot for session \(staleSelectionSessionID() ?? "?"), while the restored owner is "
                + "\(seed.predecessor.id); preview/use answer scopeUnavailable, so the global recovery "
                + "route named by §6/PC-41 cannot reach the only remaining commitment.")
        case let .success(scope):
            XCTAssertEqual(scope.sessions.map(\.id), [seed.predecessor.id])
            XCTAssertTrue(scope.hasActiveCommitment)
        }
    }

    /// The same state through the real pass controller: the user sees a scope failure, not a pass.
    func testEmergencyPassPreviewIsRefusedAfterRecoveryInTheRealCrashStateRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        clock.advance(75)
        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)

        let preview = passController().preview()
        switch preview {
        case let .failure(error):
            XCTFail("R-04/PC-41: Emergency Pass preview is unavailable (\(error)) even though the "
                + "restored predecessor \(seed.predecessor.id) is active and stoppable.")
        case let .success(preview):
            XCTAssertEqual(preview.scope.sessions.map(\.id), [seed.predecessor.id])
        }
    }

    /// Isolates the scope failure to the leftover id-keyed replacement snapshot: the picker
    /// draft ("flint.selection") is not a scope input, the snapshot is.
    func testStaleReplacementSnapshotAloneRefusesThePassScopeRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        clock.advance(75)
        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        store.clearPickerDraft() // only the replacement's id-keyed snapshot remains

        let state = try ledger().peek().get()
        let loaded = Result { try scope(state: state, at: .init(clock: clock.flintClock)) }
        switch loaded {
        case let .failure(error):
            XCTFail("R-04: with only the replacement's id-keyed target snapshot left in the store, "
                + "the pass scope fails (\(error)) instead of describing the restored owner "
                + "\(seed.predecessor.id). FlintEmergencyPassScope.load rejects the snapshot whose "
                + "sessionID is not the active session instead of treating a legacy owner's targets "
                + "as unknown-but-retained.")
        case let .success(scope):
            XCTAssertEqual(scope.sessions.map(\.id), [seed.predecessor.id])
        }
    }

    /// The picker draft alone does not make the pass scope unavailable (it is opaque policy, not
    /// an owner snapshot), so the refusal above comes from the snapshot, not from the draft.
    func testPickerDraftAloneDoesNotRefuseThePassScopeRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        clock.advance(75)
        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        store.clearSessionSelection() // the picker draft stays

        let state = try ledger().peek().get()
        let scope = try scope(state: state, at: .init(clock: clock.flintClock))
        XCTAssertEqual(scope.sessions.map(\.id), [seed.predecessor.id])
        XCTAssertTrue(scope.hasActiveCommitment)
    }

    /// Direction-1 baseline that the merged test claims: with no replacement target snapshot or
    /// picker draft left behind, the restored predecessor is reachable by scope and stop.
    func testRestoredPredecessorIsReachableByPassScopeAndStopWithoutStaleTargetsRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        forgetReplacementTargetWrites()
        clock.advance(75)

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        XCTAssertEqual(shield.applyCount, 0, "unknown legacy targets are never re-applied")
        XCTAssertEqual(shield.clearCount, 0, "restoration retains, never releases")
        XCTAssertNotNil(health.lastArmFailure, "unknown target repair stays visible")

        let state = try ledger().peek().get()
        let scope = try scope(state: state, at: .init(clock: clock.flintClock))
        XCTAssertEqual(scope.sessions.map(\.id), [seed.predecessor.id])
        XCTAssertTrue(scope.hasActiveCommitment)
        let preview = try passController().preview().get()
        XCTAssertEqual(preview.scope.sessions.map(\.id), [seed.predecessor.id])

        XCTAssertEqual(sessionController().requestStop(route: .manualStop, confirmed: true), .stopped)
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertNil(store.loadActiveSession())
    }

    // MARK: - Direction 2: the recovery must not invent authority

    /// §8 ("last-good state cannot identify safe targets → do not invent blanket blocking") and
    /// §3 (target identity). The merged test asserts `applyCount == 0`; in the real crash state
    /// reconcile() replaces the predecessor's unknown legacy targets with the replacement's set.
    func testRecoveryDoesNotReapplyTheReplacementTargetsUnderTheRestoredPredecessorRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        clock.advance(75)

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        XCTAssertEqual(shield.applyCount, 0,
            "R-04/§8: the predecessor's targets are unknown; recovery must not write the replacement's "
            + "frozen selection (or the picker draft) into the native store as if it were the "
            + "predecessor's own commitment — that replaces, and can release, targets X blocked")
        XCTAssertTrue(shield.appliedSelections.isEmpty,
            "§8/§3: the native store still held only the predecessor's last-good settings")
    }

    /// The write comes from the *picker draft* — a value no commitment ever accepted (the user
    /// can change the picker after the crash) — not even from the replacement's frozen snapshot.
    func testRecoveryDoesNotApplyThePickerDraftToTheRestoredPredecessorRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        // The draft is the user's current pick, not what any running session blocks (§3).
        store.saveSelection(selectionB)
        XCTAssertNil(store.loadSessionSelection(sessionID: seed.predecessor.id))
        XCTAssertNotNil(store.loadSessionSelection(sessionID: seed.replacement.id))
        clock.advance(75)

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        let sources = shield.appliedSelections.map {
            $0.includeEntireCategory ? "pickerDraft(selectionB)" : "replacementSnapshot(selectionA)"
        }
        XCTAssertEqual(shield.applyCount, 0,
            "R-04/§3/§8: the restored predecessor owns no accepted target set, so reconcile must not "
            + "write the picker draft or the replacement's frozen selection into the native store; "
            + "nativeWrites=\(sources)")
    }

    /// A foreign projection is never adopted as the predecessor, and nothing is released.
    func testForeignProjectionIsNeverTreatedAsThePredecessorRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        let foreign = FlintActiveSession(id: "foreign.session", name: "Other",
            startedAt: seed.replacement.startedAt, endsAt: seed.replacement.endsAt,
            breakLevel: .easy, monitorName: "flint.session.foreign",
            contractVersion: FlintActiveSession.currentContractVersion)
        store.saveActiveSession(foreign)
        clock.advance(75)

        let report = sessionController().reconcile()
        XCTAssertTrue(report.recordUnreadable)
        XCTAssertEqual(store.loadActiveSession()?.id, foreign.id, "unknown state is left untouched")
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertTrue(registrar.stopped.isEmpty)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)
        XCTAssertEqual(sessionController().requestStop(route: .manualStop, confirmed: true), .unavailable)
        XCTAssertEqual(shield.clearCount, 0)
    }

    /// A projection that matches the generation's identity but not its commitment (tier, end,
    /// targets) is not accepted as the untrusted replacement, so no predecessor is restored
    /// from invented evidence.
    func testMismatchedReplacementProjectionIsNeverTreatedAsThePredecessorRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        let replacement = seed.replacement
        let tampered = FlintActiveSession(id: replacement.id, name: replacement.name,
            startedAt: replacement.startedAt,
            endsAt: replacement.endsAt?.addingTimeInterval(600), breakLevel: .hardcore,
            monitorName: replacement.monitorName,
            contractVersion: FlintActiveSession.currentContractVersion)
        store.saveActiveSession(tampered)
        clock.advance(75)

        let report = sessionController().reconcile()
        XCTAssertTrue(report.recordUnreadable)
        XCTAssertEqual(store.loadActiveSession(), tampered, "a changed commitment proves nothing")
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertTrue(registrar.stopped.isEmpty)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)
        XCTAssertEqual(sessionController().requestStop(route: .manualStop, confirmed: true), .unavailable)
    }

    /// A finite predecessor that already passed its own end is completed, never resurrected.
    func testExpiredFinitePredecessorIsNotResurrectedRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(
            predecessorEndsAt: clock.now.addingTimeInterval(120))
        clock.advance(135)

        // The completion is committed and cleaned up inside the installation recovery, so the
        // reconciler observes an already-finished session (no second release).
        XCTAssertEqual(sessionController().reconcile().outcome, .noSession)
        XCTAssertNil(store.loadActiveSession())
        XCTAssertEqual(shield.clearCount, 1, "the predecessor's own end authorizes exactly one release")
        XCTAssertTrue(registrar.registered.isEmpty)
        XCTAssertEqual(sessionController().acceptedStartResult(replacementRequestID)?.outcome, .unconfirmed)
        guard case let .ended(ended, route, _) = try XCTUnwrap(events.log.last).unannotated else {
            return XCTFail("expected exactly one ended event")
        }
        XCTAssertEqual(ended.id, seed.predecessor.id, "the ended commitment is the predecessor")
        XCTAssertEqual(route, .completed)
    }

    /// Variant (a): a still-running (unexpired) stronger replacement is applied; the weaker
    /// predecessor is never restored while its replacement's interval is live.
    func testUnexpiredStrongerReplacementIsAppliedInsteadOfRestoringThePredecessorRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil,
            replacementLevel: .harder, replacementMinutes: 30)
        clock.advance(60) // one minute of thirty

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.replacement.id,
            "the accepted commitment still owns the interval")
        XCTAssertGreaterThanOrEqual(shield.applyCount, 1, "the stronger replacement reaches the shield")
        XCTAssertTrue(registrar.stopped.contains(seed.predecessor.monitorName))
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .applied)
        XCTAssertEqual(sessionController().acceptedStartResult(replacementRequestID)?.outcome, .running)
        XCTAssertEqual(shield.clearCount, 0)
    }

    /// Variant (b): the replacement actually applied natively, only its `applied` marker was
    /// lost. Recovery must not release that native state early, and must leave exactly one owner.
    func testPartiallyAppliedReplacementKeepsItsTargetsUntilThePredecessorIsStoppedRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        // The native write happened; only the durable marker did not.
        try shield.applyBlockList(selectionA)
        clock.advance(75)

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        XCTAssertEqual(shield.clearCount, 0,
            "a missing applied marker is not proof the native restriction is stale (§8)")

        XCTAssertEqual(sessionController().requestStop(route: .manualStop, confirmed: true), .stopped)
        XCTAssertEqual(shield.clearCount, 1, "the restored owner's stop releases exactly once")
        XCTAssertNil(store.loadActiveSession())
        XCTAssertTrue(registrar.registered.isEmpty)
    }

    /// Variant (f): a third start after the crash must not double-restore or leak a predecessor.
    func testThirdStartAfterRecoveryReplacesTheRestoredPredecessorCleanlyRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        forgetReplacementTargetWrites()
        clock.advance(75)
        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)

        let third = try sessionController().startBlockNow(selection: selectionB, duration: 600,
            breakLevel: .easy, origin: .appUI, requestID: "r04-third", confirmed: true)
        XCTAssertNotEqual(third.id, seed.predecessor.id)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.previousSession?.id,
                       seed.predecessor.id, "the third generation's predecessor is the live owner")
        XCTAssertFalse(registrar.registered.contains(seed.predecessor.monitorName))
        XCTAssertTrue(registrar.registered.contains(third.monitorName))
        XCTAssertEqual(sessionController().requestStop(route: .manualStop, confirmed: true), .stopped)
        XCTAssertNil(store.loadActiveSession())
    }

    /// Variant (g): a forward wall jump alone proves no elapsed time; the replacement is not
    /// treated as expired and the predecessor is not restored early.
    func testForwardWallJumpAloneDoesNotExpireTheReplacementRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil,
            replacementMinutes: 60)
        clock.jumpWall(30 * 60) // wall moves; the monotonic clock does not

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.replacement.id)
        XCTAssertEqual(shield.clearCount, 0)
    }

    /// Variant (h): the process dies after recovery saved the predecessor projection but before
    /// its ledger commit. The next reconcile must restore the same predecessor once, with one
    /// terminal outcome for the replacement and no extra release.
    func testCrashDuringRecoveryRestoresTheSamePredecessorOnceRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        clock.advance(75)
        // The projection write landed, the ledger transaction did not.
        store.saveActiveSession(seed.predecessor)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        let authority = try XCTUnwrap(ledger().peek().get().sessionAuthority)
        XCTAssertEqual(authority.latest?.session.id, seed.predecessor.id)
        XCTAssertEqual(authority.latest?.phase, .applied)
        XCTAssertEqual(authority.acceptedStarts.filter { $0.sessionID == seed.replacement.id }.map(\.outcome),
                       [.unconfirmed], "the unapplied replacement terminates exactly once")
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertTrue(events.kinds.isEmpty, "no duplicate start/end event for either session")

        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertEqual(sessionController().requestStop(route: .manualStop, confirmed: true), .stopped)
        XCTAssertEqual(shield.clearCount, 1)
    }

    /// Variant (i): the pass ends only the restored predecessor, releases its store once and
    /// leaves no replacement resource registered.
    func testEmergencyPassOnRestoredPredecessorEndsOnlyItAndReleasesItsStoreRedteamR04() throws {
        let seed = try seedCrashAfterReplacementProjection(predecessorEndsAt: nil)
        forgetReplacementTargetWrites()
        clock.advance(75)
        XCTAssertEqual(sessionController().reconcile().outcome, .running)
        XCTAssertEqual(store.loadActiveSession()?.id, seed.predecessor.id)
        XCTAssertEqual(registrar.registered, [seed.predecessor.monitorName],
            "the unapplied replacement's registration is retired")

        let pass = passController()
        let preview = try pass.preview().get()
        XCTAssertEqual(preview.scope.sessions.map(\.id), [seed.predecessor.id],
            "the pass covers exactly the restored owner, never the expired replacement")
        let status = try pass.use(previewRevision: preview.revision, operationID: "r04-pass").get()

        XCTAssertTrue(status.pass.isRecovering)
        XCTAssertNil(store.loadActiveSession())
        XCTAssertEqual(shield.clearCount, 1, "the pass releases the restored session exactly once")
        XCTAssertTrue(registrar.registered.isEmpty, "no session resource outlives its owner")
        XCTAssertTrue(releasedStores.contains("flint"))
        XCTAssertEqual(sessionController().acceptedStartResult(replacementRequestID)?.outcome, .unconfirmed)
    }

    /// An open-ended legacy Hardcore predecessor cannot be replaced at all, so the P1
    /// "restored but unreachable" combination cannot arise for it.
    func testOpenEndedLegacyHardcorePredecessorCannotBeReplacedRedteamR04() throws {
        _ = try ledger().transaction { state in
            state.importLegacyPass(.absent, at: .init(clock: self.clock.flintClock))
        }.get()
        let predecessor = FlintActiveSession(id: "legacy.r04.hardcore", name: "Legacy",
            startedAt: clock.now, endsAt: nil, breakLevel: .hardcore,
            monitorName: "flint.session.legacy")
        store.saveActiveSession(predecessor)

        for (level, request) in [(BreakLevel.easy, "weaker"), (.hardcore, "stronger")] {
            XCTAssertThrowsError(try sessionController().startBlockNow(selection: selectionA,
                duration: 60, breakLevel: level, origin: .appUI, requestID: request,
                confirmed: true)) { error in
                guard case .replacementDenied = (error as? FlintSessionError) ?? .protectionStateUnavailable
                else { return XCTFail("\(request): expected replacementDenied, got \(error)") }
            }
        }
        XCTAssertEqual(store.loadActiveSession()?.id, predecessor.id)
        XCTAssertNil(try ledger().peek().get().sessionAuthority?.latest?.previousSession,
            "no replacement generation exists, so R-04 has no predecessor to restore")
    }

}

// MARK: - Fakes

private struct R04Web: FlintSessionWebRestricting {
    func reconcile(_ session: FlintActiveSession, clock: FlintClock) {}
}

private final class R04Shield: FlintSessionShielding {
    var countsValue = FlintSelectionCounts(applications: 3)
    var applyCount = 0
    var clearCount = 0
    var appliedSelections: [FamilyActivitySelection] = []
    /// Native "flint" store contents: true while this store still restricts something.
    var retainsSettings = false

    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { countsValue }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets {
        FlintSessionTargets(applications: selection.includeEntireCategory ? ["A"] : ["A", "B"])
    }
    func fingerprint(of selection: FamilyActivitySelection) -> String {
        selection.includeEntireCategory ? "B" : "A"
    }
    func applyBlockList(_ selection: FamilyActivitySelection) throws {
        applyCount += 1
        appliedSelections.append(selection)
        retainsSettings = true
    }
    func clear() {
        clearCount += 1
        retainsSettings = false
    }
    func retainsAppliedSettings() -> Bool { retainsSettings }
}

private final class R04Registrar: FlintSessionActivityRegistering {
    var started: [String] = []
    var stopped: [String] = []
    var registered: Set<String> = []
    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        started.append(name)
        registered.insert(name)
    }
    func stopMonitoring(_ names: [String]) {
        stopped += names
        registered.subtract(names)
    }
    func registeredActivityNames() -> Set<String> { registered }
}

/// The production key layout of `FlintGroupStore` + its session-store extension: one active
/// session key, one picker draft key, and ONE id-keyed snapshot key holding `sessionID` + targets.
private final class R04Store: FlintSessionStateStoring {
    private enum Key {
        static let activeSession = "flint.activeSession"
        static let pickerDraft = "flint.selection"
        static let sessionSelection = "flint.activeSession.selection"
    }
    private struct SessionSelection: Codable {
        let sessionID: String
        let selection: FamilyActivitySelection
    }

    private let defaults: UserDefaults
    var readable = true

    init(defaults: UserDefaults) { self.defaults = defaults }

    func loadActiveSession() -> FlintActiveSession? {
        guard let data = defaults.data(forKey: Key.activeSession) else { return nil }
        return try? JSONDecoder().decode(FlintActiveSession.self, from: data)
    }
    func hasActiveSessionRecord() -> Bool { defaults.object(forKey: Key.activeSession) != nil }
    func saveActiveSession(_ session: FlintActiveSession) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        defaults.set(data, forKey: Key.activeSession)
    }
    func clearActiveSession() { defaults.removeObject(forKey: Key.activeSession) }

    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) {
        guard let data = try? JSONEncoder().encode(SessionSelection(sessionID: sessionID,
                                                                    selection: selection)) else { return }
        defaults.set(data, forKey: Key.sessionSelection)
    }
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? {
        guard let data = defaults.data(forKey: Key.sessionSelection),
              let saved = try? JSONDecoder().decode(SessionSelection.self, from: data),
              saved.sessionID == sessionID else { return nil }
        return saved.selection
    }
    func clearSessionSelection() { defaults.removeObject(forKey: Key.sessionSelection) }
    func sessionSelectionOwner() -> String? {
        guard let data = defaults.data(forKey: Key.sessionSelection),
              let saved = try? JSONDecoder().decode(SessionSelection.self, from: data) else { return nil }
        return saved.sessionID
    }

    func saveSelection(_ selection: FamilyActivitySelection) {
        defaults.set(try? JSONEncoder().encode(selection), forKey: Key.pickerDraft)
    }
    func loadSelection() -> FamilyActivitySelection? {
        guard let data = defaults.data(forKey: Key.pickerDraft) else { return nil }
        return try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)
    }
    func clearPickerDraft() { defaults.removeObject(forKey: Key.pickerDraft) }

    func isProtectionStateReadable() -> Bool {
        readable && defaults.object(forKey: FlintProtectionLedger.initializedMarkerKey) != nil
    }
}

private final class R04Clock {
    /// 2026-10-28 10:00 in Los Angeles (the shared fixture instant).
    var now = Date(timeIntervalSince1970: 1_793_206_800)
    var monotonic: TimeInterval = 50_000
    var boot: String? = "boot-r04"

    /// Real elapsed time: both clocks move.
    func advance(_ seconds: TimeInterval) {
        now = now.addingTimeInterval(seconds)
        monotonic += seconds
    }

    /// A clock discontinuity: only the wall clock moves.
    func jumpWall(_ seconds: TimeInterval) {
        now = now.addingTimeInterval(seconds)
    }

    var flintClock: FlintClock {
        FlintClock(
            now: { [unowned self] in self.now },
            monotonic: { [unowned self] in self.monotonic },
            bootSessionID: { [unowned self] in self.boot },
            calendar: {
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
                return calendar
            })
    }
}

private final class R04Events: FlintSessionEventSink {
    var log: [FlintSessionLifecycleEvent] = []
    func handle(_ event: FlintSessionLifecycleEvent) { log.append(event) }
    var kinds: [String] {
        log.map {
            switch $0.unannotated {
            case .started: return "started"
            case .ended: return "ended"
            case .superseded: return "superseded"
            case .passUsed: return "passUsed"
            case .failedToArm: return "failedToArm"
            case .globalPassUsed: return "globalPassUsed"
            case .annotated: return "annotated"
            }
        }
    }
}
