import XCTest
final class NarrowTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "com.flint.f4.narrow")
    var viewport: XCUIElement { app.descendants(matching: .any).matching(identifier: "f4.viewport").firstMatch }
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
    func check(_ screen: String, _ size: String) {
        continueAfterFailure = false
        app.launchArguments = ["-FlintUIPreview", "-F4Screen", screen, "-F4TextSize", size]
        app.launch()
        XCTAssertTrue(viewport.waitForExistence(timeout: 5))
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
            XCTAssertTrue(less.isHittable)
            XCTAssertTrue(more.isHittable)
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
        let footer: XCUIElement?
        switch screen {
        case "start": footer = app.buttons["Begin 240 minutes"]
        case "today": footer = app.buttons["Start focus"]
        case "you": footer = app.links.matching(NSPredicate(format: "label CONTAINS %@", "About Flint")).firstMatch
        default: footer = nil
        }
        for _ in 0..<(footer == nil ? 6 : 12) {
            if let footer, footer.exists && footer.isHittable && viewport.frame.contains(footer.frame) { break }
            scrollUp(screen)
        }
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
