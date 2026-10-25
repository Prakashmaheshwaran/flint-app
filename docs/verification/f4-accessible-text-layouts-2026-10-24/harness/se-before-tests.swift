import XCTest

final class SELayoutTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    func tab(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", name, "insights.range")).allElementsBoundByIndex.last!
    }
    func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F4-SE \(name)\n\(app.debugDescription)\nEND-F4-SE")
    }
    func launch() {
        continueAfterFailure = false
        app.launchArguments = ["-FlintUIPreview", "-flint.onboarded", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 10))
        var stable = 0
        for _ in 0..<40 {
            let bounds = app.windows.firstMatch.frame
            if app.state == .runningForeground && abs(bounds.width - 375) < 1 && abs(bounds.height - 667) < 1 && tab("Today").isHittable {
                stable += 1
                if stable == 4 { break }
            } else { stable = 0 }
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertEqual(stable, 4)
        XCTAssertEqual(app.windows.firstMatch.frame.width, 375, accuracy: 1)
        XCTAssertEqual(app.windows.firstMatch.frame.height, 667, accuracy: 1)
    }
    func tabs(_ selected: String) {
        for name in ["Today", "Rules", "Insights", "You"] {
            let control = tab(name)
            XCTAssertTrue(control.isHittable)
            XCTAssertTrue(app.windows.firstMatch.frame.contains(control.frame))
            XCTAssertGreaterThanOrEqual(control.frame.width, 44)
            XCTAssertGreaterThanOrEqual(control.frame.height, 44)
        }
        XCTAssertTrue(tab(selected).isSelected)
    }
    func visibleRect(tabs: Bool, footer: Bool = false) -> CGRect {
        let window = app.windows.firstMatch.frame
        let scroll = app.scrollViews.allElementsBoundByIndex.last!.frame
        var bottom = tabs ? tab("Today").frame.minY : window.maxY - 8
        if !footer { bottom = min(bottom, scroll.maxY) }
        if !tabs && !footer {
            let begins = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Begin ")).allElementsBoundByIndex
            if let begin = begins.first, begin.frame.maxY <= window.maxY, begin.frame.minY > scroll.minY {
                bottom = min(bottom, begin.frame.minY - 8)
            }
        }
        let top = max(scroll.minY, window.minY + 20)
        return CGRect(x: window.minX, y: top, width: window.width, height: bottom - top)
    }
    func reveal(_ element: XCUIElement, tabs: Bool, footer: Bool = false) {
        for attempt in 0..<24 {
            let visible = visibleRect(tabs: tabs, footer: footer)
            if element.exists && element.isHittable && visible.contains(element.frame) { return }
            let frame = element.frame
            print("F4-SE-REVEAL \(attempt) \(element.label) frame=\(frame) visible=\(visible) hittable=\(element.isHittable)")
            let delta = frame.minY < visible.minY
                ? min(visible.height * 0.4, max(64, visible.minY + 12 - frame.minY))
                : -min(visible.height * 0.4, max(64, frame.maxY - visible.maxY + 12))
            let startY = delta > 0 ? visible.minY + 20 : visible.maxY - 20
            let origin = app.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: visible.minX + 12, dy: startY))
                .press(forDuration: 0.1,
                       thenDragTo: origin.withOffset(CGVector(dx: visible.minX + 12, dy: startY + delta)),
                       withVelocity: XCUIGestureVelocity(rawValue: 200), thenHoldForDuration: 0.3)
        }
        capture("reveal-failure")
        XCTAssertTrue(element.isHittable && visibleRect(tabs: tabs, footer: footer).contains(element.frame), "Whole target must be visible above tabs/footer")
    }
    func testYouAboutAndPrivacyTail() {
        launch()
        tab("You").tap()
        tabs("You")
        capture("you-top")
        let about = app.links.matching(NSPredicate(format: "label CONTAINS %@", "About Flint")).firstMatch
        reveal(about, tabs: true)
        capture("you-about-visible")
        let privacy = app.staticTexts["No account. No analytics. Your lists and history stay on this phone."]
        reveal(privacy, tabs: true)
        XCTAssertLessThanOrEqual(privacy.frame.maxY, tab("Today").frame.minY)
        capture("you-privacy-tail-visible")
        app.terminate()
    }
    func testCompactNavigationAndStart() {
        launch()
        tabs("Today")
        capture("today-top")
        reveal(app.buttons["Start focus"], tabs: true)
        capture("today-actions")
        app.buttons["Start focus"].tap()
        XCTAssertTrue(app.buttons["Close"].waitForExistence(timeout: 5))
        capture("start-top")
        let preset = app.buttons["25 minutes"]
        reveal(preset, tabs: false)
        preset.tap()
        let less = app.buttons["Five minutes less"]
        reveal(less, tabs: false)
        less.tap()
        XCTAssertTrue(app.staticTexts["20"].exists)
        let hardcore = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Hardcore")).firstMatch
        reveal(hardcore, tabs: false)
        hardcore.tap()
        XCTAssertTrue(hardcore.isSelected)
        capture("start-hardcore-selected")
        let begin = app.buttons["Begin 20 minutes"]
        reveal(begin, tabs: false, footer: true)
        XCTAssertFalse(begin.isEnabled)
        capture("start-footer")
        app.buttons["Close"].tap()
        tab("Rules").tap()
        tabs("Rules")
        for prefix in ["Sleep,", "App limits,"] {
            let option = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
            reveal(option, tabs: true)
        }
        capture("rules-options")
        tab("Insights").tap()
        tabs("Insights")
        let range = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "insights.range", "Today")).firstMatch
        XCTAssertTrue(range.waitForExistence(timeout: 5))
        range.tap()
        XCTAssertTrue(range.isSelected)
        capture("insights-top")
        let description = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Simulator can’t provide Screen Time reports for")).firstMatch
        reveal(description, tabs: true)
        capture("insights-tail-visible")
        app.terminate()
    }
}
