import XCTest
import FamilyControls
import ManagedSettings
import FlintCore

final class FlintProtectionStatusCoverageTests: XCTestCase {
    typealias Builder = FlintProtectionStatusBuilder
    private let now = Date(timeIntervalSince1970: 1_793_188_800)

    private func token<T: Decodable>(_ value: String, as type: T.Type) throws -> T {
        let json = try JSONSerialization.data(withJSONObject: ["data": Data(value.utf8).base64EncodedString()])
        return try JSONDecoder().decode(type, from: json)
    }

    private func inputs(ids: [(String, FlintProtectionStatusSnapshot.Kind)]) -> Builder.Inputs {
        .init(now: now, rules: ids.map {
            .init(id: $0.0, kind: $0.1, displayName: "Rule", tier: .easy, armed: true, observed: true,
                  currentWindow: DateInterval(start: now.addingTimeInterval(-60), end: now.addingTimeInterval(600)),
                  active: true, thresholdReached: true, opensAllowed: 3)
        }, authorized: true, pass: .init(eligibility: .available), timeIntegrity: .consistent)
    }

    func testAdapterUsesExactExistingApplicationAndDomainKeyEncoding() throws {
        let app = try token("fictional-app", as: ApplicationToken.self)
        let domain = try token("fictional-site", as: WebDomainToken.self)
        XCTAssertEqual(FlintProtectionStatus.targetKey(for: app), FlintOpenLimit.key(for: app))
        XCTAssertEqual(FlintProtectionStatus.targetKey(for: domain), FlintOpenLimit.key(for: domain))
        var selection = FamilyActivitySelection()
        selection.applicationTokens = [app]
        selection.webDomainTokens = [domain]
        let schedule = FlintScheduleRule(id: "schedule", name: "Study",
            schedule: .init(startHour: 0, endHour: 23), selection: selection)
        let limit = FlintLimitRule(id: "time", name: "Budget", thresholdMinutes: 10, selection: selection)
        let open = FlintOpenLimitRule(id: "open", name: "Opens", opensAllowed: 3, selection: selection)
        let protection = inputs(ids: [("schedule", .recurringSchedule), ("time", .timeLimit), ("open", .openLimit)])
        let scopes = FlintProtectionStatus.coverageScopes(for: .init(protection: protection,
            schedules: [schedule], timeLimits: [limit], openLimits: [open]))
        let expected = FlintProtectionStatus.TargetScope.only([FlintOpenLimit.key(for: app), FlintOpenLimit.key(for: domain)])
        XCTAssertEqual(scopes, ["schedule": expected, "time": expected, "open": expected])
        let result = FlintProtectionStatus.contributors(for: FlintOpenLimit.key(for: app), inputs: protection,
                                                       coverageByCauseID: scopes)
        XCTAssertEqual(result.causes.count, 3)
        XCTAssertFalse(result.coverageUncertain)
    }

    func testAllowListUsesAllExceptAndOpaqueCategoryMembershipStaysUnknown() throws {
        let app = try token("fictional-allowed-app", as: ApplicationToken.self)
        let category = try token("fictional-category", as: ActivityCategoryToken.self)
        var selection = FamilyActivitySelection(); selection.applicationTokens = [app]
        let allow = FlintScheduleRule(id: "allow", name: "Allow", schedule: .init(startHour: 0, endHour: 23),
                                      selection: selection, allowListMode: true)
        selection.categoryTokens = [category]
        let opaque = FlintScheduleRule(id: "category", name: "Category", schedule: allow.schedule,
                                       selection: selection)
        let protection = inputs(ids: [("allow", .recurringSchedule), ("category", .recurringSchedule)])
        let scopes = FlintProtectionStatus.coverageScopes(for: .init(protection: protection, schedules: [allow, opaque]))
        XCTAssertEqual(scopes["allow"], .allExcept([FlintOpenLimit.key(for: app)]))
        XCTAssertEqual(scopes["category"], .unknown)
        let result = FlintProtectionStatus.contributors(for: FlintOpenLimit.key(for: app), inputs: protection,
                                                       coverageByCauseID: scopes)
        XCTAssertTrue(result.causes.isEmpty)
        XCTAssertTrue(result.coverageUncertain)
    }

    func testFrozenSessionSelectionOnlyAndInactiveRulesOmitted() throws {
        let app = try token("fictional-frozen-app", as: ApplicationToken.self)
        var selection = FamilyActivitySelection(); selection.applicationTokens = [app]
        var protection = inputs(ids: [])
        protection.activeSession = .init(.init(id: "session", name: "Study", startedAt: now,
            endsAt: now.addingTimeInterval(600), breakLevel: .harder, monitorName: "monitor"), armed: true)
        XCTAssertEqual(FlintProtectionStatus.coverageScopes(for: .init(protection: protection)), ["session": .unknown])
        let inactive = FlintScheduleRule(id: "inactive", name: "Inactive", schedule: .init(startHour: 0, endHour: 23),
                                         selection: selection)
        let scopes = FlintProtectionStatus.coverageScopes(for: .init(protection: protection,
            sessionSelection: selection, schedules: [inactive]))
        XCTAssertEqual(scopes, ["session": .only([FlintOpenLimit.key(for: app)])])
        protection.available = false
        XCTAssertTrue(FlintProtectionStatus.coverageScopes(for: .init(protection: protection,
            sessionSelection: selection)).isEmpty)
    }
}
