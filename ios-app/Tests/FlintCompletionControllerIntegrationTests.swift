import XCTest
import FamilyControls
import UserNotifications
@testable import FlintCore

/// Exercises lane B's production controller-to-lifecycle wiring with fake OS services.
final class FlintCompletionControllerIntegrationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var suite: String!
    private var defaults: UserDefaults!
    private var directory: URL!
    private var center: ControllerNotificationCenter!
    private var notifier: FlintCompletionNotifier!
    private var state: ControllerNotificationState!
    private var registrar: ControllerNotificationRegistrar!
    private var controller: FlintSessionController!

    override func setUp() {
        suite = "FlintCompletionControllerIntegrationTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        center = ControllerNotificationCenter()
        notifier = FlintCompletionNotifier(center: center, defaults: defaults, isHost: true,
            clock: { self.now }, history: FlintHistoryStore(directory: directory, clock: { self.now }))
        FlintSessionLifecycle.register(notifier)
        state = ControllerNotificationState()
        registrar = ControllerNotificationRegistrar()
        let state = self.state!, defaults = self.defaults!
        controller = FlintSessionController(environment: FlintSessionEnvironment(
            store: { state }, shield: ControllerNotificationShield(), registrar: registrar,
            isAuthorized: { true }, clock: FlintClock(now: { self.now }, monotonic: { 10_000 },
                bootSessionID: { "notification-integration" }, calendar: { Calendar(identifier: .gregorian) }),
            healthDefaults: { defaults }))
    }

    override func tearDownWithError() throws {
        FlintSessionLifecycle.unregister(notifier)
        drain()
        defaults.removePersistentDomain(forName: suite)
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }

    private func drain() {
        let done = expectation(description: "controller notification effects settled")
        notifier.drainForTesting { done.fulfill() }
        wait(for: [done], timeout: 2)
    }

    private func start(source: FlintSessionSource = .shortcut, level: BreakLevel = .easy) throws -> FlintActiveSession {
        try controller.startBlockNow(selection: FamilyActivitySelection(), duration: 1800,
            breakLevel: level, name: "Private session name", source: source,
            origin: source == .shortcut ? .shortcut : .appUI, confirmed: true)
    }

    func testHostShortcutControllerStartAndStopScheduleThenCancel() throws {
        let session = try start()
        drain()
        XCTAssertEqual(center.pending, [FlintCompletionNotifier.identifierPrefix + session.id])
        XCTAssertEqual(center.requests.first?.content.title, "Planned focus time ended")
        XCTAssertEqual(center.requests.first?.content.body, "Your focus session has reached its planned end.")
        controller.stop(route: .shortcutStop)
        drain()
        XCTAssertTrue(center.pending.isEmpty)
        XCTAssertNil(state.active)
    }

    func testControllerReplacementAndEmergencyPassCancelTheCorrectSession() throws {
        let first = try start(source: .manual)
        drain()
        let second = try start(level: .hardcore)
        drain()
        XCTAssertEqual(center.pending, [FlintCompletionNotifier.identifierPrefix + second.id])
        XCTAssertTrue(center.cancelled.contains(FlintCompletionNotifier.identifierPrefix + first.id))
        XCTAssertTrue(controller.useEmergencyPass())
        drain()
        XCTAssertTrue(center.pending.isEmpty)
        XCTAssertTrue(center.cancelled.contains(FlintCompletionNotifier.identifierPrefix + second.id))
    }

    func testControllerArmingFailureDoesNotScheduleNotification() {
        registrar.refuses = true
        XCTAssertThrowsError(try start())
        drain()
        XCTAssertTrue(center.requests.isEmpty)
        XCTAssertNil(state.active)
    }
}

private final class ControllerNotificationCenter: FlintNotificationCenter {
    var requests: [UNNotificationRequest] = []
    var pending: Set<String> = []
    var cancelled: [String] = []
    func authorizationStatus(_ completion: @escaping (UNAuthorizationStatus) -> Void) { completion(.authorized) }
    func requestAuthorization(_ completion: @escaping (Bool) -> Void) { XCTFail("controller must not prompt"); completion(false) }
    func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void) {
        requests.append(request); pending.insert(request.identifier); completion(nil)
    }
    func cancel(_ identifiers: [String]) { cancelled += identifiers; pending.subtract(identifiers) }
    func pendingIdentifiers(_ completion: @escaping ([String]) -> Void) { completion(Array(pending)) }
}

private final class ControllerNotificationShield: FlintSessionShielding {
    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { .init(applications: 1) }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets { .init() }
    func fingerprint(of selection: FamilyActivitySelection) -> String { "" }
    func applyBlockList(_ selection: FamilyActivitySelection) throws {}
    func setDenyAppRemoval(_ deny: Bool) {}
    func clear() {}
}

private final class ControllerNotificationRegistrar: FlintSessionActivityRegistering {
    var refuses = false
    enum Refusal: Error { case denied }
    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws { if refuses { throw Refusal.denied } }
    func stopMonitoring(_ names: [String]) {}
    func registeredActivityNames() -> Set<String> { [] }
}

private final class ControllerNotificationState: FlintSessionStateStoring {
    var active: FlintActiveSession?
    var passUsed: Date?
    func loadActiveSession() -> FlintActiveSession? { active }
    func hasActiveSessionRecord() -> Bool { active != nil }
    func saveActiveSession(_ session: FlintActiveSession) { active = session }
    func clearActiveSession() { active = nil }
    func saveSelection(_ selection: FamilyActivitySelection) {}
    func loadSelection() -> FamilyActivitySelection? { nil }
    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) {}
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? { nil }
    func clearSessionSelection() {}
    func emergencyPassLastUsed() -> Date? { passUsed }
    func recordEmergencyPassUse(_ date: Date) { passUsed = date }
}
