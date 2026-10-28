import XCTest
import FlintCore
@testable import Flint

/// App/Site detail wording follows only what the contributors query returned (ADR-012 §3/§5/§8).
final class AppDetailPresentationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private typealias Cause = FlintProtectionStatusSnapshot.Cause

    private func cause(_ id: String, _ tier: BreakLevel, end: TimeInterval?, enforcing: Bool = true,
                       kind: FlintProtectionStatusSnapshot.Kind = .recurringSchedule) -> Cause {
        Cause(id: id, kind: kind, displayName: id, tier: tier,
              end: end.map { now.addingTimeInterval($0) }, enforcing: enforcing)
    }

    private func make(_ state: FlintProtectionStatusSnapshot.State = .protected, _ causes: [Cause] = [],
                      count: Int? = nil, cap: Int? = nil, uncertain: Bool = false) -> AppDetailPresentation {
        AppDetailPresentation(.init(state: state, causes: causes, openCount: count, openCap: cap,
                                    coverageUncertain: uncertain))
    }

    func testUnavailableNeverListsCausesOrCounts() {
        let p = make(.unavailable, [cause("a", .hardcore, end: 60)], count: 1, cap: 3)
        XCTAssertEqual(p.headline, .unavailable)
        XCTAssertTrue(p.rows.isEmpty)
        XCTAssertNil(p.opens)
    }

    func testStrongestEnforcingCauseHeadlinesWithLatestEnforcingEnd() {
        let p = make(.protected, [cause("easy", .easy, end: 900), cause("hard", .hardcore, end: 600),
                                  cause("idle", .hardcore, end: 9_000, enforcing: false)])
        XCTAssertEqual(p.headline, .blocked(name: "hard", until: now.addingTimeInterval(900)))
        XCTAssertEqual(p.rows.map(\.id), ["hard", "easy", "idle"])
    }

    func testOngoingEnforcingCauseMeansUntilStopped() {
        let p = make(.protected, [cause("manual", .easy, end: nil, kind: .session), cause("s", .harder, end: 600)])
        XCTAssertEqual(p.headline, .blocked(name: "s", until: nil))
    }

    func testEqualTierTieBreaksByLatestEndThenID() {
        let ordered = AppDetailPresentation.ordered([cause("b", .harder, end: 600), cause("a", .harder, end: 600),
                                                     cause("c", .harder, end: 1_200)])
        XCTAssertEqual(ordered.map(\.id), ["c", "a", "b"])
    }

    func testCoveredButNotBlockingAndUncertainAndNotCovered() {
        XCTAssertEqual(make(.protected, [cause("limit", .easy, end: nil, enforcing: false, kind: .timeLimit)]).headline,
                       .coveredNotBlocking)
        XCTAssertEqual(make(.protected, [], uncertain: true).headline, .uncertain)
        XCTAssertEqual(make(.notProtected).headline, .notCovered)
        XCTAssertEqual(make(.suspended, [cause("a", .hardcore, end: 60)]).headline, .paused)
    }

    func testOpensLineOnlyWithACapAndNeverInventsAZeroCount() {
        XCTAssertNil(make(.protected).opens)
        XCTAssertEqual(make(.protected, count: 2, cap: 3).opens, .used(2, of: 3))
        XCTAssertEqual(make(.protected, count: nil, cap: 3).opens, .unavailable(cap: 3))
    }

    func testDegradedAndUncertaintyNotes() {
        XCTAssertTrue(make(.degraded).showsDegradedNote)
        XCTAssertTrue(make(.protected, [cause("a", .easy, end: 60)], uncertain: true).showsUncertaintyNote)
        XCTAssertFalse(make(.protected, [], uncertain: true).showsUncertaintyNote)
    }
}
