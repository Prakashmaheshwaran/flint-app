import XCTest

final class PINLifecycleTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    var currentPIN: String?
    let normalArgs = ["-FlintUIPreview", "-flint.onboarded", "YES"]

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = normalArgs
        app.launch()
        XCTAssertTrue(app.buttons["You"].appears(timeout: 8), "Expected clean synthetic test state")
        tap("You")
        XCTAssertFalse(app.buttons["Remove PIN"].exists)
    }

    override func tearDown() {
        snapshot("99-before-cleanup")
        // Always remove only the synthetic PIN created by this test, even after an assertion.
        if let pin = currentPIN {
            app.terminate()
            app.launchArguments = normalArgs
            app.launch()
            if app.secureTextFields["PIN"].appears(timeout: 5) { unlock(pin) }
            tap("You")
            if app.buttons["Remove PIN"].exists {
                tap("Remove PIN")
                authenticate(pin)
            }
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'App lock' AND label CONTAINS 'Off'")).firstMatch.appears(timeout: 5))
            print("F2-SYNTHETIC-PIN-CLEANUP passed")
        }
        app.terminate()
        super.tearDown()
    }

    func tap(_ name: String) {
        let button = app.buttons[name].firstMatch
        XCTAssertTrue(button.appears(timeout: 5), "Missing \(name)")
        button.tap()
    }
    func appLockButton() -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'App lock'")).firstMatch
    }
    func snapshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F2-SNAPSHOT \(name)\n\(app.debugDescription)\nEND-F2-SNAPSHOT")
    }
    func configure(_ pin: String) {
        appLockButton().tap()
        let field = app.alerts.textFields["PIN"]
        XCTAssertTrue(field.appears(timeout: 5))
        field.tap()
        field.typeText(pin)
        app.alerts.buttons["Save"].tap()
        currentPIN = pin
        XCTAssertTrue(app.buttons["Remove PIN"].appears(timeout: 5))
    }
    func gate() {
        XCTAssertTrue(app.staticTexts["Enter PIN"].appears(timeout: 8))
        XCTAssertTrue(app.buttons["Unlock"].isHittable)
        XCTAssertFalse(app.buttons["You"].canTap)
        XCTAssertFalse(app.buttons["Remove PIN"].canTap)
    }
    func unlock(_ pin: String) {
        let field = app.secureTextFields["PIN"]
        XCTAssertTrue(field.appears(timeout: 5))
        field.tap()
        field.typeText(pin)
        tap("Unlock")
    }
    func authenticate(_ pin: String) {
        let field = app.secureTextFields["current-pin"]
        XCTAssertTrue(field.appears(timeout: 5))
        field.tap()
        field.typeText(pin)
        tap("Continue")
    }
    func backgroundResume() {
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5))
        app.activate()
        gate()
    }
    func testLifecycleAndSensitiveChanges() {
        configure("1234567")
        backgroundResume()
        snapshot("01-resume-after-setup-gated")
        unlock("0000")
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].appears(timeout: 3))
        gate()
        unlock("1234567")
        XCTAssertTrue(app.buttons["Remove PIN"].appears(timeout: 5))
        backgroundResume()
        unlock("1234567")
        XCTAssertTrue(app.buttons["Remove PIN"].appears(timeout: 5))

        appLockButton().tap()
        authenticate("0000")
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].exists)
        XCTAssertFalse(app.alerts.textFields["PIN"].exists)
        snapshot("02-wrong-current-pin-cannot-change")
        tap("Cancel")
        tap("Remove PIN")
        authenticate("0000")
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].exists)
        snapshot("03-wrong-current-pin-cannot-remove")
        tap("Cancel")
        appLockButton().tap()
        authenticate("1234567")
        let newPIN = app.alerts.textFields["PIN"]
        XCTAssertTrue(newPIN.appears(timeout: 5))
        newPIN.tap()
        newPIN.typeText("2468")
        app.alerts.buttons["Save"].tap()
        currentPIN = "2468"
        backgroundResume()
        unlock("1234567")
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].exists)
        unlock("2468")
        XCTAssertTrue(app.buttons["Remove PIN"].appears(timeout: 5))

        app.terminate()
        app.launch()
        gate()
        snapshot("04-cold-launch-gated")
        unlock("2468")
        tap("Rules")
        tap("New schedule")
        XCTAssertTrue(app.buttons["Cancel"].appears(timeout: 5))
        backgroundResume()
        app.swipeDown()
        gate()
        XCTAssertFalse(app.buttons["Cancel"].canTap)
        snapshot("05-gate-above-presented-schedule")
        unlock("2468")
        XCTAssertTrue(app.buttons["Cancel"].appears(timeout: 5))
        tap("Cancel")
        tap("You")
        tap("Remove PIN")
        authenticate("2468")
        XCTAssertTrue(appLockButton().appears(timeout: 5))
        XCTAssertFalse(app.buttons["Remove PIN"].exists)
        snapshot("06-authenticated-removal-off")
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5))
        app.activate()
        XCTAssertTrue(app.buttons["You"].appears(timeout: 5))
        XCTAssertFalse(app.staticTexts["Enter PIN"].exists)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["You"].appears(timeout: 5))
        XCTAssertFalse(app.staticTexts["Enter PIN"].exists)
        currentPIN = nil
        print("F2-LIFECYCLE-CHANGE-REMOVE passed")
    }

    func testTransientControlCenterDoesNotRelock() {
        configure("2468")
        let screen = app.frame
        let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.01))
        let middle = app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.75))
        top.press(forDuration: 0.1, thenDragTo: middle)
        Thread.sleep(forTimeInterval: 1)
        let systemShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        systemShot.name = "09-transient-control-center-privacy"
        systemShot.lifetime = .keepAlways
        add(systemShot)
        print("F2-CONTROL-CENTER state \(app.state.rawValue) frame \(screen)")
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        print(springboard.debugDescription)
        let bottom = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.98))
        let topCenter = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
        bottom.press(forDuration: 0.1, thenDragTo: topCenter)
        XCTAssertTrue(app.buttons["Remove PIN"].appears(timeout: 5))
        XCTAssertFalse(app.staticTexts["Enter PIN"].exists)
        snapshot("10-return-from-inactive-remains-unlocked")
        print("F2-TRANSIENT-CONTROL-CENTER passed")
    }

    func testBackgroundExpiresChangeAndCoversActiveSession() {
        configure("2468")
        appLockButton().tap()
        authenticate("2468")
        XCTAssertTrue(app.alerts.textFields["PIN"].appears(timeout: 5))
        backgroundResume()
        unlock("2468")
        XCTAssertTrue(app.buttons["Remove PIN"].appears(timeout: 5))
        XCTAssertFalse(app.alerts.textFields["PIN"].exists)
        appLockButton().tap()
        XCTAssertTrue(app.secureTextFields["current-pin"].appears(timeout: 5))
        snapshot("07-background-expired-pin-change")
        tap("Cancel")

        app.terminate()
        app.launchArguments = normalArgs + ["-FlintUIPreviewSession", "easy"]
        app.launch()
        gate()
        unlock("2468")
        XCTAssertTrue(app.staticTexts["Breathe with the light."].appears(timeout: 8))
        backgroundResume()
        XCTAssertFalse(app.buttons["End session"].canTap)
        snapshot("08-gate-above-active-session")
        unlock("2468")
        XCTAssertTrue(app.buttons["End session"].appears(timeout: 5))
        tap("End session")
        print("F2-INTERRUPTED-CHANGE-ACTIVE-SESSION passed (UI preview only)")
    }
}

private extension XCUIElement {
    func appears(timeout: TimeInterval) -> Bool { exists || waitForExistence(timeout: timeout) }
    var canTap: Bool { exists && isHittable }
}
