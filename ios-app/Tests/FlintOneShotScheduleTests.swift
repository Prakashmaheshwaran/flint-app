import XCTest
import FamilyControls
import DeviceActivity
@testable import FlintCore
@testable import Flint

/// Date-specific one-shot future sessions (ledger 1.3/1.5, ADR-012 PC-13/14/54/55/56/81).
/// All progression is injected through the shared `RuleHarness`; nothing here claims to prove
/// physical Screen Time delivery.
final class FlintOneShotScheduleTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!
    private let newYork = TimeZone(identifier: "America/New_York")!

    private func date(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    private func calendar(_ zone: TimeZone) -> Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = zone
        return result
    }

    // MARK: - Helpers

    /// A dated window `daysAhead` days after `now`'s day, authored as local wall times in `zone`.
    private func offsetWindow(daysAhead: Int, from now: Date, startMinute: Int = 9 * 60,
                              endMinute: Int = 17 * 60, zone: TimeZone? = nil) -> FlintRuleWindow {
        let zone = zone ?? utc
        let day = calendar(zone).date(byAdding: .day, value: daysAhead,
                                      to: calendar(zone).startOfDay(for: now))!
        let parts = calendar(zone).dateComponents([.year, .month, .day], from: day)
        return FlintOneShotSchedule.window(year: parts.year!, month: parts.month!, day: parts.day!,
            startMinute: startMinute, endMinute: endMinute, zone: zone)!
    }

    /// An allow-list rule, like `RuleHarness.schedule`: the arming path tolerates an empty allow
    /// list (it is a saved rule, not a new edit), so a reload can attempt the real registration.
    private func oneShotRule(_ window: FlintRuleWindow, tier: BreakLevel = .easy,
                             zone: TimeZone? = nil) -> FlintScheduleRule {
        let zone = zone ?? utc
        return FlintScheduleRule(id: "oneshot", name: "Interview",
            schedule: FlintOneShotSchedule.mirrorSchedule(for: window, zone: zone),
            breakLevel: tier, allowListMode: true,
            protection: .init(timeZoneID: zone.identifier), oneShot: window)
    }

    private func replacing(_ rule: FlintScheduleRule, window: FlintRuleWindow) -> FlintScheduleRule {
        var next = rule
        next.oneShot = window
        next.schedule = FlintOneShotSchedule.mirrorSchedule(for: window,
            zone: TimeZone(identifier: rule.protection?.timeZoneID ?? "UTC") ?? .current)
        return next
    }

    /// Repeats a mutation once with the confirmation ticket it returned, like the editor's flow.
    private func confirmed(_ request: (FlintRuleConfirmation?) -> FlintRuleMutationResult)
        -> FlintRuleMutationResult {
        let first = request(nil)
        guard case let .confirmationRequired(ticket, _) = first else { return first }
        return request(ticket)
    }

    // MARK: - (a) Authoring → registration, non-repeating, saved zone, beyond 24 h

    func testAuthoredOneShotThreeDaysAheadRegistersNonRepeatingSavedZoneComponents() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let window = offsetWindow(daysAhead: 3, from: h.now) // 2026-10-31 09:00–17:00 UTC
        XCTAssertGreaterThan(window.start.timeIntervalSince(h.now), 24 * 3600,
                             "a one-time session may start any distance ahead")
        let arbiter = FlintRuleArbiter(environment: h.environment)
        XCTAssertEqual(arbiter.upsert(oneShotRule(window), creating: true), .applied)

        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(saved.oneShot, window)
        XCTAssertEqual(saved.kind, .oneShotSchedule)
        XCTAssertEqual(saved.schedule.daysOfWeek, [7], "the mirror carries the start's weekday only")

        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)
        let natively = try XCTUnwrap(h.center.schedule(for: .init(saved.monitorName)))
        XCTAssertFalse(natively.repeats, "a one-time session is registered once, never weekly")
        XCTAssertEqual(natively.intervalStart.year, 2026)
        XCTAssertEqual(natively.intervalStart.month, 10)
        XCTAssertEqual(natively.intervalStart.day, 31)
        XCTAssertEqual(natively.intervalStart.hour, 9)
        XCTAssertEqual(natively.intervalStart.minute, 0)
        XCTAssertEqual(natively.intervalEnd.hour, 17)
        XCTAssertEqual(natively.intervalEnd.minute, 0)
        XCTAssertEqual(natively.intervalStart.timeZone?.secondsFromGMT(for: window.start), 0,
                       "components are resolved in the rule's saved zone")
        XCTAssertEqual(natively.intervalEnd.timeZone?.secondsFromGMT(for: window.end), 0)
        XCTAssertEqual(h.reports["schedules"]?.armed, 1)
        XCTAssertEqual(h.center.starts.filter { $0 == saved.monitorName }.count, 1,
                       "a reload must not restart an unchanged dated registration")
    }

    // MARK: - (b) Start/end callbacks, terminal state, PC-81

    func testOneShotAppliesAtTrustedStartClearsAtTrustedEndAndNeverReplaysPC81() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let window = offsetWindow(daysAhead: 2, from: h.now)
        let draft = oneShotRule(window, tier: .hardcore)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .confirmationRequired(ticket, raisesTier) = arbiter.upsert(draft, creating: true) else {
            return XCTFail("a new Hardcore session always discloses its window")
        }
        XCTAssertTrue(raisesTier)
        XCTAssertEqual(arbiter.upsert(draft, confirmation: ticket, creating: true), .applied)

        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)
        XCTAssertNil(saved.occurrence(at: h.now, clock: h.clock), "nothing is active before the start")
        XCTAssertEqual(saved.oneShotPhase(at: h.now, clock: h.clock)?.isPending, true)

        let monitor = FlintRuleMonitor(environment: h.environment)
        h.advance(to: window.start)
        _ = monitor.handle(.start, activity: saved.monitorName)
        XCTAssertEqual(h.appliedSchedules.map(\.id), [saved.id], "the start callback shields the selection")

        h.advance(to: window.end)
        XCTAssertNil(h.load(FlintScheduleRule.self).first?.occurrence(at: h.now, clock: h.clock))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.oneShotPhase(at: h.now, clock: h.clock)?.isFinished,
                       true)
        _ = monitor.handle(.end, activity: saved.monitorName)
        XCTAssertTrue(h.cleared.contains(saved.storeName), "the trusted end releases the shield")

        // Terminal: reload after the end stops monitoring and frees the pool slot.
        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)
        let clears = h.cleared.count
        XCTAssertNil(h.center.held[.init(saved.monitorName)])
        XCTAssertTrue(h.center.stops.flatMap { $0 }.contains(saved.monitorName))

        // PC-81: a week later (and a week plus the start time) produces no occurrence and no arm.
        h.advance(7 * 86400)
        XCTAssertNil(h.load(FlintScheduleRule.self).first?.occurrence(at: h.now, clock: h.clock),
                     "an ended one-shot never repeats on its weekday")
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.oneShotPhase(at: h.now, clock: h.clock)?.isFinished,
                       true)
        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)
        XCTAssertEqual(h.center.starts.filter { $0 == saved.monitorName }.count, 1,
                       "a finished one-shot is never registered again")
        XCTAssertEqual(h.cleared.count, clears, "an already released store is not cleared twice")
        XCTAssertTrue(h.appliedSchedules.count == 1, "and it never shields again")
    }

    // MARK: - (c) DST endpoint policy (ADR-012 §9)

    func testDSTGapAndFoldResolveWithTheSharedEndpointPolicy() throws {
        // Spring forward (2026-03-08): 01:00 exists, 02:30 does not and moves past the gap.
        let spring = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 3, day: 8,
            startMinute: 60, endMinute: 150, zone: newYork))
        XCTAssertEqual(spring.start, date("2026-03-08T06:00:00Z"))
        XCTAssertEqual(spring.end, date("2026-03-08T07:30:00Z"))
        // A start inside the missing hour also moves forward, never backwards.
        let gapStart = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 3, day: 8,
            startMinute: 150, endMinute: 240, zone: newYork))
        XCTAssertEqual(gapStart.start, date("2026-03-08T07:30:00Z"))
        XCTAssertEqual(gapStart.end, date("2026-03-08T08:00:00Z"))
        XCTAssertNil(FlintOneShotSchedule.issue(for: spring, now: date("2026-03-01T00:00:00Z")))

        // Fall back (2026-11-01): ambiguous start takes the earlier instant, end the later one.
        let fall = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 11, day: 1,
            startMinute: 60, endMinute: 120, zone: newYork))
        XCTAssertEqual(fall.start, date("2026-11-01T05:00:00Z"), "01:00 EDT")
        XCTAssertEqual(fall.end, date("2026-11-01T07:00:00Z"), "02:00 EST")

        // An overnight window ends on the next local day, still under 24 h.
        let overnight = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 10, day: 31,
            startMinute: 22 * 60, endMinute: 2 * 60, zone: utc))
        XCTAssertEqual(overnight.start, date("2026-10-31T22:00:00Z"))
        XCTAssertEqual(overnight.end, date("2026-11-01T02:00:00Z"))
        XCTAssertEqual(FlintOneShotSchedule.mirrorSchedule(for: overnight, zone: utc),
                       .init(daysOfWeek: [7], startHour: 22, startMinute: 0, endHour: 2, endMinute: 0))
    }

    // MARK: - (d) Clock jumps and a device time-zone change

    func testWallJumpForwardCannotStartOneShotAndPrematureStartRepairsTheCarrier() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = date("2026-10-28T12:00:00Z")
        let window = offsetWindow(daysAhead: 0, from: h.now, startMinute: 18 * 60, endMinute: 20 * 60)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        XCTAssertEqual(arbiter.upsert(oneShotRule(window), creating: true), .applied)
        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)

        // Same boot: only the editable wall clock moves past the start (no elapsed time).
        h.now = window.start.addingTimeInterval(30 * 60)
        let observed = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNil(observed.occurrence(at: h.now, clock: h.clock),
                     "trusted time, not the wall date, decides activation")
        XCTAssertEqual(observed.oneShotPhase(at: h.now, clock: h.clock)?.isActive, false)
        XCTAssertEqual(observed.oneShotPhase(at: h.now, clock: h.clock)?.isPending, true)

        let monitor = FlintRuleMonitor(environment: h.environment)
        let starts = h.center.starts.count
        _ = monitor.handle(.start, activity: saved.monitorName)
        XCTAssertTrue(h.appliedSchedules.isEmpty, "a premature start callback never shields")
        XCTAssertEqual(h.center.starts.count, starts + 1, "the carrier is repaired to the trusted boundary")
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.reports["ruleTime.schedules"]?.failures.contains {
            $0.reason.contains("prematureStart")
        } == true)

        // The repaired carrier still fires at the logical start: advancing trusted time activates it.
        h.now = date("2026-10-28T12:00:00Z")
        h.advance(to: window.start)
        _ = monitor.handle(.start, activity: saved.monitorName)
        XCTAssertEqual(h.appliedSchedules.map(\.id), [saved.id])
    }

    func testWallMovedBackAfterStartKeepsTrustedActivationAndEnd() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = date("2026-10-28T12:00:00Z")
        let window = offsetWindow(daysAhead: 0, from: h.now, startMinute: 13 * 60, endMinute: 15 * 60)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        XCTAssertEqual(arbiter.upsert(oneShotRule(window), creating: true), .applied)
        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)

        h.advance(to: window.start.addingTimeInterval(1800))
        _ = FlintRuleMonitor(environment: h.environment).handle(.start, activity: saved.monitorName)
        XCTAssertEqual(h.appliedSchedules.map(\.id), [saved.id])

        // A device time-zone change relabels nothing: same saved window, same native components.
        var tokyo = h.clock
        tokyo.calendar = {
            var c = Calendar(identifier: .gregorian)
            c.timeZone = TimeZone(identifier: "Asia/Tokyo")!
            return c
        }
        let active = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(active.occurrence(at: h.now, clock: tokyo), window)
        XCTAssertEqual(active.oneShotPhase(at: h.now, clock: tokyo)?.window, window)
        let request = active.registration(clock: tokyo)
        XCTAssertEqual(request.schedule.intervalStart.hour, 13)
        XCTAssertEqual(request.schedule.intervalStart.timeZone?.secondsFromGMT(for: window.start), 0)

        // Wall moves back half an hour with no elapsed time: trusted activation is unchanged.
        h.now = window.start
        let moved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNotNil(moved.occurrence(at: h.now, clock: h.clock))
        XCTAssertEqual(moved.oneShotPhase(at: h.now, clock: h.clock)?.isActive, true)
        XCTAssertEqual(FlintRuleOccurrenceClock.date(for: moved, clock: h.clock),
                       window.start.addingTimeInterval(1800),
                       "the trusted timeline keeps its elapsed time")

        // The trusted end still terminates it, and a later wall reading cannot replay it.
        h.advance(to: window.end)
        _ = FlintRuleMonitor(environment: h.environment).handle(.end, activity: saved.monitorName)
        XCTAssertTrue(h.cleared.contains(saved.storeName))
        h.now = window.start.addingTimeInterval(600)
        let finished = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNil(finished.occurrence(at: h.now, clock: h.clock), "an ended one-shot never replays")
        XCTAssertEqual(finished.oneShotPhase(at: h.now, clock: h.clock)?.isFinished, true)
    }

    // MARK: - (e) Validation

    func testValidationRejectsPastStartShortOverflowAndUnresolvable() throws {
        let now = date("2026-10-28T12:00:45Z")
        let past = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 10, day: 27,
            startMinute: 9 * 60, endMinute: 17 * 60, zone: utc))
        XCTAssertEqual(FlintOneShotSchedule.issue(for: past, now: now), .startInPast)
        // 60 s grace: a start less than a minute behind is still authorable.
        let justStarted = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 10, day: 28,
            startMinute: 12 * 60, endMinute: 13 * 60, zone: utc))
        XCTAssertNil(FlintOneShotSchedule.issue(for: justStarted, now: now))
        XCTAssertEqual(FlintOneShotSchedule.issue(for: justStarted, now: now.addingTimeInterval(120)),
                       .startInPast)

        // DeviceActivity's 15-minute floor, in real minutes.
        let short = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 10, day: 30,
            startMinute: 9 * 60, endMinute: 9 * 60 + 10, zone: utc))
        XCTAssertEqual(FlintOneShotSchedule.issue(for: short, now: now), .tooShort)
        let exactlyFloor = try XCTUnwrap(FlintOneShotSchedule.window(year: 2026, month: 10, day: 30,
            startMinute: 9 * 60, endMinute: 9 * 60 + FlintSchedule.minimumWindowMinutes, zone: utc))
        XCTAssertNil(FlintOneShotSchedule.issue(for: exactlyFloor, now: now))

        // Equal times mean "the next day", which is a day long: the resolver refuses it, so a
        // one-shot can never be saved as a 24 h+ block.
        XCTAssertNil(FlintOneShotSchedule.window(year: 2026, month: 10, day: 30,
            startMinute: 9 * 60, endMinute: 9 * 60, zone: utc))
        XCTAssertNil(FlintOneShotSchedule.window(year: 2026, month: 2, day: 30,
            startMinute: 9 * 60, endMinute: 17 * 60, zone: utc), "Feb 30 is not a date")
        // A decoded window outside the supported span is rejected by the authoring gate.
        let dayLong = FlintRuleWindow(start: now, end: now.addingTimeInterval(25 * 3600))
        XCTAssertEqual(FlintOneShotSchedule.durationIssue(for: dayLong), .tooLong)
        XCTAssertEqual(FlintOneShotSchedule.durationIssue(for: .init(start: now, end: now)), .unresolvable)

        let h = try RuleHarness(); defer { h.clean() }
        h.now = now
        let arbiter = FlintRuleArbiter(environment: h.environment)
        // The commit gate judges every NEW or CHANGED one-time window…
        XCTAssertEqual(arbiter.upsert(oneShotRule(past), creating: true),
                       .invalid(FlintOneShotIssue.startInPast.message))
        // A sub-floor window is already rejected by the weekly mirror it carries, which reports
        // the same 15-minute DeviceActivity limit (ADR-012 §2); either way it cannot be saved.
        XCTAssertEqual(arbiter.upsert(oneShotRule(short), creating: true),
                       .invalid(FlintScheduleIssue.windowTooShort.message))
        XCTAssertEqual(arbiter.upsert(oneShotRule(dayLong), creating: true),
                       .invalid(FlintOneShotIssue.tooLong.message))
        XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty)

        // …but a STORED window is never re-judged: an existing one-shot shorter than today's
        // authoring floor keeps its own rules and can still be edited (ADR-012 §12).
        let storedWindow = FlintRuleWindow(start: now.addingTimeInterval(600),
                                           end: now.addingTimeInterval(600 + 3 * 60))
        let legacy = FlintScheduleRule(id: "legacy", name: "Old one-time",
            schedule: .init(daysOfWeek: [4], startHour: 9, endHour: 17), breakLevel: .easy,
            allowListMode: true, protection: .init(timeZoneID: utc.identifier), oneShot: storedWindow)
        XCTAssertNil(legacy.validationIssue, "no retroactive invalidation of a saved record")
        var renamed = h.install(legacy)
        renamed.name = "Renamed"
        XCTAssertEqual(arbiter.upsert(renamed), .applied)
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.name, "Renamed")
    }

    // MARK: - (f) ADR-012 edit rights

    func testPendingHardcoreOneShotDateChangeIsOrdinarySaveAndActiveEditsFollowTheGatePC13PC14() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = date("2026-10-28T12:00:00Z")
        let window = offsetWindow(daysAhead: 3, from: h.now)
        let nextWindow = offsetWindow(daysAhead: 4, from: h.now)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let draft = oneShotRule(window, tier: .hardcore)
        guard case let .confirmationRequired(ticket, raisesTier) = arbiter.upsert(draft, creating: true) else {
            return XCTFail()
        }
        XCTAssertTrue(raisesTier)
        XCTAssertEqual(arbiter.upsert(draft, confirmation: ticket, creating: true), .applied)

        // Pending: re-dating is an ordinary validated save (PC-13).
        let pending = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(pending.oneShotPhase(at: h.now, clock: h.clock)?.isPending, true)
        XCTAssertEqual(arbiter.upsert(replacing(pending, window: nextWindow)), .applied)
        let moved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(moved.oneShot, nextWindow)

        // Active: extending the end strengthens and applies now (with the ordinary disclosure).
        h.advance(to: nextWindow.start.addingTimeInterval(3600))
        let active = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(active.oneShotPhase(at: h.now, clock: h.clock)?.isActive, true)
        let longer = FlintRuleWindow(start: nextWindow.start, end: nextWindow.end.addingTimeInterval(3600))
        guard case let .confirmationRequired(strengthen, raises) = arbiter.upsert(replacing(active, window: longer)) else {
            return XCTFail("an extension of an active Hardcore one-shot needs the ordinary disclosure")
        }
        XCTAssertFalse(raises)
        XCTAssertEqual(arbiter.upsert(replacing(active, window: longer), confirmation: strengthen), .applied)
        let extended = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(extended.oneShot, longer)
        XCTAssertEqual(extended.oneShotPhase(at: h.now, clock: h.clock)?.window, longer)

        // Active: shortening is deferred, never applied to the running commitment.
        let shorter = FlintRuleWindow(start: longer.start, end: longer.end.addingTimeInterval(-3600))
        guard case let .deferRequired(ticket2, end) = arbiter.upsert(replacing(extended, window: shorter)) else {
            return XCTFail("shortening an active Hardcore one-shot must defer")
        }
        XCTAssertEqual(end, longer.end)
        XCTAssertEqual(arbiter.upsert(replacing(extended, window: shorter), confirmation: ticket2),
                       .queued(until: end))
        let retained = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(retained.oneShot, longer, "the running window is unchanged until its trusted end")
        XCTAssertNotNil(retained.protection?.queuedChange)
        XCTAssertEqual(retained.oneShotPhase(at: h.now, clock: h.clock)?.window, longer)

        // A second edit behind the queue is refused: only a strengthen may pass it.
        let movedDate = offsetWindow(daysAhead: 6, from: h.now)
        guard case .invalid = arbiter.upsert(replacing(retained, window: movedDate)) else {
            return XCTFail("a rule with a queued change only accepts a strengthen")
        }

        // At the trusted end the queued shortening applies without re-registering the now-past
        // non-repeating interval, and nothing is left retrying (PC-14).
        let startsBefore = h.center.starts
        h.advance(to: longer.end)
        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)
        let applied = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(applied.oneShot, shorter, "the accepted shortening is persisted at the trusted end")
        XCTAssertNil(applied.protection?.queuedChange, "the queue is consumed, not stuck retrying")
        XCTAssertEqual(applied.oneShotPhase(at: h.now, clock: h.clock)?.isFinished, true)
        XCTAssertEqual(h.center.starts, startsBefore, "a finished window's past interval is never registered")
        XCTAssertNil(h.center.held[.init(applied.monitorName)])
        XCTAssertFalse(h.reports["schedules"]?.failures.contains { $0.reason.contains("couldn't be applied") } ?? false)
    }

    func testFinishedOneShotIsHistoryNotADegradedCause() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let window = offsetWindow(daysAhead: 1, from: h.now)
        XCTAssertEqual(FlintRuleArbiter(environment: h.environment).upsert(oneShotRule(window), creating: true), .applied)
        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        // Pending and unregistered: a real cause whose missing registration must stay visible.
        let pending = FlintProtectionStatusBuilder.RuleInput.schedule(saved, armed: false, observed: false,
            now: h.now, calendar: calendar(utc), clock: h.clock)
        XCTAssertTrue(pending.enabled)
        // Finished: deliberately unregistered, so it must not publish registration_* reasons.
        h.advance(to: window.end.addingTimeInterval(60))
        let finished = FlintProtectionStatusBuilder.RuleInput.schedule(saved, armed: false, observed: false,
            now: h.now, calendar: calendar(utc), clock: h.clock)
        XCTAssertFalse(finished.enabled)
        XCTAssertNil(finished.currentWindow); XCTAssertNil(finished.nextWindow)
        let snapshot = FlintProtectionStatusBuilder.make(.init(now: h.now, quotaCalendar: calendar(utc),
            rules: [finished], authorized: true, pass: .init(eligibility: .available),
            timeIntegrity: nil, timeIntegrityApplies: false))
        XCTAssertNotEqual(snapshot.state, .degraded)
        XCTAssertTrue(snapshot.degradedReasons.isEmpty)
        // A legacy record without versioned metadata is still never projected weekly.
        var legacy = saved; legacy.protection = nil
        let legacyInput = FlintProtectionStatusBuilder.RuleInput.schedule(legacy, armed: false, observed: false,
            now: h.now.addingTimeInterval(7 * 86400 - 3600), calendar: calendar(utc), clock: h.clock)
        XCTAssertFalse(legacyInput.enabled)
        XCTAssertNil(legacyInput.nextWindow, "a one-shot's mirror weekday never yields a next week")
    }

    /// iOS keeps a trusted timeline per rule: a brand-new rule's anchor is born at the wall clock
    /// it was authored against, and its registration uses that same reading. Authoring after a
    /// same-boot wall rewind therefore activates after the real elapsed time the user picked.
    func testNewOneShotAuthoredAfterWallRewindStartsAfterTheChosenRealDelay() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = date("2026-10-28T12:00:00Z")
        let arbiter = FlintRuleArbiter(environment: h.environment)
        h.now.addTimeInterval(-3 * 3600) // wall dragged back three hours; monotonic unchanged
        let window = FlintRuleWindow(start: h.now.addingTimeInterval(30 * 60), end: h.now.addingTimeInterval(90 * 60))
        XCTAssertEqual(arbiter.upsert(oneShotRule(window), creating: true), .applied)
        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(saved.oneShotPhase(at: h.now, clock: h.clock)?.isPending, true)
        XCTAssertTrue(FlintSchedulesController(environment: h.environment).reload().isEmpty)
        let natively = try XCTUnwrap(h.center.schedule(for: .init(saved.monitorName)))
        XCTAssertEqual(natively.intervalStart.hour, calendar(utc).component(.hour, from: window.start))
        XCTAssertEqual(natively.intervalStart.minute, calendar(utc).component(.minute, from: window.start))
        h.advance(30 * 60)
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.oneShotPhase(at: h.now, clock: h.clock)?.isActive, true,
                       "it starts 30 real minutes after authoring, not three hours late or never")
    }

    func testPendingOneShotCanBeToggledOffWhileActiveHardcoreCannotPC13PC14() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = date("2026-10-28T12:00:00Z")
        let window = offsetWindow(daysAhead: 2, from: h.now)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let draft = oneShotRule(window, tier: .hardcore)
        guard case let .confirmationRequired(ticket, _) = arbiter.upsert(draft, creating: true) else {
            return XCTFail()
        }
        XCTAssertEqual(arbiter.upsert(draft, confirmation: ticket, creating: true), .applied)

        let pending = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(confirmed { arbiter.setEnabled(FlintScheduleRule.self, id: pending.id, enabled: false,
            revision: pending.revision, confirmation: $0) }, .applied)
        let disabled = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertFalse(disabled.enabled)
        XCTAssertEqual(disabled.oneShotPhase(at: h.now, clock: h.clock)?.isPending, true,
                       "a switched-off future session stays pending, never active")
        XCTAssertEqual(confirmed { arbiter.setEnabled(FlintScheduleRule.self, id: disabled.id, enabled: true,
            revision: disabled.revision, confirmation: $0) }, .applied)

        // Once it is running, turning it off is an active-Hardcore weakening.
        h.advance(to: window.start.addingTimeInterval(600))
        let active = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: active.id,
            enabled: false, revision: active.revision, confirmation: nil) else {
            return XCTFail("an active Hardcore one-shot cannot be turned off now")
        }
        XCTAssertTrue(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled)
    }

    // MARK: - (g) Registration refusal is surfaced

    func testRefusedOneShotRegistrationIsReportedAsAnArmFailure() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = date("2026-10-28T12:00:00Z")
        let window = offsetWindow(daysAhead: 3, from: h.now)
        let installed = h.install(oneShotRule(window))
        let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(saved.monitorName, installed.monitorName)

        // Simulate an exhausted DeviceActivity pool: the registration is gone and refused.
        h.center.held.removeValue(forKey: .init(saved.monitorName))
        h.center.refused = [saved.monitorName]
        let failures = FlintSchedulesController(environment: h.environment).reload()
        XCTAssertEqual(failures.count, 1)
        XCTAssertEqual(failures.first?.ruleID, saved.id)
        XCTAssertNil(failures.first?.issue, "nothing client-side is wrong with this window")
        XCTAssertEqual(failures.first?.registrationReason?.contains("excessive"), true,
                       "the OS refusal is surfaced, not swallowed")
        XCTAssertEqual(h.reports["schedules"]?.failures.contains {
            $0.activityName == saved.monitorName
        }, true)
        XCTAssertTrue(h.center.stops.isEmpty, "a refused void cannot retire saved protection")
    }

    // MARK: - (h) Display never treats a one-shot as weekly

    func testOneShotIsCurrentOnlyInsideItsOwnWindowNeverOnItsWeekday() throws {
        let now = Date()
        // A mirror that matches every day all day: only `oneShot` may decide activation.
        let everyDay = FlintSchedule(startHour: 0, endHour: 23, endMinute: 59)
        let zoneID = TimeZone.current.identifier

        let active = FlintScheduleRule(id: "active", name: "Interview", schedule: everyDay,
            protection: .init(timeZoneID: zoneID),
            oneShot: .init(start: now.addingTimeInterval(-600), end: now.addingTimeInterval(1800)))
        XCTAssertEqual(FlintTodaySchedule.current(in: [active], at: now)?.id, "active")
        XCTAssertNil(FlintTodaySchedule.next(in: [active], at: now))

        // Ended ten minutes ago: not current, not next, and shown as finished.
        let ended = FlintScheduleRule(id: "ended", name: "Call", schedule: everyDay,
            protection: .init(timeZoneID: zoneID),
            oneShot: .init(start: now.addingTimeInterval(-3600), end: now.addingTimeInterval(-600)))
        XCTAssertNil(FlintTodaySchedule.current(in: [ended], at: now))
        XCTAssertNil(FlintTodaySchedule.next(in: [ended], at: now))
        XCTAssertEqual(ended.oneShotPhase(at: now)?.isFinished, true)
        XCTAssertNil(ended.endsAtToday(now: now), "a dated window never rolls to another day")

        // A session starting tomorrow is not "next today", whatever its mirror says.
        let tomorrow = Calendar.current.date(byAdding: .day, value: 1,
            to: Calendar.current.startOfDay(for: now))!
        let future = FlintScheduleRule(id: "future", name: "Plan", schedule: everyDay,
            protection: .init(timeZoneID: zoneID),
            oneShot: .init(start: tomorrow.addingTimeInterval(9 * 3600),
                           end: tomorrow.addingTimeInterval(10 * 3600)))
        XCTAssertNil(FlintTodaySchedule.current(in: [future], at: now))
        XCTAssertNil(FlintTodaySchedule.next(in: [future], at: now))

        // A recurring rule keeps its weekday behaviour.
        let weekly = FlintScheduleRule(id: "weekly", name: "Work", schedule: everyDay,
            protection: .init(timeZoneID: zoneID))
        let noon = Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: now)!
        XCTAssertEqual(FlintTodaySchedule.current(in: [weekly], at: noon)?.id, "weekly")
    }
}
