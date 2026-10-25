import XCTest

final class LayoutTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    func capture(_ name: String) {
        Thread.sleep(forTimeInterval: 1)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F4-SNAPSHOT \(name)\n\(app.debugDescription)\nEND-F4-SNAPSHOT")
    }
    func button(_ text: String) -> XCUIElement { app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch }
    func scrollUp() {
        // Observed hierarchy retains Today behind the sheet; the final scroll view is frontmost.
        let scrolls = app.scrollViews.allElementsBoundByIndex
        XCTAssertFalse(scrolls.isEmpty)
        scrolls.last!.swipeUp()
    }
    func reveal(_ control: XCUIElement) {
        for _ in 0..<8 {
            if control.exists && control.isHittable && control.frame.minY >= 100 && control.frame.maxY <= app.frame.maxY - 35 { return }
            scrollUp()
        }
        XCTAssertTrue(control.isHittable, "Unreachable: \(control)")
    }
    func tap(_ label: String) {
        let control = app.buttons[label].firstMatch
        XCTAssertTrue(control.waitForExistence(timeout: 5), label)
        control.tap()
    }
    func assertTabs() {
        for name in ["Today", "Rules", "Insights", "You"] {
            let tab = app.buttons[name].firstMatch
            XCTAssertTrue(tab.isHittable, name)
            XCTAssertGreaterThanOrEqual(tab.frame.width, 44, name)
            XCTAssertGreaterThanOrEqual(tab.frame.height, 44, name)
            XCTAssertTrue(app.frame.contains(tab.frame), name)
        }
    }
    func testLongestDuration() {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-FlintUIPreview", "-flint.onboarded", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
        reveal(app.buttons["Start focus"])
        tap("Start focus")
        tap("90 minutes")
        let more = app.buttons["Five minutes more"]
        for _ in 0..<30 {
            reveal(more)
            more.tap()
        }
        XCTAssertTrue(app.staticTexts["240"].exists)
        XCTAssertTrue(app.buttons["Five minutes less"].isHittable)
        XCTAssertTrue(more.isHittable)
        capture("start-240")
        tap("Five minutes less")
        XCTAssertTrue(app.staticTexts["235"].exists)
        tap("Close")
    }
    func testPermissionErrorLayout() {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-flint.onboarded", "YES", "-FlintUIPreviewAuthorizationError",
                               "Screen Time access is unavailable. Please try again when Screen Time is available on this device."]
        app.launch()
        let action = app.buttons["today.authorization.action"]
        XCTAssertTrue(action.waitForExistence(timeout: 5))
        reveal(action)
        action.tap()
        let error = app.staticTexts["today.authorization.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        reveal(action)
        XCTAssertTrue(action.isEnabled)
        XCTAssertEqual(action.label, "Retry Screen Time")
        XCTAssertLessThanOrEqual(error.frame.maxY, action.frame.minY)
        let explanation = app.staticTexts["today.authorization.explanation"]
        XCTAssertLessThanOrEqual(explanation.frame.maxY, error.frame.minY)
        XCTAssertLessThanOrEqual(action.frame.maxY, app.buttons["Today"].frame.minY)
        capture("permission-error-bottom")
        action.tap()
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertTrue(action.isEnabled)
        app.terminate()
        app.launchArguments = ["-flint.onboarded", "YES"]
        app.launch()
    }
    func testLayouts() {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-FlintUIPreview", "-flint.onboarded", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
        assertTabs()
        capture("today-top")
        reveal(app.buttons["Start focus"])
        capture("today-actions")
        XCTAssertLessThanOrEqual(app.buttons["Start focus"].frame.maxY, app.buttons["Today"].frame.minY)
        let reading = button("Reading")
        XCTAssertTrue(reading.exists)
        XCTAssertGreaterThanOrEqual(reading.frame.height, 44)
        reading.tap()
        XCTAssertTrue(app.buttons["Begin 30 minutes"].waitForExistence(timeout: 5))
        capture("start-top")
        tap("25 minutes")
        tap("Five minutes less")
        let hardcore = button("Hardcore")
        reveal(hardcore)
        capture("start-levels")
        XCTAssertGreaterThanOrEqual(hardcore.frame.height, 44)
        hardcore.tap()
        XCTAssertTrue(hardcore.isSelected)
        let begin = app.buttons["Begin 20 minutes"]
        reveal(begin)
        XCTAssertFalse(begin.isEnabled)
        XCTAssertGreaterThanOrEqual(begin.frame.height, 44)
        capture("start-bottom")
        tap("Close")
        tap("Rules")
        assertTabs()
        capture("rules-top")
        let limits = button("App limits")
        reveal(limits)
        capture("rules-options")
        limits.tap()
        XCTAssertTrue(button("Add a time limit").waitForExistence(timeout: 5))
        tap("You")
        assertTabs()
        capture("you-top")
        let about = app.links.matching(NSPredicate(format: "label CONTAINS %@", "About Flint")).firstMatch
        reveal(about)
        capture("you-bottom")
        tap("Insights")
        XCTAssertTrue(app.staticTexts["Insights needs a device"].waitForExistence(timeout: 5))
        capture("insights-top")
        let weekRange = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "insights.range", "7 days"))
        let todayRange = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "insights.range", "Today"))
        XCTAssertEqual(weekRange.count, 1)
        XCTAssertEqual(todayRange.count, 1)
        weekRange.firstMatch.tap()
        todayRange.firstMatch.tap()
        XCTAssertTrue(todayRange.firstMatch.isSelected)
        XCTAssertTrue(app.staticTexts["Insights needs a device"].exists)
        scrollUp()
        XCTAssertTrue(app.staticTexts["Insights needs a device"].exists)
        capture("insights-bottom")
        app.terminate()
        app.launchArguments = ["-flint.onboarded", "YES"]
        app.launch()
    }
}
