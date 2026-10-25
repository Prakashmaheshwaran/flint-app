import XCTest

/// Uses F5's explicit DEBUG view-state fixtures; never changes Screen Time authorization.
final class InsightsAuthorizationUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    private let prefix = "insights.authorization"
    private let transientMessage = "Screen Time could not finish this request.\nThe connection ended before access was enabled. Please try again."

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    private func launch(scenario: String, error: String? = nil) {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = [
            "-flint.onboarded", "YES",
            "-FlintUIPreviewAuthorizationScenario", scenario
        ]
        if let error { app.launchArguments += ["-FlintUIPreviewAuthorizationError", error] }
        app.launch()
        XCTAssertTrue(app.buttons["Insights"].waitForExistence(timeout: 10))
        app.buttons["Insights"].tap()
        XCTAssertTrue(app.staticTexts["Screen Time access needed"].waitForExistence(timeout: 5))
    }

    private func action(_ label: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "\(prefix).action", label)).firstMatch
    }

    private func reveal(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        for _ in 0..<16 {
            if element.isHittable { return }
            let scroll = app.scrollViews.firstMatch
            if element.frame.midY >= scroll.frame.midY { scroll.swipeUp() }
            else { scroll.swipeDown() }
        }
        XCTFail("Element is not reachable: \(element)")
    }

    private func snapshot(_ name: String, application: XCUIApplication? = nil) {
        let target = application ?? app
        let attachment = XCTAttachment(screenshot: target.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F6-REVIEW-SNAPSHOT \(name)\n\(target.debugDescription)\nEND-F6-REVIEW-SNAPSHOT")
    }

    private func chooseRange(_ label: String) {
        let button = app.buttons[label].firstMatch
        XCTAssertTrue(button.isHittable)
        button.tap()
    }

    private func assertApprovedSimulator(range: String) {
        XCTAssertTrue(app.staticTexts["Insights needs a device"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "reports for \(range)")).firstMatch.exists)
        XCTAssertFalse(app.buttons["\(prefix).action"].exists)
        XCTAssertFalse(app.buttons["\(prefix).check"].exists)
        XCTAssertFalse(app.buttons["\(prefix).cancel"].exists)
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
    }

    func testDeniedUsesSettingsAndOneRecheckForBothRangesAndResume() {
        launch(scenario: "denied")
        let settingsAction = action("Open Settings")
        let check = app.buttons["\(prefix).check"]
        let guidance = app.staticTexts["\(prefix).guidance"]
        XCTAssertTrue(settingsAction.exists)
        XCTAssertTrue(guidance.exists)
        XCTAssertEqual(app.buttons.matching(identifier: "\(prefix).action").count, 1)
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "label == 'Check access again'")).count, 1)
        XCTAssertFalse(app.buttons["insights.checkAccess"].exists)
        XCTAssertFalse(app.buttons["Allow Screen Time"].exists)
        XCTAssertFalse(app.buttons["Retry Screen Time"].exists)
        XCTAssertFalse(app.buttons["Try again"].exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'activity for the last 7 days'")).firstMatch.exists)
        snapshot("01-denied-week")

        chooseRange("Today")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'activity for today'")).firstMatch.exists)
        snapshot("02-denied-today")
        reveal(guidance)
        XCTAssertTrue(guidance.label.contains("Apps with Screen Time Access"))
        XCTAssertTrue(guidance.label.contains("Enable Flint if it’s listed"))
        snapshot("03-denied-guidance")
        reveal(settingsAction)
        snapshot("04-denied-settings-action")
        settingsAction.tap()

        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 5))
        // This Simulator opens Settings' root page. Record the actual destination;
        // opening Settings does not prove a Flint permission switch is available.
        XCTAssertTrue(settings.navigationBars.firstMatch.waitForExistence(timeout: 5))
        snapshot("05-settings-destination", application: settings)
        // Observe the destination only; do not change switches or other Settings values.
        app.activate()
        XCTAssertTrue(settingsAction.waitForExistence(timeout: 5))
        reveal(check)
        check.tap()
        XCTAssertTrue(settingsAction.exists)
        XCTAssertTrue(guidance.exists)
        XCTAssertFalse(app.buttons["Retry Screen Time"].exists)
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
        snapshot("06-denied-after-settings-and-recheck")

        chooseRange("7 days")
        reveal(app.staticTexts["Screen Time access needed"])
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'activity for the last 7 days'")).firstMatch.exists)
        snapshot("07-denied-week-after-resume")
    }

    func testTransientFailureRetriesAndKeepsSelectedRange() {
        launch(scenario: "transient", error: transientMessage)
        chooseRange("Today")
        let allow = action("Allow Screen Time")
        reveal(allow)
        allow.tap()
        let retry = action("Retry Screen Time")
        XCTAssertTrue(retry.waitForExistence(timeout: 5))
        let error = app.staticTexts["\(prefix).error"]
        XCTAssertEqual(error.label, transientMessage)
        XCTAssertFalse(app.staticTexts["\(prefix).guidance"].exists)
        XCTAssertFalse(app.buttons["\(prefix).check"].exists)
        reveal(error)
        snapshot("08-transient-error")
        reveal(retry)
        XCTAssertTrue(retry.isEnabled)
        snapshot("09-transient-retry")
        retry.tap()
        assertApprovedSimulator(range: "today")
        snapshot("10-transient-recovered-today")
        chooseRange("7 days")
        assertApprovedSimulator(range: "the last 7 days")
        snapshot("11-transient-recovered-week")
    }

    func testStopWaitingReleasesPendingRequestAndAllowsRecovery() {
        launch(scenario: "hung")
        let allow = action("Allow Screen Time")
        reveal(allow)
        allow.tap()
        let requesting = action("Requesting access…")
        XCTAssertTrue(requesting.waitForExistence(timeout: 5))
        XCTAssertFalse(requesting.isEnabled)
        let cancel = app.buttons["\(prefix).cancel"]
        reveal(cancel)
        snapshot("12-pending-stop-waiting")

        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(requesting.waitForExistence(timeout: 5))
        XCTAssertFalse(requesting.isEnabled, "Foreground refresh alone must not cancel Apple's permission request")
        reveal(cancel)
        snapshot("13-pending-after-resume")
        cancel.tap()
        XCTAssertTrue(allow.waitForExistence(timeout: 5))
        XCTAssertTrue(allow.isEnabled)
        XCTAssertFalse(cancel.exists)
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
        reveal(allow)
        snapshot("14-stopped-without-error")
        allow.tap()
        assertApprovedSimulator(range: "the last 7 days")
        snapshot("15-recovered-after-stop-waiting")
    }

    func testTimeoutReleasesPendingRequestAndAllowsRetry() {
        launch(scenario: "timeout")
        let allow = action("Allow Screen Time")
        reveal(allow)
        allow.tap()
        let retry = action("Retry Screen Time")
        XCTAssertTrue(retry.waitForExistence(timeout: 8))
        let error = app.staticTexts["\(prefix).error"]
        XCTAssertTrue(error.label.contains("taking longer than expected"))
        XCTAssertTrue(error.label.contains("finish or dismiss it before trying again"))
        XCTAssertFalse(app.buttons["\(prefix).cancel"].exists)
        reveal(error)
        snapshot("16-timeout-guidance")
        reveal(retry)
        XCTAssertTrue(retry.isEnabled)
        snapshot("17-timeout-retry")
        retry.tap()
        assertApprovedSimulator(range: "the last 7 days")
        snapshot("18-recovered-after-timeout")
    }
}
