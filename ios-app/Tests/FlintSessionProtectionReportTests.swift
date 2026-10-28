import XCTest
@testable import FlintCore
@testable import Flint

final class FlintSessionProtectionReportTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 12345)

    func testRepairUsesCurrentSessionNameAndNativeObservation() {
        let active = session()
        var recovery = FlintSessionReconcileReport(outcome: .running)
        recovery.rearmed = true
        let missing = FlintSessionProtectionReport.make(active: active, recovery: recovery,
            health: .init(), observedActivities: ["flint.session.old"], now: now)
        XCTAssertEqual(missing.attempted, 1)
        XCTAssertEqual(missing.armed, 0)
        XCTAssertEqual(missing.observed, 0)
        XCTAssertEqual(missing.failures.map(\.activityName), [active.monitorName])
        let observed = FlintSessionProtectionReport.make(active: active, recovery: recovery,
            health: .init(), observedActivities: [active.monitorName, "flint.session.old"], now: now)
        XCTAssertEqual(observed.armed, 1)
        XCTAssertEqual(observed.observedActivities, [active.monitorName])
        XCTAssertTrue(observed.failures.isEmpty)
    }

    func testRefusedRepairAndDeniedAuthorizationCannotAppearArmed() {
        var recovery = FlintSessionReconcileReport(outcome: .running)
        recovery.rearmFailed = true
        recovery.notAuthorized = true
        let health = FlintSessionHealth(lastArmFailure: .init(sessionName: "Focus", reason: "pool full", at: now))
        let report = FlintSessionProtectionReport.make(active: session(), recovery: recovery,
            health: health, observedActivities: [session().monitorName], now: now)
        XCTAssertEqual(report.enabled, 1)
        XCTAssertEqual(report.attempted, 1)
        XCTAssertEqual(report.armed, 0)
        XCTAssertEqual(report.notAuthorized, true)
        XCTAssertEqual(report.rearmFailed, true)
        XCTAssertEqual(report.failures.count, 2)
        XCTAssertTrue(report.failures.contains { $0.reason == "pool full" })
    }

    func testOldZoneAndDeferredMissingRepairAreFailures() {
        let report = FlintSessionProtectionReport.make(active: session(),
            recovery: .init(outcome: .running), health: .init(),
            observedActivities: [session().monitorName], scheduleIsCurrent: false, now: now)
        XCTAssertEqual(report.attempted, 0)
        XCTAssertEqual(report.armed, 0)
        XCTAssertEqual(report.failures.map(\.activityName), [session().monitorName])
    }

    func testNoSessionAndOpenEndedSessionDoNotNeedAnAutoEnd() {
        let none = FlintSessionProtectionReport.make(active: nil, recovery: .init(outcome: .noSession),
            health: .init(), observedActivities: ["flint.session.old"], now: now)
        XCTAssertEqual(none.enabled, 0)
        XCTAssertEqual(none.observed, 0)
        XCTAssertTrue(none.failures.isEmpty)
        let open = FlintSessionProtectionReport.make(active: session(timed: false),
            recovery: .init(outcome: .running), health: .init(), observedActivities: [], now: now)
        XCTAssertEqual(open.enabled, 1)
        XCTAssertEqual(open.armed, 0)
        XCTAssertTrue(open.failures.isEmpty)
    }

    func testFailedNewStartRemainsVisibleWithoutInvalidatingOlderObservedSession() {
        let health = FlintSessionHealth(lastArmFailure: .init(sessionName: "New focus", reason: "refused", at: now))
        let report = FlintSessionProtectionReport.make(active: session(), recovery: .init(outcome: .running),
            health: health, observedActivities: [session().monitorName], now: now)
        XCTAssertEqual(report.armed, 1)
        XCTAssertEqual(report.failures.map(\.activityName), ["session.lastAttempt"])
        XCTAssertEqual(FlintWebReconcileEligibility.session(session(), report: report), session())
        let copy = FlintSessionHealthPresentation.messages(health: health, report: report).joined()
        XCTAssertTrue(copy.contains("New focus"))
        XCTAssertTrue(copy.contains("refused"))
        XCTAssertTrue(copy.contains("Recorded at"))
    }

    func testSettingsShowsEveryTimeAnomalyAndAuthorizationRecoveryFlags() {
        let kinds: [FlintSessionHealth.TimeAnomaly.Kind] = [
            .wallAhead, .wallBehind, .timeZoneChanged, .prematureEnd, .missedEndWarning
        ]
        var descriptions: Set<String> = []
        for kind in kinds {
            let health = FlintSessionHealth(lastTimeAnomaly: .init(kind: kind, seconds: 42,
                sessionID: "private-id", detectedAt: now), unauthorizedSince: now)
            let report = FlintArmingHealth.DomainReport(enabled: 1, attempted: 1, armed: 0,
                failures: [], notAuthorized: true, rearmFailed: true)
            let messages = FlintSessionHealthPresentation.messages(health: health, report: report, active: session())
            XCTAssertEqual(messages.count, 4)
            XCTAssertTrue(messages[0].contains("42 seconds"))
            XCTAssertTrue(messages[0].contains("Recorded at"))
            XCTAssertFalse(messages.joined().contains("private-id"))
            XCTAssertTrue(messages[1].contains("iOS isn't blocking it"))
            XCTAssertTrue(messages[2].contains("last check"))
            XCTAssertTrue(messages[3].contains("could not be re-registered"))
            descriptions.insert(messages[0])
        }
        XCTAssertEqual(descriptions.count, kinds.count)
    }

    func testDatedSessionHistoryDoesNotKeepProtectionInAttentionAfterItEnds() {
        let health = FlintSessionHealth(lastArmFailure: .init(sessionName: "Focus", reason: "old refusal", at: now),
            lastTimeAnomaly: .init(kind: .timeZoneChanged, seconds: 0, sessionID: "older", detectedAt: now))
        let report = FlintArmingHealth.DomainReport(enabled: 1, attempted: 0, armed: 1,
            failures: [], observedActivities: [session().monitorName])
        XCTAssertFalse(FlintSessionHealthPresentation.hasCurrentIssue(health: health, active: nil, report: report))
        XCTAssertFalse(FlintSessionHealthPresentation.hasCurrentIssue(health: health, active: session(), report: report))
        let messages = FlintSessionHealthPresentation.messages(health: health, report: report)
        XCTAssertTrue(messages[0].contains("old refusal"))
        XCTAssertFalse(messages[1].contains("0 seconds"))
        var current = health
        current.lastTimeAnomaly = .init(kind: .wallBehind, seconds: 10, sessionID: session().id, detectedAt: now)
        XCTAssertTrue(FlintSessionHealthPresentation.hasCurrentIssue(health: current, active: session(), report: report))
        current.unauthorizedSince = now
        let endedCopy = FlintSessionHealthPresentation.messages(health: current, report: report, active: nil).joined()
        XCTAssertTrue(endedCopy.contains("last session lacked Screen Time access"))
        XCTAssertFalse(endedCopy.contains("A session is running"))
    }

    private func session(timed: Bool = true) -> FlintActiveSession {
        .init(id: "session", name: "Focus", startedAt: now,
              endsAt: timed ? now.addingTimeInterval(3600) : nil,
              breakLevel: .easy, monitorName: "flint.session.current")
    }
}
