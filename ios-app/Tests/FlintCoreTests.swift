import XCTest
import FamilyControls
import FlintCore

final class FlintCoreTests: XCTestCase {

    func testBreakLevelRoundTrips() throws {
        for level in BreakLevel.allCases {
            let data = try JSONEncoder().encode(level)
            XCTAssertEqual(level, try JSONDecoder().decode(BreakLevel.self, from: data))
        }
    }

    func testSessionRoundTrips() throws {
        let session = FlintSession(
            name: "Deep work",
            breakLevel: .hardcore,
            schedule: FlintSchedule(daysOfWeek: [2, 3, 4, 5, 6], startHour: 9, endHour: 17),
            allowListMode: false
        )
        let data = try JSONEncoder().encode(session)
        XCTAssertEqual(session, try JSONDecoder().decode(FlintSession.self, from: data))
    }

    func testShieldCapBoundary() {
        XCTAssertTrue(FlintShieldLimits.withinCap(50))
        XCTAssertFalse(FlintShieldLimits.withinCap(51))
        XCTAssertEqual(FlintShieldLimits.tokenCap, 50)
    }

    func testActiveSessionExpiryAndRemaining() {
        let expired = FlintActiveSession(
            name: "x", startedAt: Date().addingTimeInterval(-120),
            endsAt: Date().addingTimeInterval(-60), breakLevel: .hardcore, monitorName: "m"
        )
        XCTAssertTrue(expired.isExpired)
        XCTAssertEqual(expired.remaining, 0, accuracy: 0.01)

        let future = FlintActiveSession(
            name: "y", startedAt: Date(),
            endsAt: Date().addingTimeInterval(300), breakLevel: .easy, monitorName: "m"
        )
        XCTAssertFalse(future.isExpired)
        XCTAssertGreaterThan(future.remaining, 0)

        let openEnded = FlintActiveSession(
            name: "z", startedAt: Date(), endsAt: nil, breakLevel: .easy, monitorName: "m"
        )
        XCTAssertFalse(openEnded.isExpired)
        XCTAssertEqual(openEnded.remaining, 0)
    }

    func testHardcoreCannotStopUntilExpired() {
        let controller = FlintSessionController()

        let runningHardcore = FlintActiveSession(
            name: "h", startedAt: Date(), endsAt: Date().addingTimeInterval(300),
            breakLevel: .hardcore, monitorName: "m"
        )
        XCTAssertFalse(controller.canStop(runningHardcore), "Hardcore must not be stoppable mid-session")

        let expiredHardcore = FlintActiveSession(
            name: "h", startedAt: Date().addingTimeInterval(-600), endsAt: Date().addingTimeInterval(-1),
            breakLevel: .hardcore, monitorName: "m"
        )
        XCTAssertTrue(controller.canStop(expiredHardcore), "Hardcore becomes stoppable once expired")

        let easy = FlintActiveSession(
            name: "e", startedAt: Date(), endsAt: Date().addingTimeInterval(300),
            breakLevel: .easy, monitorName: "m"
        )
        XCTAssertTrue(controller.canStop(easy), "Easy can always be stopped")
    }

    func testScheduleRuleDayGate() {
        let cal = Calendar(identifier: .gregorian)
        let monday = DateComponents(calendar: cal, year: 2024, month: 1, day: 1).date! // Mon
        let sunday = DateComponents(calendar: cal, year: 2024, month: 1, day: 7).date! // Sun

        let workWeek = FlintScheduleRule(
            name: "Work",
            schedule: FlintSchedule(daysOfWeek: [2, 3, 4, 5, 6], startHour: 9, endHour: 17) // Mon–Fri
        )
        XCTAssertTrue(workWeek.appliesOn(monday, calendar: cal))
        XCTAssertFalse(workWeek.appliesOn(sunday, calendar: cal))

        let everyDay = FlintScheduleRule(
            name: "All",
            schedule: FlintSchedule(startHour: 0, endHour: 23) // empty days = every day
        )
        XCTAssertTrue(everyDay.appliesOn(sunday, calendar: cal))
    }

    func testEmergencyPassWeeklyAvailability() {
        let now = Date()
        XCTAssertTrue(FlintEmergencyPass.isAvailable(lastUsed: nil, now: now), "Never used → available")
        XCTAssertFalse(
            FlintEmergencyPass.isAvailable(lastUsed: now.addingTimeInterval(-3 * 24 * 3600), now: now),
            "Used 3 days ago → not available"
        )
        XCTAssertTrue(
            FlintEmergencyPass.isAvailable(lastUsed: now.addingTimeInterval(-8 * 24 * 3600), now: now),
            "Used 8 days ago → available again"
        )
        let lastUsed = now.addingTimeInterval(-2 * 24 * 3600)
        XCTAssertEqual(
            FlintEmergencyPass.nextAvailable(lastUsed: lastUsed)!.timeIntervalSince1970,
            lastUsed.addingTimeInterval(7 * 24 * 3600).timeIntervalSince1970,
            accuracy: 0.01
        )
    }

    func testLimitRuleRoundTripsAndNames() throws {
        let limit = FlintLimitRule(name: "Social", thresholdMinutes: 30, breakLevel: .harder)
        let data = try JSONEncoder().encode(limit)
        XCTAssertEqual(limit, try JSONDecoder().decode(FlintLimitRule.self, from: data))
        XCTAssertEqual(limit.activityName, "flint.limit.\(limit.id)")
        XCTAssertEqual(limit.eventName, "flint.limitEvent.\(limit.id)")
        XCTAssertEqual(limit.storeName, "flintL.\(limit.id)")
    }

    func testAppGroupRoundTrips() throws {
        let group = FlintAppGroup(name: "Social", selection: FamilyActivitySelection())
        let data = try JSONEncoder().encode(group)
        XCTAssertEqual(group, try JSONDecoder().decode(FlintAppGroup.self, from: data))
        XCTAssertEqual(group.itemCount, 0)
    }

    func testPINHashDeterministicAndSensitive() {
        let h = FlintPIN.hash("1234", salt: "abc")
        XCTAssertEqual(h, FlintPIN.hash("1234", salt: "abc"))    // deterministic
        XCTAssertNotEqual(h, FlintPIN.hash("1234", salt: "xyz")) // salt matters
        XCTAssertNotEqual(h, FlintPIN.hash("0000", salt: "abc")) // pin matters
        XCTAssertEqual(h.count, 64)                              // SHA-256 hex
    }

    func testOpenLimitCountsAndResetsDaily() {
        var state = FlintOpenLimitState()
        let r1 = FlintOpenLimit.registerOpen("app", in: state); state = r1.state
        XCTAssertEqual(r1.count, 1)
        let r2 = FlintOpenLimit.registerOpen("app", in: state); state = r2.state
        XCTAssertEqual(r2.count, 2)
        XCTAssertEqual(FlintOpenLimit.count("app", in: state), 2)
        XCTAssertEqual(FlintOpenLimit.count("other", in: state), 0)

        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: Date())!
        let r3 = FlintOpenLimit.registerOpen("app", in: state, now: tomorrow)
        XCTAssertEqual(r3.count, 1, "new day resets the count")
    }
}
