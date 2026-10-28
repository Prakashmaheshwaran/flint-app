import XCTest
import UserNotifications
import FlintCore

final class FlintCompletionNotificationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var defaults: UserDefaults!
    private var suite: String!
    override func setUp() {
        suite = "FlintCompletionNotificationTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }
    override func tearDown() { defaults.removePersistentDomain(forName: suite) }
    private func session(id: String = "a", seconds: Double? = 1800) -> FlintActiveSession {
        .init(id: id, name: "Read", startedAt: now, endsAt: seconds.map { now.addingTimeInterval($0) },
              breakLevel: .easy, monitorName: "test")
    }
    private func notifier(_ center: NotificationCenterStub, host: Bool = true) -> FlintCompletionNotifier {
        .init(center: center, defaults: defaults, isHost: host, clock: { self.now })
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
    }
    func testToggleOffCancelsOnlyFlintCompletionRequests() {
        let center = NotificationCenterStub(), sut = notifier(center)
        XCTAssertTrue(sut.isEnabled)
        let cancelled = expectation(description: "pending completion cancelled")
        center.pending = ["flint.session.complete.a", "unrelated.notification"]
        center.onCancel = { XCTAssertEqual($0, ["flint.session.complete.a"]); cancelled.fulfill() }
        sut.setEnabled(false)
        wait(for: [cancelled], timeout: 2)
        XCTAssertFalse(sut.isEnabled)
        XCTAssertFalse(defaults.bool(forKey: FlintCompletionNotifier.preferenceKey))
    }
    func testDisabledIndefinitePastAndExtensionStartsDoNotSchedule() {
        let center = NotificationCenterStub(), sut = notifier(center)
        center.onAdd = { _, _ in XCTFail("unexpected notification") }
        center.onStatus = { _ in XCTFail("ineligible start must not query authorization") }
        let barrier = expectation(description: "all starts processed")
        center.onCancel = { _ in barrier.fulfill() }
        sut.handle(.started(session(seconds: nil), source: .manual, at: now))
        sut.handle(.started(session(seconds: -1), source: .manual, at: now))
        defaults.set(false, forKey: FlintCompletionNotifier.preferenceKey)
        sut.handle(.started(session(), source: .manual, at: now))
        sut.handle(.ended(session(id: "barrier"), route: .manualStop, at: now))
        wait(for: [barrier], timeout: 2)
        let extensionSut = notifier(center, host: false)
        extensionSut.handle(.started(session(), source: .shortcut, at: now))
        extensionSut.requestPermission { XCTAssertFalse($0) }
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
        sut.handle(.ended(session(), route: .completed, at: now.addingTimeInterval(1800)))
        wait(for: [added], timeout: 2)
    }
}

private final class NotificationCenterStub: FlintNotificationCenter {
    var status: UNAuthorizationStatus = .authorized
    var requestCount = 0
    var pending: [String] = []
    var onStatus: ((@escaping (UNAuthorizationStatus) -> Void) -> Void)?
    var onAdd: ((UNNotificationRequest, @escaping (Error?) -> Void) -> Void)?
    var onCancel: (([String]) -> Void)?
    func authorizationStatus(_ completion: @escaping (UNAuthorizationStatus) -> Void) {
        if let onStatus { onStatus(completion) } else { completion(status) }
    }
    func requestAuthorization(_ completion: @escaping (Bool) -> Void) { requestCount += 1; completion(true) }
    func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void) { onAdd?(request, completion) }
    func cancel(_ identifiers: [String]) { onCancel?(identifiers) }
    func pendingIdentifiers(_ completion: @escaping ([String]) -> Void) { completion(pending) }
}
