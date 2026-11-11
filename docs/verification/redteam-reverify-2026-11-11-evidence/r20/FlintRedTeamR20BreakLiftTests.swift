import XCTest
import FamilyControls
@testable import Flint
@testable import FlintCore

/// Red-team re-verification of residual R-20 (P0, iOS) on origin/main b888084.
///
/// The merged closure (`FlintSessionPersistence.authority(in:)`,
/// `FlintShieldStore.hasAppliedSettings`) treats a missing `flint.activeSession` as idle on the
/// first session-authority migration unless the *session-owned ManagedSettingsStore still holds a
/// shield key*. That is the only native contradiction it looks at. This file attacks that single
/// signal:
///
///  * A granted ordinary break rewrites the very same `"flint"` store with the released targets
///    removed (`FlintOrdinaryBreaks.write`, FlintOrdinaryBreaks.swift:390-395). A break for the
///    session's own full scope therefore leaves `applications`/`applicationCategories`/
///    `webDomains`/`webDomainCategories` all nil, while the session is still live: its record is
///    intact (until lost), its DeviceActivity registration still runs and its frozen target
///    snapshot is still saved. During that window `hasAppliedSettings`
///    (FlintShieldStore.swift:88-92) is false, so a lost projection is upgraded into the initial
///    idle tombstone and `reconcile()` clears the shield: the live commitment is released early
///    and the break-end re-shield (ADR-012 §4, PC-22) can never happen.
///  * The same lifted-store window after the first migration leaves a live generation
///    unavailable forever, so the reconciler never restores enforcement for the remaining
///    interval (ADR-012 §8 step 5).
///
/// Fakes are private copies of `FlintSessionControllerTests`' seams; the shield fake models the
/// real store's five keys instead of a hand-set boolean, so "the store was emptied by a break"
/// is a state the code under test produced, not a flag a test flipped arbitrarily.
final class FlintRedTeamR20BreakLiftTests: XCTestCase {

    private var shield: RedTeamShield!
    private var registrar: RedTeamRegistrar!
    private var store: RedTeamSessionStore!
    private var clock: RedTeamClock!
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var events: RedTeamLifecycleCapture!
    private var ledgerDirectory: URL!

    override func setUp() {
        super.setUp()
        shield = RedTeamShield()
        registrar = RedTeamRegistrar()
        store = RedTeamSessionStore()
        clock = RedTeamClock()
        suiteName = "flint.tests.redteam.r20.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        events = RedTeamLifecycleCapture()
        FlintSessionLifecycle.register(events)
        ledgerDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("flint-redteam-r20-ledger-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: ledgerDirectory)
        FlintSessionLifecycle.unregister(events)
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    // MARK: - Harness

    private func controller() -> FlintSessionController {
        let store = self.store!
        let defaults = self.defaults
        let directory = self.ledgerDirectory!
        let flintClock = clock.flintClock
        return FlintSessionController(environment: FlintSessionEnvironment(
            store: { store },
            shield: shield,
            webRestrictions: RedTeamNoWebRestrictions(),
            registrar: registrar,
            isAuthorized: { true },
            clock: clock.flintClock,
            healthDefaults: { defaults },
            ledger: { FlintProtectionLedger(directory: directory, clock: flintClock,
                                            initializedMarker: { nil }, setInitializedMarker: { _ in }) },
            publishStatus: {},
            transitionLock: { FlintSessionTransitionLock(directory: directory, timeout: 0.2) },
            monitorLockTimeout: 1,
            wait: { [unowned self] in self.clock.advance($0) },
            reconcileEmergencyPass: { true },
            prepareNewSession: { _ in .success(()) },
            recoveryGate: { .enforcing },
            captureRemovalGuard: { false },
            reconcileRemovalGuard: {}
        ))
    }

    private func ledger() -> FlintProtectionLedger {
        .init(directory: ledgerDirectory, clock: clock.flintClock,
              initializedMarker: { nil }, setInitializedMarker: { _ in })
    }

    private var health: FlintSessionHealth { FlintSessionHealth.load(from: defaults) }

    /// Two distinguishable selections: A blocks {A, B}, B blocks {A}.
    private let selectionA = FamilyActivitySelection()
    private let selectionB = FamilyActivitySelection(includeEntireCategory: true)

    @discardableResult
    private func start(_ c: FlintSessionController, minutes: Double,
                       level: BreakLevel) throws -> FlintActiveSession {
        try c.startBlockNow(selection: selectionA, duration: minutes * 60, breakLevel: level,
                            source: .manual, origin: .appUI, replacing: true, confirmed: true)
    }

    /// The state a shipped 0.1.x install has when the new build first runs: either a valid v2
    /// ledger with no session authority, or no ledger at all (created by this launch). The live
    /// session itself is still represented by the App Group record + saved session snapshot +
    /// DeviceActivity registration.
    private func downgradeLedgerToLegacy(_ legacyLedger: Bool) throws {
        if legacyLedger {
            try ledger().transaction {
                $0.version = 2
                $0.sessionAuthority = nil
                $0.sessionAuthorityVersion = nil
            }.get()
        } else {
            try? FileManager.default.removeItem(at: ledgerDirectory)
        }
    }

    // MARK: - A. First migration, live session whose shield a granted break lifted

    /// Live finite Harder session (finite deadline, stop friction) + its own granted ordinary
    /// break (the "flint" store legitimately holds nothing while the break runs) + a lost
    /// `flint.activeSession` on the first upgrade. Expected: the session is not idle — its
    /// registration and saved target snapshot still contradict absence — so foreground
    /// reconcile, the monitor's end callback and the stop intent must all report unavailable and
    /// must not clear the store. Actual on b888084: an initial idle tombstone is adopted,
    /// `reconcile()` reports `.noSession`, clears the shield and forgets the saved selection.
    func testRedTeamR20V2UpgradeBreakLiftedShieldIsNotIdle() throws {
        try exerciseBreakLiftedAbsence(legacyLedger: true)
    }

    /// Same attack on a pre-ledger install (ledger created by this launch), the second shape the
    /// merged fix covers only while the shield is still applied.
    func testRedTeamR20PreLedgerInstallBreakLiftedShieldIsNotIdle() throws {
        try exerciseBreakLiftedAbsence(legacyLedger: false)
    }

    private func exerciseBreakLiftedAbsence(legacyLedger: Bool) throws {
        let c = controller()
        let session = try start(c, minutes: 60, level: .harder)
        XCTAssertGreaterThanOrEqual(shield.applyCount, 1, "the live session's shield was applied")
        XCTAssertTrue(shield.retainsAppliedSettings())
        XCTAssertTrue(registrar.registered.contains(session.monitorName))
        XCTAssertNotNil(store.loadSessionSelection(sessionID: session.id), "frozen target snapshot saved")

        // The user accepts the session's ordinary break: FlintOrdinaryBreaks.write rewrites the
        // same "flint" store without the released targets, i.e. empty for a full-scope break.
        shield.ordinaryBreakReleasesWholeScope()
        XCTAssertFalse(shield.retainsAppliedSettings(),
                       "while the break runs the store holds no shield key: the fix's only native signal is empty")
        // The session is still live: record, registration and frozen snapshot all present.
        XCTAssertNotNil(store.active)
        XCTAssertTrue(registrar.registered.contains(session.monitorName))

        if legacyLedger { try downgradeLedgerToLegacy(true) } else { try downgradeLedgerToLegacy(false) }
        store.active = nil // R-20 premise: only `flint.activeSession` is lost

        let clears = shield.clearCount
        let stops = registrar.stopped
        let report = controller().reconcile()
        XCTAssertEqual(report.outcome, .running, "legacyLedger=\(legacyLedger)")
        XCTAssertTrue(report.recordUnreadable, "legacyLedger=\(legacyLedger)")
        XCTAssertEqual(shield.clearCount, clears, "R-20: a break-lifted store is not idle proof")
        XCTAssertTrue(registrar.stopped == stops, "the live registration must not be retired")
        XCTAssertTrue(registrar.registered.contains(session.monitorName))
        XCTAssertNotNil(store.loadSessionSelection(sessionID: session.id),
                        "the frozen session snapshot is not idle proof either")
        XCTAssertNotEqual(try ledger().peek().get().sessionAuthority?.provesIdle, true,
                          "no idle tombstone may be adopted from contradicted absence")
        XCTAssertNotNil(health.lastArmFailure)

        // Each remaining entry point must stay unavailable for this live session.
        controller().handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertEqual(shield.clearCount, clears, "a monitor end cannot release the retained session")
        XCTAssertTrue(registrar.stopped == stops, "nor stop its registration")
        XCTAssertEqual(controller().requestStop(route: .manualStop, confirmed: true), .unavailable)
        XCTAssertEqual(shield.clearCount, clears)
    }

    // MARK: - B. Control: the applied-shield case the merged fix covers

    /// Control (expected PASS on b888084): with the shield still applied, the same lost projection
    /// is refused. Keeps the harness honest — the failure in A is the break-lifted store, not the
    /// scaffolding.
    func testRedTeamR20ControlRetainedShieldFirstUpgradeStaysUnavailable() throws {
        let c = controller()
        let session = try start(c, minutes: 60, level: .hardcore)
        XCTAssertTrue(shield.retainsAppliedSettings())
        try downgradeLedgerToLegacy(true)
        store.active = nil
        let clears = shield.clearCount

        let report = controller().reconcile()
        XCTAssertEqual(report.outcome, .running)
        XCTAssertTrue(report.recordUnreadable)
        XCTAssertEqual(shield.clearCount, clears)
        XCTAssertTrue(registrar.registered.contains(session.monitorName))
        XCTAssertNotEqual(try ledger().peek().get().sessionAuthority?.provesIdle, true)
    }

    // MARK: - C. Post-migration: the break-end re-shield never happens

    /// After the first migration the generation still owns the session (session, deadline and the
    /// saved frozen selection are all in the ledger), but the projection is gone and the break
    /// lifted the store. ADR-012 §8 step 5 requires the reconciler to reconcile shields; §4/PC-22
    /// requires the re-shield when the break ends. Expected: the remaining interval is re-armed
    /// with the session's own saved targets. Actual on b888084: `resolve` returns unavailable and
    /// nothing re-applies, so the commitment stays released for the rest of its interval while the
    /// ledger still reports it running.
    func testRedTeamR20PostMigrationBreakEndRestoresLiveSessionShield() throws {
        let c = controller()
        _ = try start(c, minutes: 60, level: .harder)
        XCTAssertTrue(shield.retainsAppliedSettings())
        let applies = shield.applyCount
        shield.ordinaryBreakReleasesWholeScope() // the granted break lifts the store
        store.active = nil                        // the projection is lost while the break runs

        let first = controller().reconcile()
        XCTAssertEqual(first.outcome, .running)
        XCTAssertTrue(first.recordUnreadable)
        XCTAssertEqual(shield.clearCount, 0, "a live generation is never cleared")

        // The break's 300 s window ends; the session still has ~55 minutes of its own deadline.
        clock.advance(300)
        _ = controller().reconcile()
        XCTAssertGreaterThan(shield.applyCount, applies,
                             "the reconciler must restore the live session's shield after its break")
        XCTAssertTrue(shield.retainsAppliedSettings(),
                      "the store must hold the session's targets again, not stay empty")
    }

    // MARK: - D. No dead end: a normally cleared legacy store still reaches idle

    /// Control (expected PASS): variant (h) of the brief. A 0.1.x install whose session really
    /// ended clears the session store, so the same absence is positive idle and must stay
    /// reachable — the fix must not strand a healthy installation in "unavailable" forever.
    func testRedTeamR20ControlClearedLegacyStoreStillReachesIdle() throws {
        let c = controller()
        _ = try start(c, minutes: 30, level: .easy)
        try downgradeLedgerToLegacy(true)
        store.active = nil
        shield.clear() // the ordinary legacy end cleared the session-owned store

        let report = controller().reconcile()
        XCTAssertEqual(report.outcome, .noSession)
        XCTAssertFalse(report.recordUnreadable)
        XCTAssertEqual(try XCTUnwrap(ledger().peek().get().sessionAuthority).provesIdle, true)
        XCTAssertEqual(try ledger().peek().get().version, 3)
        XCTAssertFalse(shield.retainsAppliedSettings(), "nothing blocks after a real legacy end")
    }
}

// MARK: - Fakes

private struct RedTeamNoWebRestrictions: FlintSessionWebRestricting {
    func reconcile(_ session: FlintActiveSession, clock: FlintClock) {}
}

/// Models the session-owned `ManagedSettingsStore(named: "flint")` contents as the set of tokens
/// its five inspected keys hold:
///  - `applyBlockList` writes the (non-empty) application/category/web-domain tokens
///    (FlintShieldStore.swift:66-68);
///  - `clear` nils every key (:98-103);
///  - a granted ordinary break rewrites the same store without the released targets
///    (FlintOrdinaryBreaks.swift:390-395), i.e. empty when it covers the whole scope;
///  - `hasAppliedSettings` is "any of those keys non-nil" (:88-92).
private final class RedTeamShield: FlintSessionShielding {
    var nextCounts = FlintSelectionCounts(applications: 3)
    var applyCount = 0
    var clearCount = 0
    private(set) var appliedTargets: Set<String> = []

    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { nextCounts }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets {
        FlintSessionTargets(applications: selection.includeEntireCategory ? ["A"] : ["A", "B"])
    }
    func fingerprint(of selection: FamilyActivitySelection) -> String {
        selection.includeEntireCategory ? "B" : "A"
    }
    private static func tokens(of selection: FamilyActivitySelection) -> Set<String> {
        selection.includeEntireCategory ? ["A"] : ["A", "B"]
    }

    func applyBlockList(_ selection: FamilyActivitySelection) throws {
        applyCount += 1
        appliedTargets = Self.tokens(of: selection)
    }

    /// The session's own ordinary break was accepted: FlintOrdinaryBreaks rewrites this store
    /// with the released targets removed, which is all of them for a full-scope break.
    func ordinaryBreakReleasesWholeScope() { appliedTargets = [] }

    func clear() {
        clearCount += 1
        appliedTargets = []
    }

    func retainsAppliedSettings() -> Bool { !appliedTargets.isEmpty }
}

private final class RedTeamRegistrar: FlintSessionActivityRegistering {
    struct Start { let name: String; let plan: FlintSessionAutoClearPlan }
    var started: [Start] = []
    var stopped: [String] = []
    var registered: Set<String> = []

    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        started.append(Start(name: name, plan: plan))
        registered.insert(name)
    }
    func stopMonitoring(_ names: [String]) {
        stopped += names
        registered.subtract(names)
    }
    func registeredActivityNames() -> Set<String> { registered }
}

private final class RedTeamSessionStore: FlintSessionStateStoring {
    var active: FlintActiveSession?
    var selection: FamilyActivitySelection?
    var sessionSelections: [String: FamilyActivitySelection] = [:]
    var passUsed: Date?
    var readable = true

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
    func emergencyPassLastUsed() -> Date? { passUsed }
    func recordEmergencyPassUse(_ date: Date) { passUsed = date }
    func isProtectionStateReadable() -> Bool { readable }
}

private final class RedTeamClock {
    /// 2026-10-28 10:00 in Los Angeles (pinned; the tests never read the host clock/zone).
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

private final class RedTeamLifecycleCapture: FlintSessionEventSink {
    var log: [FlintSessionLifecycleEvent] = []
    func handle(_ event: FlintSessionLifecycleEvent) { log.append(event) }
}
