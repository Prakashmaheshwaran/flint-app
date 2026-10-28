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
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults), expected)
        var updated = expected
        updated.applyDuringSchedules = true
        updated.save(to: defaults)
        XCTAssertEqual(FlintWebRestrictions.load(from: defaults), updated)
    }

    func testUnreadableConfigPreservesLastGoodKeysWhileMissingKeyIsOptOut() throws {
        let suite = "test.flint.web.unavailable.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults), .default)
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: nil))
        let config = FlintWebRestrictions(enabled: true)
        config.save(to: defaults)
        let writer = RecordingWebWriter()
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, configuration: {
            FlintWebRestrictions.loadForEnforcement(from: defaults)
        })
        adapter.reconcile(session(), now: now)
        for malformed in [Data("{".utf8), Data("{\"enabled\":true}".utf8)] {
            defaults.set(malformed, forKey: "flint.webRestrictions")
            adapter.reconcile(session(), now: now)
            XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults))
            XCTAssertEqual(writer.web["flint"], config)
        }
        defaults.set("wrong storage type", forKey: "flint.webRestrictions")
        adapter.reconcile(session(), now: now)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertTrue(writer.cleared.isEmpty)
        defaults.removeObject(forKey: "flint.webRestrictions")
        adapter.reconcile(session(), now: now)
        XCTAssertNil(writer.web["flint"])
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

    func testRuleWriterRequiresScheduleScopeRegardlessOfSessionScope() {
        for sessions in [false, true] {
            for schedules in [false, true] {
                let writer = RecordingWebWriter()
                let config = FlintWebRestrictions(enabled: sessions, applyDuringSchedules: schedules)
                FlintWebRestrictionLifecycle(writer: writer).reconcileRule(
                    rule("schedule"), config: config, now: now, calendar: calendar
                )
                XCTAssertEqual(writer.applied, schedules ? ["flint.schedule"] : [])
                XCTAssertEqual(writer.web["flint.schedule"], schedules ? config : nil)
            }
        }
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
    var wholeStoresCleared: [String] = []
    var events: [String] = []

    func apply(_ config: FlintWebRestrictions, toStore storeName: String) {
        applied.append(storeName)
        events.append("web:\(storeName)")
        web[storeName] = config
    }

    func clear(store storeName: String) {
        cleared.append(storeName)
        web.removeValue(forKey: storeName)
    }

    /// Models the existing shield adapter's clearAllSettings(), not an extra Safari clear.
    func clearAllSettings(store name: String) {
        wholeStoresCleared.append(name)
        web.removeValue(forKey: name)
    }
}

/// PC-72: exercise the shipped session controller with the production Safari adapter and fake
/// OS/store seams. Store composition here proves ownership, not iOS's cross-store filter union.
final class FlintSessionWebRestrictionTests: XCTestCase {
    func testEveryFocusEntryPointAppliesOnlyAfterRegistrationAndShield() throws {
        for source in [FlintSessionSource.manual, .shortcut, .pomodoro, .focusFilter] {
            let h = WebSessionHarness()
            let controller = h.controller()
            _ = try h.start(controller, source: source)
            XCTAssertEqual(h.writer.web["flint"], h.config, source.rawValue)
            XCTAssertEqual(h.writer.events.prefix(3), ["register", "shield", "web:flint"])
        }
    }

    func testDisabledSessionScopeDoesNotApplyEvenWhenSchedulesOptIn() throws {
        let h = WebSessionHarness()
        h.config = FlintWebRestrictions(applyDuringSchedules: true)
        _ = try h.start(h.controller())
        XCTAssertTrue(h.writer.applied.isEmpty)
        XCTAssertNil(h.writer.web["flint"])
    }

    func testUnarmedOrRejectedSessionNeverAppliesSafariAndKeepsPreviousOwner() throws {
        let h = WebSessionHarness()
        let controller = h.controller()
        let first = try h.start(controller)
        let original = h.writer.web["flint"]
        h.registrar.refuse = true
        h.config.filter = .allowedOnly
        XCTAssertThrowsError(try h.start(controller))
        XCTAssertEqual(h.writer.applied, ["flint"])
        XCTAssertEqual(h.writer.web["flint"], original)
        XCTAssertEqual(h.store.active, first)

        let rejected = WebSessionHarness()
        rejected.shield.refuse = true
        XCTAssertThrowsError(try rejected.start(rejected.controller()))
        XCTAssertTrue(rejected.writer.applied.isEmpty, "no Safari write after failed shielding")
    }

    func testMonitorStartRepairsMatchingSessionAndIgnoresReplacedCallback() throws {
        let h = WebSessionHarness()
        let controller = h.controller()
        let session = try h.start(controller)
        h.writer.web.removeValue(forKey: "flint") // crash / missing OS keys
        controller.handleMonitorEvent(.intervalDidStart, activity: session.monitorName)
        XCTAssertEqual(h.writer.web["flint"], h.config)
        controller.handleMonitorEvent(.intervalDidStart, activity: "flint.session.replaced")
        XCTAssertEqual(h.writer.applied, ["flint", "flint"])
        h.config.enabled = false
        controller.handleMonitorEvent(.intervalDidStart, activity: session.monitorName)
        XCTAssertNil(h.writer.web["flint"])
    }

    func testReconcileReappliesOnlyAfterSuccessfulRegistrationRepair() throws {
        let h = WebSessionHarness()
        let controller = h.controller()
        _ = try h.start(controller)
        h.registrar.registered.removeAll()
        h.registrar.refuse = true
        XCTAssertTrue(controller.reconcile().rearmFailed)
        XCTAssertEqual(h.writer.applied, ["flint"], "unarmed cause must not reapply Safari")
        h.registrar.refuse = false
        h.config.allowedDomains = ["example.com"]
        h.config.filter = .allowedOnly
        XCTAssertTrue(controller.reconcile().rearmed)
        XCTAssertEqual(h.writer.web["flint"], h.config)
        XCTAssertEqual(h.writer.applied, ["flint", "flint"])
    }

    func testReconcileDoesNotReapplyAfterConcurrentSessionEnd() throws {
        let h = WebSessionHarness()
        let controller = h.controller()
        _ = try h.start(controller)
        h.store.loads = 0
        h.store.onLoad = { [unowned h] count in
            if count == 2 {
                h.store.active = nil
                h.writer.clearAllSettings(store: "flint")
            }
        }
        controller.reconcile()
        XCTAssertNil(h.writer.web["flint"])
        XCTAssertEqual(h.writer.applied, ["flint"])
    }

    func testPC72EverySessionEndClearsItsWholeStoreAndLeavesTwoRuleOwners() throws {
        for end in ["manual", "shortcut", "focusOff", "pass", "expiry", "monitor", "warning", "carrier", "noSession"] {
            let h = WebSessionHarness()
            let controller = h.controller()
            let session = try h.start(controller, duration: end == "warning" || end == "carrier" ? 300 : 1800)
            h.writer.web["flint.rule-a"] = h.config
            h.writer.web["flint.rule-b"] = h.config
            let surgicalClears = h.writer.cleared.count
            switch end {
            case "manual": controller.stop()
            case "shortcut": controller.stop(route: .shortcutStop)
            case "focusOff": controller.stop(route: .focusFilterOff)
            case "pass": XCTAssertTrue(controller.useEmergencyPass())
            case "expiry":
                h.now = session.endsAt!
                controller.finishIfExpired()
            case "monitor", "carrier":
                h.now = session.endsAt!.addingTimeInterval(end == "carrier" ? 900 : 0)
                controller.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
            case "warning":
                h.now = session.endsAt!
                controller.handleMonitorEvent(.intervalWillEndWarning, activity: session.monitorName)
            default:
                h.store.active = nil
                controller.reconcile()
            }
            XCTAssertEqual(Set(h.writer.web.keys), ["flint.rule-a", "flint.rule-b"], end)
            XCTAssertEqual(h.writer.wholeStoresCleared, ["flint"], end)
            XCTAssertEqual(h.writer.cleared.count, surgicalClears, "end uses only the cause's clearAllSettings: \(end)")
            XCTAssertNil(h.store.active, end)
        }
    }
}

private final class WebSessionHarness {
    var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
    var now = ISO8601DateFormatter().date(from: "2026-10-26T23:00:00-04:00")!
    let writer = RecordingWebWriter()
    let store = WebSessionState()
    lazy var shield = WebSessionShield(writer: writer)
    lazy var registrar = WebSessionRegistrar(writer: writer)

    func controller() -> FlintSessionController {
        FlintSessionController(environment: FlintSessionEnvironment(
            store: { [self] in store }, shield: shield,
            webRestrictions: FlintSessionWebRestrictionAdapter(writer: writer, configuration: { [self] in config }),
            registrar: registrar, isAuthorized: { true },
            clock: FlintClock(now: { [self] in now }, monotonic: { 50_000 }, bootSessionID: { "test-boot" },
                              calendar: {
                                  var calendar = Calendar(identifier: .gregorian)
                                  calendar.timeZone = TimeZone(identifier: "America/New_York")!
                                  return calendar
                              }),
            healthDefaults: { nil }
        ))
    }

    func start(_ controller: FlintSessionController, duration: TimeInterval = 1800,
               source: FlintSessionSource = .manual) throws -> FlintActiveSession {
        try controller.startBlockNow(selection: FamilyActivitySelection(), duration: duration,
                                     breakLevel: .hardcore, source: source)
    }
}

private final class WebSessionShield: FlintSessionShielding {
    let writer: RecordingWebWriter
    var refuse = false
    init(writer: RecordingWebWriter) { self.writer = writer }
    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { .init(applications: 1) }
    func applyBlockList(_ selection: FamilyActivitySelection) throws {
        if refuse { throw WebTestRefusal() }
        writer.events.append("shield")
    }
    func setDenyAppRemoval(_ deny: Bool) {}
    func clear() { writer.clearAllSettings(store: "flint") }
}

private struct WebTestRefusal: Error {}

private final class WebSessionRegistrar: FlintSessionActivityRegistering {
    let writer: RecordingWebWriter
    var registered: Set<String> = []
    var refuse = false
    init(writer: RecordingWebWriter) { self.writer = writer }
    func startMonitoring(_ name: String, plan: FlintSessionAutoClearPlan) throws {
        if refuse { throw WebTestRefusal() }
        writer.events.append("register")
        registered.insert(name)
    }
    func stopMonitoring(_ names: [String]) { registered.subtract(names) }
    func registeredActivityNames() -> Set<String> { registered }
}

private final class WebSessionState: FlintSessionStateStoring {
    var active: FlintActiveSession?
    var selection: FamilyActivitySelection?
    var sessionSelection: FamilyActivitySelection?
    var passUsed: Date?
    var loads = 0
    var onLoad: ((Int) -> Void)?
    func loadActiveSession() -> FlintActiveSession? {
        loads += 1
        onLoad?(loads)
        return active
    }
    func saveActiveSession(_ session: FlintActiveSession) { active = session }
    func clearActiveSession() { active = nil }
    func saveSelection(_ selection: FamilyActivitySelection) { self.selection = selection }
    func loadSelection() -> FamilyActivitySelection? { selection }
    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) { sessionSelection = selection }
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? { sessionSelection }
    func clearSessionSelection() { sessionSelection = nil }
    func emergencyPassLastUsed() -> Date? { passUsed }
    func recordEmergencyPassUse(_ date: Date) { passUsed = date }
}
