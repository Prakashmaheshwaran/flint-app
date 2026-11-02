import XCTest
@testable import FlintCore

final class FlintProtectionObservationTests: XCTestCase {
    private let date = Date(timeIntervalSinceReferenceDate: 12345)

    func testObservationPersistsWithoutTouchingLegacyHealth() throws {
        let suite = "flint.tests.protection.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let group = try XCTUnwrap(FlintGroupStore(suiteName: suite))
        let legacy = FlintArmingHealth(domains: ["limits": report()])
        group.saveArmingHealth(legacy)
        XCTAssertNil(FlintProtectionObservation.load(from: defaults), "an old ledger is not a new observation")
        let observation = FlintProtectionObservation(observedAt: date, trigger: .launch,
            domains: ["schedules": report(observed: ["flint.rule.x"])])
        observation.save(to: defaults)
        XCTAssertEqual(FlintProtectionObservation.load(from: defaults), observation)
        XCTAssertEqual(group.loadArmingHealth(), legacy)
    }

    func testLegacyDomainDecodesWithUnknownObservation() throws {
        let data = Data("{\"attempted\":1,\"failures\":[],\"updatedAt\":12345}".utf8)
        let report = try JSONDecoder().decode(FlintArmingHealth.DomainReport.self, from: data)
        XCTAssertEqual(report.enabled, 1)
        XCTAssertEqual(report.armed, 1)
        XCTAssertNil(report.observed)
        XCTAssertNil(report.observedActivities)
        XCTAssertNil(report.notAuthorized)
        XCTAssertNil(report.rearmFailed)
        XCTAssertNil(report.recordUnreadable)
    }

    func testSessionRecoveryFlagsPersistInObservation() throws {
        let suite = "flint.tests.sessionObservation.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = FlintArmingHealth.DomainReport(enabled: 1, attempted: 1, armed: 0,
            failures: [], notAuthorized: true, rearmFailed: true, recordUnreadable: true)
        let observation = FlintProtectionObservation(observedAt: date, trigger: .foreground,
                                                    domains: ["session": session])
        observation.save(to: defaults)
        let loaded = try XCTUnwrap(FlintProtectionObservation.load(from: defaults))
        XCTAssertEqual(loaded.domains["session"]?.notAuthorized, true)
        XCTAssertEqual(loaded.domains["session"]?.rearmFailed, true)
        XCTAssertEqual(loaded.domains["session"]?.recordUnreadable, true)
        var recovered = session
        recovered.notAuthorized = false
        recovered.rearmFailed = false
        recovered.recordUnreadable = false
        XCTAssertEqual(try JSONDecoder().decode(FlintArmingHealth.DomainReport.self,
            from: JSONEncoder().encode(recovered)), recovered)
    }

    func testStaleBoundaryAndClockRollback() {
        let observation = FlintProtectionObservation(observedAt: date, trigger: .foreground, domains: [:])
        XCTAssertFalse(observation.isStale(at: date.addingTimeInterval(24 * 3600)))
        XCTAssertTrue(observation.isStale(at: date.addingTimeInterval(24 * 3600 + 1)))
        XCTAssertTrue(observation.isStale(at: date.addingTimeInterval(-1)))
    }

    func testFailedRuleNeedsAttentionInBothTodayAndRulesMapping() {
        let failed = report(failures: [.init(activityName: "flint.rule.x", reason: "refused")],
                            observed: ["flint.rule.x"])
        XCTAssertEqual(status(failed), .needsAttention, "failure beats an older accepted registration")
        XCTAssertEqual(status(failed, enabled: false), .off)
        XCTAssertEqual(status(report(observed: ["flint.rule.other"])), .unverified)
        XCTAssertEqual(status(report()), .unverified, "legacy acceptance is not observation")
        XCTAssertEqual(status(report(observed: ["flint.rule.x"])), .registered)
    }

    func testNeverObservedAndStaleRulesAreNotCurrent() {
        let health = FlintArmingHealth(domains: ["schedules": report(observed: ["flint.rule.x"])])
        XCTAssertEqual(FlintRuleProtectionStatus.status(enabled: true, activityName: "flint.rule.x",
            domain: "schedules", health: health, observation: nil, at: date), .unverified)
        let old = FlintProtectionObservation(observedAt: date.addingTimeInterval(-25 * 3600),
                                             trigger: .launch, domains: health.domains)
        XCTAssertEqual(FlintRuleProtectionStatus.status(enabled: true, activityName: "flint.rule.x",
            domain: "schedules", health: health, observation: old, at: date), .unverified)
    }

    func testWholeLayerFailureAndDeniedAuthorizationCannotLookCurrent() {
        let health = FlintArmingHealth(domains: ["schedules": report(observed: ["flint.rule.x"])])
        let failed = report(failures: [.init(activityName: "schedules", reason: "storage unavailable")])
        let observation = FlintProtectionObservation(observedAt: date, trigger: .launch,
                                                    domains: ["schedules": failed])
        XCTAssertEqual(FlintRuleProtectionStatus.status(enabled: true, activityName: "flint.rule.x",
            domain: "schedules", health: health, observation: observation, at: date), .needsAttention)
        let healthy = FlintProtectionObservation(observedAt: date, trigger: .launch, domains: health.domains)
        XCTAssertEqual(FlintRuleProtectionStatus.status(enabled: true, activityName: "flint.rule.x",
            domain: "schedules", health: health, observation: healthy, at: date, authorized: false), .needsAttention)
    }

    private func status(_ report: FlintArmingHealth.DomainReport, enabled: Bool = true) -> FlintRuleProtectionStatus {
        let health = FlintArmingHealth(domains: ["schedules": report])
        let observation = FlintProtectionObservation(observedAt: date, trigger: .launch, domains: health.domains)
        return .status(enabled: enabled, activityName: "flint.rule.x", domain: "schedules",
                       health: health, observation: observation, at: date)
    }

    private func report(failures: [FlintArmingHealth.Failure] = [], observed: [String]? = nil)
        -> FlintArmingHealth.DomainReport {
        .init(enabled: 1, attempted: 1, armed: failures.isEmpty ? 1 : 0, failures: failures,
              updatedAt: date, observedActivities: observed)
    }
}
