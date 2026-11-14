import XCTest
import FlintCore

/// Comparable streak lifecycle fixtures shared conceptually with Android
/// `FocusStreakLifecycleTest`. Scenario IDs S01–S20 match across platforms.
final class FlintHistoryStreakLifecycleTests: XCTestCase {
    private var ny: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }
    /// Local noon on 2026-10-28 America/New_York.
    private var todayNoon: Date {
        ny.date(from: DateComponents(year: 2026, month: 10, day: 28, hour: 12))!
    }
    private var today: Date { ny.startOfDay(for: todayNoon) }
    private var historyStart: Date { ny.date(byAdding: .day, value: -30, to: today)! }

    private func at(dayOffset: Int, hour: Int = 9) -> Date {
        let day = ny.date(byAdding: .day, value: dayOffset, to: today)!
        return ny.date(bySettingHour: hour, minute: 0, second: 0, of: day)!
    }

    private func started(dayOffset: Int, id: String? = nil, hour: Int = 9,
                         timeConfidence: FlintEventTimeConfidence? = nil) -> FlintHistoryEvent {
        let start = at(dayOffset: dayOffset, hour: hour)
        return FlintHistoryEvent(type: .started, session: id ?? "s\(dayOffset)", at: start,
                                 plannedEnd: start.addingTimeInterval(3600), tier: "easy",
                                 source: "manual", name: "Focus", timeConfidence: timeConfidence)
    }

    private func completed(dayOffset: Int, id: String? = nil, hour: Int = 10) -> FlintHistoryEvent {
        FlintHistoryEvent(type: .completed, session: id ?? "s\(dayOffset)",
                          at: at(dayOffset: dayOffset, hour: hour))
    }

    private func endedEarly(dayOffset: Int, id: String? = nil) -> FlintHistoryEvent {
        FlintHistoryEvent(type: .endedEarly, session: id ?? "s\(dayOffset)",
                          at: at(dayOffset: dayOffset, hour: 9).addingTimeInterval(1800),
                          route: "manual_stop")
    }

    private func passOnly(dayOffset: Int = 0) -> FlintHistoryEvent {
        FlintHistoryEvent(type: .passUsed, session: "pass-\(dayOffset)",
                          at: at(dayOffset: dayOffset, hour: 11),
                          sessions: ["other"],
                          recoveryEnd: at(dayOffset: dayOffset, hour: 12))
    }

    private func completedDays(_ offsets: Int...) -> [FlintHistoryEvent] {
        offsets.flatMap { [started(dayOffset: $0), completed(dayOffset: $0)] }
    }

    private func summarize(_ events: [FlintHistoryEvent], now: Date? = nil,
                           historyStart: Date? = nil, calendar: Calendar? = nil,
                           active: String? = nil,
                           availability: FlintHistoryAvailability? = nil) -> FlintHistorySummary {
        let cal = calendar ?? ny
        let state: FlintHistoryAvailability
        if let availability {
            state = availability
        } else if events.isEmpty {
            state = .empty
        } else {
            state = .value
        }
        return FlintHistoryAggregator.summarize(
            .init(events: events,
                  historyStart: historyStart ?? self.historyStart,
                  availability: state),
            activeSessionID: active,
            now: now ?? todayNoon,
            calendar: cal,
            dayCount: 7)
    }

    func testS01_fiveConsecutiveCompletedDays() {
        let result = summarize(completedDays(-4, -3, -2, -1, 0))
        XCTAssertEqual(result.streak, 5)
        XCTAssertEqual(result.bestRecordedStreak, 5)
    }

    func testS02_todayIncompleteYesterdayCompleted() {
        let result = summarize(completedDays(-2, -1))
        XCTAssertEqual(result.streak, 2)
        XCTAssertEqual(result.bestRecordedStreak, 2)
    }

    func testS03_oneFullMissedDayBreaksCurrent() {
        let result = summarize(completedDays(-4, -3, -2))
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 3)
        XCTAssertEqual(result.sessions.filter { $0.outcome == .completed }.count, 3)
    }

    func testS04_returnAfterOneMissedDayStartsOne() {
        let result = summarize(completedDays(-4, -3, -2, 0))
        XCTAssertEqual(result.streak, 1)
        XCTAssertEqual(result.bestRecordedStreak, 3)
    }

    func testS05_threeConsecutiveMissedDays() {
        let result = summarize(completedDays(-5, -4))
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 2)
    }

    func testS06_multipleCompletionsSameDayCountOnce() {
        let events = [
            started(dayOffset: 0, id: "a", hour: 8), completed(dayOffset: 0, id: "a", hour: 9),
            started(dayOffset: 0, id: "b", hour: 11), completed(dayOffset: 0, id: "b", hour: 12),
            started(dayOffset: -1, id: "c"), completed(dayOffset: -1, id: "c"),
        ]
        let result = summarize(events)
        XCTAssertEqual(result.streak, 2)
        XCTAssertEqual(result.bestRecordedStreak, 2)
        XCTAssertEqual(result.days.last?.completed, 2)
    }

    func testS07_startedButIncompleteDoesNotCredit() {
        // Planned end must still be in the future or the active ID alone cannot stay RUNNING.
        let start = at(dayOffset: 0, hour: 11)
        let planned = at(dayOffset: 0, hour: 14)
        let event = FlintHistoryEvent(type: .started, session: "s0", at: start, plannedEnd: planned,
                                      tier: "easy", source: "manual", name: "Focus")
        let result = summarize([event], active: "s0")
        XCTAssertEqual(result.sessions.single.outcome, .running)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 0)
    }

    func testS08_unconfirmedDoesNotCredit() {
        let result = summarize([started(dayOffset: -1)])
        XCTAssertEqual(result.sessions.single.outcome, .unconfirmed)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 0)
    }

    func testS09_interruptedEndedEarlyDoesNotCreditStreak() {
        let result = summarize([started(dayOffset: 0), endedEarly(dayOffset: 0)])
        XCTAssertEqual(result.sessions.single.outcome, .endedEarly)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 0)
    }

    func testS10_emergencyPassWithoutCompletedFocus() {
        let result = summarize([passOnly()])
        XCTAssertTrue(result.sessions.isEmpty)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 0)
    }

    func testS11_historyStartCutsStreak() {
        let start = ny.date(byAdding: .day, value: -1, to: today)!
        let result = summarize(completedDays(-3, -2, -1, 0), historyStart: start)
        XCTAssertEqual(result.streak, 2)
        XCTAssertEqual(result.bestRecordedStreak, 2)
    }

    func testS12_emptyHistory() {
        let result = summarize([])
        XCTAssertEqual(result.availability, .empty)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 0)
    }

    func testS13_unavailableHistory() {
        let result = summarize([], availability: .unavailable)
        XCTAssertEqual(result.availability, .unavailable)
        XCTAssertEqual(result.streak, 0)
        XCTAssertEqual(result.bestRecordedStreak, 0)
    }

    func testS14_localMidnightBoundaryUsesEndDay() {
        let end = today.addingTimeInterval(60)
        let start = end.addingTimeInterval(-3600)
        let events = [
            FlintHistoryEvent(type: .started, session: "midnight", at: start, plannedEnd: end,
                              tier: "easy", source: "manual", name: "Focus"),
            FlintHistoryEvent(type: .completed, session: "midnight", at: end),
        ]
        let result = summarize(events)
        XCTAssertEqual(result.streak, 1)
        XCTAssertEqual(result.days.last?.completed, 1)
    }

    func testS15_dstSpringForwardConsecutiveDays() {
        let dates = ["2026-03-07", "2026-03-08", "2026-03-09"]
        var events: [FlintHistoryEvent] = []
        for (index, date) in dates.enumerated() {
            let start = ny.date(from: DateComponents(year: 2026, month: 3, day: Int(date.suffix(2))!, hour: 9))!
            let end = start.addingTimeInterval(3600)
            events += [
                FlintHistoryEvent(type: .started, session: "dst\(index)", at: start, plannedEnd: end,
                                  tier: "easy", source: "manual", name: "Focus"),
                FlintHistoryEvent(type: .completed, session: "dst\(index)", at: end),
            ]
        }
        let now = ny.date(from: DateComponents(year: 2026, month: 3, day: 9, hour: 12))!
        let history = ny.date(from: DateComponents(year: 2026, month: 3, day: 1))!
        let result = summarize(events, now: now, historyStart: history)
        XCTAssertEqual(result.streak, 3)
        XCTAssertEqual(result.bestRecordedStreak, 3)
    }

    func testS16_dstFallBackConsecutiveDays() {
        let days = [31, 1, 2]
        var events: [FlintHistoryEvent] = []
        for (index, day) in days.enumerated() {
            let month = day >= 31 ? 10 : 11
            let start = ny.date(from: DateComponents(year: 2026, month: month, day: day, hour: 9))!
            let end = start.addingTimeInterval(3600)
            events += [
                FlintHistoryEvent(type: .started, session: "fall\(index)", at: start, plannedEnd: end,
                                  tier: "easy", source: "manual", name: "Focus"),
                FlintHistoryEvent(type: .completed, session: "fall\(index)", at: end),
            ]
        }
        let now = ny.date(from: DateComponents(year: 2026, month: 11, day: 2, hour: 12))!
        let history = ny.date(from: DateComponents(year: 2026, month: 10, day: 20))!
        let result = summarize(events, now: now, historyStart: history)
        XCTAssertEqual(result.streak, 3)
        XCTAssertEqual(result.bestRecordedStreak, 3)
    }

    /// S17 — timezone rebucketing contract (parity with Android FocusStreakLifecycleTest).
    ///
    /// Storage: event `at` / session end are epoch milliseconds (absolute instants). The
    /// stored `tz` field is provenance only; aggregation uses the *current* calendar
    /// timezone via `startOfDay(for: endedAt)`. Changing the device timezone therefore
    /// rebuckets historical completions and can raise or lower current and best streak
    /// without mutating history bytes.
    func testS17_timezoneChangeRebuckets() {
        var tokyo = Calendar(identifier: .gregorian)
        tokyo.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        let history = ny.date(from: DateComponents(year: 2026, month: 10, day: 1))!

        func session(id: String, year: Int, month: Int, day: Int, hour: Int) -> [FlintHistoryEvent] {
            let start = ny.date(from: DateComponents(year: year, month: month, day: day, hour: hour))!
            let end = start.addingTimeInterval(3600)
            return [
                FlintHistoryEvent(type: .started, session: id, at: start, plannedEnd: end,
                                  tier: "easy", source: "manual", name: "Focus"),
                FlintHistoryEvent(type: .completed, session: id, at: end),
            ]
        }

        // Midday: NY today-completed streak 1; Tokyo sees that completion as yesterday → streak 1.
        let midday = session(id: "mid", year: 2026, month: 10, day: 28, hour: 10)
        let middayNow = ny.date(from: DateComponents(year: 2026, month: 10, day: 28, hour: 12))!
        let middayNy = summarize(midday, now: middayNow, historyStart: history, calendar: ny)
        let middayTokyo = summarize(midday, now: middayNow, historyStart: history, calendar: tokyo)
        XCTAssertEqual(middayNy.streak, 1)
        XCTAssertEqual(middayNy.bestRecordedStreak, 1)
        XCTAssertEqual(middayTokyo.streak, 1)
        XCTAssertEqual(middayTokyo.bestRecordedStreak, 1)

        // Collapse: two consecutive NY days land on one Tokyo day → best decreases 2 → 1.
        let collapse = session(id: "c1", year: 2026, month: 10, day: 27, hour: 22)
            + session(id: "c2", year: 2026, month: 10, day: 28, hour: 1)
        let collapseNow = middayNow
        let collapseNy = summarize(collapse, now: collapseNow, historyStart: history, calendar: ny)
        let collapseTokyo = summarize(collapse, now: collapseNow, historyStart: history, calendar: tokyo)
        XCTAssertEqual(collapseNy.streak, 2)
        XCTAssertEqual(collapseNy.bestRecordedStreak, 2)
        XCTAssertEqual(collapseTokyo.streak, 1) // Tokyo today 10-29; yesterday 10-28 anchors
        XCTAssertEqual(collapseTokyo.bestRecordedStreak, 1)

        // Split: two same-NY-day completions land on consecutive Tokyo days → streak/best 1 → 2.
        let split = session(id: "s1", year: 2026, month: 10, day: 28, hour: 1)
            + session(id: "s2", year: 2026, month: 10, day: 28, hour: 20)
        let splitNow = ny.date(from: DateComponents(year: 2026, month: 10, day: 28, hour: 21, minute: 30))!
        let splitNy = summarize(split, now: splitNow, historyStart: history, calendar: ny)
        let splitTokyo = summarize(split, now: splitNow, historyStart: history, calendar: tokyo)
        XCTAssertEqual(splitNy.streak, 1)
        XCTAssertEqual(splitNy.bestRecordedStreak, 1)
        XCTAssertEqual(splitTokyo.streak, 2)
        XCTAssertEqual(splitTokyo.bestRecordedStreak, 2)
    }

    func testS18_appRestartAfterMissedDayPreservesHistory() {
        let events = completedDays(-4, -3, -2)
        let first = summarize(events)
        let relaunch = summarize(events)
        XCTAssertEqual(first.streak, 0)
        XCTAssertEqual(first.bestRecordedStreak, 3)
        XCTAssertEqual(first.streak, relaunch.streak)
        XCTAssertEqual(first.bestRecordedStreak, relaunch.bestRecordedStreak)
        XCTAssertEqual(relaunch.sessions.filter { $0.outcome == .completed }.count, 3)
    }

    func testS19_forceStopRelaunchSameAsRestart() {
        testS18_appRestartAfterMissedDayPreservesHistory()
    }

    func testS20_uncertainHistoryDoesNotInventStreak() {
        let started = started(dayOffset: 0, timeConfidence: .uncertain(reason: "clock_discontinuity"))
        let result = summarize([started, completed(dayOffset: 0)])
        let invented = result.streak > 0 && result.sessions.contains { $0.outcome == .completed }
        XCTAssertFalse(invented)
        XCTAssertGreaterThanOrEqual(result.bestRecordedStreak, result.streak)
    }

    func testBestNeverLessThanCurrent() {
        let result = summarize(completedDays(-2, -1, 0))
        XCTAssertGreaterThanOrEqual(result.bestRecordedStreak, result.streak)
    }

    func testPureHelpersMatchRequiredRules() {
        let days: Set<Date> = [
            ny.date(byAdding: .day, value: -2, to: today)!,
            ny.date(byAdding: .day, value: -1, to: today)!,
            today,
        ]
        XCTAssertEqual(FlintHistoryAggregator.currentStreak(completedDays: days, today: today,
                                                            historyStart: historyStart, calendar: ny), 3)
        XCTAssertEqual(FlintHistoryAggregator.currentStreak(completedDays: days.subtracting([today]), today: today,
                                                            historyStart: historyStart, calendar: ny), 2)
        XCTAssertEqual(FlintHistoryAggregator.currentStreak(
            completedDays: [ny.date(byAdding: .day, value: -3, to: today)!],
            today: today, historyStart: historyStart, calendar: ny), 0)
        XCTAssertEqual(FlintHistoryAggregator.bestRecordedStreak(completedDays: days,
                                                                historyStart: historyStart, calendar: ny), 3)
    }
}

private extension Array {
    var single: Element {
        precondition(count == 1)
        return self[0]
    }
}
