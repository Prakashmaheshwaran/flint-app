import XCTest

final class PINFormPhaseUITests: XCTestCase {
    private let app = XCUIApplication()
    private var save: XCUIElement { app.buttons["pin.setup.save"] }
    private var newPIN: XCUIElement { app.secureTextFields["pin.setup.new"] }
    private var confirmation: XCUIElement { app.secureTextFields["pin.setup.confirm"] }

    override func setUp() {
        continueAfterFailure = false
        app.launch()
    }

    override func tearDown() {
        if (testRun?.totalFailureCount ?? 0) > 0 { snapshot("phase-test-failure") }
        if app.buttons["fixture.cleanup"].exists { app.buttons["fixture.cleanup"].tap() }
        app.terminate()
    }

    private func replace(_ field: XCUIElement, with text: String) {
        field.tap()
        let current = field.value as? String ?? ""
        if current != "New PIN" && current != "Confirm PIN" {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(text)
    }

    private func expectSave(_ enabled: Bool) {
        let condition = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == %@", NSNumber(value: enabled)), object: save)
        XCTAssertEqual(XCTWaiter.wait(for: [condition], timeout: 3), .completed)
    }

    private func expectStatus(_ fragment: String) {
        let status = app.staticTexts["fixture.status"]
        let condition = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", fragment), object: status)
        XCTAssertEqual(XCTWaiter.wait(for: [condition], timeout: 3), .completed)
    }

    private func expectBlocked(_ state: String) {
        expectStatus(state)
        expectSave(false)
        save.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        expectStatus("attempts=0")
        XCTAssertTrue(newPIN.exists)
        XCTAssertFalse(app.staticTexts["Couldn’t save your PIN. Please try again."].exists)
    }

    private func snapshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F3-REVIEW-SNAPSHOT \(name)\n\(app.debugDescription)\nEND-F3-REVIEW-SNAPSHOT")
    }

    func testSaveTracksLivePhaseAndLockBeforeInvokingPersistence() {
        XCTAssertTrue(newPIN.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.exists)
        XCTAssertFalse(app.textFields["pin.setup.new"].exists)
        XCTAssertFalse(app.textFields["pin.setup.confirm"].exists)
        expectSave(false)
        replace(newPIN, with: "1234567")
        replace(confirmation, with: "1234567")
        expectBlocked("phase=active; locked=false")
        replace(newPIN, with: "012345")
        expectSave(false)
        replace(confirmation, with: "012345")
        expectSave(true)
        XCTAssertNotEqual(newPIN.value as? String, "012345")
        XCTAssertNotEqual(confirmation.value as? String, "012345")

        app.buttons["fixture.inactive"].tap()
        expectBlocked("phase=inactive; locked=false")
        snapshot("f3-review-inactive-save-disabled")
        app.buttons["fixture.active"].tap()
        expectSave(true)
        app.buttons["fixture.background"].tap()
        expectBlocked("phase=background; locked=false")
        app.buttons["fixture.active"].tap()
        expectSave(true)

        app.buttons["fixture.lock"].tap()
        expectBlocked("phase=active; locked=true")
        snapshot("f3-review-locked-save-disabled")
        app.buttons["fixture.authorize"].tap()
        expectStatus("result=authorized")
        expectSave(true)
        snapshot("f3-review-authorized-save-enabled")
        save.tap()
        expectStatus("attempts=1; result=saved; pin=true")
        app.buttons["fixture.cleanup"].tap()
        expectStatus("result=clean; pin=false")
        snapshot("f3-review-isolated-store-clean")
        print("F3-REVIEW-PASS invalid/mismatched entries rejected; inactive/background/locked valid entries disable Save without persistence or generic error; active authorization restores Save; synthetic record removed")
    }
}
