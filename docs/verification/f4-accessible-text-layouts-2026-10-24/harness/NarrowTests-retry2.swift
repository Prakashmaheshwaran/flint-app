import XCTest
final class NarrowTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.f4.narrow")
    var viewport: XCUIElement { app.descendants(matching: .any).matching(identifier: "f4.viewport").firstMatch }
    func waitForSettledForeground(_ screen: String) {
        var stableSamples = 0
        for _ in 0..<40 {
            let window = app.windows.firstMatch.frame
            let bounds = viewport.frame
            let control = screen == "start" ? app.buttons["Close"] :
                app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "Today", "insights.range")).allElementsBoundByIndex.last!
            if app.state == .runningForeground && window.width >= 320 && window.height >= 568 &&
                abs(bounds.width - 320) < 1 && abs(bounds.height - 568) < 1 && control.isHittable {
                stableSamples += 1
                if stableSamples == 4 { return }
            } else { stableSamples = 0 }
            Thread.sleep(forTimeInterval: 0.25)
        }
        capture("\(screen)-unsettled-foreground")
        XCTFail("App did not settle in the foreground with a full-sized window and visible control")
    }
    func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F4-NARROW \(name)\n\(app.debugDescription)\nEND-F4-NARROW")
    }
    func scrollUp(_ screen: String) {
        let scroll = app.scrollViews.firstMatch
        let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        let tabTop = screen == "start" ? viewport.frame.maxY :
            app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "Today", "insights.range")).allElementsBoundByIndex.last!.frame.minY
        let top = max(scroll.frame.minY, viewport.frame.minY) + 16
        let bottom = min(scroll.frame.maxY, tabTop) - 16
        XCTAssertGreaterThan(bottom - top, 30)
        origin.withOffset(CGVector(dx: scroll.frame.midX, dy: bottom))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: scroll.frame.midX, dy: top)))
    }
    func fullyVisible(_ element: XCUIElement, screen: String) -> Bool {
        guard element.exists, element.isHittable else { return false }
        let scroll = app.scrollViews.firstMatch.frame
        let tabTop = screen == "start" ? viewport.frame.maxY :
            app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "Today", "insights.range")).allElementsBoundByIndex.last!.frame.minY
        let visible = CGRect(x: viewport.frame.minX, y: max(scroll.minY, viewport.frame.minY),
                             width: viewport.frame.width, height: min(tabTop, viewport.frame.maxY) - max(scroll.minY, viewport.frame.minY))
        return visible.contains(element.frame)
    }
    func reveal(_ element: XCUIElement, screen: String) {
        for _ in 0..<16 {
            if fullyVisible(element, screen: screen) { break }
            scrollUp(screen)
        }
        XCTAssertTrue(fullyVisible(element, screen: screen), "Control must be fully visible above the tab bar")
    }
    func check(_ screen: String, _ size: String) {
        continueAfterFailure = false
        app.launchArguments = ["-FlintUIPreview", "-F4Screen", screen, "-F4TextSize", size]
        app.launch()
        XCTAssertTrue(viewport.waitForExistence(timeout: 5))
        waitForSettledForeground(screen)
        XCTAssertEqual(viewport.frame.width, 320, accuracy: 1)
        XCTAssertEqual(viewport.frame.height, 568, accuracy: 1)
        capture("\(screen)-\(size)-top")
        if screen == "start" {
            let value = app.staticTexts["240"]
            XCTAssertTrue(value.exists)
            let unit = app.staticTexts["min"]
            XCTAssertTrue(unit.exists)
            let durationFrame = value.frame.union(unit.frame)
            let scrollFrame = app.scrollViews.firstMatch.frame
            XCTAssertGreaterThanOrEqual(durationFrame.minX, scrollFrame.minX)
            XCTAssertLessThanOrEqual(durationFrame.maxX, scrollFrame.maxX)
            let less = app.buttons["Five minutes less"]
            let more = app.buttons["Five minutes more"]
            for _ in 0..<8 {
                if less.isHittable && more.isHittable && viewport.frame.contains(less.frame) && viewport.frame.contains(more.frame) { break }
                scrollUp(screen)
            }
            XCTAssertTrue(less.isHittable)
            XCTAssertTrue(more.isHittable)
            XCTAssertTrue(viewport.frame.contains(less.frame))
            XCTAssertTrue(viewport.frame.contains(more.frame))
            XCTAssertGreaterThanOrEqual(less.frame.width, 44)
            XCTAssertGreaterThanOrEqual(less.frame.height, 44)
            capture("\(screen)-\(size)-duration-controls")
            less.tap()
            XCTAssertTrue(app.staticTexts["235"].exists)
            more.tap()
            XCTAssertTrue(app.staticTexts["240"].exists)
        } else {
            for name in ["Today", "Rules", "Insights", "You"] {
                let tabs = app.buttons.matching(NSPredicate(format: "label == %@", name)).allElementsBoundByIndex
                XCTAssertFalse(tabs.isEmpty)
                let tab = tabs.last!
                XCTAssertTrue(tab.isHittable)
                XCTAssertGreaterThanOrEqual(tab.frame.width, 44)
                XCTAssertGreaterThanOrEqual(tab.frame.height, 44)
                XCTAssertTrue(viewport.frame.contains(tab.frame))
            }
        }
        if screen == "rules" {
            for name in ["Sleep,", "App limits,"] {
                let option = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
                reveal(option, screen: screen)
                capture("\(screen)-\(size)-\(name.hasPrefix("Sleep") ? "sleep" : "app-limits")")
            }
        }
        let footer: XCUIElement?
        switch screen {
        case "start": footer = app.buttons["Begin 240 minutes"]
        case "today": footer = app.buttons["Start focus"]
        case "you": footer = app.links.matching(NSPredicate(format: "label CONTAINS %@", "About Flint")).firstMatch
        default: footer = nil
        }
        if let footer { reveal(footer, screen: screen) }
        else if screen == "insights" { for _ in 0..<12 { scrollUp(screen) } }
        capture("\(screen)-\(size)-bottom")
        if screen == "start" { XCTAssertTrue(app.buttons["Begin 240 minutes"].isHittable) }
        if screen == "today" { XCTAssertTrue(app.buttons["Start focus"].isHittable) }
        if screen == "you" { XCTAssertTrue(app.links.matching(NSPredicate(format: "label CONTAINS %@", "About Flint")).firstMatch.isHittable) }
        if screen == "insights" {
            let description = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Simulator can’t provide Screen Time reports for")).firstMatch
            let tab = app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "Today", "insights.range")).allElementsBoundByIndex.last!
            XCTAssertLessThanOrEqual(description.frame.maxY, tab.frame.minY)
        }
        app.terminate()
    }
    func testTodayLarge() { check("today", "large") }
    func testTodayAX5() { check("today", "ax5") }
    func testStartLarge() { check("start", "large") }
    func testStartAX5() { check("start", "ax5") }
    func testRulesLarge() { check("rules", "large") }
    func testRulesAX5() { check("rules", "ax5") }
    func testYouLarge() { check("you", "large") }
    func testYouAX5() { check("you", "ax5") }
    func testInsightsLarge() { check("insights", "large") }
    func testInsightsAX5() { check("insights", "ax5") }
}
