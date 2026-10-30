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
                         grant: FlintOpenCreditGrant? = nil, contributors: [FlintOpenCreditContributorKind] = [],
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
        // The reset follows elapsed time; only its wall-clock estimate is uncertain.
        let uncertain = copy(credit: credit(preview(remaining: 0, blockedBy: .exhausted, uncertain: true)))
        XCTAssertEqual(uncertain.subtitle, "No credits left today · the reset time is uncertain")
        XCTAssertNil(uncertain.secondaryButtonLabel)
        let local = ShieldCopy(name: "Instagram", unit: .app, contributors: nil, now: now,
                               openCredit: credit(preview(remaining: 0, blockedBy: .exhausted)),
                               locale: english, timeZone: TimeZone(identifier: "America/New_York")!)
        XCTAssertEqual(local.subtitle, "No credits left today · resets about 20:00", "device zone, not quota zone")
    }

    func testZeroAllowanceNeverPromisesARefill() {
        for cap in [0, -1] {
            let result = copy(credit: credit(preview(remaining: 0, cap: cap, blockedBy: .exhausted)))
            XCTAssertEqual(result.subtitle, "This Open Limit allows no credits")
            XCTAssertNil(result.secondaryButtonLabel)
        }
    }

    /// Easy and Harder never mention the pass: editing the limit is their ordinary path.
    func testExhaustedCopyPromisesNoEarlyExitBelowHardcore() {
        let result = copy(credit: credit(preview(remaining: 0, blockedBy: .exhausted)))
        XCTAssertEqual(result.subtitle, "No credits left today · resets about 00:00")
        XCTAssertFalse(result.subtitle.contains("Emergency Pass"))
        XCTAssertFalse(result.subtitle.contains("break"))
    }

    /// The global pass recovery suspends Open Limits (ADR-012 §6), so an exhausted Hardcore
    /// limit names it. It is a policy statement, not a claim that a pass is available now.
    func testHardcoreExhaustedNamesOnlyTheEmergencyPass() {
        let line = ". Only the Emergency Pass can pause this early."
        let cases: [(FlintOpenCreditPreview, String)] = [
            (preview(remaining: 0, blockedBy: .exhausted), "No credits left today · resets about 00:00"),
            (preview(remaining: 0, blockedBy: .exhausted, uncertain: true),
             "No credits left today · the reset time is uncertain"),
            (preview(remaining: 0, cap: 0, blockedBy: .exhausted), "This Open Limit allows no credits"),
            (.init(remaining: 0, cap: 3, blockedBy: .exhausted, duration: 300, timeUncertain: false),
             "No credits left today"),
        ]
        for (value, lead) in cases {
            let result = copy(credit: credit(value, hardcore: true))
            XCTAssertEqual(result.subtitle, lead + line)
            XCTAssertNil(result.secondaryButtonLabel)
        }
        // Hardcore never changes an available credit or another cause's copy.
        XCTAssertEqual(copy(credit: credit(preview(), hardcore: true)).secondaryButtonLabel, "Use one credit · 5 minutes")
        XCTAssertFalse(copy(credit: credit(preview(blockedBy: .otherContributor, contributors: [.session]),
                                           hardcore: true)).subtitle.contains("Emergency Pass"))
        XCTAssertFalse(copy(credit: credit(preview(blockedBy: .unavailable), hardcore: true)).subtitle
            .contains("Emergency Pass"))
    }

    func testAnotherContributorNamesOnlyItsKind() {
        let cases: [([FlintOpenCreditContributorKind], ShieldCopy.Unit, String)] = [
            ([.session], .app, "A focus session still blocks this app · no credit used"),
            ([.schedule], .app, "A schedule still blocks this app · no credit used"),
            ([.timeLimit], .app, "A Time Limit still blocks this app · no credit used"),
            ([.schedule], .site, "A schedule still blocks this site · no credit used"),
            ([.safariRestriction], .site, "A Safari restriction still blocks this site · no credit used"),
            ([.session, .schedule], .app, "A focus session still blocks this app · no credit used"),
            ([], .app, "Another protection still blocks this app · no credit used"),
            ([], .site, "Another protection still blocks this site · no credit used"),
        ]
        for (kinds, unit, expected) in cases {
            let result = copy(unit: unit, credit: credit(preview(blockedBy: .otherContributor, contributors: kinds)))
            XCTAssertEqual(result.subtitle, expected)
            XCTAssertNil(result.secondaryButtonLabel)
        }
        // "no credit used" needs an untouched pool; a missing grant is not a refund receipt.
        XCTAssertEqual(copy(credit: credit(preview(remaining: 1, blockedBy: .otherContributor,
                                                   contributors: [.schedule]))).subtitle,
                       "A schedule still blocks this app · 1 of 3 credits left today")
        XCTAssertEqual(copy(credit: credit(preview(remaining: 0, blockedBy: .otherContributor,
                                                   contributors: [.session]))).subtitle,
                       "A focus session still blocks this app · no credits left today")
        XCTAssertEqual(copy(credit: credit(preview(remaining: nil, cap: nil, blockedBy: .otherContributor,
                                                   contributors: [.session]))).subtitle,
                       "A focus session still blocks this app")
        // A newly added cause can block a committed credit; its clock keeps running.
        let blockedGrant = copy(credit: credit(preview(remaining: 2, blockedBy: .otherContributor, grant: liveGrant,
                                                       contributors: [.schedule], duration: 200)))
        XCTAssertEqual(blockedGrant.subtitle, "A schedule still blocks this app · credit active for 3 more min")
        XCTAssertNil(blockedGrant.secondaryButtonLabel)
    }

    /// Every contributor kind draws its own copy for both units; a new kind fails here
    /// instead of silently degrading to the generic line.
    func testEveryContributorKindMapsToItsCopy() {
        let expected: [FlintOpenCreditContributorKind: (app: String, site: String)] = [
            .session: ("A focus session still blocks this app", "A focus session still blocks this site"),
            .schedule: ("A schedule still blocks this app", "A schedule still blocks this site"),
            .timeLimit: ("A Time Limit still blocks this app", "A Time Limit still blocks this site"),
            // Safari restrictions apply to sites only; an app shield never comes from them.
            .safariRestriction: ("Another protection still blocks this app", "A Safari restriction still blocks this site"),
        ]
        XCTAssertEqual(Set(expected.keys), Set(FlintOpenCreditContributorKind.allCases))
        for (kind, lines) in expected {
            for (unit, line) in [(ShieldCopy.Unit.app, lines.app), (.site, lines.site)] {
                XCTAssertEqual(copy(unit: unit, credit: credit(preview(blockedBy: .otherContributor, contributors: [kind]))).subtitle,
                               line + " · no credit used", "\(kind)")
            }
        }
        XCTAssertEqual(FlintOpenCreditBlocking.query(target: "t", kind: .application, stores: [
            .init(kind: .schedule, applications: ["t"]), .init(kind: .session, applications: ["t"]),
            .init(kind: .schedule, applications: ["t"]),
        ]).contributors, [.session, .schedule], "distinct kinds, in kind order")
    }

    func testCategoryOverlapUsesTheContractMessage() {
        let app = copy(credit: credit(preview(blockedBy: .categoryOverlap, contributors: [.schedule])))
        XCTAssertEqual(app.subtitle, "Another category rule may still block this app; no credit used")
        XCTAssertNil(app.secondaryButtonLabel)
        XCTAssertEqual(copy(unit: .site, credit: credit(preview(blockedBy: .categoryOverlap))).subtitle,
                       "Another category rule may still block this site; no credit used")
        XCTAssertEqual(copy(credit: credit(preview(blockedBy: .categoryOverlap, grant: liveGrant, duration: 30))).subtitle,
                       "Another category rule may still block this app; credit active for under 1 more min")
    }

    func testAcceptanceGateOffSaysCreditsAreNotAvailableYet() {
        for unit in [ShieldCopy.Unit.app, .site] {
            let result = copy(unit: unit, credit: .acceptanceDisabled)
            XCTAssertEqual(result.subtitle, "Open credits aren't available yet")
            XCTAssertEqual(result.primaryButtonLabel, "Go back")
            XCTAssertNil(result.secondaryButtonLabel)
        }
    }

    func testCategoryTargetExplainsIndividualPicks() {
        let app = copy(credit: .categoryTarget)
        XCTAssertEqual(app.subtitle, "Open credits work for individual apps — pick apps in Flint")
        XCTAssertEqual(app.primaryButtonLabel, "Go back")
        XCTAssertNil(app.secondaryButtonLabel)
        XCTAssertEqual(copy(unit: .site, credit: .categoryTarget).subtitle,
                       "Open credits work for individual sites — pick sites in Flint")
    }

    /// Unreadable or unconfirmed state cannot prove nonconsumption, so it claims nothing more.
    func testUnconfirmedCreditStatesClaimNothingAboutConsumption() {
        let unavailable = "Flint can't confirm your credits right now"
        for block in [FlintOpenCreditBlock.unavailable, .registrationFailed, .staleOperation, .safetyFloor] {
            let result = copy(credit: credit(preview(remaining: nil, blockedBy: block)))
            XCTAssertEqual(result.subtitle, unavailable, block.rawValue)
            XCTAssertNil(result.secondaryButtonLabel)
        }
        // No pool, or no positive interval before the reset, refuses even without a block.
        XCTAssertEqual(copy(credit: credit(preview(remaining: nil))).subtitle, unavailable)
        XCTAssertEqual(copy(credit: credit(preview(duration: 0))).subtitle, unavailable)
        XCTAssertEqual(copy(credit: credit(preview(duration: nil))).subtitle, unavailable)
        XCTAssertNil(copy(credit: credit(preview(duration: 0))).secondaryButtonLabel)
        // Committed before native release failed: the credit is spent.
        let applying = copy(credit: credit(preview(blockedBy: .applying)))
        XCTAssertEqual(applying.subtitle, "A credit was used, but access hasn't opened yet")
        XCTAssertNil(applying.secondaryButtonLabel)
        let reselect = copy(credit: credit(preview(remaining: nil, blockedBy: .identityNeedsReselection)))
        XCTAssertEqual(reselect.subtitle, "Flint can't match this app to its credits yet")
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

    /// The primary button never spends, and the spending button is drawn exactly when the
    /// preview offers a credit (no block, no live grant, a positive pool and interval). That is
    /// necessary for accept to charge; `ShieldCreditPathTests` runs the real accept.
    func testEveryStateWithoutAnOfferHasOnlyGoBack() {
        var offered = 0
        let blocks: [FlintOpenCreditBlock?] = [nil] + allBlocks.map { $0 }
        for block in blocks {
            for grant in [nil, liveGrant] {
                for remaining in [nil, -1, 0, 1, 3] {
                    for duration in [nil, -1, 0, 59, 300] as [TimeInterval?] {
                        let value = preview(remaining: remaining, blockedBy: block, grant: grant, duration: duration)
                        for unit in [ShieldCopy.Unit.app, .site] {
                            let result = copy(unit: unit, credit: credit(value))
                            let offers = block == nil && grant == nil && (remaining ?? 0) > 0 && (duration ?? 0) > 0
                            XCTAssertEqual(result.primaryButtonLabel, "Go back")
                            XCTAssertEqual(result.secondaryButtonLabel != nil, offers, "\(value)")
                            XCTAssertEqual(ShieldCopy.offersCredit(value), offers)
                            if offers { offered += 1 }
                            if result.subtitle.contains("no credit used") {
                                // Only the period counter proves nonconsumption (fixture cap is 3).
                                XCTAssertTrue(grant == nil && remaining == 3, "unproven: \(result.subtitle)")
                            }
                            for text in [result.title, result.subtitle, result.primaryButtonLabel,
                                         result.secondaryButtonLabel ?? ""] {
                                XCTAssertFalse(text.localizedCaseInsensitiveContains("opens"), text)
                                XCTAssertFalse(text.localizedCaseInsensitiveContains("open left"), text)
                                XCTAssertFalse(text.contains("Emergency Pass"), text)
                            }
                        }
                    }
                }
            }
        }
        XCTAssertGreaterThan(offered, 0)
        for input in [nil, ShieldCopy.OpenCredit.categoryTarget, .acceptanceDisabled] {
            XCTAssertEqual(copy(credit: input).primaryButtonLabel, "Go back")
            XCTAssertNil(copy(credit: input).secondaryButtonLabel)
        }
    }
}
