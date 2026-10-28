import XCTest
import DeviceActivity
@testable import FlintCore

final class FlintProtectionRecoverySafetyTests: XCTestCase {
    func testUnreadableRuleStoresPreserveEveryLayerAndRecordUnavailable() throws {
        try withDefaults { defaults in
            let unreadable: [Any] = [Data("{partial".utf8), "wrong stored type"]
            for value in unreadable {
                for key in FlintDesiredRules.Key.allCases { defaults.set(value, forKey: key.rawValue) }
                let center = FakeActivityCenter()
                let names = ["flint.rule.keep", "flint.limit.keep", "flint.openLimit.keep"]
                for name in names { center.held[.init(name)] = activity(name) }
                var cleared: [String] = []
                var reports: [String: FlintArmingHealth.DomainReport] = [:]
                let env = FlintRuleReloadEnvironment(defaults: defaults, center: center,
                    clearStore: { cleared.append($0) }, applyOpenShield: { _ in XCTFail("unreadable intent must not apply") },
                    record: { reports[$0] = $1 })
                _ = FlintSchedulesController(environment: env).reload()
                FlintLimitsController(environment: env).reload()
                FlintOpenLimitsController(environment: env).reload()
                // Editing must not turn an unavailable source into an authoritative array.
                _ = FlintSchedulesController(environment: env).upsert(.init(name: "New",
                    schedule: .init(startHour: 9, endHour: 17), allowListMode: true))
                FlintLimitsController(environment: env).delete("keep")
                FlintOpenLimitsController(environment: env).upsert(.init(name: "New", opensAllowed: 2))
                XCTAssertEqual(Set(center.activities.map(\.rawValue)), Set(names))
                XCTAssertTrue(center.starts.isEmpty)
                XCTAssertTrue(center.stops.isEmpty)
                XCTAssertTrue(cleared.isEmpty)
                XCTAssertEqual(Set(reports.keys), ["schedules", "limits", "openLimits"])
                for report in reports.values {
                    XCTAssertEqual(report.attempted, 0)
                    XCTAssertNil(report.observed)
                    XCTAssertTrue(report.failures[0].reason.contains("unavailable"))
                }
            }
        }
    }

    func testDecodedEmptyArraysAuthorizeOnlyTheirOwnPruning() throws {
        try withDefaults { defaults in
            for key in FlintDesiredRules.Key.allCases { defaults.set(Data("[]".utf8), forKey: key.rawValue) }
            let center = FakeActivityCenter()
            let names = ["flint.rule.old", "flint.limit.old", "flint.openLimit.old", "flint.session.keep"]
            for name in names { center.held[.init(name)] = activity(name) }
            var cleared: [String] = []
            let env = FlintRuleReloadEnvironment(defaults: defaults, center: center,
                clearStore: { cleared.append($0) }, applyOpenShield: { _ in XCTFail() }, record: { _, _ in })
            _ = FlintSchedulesController(environment: env).reload()
            FlintLimitsController(environment: env).reload()
            FlintOpenLimitsController(environment: env).reload()
            XCTAssertEqual(center.activities.map(\.rawValue), ["flint.session.keep"])
            XCTAssertEqual(Set(cleared), ["flint.old", "flintL.old", "flintO.old"])
            XCTAssertEqual(center.stops.count, 3)
        }
    }

    func testRefusedReplacementRetainsLastGoodProtectionInEveryNamespace() {
        for prefix in ["flint.rule.", "flint.limit.", "flint.openLimit."] {
            let center = FakeActivityCenter()
            let name = prefix + "keep"
            let previous = activity(name, hour: 9)
            center.held[.init(name)] = previous
            center.refused = [name]
            var cleared = false
            let report = FlintActivityRegistration.reconcile([activity(name, hour: 10)], prefix: prefix,
                enabled: 1, validationFailures: [], center: center, defaults: nil, onRemove: { _ in cleared = true })
            XCTAssertFalse(cleared)
            XCTAssertTrue(center.stops.isEmpty)
            XCTAssertEqual(center.schedule(for: .init(name)), previous.schedule)
            XCTAssertEqual(report.armed, 0)
            XCTAssertEqual(report.observed, 0)
            XCTAssertEqual(report.failures.count, 1)
        }
    }

    func testSemanticNormalizationAndPersistedDefinitionPreventRestartChurn() throws {
        try withDefaults { defaults in
            let center = FakeActivityCenter()
            let name = "flint.limit.normalized"
            var request = activity(name)
            request.events = [.init("budget"): .init(threshold: .init(minute: 60))]
            var components = DateComponents(hour: 9, minute: 0, second: 0)
            components.calendar = Calendar(identifier: .gregorian)
            components.timeZone = TimeZone(secondsFromGMT: 0)
            center.held[.init(name)] = .init(name: name,
                schedule: .init(intervalStart: components, intervalEnd: .init(hour: 17, minute: 0, second: 0), repeats: true),
                events: [.init("budget"): .init(threshold: .init(hour: 1, minute: 0, second: 0))])
            let reconcile = {
                FlintActivityRegistration.reconcile([request], prefix: "flint.limit.", enabled: 1,
                    validationFailures: [], center: center, defaults: defaults)
            }
            XCTAssertEqual(reconcile().observed, 1)
            XCTAssertTrue(center.starts.isEmpty)
            // A further OS representation difference uses name presence + the last observed,
            // unchanged desired definition, not opaque framework equality or another start.
            center.held[.init(name)] = activity(name, hour: 8)
            XCTAssertEqual(reconcile().observed, 1)
            XCTAssertTrue(center.starts.isEmpty)
            request.events = [.init("budget"): .init(threshold: .init(minute: 90))]
            XCTAssertEqual(reconcile().attempted, 1, "a real desired edit still registers")
        }
    }

    func testScheduleUpgradeBaselineAndMetadataEditsDoNotRestartButFailedEditRetainsBaseline() throws {
        try withDefaults { defaults in
            let original = FlintScheduleRule(id: "x", name: "Original", schedule: .init(startHour: 9, endHour: 17), allowListMode: true)
            let center = FakeActivityCenter()
            center.held[.init(original.monitorName)] = activity(original.monitorName)
            var report = FlintArmingHealth.DomainReport(enabled: 0, attempted: 0, armed: 0, failures: [])
            let env = FlintRuleReloadEnvironment(defaults: defaults, center: center,
                clearStore: { _ in XCTFail("no removal or invalid selection") }, applyOpenShield: { _ in XCTFail() },
                record: { _, value in report = value })
            let controller = FlintSchedulesController(environment: env)
            defaults.set(try JSONEncoder().encode([original]), forKey: FlintDesiredRules.Key.schedules.rawValue)
            XCTAssertTrue(controller.reload().isEmpty)
            XCTAssertTrue(center.starts.isEmpty, "seed upgrade baseline without restarting a matching native schedule")
            XCTAssertEqual(FlintScheduleRegistrationState.load(from: defaults)[original.monitorName], original)
            var edited = original
            edited.name = "Renamed"
            edited.breakLevel = .harder
            defaults.set(try JSONEncoder().encode([edited]), forKey: FlintDesiredRules.Key.schedules.rawValue)
            XCTAssertTrue(controller.reload().isEmpty)
            XCTAssertTrue(center.starts.isEmpty, "metadata edits do not reset monitoring")
            let lastGood = edited
            edited.schedule.daysOfWeek = [2]
            defaults.set(try JSONEncoder().encode([edited]), forKey: FlintDesiredRules.Key.schedules.rawValue)
            center.refused = [edited.monitorName]
            XCTAssertEqual(controller.reload().count, 1)
            XCTAssertEqual(report.armed, 0)
            XCTAssertEqual(FlintScheduleRegistrationState.load(from: defaults)[original.monitorName], lastGood)
            XCTAssertEqual(controller.reload().count, 1)
            XCTAssertEqual(center.starts.count, 2, "a refused edit must retry instead of adopting a false baseline")
        }
    }

    @MainActor
    func testOpenLimitLiveLayerPreservesForegroundGrantsAndRefusedLaunchShield() async throws {
        let suite = "flint.tests.openLayer.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let rule = FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 2)
        FlintOpenLimitRule.saveAll([rule], to: defaults)
        let center = FakeActivityCenter()
        var applied = 0
        var cleared = 0
        var report = FlintArmingHealth.DomainReport(enabled: 0, attempted: 0, armed: 0, failures: [])
        let env = FlintRuleReloadEnvironment(defaults: defaults, center: center,
            clearStore: { _ in cleared += 1 }, applyOpenShield: { _ in applied += 1 }, record: { _, value in report = value })
        let controller = FlintOpenLimitsController(environment: env, selectionIssue: { _ in nil })
        let layer = FlintOpenLimitProtectionLayer { reapply in
            controller.reload(reapplyShields: reapply)
            return report
        }
        for trigger in FlintProtectionTrigger.allCases where trigger != .launch {
            _ = try await layer.reconcile(trigger: trigger)
        }
        XCTAssertEqual(applied, 0, "registration repair must not revoke an existing released grant")
        _ = try await layer.reconcile(trigger: .launch)
        XCTAssertEqual(applied, 1)
        // Force an actual replacement refusal; the existing shield is last-good protection.
        center.held[.init(rule.activityName)] = activity(rule.activityName, hour: 8)
        FlintActivityDefinition.save([:], to: defaults)
        center.refused = [rule.activityName]
        let failed = try await layer.reconcile(trigger: .launch)
        XCTAssertEqual(applied, 1)
        XCTAssertEqual(cleared, 0)
        XCTAssertTrue(center.stops.isEmpty)
        XCTAssertEqual(failed.failures.count, 1)
    }

    private func activity(_ name: String, hour: Int = 9) -> FlintActivityRegistration {
        .init(name: name, schedule: .init(intervalStart: .init(hour: hour), intervalEnd: .init(hour: 17), repeats: true))
    }

    private func withDefaults(_ body: (UserDefaults) throws -> Void) throws {
        let suite = "flint.tests.recoverySafety.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        try body(defaults)
    }
}
