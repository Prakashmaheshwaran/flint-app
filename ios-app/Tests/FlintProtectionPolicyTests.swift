import XCTest
import FlintCore

/// ADR-012 §1–§6 decisions as pure functions. Case IDs refer to the contract's §14 PC-xx table.
final class FlintProtectionPolicyTests: XCTestCase {

    private let end = Date(timeIntervalSince1970: 1_793_210_400)

    private func active(_ tier: BreakLevel, _ kind: FlintCauseState.Kind = .session, n: Int = 0,
                        end: Date?? = nil, legacy: Bool = false, id: String = "c") -> FlintCauseState {
        FlintCauseState(causeID: id, kind: kind, tier: tier, isActive: true,
                        end: end ?? self.end, harderCount: n, legacyRights: legacy)
    }

    private func inactive(_ tier: BreakLevel, _ kind: FlintCauseState.Kind = .recurringSchedule) -> FlintCauseState {
        FlintCauseState(causeID: "t", kind: kind, tier: tier, isActive: false)
    }

    private typealias Snapshot = FlintProtectionSnapshot<String>

    private func block(_ apps: Set<String>, cats: Set<String> = [], web: Set<String> = [],
                       tier: BreakLevel = .hardcore, end: Date?, budget: Int? = nil) -> Snapshot {
        Snapshot(mode: .blockList(applications: apps, categories: cats, webDomains: web), tier: tier, end: end, budget: budget)
    }

    private let allKinds: [FlintCauseState.Kind] = [.session, .pomodoro, .recurringSchedule, .oneShotSchedule, .sleep, .timeLimit, .openLimit]

    // MARK: §2 matrix — rows on existing causes, active and inactive

    func testStopOwnerEndAndBreakByTierPC07PC08() {
        for action in [FlintProtectionAction.stopEarly, .ownerEnded, .ordinaryBreak] {
            XCTAssertEqual(FlintProtectionPolicy.decide(action, for: active(.easy)), .allow(confirm: true))
            XCTAssertEqual(FlintProtectionPolicy.decide(action, for: active(.harder)), .wait(seconds: 60), "PC-07 \(action)")
            XCTAssertEqual(FlintProtectionPolicy.decide(action, for: active(.hardcore)), .deny(.hardcoreCommitment),
                           "PC-08 \(action): a Focus turning off can't end a finite Hardcore cause")
            for tier in BreakLevel.allCases {
                XCTAssertEqual(FlintProtectionPolicy.decide(action, for: inactive(tier)), .notApplicable)
            }
        }
    }

    func testReplacementByTierPC04PC05() {
        XCTAssertEqual(FlintProtectionPolicy.decide(.replaceWeaker, for: active(.hardcore)), .deny(.wouldWeakenHardcore), "PC-04")
        XCTAssertEqual(FlintProtectionPolicy.decide(.replaceDominating, for: active(.hardcore)), .allow(confirm: true), "PC-05")
        XCTAssertEqual(FlintProtectionPolicy.decide(.replaceWeaker, for: active(.harder, n: 1)), .wait(seconds: 300))
        XCTAssertEqual(FlintProtectionPolicy.decide(.replaceWeaker, for: active(.easy)), .allow(confirm: true))
        XCTAssertEqual(FlintProtectionPolicy.decide(.replaceDominating, for: inactive(.hardcore)), .notApplicable)
    }

    func testEditsOffAndDeletePC11PC13PC15PC16() {
        for kind in allKinds {
            for action in [FlintProtectionAction.editWeaken, .toggleOff, .delete] {
                XCTAssertEqual(FlintProtectionPolicy.decide(action, for: active(.hardcore, kind)), .deferUntil(end),
                               "PC-11/15/16: active Hardcore \(kind) \(action) is deferred to the occurrence end")
                XCTAssertEqual(FlintProtectionPolicy.decide(action, for: active(.harder, kind)), .wait(seconds: 60))
                XCTAssertEqual(FlintProtectionPolicy.decide(action, for: active(.easy, kind)), .allow(confirm: true))
            }
            XCTAssertEqual(FlintProtectionPolicy.decide(.editStrengthen, for: active(.hardcore, kind)), .allow(confirm: true))
            XCTAssertEqual(FlintProtectionPolicy.decide(.editCosmetic, for: active(.hardcore, kind)), .allow(confirm: false))
        }
        for tier in BreakLevel.allCases {
            XCTAssertEqual(FlintProtectionPolicy.decide(.editWeaken, for: inactive(tier)), .allow(confirm: false),
                           "PC-13: inactive templates stay editable")
            XCTAssertEqual(FlintProtectionPolicy.decide(.editCosmetic, for: inactive(tier)), .allow(confirm: false))
            XCTAssertEqual(FlintProtectionPolicy.decide(.editStrengthen, for: inactive(tier)), .allow(confirm: true))
            XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: inactive(tier)), .allow(confirm: true))
            XCTAssertEqual(FlintProtectionPolicy.decide(.delete, for: inactive(tier, .oneShotSchedule)), .allow(confirm: true))
        }
    }

    func testAnOpenEndedHardcoreCauseIsNeverOfferedAnEndlessDeferral() {
        let openEnded = active(.hardcore, end: .some(nil))
        for action in [FlintProtectionAction.editWeaken, .toggleOff, .delete] {
            XCTAssertEqual(FlintProtectionPolicy.decide(action, for: openEnded), .deny(.legacyOpenEnded))
        }
    }

    // MARK: §12 legacy rights

    func testLegacyOpenEndedFocusSessionsKeepTheirPromisedExitPC79() {
        let hardcore = active(.hardcore, end: .some(nil), legacy: true)
        XCTAssertEqual(FlintProtectionPolicy.decide(.ownerEnded, for: hardcore), .allow(confirm: false),
                       "its Focus turning off was its promised end")
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: hardcore), .deny(.legacyOpenEnded))
        let harder = active(.harder, end: .some(nil), legacy: true)
        XCTAssertEqual(FlintProtectionPolicy.decide(.ownerEnded, for: harder), .allow(confirm: false))
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: harder), .wait(seconds: 60),
                       "the in-app stop it had keeps a (now persisted) wait")
        XCTAssertEqual(FlintProtectionPolicy.decide(.ownerEnded, for: active(.hardcore, end: .some(nil))),
                       .deny(.hardcoreCommitment), "a non-legacy cause gets no such exit")
    }

    func testLegacyRuleOccurrencesKeepUngatedEditingUntilTheyEnd() {
        for kind in [FlintCauseState.Kind.recurringSchedule, .sleep, .timeLimit, .openLimit] {
            let legacy = active(.hardcore, kind, legacy: true)
            for action in [FlintProtectionAction.editWeaken, .toggleOff, .delete] {
                XCTAssertEqual(FlintProtectionPolicy.decide(action, for: legacy), .allow(confirm: true), "\(kind) \(action)")
            }
            XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: legacy), .deny(.hardcoreCommitment),
                           "no new right is invented")
        }
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: active(.hardcore, legacy: true)), .deny(.hardcoreCommitment),
                       "a legacy finite Hardcore session already had no in-app stop")
    }

    // MARK: §2 row 1, §3 starts and commit-time checks

    func testStartsDiscloseHardcoreAndRespectCapacity() {
        XCTAssertEqual(FlintProtectionPolicy.decideStart(newTier: .easy, capacityAvailable: true), .allow(confirm: false))
        XCTAssertEqual(FlintProtectionPolicy.decideStart(newTier: .harder, capacityAvailable: true), .allow(confirm: false))
        XCTAssertEqual(FlintProtectionPolicy.decideStart(newTier: .hardcore, capacityAvailable: true), .allow(confirm: true),
                       "finite-end disclosure, also via automation")
        XCTAssertEqual(FlintProtectionPolicy.decideStart(newTier: .easy, capacityAvailable: false), .deny(.capacityUnavailable), "PC-02")
    }

    func testCommitTimeReplaceAndEditComputeImpactThemselves() {
        let old = block(["A", "B"], end: end)
        let cause = active(.hardcore)
        XCTAssertEqual(FlintProtectionPolicy.decideReplace(block(["A", "B", "C"], end: end.addingTimeInterval(600)), over: old, for: cause),
                       .allow(confirm: true), "PC-05")
        XCTAssertEqual(FlintProtectionPolicy.decideReplace(block(["A"], end: end.addingTimeInterval(3600)), over: old, for: cause),
                       .deny(.wouldWeakenHardcore), "PC-06")
        XCTAssertEqual(FlintProtectionPolicy.decideEdit(from: old, to: block(["A"], end: end), for: cause), .deferUntil(end))
        XCTAssertEqual(FlintProtectionPolicy.decideEdit(from: old, to: block(["A", "B", "C"], end: end), for: cause),
                       .allow(confirm: true), "PC-12: add B")
        XCTAssertEqual(FlintProtectionPolicy.decideEdit(from: old, to: old, for: cause), .allow(confirm: false))
    }

    // MARK: §5 Open credits

    func testOpenCreditsPC28PC32PC33PC37() {
        XCTAssertEqual(FlintProtectionPolicy.decideOpenCredit(creditsRemaining: 1, otherEnforcing: []), .allow(confirm: true),
                       "PC-30: the last credit is usable")
        XCTAssertEqual(FlintProtectionPolicy.decideOpenCredit(creditsRemaining: 0, otherEnforcing: []), .deny(.noCreditsLeft), "PC-28")
        XCTAssertEqual(FlintProtectionPolicy.decideOpenCredit(creditsRemaining: 3, otherEnforcing: [active(.easy)]),
                       .deny(.otherCauseBlocks), "PC-32/33: never charge while another cause still blocks")
        XCTAssertEqual(FlintProtectionPolicy.decideOpenCredit(creditsRemaining: 3, otherEnforcing: [inactive(.hardcore)]),
                       .allow(confirm: true), "inactive contributors don't block")
        XCTAssertEqual(FlintProtectionPolicy.decideOpenCredit(creditsRemaining: nil, otherEnforcing: []), .deny(.stateUnavailable), "PC-37")
    }

    // MARK: §4 Harder waits and batches

    func testHarderWaitScheduleIsCappedPC18() {
        XCTAssertEqual((0...5).map(FlintProtectionPolicy.harderWait(forCount:)), [60, 300, 900, 900, 900, 900])
        XCTAssertEqual(FlintProtectionPolicy.harderWait(forCount: -3), 60)
    }

    func testMultiCauseOperationsPC19PC25() {
        XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: [active(.harder, n: 0), active(.harder, n: 2, id: "d")]),
                       .wait(seconds: 900), "PC-19: longest wait of the affected causes")
        XCTAssertEqual(FlintProtectionPolicy.decide(.ordinaryBreak, for: [active(.easy), active(.hardcore, id: "h")]),
                       .deny(.hardcoreCommitment), "PC-25: any Hardcore contributor denies")
        let later = end.addingTimeInterval(3600)
        XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: [active(.hardcore), active(.hardcore, .timeLimit, end: .some(later), id: "l"), active(.harder, id: "r")]),
                       .deferUntil(later), "defer the batch to the latest end; it is decided again when it applies")
        XCTAssertEqual(FlintProtectionPolicy.decide(.stopEarly, for: [inactive(.easy)]), .notApplicable)
        XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: [active(.easy), inactive(.harder)]), .allow(confirm: true))
        XCTAssertEqual(FlintProtectionPolicy.decide(.toggleOff, for: [active(.hardcore, end: .some(nil)), active(.hardcore, id: "f")]),
                       .deny(.legacyOpenEnded), "an open-ended contributor denies rather than deferring forever")
    }

    // MARK: §3 dominance and §2 edit classification

    func testDominancePC05PC06() {
        let old = block(["A", "B"], end: end)
        XCTAssertTrue(FlintProtectionPolicy.dominates(block(["A", "B", "C"], end: end.addingTimeInterval(600)), over: old))
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A"], end: end.addingTimeInterval(3600)), over: old), "drops B")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A", "B"], tier: .harder, end: end), over: old), "lower tier")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A", "B"], end: end.addingTimeInterval(-1)), over: old), "earlier end")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A", "B"], end: end), over: block(["A", "B"], end: nil)),
                       "finite never covers a legacy open-ended cause")
        XCTAssertFalse(FlintProtectionPolicy.dominates(block(["A", "B"], end: nil), over: old),
                       "an open-ended replacement would be an unbounded commitment (§1)")
    }

    func testEditImpactClassification() {
        let old = block(["A"], cats: ["social"], web: ["x.com"], end: end, budget: 30)
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: old), .cosmetic)
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A", "B"], cats: ["social"], web: ["x.com"], end: end, budget: 20)),
                       .strengthen, "more targets, smaller budget")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A"], web: ["x.com"], end: end, budget: 30)),
                       .weaken, "dropping an opaque category")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A"], cats: ["social"], web: ["x.com"], end: end, budget: 31)),
                       .weaken, "raising the budget")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: old, to: block(["A"], cats: ["social"], web: ["x.com"], end: end, budget: nil)),
                       .weaken, "removing the limit")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: block(["A"], end: end), to: block(["A"], end: end, budget: 30)),
                       .weaken, "adding a budget is a change of kind, not a proof")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: block(["A"], end: end), to: block(["A"], end: nil)),
                       .weaken, "clearing the end")
        let allow = Snapshot(mode: .allowList(allowedApplications: ["Phone", "Maps"]), tier: .harder, end: end)
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: allow, to: Snapshot(mode: .allowList(allowedApplications: ["Phone"]), tier: .harder, end: end)),
                       .strengthen, "fewer allowed apps")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: allow, to: Snapshot(mode: .allowList(allowedApplications: ["Phone", "Maps", "X"]), tier: .harder, end: end)),
                       .weaken, "adding an allowed app")
        XCTAssertEqual(FlintProtectionPolicy.editImpact(from: allow, to: block(["Phone"], tier: .harder, end: end)),
                       .weaken, "a mode change is never provably stronger")
    }

    // MARK: §1 new-cause validation

    func testStrictSessionsAndPomodorosNeedAFiniteEndOfAtMost24Hours() {
        XCTAssertNil(FlintProtectionPolicy.issue(forNew: .session, tier: .easy, duration: nil), "Easy may run until stopped")
        XCTAssertEqual(FlintProtectionPolicy.issue(forNew: .session, tier: .harder, duration: nil), .needsFiniteEnd)
        XCTAssertEqual(FlintProtectionPolicy.issue(forNew: .session, tier: .hardcore, duration: nil), .needsFiniteEnd,
                       "PC-01: new open-ended Hardcore is invalid")
        XCTAssertEqual(FlintProtectionPolicy.issue(forNew: .pomodoro, tier: .easy, duration: nil), .needsFiniteEnd)
        XCTAssertNil(FlintProtectionPolicy.issue(forNew: .session, tier: .hardcore, duration: 24 * 3600))
        XCTAssertEqual(FlintProtectionPolicy.issue(forNew: .session, tier: .hardcore, duration: 24 * 3600 + 1), .tooLong)
        XCTAssertEqual(FlintProtectionPolicy.issue(forNew: .session, tier: .easy, duration: 0), .nonPositive)
        XCTAssertEqual(FlintProtectionPolicy.issue(forNew: .session, tier: .easy, duration: .nan), .nonPositive)
        XCTAssertEqual(FlintProtectionPolicy.issue(forNew: .session, tier: .hardcore, duration: .infinity), .nonPositive)
    }

    func testOccurrencesAreAtMost26HoursIncludingA25HourDSTDay() {
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
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.used(elapsed: .nan), hasActiveCommitment: true), .unavailable)
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.neverUsed, causes: [inactive(.hardcore)]), .nothingToRecover)
        XCTAssertEqual(FlintProtectionPolicy.passEligibility(.neverUsed, causes: [inactive(.easy), active(.easy)]), .available)
    }

    func testLegacyPassPeriodMatchesTheContract() {
        XCTAssertEqual(FlintEmergencyPass.period, FlintProtectionConstants.passPeriod)
    }
}
