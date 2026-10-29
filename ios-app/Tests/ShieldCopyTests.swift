import XCTest
import FlintCore

final class ShieldCopyTests: XCTestCase {
    private typealias Builder = FlintProtectionStatusBuilder
    private let now = Date(timeIntervalSince1970: 1_793_185_200) // 2026-10-28 11:00 UTC
    private let english = Locale(identifier: "en_GB")
    private let utc = TimeZone(secondsFromGMT: 0)!
    private let neutral = "Notice the reach, then let it pass."

    private func rule(_ id: String, name: String, tier: BreakLevel = .easy,
                      end: TimeInterval = 3600, enforcing: Bool = true) -> Builder.RuleInput {
        .init(id: id, kind: .recurringSchedule, displayName: name, tier: tier,
              armed: true, observed: true,
              currentWindow: DateInterval(start: now.addingTimeInterval(-60),
                                          end: now.addingTimeInterval(end)),
              enforcing: enforcing)
    }

    private func contributors(_ rules: [Builder.RuleInput] = [],
                              ongoingTier: BreakLevel? = nil, uncertain: Bool = false,
                              available: Bool = true) -> FlintProtectionStatus.Contributors {
        let session = ongoingTier.map {
            Builder.SessionInput(.init(id: "ongoing", name: "Quiet time",
                startedAt: now.addingTimeInterval(-60), endsAt: nil, breakLevel: $0,
                monitorName: "fixture-monitor"), armed: true)
        }
        let inputs = Builder.Inputs(now: now, activeSession: session, rules: rules,
            authorized: true, pass: .init(eligibility: .available),
            timeIntegrity: .consistent, available: available)
        var coverage = Dictionary(uniqueKeysWithValues: rules.map {
            ($0.id, FlintProtectionStatus.TargetScope.only(["fixture-target"]))
        })
        if session != nil { coverage["ongoing"] = .only(["fixture-target"]) }
        if uncertain, let last = rules.last { coverage[last.id] = .unknown }
        return FlintProtectionStatus.contributors(for: "fixture-target", inputs: inputs,
                                                  coverageByCauseID: coverage)
    }

    private func copy(_ contributors: FlintProtectionStatus.Contributors? = nil,
                      name: String? = "Instagram", unit: ShieldCopy.Unit = .app,
                      remaining: Int? = nil, storeAvailable: Bool = true,
                      at date: Date? = nil) -> ShieldCopy {
        ShieldCopy(name: name, unit: unit, contributors: contributors, now: date ?? now,
                   remainingOpens: remaining, openStoreAvailable: storeAvailable,
                   locale: english, timeZone: utc)
    }

    func testSingleEnforcingCauseIncludesItsEnd() {
        let result = copy(contributors([rule("focus", name: "Laser Focus", tier: .harder)]))
        XCTAssertEqual(result.title, "Instagram is resting.")
        XCTAssertEqual(result.subtitle, "Laser Focus, until 12:00. Notice the reach, then let it pass.")
        XCTAssertEqual(result.primaryButtonLabel, "Go back")
        XCTAssertNil(result.secondaryButtonLabel)
    }

    func testOngoingEasySaysUntilYouStop() {
        XCTAssertEqual(copy(contributors(ongoingTier: .easy)).subtitle,
                       "Quiet time, until you stop. Notice the reach, then let it pass.")
    }

    func testStrongestTierNamesTheCauseButUntilUsesTheLastContributor() {
        let result = copy(contributors([
            rule("easy", name: "Longer quiet time", end: 7200),
            rule("strict", name: "Laser Focus", tier: .hardcore, end: 1800)
        ]))
        XCTAssertEqual(result.subtitle, "Laser Focus, until 13:00. Notice the reach, then let it pass.")
    }

    func testEqualTierChoosesLatestEndForTheHeadline() {
        let result = copy(contributors([
            rule("early", name: "Early", tier: .harder, end: 1800),
            rule("late", name: "Later", tier: .harder)
        ]))
        XCTAssertEqual(result.subtitle, "Later, until 12:00. Notice the reach, then let it pass.")
    }

    func testEqualTierAndEndChooseStableIDRegardlessOfInputOrder() {
        let a = rule("a", name: "Alpha", tier: .hardcore)
        let z = rule("z", name: "Zebra", tier: .hardcore)
        let expected = "Alpha, until 12:00. Notice the reach, then let it pass."
        XCTAssertEqual(copy(contributors([z, a])).subtitle, expected)
        XCTAssertEqual(copy(contributors([a, z])).subtitle, expected)
    }

    func testOngoingEasyContributorPreventsFiniteReleasePromise() {
        let result = copy(contributors([rule("strict", name: "Laser Focus", tier: .hardcore)],
                                       ongoingTier: .easy))
        XCTAssertEqual(result.subtitle, "Laser Focus, until you stop. Notice the reach, then let it pass.")
    }

    func testUncertainCoverageDoesNotNameEvenAKnownContributor() {
        let value = contributors([rule("known", name: "Known"), rule("unknown", name: "Unknown")],
                                 uncertain: true)
        XCTAssertFalse(value.causes.isEmpty)
        XCTAssertTrue(value.coverageUncertain)
        XCTAssertEqual(copy(value).subtitle, neutral)
    }

    func testUnavailableAndMissingContributorsStayNeutral() {
        XCTAssertEqual(copy().subtitle, neutral)
        XCTAssertEqual(copy(contributors([rule("focus", name: "Focus")], available: false)).subtitle, neutral)
        XCTAssertEqual(copy(contributors()).subtitle, neutral)
    }

    func testNonEnforcingAndExpiredCausesCannotSupplyNameOrEnd() {
        let value = contributors([
            rule("off", name: "Not enforcing", tier: .hardcore, end: 7200, enforcing: false),
            rule("on", name: "Focus")
        ])
        XCTAssertEqual(copy(value).subtitle, "Focus, until 12:00. Notice the reach, then let it pass.")
        XCTAssertEqual(copy(value, at: now.addingTimeInterval(3600)).subtitle, neutral)
    }

    func testUnknownStrictEndDoesNotPromiseAnOrdinaryStop() {
        XCTAssertEqual(copy(contributors(ongoingTier: .harder)).subtitle, neutral)
        XCTAssertEqual(copy(contributors(ongoingTier: .hardcore)).subtitle, neutral)
    }

    func testLongNameKeepsCauseAndDeadlineWithoutOptionalReminder() {
        let name = String(repeating: "Long focus name ", count: 6).trimmingCharacters(in: .whitespaces)
        XCTAssertEqual(copy(contributors([rule("long", name: name)])).subtitle, "\(name), until 12:00.")
    }

    func testAppAndSiteTitlesHaveLocalizedFallbacks() {
        XCTAssertEqual(copy(name: nil).title, "This app is resting.")
        XCTAssertEqual(copy(name: nil, unit: .site).title, "This site is resting.")
        XCTAssertEqual(copy(name: "example.test", unit: .site).title, "example.test is resting.")
    }

    func testPositiveOpenLimitCopyAndButtonsStayUnchangedAndTakePrecedence() {
        let value = contributors([rule("focus", name: "Focus")])
        let one = copy(value, remaining: 1)
        XCTAssertEqual(one.subtitle, "1 intentional open left today. Notice the reach first.")
        XCTAssertEqual(one.primaryButtonLabel, "Use app (1 left)")
        XCTAssertNil(one.secondaryButtonLabel)
        let many = copy(value, unit: .site, remaining: 3)
        XCTAssertEqual(many.subtitle, "3 intentional opens left today. Notice the reach first.")
        XCTAssertEqual(many.primaryButtonLabel, "Use site (3 left)")
        XCTAssertNil(many.secondaryButtonLabel)
    }

    func testExhaustedAndUnavailableOpenLimitCopyStaysUnchanged() {
        for remaining in [0, -1] {
            let exhausted = copy(remaining: remaining)
            XCTAssertEqual(exhausted.subtitle, "Today’s opens are spent. It opens again tomorrow.")
            XCTAssertEqual(exhausted.primaryButtonLabel, "Go back")
            let unavailable = copy(remaining: remaining, storeAvailable: false)
            XCTAssertEqual(unavailable.subtitle, "Opens are unavailable right now.")
            XCTAssertEqual(unavailable.primaryButtonLabel, "Go back")
            XCTAssertNil(unavailable.secondaryButtonLabel)
        }
    }
}
