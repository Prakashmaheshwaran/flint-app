import XCTest
import FamilyControls
@testable import Flint
@testable import FlintCore

/// F01: the frozen Pomodoro plan and its pure phase function.
final class FlintPomodoroPlanTests: XCTestCase {

    private func plan(_ focus: Int, _ rest: Int, _ rounds: Int,
                      long: Int? = nil, every: Int? = nil) -> FlintPomodoroPlan {
        FlintPomodoroPlan(focusMinutes: focus, restMinutes: rest, rounds: rounds,
                          longRestMinutes: long, longRestEvery: every)!
    }

    func testDesignTableVectorsWithoutALongRestF01() {
        let plan = plan(25, 5, 4)
        XCTAssertEqual(plan.totalDuration, 115 * 60)
        func phase(_ minutes: Double) -> FlintPomodoroPlan.Phase? {
            plan.phaseAt(elapsed: minutes * 60).phase
        }
        XCTAssertEqual(phase(0)?.round, 1)
        XCTAssertEqual(phase(0)?.kind, .focus)
        XCTAssertEqual(phase(0)?.start, 0)
        XCTAssertEqual(phase(0)?.end, 25 * 60)
        XCTAssertEqual(phase(24.99)?.kind, .focus)
        XCTAssertEqual(phase(25)?.kind, .rest)
        XCTAssertEqual(phase(25)?.round, 1)
        XCTAssertEqual(phase(25)?.start, 25 * 60)
        XCTAssertEqual(phase(25)?.end, 30 * 60)
        XCTAssertEqual(phase(30)?.kind, .focus)
        XCTAssertEqual(phase(30)?.round, 2)
        XCTAssertEqual(phase(114.99)?.round, 4)
        XCTAssertEqual(phase(114.99)?.start, 90 * 60)
        XCTAssertEqual(phase(114.99)?.end, 115 * 60)
        XCTAssertEqual(plan.phaseAt(elapsed: 115 * 60), .finished)
    }

    func testDesignTableVectorsWithALongRestEveryTwoRoundsF01() {
        let plan = plan(25, 5, 4, long: 15, every: 2)
        XCTAssertEqual(plan.totalDuration, 125 * 60)
        let second = plan.phaseAt(elapsed: 55 * 60).phase
        XCTAssertEqual(second?.kind, .longRest)
        XCTAssertEqual(second?.round, 2)
        XCTAssertEqual(second?.start, 55 * 60)
        XCTAssertEqual(second?.end, 70 * 60)
        XCTAssertEqual(plan.phaseAt(elapsed: 70 * 60).phase?.kind, .focus)
        XCTAssertEqual(plan.phaseAt(elapsed: 70 * 60).phase?.round, 3)
        let third = plan.phaseAt(elapsed: 95 * 60).phase
        XCTAssertEqual(third?.kind, .rest)
        XCTAssertEqual(third?.start, 95 * 60)
        XCTAssertEqual(third?.end, 100 * 60)
        XCTAssertEqual(plan.phaseAt(elapsed: 125 * 60), .finished)
    }

    func testPhasesRestsAndNegativeElapsedClampingF01() {
        let plan = plan(25, 5, 4)
        XCTAssertEqual(plan.phases.count, 7)
        XCTAssertEqual(plan.rests.map(\.index), [1, 2, 3])
        XCTAssertEqual(plan.rests.map(\.phaseIndex), [1, 3, 5])
        XCTAssertFalse(plan.rests.contains { $0.isLong })
        XCTAssertEqual(plan.phaseAt(elapsed: -30).phase?.round, 1)
        XCTAssertEqual(plan.phaseAt(elapsed: -.infinity).phase?.index, 0)
        XCTAssertEqual(plan.phaseAt(elapsed: .infinity), .finished)
    }

    func testValidationBoundsAndPairedLongRestF01() {
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 0, restMinutes: 5, rounds: 4))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 3, restMinutes: 5, rounds: 4))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 7, restMinutes: 5, rounds: 4))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 125, restMinutes: 5, rounds: 4))
        XCTAssertNotNil(FlintPomodoroPlan(focusMinutes: 120, restMinutes: 60, rounds: 8))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 0, rounds: 4))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 61, rounds: 4))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 1))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 9))
        // Both long-rest fields, or neither.
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4, longRestMinutes: 15))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4, longRestEvery: 2))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4, longRestMinutes: 4, longRestEvery: 2))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4, longRestMinutes: 61, longRestEvery: 2))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4, longRestMinutes: 30, longRestEvery: 1))
        XCTAssertNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4, longRestMinutes: 30, longRestEvery: 4))
        XCTAssertNotNil(FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4, longRestMinutes: 5, longRestEvery: 2))
    }

    func testCodableConfigAndSessionRecordCompatibilityF01() throws {
        let plan = plan(30, 10, 5, long: 20, every: 2)
        let decoded = try JSONDecoder().decode(FlintPomodoroPlan.self, from: JSONEncoder().encode(plan))
        XCTAssertEqual(decoded, plan)

        // A cadence saved before the long-rest fields existed still loads.
        let legacyConfig = Data(#"{"focusMinutes":25,"breakMinutes":5,"rounds":4}"#.utf8)
        let config = try JSONDecoder().decode(FlintPomodoroConfig.self, from: legacyConfig)
        XCTAssertEqual(config, FlintPomodoroConfig(focusMinutes: 25, breakMinutes: 5, rounds: 4))
        XCTAssertNil(config.longRestMinutes)
        XCTAssertEqual(FlintPomodoroPlan(config: config), .standard)

        // A session record saved before F01 decodes without a plan.
        let legacy = FlintActiveSession(name: "Focus", startedAt: Date(timeIntervalSince1970: 1_800_000_000),
                                        endsAt: Date(timeIntervalSince1970: 1_800_001_800), breakLevel: .easy,
                                        monitorName: "flint.session.abc")
        let raw = String(decoding: try JSONEncoder().encode(legacy), as: UTF8.self)
            .replacingOccurrences(of: ",\"pomodoro\":null", with: "")
        let record = try JSONDecoder().decode(FlintActiveSession.self, from: Data(raw.utf8))
        XCTAssertNil(record.pomodoro)
        XCTAssertNil(record.pomodoroPhase(clock: .system))
    }
}

/// F01: the parent session's carrier pre-registration, phase enforcement, teardown and history,
/// against the same fake OS seams the session controller tests use.
final class FlintPomodoroSessionTests: XCTestCase {

    private var shield: PomoShield!
    private var web: PomoWebRestrictions!
    private var registrar: PomoRegistrar!
    private var store: PomoStore!
    private var clock: PomoClock!
    private var events: PomoLifecycleCapture!
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var ledgerDirectory: URL!

    override func setUp() {
        super.setUp()
        shield = PomoShield()
        web = PomoWebRestrictions()
        registrar = PomoRegistrar()
        store = PomoStore()
        clock = PomoClock()
        events = PomoLifecycleCapture()
        FlintSessionLifecycle.register(events)
        suiteName = "flint.tests.pomodoro.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        ledgerDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-pomodoro-ledger-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: ledgerDirectory)
        FlintSessionLifecycle.unregister(events)
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private var plan: FlintPomodoroPlan { .standard }

    private func controller(strategy: FlintSessionAutoClearPlan.ShortSessionStrategy = .endWarning) -> FlintSessionController {
        let store = self.store!
        let defaults = self.defaults!
        let directory = self.ledgerDirectory!
        let clock = self.clock!
        return FlintSessionController(environment: FlintSessionEnvironment(
            store: { store },
            shield: shield,
            webRestrictions: web,
            registrar: registrar,
            isAuthorized: { true },
            clock: clock.flintClock,
            shortSessionStrategy: strategy,
            healthDefaults: { defaults },
            ledger: { FlintProtectionLedger(directory: directory, clock: clock.flintClock,
                                            initializedMarker: { nil }, setInitializedMarker: { _ in }) },
            publishStatus: {},
            transitionLock: { FlintSessionTransitionLock(directory: directory, timeout: 0.2) },
            monitorLockTimeout: 1,
            wait: { _ in },
            reconcileEmergencyPass: { true },
            prepareNewSession: { _ in .success(()) },
            recoveryGate: { .enforcing },
            captureRemovalGuard: { false },
            reconcileRemovalGuard: {}
        ))
    }

    private func ledger() -> FlintProtectionLedger {
        FlintProtectionLedger(directory: ledgerDirectory, clock: clock.flintClock,
                              initializedMarker: { nil }, setInitializedMarker: { _ in })
    }

    @discardableResult
    private func start(_ controller: FlintSessionController, plan: FlintPomodoroPlan? = nil,
                       level: BreakLevel = .easy) throws -> FlintActiveSession {
        try controller.startBlockNow(
            selection: FamilyActivitySelection(), duration: 30 * 60, breakLevel: level,
            source: .pomodoro, origin: .appUI, confirmed: true,
            pomodoro: plan ?? self.plan)
    }

    private func carriers(_ session: FlintActiveSession) -> [String] {
        FlintSessionController.pomodoroCarrierNames(for: session)
    }

    private func plannedRestEvents(_ kind: FlintPlannedRestKind, index: Int? = nil) -> [FlintSessionLifecycleEvent] {
        events.log.filter {
            guard case let .plannedRest(_, restIndex, restKind, _, _, _) = $0.unannotated else { return false }
            return restKind == kind && (index == nil || restIndex == index)
        }
    }

    // MARK: Pre-registration

    func testStartRegistersOneCarrierPerRestWithTheRestIntervalAndWarningF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        XCTAssertEqual(names.count, 3)
        XCTAssertEqual(registrar.started.count, 4, "the parent end plus three rests")
        XCTAssertEqual(Set(registrar.started.map(\.name)), Set([session.monitorName] + names))
        XCTAssertTrue(registrar.registered.contains(session.monitorName))
        for name in names { XCTAssertTrue(registrar.registered.contains(name)) }
        XCTAssertTrue(names.allSatisfy(FlintSessionController.isPomodoroCarrier))
        XCTAssertFalse(FlintSessionController.isSessionActivity(names[0]))

        // Rest 1: 10:25–10:30, padded to a 15-minute interval with a 10-minute warning so the
        // warning lands on the logical rest end (the §4 deadline adapter).
        let first = try XCTUnwrap(registrar.started.first { $0.name == names[0] }?.plan)
        XCTAssertEqual(first.intervalStart.hour, 10)
        XCTAssertEqual(first.intervalStart.minute, 25)
        XCTAssertEqual(first.intervalEnd.minute, 40)
        XCTAssertEqual(first.warningTime?.minute, 10)
        XCTAssertTrue(first.usesEndWarning)
        let third = try XCTUnwrap(registrar.started.first { $0.name == names[2] }?.plan)
        XCTAssertEqual(third.intervalStart.minute, 25, "rest 3 follows the third focus round: 11:25–11:30")
        XCTAssertEqual(third.intervalStart.hour, 11)
        XCTAssertEqual(third.warningTime?.minute, 10)
    }

    func testALongRestCarrierIsNotPaddedAndCarriesNoWarningF01() throws {
        let c = controller()
        let long = FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4,
                                     longRestMinutes: 15, longRestEvery: 2)!
        let session = try start(c, plan: long)
        let names = carriers(session)
        let second = try XCTUnwrap(registrar.started.first { $0.name == names[1] }?.plan)
        XCTAssertNil(second.warningTime, "a 15-minute rest needs no padded warning")
        XCTAssertEqual(second.intervalStart.hour, 10)
        XCTAssertEqual(second.intervalStart.minute, 55)
        XCTAssertEqual(second.intervalEnd.hour, 11)
        XCTAssertEqual(second.intervalEnd.minute, 10)
    }

    func testRefusedRestRegistrationRollsBackEveryCarrierAndLeavesNoSessionF01() throws {
        let c = controller()
        registrar.failOnStartIndex = 3 // parent, rest 1, then rest 2 is refused
        XCTAssertThrowsError(try start(c, plan: plan)) {
            guard case FlintSessionError.armingFailed = $0 else { return XCTFail("expected armingFailed, got \($0)") }
        }
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0, "nothing is shielded when the chain cannot be armed")
        XCTAssertTrue(store.sessionSelections.isEmpty)
        let sessionNames = registrar.started.map(\.name)
        XCTAssertEqual(sessionNames.count, 2)
        for name in sessionNames { XCTAssertTrue(registrar.stopped.contains(name), "\(name) rolled back") }
        XCTAssertEqual(events.kinds.filter { $0 == "failedToArm" }.count, 1)
        XCTAssertFalse(events.kinds.contains("started"))
    }

    func testStartIsRefusedWhenTheSharedRegistrationPoolLacksHeadroomF01() throws {
        let c = controller()
        // threshold 20 − 2 spare = 18: 15 held + the parent + 3 rests = 19 is refused.
        for index in 0..<15 { registrar.registered.insert("flint.session.pool\(index)") }
        XCTAssertThrowsError(try start(c, plan: plan)) {
            guard case FlintSessionError.armingFailed = $0 else { return XCTFail("expected armingFailed, got \($0)") }
        }
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertEqual(events.kinds.filter { $0 == "failedToArm" }.count, 1)
    }


    func testHeadroomCountsTheParentOnceSoAnExactFitStartsF01() throws {
        let c = controller()
        // 14 held + the parent + 3 rests = 18, exactly the spare-preserving ceiling.
        for index in 0..<14 { registrar.registered.insert("flint.session.pool\(index)") }
        let session = try start(c, plan: plan)
        XCTAssertEqual(store.active?.id, session.id)
    }

    func testARegistrationIOSDoesNotHoldRefusesTheChainF01() throws {
        let c = controller()
        registrar.dropOnStartIndex = 3 // rest 2 returns without error but is not held
        XCTAssertThrowsError(try start(c, plan: plan)) {
            guard case FlintSessionError.armingFailed(let reason) = $0 else {
                return XCTFail("expected armingFailed, got \($0)")
            }
            XCTAssertEqual(reason, "registration_not_observed")
        }
        XCTAssertNil(store.active)
        XCTAssertEqual(shield.applyCount, 0)
        XCTAssertTrue(registrar.registered.isEmpty, "every registration of the attempt is rolled back")
        XCTAssertEqual(events.kinds.filter { $0 == "failedToArm" }.count, 1)
    }

    func testRestCarriersKeepTheDeadlineAdapterUnderTheFloorStrategyF01() throws {
        let c = controller(strategy: .floor)
        let session = try start(c, plan: plan)
        let first = try XCTUnwrap(registrar.started.first { $0.name == carriers(session)[0] }?.plan)
        XCTAssertEqual(first.warningTime?.minute, 10, "a 5-minute rest still warns at its logical end")
    }

    // MARK: Phase enforcement

    func testRestCallbackClearsTheSessionStoreAndFocusResumesItF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        XCTAssertEqual(shield.applyCount, 1, "the start applies focus")

        clock.advance(25 * 60 + 60) // inside rest 1
        c.handlePomodoroCarrier(.intervalDidStart, activity: names[0])
        XCTAssertEqual(shield.clearCount, 1)
        XCTAssertEqual(shield.applyCount, 1)

        clock.advance(5 * 60 - 60) // rest 1 ends, focus 2 begins
        c.handlePomodoroCarrier(.intervalDidEnd, activity: names[0])
        XCTAssertEqual(shield.applyCount, 2, "focus reapplies the selection")
        XCTAssertEqual(shield.clearCount, 1)
    }

    func testAnEarlyDidStartDuringFocusDoesNotReleaseF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        clock.advance(60)
        c.handlePomodoroCarrier(.intervalDidStart, activity: carriers(session)[0])
        XCTAssertEqual(shield.clearCount, 0, "a callback never acts on its own name")
        XCTAssertEqual(shield.applyCount, 2, "focus stays applied")
        XCTAssertTrue(plannedRestEvents(.started).isEmpty)
    }

    func testAStaleTokenCarrierIsStoppedAndNeverReleasesFocusF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let stale = "flint.pomo.deadbeefdeadbeef.1"
        registrar.registered.insert(stale)
        let clearest = shield.clearCount
        c.handlePomodoroCarrier(.intervalDidStart, activity: stale)
        XCTAssertTrue(registrar.stopped.contains(stale))
        XCTAssertEqual(shield.clearCount, clearest, "a stale wake-up during focus releases nothing")
        XCTAssertFalse(carriers(session).contains(where: registrar.stopped.contains), "the live chain keeps its carriers")
    }

    func testSameBootWallJumpForwardOrBackDoesNotMoveThePhaseF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(25 * 60 + 60) // rest 1
        clock.now = clock.now.addingTimeInterval(3600) // wall jumps forward
        let stored = try XCTUnwrap(store.active)
        XCTAssertEqual(stored.pomodoroPhase(clock: clock.flintClock)?.phase?.kind, .rest)
        XCTAssertEqual(stored.pomodoroPhase(clock: clock.flintClock)?.phase?.round, 1)
        c.handlePomodoroCarrier(.intervalDidStart, activity: names[0])
        XCTAssertEqual(shield.clearCount, 1, "the rest still clears during the jump")
        clock.now = clock.now.addingTimeInterval(-7200) // and back
        let after = try XCTUnwrap(store.active)
        XCTAssertEqual(after.pomodoroPhase(clock: clock.flintClock)?.phase?.kind, .rest)
        XCTAssertEqual(after.pomodoroPhase(clock: clock.flintClock)?.phase?.round, 1)
    }

    func testBoundedRebootReanchoringNeverRestartsACompletedRoundF01() throws {
        let c = controller()
        _ = try start(c, plan: plan)
        clock.advance(30 * 60) // past rest 1, into focus 2
        XCTAssertEqual(store.active?.pomodoroPhase(clock: clock.flintClock)?.phase?.round, 2)

        clock.boot = "boot-2"
        clock.monotonic = 10 // a reboot resets the monotonic clock
        XCTAssertEqual(c.reconcile().outcome, .running)
        let afterReboot = try XCTUnwrap(store.active)
        XCTAssertEqual(afterReboot.pomodoroPhase(clock: clock.flintClock)?.phase?.round, 2)
        XCTAssertEqual(afterReboot.pomodoroPhase(clock: clock.flintClock)?.phase?.kind, .focus)
        XCTAssertEqual(afterReboot.remainingCheckpoint?.timeUncertain, true)

        // A backward wall jump in the new boot can never restore the completed round either.
        clock.now = clock.now.addingTimeInterval(-600)
        XCTAssertEqual(store.active?.pomodoroPhase(clock: clock.flintClock)?.phase?.round, 2)
    }

    func testHardcoreParentRefusesAnEarlyStopDuringARestF01() throws {
        let c = controller()
        _ = try start(c, plan: plan, level: .hardcore)
        clock.advance(25 * 60 + 60) // inside rest 1
        XCTAssertEqual(c.requestStop(route: .manualStop, confirmed: true),
                       .denied(.hardcoreCommitment, until: store.active?.stableLogicalEnd(clock: clock.flintClock)))
        XCTAssertNotNil(store.active)
        XCTAssertEqual(shield.clearCount, 0)
    }

    // MARK: Teardown

    func testCompletionStopsEveryCarrierAndEmitsOneCompletedEventF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(115 * 60)
        c.finishIfExpired()
        XCTAssertNil(store.active)
        for name in [session.monitorName] + names { XCTAssertTrue(registrar.stopped.contains(name)) }
        XCTAssertTrue(registrar.registered.isEmpty)
        XCTAssertEqual(shield.clearCount, 1)
        let completed = events.log.filter {
            if case let .ended(_, route, _) = $0.unannotated { return route == .completed }
            return false
        }
        XCTAssertEqual(completed.count, 1)
    }

    func testALateChainEndThroughARestCarrierIsNotAnOnTimeCompletionF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(115 * 60 + 3 * 3600) // device away for hours past the chain end
        c.handlePomodoroCarrier(.intervalDidEnd, activity: names[2])
        XCTAssertNil(store.active)
        XCTAssertFalse(events.log.contains {
            if case let .ended(_, route, _) = $0.unannotated { return route == .completed }
            return false
        }, "a late observation is unconfirmed, not completed")
        for name in [session.monitorName] + names { XCTAssertTrue(registrar.stopped.contains(name)) }
    }

    func testAnOnTimeChainEndThroughARestCarrierCompletesF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        clock.advance(115 * 60)
        c.handlePomodoroCarrier(.intervalDidEnd, activity: carriers(session)[2])
        XCTAssertNil(store.active)
        XCTAssertEqual(events.log.filter {
            if case let .ended(_, route, _) = $0.unannotated { return route == .completed }
            return false
        }.count, 1)
    }

    func testRestsCrossedWhileUnobservedAreReportedBeforeTheTerminalEventF01() throws {
        let c = controller()
        _ = try start(c, plan: plan)
        clock.advance(115 * 60) // nothing observed any boundary
        c.finishIfExpired()
        XCTAssertEqual(plannedRestEvents(.started).count, 3)
        XCTAssertEqual(plannedRestEvents(.ended).count, 3)
        let terminal = try XCTUnwrap(events.log.firstIndex {
            if case .ended = $0.unannotated { return true }
            return false
        })
        let lastRest = try XCTUnwrap(events.log.lastIndex {
            if case .plannedRest = $0.unannotated { return true }
            return false
        })
        XCTAssertLessThan(lastRest, terminal, "rest boundaries precede the chain's terminal event")
        XCTAssertTrue(plannedRestEvents(.started).allSatisfy {
            if case let .plannedRest(_, _, _, _, _, inferred) = $0.unannotated { return inferred }
            return false
        }, "late reports are inferred")
    }

    func testEmergencyPassEndsTheParentAndStopsItsCarriersF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        let at = FlintLedgerInstant(clock: clock.flintClock)
        try ledger().transaction { state in
            state.importLegacyPass(.absent, at: at)
            _ = try state.consumePass(operationID: "pomodoro-pass", hasActiveCommitment: true, at: at).get()
            state.emergencyRecovery = .init(operationID: "pomodoro-pass", at: at,
                scope: .init(revision: "accepted", sessions: [session], rules: [], stores: ["flint"]),
                carrierName: "flint.recovery.pomodoro")
            state.passLimitThresholds = [:]
            state.passSuppressedFocusOwners = []
        }.get()
        XCTAssertTrue(c.endCommittedEmergencyPass(session, at: at.wall))
        XCTAssertNil(store.active)
        for name in [session.monitorName] + names { XCTAssertTrue(registrar.stopped.contains(name)) }
        XCTAssertTrue(events.log.contains {
            if case let .ended(_, route, _) = $0.unannotated { return route == .emergencyPass }
            return false
        })
    }

    func testReconcileStopsOrphanPomodoroCarriersF01() throws {
        let c = controller()
        _ = try start(c, plan: plan)
        let orphan = "flint.pomo.0123456789abcdef.2"
        registrar.registered.insert(orphan)
        _ = c.reconcile()
        XCTAssertTrue(registrar.stopped.contains(orphan))
        XCTAssertNotNil(store.active, "the live parent is untouched")
    }

    func testATimeZoneChangeReRegistersTheRemainingRestCarriersF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(25 * 60 + 60) // inside rest 1
        clock.timeZone = TimeZone(identifier: "UTC")!
        let startedBefore = registrar.started.count
        _ = c.reconcile()
        let current = try XCTUnwrap(store.active)
        XCTAssertNotEqual(current.monitorName, session.monitorName, "the parent end is re-registered")
        XCTAssertEqual(carriers(current), names, "rest carriers keep the chain's stable token")
        let rearmed = registrar.started.dropFirst(startedBefore)
        XCTAssertEqual(Set(rearmed.map(\.name)).intersection(names), Set(names),
                       "the rest under way and both future rests are re-registered")
        // The rest under way restarts now, in the new zone, and warns at its logical end.
        let inProgress = try XCTUnwrap(rearmed.last { $0.name == names[0] }?.plan)
        let utc = clock.flintClock.calendar()
        XCTAssertEqual(inProgress.intervalStart.hour, utc.component(.hour, from: clock.now))
        XCTAssertEqual(inProgress.intervalStart.minute, utc.component(.minute, from: clock.now))
        XCTAssertEqual(inProgress.warningTime?.minute, 11, "4 minutes of rest left in a 15-minute carrier")
        XCTAssertFalse(names.contains(where: registrar.stopped.contains), "the chain's carriers are not stale")
        for name in names { XCTAssertTrue(registrar.registered.contains(name)) }
    }

    func testAReArmedParentKeepsItsRestCarriersReconcilingTheChainF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(25 * 60 + 60) // inside rest 1
        clock.timeZone = TimeZone(identifier: "UTC")!
        _ = c.reconcile() // re-arms the parent end under a new monitor name
        let current = try XCTUnwrap(store.active)
        XCTAssertNotEqual(current.monitorName, session.monitorName)
        let applies = shield.applyCount
        clock.advance(4 * 60) // rest 1 ends, focus 2 begins
        c.handlePomodoroCarrier(.intervalWillEndWarning, activity: names[0])
        XCTAssertEqual(shield.applyCount, applies + 1, "focus resumes from the re-armed parent's carrier")
        XCTAssertFalse(registrar.stopped.contains(names[0]), "the carrier is still this chain's")
        XCTAssertEqual(plannedRestEvents(.ended, index: 1).count, 1)
    }

    func testAWallClockDriftDuringARestReArmsItsEndAtTheLogicalEndF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(25 * 60 + 60) // inside rest 1, 4 minutes left
        clock.now = clock.now.addingTimeInterval(-3600) // wall moves back an hour, same boot
        let startedBefore = registrar.started.count
        _ = c.reconcile()
        let rearmed = registrar.started.dropFirst(startedBefore)
        let inProgress = try XCTUnwrap(rearmed.last { $0.name == names[0] }?.plan,
                                       "the rest under way is re-armed after the drift")
        let calendar = clock.flintClock.calendar()
        XCTAssertEqual(inProgress.intervalStart.hour, calendar.component(.hour, from: clock.now))
        XCTAssertEqual(inProgress.intervalStart.minute, calendar.component(.minute, from: clock.now))
        XCTAssertEqual(inProgress.warningTime?.minute, 11, "the warning lands on the logical rest end")
        XCTAssertTrue(rearmed.contains { $0.name == names[1] }, "future rests move with the drift")
        XCTAssertTrue(rearmed.contains { $0.name == names[2] })
        XCTAssertEqual(store.active?.pomodoroPhase(clock: clock.flintClock)?.phase?.kind, .rest)
    }

    func testFocusAfterARestRepairsAMissingParentEndBeforeRestoringSafariF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(25 * 60 + 60) // rest 1
        c.handlePomodoroCarrier(.intervalDidStart, activity: names[0])
        XCTAssertEqual(shield.clearCount, 1, "the rest clears the store, Safari keys included")
        registrar.registered.remove(session.monitorName) // iOS dropped the parent's end
        let webBefore = web.reconciled.count
        clock.advance(4 * 60) // focus 2
        c.handlePomodoroCarrier(.intervalWillEndWarning, activity: names[0])
        let current = try XCTUnwrap(store.active)
        XCTAssertNotEqual(current.monitorName, session.monitorName, "the parent end is re-armed first")
        XCTAssertTrue(registrar.registered.contains(current.monitorName))
        XCTAssertEqual(web.reconciled.dropFirst(webBefore).last, current.monitorName,
                       "Safari's restrictions come back with focus")
        XCTAssertEqual(plannedRestEvents(.ended, index: 1).count, 1)
    }

    func testARestIsNotStartedWithoutTheFrozenTargetsToRestoreFocusF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        store.sessionSelections.removeAll()
        store.selection = nil
        clock.advance(25 * 60 + 60) // rest 1
        c.handlePomodoroCarrier(.intervalDidStart, activity: carriers(session)[0])
        XCTAssertEqual(shield.clearCount, 0, "no rest without a way back to focus (fail-closed)")
    }

    func testRecoveredChainsGetTheirMissingRestCarriersBackF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        registrar.registered.remove(names[1])
        c.prepareAcceptedInstallationForScope()
        XCTAssertTrue(registrar.registered.contains(names[1]))
    }

    func testAStaleCarrierStillReconcilesTheCurrentChainPhaseF01() throws {
        let c = controller()
        _ = try start(c, plan: plan)
        clock.advance(25 * 60 + 60) // inside rest 1
        let stale = "flint.pomo.deadbeefdeadbeef.1"
        registrar.registered.insert(stale)
        c.handlePomodoroCarrier(.intervalDidStart, activity: stale)
        XCTAssertTrue(registrar.stopped.contains(stale))
        XCTAssertEqual(shield.clearCount, 1, "the wake-up reconciles the live chain's phase")
    }

    // MARK: History

    func testPlannedRestHistoryIsEmittedOnceDespiteDuplicateCallbacksF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        let names = carriers(session)
        clock.advance(25 * 60) // exactly at rest 1's start
        c.handlePomodoroCarrier(.intervalDidStart, activity: names[0])
        c.handlePomodoroCarrier(.intervalDidStart, activity: names[0])
        c.handlePomodoroCarrier(.intervalWillEndWarning, activity: names[0])
        XCTAssertEqual(plannedRestEvents(.started).count, 1)
        XCTAssertTrue(plannedRestEvents(.ended).isEmpty)
        guard case let .plannedRest(_, index, _, logicalAt, breakEnd, inferred) =
                plannedRestEvents(.started)[0].unannotated else { return XCTFail("missing rest start") }
        XCTAssertEqual(index, 1)
        XCTAssertEqual(logicalAt, session.startedAt.addingTimeInterval(25 * 60))
        XCTAssertEqual(breakEnd, session.startedAt.addingTimeInterval(30 * 60))
        XCTAssertFalse(inferred, "observed at the boundary, not inferred")
        let event = FlintHistoryEvent(plannedRestEvents(.started)[0])
        XCTAssertEqual(event.type, .breakStarted)
        XCTAssertEqual(event.kind, "planned")
        XCTAssertEqual(event.grant, "\(session.id).rest1")
        XCTAssertEqual(event.breakEnd, FlintHistoryEvent.milliseconds(session.startedAt.addingTimeInterval(30 * 60)))
        guard case let .annotated(_, _, _, observedAt, _) = plannedRestEvents(.started)[0] else {
            return XCTFail("missing observation")
        }
        XCTAssertEqual(observedAt, clock.now)

        clock.advance(5 * 60) // exactly at rest 1's end
        c.handlePomodoroCarrier(.intervalDidEnd, activity: names[0])
        c.handlePomodoroCarrier(.intervalDidEnd, activity: names[0])
        XCTAssertEqual(plannedRestEvents(.started).count, 1)
        XCTAssertEqual(plannedRestEvents(.ended).count, 1)
    }

    func testALatePhaseJumpEmitsTheCrossedRestBoundariesOnceF01() throws {
        let c = controller()
        let session = try start(c, plan: plan)
        clock.advance(35 * 60) // focus 2, rest 1 already over
        _ = c.reconcile()
        XCTAssertEqual(plannedRestEvents(.started, index: 1).count, 1)
        XCTAssertEqual(plannedRestEvents(.ended, index: 1).count, 1)
        XCTAssertTrue(plannedRestEvents(.started, index: 2).isEmpty)
        XCTAssertEqual(store.active?.pomodoroObservedPhase, 2)
        XCTAssertNotNil(session.pomodoro)
    }

    func testFocusTotalsExcludePlannedRestsAndRoundCountIsProvenF01() {
        let t = Date(timeIntervalSince1970: 1_800_000_000)
        let calendar: Calendar = {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(secondsFromGMT: 0)!
            return calendar
        }()
        let session = FlintActiveSession(name: "Pomodoro", startedAt: t,
            endsAt: t.addingTimeInterval(3600), breakLevel: .easy,
            monitorName: "flint.session.abcd", plannedDuration: 3600,
            pomodoro: FlintPomodoroPlan(focusMinutes: 25, restMinutes: 5, rounds: 4))
        let began = FlintHistoryEvent(.plannedRest(session, index: 1, kind: .started,
            logicalAt: t.addingTimeInterval(1200), breakEnd: t.addingTimeInterval(1500), inferred: false))
        let ended = FlintHistoryEvent(.plannedRest(session, index: 1, kind: .ended,
            logicalAt: t.addingTimeInterval(1500), breakEnd: nil, inferred: false))
        let events = [
            FlintHistoryEvent(.started(session, source: .pomodoro, at: t)),
            began, ended,
            FlintHistoryEvent(.ended(session, route: .completed, at: t.addingTimeInterval(3600))),
        ]
        let summary = FlintHistoryAggregator.summarize(
            .init(events: events, historyStart: t.addingTimeInterval(-86400), availability: .value),
            activeSessionID: nil, now: t.addingTimeInterval(7200), calendar: calendar, dayCount: 1)
        XCTAssertEqual(summary.focusedSeconds, 3300, "the 5-minute planned rest is not focus time")
        XCTAssertEqual(summary.sessions.first?.plannedRounds, 4)
        XCTAssertEqual(summary.sessions.first?.observedRounds, 4, "completion proves every round")
        XCTAssertEqual(summary.sessions.first?.roundsText, "4 of 4 rounds")

        // An early end credits only the rounds an observed rest proves.
        let early = [
            FlintHistoryEvent(.started(session, source: .pomodoro, at: t)),
            began, ended,
            FlintHistoryEvent(.ended(session, route: .manualStop, at: t.addingTimeInterval(2000))),
        ]
        let earlySummary = FlintHistoryAggregator.summarize(
            .init(events: early, historyStart: t.addingTimeInterval(-86400), availability: .value),
            activeSessionID: nil, now: t.addingTimeInterval(7200), calendar: calendar, dayCount: 1)
        XCTAssertEqual(earlySummary.sessions.first?.observedRounds, 1)
        XCTAssertEqual(earlySummary.sessions.first?.roundsText, "1 of 4 rounds")
    }
}

/// F01: glance/widget phase truthfulness.
final class FlintPomodoroGlanceTests: XCTestCase {
    private let t = Date(timeIntervalSince1970: 1_800_000_000)

    private func input(phase: FlintGlanceInput.Session.Phase?, phaseEnds: Date?,
                       protection: FlintGlanceInput.Protection = .enforcing) -> FlintGlanceInput {
        .init(protection: protection, checkedAt: t, validUntil: t.addingTimeInterval(3600),
              session: .init(id: "pomodoro", cause: .pomodoro, tier: "Harder", isArmed: true,
                             startedAt: t.addingTimeInterval(-600), endsAt: t.addingTimeInterval(3000),
                             name: "Pomodoro", phase: phase, round: 2, rounds: 4, phaseEndsAt: phaseEnds),
              protectionEndsAt: t.addingTimeInterval(3000), next: nil,
              history: .init(availability: .value, seconds: 600, inProgress: true, readAt: t))
    }

    func testFocusPhaseIsShownInsideItsBoundaryAndDroppedAfterItF01() {
        let model = FlintGlancePresentation(input(phase: .focus, phaseEnds: t.addingTimeInterval(600)), at: t)
        XCTAssertEqual(model.phaseSummary, "Focus · round 2 of 4")
        XCTAssertTrue(FlintGlancePresentation.boundaries(input(phase: .focus, phaseEnds: t.addingTimeInterval(600)),
                                                         after: t).contains(t.addingTimeInterval(600)))
        // Past the published boundary with no newer publication: no stale focus claim.
        XCTAssertNil(FlintGlancePresentation(input(phase: .focus, phaseEnds: t.addingTimeInterval(600)),
                                             at: t.addingTimeInterval(601)).phaseSummary)
    }

    func testRestPhaseNamesTheResumeTimeInsteadOfClaimingProtectionF01() {
        let rest = input(phase: .rest, phaseEnds: t.addingTimeInterval(300), protection: .idle)
        let model = FlintGlancePresentation(rest, at: t)
        XCTAssertEqual(model.status, .idle)
        XCTAssertEqual(model.title, "Pomodoro · planned rest")
        XCTAssertEqual(model.phaseSummary?.hasPrefix("Rest · focus resumes "), true)
        XCTAssertNil(FlintGlancePresentation(input(phase: .rest, phaseEnds: t.addingTimeInterval(300),
                                                   protection: .idle),
                                             at: t.addingTimeInterval(301)).phaseSummary)
    }
}

// MARK: - Fakes

private final class PomoWebRestrictions: FlintSessionWebRestricting {
    var reconciled: [String] = []
    func reconcile(_ session: FlintActiveSession, clock: FlintClock) { reconciled.append(session.monitorName) }
}

private final class PomoShield: FlintSessionShielding {
    var nextCounts = FlintSelectionCounts(applications: 3)
    var applyCount = 0
    var clearCount = 0

    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { nextCounts }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets {
        FlintSessionTargets(applications: ["A"])
    }
    func fingerprint(of selection: FamilyActivitySelection) -> String { "A" }
    func applyBlockList(_ selection: FamilyActivitySelection) throws { applyCount += 1 }
    func clear() { clearCount += 1 }
    func retainsAppliedSettings() -> Bool { false }
}

private final class PomoRegistrar: FlintSessionActivityRegistering {
    struct Start { let name: String; let plan: FlintSessionAutoClearPlan }
    struct Refused: Error {}
    var started: [Start] = []
    var stopped: [String] = []
    var registered: Set<String> = []
    /// 1-based index of a `startMonitoring` call that iOS refuses.
    var failOnStartIndex: Int?
    /// 1-based index of a `startMonitoring` call iOS accepts without error but does not hold.
    var dropOnStartIndex: Int?
    private var calls = 0

    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        calls += 1
        if calls == failOnStartIndex { throw Refused() }
        started.append(Start(name: name, plan: plan))
        if calls != dropOnStartIndex { registered.insert(name) }
    }

    func stopMonitoring(_ names: [String]) {
        stopped += names
        registered.subtract(names)
    }

    func registeredActivityNames() -> Set<String> { registered }
}

private final class PomoStore: FlintSessionStateStoring {
    var active: FlintActiveSession?
    var selection: FamilyActivitySelection?
    var sessionSelections: [String: FamilyActivitySelection] = [:]

    func loadActiveSession() -> FlintActiveSession? { active }
    func hasActiveSessionRecord() -> Bool { active != nil }
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

private final class PomoClock {
    /// 2026-10-28 10:00 in Los Angeles.
    var now = Date(timeIntervalSince1970: 1_793_206_800)
    var monotonic: TimeInterval = 50_000
    var boot: String? = "boot-1"
    var timeZone = TimeZone(identifier: "America/Los_Angeles")!

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

private final class PomoLifecycleCapture: FlintSessionEventSink {
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
            case .plannedRest: return "plannedRest"
            case .annotated: return "annotated"
            }
        }
    }
}
