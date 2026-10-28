import XCTest
import FlintCore

/// ADR-012 §1–§6 decisions as pure functions. Case IDs refer to the contract's §14 PC-xx table.
final class FlintProtectionPolicyTests: XCTestCase {

    private let end = Date(timeIntervalSince1970: 1_793_210_400)

    private func active(_ tier: BreakLevel, _ kind: FlintCauseState.Kind = .session, n: Int = 0) -> FlintCauseState {
        FlintCauseState(kind: kind, tier: tier, isActive: true, end: end, harderCount: n)
    }

    private func inactive(_ tier: BreakLevel, _ kind: FlintCauseState.Kind = .recurringSchedule) -> FlintCauseState {
        FlintCauseState(kind: kind, tier: tier, isActive: false)
    }

    private typealias Snapshot = FlintProtectionSnapshot<String>

    private func block(_ apps: Set<String>, cats: Set<String> = [], web: Set<String> = [],
                       tier: BreakLevel = .hardcore, end: Date?, budget: Int? = nil) -> Snapshot {
        Snapshot(mode: .blockList(applications: apps, categories: cats, webDomains: web), tier: tier, end: end, budget: budget)
    }

    // MARK: §2 matrix — every row, active and inactive

    func testStopEarlyByTier() {
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: active(.easy)), .allow(confirm: true))
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: active(.harder)), .wait(seconds: 60), "PC-07")
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: active(.hardcore)), .deny(.hardcoreCommitment))
        for tier in BreakLevel.allCases {
            XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: inactive(tier)), .notApplicable)
        }
    }

    func testReplacementPC04PC05() {
        XCTAssertEqual(FlintProtectionPolicy.decide(.replace(dominating: false), for: active(.hardcore)),
                       .deny(.wouldWeakenHardcore), "PC-04: weaker replacement of Hardcore is denied")
        XCTAssertEqual(FlintProtectionPolicy.decide(.replace(dominating: true), for: active(.hardcore)),
                       .allow(confirm: true), "PC-05: dominating replacement allowed with preview")
        XCTAssertEqual(FlintProtectionPolicy.decide(.replace(dominating: false), for: active(.harder, n: 1)),
                       .wait(seconds: 300))
        XCTAssertEqual(FlintProtectionPolicy.decide(.replace(dominating: false), for: active(.easy)),
                       .allow(confirm: true))
        XCTAssertEqual(FlintProtectionPolicy.decide(.replace(dominating: true), for: inactive(.hardcore)), .notApplicable)
    }

    func testStartAdditiveNeverRetiresExistingProtection() {
        for tier in BreakLevel.allCases {
            if case .allow = FlintProtectionPolicy.decide(.startAdditive, for: active(tier)) {} else {
                XCTFail("additive start must be allowed beside an active \(tier) cause")
            }
        }
        XCTAssertEqual(FlintProtectionPolicy.decide(.startAdditive, for: inactive(.hardcore)), .allow(confirm: true),
                       "finite-end disclosure for Hardcore")
    }

    func testBreaksAndCredits() {
        XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: active(.easy)), .allow(confirm: true))
        XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: active(.harder, n: 2)), .wait(seconds: 900))
        XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: active(.hardcore)), .deny(.hardcoreCommitment))
        for tier in BreakLevel.allCases {
            XCTAssertEqual(FlintProtectionPolicy.decide(.acceptOpenCredit, for: active(tier, .openLimit)),
                           .allow(confirm: true), "quota credits are part of the commitment; no Harder wait")
        }
        XCTAssertEqual(FlintProtectionPolicy.decide(.acceptOpenCredit, for: active(.easy, .session)), .notApplicable)
    }

    func testEditsPC11PC13PC15PC16() {
        for kind in [FlintCauseState.Kind.recurringSchedule, .timeLimit, .openLimit, .sleep, .session] {
            XCTAssertEqual(FlintProtectionPolicy.decide(.edit(.weaken), for: active(.hardcore, kind)), .deferUntil(end),
                           "PC-11/15/16: active Hardcore \(kind) weakening is deferred")
            XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: active(.hardcore, kind)), .deferUntil(end))
            XCTAssertEqual(FlintProtectionPolicy.decide(.delete, for: active(.hardcore, kind)), .deferUntil(end))
        }
        XCTAssertEqual(FlintProtectionPolicy.decide(.edit(.strengthen), for: active(.hardcore)), .allow(confirm: true))
        XCTAssertEqual(FlintProtectionPolicy.decide(.edit(.cosmetic), for: active(.hardcore)), .allow(confirm: false))
        XCTAssertEqual(FlintProtectionPolicy.decide(.edit(.weaken), for: active(.harder)), .wait(seconds: 60))
        XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: active(.easy)), .allow(confirm: true))
        for tier in BreakLevel.allCases {
            XCTAssertEqual(FlintProtectionPolicy.decide(.edit(.weaken), for: inactive(tier)), .allow(confirm: false),
                           "PC-13: inactive templates stay editable")
            XCTAssertEqual(FlintProtectionPolicy.decide(.delete, for: inactive(tier, .oneShotSchedule)), .allow(confirm: true))
        }
    }

    // MARK: §4 Harder waits

    func testHarderWaitScheduleIsCappedPC18() {
        XCTAssertEqual((0...5).map(FlintProtectionPolicy.harderWait(forCount:)), [60, 300, 900, 900, 900, 900])
        XCTAssertEqual(FlintProtectionPolicy.harderWait(forCount: -3), 60)
    }

    func testMultiCauseOperationsPC19() {
        XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: [active(.harder, n: 0), active(.harder, n: 2)]),
                       .wait(seconds: 900), "longest wait of the affected causes")
        XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: [active(.easy), active(.hardcore)]),
                       .deny(.hardcoreCommitment), "PC-25: any Hardcore contributor denies")
        let later = end.addingTimeInterval(3600)
        let defer2 = FlintCauseState(kind: .timeLimit, tier: .hardcore, isActive: true, end: later)
        XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: [active(.hardcore), defer2, active(.harder)]),
                       .deferUntil(later), "defer to the latest affected end")
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: [inactive(.easy)]), .notApplicable)
        XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: [active(.easy), inactive(.harder)]), .allow(confirm: true))
    }

    // MARK: §3 dominance and §2 edit classification

    func testDominancePC05PC06() {
        let old = block(["A", "B"], end: end)
        XCTAssertTrue(FlintProtectionPolicy.dominates(block(["A", "B", "C"], end: end.addingTimeInterval(600)), over: old),
                      "PC-05: superset, same tier, later end")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A"], end: end.addingTimeInterval(3600)), over: old),
                       "PC-06: longer but drops B")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A", "B"], tier: .harder, end: end), over: old), "lower tier")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A", "B"], end: end.addingTimeInterval(-1)), over: old), "earlier end")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A", "B"], end: end), over: block(["A", "B"], end: nil)),
                       "finite never covers a legacy open-ended cause")
        XCTAssertTrue(FlintProtectionPolicy.dominates(block(["A", "B"], end: nil), over: old))
    }

    func testEditImpactClassification() {
        let old = block(["A"], cats: ["social"], web: ["x.com"], end: end, budget: 30)
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: old), .cosmetic)
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A", "B"], cats: ["social"], web: ["x.com"], end: end, budget: 20)),
                       .strengthen, "more targets, smaller budget")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A"], cats: [], web: ["x.com"], end: end, budget: 30)),
                       .weaken, "dropping an opaque category")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A"], cats: ["social"], web: ["x.com"], end: end, budget: 31)),
                       .weaken, "raising the budget")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A"], cats: ["social"], web: ["x.com"], end: end, budget: nil)),
                       .weaken, "removing the limit")
        let allow = Snapshot(mode: .allowList(allowedApplications: ["Phone", "Maps"]), tier: .harder, end: end)
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: allow, to: Snapshot(mode: .allowList(allowedApplications: ["Phone"]), tier: .harder, end: end)),
                       .strengthen, "fewer allowed apps")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: allow, to: Snapshot(mode: .allowList(allowedApplications: ["Phone", "Maps", "X"]), tier: .harder, end: end)),
                       .weaken, "adding an allowed app")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: allow, to: block(["Phone"], tier: .harder, end: end)),
                       .weaken, "a mode change is never provably stronger")
    }

    // MARK: §1 new-cause validation

    func testStrictSessionsNeedAFiniteEndOfAtMost24Hours() {
        XCTAssertNil(FlintProtectionPolicy.issue(forNewSession: .easy, duration: nil), "Easy may run until stopped")
        XCTAssertEqual(FlintProtectionPolicy.issue(forNewSession: .harder, duration: nil), .strictNeedsFiniteEnd)
        XCTAssertEqual(FlintProtectionPolicy.issue(forNewSession: .hardcore, duration: nil), .strictNeedsFiniteEnd,
                       "PC-79: new open-ended Hardcore is invalid")
        XCTAssertNil(FlintProtectionPolicy.issue(forNewSession: .hardcore, duration: 24 * 3600))
        XCTAssertEqual(FlintProtectionPolicy.issue(forNewSession: .hardcore, duration: 24 * 3600 + 1), .tooLong)
        XCTAssertEqual(FlintProtectionPolicy.issue(forNewSession: .easy, duration: 0), .nonPositive)
    }

    func testOccurrencesAllowA25HourDSTDayButNotMore() {
        let start = Date(timeIntervalSince1970: 0)
        XCTAssertNil(FlintProtectionPolicy.issue(forOccurrenceFrom: start, to: start.addingTimeInterval(25 * 3600)))
        XCTAssertNil(FlintProtectionPolicy.issue(forOccurrenceFrom: start, to: start.addingTimeInterval(26 * 3600)))
        XCTAssertEqual(FlintProtectionPolicy.issue(forOccurrenceFrom: start, to: start.addingTimeInterval(26 * 3600 + 1)), .tooLong)
        XCTAssertEqual(FlintProtectionPolicy.issue(forOccurrenceFrom: start, to: start), .nonPositive)
    }

    // MARK: §6 Emergency Pass

    func testRollingPassPC39PC40PC47() {
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.used(elapsed: 604_799), hasActiveCommitment: true),
                       .coolingDown(remaining: 1), "PC-39: one second early")
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.used(elapsed: 604_800), hasActiveCommitment: true), .available)
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.used(elapsed: 10 * 604_800), hasActiveCommitment: true), .available,
                       "no banking: still exactly one")
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.neverUsed, hasActiveCommitment: true), .available)
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.neverUsed, hasActiveCommitment: false), .nothingToRecover)
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.unknown, hasActiveCommitment: true), .unavailable, "PC-47")
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.used(elapsed: -5), hasActiveCommitment: true), .unavailable,
                       "a future use timestamp is corrupt, never a fresh pass")
    }
}
