import XCTest
import UIKit

final class FlintPINSetupUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    private var savedTestPIN: String?
    private var newPIN: XCUIElement { app.secureTextFields["pin.setup.new"] }
    private var confirmation: XCUIElement { app.secureTextFields["pin.setup.confirm"] }
    private var save: XCUIElement { app.buttons["pin.setup.save"] }

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        if (testRun?.totalFailureCount ?? 0) > 0 { snapshot("f3-test-failure") }
        if let pin = savedTestPIN {
            launch()
            let gate = app.secureTextFields["PIN"]
            var activePIN = pin
            if gate.waitForExistence(timeout: 5) {
                // A failed replacement may leave the previous synthetic PIN in place.
                for candidate in [pin, "2468", "13579", "0123", "01234", "012345"] {
                    gate.tap()
                    gate.typeText(candidate)
                    tap("Unlock")
                    if !gate.exists {
                        activePIN = candidate
                        break
                    }
                }
            }
            tap("You")
            if app.buttons["Remove PIN"].exists { removePIN(activePIN) }
            savedTestPIN = nil
            print("F3-CLEANUP removed synthetic PIN after interrupted test")
        }
        app.terminate()
    }

    private func launch() {
        app.terminate()
        app.launchArguments = ["-flint.onboarded", "YES"]
        app.launch()
    }

    private func tap(_ label: String) {
        let button = app.buttons[label].firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        button.tap()
    }

    private func tapContaining(_ label: String) {
        let button = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        button.tap()
    }

    private func openSetup() {
        tapContaining("App lock")
        XCTAssertTrue(newPIN.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.exists)
        XCTAssertFalse(app.textFields["pin.setup.new"].exists)
        XCTAssertFalse(app.textFields["pin.setup.confirm"].exists)
    }

    private func authenticate(_ pin: String) {
        let field = app.secureTextFields["current-pin"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(pin)
        tap("Continue")
    }

    private func removePIN(_ pin: String) {
        tap("Remove PIN")
        authenticate(pin)
        let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                                                object: app.buttons["Remove PIN"])
        XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 5), .completed)
    }

    private func unlock(_ pin: String) {
        let field = app.secureTextFields["PIN"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(pin)
        tap("Unlock")
    }

    private func backgroundAndResume() {
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5))
        app.activate()
        XCTAssertTrue(app.staticTexts["Enter PIN"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Remove PIN"].isHittable)
    }

    private func replace(_ field: XCUIElement, with text: String) {
        field.tap()
        let value = field.value as? String ?? ""
        if value != "New PIN" && value != "Confirm PIN" && !value.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count))
        }
        if !text.isEmpty { field.typeText(text) }
    }

    private func snapshot(_ name: String) {
        Thread.sleep(forTimeInterval: 1)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F3-SNAPSHOT \(name)\n\(app.debugDescription)\nEND-F3-SNAPSHOT")
    }

    private func expectRejected(_ message: String? = nil) {
        XCTAssertFalse(save.isEnabled)
        if let message { XCTAssertTrue(app.staticTexts[message].exists) }
        // A center tap on the disabled action must leave the sheet in place.
        save.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(newPIN.exists)
    }

    func testAInvalidInputRemainsOpenAndDoesNotSave() {
        launch()
        tap("You")
        XCTAssertFalse(app.buttons["Remove PIN"].exists)
        openSetup()
        expectRejected()

        for pin in ["1", "12", "123", "1234567", "12345678"] {
            replace(newPIN, with: pin)
            replace(confirmation, with: pin)
            expectRejected("Use 4–6 digits (0–9), with no spaces.")
            XCTAssertNotEqual(newPIN.value as? String, pin)
            if pin.count == 7 { snapshot("f3-seven-digits-rejected-secure") }
        }

        tap("Cancel")
        XCTAssertFalse(app.buttons["Remove PIN"].exists)
        launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Enter PIN"].exists)
        print("F3-PASS invalid blank, short and overlong input cannot save; cold launch remains unlocked")
    }

    func testBPastedMalformedPINIsNotNormalized() {
        launch()
        tap("You")
        let originalClipboard = UIPasteboard.general.items
        defer { UIPasteboard.general.items = originalClipboard }
        // Real clipboard paste is required: XCTest number-pad typing cannot insert letters.
        for pin in ["abcd", "12 34", "12a34 ", "１２３４"] {
            openSetup()
            UIPasteboard.general.string = pin
            newPIN.press(forDuration: 1.2)
            let paste = app.menuItems["Paste"].firstMatch
            if paste.waitForExistence(timeout: 2) {
                paste.tap()
            } else {
                tap("Paste")
            }
            let allowPaste = app.buttons["Allow Paste"]
            if allowPaste.waitForExistence(timeout: 1) { allowPaste.tap() }
            expectRejected("Use 4–6 digits (0–9), with no spaces.")
            XCTAssertEqual((newPIN.value as? String)?.count, pin.count,
                           "The pasted value must not be filtered or trimmed")
            if pin == "12a34 " { snapshot("f3-invalid-paste-rejected") }
            tap("Cancel")
            XCTAssertFalse(app.buttons["Remove PIN"].exists)
        }
        print("F3-PASS clipboard letters, spaces, mixed input and Unicode digits rejected without normalization")
    }

    func testCConfirmationAndValidLengthsSaveAndUnlock() {
        launch()
        tap("You")
        for pin in ["0123", "01234", "012345"] {
            openSetup()
            replace(newPIN, with: pin)
            expectRejected()
            replace(confirmation, with: "9999")
            expectRejected("PINs don’t match. Enter the same PIN twice.")
            if pin.count == 4 { snapshot("f3-confirmation-mismatch") }
            replace(confirmation, with: pin)
            XCTAssertTrue(save.isEnabled)
            XCTAssertNotEqual(newPIN.value as? String, pin)
            XCTAssertNotEqual(confirmation.value as? String, pin)
            snapshot("f3-valid-\(pin.count)-digits-obscured")
            savedTestPIN = pin
            save.tap()
            XCTAssertTrue(app.buttons["Remove PIN"].waitForExistence(timeout: 5))
            snapshot("f3-valid-\(pin.count)-digits-saved")
            launch()
            let gate = app.secureTextFields["PIN"]
            XCTAssertTrue(gate.waitForExistence(timeout: 5))
            gate.tap()
            gate.typeText("9999")
            tap("Unlock")
            XCTAssertTrue(app.staticTexts["Incorrect PIN"].exists)
            gate.tap()
            gate.typeText(pin)
            tap("Unlock")
            XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
            tap("You")
            snapshot("f3-valid-\(pin.count)-digits-unlocked")
            removePIN(pin)
            XCTAssertFalse(app.buttons["Remove PIN"].exists)
            savedTestPIN = nil
            print("F3-PASS valid \(pin.count)-digit PIN saves, rejects incorrect PIN, unlocks on cold launch, and is removed")
        }
        launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 5))
        tap("You")
        XCTAssertFalse(app.buttons["Remove PIN"].exists)
        snapshot("f3-cleanup-pin-off")
    }

    func testDSecureSetupIntegratesWithLifecycleAndAuthenticatedChanges() {
        launch()
        tap("You")
        openSetup()
        replace(newPIN, with: "2468")
        replace(confirmation, with: "2468")
        savedTestPIN = "2468"
        save.tap()
        XCTAssertTrue(app.buttons["Remove PIN"].waitForExistence(timeout: 5))

        backgroundAndResume()
        snapshot("f3-combined-setup-background-locked")
        unlock("2468")
        XCTAssertTrue(app.buttons["Remove PIN"].waitForExistence(timeout: 5))

        tapContaining("App lock")
        authenticate("0000")
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].exists)
        XCTAssertFalse(newPIN.exists)
        authenticate("2468")
        XCTAssertTrue(newPIN.waitForExistence(timeout: 5))
        replace(newPIN, with: "1234567")
        expectRejected("Use 4–6 digits (0–9), with no spaces.")
        tap("Cancel")
        tapContaining("App lock")
        XCTAssertTrue(app.secureTextFields["current-pin"].waitForExistence(timeout: 5))
        authenticate("2468")
        XCTAssertTrue(newPIN.waitForExistence(timeout: 5))
        replace(newPIN, with: "13579")
        replace(confirmation, with: "13579")
        backgroundAndResume()
        unlock("2468")
        XCTAssertFalse(newPIN.exists, "Background must discard the authorized setup sheet")
        tapContaining("App lock")
        XCTAssertTrue(app.secureTextFields["current-pin"].waitForExistence(timeout: 5),
                      "Unlocking after background must not reuse prior change authorization")
        authenticate("2468")
        XCTAssertTrue(newPIN.waitForExistence(timeout: 5))
        replace(newPIN, with: "13579")
        replace(confirmation, with: "13579")
        XCTAssertTrue(save.isEnabled)
        snapshot("f3-combined-authenticated-change-secure")
        savedTestPIN = "13579"
        save.tap()
        XCTAssertTrue(app.buttons["Remove PIN"].waitForExistence(timeout: 5))
        backgroundAndResume()
        unlock("2468")
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].exists)
        unlock("13579")
        tap("Remove PIN")
        authenticate("2468")
        XCTAssertTrue(app.staticTexts["Incorrect PIN"].exists)
        snapshot("f3-combined-removal-requires-current-pin")
        authenticate("13579")
        let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                                                object: app.buttons["Remove PIN"])
        XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 5), .completed)
        savedTestPIN = nil
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.buttons["You"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Enter PIN"].exists)
        snapshot("f3-combined-cleanup-pin-off")
        print("F3-PASS secure setup relocks on background; change/removal require current PIN; cancelled/backgrounded setup loses authorization")
    }
}
