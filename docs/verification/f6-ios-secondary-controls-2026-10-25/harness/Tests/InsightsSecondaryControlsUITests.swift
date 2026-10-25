import XCTest

/// Focused follow-up for the shared secondary-label target fix. Existing semantic/range
/// proofs remain in their original result bundles; these tests do not alter real permissions.
final class InsightsSecondaryControlsUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    private let prefix = "insights.authorization"

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    private func launch(_ scenario: String) {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-flint.onboarded", "YES", "-FlintUIPreviewAuthorizationScenario", scenario]
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
        XCTFail("Secondary control is not reachable: \(element)")
    }

    private func verifyTarget(_ button: XCUIElement) {
        reveal(button)
        XCTAssertGreaterThanOrEqual(button.frame.height, 44, "The Button accessibility target must include its minimum-height label")
        XCTAssertGreaterThanOrEqual(button.frame.width, 44)
        XCTAssertTrue(button.isEnabled)
        print("F6-SECONDARY-TARGET \(button.identifier) \(button.frame)")
    }

    private func snapshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F6-SECONDARY-SNAPSHOT \(name)\n\(app.debugDescription)\nEND-F6-SECONDARY-SNAPSHOT")
    }

    func testDeniedRecheckHasMinimumTargetAndKeepsSettingsRecovery() {
        launch("denied")
        let check = app.buttons["\(prefix).check"]
        verifyTarget(check)
        snapshot("01-denied-recheck-target")
        // This upper leading portion lies outside the former reported text-only AX frame.
        check.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.1)).tap()
        XCTAssertTrue(action("Open Settings").exists)
        XCTAssertTrue(app.staticTexts["\(prefix).guidance"].exists)
        XCTAssertFalse(app.buttons["Retry Screen Time"].exists)
        XCTAssertFalse(app.buttons["insights.checkAccess"].exists)
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "label == 'Check access again'")).count, 1)
        verifyTarget(check)
        snapshot("02-denied-after-recheck")
    }

    func testStopWaitingHasMinimumTargetAndPaddedCornerTapRecovers() {
        launch("hung")
        app.buttons["Today"].firstMatch.tap()
        let allow = action("Allow Screen Time")
        reveal(allow)
        allow.tap()
        let requesting = action("Requesting access…")
        XCTAssertTrue(requesting.waitForExistence(timeout: 5))
        XCTAssertFalse(requesting.isEnabled)
        let cancel = app.buttons["\(prefix).cancel"]
        verifyTarget(cancel)
        snapshot("03-stop-waiting-target")
        cancel.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.1)).tap()
        XCTAssertTrue(allow.waitForExistence(timeout: 5))
        XCTAssertTrue(allow.isEnabled)
        XCTAssertFalse(cancel.exists)
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
        reveal(allow)
        snapshot("04-stopped-without-error")
        allow.tap()
        XCTAssertTrue(app.staticTexts["Insights needs a device"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'reports for today'")).firstMatch.exists)
        XCTAssertFalse(app.buttons["\(prefix).action"].exists)
        snapshot("05-recovered-today")
    }
}
