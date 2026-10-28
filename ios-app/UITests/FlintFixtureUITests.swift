import XCTest

/// Production navigation with DEBUG authorization fixtures. These tests never grant
/// Screen Time access, select real apps, save schedules, or claim OS enforcement.
final class FlintFixtureUITests: XCTestCase {
    private var app: XCUIApplication!
    private var needsLaunchReadiness = false

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    override func tearDownWithError() throws {
        if let app {
            capture(name)
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
        needsLaunchReadiness = true
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
        try reveal(app.buttons["onboarding.skip"])
        XCTAssertTrue(app.buttons["onboarding.skip"].isEnabled)
        capture("onboarding-consent-error-and-skip")

        // NSArgumentDomain fixes onboarded=false for this process, including after
        // a completion write. Exercise Today in a separate explicit fixture launch;
        // do not claim persisted onboarding completion under that override.
        app.terminate()
        launch(accessibility: accessibility, approved: false)
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
        needsLaunchReadiness = true
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
        let allowList = app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Allow list'")).firstMatch
        let toggle = allowList.buttons.firstMatch
        try reveal(toggle)
        // The production custom toggle exposes its whole row to accessibility,
        // while its pointer hit target is the trailing 52-point switch.
        XCTAssertEqual(allowList.value as? String, "0")
        try tap(toggle)
        XCTAssertEqual(allowList.value as? String, "1")
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
        let unavailable = app.staticTexts["Screen Time needs a device"]
        // Focus history precedes Screen Time; AX5 places this section offscreen.
        try reveal(unavailable)
        XCTAssertFalse(app.staticTexts["No screen time recorded"].exists,
                       "Unavailable data must not be presented as measured zero usage")
        // SwiftUI propagates the identifier onto both segment buttons.
        let range = app.buttons.matching(identifier: "insights.range")
        let today = range.matching(NSPredicate(format: "label == 'Today'")).firstMatch
        try reveal(today, direction: .down)
        try tap(today)
        try reveal(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'reports for today'"))
            .firstMatch)
        let week = range.matching(NSPredicate(format: "label == '7 days'")).firstMatch
        try reveal(week, direction: .down)
        try tap(week)
        try reveal(unavailable)
        capture("insights-approved-simulator-unavailable")

        app.terminate()
        launch(accessibility: accessibility, approved: false)
        try tap(app.buttons["Insights"])
        try reveal(app.staticTexts["Screen Time access needed"])
        XCTAssertFalse(app.staticTexts["No screen time recorded"].exists)
    }

    private enum ScrollDirection { case up, down }

    private func capture(_ state: String) {
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = state
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "accessibility-hierarchy-\(state)"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
    }

    /// Bounded scrolling reveals offscreen content; assertions and taps are never retried.
    private func reveal(_ element: XCUIElement, direction: ScrollDirection = .up,
                        file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertTrue(element.waitForExistence(timeout: 5), file: file, line: line)
        for _ in 0..<8 {
            if element.isHittable { return }
            // A presented editor leaves its underlying tab in the AX tree. Use
            // the frontmost hittable scroll view, not that covered tab's view.
            guard let scroll = app.scrollViews.allElementsBoundByIndex.last(where: { $0.isHittable }) else {
                XCTFail("No visible scroll container for \(element)", file: file, line: line)
                throw FixtureFailure.offscreen
            }
            // SwiftUI can expose the sheet's full frame while the keyboard covers
            // its lower portion. A default swipe then starts on the keyboard and
            // never scrolls the form. Keep both gesture endpoints above it.
            let window = app.frame
            var viewport = scroll.frame.intersection(window)
            let keyboard = app.keyboards.firstMatch
            if keyboard.exists {
                let bottom = min(viewport.maxY, keyboard.frame.minY)
                viewport.size.height = max(0, bottom - viewport.minY)
            }
            guard !viewport.isNull, viewport.height >= 100 else {
                XCTFail("No usable scroll viewport for \(element)", file: file, line: line)
                throw FixtureFailure.offscreen
            }
            let top = viewport.minY + viewport.height * 0.2
            let bottom = viewport.minY + viewport.height * 0.8
            // A center drag on a focused secure field invokes text selection.
            // Stay inside this scroll view's leading padding instead.
            let x = viewport.minX + min(12, viewport.width * 0.1)
            let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            let from = origin.withOffset(CGVector(dx: x - window.minX,
                dy: (direction == .up ? bottom : top) - window.minY))
            let to = origin.withOffset(CGVector(dx: x - window.minX,
                dy: (direction == .up ? top : bottom) - window.minY))
            from.press(forDuration: 0.05, thenDragTo: to)
        }
        XCTFail("Element remained offscreen: \(element)", file: file, line: line)
        throw FixtureFailure.offscreen
    }

    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) throws {
        let ready = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true AND hittable == true AND enabled == true"),
            object: element
        )
        // Cold simulator automation snapshots took 48s in retained CI evidence.
        // Wait once for the same readiness predicate, then send exactly one tap.
        let timeout: TimeInterval = needsLaunchReadiness ? 60 : 8
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: timeout), .completed,
                       "Target must be visible and enabled before input", file: file, line: line)
        needsLaunchReadiness = false
        element.tap()
    }

    private enum FixtureFailure: Error { case offscreen }
}
