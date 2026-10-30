import XCTest
import FamilyControls
@testable import Flint
@testable import FlintCore

/// Session start/stop/expiry/reconcile paths against fake OS seams: no Screen Time needed, and
/// the order of every registration, shield write and persisted record is observable.
final class FlintSessionControllerTests: XCTestCase {

    private var shield: FakeShield!
    private var registrar: FakeRegistrar!
    private var store: FakeSessionStore!
    private var clock: FakeClock!
    private var authorized = true
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var events: LifecycleCapture!
    private var log: CallLog!
    private var ledgerDirectory: URL!
    private var ledgerAvailable = true

    override func setUp() {
        super.setUp()
        log = CallLog()
        shield = FakeShield(log: log)
        registrar = FakeRegistrar(log: log)
        store = FakeSessionStore(log: log)
        clock = FakeClock()
        authorized = true
        suiteName = "flint.tests.session.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        events = LifecycleCapture()
        FlintSessionLifecycle.register(events)
        ledgerAvailable = true
        publications = 0
        waited = []
        waitShortfall = 0
        ledgerDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-session-ledger-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: ledgerDirectory)
        FlintSessionLifecycle.unregister(events)
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func controller(
        strategy: FlintSessionAutoClearPlan.ShortSessionStrategy = .endWarning,
        storeAvailable: Bool = true,
        reconcilePass: @escaping () -> Bool = { true },
        prepareStart: @escaping (String?) -> Result<Void, FlintEmergencyPassError> = { _ in .success(()) },
        recoveryGate: @escaping () -> FlintPassRecoveryGate = { .enforcing },
        transitionLockAvailable: Bool = true,
        afterStartCommit: @escaping () throws -> Void = {},
        afterStartApplied: @escaping () throws -> Void = {}
    ) -> FlintSessionController {
        let store = self.store!
        let defaults = self.defaults
        let directory = self.ledgerDirectory!
        let flintClock = clock.flintClock
        return FlintSessionController(environment: FlintSessionEnvironment(
            store: { storeAvailable ? store : nil },
            shield: shield,
            webRestrictions: NoSessionWebRestrictions(),
            registrar: registrar,
            isAuthorized: { [unowned self] in self.authorized },
            clock: clock.flintClock,
            shortSessionStrategy: strategy,
            healthDefaults: { defaults },
            ledger: { [unowned self] in
                guard self.ledgerAvailable else { return nil }
                return FlintProtectionLedger(directory: directory, clock: flintClock,
                                             initializedMarker: { nil }, setInitializedMarker: { _ in })
            },
            publishStatus: { [unowned self] in self.publications += 1 },
            transitionLock: { transitionLockAvailable ? FlintSessionTransitionLock(directory: directory, timeout: 0.2) : nil },
            monitorLockTimeout: 1,
            wait: { [unowned self] in self.waited.append($0); self.clock.advance($0 - self.waitShortfall) },
            reconcileEmergencyPass: reconcilePass, prepareNewSession: prepareStart, recoveryGate: recoveryGate,
            afterStartCommit: afterStartCommit, afterStartApplied: afterStartApplied,
            captureRemovalGuard: { false }, reconcileRemovalGuard: {}
        ))
    }

    /// Status projections published after committed changes.
    private var publications = 0
    /// Seconds the monitor waited out before acting on an end callback.
    private var waited: [TimeInterval] = []
    /// How far short of the requested time a wait returns (e.g. an interrupted sleep).
    private var waitShortfall: TimeInterval = 0

    private var health: FlintSessionHealth { FlintSessionHealth.load(from: defaults) }

    @discardableResult
    private func start(
        _ controller: FlintSessionController,
        minutes: Double,
        level: BreakLevel = .easy,
        source: FlintSessionSource = .manual,
        origin: FlintSessionOrigin = .appUI,
        replacing: Bool = true,
        confirmed: Bool = true,
        selection: FamilyActivitySelection = FamilyActivitySelection()
    ) throws -> FlintActiveSession {
        try controller.startBlockNow(
            selection: selection, duration: minutes * 60, breakLevel: level, source: source,
            origin: origin, replacing: replacing, confirmed: confirmed)
    }

    /// The fake shield's second target set: `selectionA` blocks {A, B}, `selectionB` only {A}.
    private let selectionA = FamilyActivitySelection()
    private let selectionB = FamilyActivitySelection(includeEntireCategory: true)

    // MARK: ADR-012 arbitration (§1–§4)

    func testEveryCommittedChangePublishesTheStatusProjection() throws {
        let c = controller()
        XCTAssertThrowsError(try start(c, minutes: 0, level: .hardcore))
        XCTAssertEqual(publications, 0, "a refused start changes nothing and publishes nothing")
        _ = try start(c, minutes: 30, level: .easy)
        XCTAssertEqual(publications, 1, "start")
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: false), .needsConfirmation)
        XCTAssertEqual(publications, 1, "an unconfirmed stop changes nothing")
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .stopped)
        XCTAssertEqual(publications, 2, "end")
    }

    // MARK: Session integrity (ADR-012 §9; R-05, R-20, R-25)

    private func ledger() -> FlintProtectionLedger {
        .init(directory: ledgerDirectory, clock: clock.flintClock,
            initializedMarker: { nil }, setInitializedMarker: { _ in })
    }

    func testRemovedActiveKeyPreservesHardcoreForOldCallbackForegroundAndStopRoutesR20PC64PC66() throws {
        let c = controller()
        let old = try start(c, minutes: 30)
        let armed = try start(c, minutes: 60, level: .hardcore)
        let accepted = try ledger().peek().get().sessionAuthority
        store.active = nil // remove ONLY the projection; the readable marker and targets remain
        let clears = shield.clearCount
        let stops = registrar.stopped
        c.handleMonitorEvent(.intervalDidEnd, activity: old.monitorName)
        XCTAssertTrue(c.reconcile().recordUnreadable)
        XCTAssertEqual(c.requestStop(route: .shortcutStop, confirmed: true), .unavailable)
        c.stop()
        c.finishIfExpired()
        XCTAssertThrowsError(try FlintShortcutsRunner.stopActiveSession(controller: c)) {
            guard case FlintShortcutsError.stateUnavailable = $0 else { return XCTFail("expected unavailable, got \($0)") }
        }
        FocusFilterController(controller: c, acknowledgeOff: { _ in .success(()) }).deactivate()
        XCTAssertEqual(shield.clearCount, clears)
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
        XCTAssertEqual(registrar.stopped, stops, "unknown state cannot retire Y's finite last-good end")
        XCTAssertTrue(registrar.registered.contains(armed.monitorName))
        XCTAssertNotNil(health.lastArmFailure)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest, accepted?.latest)
        XCTAssertNil(try ledger().peek().get().sessionAuthority?.tombstone)
    }

    func testRemovedKeyWithAnUnreadableStoreCannotClearOrMintIdleR20() throws {
        let c = controller()
        let session = try start(c, minutes: 30, level: .hardcore)
        store.active = nil; store.readable = false
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertTrue(c.reconcile().recordUnreadable)
        XCTAssertEqual(c.requestStop(confirmed: true), .unavailable)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
    }

    func testReadableGenerationMismatchCannotUseFiniteFallbackToClearSuccessorR20() throws {
        let c = controller()
        let old = try start(c, minutes: 1)
        let current = try start(c, minutes: 30, level: .hardcore)
        store.active = old
        clock.advance(60)
        c.finishIfExpired()
        c.handleMonitorEvent(.intervalDidEnd, activity: old.monitorName)
        XCTAssertTrue(c.reconcile().recordUnreadable)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.session.id, current.id)
        XCTAssertNil(try ledger().peek().get().sessionAuthority?.tombstone)
    }

    func testReadableLegacyIdleLedgerMigratesOneTombstoneButExistingGenerationNeverDoesR20() throws {
        try ledger().transaction { $0.version = 2; $0.sessionAuthority = nil; $0.sessionAuthorityVersion = nil }.get()
        XCTAssertEqual(try ledger().peek().get().version, 2)
        XCTAssertEqual(controller().reconcile().outcome, .noSession)
        XCTAssertTrue(try XCTUnwrap(ledger().peek().get().sessionAuthority).provesIdle)
        XCTAssertEqual(try ledger().peek().get().version, 3)
        let c = controller()
        _ = try start(c, minutes: 30, level: .hardcore)
        store.active = nil
        XCTAssertTrue(c.reconcile().recordUnreadable)
        XCTAssertFalse(try XCTUnwrap(ledger().peek().get().sessionAuthority).provesIdle)
    }

    func testFirstRebootObservationReanchorsBeforeAnotherForwardWallJumpR05PC54PC58() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .hardcore)
        clock.advance(600)
        _ = c.reconcile()
        clock.boot = "boot-2"; clock.monotonic = 10
        clock.now = clock.now.addingTimeInterval(60)
        _ = c.reconcile()
        XCTAssertEqual(store.active?.remainingCheckpoint?.bootID, "boot-2")
        XCTAssertEqual(store.active?.remainingCheckpoint?.remaining, 2940)
        clock.advance(10)
        clock.now = clock.now.addingTimeInterval(7200)
        c.finishIfExpired()
        XCTAssertEqual(store.active?.logicalRemaining(clock: clock.flintClock), 2930)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
    }

    func testRollbackNearExpiryAndDoubleRebootNeverRestoreSavedRemainingR05PC55PC58() throws {
        let c = controller()
        _ = try start(c, minutes: 1, level: .hardcore)
        clock.advance(55); _ = c.reconcile()
        clock.boot = "boot-2"; clock.monotonic = 10
        clock.now = clock.now.addingTimeInterval(-3600)
        _ = c.reconcile()
        XCTAssertEqual(store.active?.remainingCheckpoint?.remaining, 5)
        clock.advance(3); _ = c.reconcile()
        clock.boot = "boot-3"; clock.monotonic = 1
        clock.now = clock.now.addingTimeInterval(-3600)
        _ = c.reconcile()
        XCTAssertEqual(store.active?.remainingCheckpoint?.remaining, 2)
        clock.advance(1); clock.now = clock.now.addingTimeInterval(7200)
        c.finishIfExpired()
        XCTAssertNotNil(store.active)
        clock.advance(1); c.finishIfExpired()
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.clearCount, 1)
    }

    func testLegacyNoCheckpointRecordReanchorsOnItsFirstRebootObservationR05() throws {
        let original = FlintActiveSession(id: "legacy", name: "Legacy", startedAt: clock.now,
            endsAt: clock.now.addingTimeInterval(1800), breakLevel: .hardcore,
            monitorName: "flint.session.legacy", monotonicStart: clock.monotonic,
            bootSessionID: clock.boot, plannedDuration: 1800)
        store.active = original
        try ledger().transaction { $0.version = 2; $0.sessionAuthority = nil; $0.sessionAuthorityVersion = nil }.get()
        clock.boot = "new-boot"; clock.monotonic = 5; clock.now = clock.now.addingTimeInterval(60)
        let c = controller()
        _ = c.reconcile()
        XCTAssertEqual(store.active?.remainingCheckpoint?.remaining, 1740)
        clock.advance(10); clock.now = clock.now.addingTimeInterval(3600)
        c.finishIfExpired()
        XCTAssertEqual(store.active?.logicalRemaining(clock: clock.flintClock), 1730)
        XCTAssertEqual(shield.clearCount, 0)
    }

    func testRecreatedControllerReplaysAcceptedStartThenTerminalOutcomeR25PC03PC10() throws {
        let first = try controller().startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "lost-response", confirmed: true)
        clock.advance(2)
        let recovered = controller()
        let replay = try recovered.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "lost-response", confirmed: true)
        XCTAssertEqual(first.id, replay.id)
        XCTAssertEqual(registrar.started.count, 1)
        XCTAssertEqual(recovered.requestStop(confirmed: true), .stopped)
        XCTAssertThrowsError(try controller().startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "lost-response", confirmed: true)) {
            XCTAssertEqual($0 as? FlintSessionError, .startAlreadyEnded(sessionID: first.id, outcome: .stopped))
        }
        XCTAssertNil(store.active)
        XCTAssertEqual(registrar.started.count, 1)
    }

    func testConflictingReuseAndChangedPreviewRevisionRefuseBeforeShieldR25PC03PC10() throws {
        let c = controller()
        let preview = try c.prepareStartToken(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, route: "app", operationID: "preview")
        _ = try c.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "other-start", confirmed: true)
        XCTAssertThrowsError(try c.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: preview.operationID, expectedRevision: preview.expectedRevision, confirmed: true)) {
            XCTAssertEqual($0 as? FlintSessionError, .staleStartRevision)
        }
        XCTAssertThrowsError(try c.startBlockNow(selection: selectionB, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "other-start", confirmed: true)) {
            XCTAssertEqual($0 as? FlintSessionError, .conflictingStartRequest)
        }
        XCTAssertEqual(registrar.started.count, 1)
        XCTAssertEqual(shield.applyCount, 1)
    }

    func testExpectedRevisionIsRecheckedAtTheAcceptanceWriteR25PC10() throws {
        let c = controller()
        let token = try c.prepareStartToken(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, route: "preview")
        let foreign = FlintActiveSession(id: "foreign", name: "Foreign", startedAt: clock.now,
            endsAt: clock.now.addingTimeInterval(3600), breakLevel: .hardcore, monitorName: "flint.session.foreign")
        // Inject a changed durable revision after preflight, immediately before acceptance.
        registrar.onStart = {
            try self.ledger().transaction { $0.sessionAuthority?.adopt(foreign) }.get()
            self.store.active = foreign
        }
        XCTAssertThrowsError(try c.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: token.operationID, expectedRevision: token.expectedRevision, confirmed: true)) {
            XCTAssertEqual($0 as? FlintSessionError, .staleStartRevision)
        }
        XCTAssertNil(c.acceptedStartResult(token.operationID))
        XCTAssertEqual(store.active?.id, foreign.id)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertEqual(registrar.stopped.count, 1, "only the newly attempted registration is retired")
    }

    func testCrashAfterAcceptedLedgerWriteBeforeSessionSaveRecoversSameGenerationR25() throws {
        enum Crash: Error { case interrupted }
        let interrupted = controller(afterStartCommit: { throw Crash.interrupted })
        XCTAssertThrowsError(try interrupted.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .hardcore,
            origin: .appUI, requestID: "crash-before-save", confirmed: true))
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        let accepted = try XCTUnwrap(ledger().peek().get().sessionAuthority?.acceptedStart("crash-before-save"))
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)
        clock.advance(10)
        let recovered = controller()
        XCTAssertEqual(recovered.reconcile().outcome, .running)
        XCTAssertEqual(store.active?.id, accepted.sessionID)
        XCTAssertEqual(store.active?.logicalRemaining(clock: clock.flintClock), 1790)
        let replay = try recovered.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .hardcore,
            origin: .appUI, requestID: "crash-before-save", confirmed: true)
        XCTAssertEqual(replay.id, accepted.sessionID)
        XCTAssertEqual(registrar.started.count, 1)
        XCTAssertEqual(events.kinds.filter { $0 == "started" }.count, 1)
    }

    func testShortcutRetryUsesDurableTokenAcrossRecreatedHostAndExplicitTerminalReplayR25() throws {
        let request = FlintSessionRequest(durationMinutes: 30, breakLevel: .easy, name: "Shortcut")
        let first = try FlintShortcutsRunner.start(request, confirmed: true, controller: controller(),
            selectionResolver: { _ in self.selectionA })
        clock.advance(10)
        let retry = try FlintShortcutsRunner.start(request, confirmed: true, controller: controller(),
            selectionResolver: { _ in self.selectionA })
        XCTAssertEqual(first.id, retry.id)
        let explicit = try FlintShortcutsRunner.prepare(request, requestID: "shortcut-explicit", controller: controller(),
            selectionResolver: { _ in self.selectionA })
        let replacement = try FlintShortcutsRunner.start(explicit, confirmed: true, controller: controller())
        controller().stop()
        XCTAssertThrowsError(try FlintShortcutsRunner.start(request, confirmed: true, requestID: "shortcut-explicit",
            controller: controller(), selectionResolver: { _ in self.selectionA })) {
            XCTAssertEqual($0 as? FlintSessionError, .startAlreadyEnded(sessionID: replacement.id, outcome: .stopped))
        }
        XCTAssertEqual(registrar.started.count, 2)
    }

    func testDuplicateFocusActivationCannotRestartAfterEndUntilActualFocusOffR25() throws {
        let request = FlintSessionRequest(durationMinutes: 1, breakLevel: .easy)
        func focus() -> FocusFilterController {
            FocusFilterController(controller: controller(), selectionResolver: { _ in self.selectionA }, selectionCount: { _ in 1 },
                prepareRecovery: { _ in .success(()) }, acknowledgeOff: { _ in .success(()) })
        }
        XCTAssertTrue(focus().activate(request))
        let id = store.active?.id
        XCTAssertTrue(focus().activate(request))
        XCTAssertEqual(store.active?.id, id)
        clock.advance(60); controller().finishIfExpired()
        XCTAssertFalse(focus().activate(request), "same activation has a durable terminal result")
        XCTAssertNil(store.active)
        XCTAssertEqual(registrar.started.count, 1)
        focus().deactivate()
        XCTAssertTrue(focus().activate(request))
        XCTAssertNotEqual(store.active?.id, id)
        XCTAssertEqual(registrar.started.count, 2)
    }

    func testRefusedFocusActivationRefreshesRevisionWithoutNeedingFocusOffR25() throws {
        let c = controller()
        _ = try start(c, minutes: 30)
        let focus = FocusFilterController(controller: c, selectionResolver: { _ in self.selectionA }, selectionCount: { _ in 1 },
            prepareRecovery: { _ in .success(()) }, acknowledgeOff: { _ in .success(()) })
        let request = FlintSessionRequest(durationMinutes: 10, breakLevel: .easy)
        XCTAssertFalse(focus.activate(request), "capacity refusal does not accept the activation")
        let token = try XCTUnwrap(ledger().peek().get().sessionAuthority?.routeRequests.first { $0.route == FocusFilterController.startRoute }?.token)
        XCTAssertNil(c.acceptedStartResult(token.operationID))
        XCTAssertEqual(c.requestStop(confirmed: true), .stopped)
        XCTAssertTrue(focus.activate(request))
        XCTAssertEqual(store.active?.startRequestID, token.operationID)
        XCTAssertEqual(registrar.started.count, 2)
    }

    func testEmptyFocusSelectionIsRejectedBeforeCreatingARetryToken() throws {
        let c = controller()
        let focus = FocusFilterController(controller: c, selectionResolver: { _ in FamilyActivitySelection() },
            prepareRecovery: { _ in .success(()) })
        XCTAssertFalse(focus.activate(.init(durationMinutes: 10)))
        XCTAssertTrue(try ledger().read().get().sessionAuthority?.routeRequests.isEmpty == true)
        XCTAssertTrue(registrar.started.isEmpty)
    }

    func testTerminatedGenerationCannotBeResurrectedByRestoringItsProjectionR20() throws {
        let c = controller()
        let original = try c.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "terminal", confirmed: true)
        XCTAssertEqual(c.requestStop(confirmed: true), .stopped)
        store.active = original
        XCTAssertEqual(c.reconcile().outcome, .noSession)
        XCTAssertNil(store.active)
        XCTAssertTrue(try XCTUnwrap(ledger().peek().get().sessionAuthority).provesIdle)
        XCTAssertEqual(events.kinds.filter { $0 == "started" }.count, 1)
        XCTAssertThrowsError(try c.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "terminal", confirmed: true)) {
            XCTAssertEqual($0 as? FlintSessionError, .startAlreadyEnded(sessionID: original.id, outcome: .stopped))
        }
        XCTAssertEqual(registrar.started.count, 1)
    }

    func testStartDeliveryRecoversAfterApplicationBeforeLifecycleDelivery() throws {
        enum Crash: Error { case beforeEvent }
        XCTAssertThrowsError(try start(controller(afterStartApplied: { throw Crash.beforeEvent }), minutes: 30))
        XCTAssertNotNil(store.active)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .applied)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.startEventEmitted, false)
        XCTAssertTrue(events.log.isEmpty)
        let recovered = controller()
        _ = recovered.reconcile()
        _ = recovered.reconcile()
        XCTAssertEqual(events.kinds.filter { $0 == "started" }.count, 1)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.startEventEmitted, true)
        XCTAssertEqual(registrar.started.count, 1)
    }

    func testAcceptedButUnappliedStartRecoveredAfterItsDeadlineNeverInventsCompletion() throws {
        enum Crash: Error { case afterAcceptance }
        let interrupted = controller(afterStartCommit: { throw Crash.afterAcceptance })
        XCTAssertThrowsError(try interrupted.startBlockNow(selection: selectionA, duration: 60, breakLevel: .easy,
            origin: .appUI, requestID: "expired-unapplied", confirmed: true))
        clock.advance(75)
        let recovered = controller()
        XCTAssertEqual(recovered.reconcile().outcome, .noSession)
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertTrue(events.log.isEmpty)
        XCTAssertThrowsError(try recovered.startBlockNow(selection: selectionA, duration: 60, breakLevel: .easy,
            origin: .appUI, requestID: "expired-unapplied", confirmed: true)) {
            guard case FlintSessionError.startAlreadyEnded(_, .unconfirmed) = $0 else { return XCTFail("expected unconfirmed result") }
        }
        XCTAssertEqual(registrar.started.count, 1)
    }

    private func strictPassController(session: FlintSessionController? = nil) -> FlintEmergencyPassController {
        FlintEmergencyPassController(environment: .init(ledger: { self.ledger() }, clock: clock.flintClock,
            scope: { state, _ in
                guard !FlintSessionAuthority.recordIsUnavailable(self.store.active,
                    recordPresent: self.store.hasActiveSessionRecord(), authority: state.sessionAuthority,
                    authorityVersion: state.sessionAuthorityVersion, clock: self.clock.flintClock) else {
                    throw FlintEmergencyPassError.scopeUnavailable
                }
                return .init(revision: "strict", sessions: self.store.active.map { [$0] } ?? [], rules: [], stores: ["flint"])
            }, registerCarrier: { _, _, _ in }, terminate: { _, _ in }, release: { _ in }, resume: { _, _ in }, emit: { _ in },
            transition: { work in
                if let session { return session.withTransition { work(); return true } ?? false }
                work(); return true
            }, prepareAcceptedSession: { session?.prepareAcceptedInstallationForScope() }))
    }

    func testPassPreviewDrainsPendingInstallationOnceButReadOnlyStatusAndScopeNeverDo() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        XCTAssertThrowsError(try start(controller(afterStartCommit: { throw Crash.afterAcceptance }), minutes: 30))
        let pass = strictPassController(session: controller())
        guard case .failure(.scopeUnavailable) = pass.status() else { return XCTFail("pending projection is unknown") }
        _ = pass.currentScope(); _ = pass.resumingScope()
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertTrue(events.log.isEmpty)
        let first = try pass.preview().get()
        XCTAssertEqual(first.scope.sessions.count, 1)
        XCTAssertNotNil(store.active)
        XCTAssertEqual(shield.applyCount, 1)
        XCTAssertEqual(events.kinds, ["started"])
        _ = try pass.preview().get()
        _ = pass.status(); _ = pass.currentScope(); _ = pass.resumingScope()
        XCTAssertEqual(shield.applyCount, 1)
        XCTAssertEqual(events.kinds, ["started"])
        XCTAssertEqual(registrar.started.count, 1)
    }

    // Isolates the start-side drain before validateStartGate calls the pass scope reader.
    func testStartPreparationDrainsBeforeTheRealPassPreparationReadsItsScope() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        let pass = strictPassController()
        let gate = { FlintPassRecoveryGate.read(ledger: self.ledger(), at: .init(clock: self.clock.flintClock)) }
        let prepare: (String?) -> Result<Void, FlintEmergencyPassError> = { pass.prepareForNewSession(focusOwnerID: $0) }
        let reconcile = { if case .success = pass.reconcile() { return true }; return false }
        let interrupted = controller(reconcilePass: reconcile, prepareStart: prepare, recoveryGate: gate,
            afterStartCommit: { throw Crash.afterAcceptance })
        XCTAssertThrowsError(try start(interrupted, minutes: 30))
        XCTAssertNil(store.active)
        guard case .failure(.scopeUnavailable) = pass.prepareForNewSession() else { return XCTFail("unrepaired scope should be unavailable") }
        let recovered = controller(reconcilePass: reconcile, prepareStart: prepare, recoveryGate: gate)
        _ = try recovered.prepareStartToken(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, route: "recovered-start")
        XCTAssertEqual(recovered.reconcile().outcome, .running)
        XCTAssertNotNil(store.active)
        let applies = shield.applyCount
        _ = recovered.withTransition {}
        XCTAssertEqual(shield.applyCount, applies, "a second installation drain is a no-op")
        XCTAssertEqual(registrar.started.count, 1)
        XCTAssertEqual(events.kinds.filter { $0 == "started" }.count, 1)
    }

    func testAcceptedInstallationDrainRefusesSuspendedAndPendingPassWork() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        XCTAssertThrowsError(try start(controller(afterStartCommit: { throw Crash.afterAcceptance }), minutes: 30))
        let accepted = try XCTUnwrap(ledger().peek().get().sessionAuthority?.latest?.session)
        let instant = FlintLedgerInstant(clock: clock.flintClock)
        try ledger().transaction { state in
            _ = try state.consumePass(operationID: "pending-install-pass", hasActiveCommitment: true, at: instant).get()
            state.emergencyRecovery = .init(operationID: "pending-install-pass", at: instant,
                scope: .init(revision: "pending", sessions: [accepted], rules: [], stores: ["flint"]), carrierName: "flint.recovery.pending")
            state.passLimitThresholds = [:]; state.passSuppressedFocusOwners = []
        }.get()
        let c = controller(reconcilePass: { false }, recoveryGate: {
            FlintPassRecoveryGate.read(ledger: self.ledger(), at: .init(clock: self.clock.flintClock))
        })
        c.prepareAcceptedInstallationForScope()
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertNotNil(health.lastArmFailure)
        clock.advance(3601) // recovery expired, but its release/resume outbox is still pending
        c.prepareAcceptedInstallationForScope()
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)
    }

    func testAcceptedInstallationDrainRefusesUnknownPassState() throws {
        enum Crash: Error { case afterAcceptance }
        XCTAssertThrowsError(try start(controller(afterStartCommit: { throw Crash.afterAcceptance }), minutes: 30))
        try ledger().transaction { $0.pass.usage = .unknown }.get()
        let c = controller(recoveryGate: { .enforcing })
        c.prepareAcceptedInstallationForScope()
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertNotNil(health.lastArmFailure)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)
    }

    func testUnrelatedTransitionAndMissingTransitionLockNeverDrainAcceptedInstallation() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        XCTAssertThrowsError(try start(controller(afterStartCommit: { throw Crash.afterAcceptance }), minutes: 30))
        _ = controller().withTransition {}
        controller(transitionLockAvailable: false).prepareAcceptedInstallationForScope()
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertTrue(events.log.isEmpty)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .installing)
    }

    func testSuccessfulTargetedInstallationClearsPriorArmFailure() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        XCTAssertThrowsError(try start(controller(afterStartCommit: { throw Crash.afterAcceptance }), minutes: 30))
        controller(recoveryGate: { .unavailable }).prepareAcceptedInstallationForScope()
        XCTAssertNotNil(health.lastArmFailure)
        controller().prepareAcceptedInstallationForScope()
        XCTAssertNotNil(store.active)
        XCTAssertNil(health.lastArmFailure)
        XCTAssertEqual(shield.applyCount, 1)
    }

    func testExpiredUnappliedInstallationCleansResourcesDuringTargetedPreparation() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        XCTAssertThrowsError(try start(controller(afterStartCommit: { throw Crash.afterAcceptance }), minutes: 1))
        clock.advance(75)
        controller().prepareAcceptedInstallationForScope()
        XCTAssertNil(store.active)
        XCTAssertTrue(registrar.registered.isEmpty)
        XCTAssertTrue(store.sessionSelections.isEmpty)
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertTrue(events.log.isEmpty)
        XCTAssertTrue(try XCTUnwrap(ledger().peek().get().sessionAuthority).provesIdle)
    }

    func testExpiredUnappliedReplacementRestoresLivePreviousCommitment() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        let previous = try controller().startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "previous-live", confirmed: true)
        XCTAssertThrowsError(try controller(afterStartCommit: { throw Crash.afterAcceptance })
            .startBlockNow(selection: selectionB, duration: 60, breakLevel: .easy,
                origin: .appUI, requestID: "expired-replacement", confirmed: true))
        let failed = try XCTUnwrap(ledger().peek().get().sessionAuthority?.latest?.session)
        // The live store only retains one target snapshot; the ledger must keep the old one.
        store.sessionSelections.removeAll()
        store.active = failed
        clock.advance(75)
        controller().prepareAcceptedInstallationForScope()
        XCTAssertEqual(store.active?.id, previous.id)
        XCTAssertEqual(store.active?.logicalRemaining(clock: clock.flintClock), 1725)
        XCTAssertEqual(store.sessionSelections[previous.id]?.includeEntireCategory, false)
        XCTAssertTrue(registrar.registered.contains(previous.monitorName))
        XCTAssertFalse(registrar.registered.contains(failed.monitorName))
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertEqual(events.kinds, ["started"])
        XCTAssertEqual(controller().acceptedStartResult("previous-live")?.outcome, .running)
        XCTAssertEqual(controller().acceptedStartResult("expired-replacement")?.outcome, .unconfirmed)
    }

    func testExpiredUnappliedReplacementCompletesOnlyExpiredPreviousCommitment() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        let previous = try controller().startBlockNow(selection: selectionA, duration: 120, breakLevel: .easy,
            origin: .appUI, requestID: "previous-ended", confirmed: true)
        XCTAssertThrowsError(try controller(afterStartCommit: { throw Crash.afterAcceptance })
            .startBlockNow(selection: selectionB, duration: 60, breakLevel: .easy,
                origin: .appUI, requestID: "expired-replacement", confirmed: true))
        clock.advance(135)
        controller().prepareAcceptedInstallationForScope()
        XCTAssertNil(store.active)
        XCTAssertTrue(registrar.registered.isEmpty)
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertEqual(events.kinds, ["started", "ended"])
        let event = try XCTUnwrap(events.log.last)
        guard case let .ended(session, route, at) = event.unannotated else { return XCTFail("missing prior completion") }
        XCTAssertEqual(session.id, previous.id)
        XCTAssertEqual(route, .completed)
        XCTAssertEqual(at, previous.endsAt)
        XCTAssertEqual(controller().acceptedStartResult("previous-ended")?.outcome, .completed)
        XCTAssertEqual(controller().acceptedStartResult("expired-replacement")?.outcome, .unconfirmed)
    }

    func testPreviousRestorationRecoversCrashBetweenLedgerAndProjectionSavePC05() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        let previous = try start(controller(), minutes: 30)
        XCTAssertThrowsError(try controller(afterStartCommit: { throw Crash.afterAcceptance })
            .startBlockNow(selection: selectionB, duration: 60, breakLevel: .easy,
                origin: .appUI, requestID: "expired-replacement", confirmed: true))
        store.active = try ledger().peek().get().sessionAuthority?.latest?.session
        clock.advance(75)
        try ledger().transaction { state in
            let targets = state.sessionAuthority?.latest?.previousApplication?.selection
            try state.sessionAuthority?.restorePrevious(previous.checkpointed(clock: clock.flintClock),
                selection: targets, at: clock.now)
        }.get() // process dies before saving the restored projection
        controller().prepareAcceptedInstallationForScope()
        XCTAssertEqual(store.active?.id, previous.id)
        XCTAssertEqual(try ledger().peek().get().sessionAuthority?.latest?.phase, .applied)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertEqual(events.kinds, ["started"])
    }

    func testExpiredUnappliedReplacementAdoptsReadableTargetlessPredecessorWithoutReleasing() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        let previous = FlintActiveSession(id: "legacy", name: "Legacy", startedAt: clock.now,
            endsAt: clock.now.addingTimeInterval(1800), breakLevel: .easy, monitorName: "flint.session.legacy")
        store.active = previous
        registrar.registered.insert(previous.monitorName)
        XCTAssertThrowsError(try controller(afterStartCommit: { throw Crash.afterAcceptance })
            .startBlockNow(selection: selectionA, duration: 60, breakLevel: .easy,
                origin: .appUI, requestID: "targetless-replacement", confirmed: true))
        let failed = try XCTUnwrap(ledger().peek().get().sessionAuthority?.latest?.session)
        clock.advance(75)
        controller().prepareAcceptedInstallationForScope()
        XCTAssertEqual(store.active?.id, previous.id)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertTrue(registrar.registered.contains(previous.monitorName))
        XCTAssertFalse(registrar.stopped.contains(previous.monitorName))
        XCTAssertFalse(registrar.registered.contains(failed.monitorName))
        XCTAssertTrue(events.log.isEmpty)
        XCTAssertNotNil(health.lastArmFailure)
        XCTAssertEqual(controller().acceptedStartResult("targetless-replacement")?.outcome, .unconfirmed)
    }

    func testUnreadableTargetlessPredecessorKeepsNativeStateUntilItsOwnDeadline() throws {
        enum Crash: Error { case afterAcceptance }
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        let previous = FlintActiveSession(id: "legacy", name: "Legacy", startedAt: clock.now,
            endsAt: clock.now.addingTimeInterval(1800), breakLevel: .easy, monitorName: "flint.session.legacy")
        store.active = previous
        registrar.registered.insert(previous.monitorName)
        XCTAssertThrowsError(try controller(afterStartCommit: { throw Crash.afterAcceptance })
            .startBlockNow(selection: selectionA, duration: 60, breakLevel: .easy,
                origin: .appUI, requestID: "targetless-replacement", confirmed: true))
        let failed = try XCTUnwrap(ledger().peek().get().sessionAuthority?.latest?.session)
        store.active = failed // no positive predecessor record or frozen targets remain
        clock.advance(75)
        controller().handleMonitorEvent(.intervalDidEnd, activity: failed.monitorName)
        XCTAssertEqual(shield.clearCount, 0, "the installing replacement never owned the last-good shield")
        XCTAssertTrue(registrar.registered.contains(previous.monitorName))
        XCTAssertTrue(events.log.isEmpty)
        XCTAssertNotNil(health.lastArmFailure)
        clock.advance(1726)
        controller().handleMonitorEvent(.intervalDidEnd, activity: previous.monitorName)
        XCTAssertEqual(shield.clearCount, 1, "only the predecessor's logical end permits release")
        XCTAssertNil(store.active)
        XCTAssertEqual(events.kinds, ["ended"])
    }

    func testInterruptedRearmRecoveredAfterDeadlineCompletesTheActuallyAppliedSession() throws {
        try ledger().transaction { $0.importLegacyPass(.absent, at: .init(clock: clock.flintClock)) }.get()
        let original = try start(controller(), minutes: 1)
        clock.advance(10)
        let plan = FlintSessionAutoClearPlan.make(start: clock.now, end: original.endsAt!,
            calendar: clock.flintClock.calendar(), strategy: .endWarning)
        let rearmed = original.rearmed(monitorName: "flint.session.rearmed", plan: plan,
            at: clock.now, countsTowardLimit: true).checkpointed(clock: clock.flintClock)
        try ledger().transaction { state in
            try state.sessionAuthority?.rearm(rearmed, selection: JSONEncoder().encode(selectionA))
        }.get() // crash before projection save and stopping the old monitor
        clock.advance(65)
        controller().prepareAcceptedInstallationForScope()
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertEqual(events.kinds, ["started", "ended"])
        guard case let .ended(session, route, at) = events.log.last?.unannotated else { return XCTFail("missing completion") }
        XCTAssertEqual(session.id, original.id)
        XCTAssertEqual(route, .completed)
        XCTAssertEqual(at, original.endsAt)
    }

    func testLegacyPomodoroCadenceExecutesOnlyOneFocusRoundR24PC01PC27() throws {
        let legacy = FlintPomodoroConfig(focusMinutes: 20, breakMinutes: 30, rounds: 8)
        let request = FlintShortcutsRunner.pomodoroRoundRequest(legacy, appGroupID: "group", breakLevel: .easy)
        XCTAssertEqual(request.duration, 1200)
        XCTAssertEqual(request.appGroupID, "group")
        let session = try FlintShortcutsRunner.startPomodoro(legacy, appGroupID: nil, breakLevel: .easy,
            confirmed: true, controller: controller(), selectionResolver: { _ in self.selectionA })
        clock.advance(1200); controller().finishIfExpired()
        XCTAssertNil(store.active)
        XCTAssertEqual(registrar.started.count, 1)
        XCTAssertEqual(session.plannedDuration, 1200)
    }

    func testLogicalCompletionCallSitesUseObservedDeadlineAfterRollbackF2PC54PC55() throws {
        for monitor in [false, true] {
            let c = controller()
            let session = try start(c, minutes: 1)
            clock.now = clock.now.addingTimeInterval(-3600)
            clock.advance(63)
            let logicalEnd = clock.now.addingTimeInterval(-3)
            if monitor { c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName) }
            else { c.finishIfExpired() }
            let end = try XCTUnwrap(events.log.last { if case .ended = $0.unannotated { return true }; return false })
            guard case let .ended(ended, route, at) = end.unannotated else { return XCTFail("missing end") }
            XCTAssertEqual(ended.id, session.id)
            XCTAssertEqual(route, .completed)
            XCTAssertEqual(at, logicalEnd)
            guard case let .annotated(_, _, _, observedAt, _) = end else { return XCTFail("missing observation") }
            XCTAssertEqual(observedAt, clock.now)
        }
    }

    func testStorageReadLatencyCannotMoveTheLogicalCompletionInstantF2() throws {
        for monitor in [false, true] {
            let c = controller()
            let session = try start(c, minutes: 1)
            clock.advance(63)
            store.loads = 0
            store.onLoad = { [unowned self] _ in self.clock.advance(2) }
            if monitor { c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName) }
            else { c.finishIfExpired() }
            store.onLoad = nil
            let end = try XCTUnwrap(events.log.last { if case .ended = $0.unannotated { return true }; return false })
            guard case let .ended(_, _, at) = end.unannotated else { return XCTFail("missing end") }
            XCTAssertEqual(at, session.endsAt, "storage work cannot pull a completion two seconds earlier")
            guard case let .annotated(_, _, _, observedAt, _) = end else { return XCTFail("missing observation") }
            XCTAssertEqual(observedAt, clock.now)
        }
    }

    func testASameBootForwardClockJumpNeverEndsAHardcoreSessionEarlyPC54() throws {
        let c = controller()
        let session = try start(c, minutes: 60, level: .hardcore)
        clock.advance(10 * 60)
        clock.now = clock.now.addingTimeInterval(2 * 3600) // wall jumps; elapsed time doesn't
        let logicalEnd = clock.now.addingTimeInterval(50 * 60)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .denied(.hardcoreCommitment, until: logicalEnd),
                       "denied until the logical end, not the wall end that already looks past")
        c.finishIfExpired()
        XCTAssertNotNil(store.active, "still committed: 50 minutes remain")
        // The old registration fires at its wall end: premature, so the block stays and re-arms.
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        let rearmed = try XCTUnwrap(store.active)
        XCTAssertEqual(rearmed.id, session.id)
        XCTAssertEqual(try XCTUnwrap(rearmed.endsAt).timeIntervalSince(clock.now), 50 * 60, accuracy: 1,
                       "the end moves with the clock; remaining time is unchanged")
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
        clock.advance(50 * 60)
        c.handleMonitorEvent(.intervalDidEnd, activity: rearmed.monitorName)
        XCTAssertNil(store.active, "ends after the full committed duration")
    }

    func testAfterAForwardJumpAShortReplacementCannotOutlastHardcorePC54() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .hardcore)
        clock.advance(10 * 60)
        clock.now = clock.now.addingTimeInterval(2 * 3600) // the recorded end now reads as past
        let logicalEnd = clock.now.addingTimeInterval(50 * 60)
        XCTAssertThrowsError(try start(c, minutes: 1, level: .hardcore)) {
            XCTAssertEqual($0 as? FlintSessionError, .replacementDenied(until: logicalEnd),
                           "a 1-minute block would end the commitment 49 minutes early")
        }
    }

    func testClockChangeReArmsHaveTheirOwnBound() throws {
        let c = controller()
        var session = try start(c, minutes: 600, level: .hardcore)
        for _ in 0..<FlintSessionController.maximumClockChangeRearms {
            clock.advance(60)
            clock.now = clock.now.addingTimeInterval(24 * 3600)
            c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
            session = try XCTUnwrap(store.active)
        }
        XCTAssertEqual(session.clockRearmCount, FlintSessionController.maximumClockChangeRearms)
        XCTAssertEqual(session.rearmCount ?? 0, 0, "the misfire budget is untouched")
        clock.advance(60)
        clock.now = clock.now.addingTimeInterval(24 * 3600)
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertEqual(store.active?.rearmCount, 1, "past its bound, a clock-ahead early end is a misfire again")
    }

    func testALogicalCompletionAfterABackwardJumpStillReportsIt() throws {
        let c = controller()
        _ = try start(c, minutes: 30)
        clock.now = clock.now.addingTimeInterval(-3600)
        clock.advance(30 * 60) // elapsed time completes it; the recorded end reads as future
        events.log.removeAll()
        c.finishIfExpired()
        XCTAssertNil(store.active)
        XCTAssertEqual(events.kinds, ["ended"], "one completion, not a silently dropped outcome")
    }

    func testAReconcileAfterAWallJumpReRegistersTheLogicalEnd() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder)
        clock.advance(61) // past the repair spacing
        clock.now = clock.now.addingTimeInterval(-3600)
        let report = c.reconcile()
        XCTAssertTrue(report.rearmed)
        XCTAssertEqual(try XCTUnwrap(store.active?.endsAt).timeIntervalSince(clock.now), 59 * 60, accuracy: 1)
    }

    func testABackwardClockJumpDoesNotExtendTheSessionPC55() throws {
        let c = controller()
        _ = try start(c, minutes: 30, level: .hardcore)
        clock.now = clock.now.addingTimeInterval(-3600)
        clock.advance(30 * 60)
        c.finishIfExpired()
        XCTAssertNil(store.active, "30 minutes of elapsed time ended it, whatever the wall clock says")
    }

    func testAcrossARebootTheBoundedWallFallbackApplies() throws {
        let c = controller()
        let session = try start(c, minutes: 60, level: .hardcore)
        clock.boot = "boot-2"
        clock.monotonic = 10
        clock.now = clock.now.addingTimeInterval(10 * 60)
        XCTAssertEqual(try XCTUnwrap(session.logicalRemaining(clock: clock.flintClock)), 50 * 60, accuracy: 1)
        clock.now = clock.now.addingTimeInterval(3600)
        c.finishIfExpired()
        XCTAssertNil(store.active, "no trusted elapsed time across a reboot: a past wall end expires it (§9)")
    }

    func testARetriedStartReturnsTheSessionItStartedPC03() throws {
        let c = controller()
        let first = try c.startBlockNow(selection: FamilyActivitySelection(), duration: 1800, breakLevel: .easy,
                                        origin: .appUI, requestID: "tap-1", confirmed: true)
        clock.advance(2)
        let retry = try c.startBlockNow(selection: FamilyActivitySelection(), duration: 1800, breakLevel: .easy,
                                        origin: .appUI, requestID: "tap-1", confirmed: true)
        XCTAssertEqual(retry.id, first.id)
        XCTAssertEqual(retry.endsAt, first.endsAt, "time observations cannot extend the accepted request")
        XCTAssertEqual(registrar.started.count, 1)
    }

    func testATransitionHeldByAnotherProcessChangesNothingR20() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        clock.advance(30 * 60)
        let other = FlintSessionTransitionLock(directory: ledgerDirectory, timeout: 0.2)
        let hold = try XCTUnwrap(other.acquire(), "the other process holds the lock")
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNotNil(store.active, "a callback that can't take the lock does nothing")
        XCTAssertThrowsError(try start(c, minutes: 10)) {
            XCTAssertEqual($0 as? FlintSessionError, .protectionStateUnavailable)
        }
        hold.end()
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNil(store.active)
    }

    func testAnOuterTransitionIsReentrantForControllerCalls() throws {
        let c = controller()
        let result = try c.withTransition { () -> FlintActiveSession in
            try start(c, minutes: 30)
        }
        XCTAssertNotNil(result, "nested controller calls inside the transition don't deadlock")
        let other = FlintSessionTransitionLock(directory: ledgerDirectory, timeout: 0.2)
        let hold = try XCTUnwrap(other.acquire())
        XCTAssertNil(c.withTransition { 1 }, "another holder: the body doesn't run")
        hold.end()
    }

    func testAnOldCallbackNeverClearsAnUnreadableRecordR20() throws {
        let c = controller()
        _ = try start(c, minutes: 30)
        store.active = nil
        store.corruptRecord = true // a record is stored but no longer decodes
        let cleared = shield.clearCount
        c.handleMonitorEvent(.intervalDidEnd, activity: "flint.session.0123456789abcdef")
        XCTAssertEqual(shield.clearCount, cleared, "unknown protection is kept, not treated as no session")
    }

    func testAMonitorEndWaitsOutAnotherProcessesShortTransitionR20() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        clock.advance(30 * 60)
        let other = FlintSessionTransitionLock(directory: ledgerDirectory, timeout: 0.2)
        let hold = try XCTUnwrap(other.acquire())
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.4) { hold.end() }
        // Longer than an app-side wait (0.2 s here): the extension doesn't give up on the end.
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNil(store.active, "ended once the other transition finished")
    }

    func testABusyMonitorEndIsRepairedByTheNextReconcile() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        clock.advance(30 * 60)
        let other = FlintSessionTransitionLock(directory: ledgerDirectory, timeout: 0.2)
        let hold = try XCTUnwrap(other.acquire())
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNotNil(store.active, "still busy after the wait: nothing changes and the block stays")
        XCTAssertTrue(c.reconcile().transitionBusy)
        hold.end()
        registrar.registered.remove(session.monitorName) // iOS consumed the one-shot interval
        c.reconcile()
        XCTAssertNil(store.active, "the host's next reconcile ends the session that is over")
    }

    func testStopsDuringAnotherProcessesTransitionChangeNothing() throws {
        let c = controller()
        _ = try start(c, minutes: 30, level: .easy)
        let other = FlintSessionTransitionLock(directory: ledgerDirectory, timeout: 0.2)
        let hold = try XCTUnwrap(other.acquire())
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .unavailable)
        c.stop()
        XCTAssertNotNil(store.active, "a busy transition never ends the session")
        hold.end()
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .stopped)
    }

    func testAnOldCallbackKeepsShieldsWhenTheContainerReadsEmpty() throws {
        let cleared = shield.clearCount
        store.readable = false // e.g. before first unlock: the shared suite reads as empty
        controller().handleMonitorEvent(.intervalDidEnd, activity: "flint.session.0123456789abcdef")
        XCTAssertEqual(shield.clearCount, cleared, "an empty read is not proof there's no session")
        controller(storeAvailable: false).handleMonitorEvent(.intervalDidEnd, activity: "flint.session.0123456789abcdef")
        XCTAssertEqual(shield.clearCount, cleared, "nor is a missing container")
        store.readable = true
        controller().handleMonitorEvent(.intervalDidEnd, activity: "flint.session.0123456789abcdef")
        XCTAssertEqual(shield.clearCount, cleared + 1, "a record proven absent leaves only a stale shield")
    }

    func testALegacyRecordWithoutAnchorsFollowsItsWallEnd() throws {
        // Saved by an older build: no planned duration and no monotonic or boot anchors.
        let started = clock.now
        let json = """
        {"id":"legacy","name":"Old","startedAt":\(started.timeIntervalSinceReferenceDate),\
        "endsAt":\(started.addingTimeInterval(1800).timeIntervalSinceReferenceDate),\
        "breakLevel":"hardcore","monitorName":"flint.session.legacy"}
        """
        let legacy = try JSONDecoder().decode(FlintActiveSession.self, from: Data(json.utf8))
        XCTAssertNil(legacy.plannedDuration)
        XCTAssertNil(legacy.monotonicStart)
        clock.advance(600)
        XCTAssertEqual(try XCTUnwrap(legacy.logicalRemaining(clock: clock.flintClock)), 1200, accuracy: 0.001)
        clock.now = clock.now.addingTimeInterval(-3600)
        XCTAssertEqual(try XCTUnwrap(legacy.logicalRemaining(clock: clock.flintClock)), 1800, accuracy: 0.001,
                       "without anchors the wall fallback is still capped at the planned length")
        clock.now = clock.now.addingTimeInterval(2 * 3600)
        XCTAssertTrue(legacy.isLogicallyExpired(clock: clock.flintClock))
    }

    func testASecondReconcileAfterARepairDoesNotReArm() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder)
        clock.advance(61)
        clock.now = clock.now.addingTimeInterval(-3600)
        XCTAssertTrue(c.reconcile().rearmed)
        let registrations = registrar.started.count
        clock.advance(120)
        XCTAssertFalse(c.reconcile().rearmed, "repaired: its end now matches logical time")
        XCTAssertEqual(registrar.started.count, registrations)
    }

    func testClockJumpReArmsDoNotSpendTheMisfireBudget() throws {
        let c = controller()
        var session = try start(c, minutes: 600, level: .hardcore)
        for _ in 0...FlintSessionController.maximumRearms {
            clock.advance(60)
            clock.now = clock.now.addingTimeInterval(24 * 3600) // past the registered wall end
            c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
            session = try XCTUnwrap(store.active, "the block stays")
        }
        XCTAssertTrue(registrar.registered.contains(session.monitorName), "still re-registered after every jump")
        XCTAssertNil(health.lastArmFailure)
    }

    func testStrictSessionsNeedAnEndTimePC01() {
        XCTAssertThrowsError(try start(controller(), minutes: 0, level: .hardcore)) {
            XCTAssertEqual($0 as? FlintSessionError, .needsFiniteEnd)
        }
        XCTAssertThrowsError(try start(controller(), minutes: 0, level: .harder)) {
            XCTAssertEqual($0 as? FlintSessionError, .needsFiniteEnd)
        }
        XCTAssertNoThrow(try start(controller(), minutes: 0, level: .easy), "Easy may run until stopped")
        XCTAssertThrowsError(try start(controller(), minutes: 0, level: .easy, source: .pomodoro), "Pomodoros are finite")
    }

    func testAHardcoreStartNeedsConfirmation() {
        XCTAssertThrowsError(try start(controller(), minutes: 30, level: .hardcore, confirmed: false)) {
            XCTAssertEqual($0 as? FlintSessionError, .needsConfirmation)
        }
        XCTAssertTrue(registrar.started.isEmpty)
        XCTAssertNoThrow(try start(controller(), minutes: 30, level: .hardcore, confirmed: true))
    }

    func testAWeakerReplacementOfHardcoreIsDeniedPC04() throws {
        let c = controller()
        let hardcore = try start(c, minutes: 60, level: .hardcore)
        for (minutes, level) in [(30.0, BreakLevel.easy), (30, .hardcore), (120, .harder)] {
            XCTAssertThrowsError(try start(c, minutes: minutes, level: level)) {
                XCTAssertEqual($0 as? FlintSessionError, .replacementDenied(until: hardcore.endsAt), "\(minutes) \(level)")
            }
        }
        XCTAssertEqual(store.active, hardcore.checkpointed(clock: clock.flintClock), "original deadline, targets and guard retained")
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
        XCTAssertEqual(registrar.started.count, 1)
    }

    func testADominatingReplacementOfHardcoreIsAllowedPC05() throws {
        let c = controller()
        let hardcore = try start(c, minutes: 60, level: .hardcore)
        let longer = try start(c, minutes: 90, level: .hardcore)
        XCTAssertEqual(store.active, longer.checkpointed(clock: clock.flintClock))
        XCTAssertEqual(events.kinds.suffix(2), ["superseded", "started"])
        XCTAssertTrue(registrar.stopped.contains(hardcore.monitorName))
    }

    func testDroppingATargetIsNotDominancePC06() throws {
        let c = controller()
        let hardcore = try start(c, minutes: 60, level: .hardcore, selection: selectionA)
        XCTAssertThrowsError(try start(c, minutes: 120, level: .hardcore, selection: selectionB),
                             "a longer Hardcore block of A alone drops B") {
            XCTAssertEqual($0 as? FlintSessionError, .replacementDenied(until: hardcore.endsAt))
        }
        XCTAssertEqual(store.active, hardcore.checkpointed(clock: clock.flintClock))
    }

    func testWithoutItsOwnTargetsARunningSessionCanOnlyBeReplacedAsWeakening() throws {
        // A session from an older build has no id-keyed target snapshot; the picker selection is
        // the user's current draft and must never stand in for what is blocked.
        let legacy = FlintActiveSession(name: "Focus", startedAt: clock.now, endsAt: clock.now.addingTimeInterval(3600),
                                        breakLevel: .hardcore, monitorName: "flint.session")
        store.active = legacy
        store.selection = selectionB
        XCTAssertThrowsError(try start(controller(), minutes: 120, level: .hardcore, selection: selectionB)) {
            XCTAssertEqual($0 as? FlintSessionError, .replacementDenied(until: legacy.endsAt))
        }
        XCTAssertEqual(store.active, legacy.checkpointed(clock: clock.flintClock))
    }

    func testLegacyEasyWithoutItsOwnTargetsMayStillBeReplacedAsWeakening() {
        let easy = FlintActiveSession(name: "Focus", startedAt: clock.now, endsAt: clock.now.addingTimeInterval(3600),
                                      breakLevel: .easy, monitorName: "flint.session")
        store.active = easy
        XCTAssertNoThrow(try start(controller(), minutes: 30, level: .easy, selection: selectionB),
                         "Easy may still be replaced, as a weakening")
    }

    func testAStartNeverOverwritesAnUnreadableRecord() {
        store.corruptRecord = true
        XCTAssertThrowsError(try start(controller(), minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .protectionStateUnavailable)
        }
        XCTAssertTrue(registrar.started.isEmpty)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertNil(shield.denyAppRemoval)
    }

    func testAReplacementWaitCoversOnlyTheRequestedTargets() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder, selection: selectionA)
        XCTAssertThrowsError(try start(c, minutes: 30, level: .easy, selection: selectionA))
        clock.advance(60)
        XCTAssertThrowsError(try start(c, minutes: 30, level: .easy, selection: selectionB),
                             "other targets are a different request: a new wait") {
            XCTAssertEqual($0 as? FlintSessionError, .replacementWaiting(remaining: 60))
        }
    }

    func testABackgroundReplacementNeverCompletesAHarderWait() throws {
        let c = controller()
        let harder = try start(c, minutes: 60, level: .harder)
        XCTAssertThrowsError(try start(c, minutes: 30, level: .easy, origin: .shortcut))
        clock.advance(60)
        XCTAssertThrowsError(try start(c, minutes: 30, level: .easy, origin: .shortcut)) {
            XCTAssertEqual($0 as? FlintSessionError, .confirmInFlint)
        }
        XCTAssertEqual(store.active, harder.checkpointed(clock: clock.flintClock))
    }

    func testAHarderWaitSpanningTheBackgroundOnlyEndsInFlint() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder, origin: .focusFilter, replacing: false)
        XCTAssertEqual(c.requestStop(route: .focusFilterOff, confirmed: true), .waiting(remaining: 60))
        clock.advance(60)
        XCTAssertEqual(c.requestStop(route: .focusFilterOff, confirmed: true), .readyToConfirm,
                       "Focus off again after the wait: still no automatic completion")
        XCTAssertEqual(c.requestStop(route: .shortcutStop, confirmed: true), .readyToConfirm)
        XCTAssertNotNil(store.active)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .stopped, "the final confirmation in Flint")
    }

    func testACommittedStopProceedsWithoutASecondWait() throws {
        let c = controller()
        let harder = try start(c, minutes: 60, level: .harder)
        _ = c.requestStop(route: .manualStop, confirmed: false)
        clock.advance(60)
        // Crash after the commit, before the teardown.
        let ledger = FlintProtectionLedger(directory: ledgerDirectory!, clock: clock.flintClock,
                                           initializedMarker: { nil }, setInitializedMarker: { _ in })
        _ = ledger.transaction { $0.commitHarderWait(cause: harder.id, operationID: "stop.\(harder.id).",
                                                     at: FlintLedgerInstant(clock: self.clock.flintClock)) }
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .stopped)
        let state = try ledger.read().get()
        XCTAssertEqual(state.operations.filter { $0.id.hasPrefix("stop.\(harder.id).") }.count, 1, "committed once")
        XCTAssertNil(state.harder[harder.id], "the ended session's count is dropped")
    }

    func testAHarderSessionWaitsBeforeAWeakerReplacement() throws {
        let c = controller()
        let harder = try start(c, minutes: 60, level: .harder)
        XCTAssertThrowsError(try start(c, minutes: 30, level: .easy)) {
            XCTAssertEqual($0 as? FlintSessionError, .replacementWaiting(remaining: 60))
        }
        XCTAssertEqual(store.active, harder.checkpointed(clock: clock.flintClock))
        clock.advance(60)
        let easy = try start(c, minutes: 30, level: .easy)
        XCTAssertEqual(store.active, easy.checkpointed(clock: clock.flintClock), "after the wait, the same request replaces it")
    }

    func testAnAdditiveStartBesideARunningSessionIsCapacityUnavailablePC02() throws {
        let c = controller()
        let manual = try start(c, minutes: 60, level: .easy)
        XCTAssertThrowsError(try start(c, minutes: 30, level: .easy, origin: .focusFilter, replacing: false)) {
            XCTAssertEqual($0 as? FlintSessionError, .capacityUnavailable(runningName: manual.name, until: manual.endsAt))
        }
        XCTAssertEqual(store.active, manual.checkpointed(clock: clock.flintClock), "a Focus turning on never overwrites another session")
    }

    func testStopByTierPC07() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .easy)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: false), .needsConfirmation)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .stopped)

        let harder = try start(c, minutes: 60, level: .harder)
        XCTAssertEqual(c.requestStop(route: .shortcutStop, confirmed: true), .waiting(remaining: 60),
                       "PC-07: a Shortcut stop starts the persisted wait, never an immediate stop")
        clock.advance(30)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: false), .waiting(remaining: 30), "resumed, not restarted")
        clock.advance(30)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: false), .readyToConfirm, "no automatic completion")
        XCTAssertNotNil(store.active)
        events.log.removeAll()
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .stopped)
        XCTAssertEqual(events.log.map(\.unannotated), [.ended(harder.checkpointed(clock: clock.flintClock), route: .harderWait, at: clock.now)])

        let hardcore = try start(c, minutes: 60, level: .hardcore)
        XCTAssertEqual(c.requestStop(route: .shortcutStop, confirmed: true), .denied(.hardcoreCommitment, until: hardcore.endsAt))
        XCTAssertEqual(store.active, hardcore.checkpointed(clock: clock.flintClock))
    }

    func testCancellingAHarderWaitSpendsNothingAndRestartsItPC20() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder)
        _ = c.requestStop(route: .manualStop, confirmed: false)
        clock.advance(45)
        c.cancelPendingStop()
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: false), .waiting(remaining: 60))
    }

    func testAHarderWaitSurvivesTheProcessPC21() throws {
        _ = try start(controller(), minutes: 60, level: .harder)
        _ = controller().requestStop(route: .manualStop, confirmed: false)
        clock.advance(60)
        XCTAssertEqual(controller().requestStop(route: .manualStop, confirmed: false), .readyToConfirm,
                       "a fresh controller (relaunch) sees the same persisted wait")
    }

    func testFocusOffOnlyAffectsItsOwnSessionPC08PC09() throws {
        let c = controller()
        let manual = try start(c, minutes: 60, level: .easy)
        XCTAssertEqual(c.requestStop(route: .focusFilterOff, confirmed: true), .notApplicable)
        XCTAssertEqual(store.active, manual.checkpointed(clock: clock.flintClock), "PC-09: the manual session is untouched")
        c.stop(route: .manualStop)

        _ = try start(c, minutes: 0, level: .easy, origin: .focusFilter, replacing: false)
        XCTAssertEqual(c.requestStop(route: .focusFilterOff, confirmed: true), .stopped, "Easy follows its Focus")

        let strict = try start(c, minutes: 60, level: .hardcore, origin: .focusFilter, replacing: false)
        XCTAssertEqual(c.requestStop(route: .focusFilterOff, confirmed: true), .denied(.hardcoreCommitment, until: strict.endsAt),
                       "PC-08: a finite Hardcore block stays committed after Focus off")
    }

    func testALegacyFocusSessionKeepsItsFocusOffExit() throws {
        let legacy = FlintActiveSession(name: FlintSessionController.legacyFocusFilterName, startedAt: clock.now, endsAt: nil,
                                        breakLevel: .hardcore, monitorName: "flint.session")
        store.active = legacy
        XCTAssertEqual(controller().requestStop(route: .focusFilterOff, confirmed: true), .stopped,
                       "ADR-012 §12: an old open-ended Hardcore Focus block still ends with its Focus")
    }

    func testALegacyOpenEndedFocusStillRefusesAnOrdinaryManualStop() {
        let legacy = FlintActiveSession(name: FlintSessionController.legacyFocusFilterName, startedAt: clock.now, endsAt: nil,
                                        breakLevel: .hardcore, monitorName: "flint.session")
        store.active = legacy
        XCTAssertEqual(controller().requestStop(route: .manualStop, confirmed: true), .denied(.legacyOpenEnded, until: nil))
    }

    func testAnUnreadableSessionRecordKeepsItsShield() {
        store.corruptRecord = true
        let report = controller().reconcile()
        XCTAssertTrue(report.recordUnreadable)
        XCTAssertEqual(shield.clearCount, 0, "corrupt state never releases protection (ADR-012 §8)")
        XCTAssertEqual(controller().requestStop(route: .manualStop, confirmed: true), .unavailable)
        XCTAssertEqual(shield.clearCount, 0)
    }

    func testWeakeningWithoutTheLedgerIsRefusedPC47() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder)
        ledgerAvailable = false
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .unavailable)
        XCTAssertNotNil(store.active)
        c.stop()
        XCTAssertNotNil(store.active)
        ledgerAvailable = true
        c.stop()
        _ = try start(c, minutes: 60, level: .easy)
        ledgerAvailable = false
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .unavailable,
                       "Easy also requires its durable generation tombstone before releasing")
        XCTAssertNotNil(store.active)
        XCTAssertThrowsError(try start(c, minutes: 60, level: .easy)) {
            XCTAssertEqual($0 as? FlintSessionError, .protectionStateUnavailable)
        }
    }

    // MARK: Start refuses before touching anything

    func testUnauthorizedStartChangesNothing() {
        authorized = false
        XCTAssertThrowsError(try start(controller(), minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .notAuthorized)
        }
        XCTAssertTrue(registrar.started.isEmpty)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertNil(store.active)
        XCTAssertTrue(events.log.isEmpty)
    }

    func testRegistrationFailurePersistsNoSessionAndIsSurfaced() {
        registrar.failNextStart = true
        XCTAssertThrowsError(try start(controller(), minutes: 30, level: .hardcore)) { error in
            guard case .armingFailed? = error as? FlintSessionError else {
                return XCTFail("expected armingFailed, got \(error)")
            }
            XCTAssertNotNil((error as? LocalizedError)?.errorDescription)
        }
        XCTAssertEqual(shield.applyCount, 0, "no shield without an out-of-app end")
        XCTAssertNil(shield.denyAppRemoval)
        XCTAssertNil(store.active, "no apparent active session")
        XCTAssertNotNil(health.lastArmFailure)
        XCTAssertEqual(events.kinds, ["failedToArm"])
    }

    func testRegistrationFailureKeepsThePreviousSessionIntact() throws {
        let c = controller()
        let first = try start(c, minutes: 60)
        registrar.failNextStart = true
        XCTAssertThrowsError(try start(c, minutes: 30))
        XCTAssertEqual(store.active, first.checkpointed(clock: clock.flintClock))
        XCTAssertFalse(registrar.stopped.contains(first.monitorName), "previous end must stay armed")
    }

    func testOverCapSelectionsAreRejectedBeforeAnyWrite() {
        shield.nextCounts = FlintSelectionCounts(applications: 51)
        XCTAssertThrowsError(try start(controller(), minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .tooManyTokens(count: 51, cap: 50))
        }
        shield.nextCounts = FlintSelectionCounts(webDomains: 51)
        XCTAssertThrowsError(try start(controller(), minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .tooManyTokens(count: 51, cap: 50))
        }
        shield.nextCounts = FlintSelectionCounts(categories: 80)
        XCTAssertNoThrow(try start(controller(), minutes: 30), "categories are not token-capped")
        shield.nextCounts = FlintSelectionCounts()
        XCTAssertThrowsError(try start(controller(), minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .emptySelection)
        }
    }

    func testDurationBounds() {
        XCTAssertThrowsError(try start(controller(), minutes: 0.5)) {
            XCTAssertEqual($0 as? FlintSessionError, .durationTooShort(minimumMinutes: 1))
        }
        XCTAssertThrowsError(try start(controller(), minutes: 24 * 60)) {
            XCTAssertEqual($0 as? FlintSessionError, .durationTooLong(maximumMinutes: 23 * 60 + 59))
        }
        XCTAssertNoThrow(try start(controller(), minutes: 23 * 60 + 59))
        XCTAssertThrowsError(try start(controller(strategy: .floor), minutes: 10)) {
            XCTAssertEqual($0 as? FlintSessionError, .durationTooShort(minimumMinutes: 15))
        }
        XCTAssertTrue(registrar.started.count == 1, "only the in-range start registered")
    }

    // MARK: Successful start

    func testTimedStartArmsShieldsAndRecordsTheSession() throws {
        let session = try start(controller(), minutes: 45, level: .hardcore, source: .shortcut)
        XCTAssertEqual(registrar.started.map(\.name), [session.monitorName])
        XCTAssertTrue(session.monitorName.hasPrefix("flint.session."))
        XCTAssertTrue(FlintSessionController.isSessionActivity(session.monitorName))
        XCTAssertEqual(shield.applyCount, 1)
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
        XCTAssertEqual(store.active, session)
        XCTAssertEqual(session.endsAt, clock.now.addingTimeInterval(45 * 60))
        XCTAssertEqual(session.monotonicStart, clock.monotonic)
        XCTAssertEqual(session.usesEndWarning, false)
        let plan = try XCTUnwrap(registrar.started.first?.plan)
        XCTAssertNil(plan.warningTime)
        XCTAssertEqual(plan.intervalEnd.hour, 10)
        XCTAssertEqual(plan.intervalEnd.minute, 45)
        XCTAssertEqual(events.log, [.started(session, source: .shortcut, at: clock.now)])
    }

    func testOpenEndedStartRegistersNothing() throws {
        let session = try start(controller(), minutes: 0)
        XCTAssertNil(session.endsAt)
        XCTAssertTrue(registrar.started.isEmpty)
        XCTAssertEqual(store.active, session)
    }

    func testShortSessionPadsTheIntervalAndEndsOnTheWarning() throws {
        let session = try start(controller(), minutes: 5)
        let plan = try XCTUnwrap(registrar.started.first?.plan)
        XCTAssertEqual(plan.warningTime, DateComponents(minute: 10), "warning lands at 5 of 15 minutes")
        XCTAssertEqual(plan.intervalEnd.minute, 15)
        XCTAssertEqual(session.usesEndWarning, true)
        XCTAssertEqual(session.endsAt, clock.now.addingTimeInterval(5 * 60))
    }

    // MARK: Replacement and stale callbacks

    func testStartKeepsThePreviousDependableEndUntilTheReplacementApplies() throws {
        let c = controller()
        let first = try start(c, minutes: 60)
        log.entries.removeAll()
        let second = try start(c, minutes: 30, level: .hardcore)
        XCTAssertEqual(log.entries, [
            "register:\(second.monitorName)", "persist", "shield", "stop:\(first.monitorName)",
        ])
    }

    func testReplacementStopsThePreviousEndOnlyAfterTheNewOneIsArmed() throws {
        let c = controller()
        let first = try start(c, minutes: 60, level: .easy)
        clock.advance(60)
        let second = try start(c, minutes: 30, level: .hardcore)
        XCTAssertNotEqual(first.monitorName, second.monitorName)
        XCTAssertEqual(registrar.calls, [
            "start:\(first.monitorName)", "start:\(second.monitorName)", "stop:\(first.monitorName)",
        ])
        XCTAssertNil(shield.denyAppRemoval, "The derived store owns the new tier guard")
        XCTAssertEqual(events.kinds, ["started", "superseded", "started"])
    }

    func testStaleEndFromAReplacedSessionCannotClearTheNewOne() throws {
        let c = controller()
        let first = try start(c, minutes: 30)
        let second = try start(c, minutes: 60)
        clock.advance(30 * 60)
        events.log.removeAll()
        c.handleMonitorEvent(.intervalDidEnd, activity: first.monitorName)
        XCTAssertEqual(store.active, second.checkpointed(clock: clock.flintClock), "newer session commitment untouched")
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertTrue(registrar.stopped.contains(first.monitorName))
        XCTAssertTrue(events.log.isEmpty)
    }

    func testLegacySharedNameStillEndsALegacySession() throws {
        let legacy = FlintActiveSession(name: "Focus", startedAt: clock.now, endsAt: clock.now.addingTimeInterval(1800),
                                        breakLevel: .easy, monitorName: "flint.session")
        store.active = legacy
        clock.advance(1800)
        controller().handleMonitorEvent(.intervalDidEnd, activity: "flint.session")
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertEqual(events.log.map(\.unannotated), [.ended(legacy.checkpointed(clock: clock.flintClock), route: .completed, at: legacy.endsAt!)])
    }

    func testLegacySessionJSONStillDecodes() throws {
        let json = #"{"id":"A","name":"Focus","startedAt":0,"endsAt":1800,"breakLevel":"hardcore","monitorName":"flint.session"}"#
        let session = try JSONDecoder().decode(FlintActiveSession.self, from: Data(json.utf8))
        XCTAssertEqual(session.breakLevel, .hardcore)
        XCTAssertNil(session.monotonicStart)
        XCTAssertNil(session.usesEndWarning)
        XCTAssertEqual(FlintTimeIntegrity.verdict(for: session, clock: clock.flintClock), .unverifiable)
    }

    // MARK: Monitor-driven ends

    func testIntervalEndAtPlannedEndCompletesTheSession() throws {
        let c = controller()
        let session = try start(c, minutes: 30, level: .hardcore)
        clock.advance(30 * 60 + 1)
        events.log.removeAll()
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.clearCount, 1, "clearing the store also drops the uninstall guard")
        XCTAssertEqual(events.log.map(\.unannotated), [.ended(session.checkpointed(clock: clock.flintClock), route: .completed, at: session.endsAt!)])
    }

    func testPrematureIntervalEndKeepsTheBlockAndRearms() throws {
        let c = controller()
        let session = try start(c, minutes: 120)
        clock.advance(60 * 60) // e.g. a time-zone change made iOS fire the end an hour early
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        let kept = try XCTUnwrap(store.active)
        XCTAssertEqual(kept.id, session.id)
        XCTAssertEqual(kept.endsAt, session.endsAt, "never shortened or extended")
        XCTAssertNotEqual(kept.monitorName, session.monitorName)
        XCTAssertEqual(registrar.started.last?.name, kept.monitorName)
        XCTAssertEqual(shield.clearCount, 0)
        XCTAssertEqual(health.lastTimeAnomaly?.kind, .prematureEnd)
    }

    func testShortSessionEndsOnWarningButIgnoresAnEarlyOne() throws {
        let c = controller()
        let session = try start(c, minutes: 5)
        clock.advance(2 * 60)
        c.handleMonitorEvent(.intervalWillEndWarning, activity: session.monitorName)
        XCTAssertEqual(store.active, session.checkpointed(clock: clock.flintClock), "a warning before endsAt keeps the commitment")
        clock.advance(3 * 60)
        c.handleMonitorEvent(.intervalWillEndWarning, activity: session.monitorName)
        XCTAssertNil(store.active)
        XCTAssertEqual(events.kinds.suffix(1), ["ended"])
    }

    func testShortSessionCarrierEndIsRecoveryNotACompletion() throws {
        let c = controller()
        let session = try start(c, minutes: 5)
        clock.advance(15 * 60) // the warning never arrived; the padded interval ended
        events.log.removeAll()
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNil(store.active, "the block still ends — never left without an end")
        XCTAssertTrue(events.log.isEmpty, "ADR-012 §4: not reported as a successful 5-minute session")
        XCTAssertEqual(health.lastTimeAnomaly?.kind, .missedEndWarning)
        XCTAssertEqual(health.lastTimeAnomaly?.seconds, 10 * 60)
    }

    func testStopForgetsTheRecordBeforeStoppingItsRegistration() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        log.entries.removeAll()
        c.stop(route: .manualStop)
        XCTAssertEqual(log.entries, ["clear", "forget", "stop:\(session.monitorName)"])
        // iOS may still deliver the stopped activity's end: it finds no session to complete.
        events.log.removeAll()
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertTrue(events.log.isEmpty)
    }

    func testEndCallbacksBeforeEndsAtNeverEndTheSession() throws {
        let c = controller()
        let session = try start(c, minutes: 5)
        clock.advance(5 * 60 - 90) // 90 s early — inside the on-time window, still early
        c.handleMonitorEvent(.intervalWillEndWarning, activity: session.monitorName)
        XCTAssertEqual(store.active, session.checkpointed(clock: clock.flintClock), "early warning keeps the commitment")
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertEqual(store.active?.id, session.id, "early interval end keeps the block")
        XCTAssertEqual(shield.clearCount, 0)
        clock.advance(89) // one second before endsAt: whole-second resolution
        let endsAt = try XCTUnwrap(session.endsAt)
        let clearsBefore = shield.clearCount
        shield.onClear = { [unowned self] in
            XCTAssertGreaterThanOrEqual(self.clock.now, endsAt, "never released before the logical deadline (R-36)")
        }
        c.handleMonitorEvent(.intervalWillEndWarning, activity: store.active!.monitorName)
        XCTAssertEqual(waited, [1], "waited out the last second instead of releasing early")
        XCTAssertEqual(shield.clearCount, clearsBefore + 1)
        XCTAssertNil(store.active)
        XCTAssertEqual(events.kinds.suffix(1), ["ended"], "an on-time completion")
    }

    func testAnIntervalEndASecondEarlyWaitsThenCompletes() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        clock.advance(30 * 60 - 1)
        events.log.removeAll()
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertEqual(waited, [1])
        XCTAssertNil(store.active)
        XCTAssertEqual(events.kinds, ["ended"])
        XCTAssertNotEqual(health.lastTimeAnomaly?.kind, .prematureEnd)
    }

    func testAWaitThatEndsShortKeepsTheBlockWithoutSpendingAMisfire() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        clock.advance(30 * 60 - 1)
        waitShortfall = 0.5 // the sleep returned half a second early
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        let kept = try XCTUnwrap(store.active, "not yet at the deadline: the block stays")
        XCTAssertEqual(kept.id, session.id)
        XCTAssertNotEqual(kept.monitorName, session.monitorName, "its end is re-registered")
        XCTAssertEqual(kept.rearmCount ?? 0, 0, "a local clock effect isn't an iOS misfire")
        XCTAssertEqual(kept.clockRearmCount, 1)
        XCTAssertEqual(shield.clearCount, 0)
    }

    func testALateEndWarningRecordsItsLatencyPC76() throws {
        let c = controller()
        let session = try start(c, minutes: 5)
        clock.advance(5 * 60 + 300) // the warning arrives five minutes late
        events.log.removeAll()
        c.handleMonitorEvent(.intervalWillEndWarning, activity: session.monitorName)
        XCTAssertNil(store.active, "a late warning still ends the block")
        XCTAssertEqual(health.lastTimeAnomaly?.kind, .lateEnd)
        XCTAssertEqual(try XCTUnwrap(health.lastTimeAnomaly?.seconds), 300, accuracy: 1)
        XCTAssertTrue(events.log.isEmpty, "a late observation isn't reported as an on-time completion")
        XCTAssertTrue(waited.isEmpty)
    }

    func testOrphanEndWithNoSessionRecordClearsTheLeftoverShield() {
        controller().handleMonitorEvent(.intervalDidEnd, activity: "flint.session.orphan")
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertEqual(registrar.stopped, ["flint.session.orphan"])
        controller().handleMonitorEvent(.intervalDidStart, activity: "flint.session.orphan")
        XCTAssertEqual(shield.applyCount, 0, "a start for an unknown registration changes nothing")
    }

    func testMonitorStartReappliesShieldWithoutOwningRemovalGuard() throws {
        let c = controller()
        let session = try start(c, minutes: 30, level: .hardcore)
        shield.denyAppRemoval = nil
        c.handleMonitorEvent(.intervalDidStart, activity: session.monitorName)
        XCTAssertEqual(shield.applyCount, 2)
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")
        c.handleMonitorEvent(.intervalDidStart, activity: "flint.session.someoneelse")
        XCTAssertEqual(shield.applyCount, 2)
    }

    // MARK: Stop routes and the pass

    func testStopRoutesAndCompletionAfterPlannedEnd() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        c.stop(route: .shortcutStop)
        XCTAssertNil(store.active)
        XCTAssertTrue(registrar.stopped.contains(session.monitorName))
        XCTAssertEqual(events.log.last, .ended(session, route: .shortcutStop, at: clock.now))

        let late = try start(c, minutes: 30)
        clock.advance(31 * 60)
        c.stop(route: .manualStop)
        XCTAssertEqual(events.log.last?.unannotated, .ended(late.checkpointed(clock: clock.flintClock), route: .completed, at: late.endsAt!))
    }

    func testEmergencyPassEndsHardcoreAndReportsIt() throws {
        let c = controller()
        let session = try start(c, minutes: 60, level: .hardcore)
        XCTAssertFalse(c.canStop(session))
        let ledger = FlintProtectionLedger(directory: ledgerDirectory, clock: clock.flintClock,
            initializedMarker: { nil }, setInitializedMarker: { _ in },
            migrate: { $0.importLegacyPass(.absent, at: $1) })
        let pass = FlintEmergencyPassController(environment: .init(ledger: { ledger }, clock: clock.flintClock,
            scope: { _, _ in .init(revision: "scope", sessions: self.store.active.map { [$0] } ?? [], rules: [], stores: ["flint"]) },
            registerCarrier: { _, _, _ in }, terminate: { sessions, at in
                for ended in sessions { XCTAssertTrue(c.endCommittedEmergencyPass(ended, at: at)) }
            }, release: { _ in }, resume: { _, _ in }, emit: { recovery in
                FlintSessionLifecycle.didUsePass(operationID: recovery.operationID, sessions: recovery.scope.sessions,
                    recoveryEnd: recovery.recoveryEnd, at: recovery.acceptedAt.wall)
            }))
        let preview = try pass.preview().get()
        XCTAssertTrue(try pass.use(previewRevision: preview.revision, operationID: "test-pass").get().pass.isRecovering)
        XCTAssertNil(store.active)
        XCTAssertNil(store.passUsed, "the legacy UserDefaults path must never be written")
        XCTAssertEqual(events.kinds.suffix(2), ["ended", "globalPassUsed"])
        XCTAssertTrue(events.log.contains(.ended(session, route: .emergencyPass, at: clock.now)))
        XCTAssertEqual(pass.use(previewRevision: preview.revision, operationID: "second-pass"), .failure(.resumeProtectionFirst))
    }

    // MARK: Reconcile

    func testReconcileFinishesAnExpiredSession() throws {
        let c = controller()
        let session = try start(c, minutes: 30, level: .hardcore)
        clock.advance(40 * 60)
        events.log.removeAll()
        XCTAssertEqual(c.reconcile().outcome, .finishedExpired)
        XCTAssertNil(store.active)
        XCTAssertEqual(events.log.map(\.unannotated), [.ended(session.checkpointed(clock: clock.flintClock), route: .completed, at: session.endsAt!)])
    }

    func testReconcileRearmsAfterATimeZoneChangeWithoutMovingTheEnd() throws {
        let c = controller()
        let session = try start(c, minutes: 120)
        clock.advance(10 * 60)
        clock.timeZone = TimeZone(identifier: "America/New_York")!
        let report = c.reconcile()
        XCTAssertTrue(report.rearmed)
        let rearmed = try XCTUnwrap(store.active)
        XCTAssertEqual(rearmed.endsAt, session.endsAt)
        XCTAssertEqual(rearmed.autoClearTimeZoneID, "America/New_York")
        let plan = try XCTUnwrap(registrar.started.last?.plan)
        // 10:00 Los Angeles + 2 h = 12:00 LA = 15:00 New York.
        XCTAssertEqual(plan.intervalEnd.hour, 15)
        XCTAssertEqual(registrar.calls.suffix(2), ["start:\(rearmed.monitorName)", "stop:\(session.monitorName)"])
        XCTAssertEqual(health.lastTimeAnomaly?.kind, .timeZoneChanged)
        XCTAssertFalse(c.reconcile().rearmed, "idempotent once re-registered")
    }

    func testReconcileRearmsWhenIOSNoLongerHoldsTheRegistration() throws {
        let c = controller()
        let session = try start(c, minutes: 60)
        registrar.registered.remove(session.monitorName)
        XCTAssertTrue(c.reconcile().rearmed)
        XCTAssertNotEqual(store.active?.monitorName, session.monitorName)
    }

    func testReconcileReportsRearmFailureAndKeepsTheSession() throws {
        let c = controller()
        let session = try start(c, minutes: 60)
        registrar.registered.remove(session.monitorName)
        registrar.failNextStart = true
        let report = c.reconcile()
        XCTAssertTrue(report.rearmFailed)
        XCTAssertEqual(store.active, session)
        XCTAssertNotNil(health.lastArmFailure)
        XCTAssertTrue(c.reconcile().rearmed, "iOS accepts the next attempt")
        XCTAssertNil(health.lastArmFailure, "a recovered re-arm clears the stale failure")
    }

    func testReconcileDetectsWallClockChanges() throws {
        let c = controller()
        _ = try start(c, minutes: 120, level: .hardcore)
        clock.advance(10 * 60)
        clock.now = clock.now.addingTimeInterval(30 * 60) // wall clock pushed forward 30 min
        XCTAssertEqual(c.reconcile().timeVerdict, .wallAhead(seconds: 30 * 60))
        XCTAssertEqual(health.lastTimeAnomaly?.kind, .wallAhead)
        XCTAssertNil(shield.denyAppRemoval, "The session shield no longer owns removal denial")

        clock.now = clock.now.addingTimeInterval(-60 * 60) // and back 60 min
        XCTAssertEqual(c.reconcile().timeVerdict, .wallBehind(seconds: 60 * 60), "since the last checkpoint")
        XCTAssertEqual(health.lastTimeAnomaly?.kind, .wallBehind)
    }

    func testRebootMakesTimeUnverifiableRatherThanAnAnomaly() throws {
        let c = controller()
        _ = try start(c, minutes: 120)
        clock.now = clock.now.addingTimeInterval(20 * 60)
        clock.monotonic = 30 // fresh boot
        clock.boot = "boot-2"
        XCTAssertEqual(c.reconcile().timeVerdict, .unverifiable)
        XCTAssertNil(health.lastTimeAnomaly)
    }

    func testRepairsAreSpacedButNeverRefusedWhileLive() throws {
        let c = controller()
        _ = try start(c, minutes: 120)
        for attempt in 1...5 {
            registrar.registered.removeAll()
            XCTAssertTrue(c.reconcile().rearmed, "repair \(attempt)")
            registrar.registered.removeAll()
            let burst = c.reconcile()
            XCTAssertFalse(burst.rearmed, "a second repair within a minute waits")
            XCTAssertFalse(burst.rearmFailed)
            clock.advance(FlintSessionController.repairInterval)
        }
        XCTAssertEqual(store.active?.rearmCount, 0, "repairs don't spend the premature-end budget")
    }

    func testPrematureEndRearmsAreBounded() throws {
        let c = controller()
        let session = try start(c, minutes: 120)
        for attempt in 1...FlintSessionController.maximumRearms {
            clock.advance(5 * 60)
            c.handleMonitorEvent(.intervalDidEnd, activity: store.active!.monitorName)
            XCTAssertEqual(store.active?.rearmCount, attempt)
        }
        let lastName = store.active!.monitorName
        clock.advance(5 * 60)
        c.handleMonitorEvent(.intervalDidEnd, activity: lastName)
        XCTAssertEqual(store.active?.monitorName, lastName, "no further re-registration")
        XCTAssertEqual(store.active?.id, session.id, "the block itself is kept")
        XCTAssertNotNil(health.lastArmFailure)
        XCTAssertEqual(shield.clearCount, 0)
    }

    func testReconcileReappliesTheSessionsOwnTargetsNotThePickers() throws {
        let c = controller()
        _ = try start(c, minutes: 60)
        store.selection = nil // the picker was edited/cleared after the start
        c.reconcile()
        XCTAssertEqual(shield.applyCount, 2, "re-applied from the session's frozen snapshot")
    }

    func testReconcileDoesNotReshieldASessionEndedMeanwhile() throws {
        let c = controller()
        _ = try start(c, minutes: 60)
        // The monitor ends the session between reconcile's first read and its shield write.
        store.onLoad = { [unowned store] count in if count == 2 { store!.active = nil } }
        store.loads = 0
        c.reconcile()
        XCTAssertEqual(shield.applyCount, 1, "no shield written after the record vanished")
        XCTAssertEqual(shield.clearCount, 0, "it was the running branch, not the no-session branch")
    }

    func testReconcileReappliesTheShieldAndFlagsMissingAuthorization() throws {
        let c = controller()
        let session = try start(c, minutes: 60, level: .hardcore)
        authorized = false
        let report = c.reconcile()
        XCTAssertTrue(report.notAuthorized)
        XCTAssertEqual(store.active, session, "never ended on an authorization read")
        XCTAssertEqual(shield.applyCount, 2, "shield re-applied from the saved selection")
        XCTAssertNotNil(health.unauthorizedSince)
        authorized = true
        XCTAssertFalse(c.reconcile().notAuthorized)
        XCTAssertNil(health.unauthorizedSince)
    }

    func testAForwardJumpPastTheEndIsRecordedButNeverEndsTheSession() throws {
        let c = controller()
        _ = try start(c, minutes: 60)
        clock.advance(10 * 60)
        clock.now = clock.now.addingTimeInterval(2 * 3600)
        let report = c.reconcile()
        XCTAssertEqual(report.outcome, .running, "same boot: 50 minutes of elapsed time remain (ADR-012 §9, R-05)")
        XCTAssertTrue(report.rearmed, "its OS end moves to the new wall-clock equivalent")
        XCTAssertEqual(report.timeVerdict, .wallAhead(seconds: 2 * 3600))
        XCTAssertEqual(health.lastTimeAnomaly?.kind, .wallAhead)
    }

    func testWithoutABootIDOnlyBackwardSkewIsTrusted() throws {
        clock.boot = nil
        let c = controller()
        _ = try start(c, minutes: 120)
        clock.advance(10 * 60)
        clock.now = clock.now.addingTimeInterval(30 * 60)
        XCTAssertEqual(c.reconcile().timeVerdict, .unverifiable, "could be an undetected reboot")
        clock.now = clock.now.addingTimeInterval(-60 * 60)
        XCTAssertEqual(c.reconcile().timeVerdict, .wallBehind(seconds: 60 * 60), "since the last checkpoint")
    }

    func testRenamedZoneWithTheSameOffsetDoesNotReArm() throws {
        let c = controller()
        _ = try start(c, minutes: 60)
        clock.timeZone = try XCTUnwrap(TimeZone(identifier: "US/Pacific"))
        XCTAssertFalse(c.reconcile().rearmed)
    }

    func testReconcileWithoutSessionClearsStaleSessionSettings() {
        shield.denyAppRemoval = true
        XCTAssertEqual(controller().reconcile().outcome, .noSession)
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertEqual(shield.denyAppRemoval, true, "Only derived migration may clear the old removal setting")
    }

    func testMissingAppGroupFailsLoudly() {
        XCTAssertThrowsError(try start(controller(storeAvailable: false), minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .appGroupUnavailable)
        }
        XCTAssertTrue(registrar.started.isEmpty)
    }

    // MARK: Pure plan math

    func testEveryStartOriginAndTierRefusesRecoveryBeforeIdempotentReturnPC46() throws {
        var gate = FlintPassRecoveryGate.enforcing
        let c = controller(recoveryGate: { gate })
        let original = try c.startBlockNow(selection: selectionA, duration: 1800, breakLevel: .easy,
            origin: .appUI, requestID: "same-request", confirmed: true)
        let registrationCount = registrar.started.count
        let shieldCount = shield.applyCount
        gate = .suspended(until: clock.now.addingTimeInterval(3600))
        for origin in [FlintSessionOrigin.appUI, .shortcut, .focusFilter, .automation] {
            for tier in BreakLevel.allCases {
                XCTAssertThrowsError(try c.startBlockNow(selection: selectionA, duration: 1800, breakLevel: tier,
                    origin: origin, requestID: "same-request", confirmed: true)) {
                    XCTAssertEqual($0 as? FlintSessionError, .resumeProtectionFirst)
                }
            }
        }
        XCTAssertEqual(store.active?.id, original.id)
        XCTAssertEqual(registrar.started.count, registrationCount)
        XCTAssertEqual(shield.applyCount, shieldCount)
    }

    func testFocusSuppressionAndUnavailablePassPreparationRefuseWithoutArming() {
        let focus = controller(prepareStart: { owner in
            XCTAssertEqual(owner, FlintSessionController.focusFilterOwnerID)
            return .failure(.focusCycleRequired)
        })
        XCTAssertThrowsError(try start(focus, minutes: 30, origin: .focusFilter)) {
            XCTAssertEqual($0 as? FlintSessionError, .focusCycleRequired)
        }
        let unavailable = controller(prepareStart: { _ in .failure(.unavailable) })
        XCTAssertThrowsError(try start(unavailable, minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .protectionStateUnavailable)
        }
        XCTAssertTrue(registrar.started.isEmpty)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertNil(store.active)
    }

    func testSessionReconcileAndMonitorCannotApplyUnderRecoveryPC41() throws {
        let original = try start(controller(), minutes: 30, level: .hardcore)
        let before = shield.applyCount
        let c = controller(recoveryGate: { .suspended(until: self.clock.now.addingTimeInterval(3600)) })
        let report = c.reconcile()
        XCTAssertNotNil(report.suspendedUntil)
        c.handleMonitorEvent(.intervalDidStart, activity: original.monitorName)
        XCTAssertEqual(shield.applyCount, before)
        XCTAssertEqual(store.active?.id, original.id, "fake gate cannot authorize a termination")
        XCTAssertEqual(shield.clearCount, 0)
    }

    func testAcceptedPassReplaysBeforeNaturalExpiryAndKeepsItsOriginalEarlyEndPC44() throws {
        let ending = controller()
        let original = try start(ending, minutes: 30, level: .hardcore)
        let acceptedAt = clock.now
        let ledger = FlintProtectionLedger(directory: ledgerDirectory, clock: clock.flintClock,
            initializedMarker: { nil }, setInitializedMarker: { _ in },
            migrate: { state, at in state.importLegacyPass(.absent, at: at) })
        let scope = FlintPassScope(revision: "accepted-session", sessions: [original], rules: [], stores: ["flint"])
        let pass = FlintEmergencyPassController(environment: .init(ledger: { ledger }, clock: clock.flintClock,
            scope: { _, _ in scope }, registerCarrier: { _, _, _ in }, terminate: { sessions, at in
                for session in sessions {
                    guard ending.endCommittedEmergencyPass(session, at: at) else { throw FlintEmergencyPassError.applying }
                }
            }, release: { _ in }, resume: { _, _ in }, emit: { _ in },
            afterCommit: { throw FlintEmergencyPassError.applying }))
        let preview = try pass.preview().get()
        XCTAssertEqual(pass.use(previewRevision: preview.revision, operationID: "accepted-pass"), .failure(.applying))
        XCTAssertNotNil(store.active, "crash left the termination pending")
        XCTAssertEqual(shield.clearCount, 0)
        clock.advance(4000)
        let recovered = controller(reconcilePass: {
            if case .success = pass.reconcile() { return true }; return false
        })
        recovered.finishIfExpired()
        XCTAssertNil(store.active)
        let ends = events.log.compactMap { event -> (FlintSessionEndRoute, Date)? in
            if case let .ended(_, route, at) = event.unannotated { return (route, at) }; return nil
        }
        XCTAssertEqual(ends.count, 1)
        XCTAssertEqual(ends.first?.0, .emergencyPass)
        XCTAssertEqual(ends.first?.1, acceptedAt)
        XCTAssertEqual(try ledger.peek().get().pass.lastOperationID, "accepted-pass")
        XCTAssertFalse(try XCTUnwrap(ledger.peek().get().emergencyRecovery).releasePending)
    }

    func testCommittedTerminationNeedsPositiveOutboxAndNeverClearsSuccessor() throws {
        let c = controller()
        let original = try start(c, minutes: 30)
        XCTAssertFalse(c.endCommittedEmergencyPass(original, at: clock.now))
        XCTAssertEqual(shield.clearCount, 0)
        let ledger = FlintProtectionLedger(directory: ledgerDirectory, clock: clock.flintClock,
            initializedMarker: { nil }, setInitializedMarker: { _ in },
            migrate: { state, at in state.importLegacyPass(.absent, at: at) })
        let at = FlintLedgerInstant(clock: clock.flintClock)
        let scope = FlintPassScope(revision: "original", sessions: [original], rules: [], stores: ["flint"])
        try ledger.transaction { state in
            _ = try state.consumePass(operationID: "positive-pass", hasActiveCommitment: true, at: at).get()
            state.emergencyRecovery = .init(operationID: "positive-pass", at: at, scope: scope, carrierName: "flint.recovery.test")
            state.passLimitThresholds = [:]; state.passSuppressedFocusOwners = []
        }.get()
        let successor = FlintActiveSession(name: "Successor", startedAt: clock.now, endsAt: clock.now.addingTimeInterval(3600),
            breakLevel: .easy, monitorName: "flint.session.successor")
        store.active = successor
        XCTAssertFalse(c.endCommittedEmergencyPass(original, at: at.wall))
        XCTAssertEqual(store.active?.id, successor.id)
        XCTAssertEqual(shield.clearCount, 0)
        store.active = nil; store.corruptRecord = true
        XCTAssertFalse(c.endCommittedEmergencyPass(original, at: at.wall))
        XCTAssertEqual(shield.clearCount, 0)
    }

    func testCommittedPassTerminationReplayedTwiceClearsAndEmitsEndOnlyOnce() throws {
        let c = controller()
        let session = try start(c, minutes: 30)
        let at = FlintLedgerInstant(clock: clock.flintClock)
        try ledger().transaction { state in
            state.importLegacyPass(.absent, at: at)
            _ = try state.consumePass(operationID: "replayed-termination", hasActiveCommitment: true, at: at).get()
            state.emergencyRecovery = .init(operationID: "replayed-termination", at: at,
                scope: .init(revision: "accepted", sessions: [session], rules: [], stores: ["flint"]),
                carrierName: "flint.recovery.replayed")
            state.passLimitThresholds = [:]; state.passSuppressedFocusOwners = []
        }.get()
        XCTAssertTrue(c.endCommittedEmergencyPass(session, at: at.wall))
        XCTAssertTrue(c.endCommittedEmergencyPass(session, at: at.wall), "already terminal acknowledges the same capability")
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertNil(store.active)
        let ended = events.log.filter {
            if case let .ended(_, route, _) = $0.unannotated { return route == .emergencyPass }; return false
        }
        XCTAssertEqual(ended.count, 1)
        XCTAssertTrue(try ledger().peek().get().emergencyRecovery?.releasePending == true, "models crash before outbox acknowledgement")
    }

    func testUnavailablePassStateCannotExtendAKnownFiniteSessionOrInventCompletion() throws {
        let original = try start(controller(), minutes: 1, level: .hardcore)
        let blocked = controller(reconcilePass: { false })
        blocked.handleMonitorEvent(.intervalDidEnd, activity: original.monitorName)
        XCTAssertNotNil(store.active, "an early callback cannot release")
        XCTAssertEqual(shield.clearCount, 0)
        clock.advance(60)
        blocked.handleMonitorEvent(.intervalDidEnd, activity: original.monitorName)
        XCTAssertNotNil(store.active?.naturalEndObservedAt, "retain positive evidence until a tombstone can commit")
        XCTAssertNil(blocked.activeSession)
        XCTAssertEqual(shield.clearCount, 1)
        blocked.handleMonitorEvent(.intervalDidEnd, activity: original.monitorName)
        XCTAssertEqual(shield.clearCount, 1, "an unknown policy ledger cannot authorize another release")
        XCTAssertEqual(controller().reconcile().outcome, .noSession)
        XCTAssertNil(store.active, "readable recovery commits the retroactive tombstone")
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertFalse(events.log.contains { if case .ended = $0.unannotated { return true }; return false })
    }

    func testPlanCrossesMidnightAndKeepsWholeSecondComponents() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let start = calendar.date(from: DateComponents(year: 2026, month: 10, day: 28, hour: 23, minute: 55, second: 7))!
        let plan = FlintSessionAutoClearPlan.make(start: start, end: start.addingTimeInterval(30 * 60),
                                                  calendar: calendar, strategy: .endWarning)
        XCTAssertEqual(plan.intervalStart, DateComponents(hour: 23, minute: 55, second: 7))
        XCTAssertEqual(plan.intervalEnd, DateComponents(hour: 0, minute: 25, second: 7))
        XCTAssertNil(plan.warningTime)
        XCTAssertEqual(plan.timeZoneIdentifier, calendar.timeZone.identifier)
        XCTAssertEqual(plan.utcOffsetAtEnd, 0)
    }

    func testShortPlanWarningNeverPrecedesTheRealEnd() {
        let calendar = Calendar(identifier: .gregorian)
        let start = Date(timeIntervalSince1970: 1_000_000)
        for seconds in stride(from: 60.0, to: 15 * 60, by: 30) {
            let plan = FlintSessionAutoClearPlan.make(start: start, end: start.addingTimeInterval(seconds),
                                                      calendar: calendar, strategy: .endWarning)
            let lead = TimeInterval((plan.warningTime?.minute ?? 0) * 60)
            XCTAssertGreaterThanOrEqual(15 * 60 - lead, seconds, "warning for \(seconds)s session")
        }
        let floorPlan = FlintSessionAutoClearPlan.make(start: start, end: start.addingTimeInterval(300),
                                                       calendar: calendar, strategy: .floor)
        XCTAssertNil(floorPlan.warningTime)
    }
}

// MARK: - Fakes

private struct NoSessionWebRestrictions: FlintSessionWebRestricting {
    func reconcile(_ session: FlintActiveSession, clock: FlintClock) {}
}

/// One ordered record of OS and store writes across all fakes.
private final class CallLog {
    var entries: [String] = []
}

private final class FakeShield: FlintSessionShielding {
    let log: CallLog
    var nextCounts = FlintSelectionCounts(applications: 3)
    var applyCount = 0
    var clearCount = 0
    var denyAppRemoval: Bool?

    init(log: CallLog) { self.log = log }

    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { nextCounts }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets {
        FlintSessionTargets(applications: selection.includeEntireCategory ? ["A"] : ["A", "B"])
    }
    func fingerprint(of selection: FamilyActivitySelection) -> String { selection.includeEntireCategory ? "B" : "A" }
    func applyBlockList(_ selection: FamilyActivitySelection) throws {
        applyCount += 1
        log.entries.append("shield")
    }

    /// Observes the moment of each release.
    var onClear: (() -> Void)?

    func clear() {
        onClear?()
        clearCount += 1
        // Production clears only session-owned targets; derived migration owns removal.
        log.entries.append("clear")
    }
}

private final class FakeRegistrar: FlintSessionActivityRegistering {
    struct Start { let name: String; let plan: FlintSessionAutoClearPlan }
    let log: CallLog
    var started: [Start] = []
    var stopped: [String] = []
    var calls: [String] = []
    var registered: Set<String> = []
    var failNextStart = false
    var onStart: (() throws -> Void)?

    struct Refused: Error {}

    init(log: CallLog) { self.log = log }

    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        if failNextStart {
            failNextStart = false
            throw Refused()
        }
        try onStart?()
        started.append(Start(name: name, plan: plan))
        calls.append("start:\(name)")
        log.entries.append("register:\(name)")
        registered.insert(name)
    }

    func stopMonitoring(_ names: [String]) {
        stopped += names
        calls += names.map { "stop:\($0)" }
        log.entries += names.map { "stop:\($0)" }
        registered.subtract(names)
    }

    func registeredActivityNames() -> Set<String> { registered }
}

private final class FakeSessionStore: FlintSessionStateStoring {
    let log: CallLog
    var active: FlintActiveSession?
    var selection: FamilyActivitySelection?
    var sessionSelections: [String: FamilyActivitySelection] = [:]
    var passUsed: Date?
    var corruptRecord = false
    var loads = 0
    var onLoad: ((Int) -> Void)?

    init(log: CallLog) { self.log = log }

    func loadActiveSession() -> FlintActiveSession? {
        loads += 1
        onLoad?(loads)
        return active
    }
    func hasActiveSessionRecord() -> Bool { active != nil || corruptRecord }
    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) {
        sessionSelections[sessionID] = selection
    }
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? { sessionSelections[sessionID] }
    func clearSessionSelection() { sessionSelections.removeAll() }
    func saveActiveSession(_ session: FlintActiveSession) {
        active = session
        log.entries.append("persist")
    }
    func clearActiveSession() {
        active = nil
        log.entries.append("forget")
    }
    func saveSelection(_ selection: FamilyActivitySelection) { self.selection = selection }
    func loadSelection() -> FamilyActivitySelection? { selection }
    func emergencyPassLastUsed() -> Date? { passUsed }
    func recordEmergencyPassUse(_ date: Date) { passUsed = date }
    /// false models a locked or unavailable container: everything reads as empty.
    var readable = true
    func isProtectionStateReadable() -> Bool { readable }
}

private final class FakeClock {
    /// 2026-10-28 10:00 in Los Angeles.
    var now = Date(timeIntervalSince1970: 1_793_206_800)
    var monotonic: TimeInterval = 50_000
    var boot: String? = "boot-1"
    var timeZone = TimeZone(identifier: "America/Los_Angeles")!

    /// Real time passing: both clocks move together.
    func advance(_ seconds: TimeInterval) {
        now = now.addingTimeInterval(seconds)
        monotonic += seconds
    }

    var flintClock: FlintClock {
        FlintClock(
            now: { [unowned self] in self.now },
            monotonic: { [unowned self] in self.monotonic },
            bootSessionID: { [unowned self] in self.boot },
            calendar: { [unowned self] in
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = self.timeZone
                return calendar
            }
        )
    }
}

private final class LifecycleCapture: FlintSessionEventSink {
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
