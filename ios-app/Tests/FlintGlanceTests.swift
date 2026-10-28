import XCTest
@testable import Flint

final class FlintGlanceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func input(_ state: FlintGlanceInput.Protection = .enforcing,
                       checked: TimeInterval = -10, valid: TimeInterval = 60,
                       end: TimeInterval = 600, session: Bool = true,
                       sessionArmed: Bool = true,
                       cause: FlintGlanceInput.Session.Cause = .manual,
                       history: FlintGlanceInput.History.Availability = .value,
                       seconds: TimeInterval? = 125, inProgress: Bool = false) -> FlintGlanceInput {
        .init(protection: state, checkedAt: now.addingTimeInterval(checked),
              validUntil: now.addingTimeInterval(valid),
              session: session ? .init(id: "fixture", cause: cause, tier: "Harder", isArmed: sessionArmed,
                                      startedAt: now.addingTimeInterval(-300),
                                      endsAt: now.addingTimeInterval(end)) : nil,
              protectionEndsAt: now.addingTimeInterval(end),
              next: .init(startsAt: now.addingTimeInterval(1200), endsAt: now.addingTimeInterval(1800)),
              history: .init(availability: history, seconds: seconds, inProgress: inProgress, readAt: now))
    }

    private func plan(_ value: FlintGlanceInput, optedIn: Bool = true,
                      system: Bool = true, at date: Date? = nil) -> FlintGlanceActivityPlan? {
        .make(value, optedIn: optedIn, systemEnabled: system, at: date ?? now)
    }

    func testEveryAuthorityStateMapsConservatively() {
        let expected: [FlintGlancePresentation.Status] = [
            .idle, .saved, .armed, .enforcing, .suspended, .pass, .degraded, .unavailable,
        ]
        for (state, status) in zip(FlintGlanceInput.Protection.allCases, expected) {
            let model = FlintGlancePresentation(input(state), at: now)
            XCTAssertEqual(model.status, status, "\(state)")
            XCTAssertEqual(model.countdownEnd != nil, state == .armed || state == .enforcing)
        }
    }

    func testNoSnapshotIsUnavailableNeverIdle() {
        let model = FlintGlancePresentation(.unavailable(at: now), at: now)
        XCTAssertEqual(model.status, .unavailable)
        XCTAssertNil(model.countdownEnd)
        XCTAssertEqual(model.historyValue, "Unavailable")
    }

    func testStaleSnapshotWithdrawsTimerNextAndEligibility() {
        let value = input(valid: 0)
        let model = FlintGlancePresentation(value, at: now)
        XCTAssertEqual(model.status, .stale)
        XCTAssertNil(model.countdownEnd)
        XCTAssertNil(model.nextStart)
        XCTAssertNil(plan(value))
    }

    func testFutureCheckAndInvertedValidityAreNotEvidence() {
        XCTAssertEqual(FlintGlancePresentation(input(checked: 1), at: now).status, .stale)
        XCTAssertEqual(FlintGlancePresentation(input(checked: -1, valid: -2), at: now).status, .stale)
        XCTAssertNil(plan(input(checked: 1)))
    }

    func testLogicalEndNeverBecomesCompletedOrContinuingTimer() {
        for end: TimeInterval in [-1, 0] {
            let model = FlintGlancePresentation(input(end: end), at: now)
            XCTAssertEqual(model.status, .expired)
            XCTAssertEqual(model.title, "Planned end reached")
            XCTAssertNil(model.countdownEnd)
            XCTAssertFalse(model.title.lowercased().contains("completed"))
            XCTAssertNil(plan(input(end: end)))
        }
    }

    func testCountdownAndActivityStopAtEarliestTrustedBoundary() {
        let value = input(valid: 60, end: 600)
        XCTAssertEqual(FlintGlancePresentation(value, at: now).countdownEnd, now.addingTimeInterval(60))
        XCTAssertEqual(plan(value)?.endsAt, now.addingTimeInterval(600))
        XCTAssertEqual(plan(value)?.staleAt, now.addingTimeInterval(60))
        XCTAssertEqual(FlintGlancePresentation(input(valid: 600, end: 30), at: now).countdownEnd,
                       now.addingTimeInterval(30))
        XCTAssertEqual(FlintGlanceActivityPlan.countdownLabel(endsAt: now.addingTimeInterval(600),
                                                            staleAt: now.addingTimeInterval(60)), "Check in")
        XCTAssertEqual(FlintGlanceActivityPlan.countdownLabel(endsAt: now.addingTimeInterval(30),
                                                            staleAt: now.addingTimeInterval(30)), "Planned time left")
    }

    func testActiveSessionAloneCannotAuthorizeActivity() {
        for state in FlintGlanceInput.Protection.allCases where ![.armed, .enforcing].contains(state) {
            XCTAssertNil(plan(input(state)), "\(state)")
        }
        XCTAssertNil(plan(input(session: false)))
        XCTAssertNil(plan(input(sessionArmed: false)))
    }

    func testActivityRequiresOptInAndOSAuthorization() {
        XCTAssertNil(plan(input(), optedIn: false))
        XCTAssertNil(plan(input(), system: false))
        XCTAssertNotNil(plan(input(.armed)))
    }

    func testDismissedSessionIsNotAutomaticallyRequestedAgain() {
        let value = plan(input())!
        XCTAssertTrue(value.mayRequest(lastRequestedSession: nil))
        XCTAssertFalse(value.mayRequest(lastRequestedSession: value.sessionID))
        XCTAssertTrue(value.mayRequest(lastRequestedSession: "previous-session"))
    }

    func testOnlyManualTimerPomodoroAreEligible() {
        for cause in FlintGlanceInput.Session.Cause.allCases {
            XCTAssertEqual(plan(input(cause: cause)) != nil, cause != .other)
        }
    }

    func testHostDeadFutureEntriesOnlyWithdrawClaims() {
        let value = input()
        let dates = FlintGlancePresentation.boundaries(value, after: now)
        XCTAssertTrue(dates.contains(now.addingTimeInterval(60)))
        XCTAssertTrue(dates.contains(now.addingTimeInterval(600)))
        XCTAssertTrue(dates.contains(now.addingTimeInterval(1200)))
        XCTAssertEqual(dates, dates.sorted())
        for date in dates {
            let model = FlintGlancePresentation(value, at: date)
            XCTAssertNil(model.countdownEnd)
            XCTAssertFalse(model.title.lowercased().contains("completed"))
        }
    }

    func testEveryHistoryAvailabilityPreservesMissingVersusZero() {
        XCTAssertEqual(FlintGlancePresentation(input(history: .unavailable, seconds: 0), at: now).historyValue,
                       "Unavailable")
        XCTAssertEqual(FlintGlancePresentation(input(history: .empty, seconds: 0), at: now).historyValue,
                       "No sessions yet")
        XCTAssertEqual(FlintGlancePresentation(input(seconds: 0), at: now).historyValue, "0m")
        XCTAssertEqual(FlintGlancePresentation(input(seconds: nil), at: now).historyValue, "No data")
        let degraded = FlintGlancePresentation(input(history: .degraded), at: now)
        XCTAssertEqual(degraded.historyValue, "2m")
        XCTAssertEqual(degraded.historyNote, "Some history couldn't be read")
        XCTAssertEqual(FlintGlancePresentation(input(inProgress: true), at: now).historyNote,
                       "In progress · as of update")
    }

    func testYesterdayHistoryDoesNotBecomeTodayZero() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: now)!
        let model = FlintGlancePresentation(input(), at: tomorrow, calendar: calendar)
        XCTAssertEqual(model.historyValue, "Unavailable")
    }

    func testCachedRunningHistoryWithdrawsProgressAtUnsupportedBoundaries() {
        let value = input(inProgress: true)
        XCTAssertTrue(FlintGlancePresentation(value, at: now).historyValue.contains("in progress at update"))
        for offset: TimeInterval in [60, 600] {
            let model = FlintGlancePresentation(value, at: now.addingTimeInterval(offset))
            XCTAssertEqual(model.historyValue, "2m · as of update")
            XCTAssertFalse(model.historyValue.contains("in progress"))
            XCTAssertEqual(model.historyNote, "Open Flint to refresh")
        }
        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: now)!
        XCTAssertEqual(FlintGlancePresentation(value, at: tomorrow).historyValue, "Unavailable")
    }

    func testDurationNeverInventsOrOverflows() {
        XCTAssertEqual(FlintGlancePresentation.duration(1), "<1m")
        XCTAssertEqual(FlintGlancePresentation.duration(3600), "1h")
        XCTAssertEqual(FlintGlancePresentation.duration(3660), "1h 1m")
        XCTAssertEqual(FlintGlancePresentation.duration(-1), "Unavailable")
        XCTAssertEqual(FlintGlancePresentation.duration(.infinity), "Unavailable")
    }

    func testOnlyDeepLinkHasNoActionPayload() {
        XCTAssertEqual(FlintGlancePresentation.openURL.absoluteString, "flint://open")
        XCTAssertNil(URLComponents(url: FlintGlancePresentation.openURL, resolvingAgainstBaseURL: false)?.query)
    }

    func testPublicNamesAreBoundedAndControlCharactersDoNotReachSurfaces() {
        XCTAssertEqual(FlintGlancePresentation.sanitizedName(" Deep\n focus\u{202E} "), "Deep focus")
        XCTAssertEqual(FlintGlancePresentation.sanitizedName("\n\u{202E}"), "Focus session")
        XCTAssertEqual(FlintGlancePresentation.sanitizedName(String(repeating: "a", count: 100)).count, 64)
        var value = input()
        var session = value.session!
        session.name = "Deep study"
        value = .init(protection: value.protection, checkedAt: value.checkedAt, validUntil: value.validUntil,
                      session: session, protectionEndsAt: value.protectionEndsAt, next: value.next,
                      history: value.history, headline: .init(name: "Deep study", kind: "session", tier: "Harder"))
        XCTAssertEqual(plan(value)?.cause, "Deep study")
        XCTAssertEqual(FlintGlancePresentation(value, at: now).causeSummary, "Deep study · Harder")
    }

    private func project(version: Int = 1, state: String = "protected", observed: TimeInterval = -10,
                         validity: TimeInterval? = 600, reasons: [String] = [],
                         ends: [Date?]? = nil, recovery: Date? = nil) -> FlintGlanceInput {
        let fixture = input()
        return FlintGlanceStatusAdapter.project(version: version, observedAt: now.addingTimeInterval(observed),
            validUntil: validity.map { now.addingTimeInterval($0) }, state: state,
            session: fixture.session, enforcingEnds: ends ?? [now.addingTimeInterval(600)],
            next: fixture.next, recoveryUntil: recovery, degradedReasons: reasons,
            history: fixture.history, at: now)
    }

    func testBPublicStateProjectionAndGlobalRecovery() {
        for (state, expected) in [("protected", FlintGlanceInput.Protection.enforcing),
                                  ("not_protected", .idle), ("degraded", .degraded),
                                  ("suspended", .suspended), ("unavailable", .unavailable)] {
            XCTAssertEqual(project(state: state).protection, expected)
        }
        XCTAssertEqual(project(state: "suspended", recovery: now.addingTimeInterval(600)).protection, .pass)
        XCTAssertNil(plan(project(state: "suspended", recovery: now.addingTimeInterval(600))))
        XCTAssertEqual(project(reasons: ["registration_unobserved"]).protection, .degraded)
        XCTAssertNil(plan(project(reasons: ["registration_unobserved"])))
    }

    func testBUnknownMissingUntrustedAndClockUncertainFailUnavailable() {
        for value in [project(version: 0), project(version: 2), project(state: "future_state"),
                      project(observed: 1), project(validity: nil), project(validity: 0),
                      project(reasons: ["time_uncertain"]), project(ends: []), project(observed: -301)] {
            XCTAssertEqual(value.protection, .unavailable)
            XCTAssertNil(plan(value))
            // Independently readable F history does not become a fabricated zero.
            XCTAssertEqual(FlintGlancePresentation(value, at: now).historyValue, "2m")
        }
    }

    func testBDisplayFreshnessIsBoundedIndependentlyOfLogicalEnd() {
        let value = project()
        XCTAssertEqual(value.validUntil, now.addingTimeInterval(290))
        XCTAssertEqual(value.protectionEndsAt, now.addingTimeInterval(600))
        XCTAssertEqual(plan(value)?.staleAt, now.addingTimeInterval(290))
        XCTAssertEqual(plan(value)?.endsAt, now.addingTimeInterval(600))
    }

    func testIdleRequiresTheWriterFreshnessDeadlineEvenWithoutUpcomingOccurrence() {
        let value = project(state: "not_protected")
        XCTAssertEqual(value.protection, .idle)
        XCTAssertEqual(value.validUntil, now.addingTimeInterval(290))
        XCTAssertEqual(FlintGlancePresentation(value, at: now).status, .idle)
        XCTAssertNil(plan(value))
        XCTAssertEqual(project(state: "not_protected", validity: nil).protection, .unavailable)
        XCTAssertEqual(project(validity: nil).protection, .unavailable)
    }

    func testBOverlapUntilNeverDropsLaterOrOpenEndedContributor() {
        XCTAssertEqual(project(ends: [now.addingTimeInterval(60), now.addingTimeInterval(600)]).protectionEndsAt,
                       now.addingTimeInterval(600))
        let openEnded = project(ends: [nil, now.addingTimeInterval(600)])
        XCTAssertNil(openEnded.protectionEndsAt)
        XCTAssertNotNil(plan(openEnded), "An unrelated open-ended cause cannot erase a finite armed session")
    }

    func testNamedHeadlineKeepsItsOwnDeadlineAcrossOverlap() {
        var value = project(ends: [now.addingTimeInterval(60), now.addingTimeInterval(600)])
        value.headline = .init(name: "Strict A", kind: "recurringSchedule", tier: "Hardcore",
                               endsAt: now.addingTimeInterval(60))
        let model = FlintGlancePresentation(value, at: now)
        XCTAssertEqual(model.causeSummary, "Strict A · Hardcore")
        XCTAssertEqual(model.plannedEnd, now.addingTimeInterval(60))
        XCTAssertEqual(model.countdownEnd, now.addingTimeInterval(60))
        XCTAssertEqual(value.protectionEndsAt, now.addingTimeInterval(600))
        let boundary = FlintGlancePresentation(value, at: now.addingTimeInterval(60))
        XCTAssertEqual(boundary.status, .stale)
        XCTAssertNil(boundary.causeSummary)
        XCTAssertNil(boundary.countdownEnd)
        XCTAssertNil(boundary.plannedEnd)
        XCTAssertTrue(FlintGlancePresentation.boundaries(value, after: now).contains(now.addingTimeInterval(60)))

        value = project(ends: [nil, now.addingTimeInterval(600)])
        value.headline = .init(name: "Open-ended A", kind: "session", tier: "Easy", endsAt: nil)
        XCTAssertNil(FlintGlancePresentation(value, at: now).plannedEnd)
        XCTAssertNil(FlintGlancePresentation(value, at: now).countdownEnd)
    }
}
