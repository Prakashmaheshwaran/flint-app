import XCTest
import FlintCore
@testable import Flint

final class FlintGlanceProtectionAdapterTests: XCTestCase {
    private typealias Status = FlintProtectionStatusSnapshot
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func session(end: Date?, armed: Bool = true, tier: BreakLevel = .harder) -> Status.Session {
        .init(id: "fixture", kind: .pomodoro, name: "Deep study", tier: tier,
              startedAt: now.addingTimeInterval(-120), logicalEnd: end, origin: .appUI, armed: armed)
    }

    private func snapshot(_ state: Status.State = .protected, version: Int = 1,
                          session: Status.Session? = nil, reasons: [String] = [],
                          validity: Date? = nil, recovery: Date? = nil) -> Status {
        let cause = Status.Cause(id: "cause", kind: .pomodoro, displayName: "Deep study",
            tier: session?.tier ?? .harder, end: session?.logicalEnd, enforcing: true)
        return .init(version: version, observedAt: now.addingTimeInterval(-10),
                     validUntil: validity ?? now.addingTimeInterval(600), state: state,
                     session: session, headline: cause, causes: [cause],
                     nextOccurrence: .init(causeID: "next", kind: .recurringSchedule, displayName: "Evening focus",
                                           start: now.addingTimeInterval(1200), end: now.addingTimeInterval(1800)),
                     pass: .init(eligibility: .available, recoveryUntil: recovery), degradedReasons: reasons)
    }

    func testPublicTypedFieldsReachTheActualPresentationAndActivityPlan() {
        let source = snapshot(session: session(end: now.addingTimeInterval(600)))
        let value = FlintGlanceReader.project(source, at: now)
        XCTAssertEqual(value.protection, .enforcing)
        XCTAssertEqual(value.activeFocusSessionID, "fixture")
        XCTAssertEqual(value.session?.cause, .pomodoro)
        XCTAssertEqual(value.next?.name, "Evening focus")
        let plan = FlintGlanceActivityPlan.make(value, optedIn: true, systemEnabled: true, at: now)
        XCTAssertEqual(plan?.cause, "Deep study")
        XCTAssertEqual(plan?.tier, "Harder")
        XCTAssertEqual(plan?.endsAt, now.addingTimeInterval(600))
        XCTAssertEqual(plan?.staleAt, now.addingTimeInterval(600))
        XCTAssertEqual(FlintGlancePresentation(value, at: now).causeSummary, "Deep study · Harder")
    }

    func testOpenEndedArmedEasyIdentityIsHistoryOnlyWithoutInventingEnd() {
        let value = FlintGlanceReader.project(snapshot(session: session(end: nil, tier: .easy)), at: now)
        XCTAssertEqual(value.protection, .enforcing)
        XCTAssertEqual(value.activeFocusSessionID, "fixture")
        XCTAssertNil(value.session)
        XCTAssertNil(FlintGlancePresentation(value, at: now).countdownEnd)
        XCTAssertNil(FlintGlanceActivityPlan.make(value, optedIn: true, systemEnabled: true, at: now))
        XCTAssertEqual(value.replacingHistory(.init(availability: .value, seconds: 120,
            inProgress: true, readAt: now)).activeFocusSessionID, "fixture")
    }

    func testUnarmedSessionDoesNotGainActivityOrRunningHistoryIdentity() {
        let value = FlintGlanceReader.project(snapshot(session: session(end: now.addingTimeInterval(600), armed: false)), at: now)
        XCTAssertEqual(value.protection, .degraded)
        XCTAssertNil(value.activeFocusSessionID)
        XCTAssertNil(FlintGlanceActivityPlan.make(value, optedIn: true, systemEnabled: true, at: now))
    }

    func testMissingUnknownClockAndUnavailableSnapshotsStayUnavailable() {
        XCTAssertEqual(FlintGlanceReader.project(nil, at: now).protection, .unavailable)
        for source in [snapshot(version: 2), snapshot(.unavailable), snapshot(reasons: ["time_uncertain"])] {
            let value = FlintGlanceReader.project(source, at: now)
            XCTAssertEqual(value.protection, .unavailable)
            XCTAssertNil(value.activeFocusSessionID)
        }
    }

    func testRecoveryAndInactiveNeverProjectRunningSessionIdentity() {
        let active = session(end: now.addingTimeInterval(600))
        let recovery = FlintGlanceReader.project(snapshot(.suspended, session: active,
            recovery: now.addingTimeInterval(600)), at: now)
        XCTAssertEqual(recovery.protection, .pass)
        XCTAssertNil(recovery.activeFocusSessionID)
        XCTAssertNil(FlintGlanceReader.project(snapshot(.notProtected, session: active), at: now).activeFocusSessionID)
    }

    func testCommitPublisherWritesBeforeCanonicalReloadAndDarwinThenFeedsGlance() throws {
        let suite = "FlintGlancePublisher.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: directory)
        }
        let clock = FlintClock(now: { self.now }, monotonic: { 1000 }, bootSessionID: { "glance-test" }, calendar: { .current })
        var marker: Date?
        let ledger = FlintProtectionLedger(directory: directory, clock: clock,
            initializedMarker: { marker }, setInitializedMarker: { marker = $0 })
        _ = try ledger.transaction { $0.pass.usage = .neverUsed }.get()
        var authorized = true
        var armed = true
        let active = FlintActiveSession(id: "published-session", name: "Deep study",
            startedAt: now.addingTimeInterval(-600), endsAt: now.addingTimeInterval(3600),
            breakLevel: .harder, monitorName: "glance-test-monitor",
            monotonicStart: 400, bootSessionID: "glance-test")
        let environment = FlintProtectionStatus.ObservationEnvironment(
            authorized: { authorized }, registeredActivities: { armed ? [active.monitorName] : [] },
            clock: clock, loadActiveSession: { active }, hasActiveSessionRecord: { true },
            loadArmingHealth: { .init() })
        var order: [String] = []
        let effects = FlintProtectionStatus.PublicationEffects(reloadTimelines: { kind in
            XCTAssertEqual(kind, "FlintProtectionWidget")
            XCTAssertNotNil(defaults.data(forKey: "flint.status.v1"))
            XCTAssertEqual(FlintProtectionStatus.load(now: self.now, from: defaults)?.session?.id, active.id)
            order.append("reload")
        }, postDarwinNotification: { name in
            XCTAssertEqual(name, "com.flint.peakfocus.status.changed")
            XCTAssertEqual(order, ["reload"])
            order.append("darwin")
        })
        let published = FlintProtectionStatus.publishAfterCommit(now: now, defaults: defaults,
            ledger: { ledger }, environment: environment, effects: effects)
        XCTAssertEqual(order, ["reload", "darwin"])
        XCTAssertEqual(published.state, .protected)
        let later = now.addingTimeInterval(1800)
        let input = FlintGlanceReader.project(FlintProtectionStatus.load(now: later, from: defaults), at: later)
        XCTAssertEqual(input.validUntil, published.validUntil)
        XCTAssertEqual(FlintGlancePresentation(input, at: later).status, .enforcing)
        let plan = try XCTUnwrap(FlintGlanceActivityPlan.make(input, optedIn: true, systemEnabled: true, at: later))
        XCTAssertEqual(plan.endsAt, active.endsAt)
        XCTAssertEqual(plan.staleAt, published.validUntil)
        XCTAssertTrue(FlintGlancePresentation.boundaries(input, after: later).contains(plan.staleAt))
        // Strict at the boundary even though B's loader permits equality.
        let boundary = FlintGlanceReader.project(FlintProtectionStatus.load(now: plan.staleAt, from: defaults), at: plan.staleAt)
        XCTAssertEqual(boundary.protection, .unavailable)
        XCTAssertNil(FlintGlanceActivityPlan.make(boundary, optedIn: true, systemEnabled: true, at: plan.staleAt))
        XCTAssertNil(FlintProtectionStatus.load(now: plan.staleAt.addingTimeInterval(1), from: defaults))

        armed = false
        let degraded = FlintProtectionStatus.publishAfterCommit(now: now, defaults: defaults,
            ledger: { ledger }, environment: environment, effects: .silent)
        XCTAssertEqual(degraded.state, .degraded)
        let unarmed = FlintGlanceReader.project(FlintProtectionStatus.load(now: now, from: defaults), at: now)
        XCTAssertEqual(FlintGlancePresentation(unarmed, at: now).status, .degraded)
        XCTAssertNil(FlintGlanceActivityPlan.make(unarmed, optedIn: true, systemEnabled: true, at: now))
        authorized = false
        let missing = FlintProtectionStatus.publishAfterCommit(now: now, defaults: defaults,
            ledger: { nil }, environment: environment, effects: .silent)
        XCTAssertEqual(missing.state, .unavailable)
        XCTAssertEqual(FlintGlanceReader.project(FlintProtectionStatus.load(now: now, from: defaults), at: now).protection, .unavailable)
    }
}
