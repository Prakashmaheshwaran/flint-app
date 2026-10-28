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
    }

    override func tearDown() {
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
        return FlintSessionController(environment: FlintSessionEnvironment(
            store: { storeAvailable ? store : nil },
            shield: shield,
            registrar: registrar,
            isAuthorized: { [unowned self] in self.authorized },
            clock: clock.flintClock,
            shortSessionStrategy: strategy,
            healthDefaults: { defaults }
        ))
    }

    private var health: FlintSessionHealth { FlintSessionHealth.load(from: defaults) }

    @discardableResult
    private func start(
        _ controller: FlintSessionController,
        minutes: Double,
        level: BreakLevel = .easy,
        source: FlintSessionSource = .manual
    ) throws -> FlintActiveSession {
        try controller.startBlockNow(
            selection: FamilyActivitySelection(), duration: minutes * 60, breakLevel: level, source: source)
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

    func testTimedStartRegistersThenShieldsThenPersists() throws {
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
        let first = try start(c, minutes: 60, level: .hardcore)
        clock.advance(60)
        let second = try start(c, minutes: 30)
        XCTAssertNotEqual(first.monitorName, second.monitorName)
        XCTAssertEqual(registrar.calls, [
            "start:\(first.monitorName)", "start:\(second.monitorName)", "stop:\(first.monitorName)",
        ])
        XCTAssertEqual(shield.denyAppRemoval, false, "an Easy replacement drops the Hardcore guard")
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

    func testShortSessionFallbackEndStillCompletes() throws {
        let c = controller()
        let session = try start(c, minutes: 5)
        clock.advance(15 * 60) // the warning never arrived; the padded interval ended
        events.log.removeAll()
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertNil(store.active)
        XCTAssertEqual(events.log, [.ended(session, route: .completed, at: session.endsAt!)])
    }

    func testEndCallbacksBeforeEndsAtNeverEndTheSession() throws {
        let c = controller()
        let session = try start(c, minutes: 5)
        clock.advance(5 * 60 - 90) // 90 s early — inside the old tolerance, still early
        c.handleMonitorEvent(.intervalWillEndWarning, activity: session.monitorName)
        XCTAssertEqual(store.active, session, "early warning ignored")
        c.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertEqual(store.active?.id, session.id, "early interval end keeps the block")
        XCTAssertEqual(shield.clearCount, 0)
        clock.advance(89) // one second before endsAt: whole-second slack
        c.handleMonitorEvent(.intervalWillEndWarning, activity: store.active!.monitorName)
        XCTAssertNil(store.active)
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

    func testReArmingIsBounded() throws {
        let c = controller()
        _ = try start(c, minutes: 120)
        for attempt in 1...FlintSessionController.maximumRearms {
            registrar.registered.removeAll()
            XCTAssertTrue(c.reconcile().rearmed, "attempt \(attempt)")
            XCTAssertEqual(store.active?.rearmCount, attempt)
        }
        registrar.registered.removeAll()
        let report = c.reconcile()
        XCTAssertFalse(report.rearmed)
        XCTAssertTrue(report.rearmFailed)
        XCTAssertEqual(health.lastArmFailure?.reason, "re-registration limit reached")
        XCTAssertNotNil(store.active, "the block itself is kept")
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

    func testAForwardJumpThatExpiresTheSessionIsStillRecorded() throws {
        let c = controller()
        _ = try start(c, minutes: 60)
        clock.advance(10 * 60)
        clock.now = clock.now.addingTimeInterval(2 * 3600)
        let report = c.reconcile()
        XCTAssertEqual(report.outcome, .finishedExpired)
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

    func testReconcileWithoutSessionDropsAStaleUninstallGuard() {
        shield.denyAppRemoval = true
        XCTAssertEqual(controller().reconcile().outcome, .noSession)
        XCTAssertEqual(shield.denyAppRemoval, false)
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
    func applyBlockList(_ selection: FamilyActivitySelection) throws {
        applyCount += 1
        log.entries.append("shield")
    }
    func setDenyAppRemoval(_ deny: Bool) {
        denyAppRemoval = deny
        log.entries.append("deny:\(deny)")
    }
    func clear() {
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
    var passUsed: Date?

    init(log: CallLog) { self.log = log }

    func loadActiveSession() -> FlintActiveSession? { active }
    func saveActiveSession(_ session: FlintActiveSession) {
        active = session
        log.entries.append("persist")
    }
    func clearActiveSession() { active = nil }
    func saveSelection(_ selection: FamilyActivitySelection) { self.selection = selection }
    func loadSelection() -> FamilyActivitySelection? { selection }
    func emergencyPassLastUsed() -> Date? { passUsed }
    func recordEmergencyPassUse(_ date: Date) { passUsed = date }
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
            switch $0 {
            case .started: return "started"
            case .ended: return "ended"
            case .superseded: return "superseded"
            case .passUsed: return "passUsed"
            case .failedToArm: return "failedToArm"
            }
        }
    }
}
