import XCTest
import FamilyControls
import ManagedSettings
@testable import FlintCore

final class FlintWebRestrictionLifecycleTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }

    private func date(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }
    private var now: Date { date("2026-10-26T23:00:00-04:00") } // Monday

    private func session(endsAt: Date? = nil) -> FlintActiveSession {
        FlintActiveSession(name: "Focus", startedAt: now.addingTimeInterval(-60), endsAt: endsAt,
                           breakLevel: .easy, monitorName: "flint.session")
    }

    private func rule(_ id: String, enabled: Bool = true) -> FlintScheduleRule {
        FlintScheduleRule(id: id, name: id,
                          schedule: FlintSchedule(daysOfWeek: [2], startHour: 22, endHour: 7),
                          enabled: enabled)
    }

    func testLegacyJSONKeepsEveryUserSettingAndOptsSchedulesOut() throws {
        // Exact pre-scope shape, without applyDuringSchedules. Exercise the production loader
        // as well as decoding so a migration cannot silently replace the user's config.
        let legacy = Data("""
        {"enabled":true,"filter":"allowedOnly","blockedDomains":["reddit.com"],
         "allowedDomains":["wikipedia.org","apple.com"],"denyExplicitMedia":true}
        """.utf8)
        let expected = FlintWebRestrictions(enabled: true, filter: .allowedOnly,
                                            blockedDomains: ["reddit.com"],
                                            allowedDomains: ["wikipedia.org", "apple.com"],
                                            denyExplicitMedia: true)
        XCTAssertEqual(try JSONDecoder().decode(FlintWebRestrictions.self, from: legacy), expected)
        let suite = "test.flint.web.legacy.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(legacy, forKey: "flint.webRestrictions")
        XCTAssertEqual(FlintWebRestrictions.load(from: defaults), expected)
        var updated = expected
        updated.applyDuringSchedules = true
        updated.save(to: defaults)
        XCTAssertEqual(FlintWebRestrictions.load(from: defaults), updated)
    }

    func testIndependentScopesExcludeTimeAndOpenLimits() {
        for sessions in [false, true] {
            for schedules in [false, true] {
                let config = FlintWebRestrictions(enabled: sessions, applyDuringSchedules: schedules)
                XCTAssertEqual(FlintWebRestrictionPolicy.shouldApply(to: .session(session()),
                    config: config, now: now), sessions)
                XCTAssertEqual(FlintWebRestrictionPolicy.shouldApply(
                    to: .schedule(rule("r").schedule, enabled: true), config: config,
                    now: now, calendar: calendar), schedules)
                XCTAssertFalse(FlintWebRestrictionPolicy.shouldApply(to: .timeLimit, config: config))
                XCTAssertFalse(FlintWebRestrictionPolicy.shouldApply(to: .openLimit, config: config))
            }
        }
    }

    func testSessionExpiryBoundaryAndAbsentSessionClearOnlySessionWebKeys() {
        let writer = RecordingWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer)
        let config = FlintWebRestrictions(enabled: true)
        writer.web["flint.rule-a"] = config
        lifecycle.reconcileSession(session(endsAt: now.addingTimeInterval(1)), config: config, now: now)
        XCTAssertEqual(writer.web["flint"], config)
        lifecycle.reconcileSession(session(endsAt: now), config: config, now: now)
        XCTAssertNil(writer.web["flint"])
        lifecycle.reconcileSession(nil, config: config, now: now)
        XCTAssertEqual(writer.web["flint.rule-a"], config)
        XCTAssertEqual(writer.cleared, ["flint", "flint"])
    }

    func testSessionAndTwoRulesOverlapAndEndIndependently() {
        let writer = RecordingWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer)
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        let rules = [rule("sleep.night"), rule("schedule")]
        lifecycle.reconcile(activeSession: session(), rules: rules, config: config, now: now, calendar: calendar)
        XCTAssertEqual(Set(writer.web.keys), ["flint", "flint.sleep.night", "flint.schedule"])

        lifecycle.reconcileSession(nil, config: config, now: now)
        XCTAssertEqual(Set(writer.web.keys), ["flint.sleep.night", "flint.schedule"])
        lifecycle.reconcileSession(session(), config: config, now: now)
        lifecycle.reconcileRule(rule("schedule", enabled: false), config: config, now: now, calendar: calendar)
        XCTAssertEqual(Set(writer.web.keys), ["flint", "flint.sleep.night"])
        lifecycle.reconcileRule(rules[0], config: config,
                                now: date("2026-10-27T07:00:00-04:00"), calendar: calendar)
        XCTAssertEqual(Set(writer.web.keys), ["flint"])
    }

    func testDisableBothScopesClearsAllKnownOwnersAndLegacy() {
        let writer = RecordingWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer)
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        writer.web["flint.web"] = config
        lifecycle.reconcile(activeSession: session(), rules: [rule("a"), rule("b")],
                            config: config, now: now, calendar: calendar)
        XCTAssertNil(writer.web["flint.web"])
        lifecycle.reconcile(activeSession: session(), rules: [rule("a"), rule("b")],
                            config: .default, now: now, calendar: calendar)
        XCTAssertTrue(writer.web.isEmpty)
        XCTAssertEqual(Set(writer.cleared), ["flint", "flint.a", "flint.b", "flint.web"])
    }

    func testEditingActiveCausesReappliesLatestConfigAndScheduleOnlyWorks() {
        let writer = RecordingWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer)
        var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        lifecycle.reconcile(activeSession: session(), rules: [rule("a")], config: config,
                            now: now, calendar: calendar)
        config.filter = .allowedOnly
        config.allowedDomains = ["example.com"]
        config.denyExplicitMedia = true
        lifecycle.reconcile(activeSession: session(), rules: [rule("a")], config: config,
                            now: now, calendar: calendar)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertEqual(writer.web["flint.a"], config)
        XCTAssertEqual(writer.applied, ["flint", "flint.a", "flint", "flint.a"])
        config.enabled = false
        lifecycle.reconcile(activeSession: session(), rules: [rule("a")], config: config,
                            now: now, calendar: calendar)
        XCTAssertNil(writer.web["flint"])
        XCTAssertEqual(writer.web["flint.a"], config)
    }

    func testOutsideWindowAndDisabledRulesClearStaleWebKeys() {
        let writer = RecordingWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer)
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        writer.web["flint.a"] = config
        lifecycle.reconcileRule(rule("a"), config: config,
                                now: date("2026-10-26T21:59:00-04:00"), calendar: calendar)
        XCTAssertNil(writer.web["flint.a"])
        lifecycle.reconcileRule(rule("a", enabled: false), config: config, now: now, calendar: calendar)
        XCTAssertEqual(writer.cleared, ["flint.a", "flint.a"])
        XCTAssertTrue(writer.applied.isEmpty)
    }

    func testSameDayWindowIncludesStartExcludesEndAndGatesWeekday() {
        let schedule = FlintSchedule(daysOfWeek: [2], startHour: 9, startMinute: 30, endHour: 17)
        for (instant, expected) in [
            ("2026-10-26T09:29:59-04:00", false), ("2026-10-26T09:30:00-04:00", true),
            ("2026-10-26T16:59:59-04:00", true), ("2026-10-26T17:00:00-04:00", false),
            ("2026-10-27T10:00:00-04:00", false)
        ] {
            XCTAssertEqual(schedule.isWindowActive(at: date(instant), calendar: calendar), expected, instant)
        }
    }

    func testOvernightUsesStartWeekdayIncludingSaturdayToSunday() {
        let schedule = FlintSchedule(daysOfWeek: [7], startHour: 22, endHour: 7)
        for (instant, expected) in [
            ("2026-10-24T21:59:00-04:00", false), ("2026-10-24T22:00:00-04:00", true),
            ("2026-10-25T06:59:00-04:00", true), ("2026-10-25T07:00:00-04:00", false),
            ("2026-10-25T22:00:00-04:00", false), ("2026-10-26T06:00:00-04:00", false)
        ] {
            XCTAssertEqual(schedule.isWindowActive(at: date(instant), calendar: calendar), expected, instant)
        }
    }

    func testDSTWindowsUseWallClockMinutesAndStartDay() {
        let schedule = FlintSchedule(daysOfWeek: [7], startHour: 22, endHour: 7)
        for instant in ["2026-03-08T01:59:00-05:00", "2026-03-08T03:00:00-04:00",
                        "2026-03-08T06:59:00-04:00", "2026-11-01T01:30:00-04:00",
                        "2026-11-01T01:30:00-05:00", "2026-11-01T06:59:00-05:00"] {
            XCTAssertTrue(schedule.isWindowActive(at: date(instant), calendar: calendar), instant)
        }
        for instant in ["2026-03-08T07:00:00-04:00", "2026-11-01T07:00:00-05:00"] {
            XCTAssertFalse(schedule.isWindowActive(at: date(instant), calendar: calendar), instant)
        }
    }

    func testEveryDayAndInvalidOrEmptyWindows() {
        XCTAssertTrue(FlintSchedule(startHour: 22, endHour: 7).isWindowActive(at: now, calendar: calendar))
        for schedule in [FlintSchedule(startHour: 7, endHour: 7),
                         FlintSchedule(startHour: Int.max, endHour: 7),
                         FlintSchedule(daysOfWeek: [0], startHour: 22, endHour: 7)] {
            XCTAssertFalse(schedule.isWindowActive(at: now, calendar: calendar))
        }
    }

    func testAllowedOnlyBlocksAllExceptNormalizedAllowedDomains() {
        let config = FlintWebRestrictions(filter: .allowedOnly, allowedDomains: ["https://www.Example.com/x"])
        XCTAssertEqual(FlintWebRestrictionStore.filterPolicy(for: config),
                       .all(except: [WebDomain(domain: "example.com")]))
        XCTAssertEqual(FlintWebRestrictionStore.filterPolicy(for: FlintWebRestrictions(filter: .allowedOnly)),
                       .all(except: []))
    }

    func testAdultFilterKeepsNormalizedBlockAndAllowLists() {
        let config = FlintWebRestrictions(blockedDomains: ["www.example.com"], allowedDomains: ["apple.com"])
        XCTAssertEqual(FlintWebRestrictionStore.filterPolicy(for: config),
                       .auto([WebDomain(domain: "example.com")], except: [WebDomain(domain: "apple.com")]))
    }
}

private final class RecordingWebWriter: FlintWebRestrictionWriting {
    var web: [String: FlintWebRestrictions] = [:]
    var applied: [String] = []
    var cleared: [String] = []

    func apply(_ config: FlintWebRestrictions, toStore storeName: String) {
        applied.append(storeName)
        web[storeName] = config
    }

    func clear(store storeName: String) {
        cleared.append(storeName)
        web.removeValue(forKey: storeName)
    }
}
