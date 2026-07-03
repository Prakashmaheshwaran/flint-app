import XCTest
import FamilyControls
import FlintCore

/// Pure Open-Limits logic: rule persistence round-trips and the daily open accounting the
/// ShieldAction extension spends against. (The store/DeviceActivity side is device-gated,
/// like every shield.)
final class FlintOpenLimitsTests: XCTestCase {

    // MARK: Rule model + persistence

    func testOpenLimitRuleRoundTripsAndNames() throws {
        let rule = FlintOpenLimitRule(name: "Social", opensAllowed: 3, breakLevel: .harder)
        let data = try JSONEncoder().encode(rule)
        XCTAssertEqual(rule, try JSONDecoder().decode(FlintOpenLimitRule.self, from: data))
        XCTAssertEqual(rule.storeName, "flintO.\(rule.id)")
        XCTAssertEqual(rule.activityName, "flint.openLimit.\(rule.id)")
    }

    func testOpenLimitRulesSaveLoadRoundTrip() throws {
        let suiteName = "flint.tests.openLimitRules"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertEqual(FlintOpenLimitRule.loadAll(from: defaults), [], "empty store → no rules")

        let rules = [
            FlintOpenLimitRule(name: "Social", opensAllowed: 3),
            FlintOpenLimitRule(name: "News", opensAllowed: 1, breakLevel: .hardcore, enabled: false),
        ]
        FlintOpenLimitRule.saveAll(rules, to: defaults)
        XCTAssertEqual(FlintOpenLimitRule.loadAll(from: defaults), rules)
    }

    // MARK: Open accounting (what a shield tap spends)

    func testRequestOpenGrantsUntilCapThenExhausts() {
        var state = FlintOpenLimitState()

        let first = FlintOpenLimit.requestOpen("app", opensAllowed: 2, in: state)
        XCTAssertEqual(first.verdict, .granted(count: 1, remaining: 1))
        state = first.state

        let second = FlintOpenLimit.requestOpen("app", opensAllowed: 2, in: state)
        XCTAssertEqual(second.verdict, .granted(count: 2, remaining: 0))
        state = second.state

        let third = FlintOpenLimit.requestOpen("app", opensAllowed: 2, in: state)
        XCTAssertEqual(third.verdict, .exhausted(count: 2))
        XCTAssertEqual(third.state, state, "an exhausted request must not mutate the state")
    }

    func testRequestOpenNonPositiveCapNeverGrants() {
        let state = FlintOpenLimitState()
        XCTAssertEqual(FlintOpenLimit.requestOpen("app", opensAllowed: 0, in: state).verdict,
                       .exhausted(count: 0))
        XCTAssertEqual(FlintOpenLimit.requestOpen("app", opensAllowed: -1, in: state).verdict,
                       .exhausted(count: 0))
    }

    func testRequestOpenResetsOnNewDay() {
        var state = FlintOpenLimitState()
        state = FlintOpenLimit.requestOpen("app", opensAllowed: 1, in: state).state
        XCTAssertEqual(FlintOpenLimit.requestOpen("app", opensAllowed: 1, in: state).verdict,
                       .exhausted(count: 1), "cap spent today")

        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: Date())!
        XCTAssertEqual(
            FlintOpenLimit.requestOpen("app", opensAllowed: 1, in: state, now: tomorrow).verdict,
            .granted(count: 1, remaining: 0),
            "the day boundary refills the allowance"
        )
    }

    func testRemainingOpensAccounting() {
        var state = FlintOpenLimitState()
        XCTAssertEqual(FlintOpenLimit.remainingOpens("app", opensAllowed: 3, in: state), 3)

        state = FlintOpenLimit.registerOpen("app", in: state).state
        XCTAssertEqual(FlintOpenLimit.remainingOpens("app", opensAllowed: 3, in: state), 2)
        XCTAssertEqual(FlintOpenLimit.remainingOpens("other", opensAllowed: 3, in: state), 3,
                       "counters are per-key")

        state = FlintOpenLimit.registerOpen("app", in: state).state
        state = FlintOpenLimit.registerOpen("app", in: state).state
        XCTAssertEqual(FlintOpenLimit.remainingOpens("app", opensAllowed: 3, in: state), 0)

        // Over-count (e.g. the cap was lowered after opens were spent) still floors at 0.
        state = FlintOpenLimit.registerOpen("app", in: state).state
        XCTAssertEqual(FlintOpenLimit.remainingOpens("app", opensAllowed: 3, in: state), 0)

        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: Date())!
        XCTAssertEqual(FlintOpenLimit.remainingOpens("app", opensAllowed: 3, in: state, now: tomorrow),
                       3, "a stale day reads as a fresh allowance")
    }
}
