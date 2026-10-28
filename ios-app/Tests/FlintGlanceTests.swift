import XCTest
@testable import Flint

final class FlintGlanceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func input(_ state: FlintGlanceInput.Protection = .enforcing,
                       checked: TimeInterval = -10, valid: TimeInterval = 60,
                       end: TimeInterval = 600, session: Bool = true,
                       cause: FlintGlanceInput.Session.Cause = .manual,
                       history: FlintGlanceInput.History.Availability = .value,
                       seconds: TimeInterval? = 125, inProgress: Bool = false) -> FlintGlanceInput {
        .init(protection: state, checkedAt: now.addingTimeInterval(checked),
              validUntil: now.addingTimeInterval(valid),
              session: session ? .init(id: "fixture", cause: cause, tier: "Harder",
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
    }

    func testActiveSessionAloneCannotAuthorizeActivity() {
        for state in FlintGlanceInput.Protection.allCases where ![.armed, .enforcing].contains(state) {
            XCTAssertNil(plan(input(state)), "\(state)")
        }
        XCTAssertNil(plan(input(session: false)))
    }

    func testActivityRequiresOptInAndOSAuthorization() {
        XCTAssertNil(plan(input(), optedIn: false))
        XCTAssertNil(plan(input(), system: false))
        XCTAssertNotNil(plan(input(.armed)))
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
}
