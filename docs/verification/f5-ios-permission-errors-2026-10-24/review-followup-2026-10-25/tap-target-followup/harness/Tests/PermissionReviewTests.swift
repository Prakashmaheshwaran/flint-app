import XCTest

final class PermissionReviewTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    let longError = """
        Screen Time could not finish this request.
        Flint has not received permission to rest your apps. This deliberately long UI fixture checks that every line remains readable when a transient failure includes several sentences of explanation.
        Access remains off. Read this entire message, then use Retry Screen Time below to make another request.
        """

    override func setUp() { continueAfterFailure = false }
    override func tearDown() { app.terminate() }

    func launch(_ scenario: String? = nil, onboarding: Bool = false, extra: [String] = []) {
        app.terminate()
        app.launchArguments = ["-flint.onboarded", onboarding ? "NO" : "YES"] + extra
        if let scenario { app.launchArguments += ["-FlintUIPreviewAuthorizationScenario", scenario] }
        app.launch()
        if onboarding {
            XCTAssertTrue(app.buttons["Begin"].waitForExistence(timeout: 5))
            app.buttons["Begin"].tap()
        } else {
            XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
            XCTAssertLessThan(app.scrollViews.firstMatch.frame.maxY, app.buttons["Today"].frame.minY)
        }
    }

    func snapshot(_ name: String, application: XCUIApplication? = nil) {
        let target = application ?? app
        let image = XCTAttachment(screenshot: target.screenshot())
        image.name = name
        image.lifetime = .keepAlways
        add(image)
        print("F5-REVIEW-SNAPSHOT \(name)\n\(target.debugDescription)\nEND-F5-REVIEW-SNAPSHOT")
    }

    func reveal(_ element: XCUIElement, snapshots prefix: String? = nil) {
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        let scroll = app.scrollViews.firstMatch
        for index in 0..<18 {
            if let prefix { snapshot("\(prefix)-\(index)") }
            let viewport = scroll.frame.insetBy(dx: 0, dy: 8)
            if element.isHittable && element.frame.minY >= viewport.minY && element.frame.maxY <= viewport.maxY {
                XCTAssertGreaterThanOrEqual(element.frame.minX, 20)
                XCTAssertLessThanOrEqual(element.frame.maxX, app.frame.maxX - 20)
                XCTAssertGreaterThanOrEqual(element.frame.height, 44, "The actual accessibility button frame must be at least 44pt tall")
                XCTAssertGreaterThanOrEqual(element.frame.width, 44)
                print("F5-TAP-TARGET \(element.identifier) label=\(element.label) frame=\(element.frame)")
                return
            }
            let forward = element.frame.maxY > viewport.maxY
            scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: forward ? 0.72 : 0.32))
                .press(forDuration: 0.05, thenDragTo: scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: forward ? 0.32 : 0.72)))
        }
        XCTFail("Control is not fully reachable: \(element.identifier) \(element.frame), viewport \(scroll.frame)")
    }

    func assertApproved(_ snapshotName: String) {
        let start = app.buttons["Start focus"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["today.authorization.error"].exists)
        XCTAssertFalse(app.buttons["today.authorization.action"].exists)
        reveal(start)
        XCTAssertTrue(start.isEnabled)
        snapshot(snapshotName)
    }

    func testActualSimulatorFailureRemainsReadableAndRetryable() {
        launch()
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        XCTAssertEqual(action.label, "Allow Screen Time")
        action.tap()
        let error = app.staticTexts["today.authorization.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertFalse(error.label.isEmpty)
        XCTAssertEqual(action.label, "Retry Screen Time")
        reveal(action, snapshots: "actual-helper-error")
        XCTAssertTrue(action.isEnabled)
        action.tap()
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertEqual(action.label, "Retry Screen Time")
        XCTAssertFalse(app.buttons["Start focus"].exists)
        snapshot("actual-helper-retry")
    }

    func testDeniedUsesSettingsAndCheckWithoutReprompt() {
        launch("denied")
        let guidance = app.staticTexts["today.authorization.guidance"]
        let action = app.buttons["today.authorization.action"]
        let check = app.buttons["today.authorization.check"]
        XCTAssertTrue(guidance.waitForExistence(timeout: 5))
        XCTAssertTrue(guidance.label.contains("Enable Flint if it’s listed"))
        XCTAssertLessThanOrEqual(guidance.frame.maxY + 8, action.frame.minY)
        XCTAssertLessThanOrEqual(action.frame.maxY, check.frame.minY)
        XCTAssertEqual(action.label, "Open Settings")
        XCTAssertFalse(app.staticTexts["today.authorization.error"].exists)
        reveal(check, snapshots: "denied-guidance")
        XCTAssertTrue(check.isEnabled)
        check.tap()
        XCTAssertEqual(action.label, "Open Settings")

        reveal(action)
        action.tap()
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 8))
        snapshot("settings-destination", application: settings)
        app.activate()
        XCTAssertTrue(action.waitForExistence(timeout: 5))
        XCTAssertEqual(action.label, "Open Settings")
        XCTAssertFalse(app.buttons["Retry Screen Time"].exists)
        reveal(check)
        snapshot("denied-return")
    }

    func testTransientLongErrorAndSessionErrorStaySeparateThenRecover() {
        launch("transient", extra: ["-FlintUIPreviewAuthorizationError", longError,
                                    "-FlintUIPreviewSessionError", "The previous focus session could not start."])
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        action.tap()
        let error = app.staticTexts["today.authorization.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertEqual(error.label, longError)
        let explanation = app.staticTexts["today.authorization.explanation"]
        let session = app.staticTexts["today.session.error"]
        XCTAssertLessThanOrEqual(session.frame.maxY + 8, explanation.frame.minY)
        XCTAssertLessThanOrEqual(explanation.frame.maxY + 8, error.frame.minY)
        XCTAssertLessThanOrEqual(error.frame.maxY + 8, action.frame.minY)
        XCTAssertGreaterThanOrEqual(error.frame.minX, 20)
        XCTAssertLessThanOrEqual(error.frame.maxX, app.frame.maxX - 20)
        XCTAssertEqual(action.label, "Retry Screen Time")
        reveal(action, snapshots: "transient-long-error")
        XCTAssertTrue(action.isEnabled)
        action.tap()
        assertApproved("transient-recovered")
        XCTAssertTrue(session.exists, "Authorization recovery must not erase a separate session error")
    }

    func testHungRequestSurvivesForegroundThenStopWaitingAllowsRecovery() {
        launch("hung")
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        action.tap()
        let cancel = app.buttons["today.authorization.cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 5))
        XCTAssertFalse(action.isEnabled)
        XCTAssertEqual(action.label, "Requesting access…")
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(cancel.waitForExistence(timeout: 5))
        XCTAssertFalse(action.isEnabled, "Foreground refresh must not cancel the permission request")
        reveal(cancel, snapshots: "pending-controls")
        XCTAssertTrue(cancel.isEnabled)
        // Hit the padded corner, outside the centered glyphs, to verify the real label region.
        cancel.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.1)).tap()
        XCTAssertEqual(action.label, "Allow Screen Time")
        XCTAssertTrue(action.isEnabled)
        XCTAssertFalse(app.staticTexts["today.authorization.error"].exists)
        reveal(action)
        snapshot("stopped-waiting")
        action.tap()
        assertApproved("hung-recovered")
    }

    func testTimeoutOffersReadableRetryAndRecovers() {
        launch("timeout")
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        action.tap()
        let error = app.staticTexts["today.authorization.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 8))
        XCTAssertTrue(error.label.contains("permission sheet is still open"))
        XCTAssertEqual(action.label, "Retry Screen Time")
        reveal(action, snapshots: "timeout-guidance")
        XCTAssertTrue(action.isEnabled)
        action.tap()
        assertApproved("timeout-recovered")
    }

    func testCancellationDoesNotShowTechnicalError() {
        launch("cancelled")
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        action.tap()
        XCTAssertTrue(action.isEnabled)
        XCTAssertEqual(action.label, "Allow Screen Time")
        XCTAssertFalse(app.staticTexts["today.authorization.error"].exists)
        XCTAssertFalse(app.buttons["Start focus"].exists)
        snapshot("cancelled-silently")
    }

    func testOnboardingDeniedUsesSameReachableRecoveryControls() {
        launch("denied", onboarding: true)
        let guidance = app.staticTexts["onboarding.authorization.guidance"]
        let action = app.buttons["onboarding.authorization.action"]
        let check = app.buttons["onboarding.authorization.check"]
        XCTAssertTrue(guidance.waitForExistence(timeout: 5))
        XCTAssertEqual(action.label, "Open Settings")
        XCTAssertLessThanOrEqual(guidance.frame.maxY + 8, action.frame.minY)
        reveal(check, snapshots: "onboarding-denied")
        check.tap()
        XCTAssertEqual(action.label, "Open Settings")
        XCTAssertFalse(app.staticTexts["onboarding.authorization.error"].exists)
        let skip = app.buttons["Not now"]
        reveal(skip)
        snapshot("onboarding-denied-controls")
        skip.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.1)).tap()
        // The launch-argument domain forces flint.onboarded=NO for that process. Remove the
        // override before checking the value persisted by Not now; it outranks UserDefaults.
        app.terminate()
        app.launchArguments = ["-FlintUIPreviewAuthorizationScenario", "denied"]
        app.launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["today.authorization.action"].label, "Open Settings")
    }

    func testZOrdinaryLaunchDoesNotRetainPreviewOrPIN() {
        app.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.secureTextFields["PIN"].exists)
        let action = app.buttons["today.authorization.action"]
        reveal(action)
        XCTAssertEqual(action.label, "Allow Screen Time")
        XCTAssertFalse(app.staticTexts["today.authorization.error"].exists)
        XCTAssertFalse(app.staticTexts["today.authorization.guidance"].exists)
        XCTAssertFalse(app.staticTexts["today.session.error"].exists)
        XCTAssertFalse(app.buttons["Start focus"].exists)
        snapshot("ordinary-launch-cleanup")
    }
}
