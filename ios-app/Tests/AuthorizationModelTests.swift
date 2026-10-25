import XCTest
import FamilyControls
@testable import Flint

@MainActor
final class AuthorizationModelTests: XCTestCase {
    private struct Failure: LocalizedError {
        let errorDescription: String? = "Couldn’t communicate with a helper application.\nScreen Time remains unavailable."
    }

    /// A provider that deliberately ignores Task cancellation until the test completes it.
    private final class SuspendedCall {
        let started = XCTestExpectation(description: "Provider started")
        let returned = XCTestExpectation(description: "Provider returned")
        private var continuation: CheckedContinuation<Void, Error>?
        var isSuspended: Bool { continuation != nil }

        func run() async throws {
            defer { returned.fulfill() }
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                started.fulfill()
            }
        }

        func complete(_ result: Result<Void, Error> = .success(())) {
            let pending = continuation
            continuation = nil
            pending?.resume(with: result)
        }
    }

    func testDeniedAccessDoesNotRequestAgainFromAnyCaller() async {
        var calls = 0
        let model = AuthorizationModel(readStatus: { .denied }, request: { calls += 1 })

        // The guard must read the system even if this caller has not refreshed yet.
        await model.requestAuthorization()
        await model.requestAuthorization()
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(model.status, .denied)
        XCTAssertNil(model.lastError)
        XCTAssertFalse(model.isRequesting)
    }

    func testExistingApprovalDoesNotRequestAgain() async {
        var calls = 0
        let model = AuthorizationModel(readStatus: { .approved }, request: { calls += 1 })
        await model.requestAuthorization()
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(model.status, .approved)
    }

    func testTransientFailureCanRetryAndApprovalClearsError() async {
        var status: AuthorizationStatus = .notDetermined
        var calls = 0
        let model = AuthorizationModel(readStatus: { status }, request: {
            calls += 1
            if calls == 1 { throw Failure() }
            status = .approved
        })

        await model.requestAuthorization()
        XCTAssertEqual(model.lastError, Failure().errorDescription)
        XCTAssertFalse(model.isRequesting)
        await model.requestAuthorization()
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(model.status, .approved)
        XCTAssertNil(model.lastError)
        XCTAssertFalse(model.isRequesting)
    }

    func testDenialTakesPrecedenceOverProviderErrorAndStopsReprompt() async {
        var status: AuthorizationStatus = .notDetermined
        var calls = 0
        let model = AuthorizationModel(readStatus: { status }, request: {
            calls += 1
            status = .denied
            throw Failure()
        })
        await model.requestAuthorization()
        XCTAssertEqual(model.status, .denied)
        XCTAssertNil(model.lastError, "Denied access uses Settings guidance, not a technical retry error")
        await model.requestAuthorization()
        XCTAssertEqual(calls, 1)
        XCTAssertFalse(model.isRequesting)
    }

    func testRequestReturningWithoutApprovalDoesNotPretendToSucceed() async {
        let model = AuthorizationModel(readStatus: { .notDetermined }, request: {})
        await model.requestAuthorization()
        XCTAssertEqual(model.status, .notDetermined)
        XCTAssertEqual(model.lastError, "Screen Time access wasn’t enabled. You can try again.")
        XCTAssertFalse(model.isRequesting)
    }

    func testProviderCancellationIsSilentAndAnotherAttemptCanSucceed() async {
        var status: AuthorizationStatus = .notDetermined
        var calls = 0
        let model = AuthorizationModel(readStatus: { status }, request: {
            calls += 1
            if calls == 1 { throw CancellationError() }
            status = .approved
        })
        await model.requestAuthorization()
        XCTAssertNil(model.lastError)
        XCTAssertFalse(model.isRequesting)
        XCTAssertEqual(model.status, .notDetermined)
        await model.requestAuthorization()
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(model.status, .approved)
    }

    func testSystemAuthorizationCancellationIsSilent() async {
        let model = AuthorizationModel(readStatus: { .notDetermined }, request: {
            throw FamilyControlsError.authorizationCanceled
        })
        await model.requestAuthorization()
        XCTAssertNil(model.lastError)
        XCTAssertFalse(model.isRequesting)
    }

    func testConcurrentRequestIsIgnoredAndFailureReleasesInFlightState() async {
        let provider = SuspendedCall()
        var calls = 0
        let model = AuthorizationModel(readStatus: { .notDetermined }, request: {
            calls += 1
            try await provider.run()
        })

        let first = Task { await model.requestAuthorization() }
        await fulfillment(of: [provider.started], timeout: 2)
        XCTAssertTrue(model.isRequesting)
        await model.requestAuthorization()
        XCTAssertEqual(calls, 1)

        provider.complete(.failure(Failure()))
        await first.value
        XCTAssertFalse(model.isRequesting)
        XCTAssertEqual(model.lastError, Failure().errorDescription)
    }

    func testForegroundRefreshDoesNotCancelAnOpenPermissionSheet() async {
        var status: AuthorizationStatus = .notDetermined
        let provider = SuspendedCall()
        let model = AuthorizationModel(readStatus: { status }, request: { try await provider.run() })
        let task = Task { await model.requestAuthorization() }
        await fulfillment(of: [provider.started], timeout: 2)

        model.refresh()
        model.refresh()
        XCTAssertTrue(model.isRequesting)
        XCTAssertNil(model.lastError)

        status = .approved
        provider.complete()
        await task.value
        XCTAssertEqual(model.status, .approved)
        XCTAssertFalse(model.isRequesting)
    }

    func testExternalApprovalClearsErrorAndLaterRevocationIsRefreshed() async {
        var status: AuthorizationStatus = .notDetermined
        let model = AuthorizationModel(readStatus: { status }, request: { throw Failure() })
        await model.requestAuthorization()
        XCTAssertNotNil(model.lastError)

        status = .approved
        model.refresh()
        XCTAssertEqual(model.status, .approved)
        XCTAssertNil(model.lastError)

        status = .denied
        model.refresh()
        XCTAssertEqual(model.status, .denied)
        XCTAssertNil(model.lastError)
    }

    func testExternalApprovalReleasesHungWaiterAndIgnoresLateFailure() async {
        var status: AuthorizationStatus = .notDetermined
        let provider = SuspendedCall()
        let finished = expectation(description: "Caller released before provider")
        let model = AuthorizationModel(readStatus: { status }, request: { try await provider.run() })
        let task = Task { await model.requestAuthorization(); finished.fulfill() }
        await fulfillment(of: [provider.started], timeout: 2)

        status = .approved
        model.refresh()
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertFalse(model.isRequesting)
        XCTAssertTrue(provider.isSuspended)

        provider.complete(.failure(Failure()))
        await fulfillment(of: [provider.returned], timeout: 2)
        await task.value
        XCTAssertEqual(model.status, .approved)
        XCTAssertNil(model.lastError)
    }

    func testStopWaitingReleasesHungProviderAndLateResultCannotOverwriteNewRequest() async {
        var status: AuthorizationStatus = .notDetermined
        var calls = 0
        let old = SuspendedCall()
        let next = SuspendedCall()
        let finished = expectation(description: "Cancelled caller released")
        let model = AuthorizationModel(readStatus: { status }, request: {
            calls += 1
            if calls == 1 { try await old.run() } else { try await next.run() }
        })
        let first = Task { await model.requestAuthorization(); finished.fulfill() }
        await fulfillment(of: [old.started], timeout: 2)

        model.cancelRequest()
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertFalse(model.isRequesting)
        XCTAssertNil(model.lastError)
        XCTAssertTrue(old.isSuspended)

        let second = Task { await model.requestAuthorization() }
        await fulfillment(of: [next.started], timeout: 2)
        old.complete(.failure(Failure()))
        await fulfillment(of: [old.returned], timeout: 2)
        XCTAssertTrue(model.isRequesting, "An old result must not release the newer waiter")
        XCTAssertNil(model.lastError)

        status = .approved
        next.complete()
        await first.value
        await second.value
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(model.status, .approved)
        XCTAssertNil(model.lastError)
    }

    func testCallerTaskCancellationReleasesNoncooperativeProvider() async {
        let provider = SuspendedCall()
        let finished = expectation(description: "Cancelled caller released")
        let model = AuthorizationModel(readStatus: { .notDetermined }, request: { try await provider.run() })
        let task = Task { await model.requestAuthorization(); finished.fulfill() }
        await fulfillment(of: [provider.started], timeout: 2)

        task.cancel()
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertFalse(model.isRequesting)
        XCTAssertNil(model.lastError)
        XCTAssertTrue(provider.isSuspended)

        provider.complete(.failure(Failure()))
        await fulfillment(of: [provider.returned], timeout: 2)
        await task.value
        XCTAssertNil(model.lastError)
    }

    func testAlreadyCancelledCallerNeverStartsProvider() async {
        var calls = 0
        let model = AuthorizationModel(readStatus: { .notDetermined }, request: { calls += 1 })
        let task = Task { await model.requestAuthorization() }
        task.cancel()
        await task.value
        XCTAssertEqual(calls, 0)
        XCTAssertFalse(model.isRequesting)
        XCTAssertNil(model.lastError)
    }

    func testTimeoutReleasesNoncooperativeProviderAndAllowsRecovery() async {
        var status: AuthorizationStatus = .notDetermined
        var calls = 0
        var timers = 0
        let provider = SuspendedCall()
        let deadline = SuspendedCall()
        let finished = expectation(description: "Timed out caller released")
        let model = AuthorizationModel(readStatus: { status }, request: {
            calls += 1
            if calls == 1 { try await provider.run() } else { status = .approved }
        }, waitForTimeout: {
            timers += 1
            if timers == 1 { try await deadline.run() }
            else { try await Task.sleep(nanoseconds: 120_000_000_000) }
        })
        let task = Task { await model.requestAuthorization(); finished.fulfill() }
        await fulfillment(of: [provider.started, deadline.started], timeout: 2)

        deadline.complete()
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertFalse(model.isRequesting)
        XCTAssertTrue(provider.isSuspended)
        XCTAssertTrue(model.lastError?.contains("taking longer than expected") == true)

        await model.requestAuthorization()
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(model.status, .approved)
        XCTAssertNil(model.lastError)

        provider.complete(.failure(Failure()))
        await fulfillment(of: [provider.returned], timeout: 2)
        await task.value
        XCTAssertEqual(model.status, .approved)
        XCTAssertNil(model.lastError)
    }
}
