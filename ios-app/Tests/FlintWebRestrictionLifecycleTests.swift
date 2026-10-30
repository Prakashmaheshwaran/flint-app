import XCTest
import FamilyControls
import ManagedSettings
import DeviceActivity
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
                          enabled: enabled, protection: .init(timeZoneID: calendar.timeZone.identifier))
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
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), expected)
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), expected)
        var updated = expected
        updated.applyDuringSchedules = true
        updated.save(to: defaults)
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), updated)
    }

    func testUnreadableOrDeletedConfigPreservesActiveHardcoreSessionWebKeys() throws {
        let suite = "test.flint.web.unavailable.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default)
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: nil))
        let config = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: defaults))
        let writer = RecordingWebWriter()
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, configuration: {
            FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false })
        }, children: .init(defaults: defaults))
        let fixedNow = now
        let clock = FlintClock(now: { fixedNow }, monotonic: { 0 }, bootSessionID: { nil }, calendar: { .current })
        let active = FlintActiveSession(name: "Hardcore", startedAt: now.addingTimeInterval(-60),
            endsAt: now.addingTimeInterval(3600), breakLevel: .hardcore, monitorName: "flint.session",
            contractVersion: FlintActiveSession.currentContractVersion)
        defaults.set(try JSONEncoder().encode(active), forKey: "flint.activeSession")
        adapter.reconcile(active, clock: clock)
        for malformed in [Data("{".utf8), Data("{\"enabled\":true}".utf8)] {
            defaults.set(malformed, forKey: "flint.webRestrictions")
            adapter.reconcile(active, clock: clock)
            XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }))
            XCTAssertEqual(writer.web["flint"], config)
        }
        defaults.set("wrong storage type", forKey: "flint.webRestrictions")
        adapter.reconcile(active, clock: clock)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertTrue(writer.cleared.isEmpty)
        defaults.removeObject(forKey: "flint.webRestrictions")
        adapter.reconcile(active, clock: clock)
        XCTAssertNil(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }))
        let report = FlintWebRestrictionHostReconciler.reconcile(activeSession: active,
            hasActiveSessionRecord: true, rules: [], config: .loadForEnforcement(from: defaults),
            health: hostHealth([], observed: []), lifecycle: .init(writer: writer),
            now: now, calendar: calendar, clock: clock)
        XCTAssertFalse(report.failures.isEmpty)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertTrue(writer.cleared.isEmpty, "R2-N01: foreground must not release the finite Hardcore child")
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

    func testBusyUIReadDoesNotSkipNormalSessionEnforcementOfStrengthening() {
        let writer = RecordingWebWriter()
        var desired = FlintWebRestrictions(enabled: true)
        var reads = 0
        let load = { reads += 1; return desired as FlintWebRestrictions? }
        let contended: (TimeInterval?, () -> Void) -> Bool = { timeout, body in
            guard timeout != 0 else { return false }
            body(); return true
        }
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, configuration: {
            guard case let .available(config) = FlintWebRestrictions.read(
                uiRead: false, load: load, transition: contended
            ) else { return nil }
            return config
        }, children: .isolated())
        let instant = now
        let clock = FlintClock(now: { instant }, monotonic: { 0 }, bootSessionID: { nil },
                               calendar: { self.calendar })
        let active = session(endsAt: now.addingTimeInterval(3600))
        adapter.reconcile(active, clock: clock)
        desired.blockedDomains = ["example.com"]
        desired.denyExplicitMedia = true

        XCTAssertEqual(FlintWebRestrictions.read(uiRead: true, load: load, transition: contended), .busy)
        XCTAssertEqual(reads, 1, "The opportunistic UI read neither initializes nor loads intent")
        adapter.reconcile(active, clock: clock)
        XCTAssertEqual(reads, 2, "Enforcement retains its normal acquisition and reads the strengthening")
        XCTAssertEqual(writer.web["flint"], desired)
        XCTAssertEqual(writer.applied, ["flint", "flint"])
        XCTAssertTrue(writer.cleared.isEmpty)
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

    func testRecoveryAndUnavailableSuppressEverySafariApplyWithoutAuthorizingCleanup() {
        let writer = RecordingWebWriter()
        var gate = FlintPassRecoveryGate.suspended(until: now.addingTimeInterval(3600))
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, recoveryGate: { gate })
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer,
            configuration: { FlintWebRestrictions(enabled: true) }, recoveryGate: { gate }, children: .isolated())
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        writer.web["flint.web"] = config
        let clock = FlintClock(now: { self.now }, monotonic: { 0 }, bootSessionID: { nil }, calendar: { self.calendar })
        for value in [gate, .unavailable] {
            gate = value
            lifecycle.reconcileSession(session(), config: config, now: now)
            lifecycle.reconcileRule(rule("a"), config: config, now: now, calendar: calendar)
            lifecycle.reconcile(activeSession: session(), rules: [rule("a")], config: config, now: now, calendar: calendar)
            adapter.reconcile(session(), clock: clock)
        }
        XCTAssertTrue(writer.applied.isEmpty)
        XCTAssertTrue(writer.cleared.isEmpty)
        gate = .enforcing
        lifecycle.reconcile(activeSession: nil, rules: [rule("a")], config: config, now: now, calendar: calendar)
        XCTAssertEqual(writer.applied, ["flint.a"])
    }

    func testSafariDoesNotExpireALiveSessionBecauseTheWallClockMovedForward() {
        let writer = RecordingWebWriter()
        let shifted = now.addingTimeInterval(86400)
        let clock = FlintClock(now: { shifted }, monotonic: { 1060 }, bootSessionID: { "boot" }, calendar: { self.calendar })
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, clock: clock)
        let active = FlintActiveSession(name: "Focus", startedAt: now, endsAt: now.addingTimeInterval(3600),
            breakLevel: .hardcore, monitorName: "session", monotonicStart: 1000, bootSessionID: "boot")
        let config = FlintWebRestrictions(enabled: true)
        lifecycle.reconcileSession(active, config: config, now: shifted)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertTrue(writer.cleared.isEmpty)
    }

    func testSafariResumeHonorsSavedOneShotEndRatherThanRecurringTemplate() {
        let writer = RecordingWebWriter()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer)
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        var oneShot = rule("one-shot")
        oneShot.protection = .init(timeZoneID: "America/New_York")
        oneShot.oneShot = .init(start: now.addingTimeInterval(-60), end: now.addingTimeInterval(1800))
        lifecycle.reconcileRule(oneShot, config: config, now: now, calendar: calendar)
        XCTAssertEqual(writer.applied, [oneShot.storeName])
        lifecycle.reconcileRule(oneShot, config: config, now: now.addingTimeInterval(3600), calendar: calendar)
        XCTAssertNil(writer.web[oneShot.storeName])
        XCTAssertEqual(writer.applied, [oneShot.storeName])
    }

    func testRawScopeDisableKeepsLiveChildrenUntilAnAuthorizedEdit() {
        let writer = RecordingWebWriter()
        let children = FlintWebRestrictionChildren.isolated()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, children: children)
        let active = session(), rules = [rule("a"), rule("b")]
        let clock = FlintClock(now: { self.now }, monotonic: { 0 }, bootSessionID: { nil }, calendar: { self.calendar })
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        writer.web["flint.web"] = config
        lifecycle.reconcile(activeSession: active, rules: rules,
                            config: config, now: now, calendar: calendar)
        XCTAssertNil(writer.web["flint.web"])
        lifecycle.reconcile(activeSession: active, rules: rules,
                            config: .default, now: now, calendar: calendar)
        XCTAssertEqual(Set(writer.web.keys), ["flint", "flint.a", "flint.b"])
        XCTAssertTrue(children.saveAuthorized(.default, snapshot: .init(session: active, rules: rules), clock: clock))
        lifecycle.reconcile(activeSession: active, rules: rules, config: .default, now: now, calendar: calendar)
        XCTAssertTrue(writer.web.isEmpty)
        XCTAssertEqual(Set(writer.cleared), ["flint", "flint.a", "flint.b", "flint.web"])
    }

    func testApprovedConfigReappliesToEligibleOwnersAndScheduleOnlyWorks() {
        let writer = RecordingWebWriter()
        let children = FlintWebRestrictionChildren.isolated()
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, children: children)
        let active = session(), rules = [rule("a")]
        let clock = FlintClock(now: { self.now }, monotonic: { 0 }, bootSessionID: { nil }, calendar: { self.calendar })
        var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        lifecycle.reconcile(activeSession: active, rules: rules, config: config,
                            now: now, calendar: calendar)
        config.filter = .allowedOnly
        config.allowedDomains = ["example.com"]
        config.denyExplicitMedia = true
        XCTAssertTrue(children.saveAuthorized(config, snapshot: .init(session: active, rules: rules), clock: clock))
        lifecycle.reconcile(activeSession: active, rules: rules, config: config,
                            now: now, calendar: calendar)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertEqual(writer.web["flint.a"], config)
        XCTAssertEqual(writer.applied, ["flint", "flint.a", "flint", "flint.a"])
        config.enabled = false
        XCTAssertTrue(children.saveAuthorized(config, snapshot: .init(session: active, rules: rules), clock: clock))
        lifecycle.reconcile(activeSession: active, rules: rules, config: config,
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

    func testHostKeepsALiveSessionsSafariSettingsAfterAWallJumpPC54() {
        let writer = RecordingWebWriter()
        let config = FlintWebRestrictions(enabled: true)
        var wall = now
        var monotonic: TimeInterval = 1_000
        let clock = FlintClock(now: { wall }, monotonic: { monotonic }, bootSessionID: { "host-boot" },
                               calendar: { [calendar] in calendar })
        let live = FlintActiveSession(name: "Focus", startedAt: now, endsAt: now.addingTimeInterval(1800),
                                      breakLevel: .hardcore, monitorName: "flint.session",
                                      monotonicStart: monotonic, bootSessionID: "host-boot", plannedDuration: 1800)
        wall = now.addingTimeInterval(3600) // the wall clock jumps an hour…
        monotonic += 60                     // …one minute after the start
        _ = FlintWebRestrictionHostReconciler.reconcile(
            activeSession: live, hasActiveSessionRecord: true, rules: [], config: config, health: hostHealth([]),
            lifecycle: FlintWebRestrictionLifecycle(writer: writer), now: wall, calendar: calendar, clock: clock)
        XCTAssertEqual(writer.web["flint"], config, "29 minutes remain: the session keeps its Safari settings")
        XCTAssertFalse(writer.cleared.contains("flint"))
        monotonic += 29 * 60
        _ = FlintWebRestrictionHostReconciler.reconcile(
            activeSession: live, hasActiveSessionRecord: true, rules: [], config: config, health: hostHealth([]),
            lifecycle: FlintWebRestrictionLifecycle(writer: writer), now: wall, calendar: calendar, clock: clock)
        XCTAssertTrue(writer.cleared.contains("flint"), "released once its committed time has elapsed")
    }

    func testUnavailableIntentClearsOnlyTrustedEndedOwnersDespiteFailedEligibility() {
        let writer = RecordingWebWriter()
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        var wall = now
        var monotonic: TimeInterval = 1000
        let clock = FlintClock(now: { wall }, monotonic: { monotonic }, bootSessionID: { "end-boot" },
                               calendar: { self.calendar })
        let active = FlintActiveSession(name: "Hardcore", startedAt: now, endsAt: now.addingTimeInterval(1800),
            breakLevel: .hardcore, monitorName: "flint.session", monotonicStart: monotonic,
            bootSessionID: "end-boot", contractVersion: FlintActiveSession.currentContractVersion, plannedDuration: 1800)
        var frozen = rule("frozen")
        frozen.protection?.occurrence = .init(start: now, end: now.addingTimeInterval(1800))
        FlintRuleOccurrenceClock.observe(&frozen, clock: clock)
        var health = hostHealth([frozen]); health.domains["session"]?.rearmFailed = true
        health.domains["schedules"]?.observedActivities = []
        health.domains["schedules"]?.failures = [.init(activityName: frozen.monitorName, reason: "repair failed")]
        writer.web = ["flint": config, frozen.storeName: config, "flint.web": config]
        wall.addTimeInterval(86400); monotonic += 60
        _ = FlintWebRestrictionHostReconciler.reconcile(activeSession: active, hasActiveSessionRecord: true,
            rules: [frozen], config: nil, health: health, lifecycle: .init(writer: writer), now: wall, clock: clock)
        XCTAssertEqual(writer.web.count, 3, "Unarmed masks and a wall jump cannot prove the actual owners ended")
        monotonic += 1740
        var ended = frozen; ended.enabled = false
        _ = FlintWebRestrictionHostReconciler.reconcile(activeSession: active, hasActiveSessionRecord: true,
            rules: [ended], config: nil, health: health, lifecycle: .init(writer: writer), now: wall, clock: clock)
        XCTAssertTrue(writer.web.isEmpty, "The last logical end releases the primary and legacy web keys")
    }

    @MainActor
    func testWebLiveLayerRunsNormalAcquisitionOffMainAndRetriesBusyWithoutCorruption() async throws {
        let writer = RecordingWebWriter()
        let config = FlintWebRestrictions(enabled: true, blockedDomains: ["strengthened.example"])
        let active = session()
        let health = hostHealth([])
        let instant = now
        var acquired = false
        let layer = FlintWebProtectionLayer {
            XCTAssertFalse(Thread.isMainThread)
            return FlintWebRestrictionHostReconciler.withRead({ acquired ? .available(config) : .busy }) { read in
                FlintWebRestrictionHostReconciler.reconcile(activeSession: active, hasActiveSessionRecord: true,
                    rules: [], config: read, health: health, lifecycle: .init(writer: writer), now: instant)
            }
        }
        let busy = try await layer.reconcile(trigger: .foreground)
        XCTAssertTrue(writer.applied.isEmpty); XCTAssertTrue(writer.cleared.isEmpty)
        XCTAssertTrue(busy.failures.first?.reason.contains("busy") == true)
        XCTAssertFalse(busy.failures.first?.reason.contains("unavailable") == true)
        acquired = true
        let applied = try await layer.reconcile(trigger: .foreground)
        XCTAssertEqual(applied.armed, 1)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertTrue(applied.failures.isEmpty)
    }

    func testUnavailableIntentReleasesTrustedOneShotEndButNotUnknownSnapshotOrPassGate() {
        let writer = RecordingWebWriter()
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        var ended = rule("ended")
        ended.oneShot = .init(start: now.addingTimeInterval(-120), end: now)
        writer.web = [ended.storeName: config, "flint.web": config]
        _ = FlintWebRestrictionHostReconciler.reconcile(activeSession: nil, hasActiveSessionRecord: true,
            rules: [ended], config: nil, health: hostHealth([ended]), lifecycle: .init(writer: writer), now: now)
        XCTAssertEqual(writer.web.count, 2, "A missing session record is unknown, not an ended owner")
        let blocked = FlintWebRestrictionLifecycle(writer: writer, recoveryGate: { .unavailable })
        blocked.clearEndedOwners(activeSession: nil, rules: [ended], now: now)
        XCTAssertEqual(writer.web.count, 2)
        _ = FlintWebRestrictionHostReconciler.reconcile(activeSession: nil, hasActiveSessionRecord: false,
            rules: [ended], config: nil, health: hostHealth([ended]), lifecycle: .init(writer: writer), now: now)
        XCTAssertTrue(writer.web.isEmpty)
    }

    func testSessionAdapterUnavailableIntentUsesLogicalExpiryForCleanup() {
        let writer = RecordingWebWriter()
        let config = FlintWebRestrictions(enabled: true)
        var monotonic: TimeInterval = 1000
        let wall = now.addingTimeInterval(86400)
        let clock = FlintClock(now: { wall }, monotonic: { monotonic }, bootSessionID: { "adapter-end" },
                               calendar: { self.calendar })
        let active = FlintActiveSession(name: "Focus", startedAt: now, endsAt: now.addingTimeInterval(60),
            breakLevel: .hardcore, monitorName: "flint.session", monotonicStart: 1000,
            bootSessionID: "adapter-end", plannedDuration: 60)
        writer.web["flint"] = config
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, configuration: { nil }, children: .isolated())
        adapter.reconcile(active, clock: clock)
        XCTAssertEqual(writer.web["flint"], config)
        monotonic += 60; adapter.reconcile(active, clock: clock)
        XCTAssertNil(writer.web["flint"])
    }

    private func hostHealth(_ rules: [FlintScheduleRule], failures: [FlintArmingHealth.Failure] = [],
                            observed: [String]? = nil) -> FlintArmingHealth {
        .init(domains: [
            "session": .init(enabled: 1, attempted: 0, armed: 1, failures: [], observedActivities: ["flint.session"]),
            "schedules": .init(enabled: rules.count, attempted: 0, armed: rules.count, failures: failures,
                               observedActivities: observed ?? rules.map(\.monitorName))
        ])
    }

    func testHostPreservesFailedObservedRuleWhileUpdatingOtherOwners() {
        let writer = RecordingWebWriter()
        let rules = [rule("failed"), rule("healthy")]
        let old = FlintWebRestrictions(enabled: true)
        writer.web[rules[0].storeName] = old
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true, filter: .allowedOnly)
        let health = hostHealth(rules, failures: [.init(activityName: rules[0].monitorName, reason: "refused repair")])
        let report = FlintWebRestrictionHostReconciler.reconcile(
            activeSession: session(), hasActiveSessionRecord: true, rules: rules, config: config, health: health,
            lifecycle: FlintWebRestrictionLifecycle(writer: writer), now: now, calendar: calendar
        )
        XCTAssertEqual(writer.web[rules[0].storeName], old)
        XCTAssertEqual(writer.web[rules[1].storeName], config)
        XCTAssertEqual(writer.web["flint"], config)
        XCTAssertFalse(writer.cleared.contains(rules[0].storeName))
        XCTAssertEqual(report.armed, 2, "only the session and healthy rule were applied")
        XCTAssertEqual(report.enabled, 2, "the unwritten preserved owner is reported as a failure")
        XCTAssertNil(report.observedActivities, "a settings write is not observed OS enforcement")
        XCTAssertEqual(health.replacing("webRestrictions", with: report).armedTotal, health.armedTotal,
                       "applied web settings do not create additional native activity slots")
        XCTAssertEqual(report.failures.map(\.activityName), [rules[0].monitorName])
    }

    func testHostPreservesFailedUnobservedRuleUntilBoundedWholeStoreCleanup() throws {
        let suite = "test.flint.web.bound.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let writer = RecordingWebWriter()
        let rule = rule("failed")
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        writer.web[rule.storeName] = config
        let center = FakeActivityCenter()
        center.refused = [rule.monitorName]
        let end = date("2026-10-27T07:00:00-04:00")
        let request = FlintActivityRegistration(name: rule.monitorName,
            schedule: DeviceActivitySchedule(intervalStart: .init(hour: 22), intervalEnd: .init(hour: 7), repeats: true),
            retainedUntil: end)
        for instant in [now, end, end.addingTimeInterval(60)] {
            let report = FlintActivityRegistration.reconcile([request], prefix: "flint.rule.", enabled: 1,
                validationFailures: [], center: center, defaults: defaults, now: instant,
                onRemove: { _ in writer.clearAllSettings(store: rule.storeName) })
            var health = hostHealth([rule])
            health.domains["schedules"] = report
            _ = FlintWebRestrictionHostReconciler.reconcile(
                activeSession: nil, hasActiveSessionRecord: false, rules: [rule], config: config, health: health,
                lifecycle: FlintWebRestrictionLifecycle(writer: writer), now: instant, calendar: calendar
            )
            XCTAssertEqual(writer.web[rule.storeName], instant < end ? config : nil)
        }
        XCTAssertEqual(writer.wholeStoresCleared, [rule.storeName], "bounded cleanup clears exactly once")
        XCTAssertTrue(writer.applied.isEmpty, "the web adapter never resurrects a failed cause")
    }

    func testHostClearsExplicitlyDisabledHealthyRule() {
        let writer = RecordingWebWriter()
        let rule = rule("disabled", enabled: false)
        writer.web[rule.storeName] = FlintWebRestrictions(applyDuringSchedules: true)
        _ = FlintWebRestrictionHostReconciler.reconcile(
            activeSession: nil, hasActiveSessionRecord: false, rules: [rule], config: .default,
            health: hostHealth([rule], observed: []), lifecycle: FlintWebRestrictionLifecycle(writer: writer),
            now: now, calendar: calendar
        )
        XCTAssertNil(writer.web[rule.storeName])
        XCTAssertTrue(writer.cleared.contains(rule.storeName))
        XCTAssertTrue(writer.wholeStoresCleared.isEmpty, "web config edits preserve unrelated keys")
    }

    func testHostUnavailableInputOrUnreadableSessionMakesNoNativeWrites() {
        let rules = [rule("a")]
        for kind in ["rules", "config", "sessionDomain", "scheduleDomain", "record", "reportFlag", "missingReport"] {
            let writer = RecordingWebWriter()
            let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
            writer.web = ["flint": config, rules[0].storeName: config, "flint.web": config]
            let before = writer.web
            var health = hostHealth(rules)
            if kind == "sessionDomain" { health.domains["session"]?.failures = [.init(activityName: "session", reason: "unavailable")] }
            if kind == "scheduleDomain" { health.domains["schedules"]?.failures = [.init(activityName: "schedules", reason: "unavailable")] }
            if kind == "reportFlag" { health.domains["session"]?.recordUnreadable = true }
            if kind == "missingReport" { health.domains.removeValue(forKey: "schedules") }
            let report = FlintWebRestrictionHostReconciler.reconcile(
                activeSession: kind == "record" ? nil : session(), hasActiveSessionRecord: true,
                rules: kind == "rules" ? nil : rules, config: kind == "config" ? nil : .default,
                health: health, lifecycle: FlintWebRestrictionLifecycle(writer: writer), now: now, calendar: calendar
            )
            XCTAssertEqual(writer.web, before, kind)
            XCTAssertTrue(writer.applied.isEmpty, kind)
            XCTAssertTrue(writer.cleared.isEmpty, kind)
            XCTAssertFalse(report.failures.isEmpty, kind)
        }
    }

    func testHostSessionRearmFailurePreservesSessionKeysAndReconcilesHealthyRules() {
        let writer = RecordingWebWriter()
        let rules = [rule("healthy")]
        let old = FlintWebRestrictions(enabled: true)
        writer.web["flint"] = old
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        var health = hostHealth(rules)
        health.domains["session"]?.rearmFailed = true
        _ = FlintWebRestrictionHostReconciler.reconcile(
            activeSession: session(), hasActiveSessionRecord: true, rules: rules, config: config, health: health,
            lifecycle: FlintWebRestrictionLifecycle(writer: writer), now: now, calendar: calendar
        )
        XCTAssertEqual(writer.web["flint"], old)
        XCTAssertEqual(writer.web[rules[0].storeName], config)
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
        let frozen = h.config
        h.writer.web.removeValue(forKey: "flint") // crash / missing OS keys
        controller.handleMonitorEvent(.intervalDidStart, activity: session.monitorName)
        XCTAssertEqual(h.writer.web["flint"], h.config)
        controller.handleMonitorEvent(.intervalDidStart, activity: "flint.session.replaced")
        XCTAssertEqual(h.writer.applied, ["flint", "flint"])
        h.config.enabled = false
        controller.handleMonitorEvent(.intervalDidStart, activity: session.monitorName)
        XCTAssertEqual(h.writer.web["flint"], frozen, "A raw future preference cannot release the live Hardcore child")
    }

    func testReconcileReappliesOnlyAfterSuccessfulRegistrationRepair() throws {
        let h = WebSessionHarness()
        let controller = h.controller()
        _ = try h.start(controller)
        let frozen = h.config
        h.registrar.registered.removeAll()
        h.registrar.refuse = true
        XCTAssertTrue(controller.reconcile().rearmFailed)
        XCTAssertEqual(h.writer.applied, ["flint"], "unarmed cause must not reapply Safari")
        h.registrar.refuse = false
        h.config.allowedDomains = ["example.com"]
        h.config.filter = .allowedOnly
        XCTAssertTrue(controller.reconcile().rearmed)
        XCTAssertEqual(h.writer.web["flint"], frozen)
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

    func testUnreadableActiveSessionRecordPreservesExistingSafariKeys() throws {
        let h = WebSessionHarness()
        let controller = h.controller()
        _ = try h.start(controller)
        h.store.active = nil
        h.store.corruptRecord = true
        XCTAssertTrue(controller.reconcile().recordUnreadable)
        XCTAssertEqual(h.writer.web["flint"], h.config)
        XCTAssertTrue(h.writer.wholeStoresCleared.isEmpty)
    }

    func testPC72EverySessionEndClearsItsWholeStoreAndLeavesTwoRuleOwners() throws {
        for end in ["manual", "shortcut", "focusOff", "expiry", "monitor", "warning", "carrier"] {
            let h = WebSessionHarness()
            let controller = h.controller()
            let session = try h.start(controller, duration: end == "warning" || end == "carrier" ? 300 : 1800,
                                      source: end == "focusOff" ? .focusFilter : .manual,
                                      level: ["manual", "shortcut", "focusOff"].contains(end) ? .easy : .hardcore)
            h.writer.web["flint.rule-a"] = h.config
            h.writer.web["flint.rule-b"] = h.config
            let surgicalClears = h.writer.cleared.count
            switch end {
            case "manual": XCTAssertEqual(controller.requestStop(confirmed: true), .stopped)
            case "shortcut": XCTAssertEqual(controller.requestStop(route: .shortcutStop, confirmed: true), .stopped)
            case "focusOff": XCTAssertEqual(controller.requestStop(route: .focusFilterOff, confirmed: true), .stopped)
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
                XCTFail("unknown end fixture")
            }
            XCTAssertEqual(Set(h.writer.web.keys), ["flint.rule-a", "flint.rule-b"], end)
            XCTAssertEqual(h.writer.wholeStoresCleared, ["flint"], end)
            XCTAssertEqual(h.writer.cleared.count, surgicalClears, "end uses only the cause's clearAllSettings: \(end)")
            XCTAssertNil(h.store.active, end)
        }
    }

    func testMissingActiveKeyDoesNotReleaseSessionSafariOrOtherRuleOwnersR20PC64PC66() throws {
        let h = WebSessionHarness()
        let controller = h.controller()
        let session = try h.start(controller)
        h.writer.web["flint.rule-a"] = h.config
        h.writer.web["flint.rule-b"] = h.config
        h.store.active = nil
        XCTAssertTrue(controller.reconcile().recordUnreadable)
        controller.handleMonitorEvent(.intervalDidEnd, activity: session.monitorName)
        XCTAssertEqual(controller.requestStop(route: .shortcutStop, confirmed: true), .unavailable)
        XCTAssertEqual(Set(h.writer.web.keys), ["flint", "flint.rule-a", "flint.rule-b"])
        XCTAssertTrue(h.writer.wholeStoresCleared.isEmpty)
        XCTAssertTrue(h.registrar.registered.contains(session.monitorName))
    }

    func testPC41PC72PassReleasesSessionAndAllSafariRuleOwnersAfterCommit() throws {
        let h = WebSessionHarness()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("FlintWebPass-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let clock = FlintClock(now: { h.now }, monotonic: { 50000 }, bootSessionID: { "test-boot" },
                               calendar: { Calendar(identifier: .gregorian) })
        let ledger = FlintProtectionLedger(directory: directory, clock: clock,
            initializedMarker: { nil }, setInitializedMarker: { _ in }, migrate: { $0.importLegacyPass(.absent, at: $1) })
        let sessionController = h.controller(ledger: ledger)
        let session = try h.start(sessionController)
        h.writer.web["flint.rule-a"] = h.config
        h.writer.web["flint.rule-b"] = h.config
        let stores = ["flint", "flint.rule-a", "flint.rule-b"]
        let pass = FlintEmergencyPassController(environment: .init(ledger: { ledger }, clock: clock,
            scope: { _, _ in .init(revision: "scope", sessions: [session], rules: [], stores: stores) },
            registerCarrier: { _, _, _ in }, terminate: { sessions, at in
                for ended in sessions { XCTAssertTrue(sessionController.endCommittedEmergencyPass(ended, at: at)) }
            }, release: { scope in
                XCTAssertEqual(try ledger.peek().get().pass.usage, .used)
                for store in scope.stores { h.writer.clearAllSettings(store: store) }
            }, resume: { _, _ in }, emit: { recovery in
                FlintSessionLifecycle.didUsePass(operationID: recovery.operationID, sessions: recovery.scope.sessions,
                    recoveryEnd: recovery.recoveryEnd, at: recovery.acceptedAt.wall)
            }))
        let preview = try pass.preview().get()
        _ = try pass.use(previewRevision: preview.revision, operationID: "web-pass").get()
        XCTAssertTrue(h.writer.web.isEmpty)
        XCTAssertEqual(Set(h.writer.wholeStoresCleared), Set(stores))
        XCTAssertNil(h.store.active)
    }
}

private final class WebSessionHarness {
    private let ledgerDirectory = FileManager.default.temporaryDirectory
        .appendingPathComponent("flint-web-session-\(UUID().uuidString)")
    deinit { try? FileManager.default.removeItem(at: ledgerDirectory) }
    var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
    var now = ISO8601DateFormatter().date(from: "2026-10-26T23:00:00-04:00")!
    /// Moving `now` models real time passing: monotonic elapsed time moves with it (a session's
    /// remaining time follows elapsed time, ADR-012 §9).
    private let origin = ISO8601DateFormatter().date(from: "2026-10-26T23:00:00-04:00")!
    let writer = RecordingWebWriter()
    let children = FlintWebRestrictionChildren.isolated()
    let store = WebSessionState()
    lazy var shield = WebSessionShield(writer: writer)
    lazy var registrar = WebSessionRegistrar(writer: writer)

    func controller(ledger: FlintProtectionLedger? = nil) -> FlintSessionController {
        let clock = FlintClock(now: { [self] in now }, monotonic: { [self] in 50_000 + now.timeIntervalSince(origin) },
            bootSessionID: { "test-boot" }, calendar: {
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = TimeZone(identifier: "America/New_York")!
                return calendar
            })
        let sessionLedger = ledger ?? FlintProtectionLedger(directory: ledgerDirectory, clock: clock,
            initializedMarker: { nil }, setInitializedMarker: { _ in })
        return FlintSessionController(environment: FlintSessionEnvironment(
            store: { [self] in store }, shield: shield,
            webRestrictions: FlintSessionWebRestrictionAdapter(writer: writer, configuration: { [self] in config }, children: children),
            registrar: registrar, isAuthorized: { true },
            clock: clock, healthDefaults: { nil }, ledger: { sessionLedger }
        ))
    }

    func start(_ controller: FlintSessionController, duration: TimeInterval = 1800,
               source: FlintSessionSource = .manual, level: BreakLevel = .hardcore) throws -> FlintActiveSession {
        try controller.startBlockNow(selection: FamilyActivitySelection(), duration: duration,
                                     breakLevel: level, source: source,
                                     origin: source == .focusFilter ? .focusFilter : source == .shortcut ? .shortcut : .appUI,
                                     confirmed: true)
    }
}

private final class WebSessionShield: FlintSessionShielding {
    let writer: RecordingWebWriter
    var refuse = false
    init(writer: RecordingWebWriter) { self.writer = writer }
    func counts(of selection: FamilyActivitySelection) -> FlintSelectionCounts { .init(applications: 1) }
    func targets(of selection: FamilyActivitySelection) -> FlintSessionTargets { .init(applications: ["test-app"]) }
    func fingerprint(of selection: FamilyActivitySelection) -> String { "test-app" }
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
    var corruptRecord = false
    var onLoad: ((Int) -> Void)?
    func loadActiveSession() -> FlintActiveSession? {
        loads += 1
        onLoad?(loads)
        return active
    }
    func saveActiveSession(_ session: FlintActiveSession) { active = session }
    func hasActiveSessionRecord() -> Bool { active != nil || corruptRecord }
    func clearActiveSession() { active = nil }
    func saveSelection(_ selection: FamilyActivitySelection) { self.selection = selection }
    func loadSelection() -> FamilyActivitySelection? { selection }
    func saveSessionSelection(_ selection: FamilyActivitySelection, sessionID: String) { sessionSelection = selection }
    func loadSessionSelection(sessionID: String) -> FamilyActivitySelection? { sessionSelection }
    func clearSessionSelection() { sessionSelection = nil }
    func emergencyPassLastUsed() -> Date? { passUsed }
    func recordEmergencyPassUse(_ date: Date) { passUsed = date }
}

final class FlintWebRestrictionEditorTests: XCTestCase {
    func testHardcoreToggleOffAndMixedEditsRemainDraftsWithoutMutation() throws {
        let h = WebEditorHarness()
        h.active = h.session(.hardcore)
        for kind in ["off", "mode", "mixed", "media", "removeBlock", "addAllow"] {
            var draft = h.config
            switch kind {
            case "off": draft.enabled = false
            case "mode": draft.filter = .allowedOnly
            case "mixed": draft.blockedDomains += ["extra.example"]; draft.allowedDomains += ["extra.example"]
            case "media": draft.denyExplicitMedia = false
            case "removeBlock": draft.blockedDomains = []
            default: draft.allowedDomains += ["extra.example"]
            }
            XCTAssertEqual(h.editor().save(draft, requestID: kind, confirmed: true), .protected(until: h.active?.endsAt), kind)
        }
        XCTAssertEqual(h.writes, 0)
        XCTAssertTrue(try h.ledger().read().get().harder.isEmpty, "blocked batches start no waits")
    }

    func testHarderWaitPersistsAcrossEditorsAndNeedsFinalConfirmation() throws {
        let h = WebEditorHarness()
        h.active = h.session(.harder)
        var draft = h.config; draft.enabled = false
        XCTAssertEqual(h.editor().save(draft, requestID: "first"), .waiting(seconds: 60))
        h.advance(60)
        XCTAssertEqual(h.editor().save(draft, requestID: "first"), .confirmationRequired)
        XCTAssertEqual(h.writes, 0, "a matured wait never saves automatically")
        XCTAssertEqual(h.editor().save(draft, requestID: "first", confirmed: true), .saved)
        XCTAssertEqual(try h.ledger().read().get().harder[h.active!.id]?.committedCount, 1)
        XCTAssertEqual(h.editor().save(draft, requestID: "first", confirmed: true), .saved)
        XCTAssertEqual(try h.ledger().read().get().harder[h.active!.id]?.committedCount, 1)
        var stronger = h.config; stronger.enabled = true
        XCTAssertEqual(h.editor().save(stronger, requestID: "on"), .saved)
        XCTAssertEqual(h.editor().save(draft, requestID: "second"), .waiting(seconds: 300))
    }

    func testChangedDraftFingerprintStartsANewFullHarderWait() {
        let h = WebEditorHarness(); h.active = h.session(.harder)
        var draft = h.config; draft.allowedDomains += ["one.example"]
        XCTAssertEqual(h.editor().save(draft, requestID: "first"), .waiting(seconds: 60))
        h.advance(30)
        draft.allowedDomains = ["two.example"]
        XCTAssertEqual(h.editor().save(draft, requestID: "changed"), .waiting(seconds: 60))
        XCTAssertEqual(h.writes, 0)
    }

    func testEasyAndInactiveCausesAllowWeakening() {
        let h = WebEditorHarness(); h.active = h.session(.easy)
        var draft = h.config; draft.enabled = false
        XCTAssertEqual(h.editor().save(draft, requestID: "easy"), .saved)
        h.config.enabled = true
        h.active = h.session(.hardcore, end: h.now)
        XCTAssertEqual(h.editor().save(draft, requestID: "expired"), .saved)
        h.config.enabled = true; h.active = nil
        XCTAssertEqual(h.editor().save(draft, requestID: "inactive"), .saved)
    }

    func testStrengtheningHardcoreAppliesImmediately() {
        let h = WebEditorHarness(); h.active = h.session(.hardcore)
        var draft = h.config
        draft.blockedDomains += ["extra.example"]
        draft.allowedDomains = []
        XCTAssertEqual(h.editor().save(draft, requestID: "strengthen"), .saved)
        XCTAssertEqual(h.config, draft)
    }

    func testBatchHardcoreRuleBlocksEasySessionButUnchangedScopeDoesNot() {
        let h = WebEditorHarness(); h.active = h.session(.easy)
        h.rules = [h.rule(.hardcore)]
        var draft = h.config; draft.allowedDomains += ["extra.example"]
        let end = FlintWebRestrictionEditor.affectedCauses(previous: h.config, candidate: draft,
            snapshot: .init(session: h.active, rules: h.rules), now: h.now, calendar: h.calendar).last?.end
        XCTAssertEqual(h.editor().save(draft, requestID: "batch"), .protected(until: end))
        XCTAssertEqual(h.writes, 0)
        draft = h.config; draft.enabled = false // only the Easy session changes
        XCTAssertEqual(h.editor().save(draft, requestID: "session-only"), .saved)
    }

    func testAWallJumpCannotMakeALiveSessionsSafariPolicyFreelyEditablePC54() throws {
        let h = WebEditorHarness()
        // Started a minute ago in this boot, an hour committed.
        h.active = FlintActiveSession(name: "Focus", startedAt: h.now.addingTimeInterval(-60),
                                      endsAt: h.now.addingTimeInterval(3540), breakLevel: .hardcore,
                                      monitorName: "flint.session", monotonicStart: h.monotonic - 60,
                                      bootSessionID: "web-boot", plannedDuration: 3600)
        h.now = h.now.addingTimeInterval(2 * 3600) // the wall clock jumps; elapsed time doesn't
        var draft = h.config; draft.enabled = false
        let causes = FlintWebRestrictionEditor.affectedCauses(previous: h.config, candidate: draft,
            snapshot: .init(session: h.active, rules: []), now: h.now, calendar: h.calendar, clock: h.clock)
        let session = try XCTUnwrap(h.active)
        XCTAssertEqual(causes.map(\.causeID), [session.id], "still a live cause by logical time")
        let end = try XCTUnwrap(causes.first?.end)
        XCTAssertEqual(end.timeIntervalSince(h.now), 3540, accuracy: 1)
        XCTAssertEqual(h.editor().save(draft, requestID: "jump"), .protected(until: end))
        XCTAssertEqual(h.writes, 0)
    }

    func testHarderScheduleCountsAreScopedToItsOccurrence() throws {
        let h = WebEditorHarness(); h.rules = [h.rule(.harder)]
        var draft = h.config; draft.applyDuringSchedules = false
        let cause = FlintWebRestrictionEditor.affectedCauses(previous: h.config, candidate: draft,
            snapshot: .init(session: nil, rules: h.rules), now: h.now, calendar: h.calendar)[0]
        XCTAssertEqual(h.editor().save(draft, requestID: "schedule"), .waiting(seconds: 60))
        h.advance(60)
        XCTAssertEqual(h.editor().save(draft, requestID: "schedule", confirmed: true), .saved)
        XCTAssertEqual(try h.ledger().read().get().harder[cause.occurrenceID]?.committedCount, 1)
    }

    func testRepeatedHourUsesSharedLaterEndForSafariAndEditGating() {
        let h = WebEditorHarness()
        h.now = ISO8601DateFormatter().date(from: "2026-11-01T01:45:00-04:00")!
        var rule = h.rule(.hardcore)
        rule.schedule = .init(daysOfWeek: [1], startHour: 0, endHour: 1, endMinute: 30)
        h.rules = [rule]
        let end = ISO8601DateFormatter().date(from: "2026-11-01T01:30:00-05:00")!
        XCTAssertTrue(rule.schedule.isWindowActive(at: h.now, calendar: h.calendar))
        var draft = h.config; draft.applyDuringSchedules = false
        XCTAssertEqual(h.editor().save(draft, requestID: "repeated"), .protected(until: end))
        h.now = end
        XCTAssertFalse(rule.schedule.isWindowActive(at: h.now, calendar: h.calendar))
        XCTAssertEqual(h.editor().save(draft, requestID: "ended"), .saved)
    }

    func testOverlappingHarderSessionAndTwoRulesCommitEachWaitOnce() throws {
        let h = WebEditorHarness(); h.active = h.session(.harder)
        var second = h.rule(.harder); second.id = "second"
        h.rules = [h.rule(.harder), second]
        var draft = h.config; draft.allowedDomains += ["extra.example"]
        let causes = FlintWebRestrictionEditor.affectedCauses(previous: h.config, candidate: draft,
            snapshot: .init(session: h.active, rules: h.rules), now: h.now, calendar: h.calendar)
        XCTAssertEqual(causes.count, 3)
        XCTAssertEqual(h.editor().save(draft, requestID: "three", confirmed: true), .waiting(seconds: 60))
        XCTAssertEqual(h.writes, 0)
        h.advance(59)
        XCTAssertEqual(h.editor().save(draft, requestID: "three", confirmed: true), .waiting(seconds: 1))
        h.advance(1)
        XCTAssertEqual(h.editor().save(draft, requestID: "three"), .confirmationRequired)
        XCTAssertEqual(h.editor().save(draft, requestID: "three", confirmed: true), .saved)
        let state = try h.ledger().read().get()
        for cause in causes { XCTAssertEqual(state.harder[cause.occurrenceID]?.committedCount, 1) }
        XCTAssertEqual(h.writes, 1)
    }

    func testUnavailableLedgerOrChangedCauseRefusesTheSave() {
        let h = WebEditorHarness(); h.active = h.session(.harder); h.ledgerAvailable = false
        var draft = h.config; draft.enabled = false
        XCTAssertEqual(h.editor().save(draft, requestID: "no-ledger"), .unavailable)
        h.active = h.session(.easy); h.changeSnapshotOnRead = 2; h.snapshotReads = 0
        XCTAssertEqual(h.editor().save(draft, requestID: "raced"), .unavailable)
        XCTAssertEqual(h.writes, 0)
    }

    func testUnreadableConfigRecoveryRespectsHardcoreThenRetainsOriginalBytes() throws {
        let suite = "test.flint.web.editor.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let original = Data("{broken user configuration".utf8)
        defaults.set(original, forKey: "flint.webRestrictions")
        let h = WebEditorHarness()
        h.active = h.session(.hardcore)
        let recovery = h.directory.appendingPathComponent("Recovery")
        let editor = FlintWebRestrictionEditor(load: { .loadForEnforcement(from: defaults, existingWebStores: { false }) },
            snapshot: { .init(session: h.active, rules: []) },
            persist: { $0.saveRecoveringUnavailableIntent(to: defaults, recoveryDirectory: recovery) },
            ledger: { nil }, clock: h.clock)
        XCTAssertEqual(editor.save(.default, requestID: "repair"), .protected(until: h.active?.endsAt))
        XCTAssertEqual(defaults.data(forKey: "flint.webRestrictions"), original)
        h.active = nil
        XCTAssertEqual(editor.save(.default, requestID: "repair"), .saved)
        let key = try XCTUnwrap(defaults.dictionaryRepresentation().keys.first {
            $0.hasPrefix("flint.webRestrictions.recovered.")
        })
        let reference = try XCTUnwrap(defaults.object(forKey: key))
        XCTAssertEqual(FlintRuleRecovery.read(reference, key: key, directory: recovery), original)
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default)
    }

    func testUnavailableIntentAllowsEasyOrNoOwnerButNotUnknownOwners() {
        for active in [false, true] {
            let h = WebEditorHarness(); h.readOverride = .unavailable
            h.active = active ? h.session(.easy) : nil
            XCTAssertEqual(h.editor().save(.default, requestID: "repair"), .saved)
            XCTAssertEqual(h.writes, 1)
        }
        let h = WebEditorHarness(); h.readOverride = .unavailable; h.snapshotAvailable = false
        XCTAssertEqual(h.editor().save(.default, requestID: "unknown"), .unavailable)
        XCTAssertEqual(h.writes, 0)
    }

    func testWebHostAndEditorPreserveWhenAppliedGenerationLosesItsProjectionOrIsInstalling() throws {
        let h = WebEditorHarness()
        let active = h.session(.hardcore)
        var state = try h.ledger().read().get()
        var authority = FlintSessionAuthority(initializedAt: h.now)
        authority.adopt(active)
        state.sessionAuthority = authority; state.sessionAuthorityVersion = 1
        let valid = FlintWebRestrictionEditor.Snapshot.observing(session: active, recordPresent: true,
            rules: [], ledgerState: state, clock: h.clock)
        XCTAssertEqual(valid?.session?.id, active.id)
        for installing in [false, true] {
            if installing { state.sessionAuthority?.latest?.phase = .installing }
            let snapshot = FlintWebRestrictionEditor.Snapshot.observing(session: installing ? active : nil,
                recordPresent: installing, rules: [], ledgerState: state, clock: h.clock)
            XCTAssertNil(snapshot, "A lost projection or an installing generation is unknown, never idle")
            let writer = RecordingWebWriter(); writer.web["flint"] = h.config
            let health = FlintArmingHealth(domains: [
                "session": .init(enabled: 1, attempted: 1, armed: 1, failures: []),
                "schedules": .init(enabled: 0, attempted: 0, armed: 0, failures: [])
            ])
            let report = FlintWebRestrictionHostReconciler.reconcile(snapshot: snapshot, config: nil,
                health: health, lifecycle: .init(writer: writer), now: h.now, clock: h.clock)
            XCTAssertFalse(report.failures.isEmpty)
            XCTAssertEqual(writer.web["flint"], h.config)
            XCTAssertTrue(writer.cleared.isEmpty)
            let editor = FlintWebRestrictionEditor(read: { .unavailable }, snapshot: { snapshot },
                persist: { _ in XCTFail("Unknown owner cannot authorize repair"); return false },
                ledger: { h.ledger() }, clock: h.clock)
            XCTAssertEqual(editor.save(.default, requestID: "lost-projection"), .unavailable)
        }
        state.sessionAuthority = .init(initializedAt: h.now)
        XCTAssertNotNil(FlintWebRestrictionEditor.Snapshot.observing(session: nil, recordPresent: false,
            rules: [], ledgerState: state, clock: h.clock), "The explicit idle tombstone permits an owner snapshot")
        XCTAssertNil(FlintWebRestrictionEditor.Snapshot.observing(session: nil, recordPresent: false,
            rules: [], ledgerState: nil, clock: h.clock), "Ledger outage is not idle")
    }

    func testUnavailableIntentHarderRepairWaitsAndFailedWriteRetrySpendsOnce() throws {
        let h = WebEditorHarness(); h.readOverride = .unavailable
        h.active = h.session(.harder); h.rules = [h.rule(.harder)]
        XCTAssertEqual(h.editor().save(.default, requestID: "repair"), .waiting(seconds: 60))
        h.advance(60)
        XCTAssertEqual(h.editor().save(.default, requestID: "repair"), .confirmationRequired)
        h.saveAllowed = false
        guard case .invalid = h.editor().save(.default, requestID: "repair", confirmed: true) else {
            return XCTFail("An authorized failed repair must retain a retryable draft")
        }
        let spent = try h.ledger().read().get().harder.mapValues(\.committedCount)
        XCTAssertEqual(spent.count, 2)
        XCTAssertTrue(spent.values.allSatisfy { $0 == 1 })
        h.saveAllowed = true
        XCTAssertEqual(h.editor().save(.default, requestID: "repair", confirmed: true), .saved)
        XCTAssertEqual(try h.ledger().read().get().harder.mapValues(\.committedCount), spent)
    }

    func testUnavailableIntentHardcoreRuleDefersEvenAnOptInDraft() {
        let h = WebEditorHarness(); h.readOverride = .unavailable; h.rules = [h.rule(.hardcore)]
        let end = h.rules[0].occurrence(at: h.now, clock: h.clock)?.end
        XCTAssertEqual(h.editor().save(h.config, requestID: "repair"), .protected(until: end))
        XCTAssertEqual(h.writes, 0)
        h.now = end!; h.monotonic += 8 * 3600
        XCTAssertEqual(h.editor().save(h.config, requestID: "repair"), .saved)
    }

    func testUnknownSafariIntentRepairDoesNotInheritLegacyHardcoreWeakeningRights() {
        for sessionOwner in [false, true] {
            let h = WebEditorHarness(); h.readOverride = .unavailable
            let end: Date
            if sessionOwner {
                let active = FlintActiveSession(name: "Legacy", startedAt: h.now.addingTimeInterval(-60),
                    endsAt: h.now.addingTimeInterval(3600), breakLevel: .hardcore, monitorName: "flint.session.legacy")
                h.active = active; end = active.endsAt!
            } else {
                var rule = h.rule(.hardcore)
                end = rule.occurrence(at: h.now, clock: h.clock)!.end
                rule.protection?.legacyRightsUntil = end
                h.rules = [rule]
            }
            XCTAssertEqual(h.editor().save(.default, requestID: "legacy-repair"), .protected(until: end))
            XCTAssertEqual(h.writes, 0, "Unknown original web intent cannot import an easier exit")
            h.advance(end.timeIntervalSince(h.now))
            XCTAssertEqual(h.editor().save(.default, requestID: "legacy-repair"), .saved)
        }
    }

    func testBusyEditorReadAndBoundaryAreRetryableWithoutMutation() {
        let h = WebEditorHarness(); h.readOverride = .busy
        XCTAssertEqual(h.editor().save(.default, requestID: "busy"), .busy)
        let editor = FlintWebRestrictionEditor(load: { h.config }, snapshot: { .init(session: nil, rules: []) },
            persist: { _ in XCTFail("No mutation without the boundary"); return false }, ledger: { nil },
            clock: h.clock, withTransitionBoundary: { _ in false })
        XCTAssertEqual(editor.save(.default, requestID: "lock-busy"), .busy)
        XCTAssertEqual(h.writes, 0)
    }

    func testEditorBoundaryFailureRechecksInitializationFailureInsteadOfReportingBusy() {
        let h = WebEditorHarness()
        var preparations = 0
        let editor = FlintWebRestrictionEditor(read: { XCTFail("No read outside transition"); return .busy },
            snapshot: { nil }, persist: { _ in false }, ledger: { nil }, clock: h.clock, prepare: {
                preparations += 1
                return preparations == 1 ? nil : .unavailable
            }, withTransitionBoundary: { _ in false })
        XCTAssertEqual(editor.save(.default, requestID: "failed-receipt"), .unavailable)
        XCTAssertEqual(preparations, 2)
    }

    func testEditorCannotInferAffectedRulesFromDeviceCalendarWhenSavedZoneIsUnknown() {
        let h = WebEditorHarness()
        var unknown = h.rule(.hardcore); unknown.protection = nil
        h.rules = [unknown]
        XCTAssertEqual(h.editor().save(.default, requestID: "unknown-zone"), .unavailable)
        XCTAssertEqual(h.writes, 0)
    }

    func testDomainCapRefusesSaveAndReportsWithoutNativeWrites() throws {
        let h = WebEditorHarness()
        var draft = h.config
        draft.blockedDomains = (0...FlintShieldLimits.tokenCap).map { "site\($0).example" }
        XCTAssertEqual(h.editor().save(draft, requestID: "too-many"), .invalid(draft.validationMessage!))
        XCTAssertEqual(h.writes, 0)
        let writer = RecordingWebWriter()
        writer.web["flint"] = h.config
        let report = FlintWebRestrictionHostReconciler.reconcile(activeSession: h.session(.hardcore), hasActiveSessionRecord: true,
            rules: [], config: draft, health: .init(domains: [
                "session": .init(enabled: 0, attempted: 0, armed: 0, failures: []),
                "schedules": .init(enabled: 0, attempted: 0, armed: 0, failures: [])
            ]), lifecycle: .init(writer: writer), now: h.now, calendar: h.calendar)
        XCTAssertTrue(report.failures.contains { $0.reason == draft.validationMessage })
        XCTAssertTrue(writer.applied.isEmpty); XCTAssertTrue(writer.cleared.isEmpty)
        XCTAssertEqual(writer.web["flint"], h.config)
        draft.blockedDomains.removeLast()
        XCTAssertNil(draft.validationMessage)
        XCTAssertEqual(h.editor().save(draft, requestID: "fifty"), .saved)
    }

    func testAllowedDomainCapCountsNormalizedUniqueSitesAndPreservesSavedConfig() throws {
        let suite = "test.flint.web.cap.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let previous = FlintWebRestrictions(enabled: true, allowedDomains: ["saved.example"])
        XCTAssertTrue(previous.save(to: defaults))
        var draft = previous
        draft.allowedDomains = (0..<FlintShieldLimits.tokenCap).map { "site\($0).example" }
        draft.allowedDomains += ["https://www.site0.example/path", "invalid"]
        XCTAssertNil(draft.validationMessage, "only normalized unique hostnames reach ManagedSettings")
        draft.allowedDomains.append("overflow.example")
        XCTAssertNotNil(draft.validationMessage)
        XCTAssertFalse(draft.save(to: defaults))
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), previous)
    }
}

private final class WebEditorHarness {
    var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true,
        blockedDomains: ["blocked.example"], allowedDomains: ["allowed.example"], denyExplicitMedia: true)
    var active: FlintActiveSession?
    var rules: [FlintScheduleRule] = []
    var now = ISO8601DateFormatter().date(from: "2026-10-26T23:00:00-04:00")!
    var monotonic: TimeInterval = 1000
    var marker: Date?
    var ledgerAvailable = true
    var readOverride: FlintWebRestrictionRead?
    var snapshotAvailable = true
    var saveAllowed = true
    var writes = 0
    var snapshotReads = 0
    var changeSnapshotOnRead: Int?
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-web-edit-\(UUID().uuidString)")
    deinit { try? FileManager.default.removeItem(at: directory) }
    var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }
    var clock: FlintClock { .init(now: { [self] in now }, monotonic: { [self] in monotonic },
        bootSessionID: { "web-boot" }, calendar: { [self] in calendar }) }
    func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds); monotonic += seconds }
    func session(_ tier: BreakLevel, end: Date? = nil) -> FlintActiveSession {
        .init(name: "Focus", startedAt: now.addingTimeInterval(-60), endsAt: end ?? now.addingTimeInterval(3600),
              breakLevel: tier, monitorName: "flint.session", contractVersion: FlintActiveSession.currentContractVersion)
    }
    func rule(_ tier: BreakLevel) -> FlintScheduleRule {
        .init(id: "sleep.night", name: "Sleep", schedule: .init(daysOfWeek: [2], startHour: 22, endHour: 7),
              breakLevel: tier, protection: .init(timeZoneID: calendar.timeZone.identifier))
    }
    func ledger() -> FlintProtectionLedger {
        .init(directory: directory, clock: clock, initializedMarker: { [self] in marker },
              setInitializedMarker: { [self] in marker = $0 })
    }
    func editor() -> FlintWebRestrictionEditor {
        .init(read: { [self] in readOverride ?? .available(config) }, snapshot: { [self] in
            guard snapshotAvailable else { return nil }
            snapshotReads += 1
            if snapshotReads == changeSnapshotOnRead { active = session(.hardcore) }
            return .init(session: active, rules: rules)
        }, persist: { [self] in
            guard saveAllowed else { return false }
            config = $0; readOverride = nil; writes += 1; return true
        },
        ledger: { [self] in ledgerAvailable ? ledger() : nil }, clock: clock)
    }
}
