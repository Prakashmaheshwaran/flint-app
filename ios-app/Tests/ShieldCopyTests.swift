import XCTest
@testable import FlintCore

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
                      credit: ShieldCopy.OpenCredit? = nil,
                      at date: Date? = nil) -> ShieldCopy {
        ShieldCopy(name: name, unit: unit, contributors: contributors, now: date ?? now,
                   openCredit: credit, locale: english, timeZone: utc)
    }

    // MARK: Open-credit fixtures

    private var midnight: Date { now.addingTimeInterval(13 * 3600) }

    private func preview(remaining: Int? = 3, cap: Int? = 3, blockedBy: FlintOpenCreditBlock? = nil,
                         grant: FlintOpenCreditGrant? = nil, contributors: [String] = [],
                         duration: TimeInterval? = 300, uncertain: Bool = false) -> FlintOpenCreditPreview {
        .init(remaining: remaining, cap: cap, blockedBy: blockedBy, grant: grant,
              blockingContributors: contributors, duration: duration, resetAtEstimate: midnight,
              quotaZone: "UTC", timeUncertain: uncertain)
    }

    private func credit(_ preview: FlintOpenCreditPreview, hardcore: Bool = false) -> ShieldCopy.OpenCredit {
        .target(preview, hardcore: hardcore)
    }

    private var liveGrant: FlintOpenCreditGrant {
        let restoration = FlintOpenCreditRestoration(kind: .application, encodedToken: Data(#"["app"]"#.utf8),
                                                     storeNames: ["flintO.open-a"])
        return FlintOpenCreditGrant(targetKey: restoration.targetKey, operationID: "op",
            grantedAt: .init(wall: now, monotonic: 100, bootID: "boot"), deadline: now.addingTimeInterval(300),
            duration: 300, coveredCauses: ["open-a"], coveredFingerprints: ["open-a": "f"],
            coveredScopes: ["open-a": "s"], coveredCaps: ["open-a": 3],
            coveredActivities: ["open-a": "flint.openLimit.open-a"], restoration: restoration)
    }

    private let allBlocks: [FlintOpenCreditBlock] = [
        .unavailable, .exhausted, .otherContributor, .categoryOverlap, .notOpenLimited,
        .identityNeedsReselection, .registrationFailed, .staleOperation, .safetyFloor, .applying,
    ]

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

    // MARK: Open credits (ADR-012 §5, lane B copy)

    func testAvailableCreditOffersOnlyTheSecondaryFiveMinuteButton() {
        let result = copy(credit: credit(preview()))
        XCTAssertEqual(result.title, "Instagram is resting.")
        XCTAssertEqual(result.subtitle, "3 of 3 credits left today")
        XCTAssertEqual(result.primaryButtonLabel, "Go back")
        XCTAssertEqual(result.secondaryButtonLabel, "Use one credit · 5 minutes")
        XCTAssertEqual(copy(credit: credit(preview(remaining: 2, cap: 4))).subtitle, "2 of 4 credits left today")
    }

    func testLastCreditIsStillOffered() {
        let result = copy(credit: credit(preview(remaining: 1)))
        XCTAssertEqual(result.subtitle, "Last credit today")
        XCTAssertEqual(result.primaryButtonLabel, "Go back")
        XCTAssertEqual(result.secondaryButtonLabel, "Use one credit · 5 minutes")
    }

    func testMidnightClippedCreditShowsWholeMinutesRoundedDown() {
        let labels: [(TimeInterval, String)] = [
            (299, "Use one credit · 4 minutes"), (150, "Use one credit · 2 minutes"),
            (120, "Use one credit · 2 minutes"), (119, "Use one credit · 1 minute"),
            (60, "Use one credit · 1 minute"), (59, "Use one credit · under 1 minute"),
            (1, "Use one credit · under 1 minute"),
        ]
        for (duration, label) in labels {
            XCTAssertEqual(copy(credit: credit(preview(duration: duration))).secondaryButtonLabel, label, "\(duration)s")
        }
    }

    func testLiveGrantDescribesTheCreditWithoutAReplayButton() {
        let result = copy(credit: credit(preview(remaining: 2, grant: liveGrant, duration: 250)))
        XCTAssertEqual(result.subtitle, "Credit active for 4 more min · no extra credit used")
        XCTAssertEqual(result.primaryButtonLabel, "Go back")
        XCTAssertNil(result.secondaryButtonLabel)
        XCTAssertEqual(copy(credit: credit(preview(remaining: 0, grant: liveGrant, duration: 30))).subtitle,
                       "Credit active for under 1 more min · no extra credit used")
    }

    func testExhaustedShowsTheEstimatedResetOrClockUncertainty() {
        let exhausted = copy(credit: credit(preview(remaining: 0, blockedBy: .exhausted)))
        XCTAssertEqual(exhausted.subtitle, "No credits left today · resets about 00:00")
        XCTAssertEqual(exhausted.primaryButtonLabel, "Go back")
        XCTAssertNil(exhausted.secondaryButtonLabel)
        let uncertain = copy(credit: credit(preview(remaining: 0, blockedBy: .exhausted, uncertain: true)))
        XCTAssertEqual(uncertain.subtitle, "No credits left today · resets after the clock is confirmed")
        XCTAssertNil(uncertain.secondaryButtonLabel)
    }

    func testHardcoreExhaustedNamesOnlyTheEmergencyPass() {
        let result = copy(credit: credit(preview(remaining: 0, blockedBy: .exhausted), hardcore: true))
        XCTAssertEqual(result.subtitle,
                       "No credits left today · resets about 00:00. Only the Emergency Pass ends this early.")
        XCTAssertNil(result.secondaryButtonLabel)
        let uncertain = copy(credit: credit(preview(remaining: 0, blockedBy: .exhausted, uncertain: true), hardcore: true))
        XCTAssertEqual(uncertain.subtitle,
                       "No credits left today · resets after the clock is confirmed. Only the Emergency Pass ends this early.")
        // Hardcore never changes an available credit: quota credits are part of the commitment.
        XCTAssertEqual(copy(credit: credit(preview(), hardcore: true)).secondaryButtonLabel, "Use one credit · 5 minutes")
    }

    func testAnotherContributorNamesOnlyItsKind() {
        let cases: [([String], ShieldCopy.Unit, String)] = [
            (["Current session"], .app, "A focus session still blocks this app · no credit used"),
            (["Schedule: Deep work"], .app, "A schedule still blocks this app · no credit used"),
            (["Time Limit: Social"], .app, "A Time Limit still blocks this app · no credit used"),
            (["Schedule: Deep work"], .site, "A schedule still blocks this site · no credit used"),
            (["Safari restriction"], .site, "A Safari restriction still blocks this site · no credit used"),
            (["Schedule: Deep work", "Current session"], .app, "A focus session still blocks this app · no credit used"),
            ([], .app, "Another protection still blocks this app · no credit used"),
            (["Unrecognized store"], .site, "Another protection still blocks this site · no credit used"),
        ]
        for (names, unit, expected) in cases {
            let result = copy(unit: unit, credit: credit(preview(blockedBy: .otherContributor, contributors: names)))
            XCTAssertEqual(result.subtitle, expected)
            XCTAssertFalse(result.subtitle.contains("Deep work") || result.subtitle.contains("Social"), "kind only")
            XCTAssertNil(result.secondaryButtonLabel)
        }
    }

    func testCategoryOverlapUsesTheContractMessage() {
        let app = copy(credit: credit(preview(blockedBy: .categoryOverlap, contributors: ["Schedule: Deep work"])))
        XCTAssertEqual(app.subtitle, "Another category rule may still block this app; no credit used")
        XCTAssertNil(app.secondaryButtonLabel)
        XCTAssertEqual(copy(unit: .site, credit: credit(preview(blockedBy: .categoryOverlap))).subtitle,
                       "Another category rule may still block this site; no credit used")
    }

    func testCategoryTargetExplainsIndividualPicks() {
        let app = copy(credit: .categoryTarget)
        XCTAssertEqual(app.subtitle, "Open credits work for individual apps — pick apps in Flint")
        XCTAssertEqual(app.primaryButtonLabel, "Go back")
        XCTAssertNil(app.secondaryButtonLabel)
        XCTAssertEqual(copy(unit: .site, credit: .categoryTarget).subtitle,
                       "Open credits work for individual sites — pick sites in Flint")
    }

    func testUnconfirmedCreditStatesSayNoCreditUsed() {
        let unavailable = "Flint can't confirm your credits right now · no credit used"
        for block in [FlintOpenCreditBlock.unavailable, .registrationFailed, .staleOperation, .safetyFloor, .applying] {
            let result = copy(credit: credit(preview(remaining: nil, blockedBy: block)))
            XCTAssertEqual(result.subtitle, unavailable, block.rawValue)
            XCTAssertNil(result.secondaryButtonLabel)
        }
        // No pool, or no positive interval before the reset, refuses even without a block.
        XCTAssertEqual(copy(credit: credit(preview(remaining: nil))).subtitle, unavailable)
        XCTAssertEqual(copy(credit: credit(preview(duration: 0))).subtitle, unavailable)
        XCTAssertEqual(copy(credit: credit(preview(duration: nil))).subtitle, unavailable)
        XCTAssertNil(copy(credit: credit(preview(duration: 0))).secondaryButtonLabel)
        let reselect = copy(credit: credit(preview(remaining: nil, blockedBy: .identityNeedsReselection)))
        XCTAssertEqual(reselect.subtitle, "Flint can't match this app to its credits yet · no credit used")
        XCTAssertNil(reselect.secondaryButtonLabel)
    }

    func testNotOpenLimitedFallsBackToTheShieldCause() {
        let result = copy(contributors([rule("focus", name: "Focus")]),
                          credit: credit(preview(remaining: nil, cap: nil, blockedBy: .notOpenLimited)))
        XCTAssertEqual(result.subtitle, "Focus, until 12:00. Notice the reach, then let it pass.")
        XCTAssertNil(result.secondaryButtonLabel)
    }

    func testCreditCopyTakesPrecedenceOverTheCauseSubtitle() {
        let value = contributors([rule("focus", name: "Focus")])
        XCTAssertEqual(copy(value, credit: credit(preview(remaining: 2))).subtitle, "2 of 3 credits left today")
        XCTAssertEqual(copy(value).subtitle, "Focus, until 12:00. Notice the reach, then let it pass.")
    }

    /// The primary button never spends, and a spending button exists exactly when accept
    /// would charge: no block, no live grant, a positive pool and a positive interval.
    func testEveryStateThatAcceptWouldRefuseHasOnlyGoBack() {
        var offered = 0
        let blocks: [FlintOpenCreditBlock?] = [nil] + allBlocks.map { $0 }
        for block in blocks {
            for grant in [nil, liveGrant] {
                for remaining in [nil, -1, 0, 1, 3] {
                    for duration in [nil, -1, 0, 59, 300] as [TimeInterval?] {
                        for hardcore in [false, true] {
                            let value = preview(remaining: remaining, blockedBy: block, grant: grant, duration: duration)
                            for unit in [ShieldCopy.Unit.app, .site] {
                                let result = copy(unit: unit, credit: credit(value, hardcore: hardcore))
                                let charges = block == nil && grant == nil && (remaining ?? 0) > 0 && (duration ?? 0) > 0
                                XCTAssertEqual(result.primaryButtonLabel, "Go back")
                                XCTAssertEqual(result.secondaryButtonLabel != nil, charges, "\(value)")
                                XCTAssertEqual(ShieldCopy.offersCredit(value), charges)
                                if charges { offered += 1 }
                                for text in [result.title, result.subtitle, result.primaryButtonLabel,
                                             result.secondaryButtonLabel ?? ""] {
                                    XCTAssertFalse(text.localizedCaseInsensitiveContains("opens"), text)
                                    XCTAssertFalse(text.localizedCaseInsensitiveContains("open left"), text)
                                }
                            }
                        }
                    }
                }
            }
        }
        XCTAssertGreaterThan(offered, 0)
        for input in [nil, ShieldCopy.OpenCredit.categoryTarget] {
            XCTAssertEqual(copy(credit: input).primaryButtonLabel, "Go back")
            XCTAssertNil(copy(credit: input).secondaryButtonLabel)
        }
    }
}
