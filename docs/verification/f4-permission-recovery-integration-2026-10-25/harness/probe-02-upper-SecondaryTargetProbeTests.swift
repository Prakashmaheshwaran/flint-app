import XCTest

/// Production app with F5's explicit view-state fixtures; never changes system authorization.
final class SecondaryTargetProbeTests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.flint.peakfocus")
    private let message = "Screen Time could not finish this request.\nThe connection ended before access was enabled. Please try again."

    override func tearDown() {
        app.terminate()
        super.tearDown()
    }

    private func tab(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", name, "insights.range")).allElementsBoundByIndex.last!
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("F4-REVIEW \(name)\n\(app.debugDescription)\nEND-F4-REVIEW")
    }

    private func settled() {
        var stable = 0
        for _ in 0..<40 {
            let bounds = app.windows.firstMatch.frame
            if app.state == .runningForeground && abs(bounds.width - 402) < 1 && abs(bounds.height - 874) < 1 && tab("Today").isHittable {
                stable += 1
                if stable == 4 { return }
            } else { stable = 0 }
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTFail("Production app did not settle in the authorized 402×874pt guest")
    }

    private func assertTabs(_ selected: String) {
        for name in ["Today", "Rules", "Insights", "You"] {
            let control = tab(name)
            XCTAssertTrue(control.isHittable)
            XCTAssertTrue(app.windows.firstMatch.frame.contains(control.frame))
            XCTAssertGreaterThanOrEqual(control.frame.width, 44)
            XCTAssertGreaterThanOrEqual(control.frame.height, 44)
        }
        XCTAssertTrue(tab(selected).isSelected)
    }

    private func launch(_ scenario: String, screen: String) {
        continueAfterFailure = false
        app.launchArguments = ["-flint.onboarded", "YES", "-FlintUIPreviewAuthorizationScenario", scenario]
        if scenario == "transient" { app.launchArguments += ["-FlintUIPreviewAuthorizationError", message] }
        app.launch()
        XCTAssertTrue(app.buttons["Today"].waitForExistence(timeout: 10))
        settled()
        if screen != "Today" { tab(screen).tap() }
        assertTabs(screen)
        if screen == "Insights" {
            let today = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "insights.range", "Today")).firstMatch
            XCTAssertTrue(today.waitForExistence(timeout: 5))
            XCTAssertTrue(today.isHittable)
            today.tap()
            XCTAssertTrue(today.isSelected)
        }
        capture("\(screen.lowercased())-\(scenario)-top")
    }

    private func visibleRect() -> CGRect {
        let window = app.windows.firstMatch.frame
        let scroll = app.scrollViews.firstMatch.frame
        let top = max(scroll.minY, window.minY + 20)
        let bottom = min(scroll.maxY, tab("Today").frame.minY)
        let left = max(window.minX, scroll.minX)
        let right = min(window.maxX, scroll.maxX)
        return CGRect(x: left, y: top, width: right - left, height: bottom - top)
    }

    private func drag(_ delta: CGFloat) {
        let gesture = visibleRect().intersection(app.scrollViews.firstMatch.frame)
        XCTAssertGreaterThan(gesture.height, 40)
        let startY = delta > 0 ? gesture.minY + 20 : gesture.maxY - 20
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: gesture.minX + 12, dy: startY))
            .press(forDuration: 0.1,
                   thenDragTo: origin.withOffset(CGVector(dx: gesture.minX + 12, dy: startY + delta)),
                   withVelocity: XCUIGestureVelocity(rawValue: 200), thenHoldForDuration: 0.3)
    }

    private func reveal(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        for _ in 0..<24 {
            let visible = visibleRect()
            if element.isHittable && visible.insetBy(dx: -0.01, dy: -0.01).contains(element.frame) {
                XCTAssertLessThanOrEqual(element.frame.maxY, tab("Today").frame.minY + 0.01)
                return
            }
            let frame = element.frame
            let delta = frame.minY < visible.minY
                ? min(visible.height * 0.4, max(64, visible.minY + 12 - frame.minY))
                : -min(visible.height * 0.4, max(64, frame.maxY - visible.maxY + 12))
            drag(delta)
        }
        capture("whole-control-failure")
        XCTFail("Whole target must be hittable inside the actual scroll viewport above tabs: \(element.identifier)")
    }

    private func revealProse(_ element: XCUIElement, name: String) {
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        if element.frame.height <= visibleRect().height {
            reveal(element)
            capture(name)
            return
        }
        // Long prose may exceed the viewport. Capture its first line, overlapping scroll
        // positions, and final line while enforcing horizontal fit throughout.
        for _ in 0..<24 {
            let visible = visibleRect()
            if element.frame.minY >= visible.minY - 0.01 && element.frame.minY + 40 <= visible.maxY { break }
            let delta = element.frame.minY < visible.minY
                ? min(visible.height * 0.4, max(64, visible.minY + 12 - element.frame.minY))
                : -min(visible.height * 0.4, max(64, element.frame.minY + 40 - visible.maxY))
            drag(delta)
        }
        XCTAssertGreaterThanOrEqual(element.frame.minY, visibleRect().minY - 0.01)
        XCTAssertLessThanOrEqual(element.frame.minY + 40, visibleRect().maxY)
        capture("\(name)-first-line")
        for attempt in 0..<24 {
            let visible = visibleRect()
            XCTAssertGreaterThanOrEqual(element.frame.minX, visible.minX - 0.01)
            XCTAssertLessThanOrEqual(element.frame.maxX, visible.maxX + 0.01)
            if element.frame.maxY <= visible.maxY + 0.01 && element.frame.maxY > visible.minY { break }
            drag(-min(visible.height * 0.4, max(64, element.frame.maxY - visible.maxY + 12)))
            capture("\(name)-scroll-\(attempt)")
        }
        XCTAssertLessThanOrEqual(element.frame.maxY, visibleRect().maxY + 0.01)
        XCTAssertGreaterThan(element.frame.maxY, visibleRect().minY)
        capture("\(name)-last-line")
    }

    private func action(_ prefix: String, label: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "\(prefix).action", label)).firstMatch
    }

    private func denied(_ screen: String) {
        launch("denied", screen: screen)
        let prefix = "\(screen.lowercased()).authorization"
        let guidance = app.staticTexts["\(prefix).guidance"]
        XCTAssertTrue(guidance.label.contains("Apps with Screen Time Access"))
        XCTAssertTrue(guidance.label.contains("Enable Flint if it’s listed"))
        XCTAssertFalse(app.buttons["Allow Screen Time"].exists)
        XCTAssertFalse(app.buttons["Retry Screen Time"].exists)
        XCTAssertFalse(app.buttons["Try again"].exists)
        revealProse(guidance, name: "\(screen.lowercased())-denied-guidance")
        let settingsAction = action(prefix, label: "Open Settings")
        reveal(settingsAction)
        XCTAssertGreaterThanOrEqual(settingsAction.frame.height, 44)
        capture("\(screen.lowercased())-settings-whole-control")
        settingsAction.tap()
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 5))
        XCTAssertTrue(settings.navigationBars.firstMatch.waitForExistence(timeout: 5))
        print("F4-REVIEW Settings destination navigation title: \(settings.navigationBars.firstMatch.identifier)")
        // Observe the destination only. Do not touch Settings controls or system permissions.
        app.activate()
        settled()
        assertTabs(screen)
        let check = app.buttons["\(prefix).check"]
        XCTAssertEqual(app.buttons.matching(identifier: "\(prefix).check").count, 1)
        reveal(check)
        XCTAssertGreaterThanOrEqual(check.frame.height, 44)
        capture("\(screen.lowercased())-recheck-whole-control")
        check.tap()
        XCTAssertTrue(settingsAction.exists)
        XCTAssertTrue(guidance.exists)
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
        XCTAssertFalse(app.buttons["Retry Screen Time"].exists)
        reveal(settingsAction)
        capture("\(screen.lowercased())-denied-after-recheck")
    }

    private func transient(_ screen: String) {
        launch("transient", screen: screen)
        let prefix = "\(screen.lowercased()).authorization"
        let allow = action(prefix, label: "Allow Screen Time")
        reveal(allow)
        capture("\(screen.lowercased())-allow-whole-control")
        allow.tap()
        let retry = action(prefix, label: "Retry Screen Time")
        XCTAssertTrue(retry.waitForExistence(timeout: 5))
        let error = app.staticTexts["\(prefix).error"]
        XCTAssertEqual(error.label, message)
        XCTAssertFalse(app.staticTexts["\(prefix).guidance"].exists)
        revealProse(error, name: "\(screen.lowercased())-complete-transient-feedback")
        reveal(retry)
        XCTAssertTrue(retry.isEnabled)
        XCTAssertGreaterThanOrEqual(retry.frame.height, 44)
        capture("\(screen.lowercased())-retry-whole-control")
        retry.tap()
        if screen == "Today" {
            let start = app.buttons["Start focus"]
            XCTAssertTrue(start.waitForExistence(timeout: 5))
            reveal(start)
        } else {
            XCTAssertTrue(app.staticTexts["Insights needs a device"].waitForExistence(timeout: 5))
            let range = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@", "insights.range", "Today")).firstMatch
            XCTAssertTrue(range.isSelected)
        }
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
        XCTAssertFalse(app.buttons["\(prefix).action"].exists)
        assertTabs(screen)
        capture("\(screen.lowercased())-recovered")
    }

    func testTodayDeniedGuidanceSettingsAndRecheck() { denied("Today") }
    func testInsightsDeniedGuidanceSettingsAndRecheck() { denied("Insights") }
    func testTodayTransientFeedbackAndRetry() { transient("Today") }
    func testInsightsTransientFeedbackAndRetry() { transient("Insights") }

    func testStopWaitingTargetProbe() {
        launch("hung", screen: "Today")
        let prefix = "today.authorization"
        let allow = action(prefix, label: "Allow Screen Time")
        reveal(allow)
        allow.tap()
        let waiting = action(prefix, label: "Requesting access…")
        XCTAssertTrue(waiting.waitForExistence(timeout: 5))
        XCTAssertFalse(waiting.isEnabled)
        let stop = app.buttons["\(prefix).cancel"]
        reveal(stop)
        var intendedTarget = CGRect(x: stop.frame.minX, y: stop.frame.midY - 22,
                                    width: stop.frame.width, height: 44)
        if !visibleRect().contains(intendedTarget) {
            drag(intendedTarget.minY < visibleRect().minY ? 64 : -64)
            intendedTarget = CGRect(x: stop.frame.minX, y: stop.frame.midY - 22,
                                    width: stop.frame.width, height: 44)
        }
        XCTAssertTrue(visibleRect().contains(intendedTarget))
        let measured = stop.frame
        let lower = CGPoint(x: measured.midX, y: measured.midY + 21)
        let upper = CGPoint(x: measured.midX, y: measured.midY - 21)
        XCTAssertFalse(measured.contains(lower))
        XCTAssertFalse(measured.contains(upper))
        capture("stop-waiting-before-off-label-probes")
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: lower.x, dy: lower.y)).tap()
        let lowerActivated = allow.waitForExistence(timeout: 1)
        var upperActivated: Bool? = nil
        var centerActivated: Bool? = nil
        if !lowerActivated {
            XCTAssertTrue(waiting.exists)
            origin.withOffset(CGVector(dx: upper.x, dy: upper.y)).tap()
            upperActivated = allow.waitForExistence(timeout: 1)
        }
        if !lowerActivated && upperActivated != true {
            XCTAssertTrue(waiting.exists)
            stop.tap()
            centerActivated = allow.waitForExistence(timeout: 5)
            XCTAssertEqual(centerActivated, true)
        }
        XCTAssertTrue(allow.isEnabled)
        XCTAssertFalse(stop.exists)
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
        let record: [String: Any] = [
            "axFrame": [measured.minX, measured.minY, measured.width, measured.height],
            "intended44ptLayoutArea": [intendedTarget.minX, intendedTarget.minY, intendedTarget.width, intendedTarget.height],
            "lowerProbePoint": [lower.x, lower.y], "upperProbePoint": [upper.x, upper.y],
            "lowerOffLabelActivated": lowerActivated,
            "upperOffLabelActivated": upperActivated.map { $0 as Any } ?? NSNull(),
            "centerActivated": centerActivated.map { $0 as Any } ?? NSNull()
        ]
        let data = try! JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
        print("F4-SECONDARY-PROBE \(String(data: data, encoding: .utf8)!)")
        capture("stop-waiting-after-off-label-probes")
        // Keep the original strict AX-target size requirement after recording touch observations.
        XCTAssertGreaterThanOrEqual(measured.height, 44)
    }

    func testStopWaitingUpperTargetProbe() {
        launch("hung", screen: "Today")
        let prefix = "today.authorization"
        let allow = action(prefix, label: "Allow Screen Time")
        reveal(allow)
        allow.tap()
        let waiting = action(prefix, label: "Requesting access…")
        XCTAssertTrue(waiting.waitForExistence(timeout: 5))
        let stop = app.buttons["\(prefix).cancel"]
        reveal(stop)
        let measured = stop.frame
        let intendedTarget = CGRect(x: measured.minX, y: measured.midY - 22,
                                    width: measured.width, height: 44)
        XCTAssertTrue(visibleRect().contains(intendedTarget))
        let upper = CGPoint(x: measured.midX, y: measured.midY - 21)
        XCTAssertFalse(measured.contains(upper))
        capture("stop-waiting-before-upper-edge-probe")
        app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: upper.x, dy: upper.y)).tap()
        let activated = allow.waitForExistence(timeout: 1)
        var centerActivated: Bool? = nil
        if !activated {
            XCTAssertTrue(waiting.exists)
            stop.tap()
            centerActivated = allow.waitForExistence(timeout: 5)
            XCTAssertEqual(centerActivated, true)
        }
        XCTAssertTrue(allow.isEnabled)
        XCTAssertFalse(stop.exists)
        XCTAssertFalse(app.staticTexts["\(prefix).error"].exists)
        let record: [String: Any] = [
            "axFrame": [measured.minX, measured.minY, measured.width, measured.height],
            "intended44ptLayoutArea": [intendedTarget.minX, intendedTarget.minY, intendedTarget.width, intendedTarget.height],
            "upperProbePoint": [upper.x, upper.y], "upperOffLabelActivated": activated,
            "centerActivated": centerActivated.map { $0 as Any } ?? NSNull()
        ]
        let data = try! JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
        print("F4-SECONDARY-UPPER-PROBE \(String(data: data, encoding: .utf8)!)")
        capture("stop-waiting-after-upper-edge-probe")
        XCTAssertGreaterThanOrEqual(measured.height, 44)
    }
}
