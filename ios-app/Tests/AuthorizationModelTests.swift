import XCTest
import FamilyControls

@MainActor
final class AuthorizationModelTests: XCTestCase {
    private struct Failure: LocalizedError {
        let errorDescription: String? = "Couldn’t communicate with a helper application.\nScreen Time remains unavailable."
    }

    func testConcurrentRequestIsIgnoredAndFailureReleasesInFlightState() async {
        let started = expectation(description: "Request is suspended")
        var pending: CheckedContinuation<Void, Error>?
        var calls = 0
        let model = AuthorizationModel(readStatus: { .notDetermined }, request: {
            calls += 1
            try await withCheckedThrowingContinuation { continuation in
                pending = continuation
                started.fulfill()
            }
        })

        let first = Task { await model.requestAuthorization() }
        await fulfillment(of: [started], timeout: 2)
        XCTAssertTrue(model.isRequesting)
        await model.requestAuthorization()
        XCTAssertEqual(calls, 1)

        pending?.resume(throwing: Failure())
        await first.value
        XCTAssertFalse(model.isRequesting)
        XCTAssertEqual(model.status, .notDetermined)
        XCTAssertEqual(model.lastError, Failure().errorDescription)
    }

    func testRetryPerformsAnotherRequestAndApprovalClearsFailure() async {
        var status: AuthorizationStatus = .denied
        var calls = 0
        let model = AuthorizationModel(readStatus: { status }, request: {
            calls += 1
            if calls == 1 { throw Failure() }
            status = .approved
        })

        await model.requestAuthorization()
        XCTAssertEqual(model.status, .denied)
        XCTAssertEqual(model.lastError, Failure().errorDescription)
        await model.requestAuthorization()
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(model.status, .approved)
        XCTAssertNil(model.lastError)
        XCTAssertFalse(model.isRequesting)
    }

    func testRefreshClearsStaleFailureWhenAccessIsApprovedElsewhere() async {
        var status: AuthorizationStatus = .denied
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
    }

    func testRequestReturningWithoutApprovalDoesNotPretendToSucceed() async {
        let model = AuthorizationModel(readStatus: { .denied }, request: {})
        await model.requestAuthorization()
        XCTAssertEqual(model.status, .denied)
        XCTAssertEqual(model.lastError, "Screen Time access is still off. Try again to allow access.")
        XCTAssertFalse(model.isRequesting)
    }
}
