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
        XCTAssertEqual(plan?.staleAt, now.addingTimeInterval(290))
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
}
