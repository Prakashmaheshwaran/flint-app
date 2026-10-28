import XCTest

/// Production navigation with DEBUG authorization fixtures. These tests never grant
/// Screen Time access, select real apps, save schedules, or claim OS enforcement.
final class FlintFixtureUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    override func tearDownWithError() throws {
        if let app {
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = name
            screenshot.lifetime = .keepAlways
            add(screenshot)
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.name = "accessibility-hierarchy"
            hierarchy.lifetime = .keepAlways
            add(hierarchy)
            app.terminate()
        }
    }

    func testOnboardingAndTodayAtDefaultSize() throws { try onboarding(accessibility: false) }
    func testOnboardingAndTodayAtAccessibilitySize() throws { try onboarding(accessibility: true) }
    func testTodayAndStartFocusAtDefaultSize() throws { try startFocus(accessibility: false) }
    func testTodayAndStartFocusAtAccessibilitySize() throws { try startFocus(accessibility: true) }
    func testPINLockAtDefaultSize() throws { try pinLock(accessibility: false) }
    func testPINLockAtAccessibilitySize() throws { try pinLock(accessibility: true) }
    func testScheduleValidationAtDefaultSize() throws { try scheduleValidation(accessibility: false) }
    func testScheduleValidationAtAccessibilitySize() throws { try scheduleValidation(accessibility: true) }
    func testInsightsUnavailableAtDefaultSize() throws { try insights(accessibility: false) }
    func testInsightsUnavailableAtAccessibilitySize() throws { try insights(accessibility: true) }

    private func launch(accessibility: Bool, approved: Bool = true, onboarded: Bool = true) {
        app = XCUIApplication()
        app.launchArguments = [
            "-flint.onboarded", onboarded ? "YES" : "NO",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName",
            accessibility ? "UICTContentSizeCategoryAccessibilityXXXL" : "UICTContentSizeCategoryL"
        ]
        app.launchArguments += approved ? ["-FlintUIPreview"] : [
            "-FlintUIPreviewAuthorizationScenario", "transient"
        ]
        app.launch()
    }

    private func onboarding(accessibility: Bool) throws {
        launch(accessibility: accessibility, approved: false, onboarded: false)
        try tap(app.buttons["Begin"])
        let allow = app.buttons["onboarding.authorization.action"]
        try reveal(allow)
        try tap(allow)
        let error = app.staticTexts["onboarding.authorization.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertEqual(allow.label, "Retry Screen Time")
        try tap(app.buttons["onboarding.skip"])
        XCTAssertTrue(app.staticTexts["A quiet day so far."].waitForExistence(timeout: 5))
        try reveal(app.buttons["today.authorization.action"])
        XCTAssertTrue(app.staticTexts["today.authorization.explanation"].exists)
        XCTAssertFalse(app.buttons["Start focus"].exists)
    }

    private func startFocus(accessibility: Bool) throws {
        launch(accessibility: accessibility)
        XCTAssertTrue(app.staticTexts["A quiet day so far."].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No schedules yet — add one in Rules."].exists)
        try reveal(app.buttons["Start focus"])
        try tap(app.buttons["Start focus"])
        XCTAssertTrue(app.staticTexts["Start focus"].waitForExistence(timeout: 5))
        try tap(app.buttons["25 minutes"])
        let begin = app.buttons["Begin 25 minutes"]
        try reveal(begin)
        XCTAssertFalse(begin.isEnabled, "An empty selection must never start a session")
        XCTAssertTrue(app.staticTexts["Choose what rests first."].exists)
        try tap(app.buttons["Close"])
        XCTAssertTrue(app.buttons["Start focus"].waitForExistence(timeout: 5))
    }

    private func pinLock(accessibility: Bool) throws {
        launch(accessibility: accessibility)
        try tap(app.buttons["You"])
        let appLock = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'App lock'")).firstMatch
        try reveal(appLock)
        try tap(appLock)
        let newPIN = app.secureTextFields["pin.setup.new"]
        XCTAssertTrue(newPIN.waitForExistence(timeout: 5))
        try tap(newPIN)
        newPIN.typeText("0426") // Synthetic test PIN, never a user credential.
        let confirm = app.secureTextFields["pin.setup.confirm"]
        try reveal(confirm)
        try tap(confirm)
        confirm.typeText("0426")
        try tap(app.buttons["pin.setup.save"])
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Remove PIN'")).firstMatch
            .waitForExistence(timeout: 5))

        app.terminate()
        app.launch()
        XCTAssertTrue(app.staticTexts["Enter PIN"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["You"].exists, "The protected hierarchy must be hidden")
        let gatePIN = app.secureTextFields["PIN"]
        try tap(gatePIN)
        gatePIN.typeText("1111")
        try tap(app.buttons["Unlock"])
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["You"].exists)
        try tap(gatePIN)
        gatePIN.typeText("0426")
        try tap(app.buttons["Unlock"])
        XCTAssertTrue(app.buttons["You"].waitForExistence(timeout: 5))

        // Remove through the production authentication flow so the next case starts clean.
        try tap(app.buttons["You"])
        let remove = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Remove PIN'")).firstMatch
        try reveal(remove)
        try tap(remove)
        let current = app.secureTextFields["current-pin"]
        XCTAssertTrue(current.waitForExistence(timeout: 5))
        try tap(current)
        current.typeText("0426")
        try tap(app.buttons["Continue"])
        XCTAssertTrue(remove.waitForNonExistence(timeout: 5))
    }

    private func scheduleValidation(accessibility: Bool) throws {
        launch(accessibility: accessibility)
        try tap(app.buttons["Rules"])
        try tap(app.buttons["New schedule"])
        let save = app.buttons["Save"]
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        XCTAssertFalse(save.isEnabled, "A block list with no selection cannot save")
        let allowList = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow list'")).firstMatch
        try reveal(allowList)
        try tap(allowList)
        XCTAssertTrue(save.isEnabled, "A valid allow-list draft can be saved without tokens")

        let ends = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Ends '")).firstMatch
        try reveal(ends, direction: .down)
        try tap(ends)
        XCTAssertTrue(app.pickerWheels.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(app.pickerWheels.count, 3, "en_US time picker must expose hour, minute, AM/PM")
        app.pickerWheels.element(boundBy: 0).adjust(toPickerWheelValue: "9")
        app.pickerWheels.element(boundBy: 1).adjust(toPickerWheelValue: "00")
        app.pickerWheels.element(boundBy: 2).adjust(toPickerWheelValue: "AM")
        try tap(app.buttons["Done"])
        XCTAssertTrue(app.staticTexts["Start and end can't be the same time. For all day, use 00:00–23:59."]
            .waitForExistence(timeout: 5))
        XCTAssertFalse(save.isEnabled)

        try tap(ends)
        app.pickerWheels.element(boundBy: 1).adjust(toPickerWheelValue: "10")
        try tap(app.buttons["Done"])
        XCTAssertTrue(app.staticTexts["iOS needs at least 15 minutes between start and end, or the block never arms."]
            .waitForExistence(timeout: 5))
        XCTAssertFalse(save.isEnabled)
        try tap(ends)
        app.pickerWheels.element(boundBy: 1).adjust(toPickerWheelValue: "15")
        try tap(app.buttons["Done"])
        XCTAssertTrue(save.isEnabled, "The exact 15-minute boundary must be valid")
        try tap(app.buttons["Cancel"]) // Never arm a real schedule from this suite.
        XCTAssertTrue(app.buttons["New schedule"].waitForExistence(timeout: 5))
    }

    private func insights(accessibility: Bool) throws {
        launch(accessibility: accessibility)
        try tap(app.buttons["Insights"])
        XCTAssertTrue(app.staticTexts["Insights needs a device"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["No screen time recorded"].exists,
                       "Unavailable data must not be presented as measured zero usage")
        let range = app.descendants(matching: .any)["insights.range"]
        let today = range.buttons["Today"]
        try tap(today)
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'reports for today'"))
            .firstMatch.waitForExistence(timeout: 5))
        try tap(range.buttons["7 days"])
        XCTAssertTrue(app.staticTexts["Insights needs a device"].exists)

        app.terminate()
        launch(accessibility: accessibility, approved: false)
        try tap(app.buttons["Insights"])
        XCTAssertTrue(app.staticTexts["Screen Time access needed"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["No screen time recorded"].exists)
    }

    private enum ScrollDirection { case up, down }

    /// Bounded scrolling reveals offscreen content; assertions and taps are never retried.
    private func reveal(_ element: XCUIElement, direction: ScrollDirection = .up,
                        file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertTrue(element.waitForExistence(timeout: 5), file: file, line: line)
        for _ in 0..<8 {
            if element.isHittable { return }
            let scroll = app.scrollViews.firstMatch
            XCTAssertTrue(scroll.exists, "No scroll container for \(element)", file: file, line: line)
            if direction == .up { scroll.swipeUp() } else { scroll.swipeDown() }
        }
        XCTFail("Element remained offscreen: \(element)", file: file, line: line)
        throw FixtureFailure.offscreen
    }

    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertTrue(element.waitForExistence(timeout: 5), file: file, line: line)
        XCTAssertTrue(element.isHittable, "Target must be visible before input", file: file, line: line)
        XCTAssertTrue(element.isEnabled, file: file, line: line)
        element.tap()
    }

    private enum FixtureFailure: Error { case offscreen }
}
