import XCTest
import UserNotifications
@testable import FlintCore

final class FlintCompletionNotificationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var defaults: UserDefaults!
    private var suite: String!
    private var historyDirectory: URL!
    private var history: FlintHistoryStore!
    override func setUp() {
        suite = "FlintCompletionNotificationTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        historyDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        history = FlintHistoryStore(directory: historyDirectory, clock: { self.now })
    }
    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suite)
        if FileManager.default.fileExists(atPath: historyDirectory.path) { try FileManager.default.removeItem(at: historyDirectory) }
    }
    private func session(id: String = "a", seconds: Double? = 1800) -> FlintActiveSession {
        .init(id: id, name: "Read", startedAt: now, endsAt: seconds.map { now.addingTimeInterval($0) },
              breakLevel: .easy, monitorName: "test")
    }
    private func notifier(_ center: NotificationCenterStub, host: Bool = true) -> FlintCompletionNotifier {
        .init(center: center, defaults: defaults, isHost: host, clock: { self.now }, history: history)
    }
    private func drain(_ sut: FlintCompletionNotifier) {
        let drained = expectation(description: "notifier decisions drained")
        sut.drainForTesting { drained.fulfill() }
        wait(for: [drained], timeout: 2)
    }
    func testAuthorizedStartSchedulesOneHonestPlannedDeadlineNotification() {
        let center = NotificationCenterStub(), sut = notifier(center)
        let added = expectation(description: "one scheduled notification")
        added.assertForOverFulfill = true
        center.onAdd = { request, completion in
            XCTAssertEqual(request.identifier, "flint.session.complete.a")
            XCTAssertEqual(request.content.title, "Planned focus time ended")
            let trigger = request.trigger as! UNCalendarNotificationTrigger
            XCTAssertFalse(trigger.repeats)
            XCTAssertEqual(trigger.dateComponents.calendar?.date(from: trigger.dateComponents), self.now.addingTimeInterval(1801))
            added.fulfill(); completion(nil)
        }
        let event = FlintSessionLifecycleEvent.started(session(seconds: 1800.1), source: .shortcut, at: now)
        sut.handle(event); sut.handle(event)
        wait(for: [added], timeout: 2)
        drain(sut)
        XCTAssertEqual(center.requests.count, 1)
        XCTAssertEqual(center.requests.first?.content.body, "Your focus session has reached its planned end.")
        XCTAssertTrue(center.requests.first!.content.userInfo.isEmpty)
        XCTAssertEqual(center.requestCount, 0)
        withExtendedLifetime(sut) {}
    }
    func testDeniedAndUndeterminedNeverScheduleOrPromptAutomatically() {
        for status in [UNAuthorizationStatus.denied, .notDetermined] {
            let center = NotificationCenterStub(); center.status = status
            let sut = notifier(center), checked = expectation(description: "authorization read")
            center.onStatus = { callback in callback(status); checked.fulfill() }
            center.onAdd = { _, _ in XCTFail("unauthorized schedule") }
            sut.handle(.started(session(), source: .manual, at: now))
            wait(for: [checked], timeout: 2)
            drain(sut)
            XCTAssertTrue(center.requests.isEmpty)
            XCTAssertEqual(center.requestCount, 0)
            withExtendedLifetime(sut) {}
        }
    }
    func testPermissionRequestIsExplicitAndDenialIsNotReprompted() {
        let center = NotificationCenterStub(), sut = notifier(center)
        center.status = .notDetermined
        sut.requestPermission { XCTAssertTrue($0) }
        XCTAssertEqual(center.requestCount, 1)
        center.status = .denied
        sut.requestPermission { XCTAssertFalse($0) }
        XCTAssertEqual(center.requestCount, 1)
        center.status = .authorized
        sut.requestPermission { XCTAssertTrue($0) }
        XCTAssertEqual(center.requestCount, 1)
        drain(sut)
    }
    func testEarlyEndCancelsEvenWhenStartAuthorizationArrivesLate() {
        let center = NotificationCenterStub(), sut = notifier(center)
        let requested = expectation(description: "status requested"), cancelled = expectation(description: "cancelled")
        var releaseStatus: ((UNAuthorizationStatus) -> Void)?
        center.onStatus = { releaseStatus = $0; requested.fulfill() }
        center.onAdd = { _, _ in XCTFail("late status must not revive cancelled session") }
        center.onCancel = { XCTAssertEqual($0, ["flint.session.complete.a"]); cancelled.fulfill() }
        sut.handle(.started(session(), source: .manual, at: now))
        wait(for: [requested], timeout: 2)
        sut.handle(.ended(session(), route: .manualStop, at: now.addingTimeInterval(60)))
        wait(for: [cancelled], timeout: 2)
        releaseStatus?(.authorized)
        // Queue a second cancellation as a barrier after the authorization callback.
        let barrier = expectation(description: "late callback processed")
        center.onCancel = { _ in barrier.fulfill() }
        sut.handle(.ended(session(id: "barrier"), route: .manualStop, at: now))
        wait(for: [barrier], timeout: 2)
        drain(sut)
    }
    func testCancellationWinsAnAddCompletionRace() {
        let center = NotificationCenterStub(), sut = notifier(center)
        let added = expectation(description: "add initiated"), cancelled = expectation(description: "cancelled twice")
        cancelled.expectedFulfillmentCount = 2
        var finishAdd: ((Error?) -> Void)?
        center.onAdd = { _, completion in finishAdd = completion; added.fulfill() }
        center.onCancel = { XCTAssertEqual($0, ["flint.session.complete.a"]); cancelled.fulfill() }
        sut.handle(.started(session(), source: .manual, at: now))
        wait(for: [added], timeout: 2)
        sut.handle(.superseded(session(), by: session(id: "b"), at: now.addingTimeInterval(1)))
        finishAdd?(nil)
        wait(for: [cancelled], timeout: 2)
        drain(sut)
    }
    func testStartupRecoversSavedOptOutWithoutSchedulingOrRequestingPermission() {
        defaults.set(false, forKey: FlintCompletionNotifier.preferenceKey)
        let center = NotificationCenterStub()
        center.pending = ["flint.session.complete.a", "unrelated.notification"]
        let sut = notifier(center)
        drain(sut)
        XCTAssertEqual(center.pending, ["unrelated.notification"])
        XCTAssertEqual(center.cancelled, ["flint.session.complete.a"])
        XCTAssertTrue(center.requests.isEmpty)
        XCTAssertEqual(center.statusCount, 0)
        XCTAssertEqual(center.requestCount, 0)
    }

    func testStartupOptOutEnumerationPreservesNewStartAfterReenable() {
        defaults.set(false, forKey: FlintCompletionNotifier.preferenceKey)
        let center = NotificationCenterStub()
        center.pending = ["flint.session.complete.a", "unrelated.notification"]
        var pendingReply: (([String]) -> Void)?
        center.onPending = { pendingReply = $0 }
        let sut = notifier(center)
        drain(sut)
        sut.setEnabled(true)
        sut.handle(.started(session(id: "b"), source: .manual, at: now))
        drain(sut)
        pendingReply?(["flint.session.complete.a", "flint.session.complete.b", "unrelated.notification"])
        drain(sut)
        XCTAssertEqual(Set(center.pending), ["flint.session.complete.b", "unrelated.notification"])
        XCTAssertEqual(center.cancelled, ["flint.session.complete.a"])
        XCTAssertEqual(center.requests.map(\.identifier), ["flint.session.complete.b"])
        XCTAssertEqual(center.requestCount, 0)
    }

    func testToggleOffCancelsOnlyFlintCompletionRequests() {
        let center = NotificationCenterStub(), sut = notifier(center)
        // Isolate the explicit toggle from the separately tested startup recovery.
        // Both may cancel the same identifier safely if the preference changes
        // while the startup enumeration is in flight.
        drain(sut)
        XCTAssertTrue(sut.isEnabled)
        let cancelled = expectation(description: "pending completion cancelled")
        center.pending = ["flint.session.complete.a", "unrelated.notification"]
        center.onCancel = { XCTAssertEqual($0, ["flint.session.complete.a"]); cancelled.fulfill() }
        sut.setEnabled(false)
        wait(for: [cancelled], timeout: 2)
        drain(sut)
        XCTAssertFalse(sut.isEnabled)
        XCTAssertFalse(defaults.bool(forKey: FlintCompletionNotifier.preferenceKey))
    }
    func testDisabledIndefinitePastAndExtensionStartsDoNotSchedule() {
        for (end, enabled) in [(nil as Double?, true), (-1, true), (0, true), (1800, false)] {
            defaults.set(enabled, forKey: FlintCompletionNotifier.preferenceKey)
            let center = NotificationCenterStub(), sut = notifier(center)
            sut.handle(.started(session(seconds: end), source: .manual, at: now))
            drain(sut)
            XCTAssertTrue(center.requests.isEmpty)
            XCTAssertEqual(center.statusCount, 0)
        }
        defaults.set(true, forKey: FlintCompletionNotifier.preferenceKey)
        let center = NotificationCenterStub()
        let extensionSut = notifier(center, host: false)
        extensionSut.handle(.started(session(), source: .shortcut, at: now))
        extensionSut.requestPermission { XCTAssertFalse($0) }
        drain(extensionSut)
        XCTAssertTrue(center.requests.isEmpty)
        XCTAssertEqual(center.statusCount, 0)
        XCTAssertEqual(center.requestCount, 0)
    }
    func testProvisionalAuthorizationIsUsableAndCompletionDoesNotCancel() {
        XCTAssertTrue(FlintCompletionNotifier.isAuthorized(.authorized))
        XCTAssertTrue(FlintCompletionNotifier.isAuthorized(.provisional))
        XCTAssertTrue(FlintCompletionNotifier.isAuthorized(.ephemeral))
        XCTAssertFalse(FlintCompletionNotifier.isAuthorized(.denied))
        let center = NotificationCenterStub(), sut = notifier(center)
        center.status = .provisional
        let added = expectation(description: "provisional scheduled")
        center.onAdd = { _, done in added.fulfill(); done(nil) }
        center.onCancel = { _ in XCTFail("logical completion must not cancel its planned notification") }
        sut.handle(.started(session(), source: .manual, at: now))
        wait(for: [added], timeout: 2)
        drain(sut)
        sut.handle(.ended(session(), route: .completed, at: now.addingTimeInterval(1800)))
        drain(sut)
        XCTAssertEqual(center.pending, ["flint.session.complete.a"])
    }

    func testReplayedStartAfterEarlyEndOrSupersessionNeverRevivesRequest() {
        for superseded in [false, true] {
            let center = NotificationCenterStub(), sut = notifier(center)
            sut.handle(.started(session(), source: .manual, at: now)); drain(sut)
            if superseded { sut.handle(.superseded(session(), by: session(id: "b"), at: now)) }
            else { sut.handle(.ended(session(), route: .manualStop, at: now)) }
            sut.handle(.started(session(), source: .manual, at: now))
            sut.handle(.started(session(id: "b"), source: .manual, at: now))
            drain(sut)
            XCTAssertEqual(center.requests.map(\.identifier), ["flint.session.complete.a", "flint.session.complete.b"])
            XCTAssertEqual(center.pending, ["flint.session.complete.b"])
        }
    }
    func testRestartCancelsPersistedTerminalAndRejectsItsReplayedStart() {
        history.append(FlintHistoryEvent(.started(session(), source: .manual, at: now)))
        history.append(FlintHistoryEvent(.ended(session(), route: .manualStop, at: now)))
        let center = NotificationCenterStub(); center.pending = ["flint.session.complete.a", "flint.session.complete.b", "unrelated"]
        let restarted = notifier(center); drain(restarted)
        XCTAssertEqual(center.pending, ["flint.session.complete.b", "unrelated"])
        restarted.handle(.started(session(), source: .manual, at: now)); drain(restarted)
        XCTAssertTrue(center.requests.isEmpty)
    }
    func testEveryEarlyRouteCancelsIncludingEmergencyPass() {
        for route in FlintSessionEndRoute.allCases where route.isEarly {
            let center = NotificationCenterStub(), sut = notifier(center)
            sut.handle(.started(session(), source: .manual, at: now)); drain(sut)
            sut.handle(.ended(session(), route: route, at: now)); drain(sut)
            XCTAssertEqual(center.cancelled, ["flint.session.complete.a"], route.rawValue)
            XCTAssertTrue(center.pending.isEmpty)
        }
    }
    func testNonterminalGlobalAndLegacyPassKeepLiteralPlannedTimeReminder() {
        let center = NotificationCenterStub(), sut = notifier(center)
        sut.handle(.started(session(), source: .manual, at: now)); drain(sut)
        sut.handle(.passUsed(session(), at: now))
        sut.handle(.globalPassUsed(operationID: "pass", sessions: [session()], recoveryEnd: now.addingTimeInterval(300), at: now))
        drain(sut)
        XCTAssertEqual(center.pending, ["flint.session.complete.a"])
        XCTAssertTrue(center.cancelled.isEmpty)
    }
    func testClearKeepsRunningSessionAndItsPendingReminder() {
        history.append(FlintHistoryEvent(.started(session(), source: .manual, at: now)))
        let center = NotificationCenterStub(), sut = notifier(center)
        sut.handle(.started(session(), source: .manual, at: now)); drain(sut)
        XCTAssertTrue(history.clear(keepingRunningSession: "a"))
        let restarted = notifier(center); drain(restarted)
        XCTAssertEqual(center.pending, ["flint.session.complete.a"])
        XCTAssertTrue(center.cancelled.isEmpty)
    }
    func testOffOnPendingEnumerationCannotCancelANewStart() {
        let center = NotificationCenterStub(), sut = notifier(center)
        sut.handle(.started(session(), source: .manual, at: now)); drain(sut)
        var finishEnumeration: (([String]) -> Void)?
        center.onPending = { finishEnumeration = $0 }
        sut.setEnabled(false); drain(sut)
        sut.setEnabled(true)
        sut.handle(.started(session(id: "b"), source: .manual, at: now)); drain(sut)
        finishEnumeration?(["flint.session.complete.a", "flint.session.complete.b", "unrelated"])
        drain(sut)
        XCTAssertEqual(center.pending, ["flint.session.complete.b"])
        XCTAssertEqual(center.cancelled, ["flint.session.complete.a"])
    }
}

private final class NotificationCenterStub: FlintNotificationCenter {
    var status: UNAuthorizationStatus = .authorized
    var requestCount = 0
    var statusCount = 0
    var pending: [String] = []
    var requests: [UNNotificationRequest] = []
    var cancelled: [String] = []
    var onStatus: ((@escaping (UNAuthorizationStatus) -> Void) -> Void)?
    var onAdd: ((UNNotificationRequest, @escaping (Error?) -> Void) -> Void)?
    var onCancel: (([String]) -> Void)?
    var onPending: ((@escaping ([String]) -> Void) -> Void)?
    func authorizationStatus(_ completion: @escaping (UNAuthorizationStatus) -> Void) {
        statusCount += 1
        if let onStatus { onStatus(completion) } else { completion(status) }
    }
    func requestAuthorization(_ completion: @escaping (Bool) -> Void) { requestCount += 1; completion(true) }
    func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void) {
        requests.append(request)
        pending.removeAll { $0 == request.identifier }; pending.append(request.identifier)
        if let onAdd { onAdd(request, completion) } else { completion(nil) }
    }
    func cancel(_ identifiers: [String]) {
        cancelled.append(contentsOf: identifiers); pending.removeAll { identifiers.contains($0) }
        onCancel?(identifiers)
    }
    func pendingIdentifiers(_ completion: @escaping ([String]) -> Void) {
        if let onPending { onPending(completion) } else { completion(pending) }
    }
}
