import XCTest
import FamilyControls
import FlintCore

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
        storeAvailable: Bool = true
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
            transitionLock: { FlintSessionTransitionLock(directory: directory, timeout: 0.2) },
            monitorLockTimeout: 1,
            wait: { [unowned self] in self.waited.append($0); self.clock.advance($0 - self.waitShortfall) }
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
        XCTAssertEqual(shield.denyAppRemoval, true)
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
        XCTAssertEqual(retry, first, "no new id, deadline or registration")
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
        XCTAssertEqual(store.active, hardcore, "original deadline, targets and guard retained")
        XCTAssertEqual(shield.denyAppRemoval, true)
        XCTAssertEqual(registrar.started.count, 1)
    }

    func testADominatingReplacementOfHardcoreIsAllowedPC05() throws {
        let c = controller()
        let hardcore = try start(c, minutes: 60, level: .hardcore)
        let longer = try start(c, minutes: 90, level: .hardcore)
        XCTAssertEqual(store.active, longer)
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
        XCTAssertEqual(store.active, hardcore)
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
        XCTAssertEqual(store.active, legacy)

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
        XCTAssertEqual(store.active, harder)
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
        XCTAssertEqual(store.active, harder)
        clock.advance(60)
        let easy = try start(c, minutes: 30, level: .easy)
        XCTAssertEqual(store.active, easy, "after the wait, the same request replaces it")
    }

    func testAnAdditiveStartBesideARunningSessionIsCapacityUnavailablePC02() throws {
        let c = controller()
        let manual = try start(c, minutes: 60, level: .easy)
        XCTAssertThrowsError(try start(c, minutes: 30, level: .easy, origin: .focusFilter, replacing: false)) {
            XCTAssertEqual($0 as? FlintSessionError, .capacityUnavailable(runningName: manual.name, until: manual.endsAt))
        }
        XCTAssertEqual(store.active, manual, "a Focus turning on never overwrites another session")
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
        XCTAssertEqual(events.log, [.ended(harder, route: .harderWait, at: clock.now)])

        let hardcore = try start(c, minutes: 60, level: .hardcore)
        XCTAssertEqual(c.requestStop(route: .shortcutStop, confirmed: true), .denied(.hardcoreCommitment, until: hardcore.endsAt))
        XCTAssertEqual(store.active, hardcore)
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
        XCTAssertEqual(store.active, manual, "PC-09: the manual session is untouched")
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
        ledgerAvailable = false
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .unavailable)
        XCTAssertNotNil(store.active)
        c.stop()
        _ = try start(c, minutes: 60, level: .easy)
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true), .stopped, "Easy needs no ledger")
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
        XCTAssertEqual(store.active, first)
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
        XCTAssertEqual(shield.denyAppRemoval, true)
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

    func testStartOrderIsRegisterPersistStopPreviousThenShield() throws {
        let c = controller()
        let first = try start(c, minutes: 60)
        log.entries.removeAll()
        let second = try start(c, minutes: 30, level: .hardcore)
        XCTAssertEqual(log.entries, [
            "register:\(second.monitorName)", "persist", "stop:\(first.monitorName)", "shield", "deny:true",
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
        XCTAssertEqual(shield.denyAppRemoval, true, "the new tier's guard is asserted")
        XCTAssertEqual(events.kinds, ["started", "superseded", "started"])
    }

    func testStaleEndFromAReplacedSessionCannotClearTheNewOne() throws {
        let c = controller()
        let first = try start(c, minutes: 30)
        let second = try start(c, minutes: 60)
        clock.advance(30 * 60)
        events.log.removeAll()
        c.handleMonitorEvent(.intervalDidEnd, activity: first.monitorName)
        XCTAssertEqual(store.active, second, "newer session untouched")
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
        XCTAssertEqual(events.log, [.ended(legacy, route: .completed, at: legacy.endsAt!)])
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
        XCTAssertEqual(events.log, [.ended(session, route: .completed, at: session.endsAt!)])
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
        XCTAssertEqual(store.active, session, "a warning before endsAt is ignored")
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
        XCTAssertEqual(store.active, session, "early warning ignored")
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

    func testMonitorStartReappliesShieldAndGuardForTheCurrentSessionOnly() throws {
        let c = controller()
        let session = try start(c, minutes: 30, level: .hardcore)
        shield.denyAppRemoval = nil
        c.handleMonitorEvent(.intervalDidStart, activity: session.monitorName)
        XCTAssertEqual(shield.applyCount, 2)
        XCTAssertEqual(shield.denyAppRemoval, true)
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
        XCTAssertEqual(events.log.last, .ended(late, route: .completed, at: late.endsAt!))
    }

    func testEmergencyPassEndsHardcoreAndReportsIt() throws {
        let c = controller()
        let session = try start(c, minutes: 60, level: .hardcore)
        XCTAssertFalse(c.canStop(session))
        XCTAssertTrue(c.useEmergencyPass())
        XCTAssertNil(store.active)
        XCTAssertEqual(store.passUsed, clock.now)
        XCTAssertEqual(events.kinds.suffix(2), ["passUsed", "ended"])
        XCTAssertEqual(events.log.last, .ended(session, route: .emergencyPass, at: clock.now))
        _ = try start(c, minutes: 60, level: .hardcore)
        XCTAssertFalse(c.useEmergencyPass(), "one per rolling week")
    }

    // MARK: Reconcile

    func testReconcileFinishesAnExpiredSession() throws {
        let c = controller()
        let session = try start(c, minutes: 30, level: .hardcore)
        clock.advance(40 * 60)
        events.log.removeAll()
        XCTAssertEqual(c.reconcile().outcome, .finishedExpired)
        XCTAssertNil(store.active)
        XCTAssertEqual(events.log, [.ended(session, route: .completed, at: session.endsAt!)])
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
        XCTAssertEqual(shield.denyAppRemoval, true)

        clock.now = clock.now.addingTimeInterval(-60 * 60) // and back 60 min
        XCTAssertEqual(c.reconcile().timeVerdict, .wallBehind(seconds: 30 * 60))
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
        XCTAssertEqual(c.reconcile().timeVerdict, .wallBehind(seconds: 30 * 60))
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
        XCTAssertNil(shield.denyAppRemoval)
    }

    func testMissingAppGroupFailsLoudly() {
        XCTAssertThrowsError(try start(controller(storeAvailable: false), minutes: 30)) {
            XCTAssertEqual($0 as? FlintSessionError, .appGroupUnavailable)
        }
        XCTAssertTrue(registrar.started.isEmpty)
    }

    // MARK: Pure plan math

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
    func setDenyAppRemoval(_ deny: Bool) {
        denyAppRemoval = deny
        log.entries.append("deny:\(deny)")
    }
    /// Observes the moment of each release.
    var onClear: (() -> Void)?

    func clear() {
        onClear?()
        clearCount += 1
        denyAppRemoval = nil
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

    struct Refused: Error {}

    init(log: CallLog) { self.log = log }

    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        if failNextStart {
            failNextStart = false
            throw Refused()
        }
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
