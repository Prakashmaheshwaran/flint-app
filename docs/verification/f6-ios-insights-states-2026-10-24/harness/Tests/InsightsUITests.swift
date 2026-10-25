import XCTest

// Adapted from the shared audit harness; this copy only exercises F6.
final class InsightsUITests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")

    func launch(preview: Bool = false) {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-flint.onboarded", "YES"] + (preview ? ["-FlintUIPreview"] : [])
        app.launch()
        XCTAssertTrue(app.buttons["Insights"].waitForExistence(timeout: 10))
        app.buttons["Insights"].tap()
    }

    func snapshot(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
        print("F6-SNAPSHOT \(name)\n\(app.debugDescription)\nEND-F6-SNAPSHOT")
    }

    func reveal(_ element: XCUIElement) {
        for _ in 0..<12 {
            if element.isHittable { return }
            app.scrollViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(element.isHittable, "Not reachable: \(element)")
    }

    func chooseToday() {
        let today = app.buttons["Today"].firstMatch
        XCTAssertTrue(today.isHittable)
        today.tap()
    }

    func testUnauthorizedBothRangesAndRefresh() {
        launch()
        XCTAssertTrue(app.staticTexts["Screen Time access needed"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'the last 7 days'")).firstMatch.exists)
        snapshot("01-unauthorized-week")
        chooseToday()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'activity for today'")).firstMatch.exists)
        snapshot("02-unauthorized-today")

        let check = app.buttons["insights.checkAccess"]
        reveal(check)
        check.tap()
        XCTAssertTrue(app.staticTexts["insights.accessFeedback"].waitForExistence(timeout: 3))
        reveal(app.staticTexts["insights.accessFeedback"])
        snapshot("03-check-access-feedback")

        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.buttons["7 days"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["insights.accessFeedback"].exists, "Scene activation refresh clears prior check feedback")
        app.buttons["7 days"].tap()
        snapshot("04-resume-refresh")
        app.terminate()
    }

    func testAuthorizationFailureAndRetry() {
        launch()
        let allow = app.buttons["insights.authorize"]
        reveal(allow)
        XCTAssertEqual(allow.label, "Allow Screen Time")
        allow.tap()
        let retry = app.buttons.matching(NSPredicate(format: "identifier == 'insights.authorize' AND label == 'Try again'")).firstMatch
        XCTAssertTrue(retry.waitForExistence(timeout: 15))
        XCTAssertTrue(app.otherElements["insights.authorizationError"].exists || app.staticTexts["insights.authorizationError"].exists)
        reveal(retry)
        snapshot("05-authorization-error")
        XCTAssertTrue(retry.isEnabled)
        retry.tap()
        XCTAssertTrue(retry.waitForExistence(timeout: 15))
        reveal(retry)
        XCTAssertTrue(retry.isEnabled)
        snapshot("06-retry-error")
        app.terminate()
    }

    func testSimulatorRemainsUnavailableInApprovedUIPreview() {
        launch(preview: true)
        XCTAssertTrue(app.staticTexts["Insights needs a device"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["insights.authorize"].exists)
        snapshot("07-simulator-week-ui-preview")
        chooseToday()
        XCTAssertTrue(app.staticTexts["Insights needs a device"].exists)
        snapshot("08-simulator-today-ui-preview")
        app.terminate()
    }
}
