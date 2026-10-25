import XCTest

final class PermissionTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    let longPermissionError = """
        Screen Time could not finish this request.
        Flint has not received permission to rest your apps. This is a deliberately long UI fixture used to check wrapping when the system returns several lines of explanation.
        Access remains off. You can read this entire message, then use Retry Screen Time below to make another request.
        """
    let longSessionError = """
        Focus could not start.
        This is a long session-error layout fixture. Your apps have not been shielded, and the failed session has not started. The complete explanation must remain readable when you return to Today.
        Review the session settings with Start focus below before trying again.
        """

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        app.terminate()
    }

    func launch(_ arguments: [String] = []) {
        app.terminate()
        app.launchArguments = ["-flint.onboarded", "YES"] + arguments
        app.launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
        XCTAssertLessThan(app.scrollViews.firstMatch.frame.maxY, app.buttons["Today"].frame.minY,
                          "Scrolling feedback must have its own viewport above the tab controls")
    }

    func snapshot(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
        print("F5-SNAPSHOT \(name)\n\(app.debugDescription)\nEND-F5-SNAPSHOT")
    }

    func scrollForward() {
        let scroll = app.scrollViews.firstMatch
        scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.70))
            .press(forDuration: 0.05, thenDragTo: scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.35)))
    }

    func isFullyReachable(_ element: XCUIElement) -> Bool {
        element.exists && element.isHittable && element.frame.minY >= 65
            && element.frame.maxY < app.buttons["Today"].frame.minY - 12
    }

    func reveal(_ element: XCUIElement, snapshots prefix: String? = nil) {
        for index in 0..<16 {
            if let prefix { snapshot("\(prefix)-\(index)") }
            if isFullyReachable(element) { return }
            scrollForward()
        }
        XCTFail("Could not reach \(element.identifier): \(element.frame)")
    }

    func assertPermissionOrder() {
        let explanation = app.staticTexts["today.authorization.explanation"]
        let error = app.staticTexts["today.authorization.error"]
        let action = app.buttons["today.authorization.action"]
        XCTAssertTrue(explanation.exists)
        XCTAssertTrue(error.exists)
        XCTAssertTrue(action.exists)
        XCTAssertLessThanOrEqual(explanation.frame.maxY + 8, error.frame.minY)
        XCTAssertLessThanOrEqual(error.frame.maxY + 8, action.frame.minY)
        XCTAssertGreaterThanOrEqual(error.frame.minX, 20)
        XCTAssertLessThanOrEqual(error.frame.maxX, app.frame.maxX - 20)
        print("F5-PERMISSION-FRAMES explanation=\(explanation.frame) error=\(error.frame) action=\(action.frame)")
    }

    func testActualSimulatorFailureAndRetry() {
        launch()
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        XCTAssertEqual(action.label, "Allow Screen Time")
        snapshot("real-permission-before")
        action.tap()
        let error = app.staticTexts["today.authorization.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertFalse(error.label.isEmpty)
        XCTAssertEqual(action.label, "Retry Screen Time")
        assertPermissionOrder()
        reveal(action, snapshots: "real-permission-result")
        let originalError = error.label
        action.tap()
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertEqual(error.label, originalError)
        XCTAssertEqual(action.label, "Retry Screen Time")
        XCTAssertTrue(action.isEnabled)
        assertPermissionOrder()
        reveal(action)
        snapshot("real-permission-retry")
        XCTAssertFalse(app.buttons["Start focus"].exists)
    }

    func testLongPermissionFailureAndSessionFailureStaySeparate() {
        launch(["-FlintUIPreviewAuthorizationError", longPermissionError,
                "-FlintUIPreviewSessionError", "The previous focus session could not start."])
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        action.tap()
        let error = app.staticTexts["today.authorization.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertEqual(error.label, longPermissionError)
        assertPermissionOrder()
        let session = app.staticTexts["today.session.error"]
        XCTAssertTrue(session.exists)
        XCTAssertLessThanOrEqual(session.frame.maxY + 8, app.staticTexts["today.authorization.explanation"].frame.minY)
        reveal(action, snapshots: "long-permission")
        XCTAssertEqual(action.label, "Retry Screen Time")
        action.tap()
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertEqual(error.label, longPermissionError)
        XCTAssertTrue(action.isEnabled)
        XCTAssertFalse(app.buttons["Start focus"].exists)
    }

    func testSessionErrorRemainsReadableAfterReturningFromStart() {
        launch(["-FlintUIPreview", "-FlintUIPreviewSessionError", longSessionError])
        let error = app.staticTexts["today.session.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertEqual(error.label, longSessionError)
        let action = app.buttons["Start focus"]
        XCTAssertLessThanOrEqual(error.frame.maxY + 8, action.frame.minY)
        XCTAssertGreaterThanOrEqual(error.frame.minX, 20)
        XCTAssertLessThanOrEqual(error.frame.maxX, app.frame.maxX - 20)
        reveal(action, snapshots: "long-session")
        action.tap()
        XCTAssertTrue(app.buttons["Close"].waitForExistence(timeout: 5))
        app.buttons["Close"].tap()
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertEqual(error.label, longSessionError)
        reveal(action)
        snapshot("session-return-to-today")
    }
}
