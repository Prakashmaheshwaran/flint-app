import XCTest
@testable import FlintCore

final class FlintRemovalGuardTests: XCTestCase {
    private typealias Guard = FlintRemovalGuard
    private typealias Owner = FlintRemovalGuardOwner
    private let wall = Date(timeIntervalSince1970: 1_800_000_000)

    private func instant(_ mono: Double = 1000, boot: String? = "boot-1", shift: Double = 0) -> FlintLedgerInstant {
        .init(wall: wall.addingTimeInterval(mono - 1000 + shift), monotonic: mono, bootID: boot)
    }

    private func clock(_ mono: Double = 1000, boot: String? = "boot-1", shift: Double = 0,
                       zone: String = "UTC") -> FlintClock {
        let now = instant(mono, boot: boot, shift: shift)
        return .init(now: { now.wall }, monotonic: { now.monotonic }, bootSessionID: { now.bootID },
                     calendar: {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: zone)!
            return calendar
        })
    }

    private func owner(_ id: String = "session", kind: FlintCauseState.Kind = .session,
                       tier: BreakLevel = .hardcore, active: Bool = true, end: Double? = 1600,
                       captured: Bool? = true, registration: Owner.Registration = .observed,
                       timeKnown: Bool = true, recovered: Bool? = false, legacy: Bool = false) -> Owner {
        .init(cause: .init(causeID: id, occurrenceID: id + ".occurrence", revision: 1,
                          kind: kind, tier: tier, isActive: active,
                          end: end.map { instant($0).wall }, legacyRights: legacy),
              registration: registration, removalGuard: captured, logicalEnd: end.map { instant($0) },
              timeKnown: timeKnown, passRecovered: recovered)
    }

    func testPC78TwoOwnersEndIndependentlyAndLastEndReleases() {
        let session = owner(end: 1100)
        let schedule = owner("schedule", kind: .recurringSchedule, end: 1200)
        XCTAssertEqual(Guard.decide(owners: [session, schedule], clock: clock()), .deny)
        XCTAssertEqual(Guard.decide(owners: [session, schedule], clock: clock(1100)), .deny)
        XCTAssertEqual(Guard.decide(owners: [schedule], clock: clock(1199.99)), .deny)
        XCTAssertEqual(Guard.decide(owners: [session, schedule], clock: clock(1200)), .release)
        XCTAssertEqual(Guard.decide(owners: [], clock: clock()), .release)
    }

    func testPC78InactiveHardcoreTemplateNeverArms() {
        XCTAssertEqual(Guard.decide(owners: [owner(kind: .recurringSchedule, active: false)], clock: clock()), .release)
    }

    func testAllCommitmentKindsCanOwnGuardIncludingPlannedRestAndUnspentQuota() {
        // No enforcing/shield-visible flag: activity survives a planned rest or unspent quota.
        let kinds: [FlintCauseState.Kind] = [.session, .pomodoro, .recurringSchedule,
                                            .oneShotSchedule, .sleep, .timeLimit, .openLimit]
        for kind in kinds {
            XCTAssertEqual(Guard.decide(owners: [owner(kind: kind)], clock: clock()), .deny, "\(kind)")
        }
    }

    func testEasyHarderOptOutAndFailedRegistrationNeverArm() {
        for item in [owner(tier: .easy), owner(tier: .harder), owner(captured: false),
                     owner(captured: nil), owner(registration: .notObserved)] {
            XCTAssertEqual(Guard.decide(owners: [item], clock: clock()), .release)
        }
    }

    func testPC46AndPC78CommittedPassRecoveryReleasesEveryOwner() {
        let recovered = [owner(recovered: true), owner("rule", kind: .recurringSchedule, recovered: true)]
        XCTAssertEqual(Guard.decide(owners: recovered, clock: clock()), .release)
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: recovered, clock: clock()), .allow(confirm: true))
        // A rule beginning inside the recovery window is covered too; no guard re-arm.
        XCTAssertEqual(Guard.decide(owners: recovered + [owner("new-rule", kind: .sleep, recovered: true)], clock: clock()), .release)
        // Releasing one owner cannot clear an independently active owner's guard.
        XCTAssertEqual(Guard.decide(owners: recovered + [owner("other")], clock: clock()), .deny)
        // PC-46's actual safe Settings/recovery reachability remains phase-2/device evidence.
    }

    func testPC79LegacyOpenEndedAndMigratedRunningSessionDoNotArm() throws {
        XCTAssertEqual(Guard.decide(owners: [owner(end: nil, legacy: true)], clock: clock()), .release)
        let old = FlintActiveSession(name: "Legacy", startedAt: wall, endsAt: wall.addingTimeInterval(600),
                                     breakLevel: .hardcore, monitorName: "flint.session.legacy")
        let decoded = try JSONDecoder().decode(FlintActiveSession.self, from: JSONEncoder().encode(old))
        // Phase 1 does not change FlintActiveSession's wire schema. This is the phase-2
        // projection of a legacy missing removalGuard field, regardless of current opt-in.
        let migrated = Owner(cause: .init(causeID: decoded.id, kind: .session, tier: decoded.breakLevel,
                                          isActive: true, end: decoded.endsAt, legacyRights: true),
                             registration: .observed, removalGuard: nil, logicalEnd: instant(1600),
                             timeKnown: true, passRecovered: false)
        XCTAssertEqual(Guard.decide(owners: [migrated], clock: clock()), .release)
        XCTAssertEqual(Guard.decidePreferenceChange(to: true, owners: [migrated], clock: clock()), .allow(confirm: true))
        XCTAssertEqual(Guard.decide(owners: [migrated], clock: clock()), .release)
        XCTAssertEqual(FlintProtectionPolicy.decide(.ownerEnded, for: migrated.cause!), .allow(confirm: false))
    }

    func testCaptureAtStartIsUnaffectedByLaterPreferenceChanges() throws {
        var bytes: Data?
        let store = FlintRemovalGuardPreference.Store(read: { bytes }, write: { bytes = $0 })
        let beforeOptIn = owner(captured: store.load().captureForNewCommitment)
        try store.save(.init(enabled: true, disclosedAt: wall, disclosureVersion: 1))
        let afterOptIn = owner("new", captured: store.load().captureForNewCommitment)
        XCTAssertEqual(Guard.decide(owners: [beforeOptIn], clock: clock()), .release)
        XCTAssertEqual(Guard.decide(owners: [afterOptIn], clock: clock()), .deny)
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [afterOptIn], clock: clock()),
                       .deferUntil(instant(1600).wall))
        // Low-level storage is not the commit-time policy gate. Even a later persisted
        // off or unreadable preference cannot rewrite a frozen running commitment.
        try store.save(.init())
        XCTAssertFalse(store.load().captureForNewCommitment)
        XCTAssertEqual(Guard.decide(owners: [afterOptIn], clock: clock()), .deny)
        bytes = Data("unreadable".utf8)
        XCTAssertFalse(store.load().captureForNewCommitment)
        XCTAssertEqual(Guard.decide(owners: [afterOptIn], clock: clock()), .deny)
    }

    func testDisablingUsesTierPolicyAndLatestLogicalEnd() {
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [owner(tier: .easy)], clock: clock()), .allow(confirm: true))
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [owner(tier: .harder)], clock: clock()), .wait(seconds: 60))
        let owners = [owner(end: 1100), owner("later", kind: .sleep, end: 1700)]
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: owners, clock: clock()), .deferUntil(instant(1700).wall))
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: owners, clock: clock(1700)), .allow(confirm: true))
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [owner(registration: .notObserved)], clock: clock()),
                       .deferUntil(instant(1600).wall), "lost coverage does not end the commitment")
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [owner(end: nil, legacy: true)], clock: clock()),
                       .allow(confirm: true), "no unbounded guard or endless deferral")
    }

    func testUnknownOwnersAlwaysKeepEvenBesideEligibleOwner() {
        let unknown = Owner(cause: nil, registration: .observed, removalGuard: nil,
                            logicalEnd: nil, timeKnown: false, passRecovered: false)
        let unreadable = [unknown, owner(registration: .unavailable), owner(recovered: nil)]
        XCTAssertEqual(Guard.decide(owners: nil, clock: clock()), .keep)
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: nil, clock: clock()), .deny(.stateUnavailable))
        for item in unreadable {
            for owners in [[item], [owner(), item], [item, owner()]] {
                XCTAssertEqual(Guard.decide(owners: owners, clock: clock()), .keep)
                XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: owners, clock: clock()), .deny(.stateUnavailable))
            }
        }
    }

    func testKnownTimeUncertaintyCannotArmAndCannotAuthorizeWeakening() {
        XCTAssertEqual(Guard.decide(owners: [owner(timeKnown: false)], clock: clock()), .release)
        XCTAssertEqual(Guard.decide(owners: [owner()], clock: clock(1000, boot: nil)), .release)
        XCTAssertEqual(Guard.decide(owners: [owner()], clock: clock(1000, boot: "new-boot")), .release)
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [owner(timeKnown: false)], clock: clock()), .deny(.stateUnavailable))
        XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [owner()], clock: clock(1000, boot: "new-boot")), .deny(.stateUnavailable))
        XCTAssertEqual(Guard.decide(owners: [owner(timeKnown: false), owner("known")], clock: clock()), .deny)
    }

    func testMalformedOwnerAndFalseKnownTimeClaimKeep() {
        let base = owner()
        let missingEnd = Owner(cause: base.cause, registration: .observed, removalGuard: true,
                               logicalEnd: nil, timeKnown: true, passRecovered: false)
        XCTAssertEqual(Guard.decide(owners: [missingEnd], clock: clock()), .keep)
        for value in [Double.nan, .infinity, -1] {
            let badEnd = Owner(cause: base.cause, registration: .observed, removalGuard: true,
                               logicalEnd: instant(value), timeKnown: true, passRecovered: false)
            XCTAssertEqual(Guard.decide(owners: [badEnd], clock: clock()), .keep)
            XCTAssertEqual(Guard.decide(owners: [base], clock: clock(value)), .keep)
        }
        var badCause = base.cause!
        badCause.causeID = ""
        let badID = Owner(cause: badCause, registration: .observed, removalGuard: true,
                          logicalEnd: base.logicalEnd, timeKnown: true, passRecovered: false)
        XCTAssertEqual(Guard.decide(owners: [badID], clock: clock()), .keep)
    }

    func testWallChangesAndTimeZonesCannotMoveDeadline() {
        for zone in ["UTC", "America/New_York", "Pacific/Kiritimati"] {
            for shift in [-86_400.0, 0, 86_400] {
                let now = clock(1500, shift: shift, zone: zone)
                XCTAssertEqual(Guard.decide(owners: [owner()], clock: now), .deny)
                XCTAssertEqual(Guard.decide(owners: [owner()], clock: clock(1600, shift: shift, zone: zone)), .release)
                XCTAssertEqual(Guard.decidePreferenceChange(to: false, owners: [owner()], clock: now),
                               .deferUntil(now.now().addingTimeInterval(100)))
            }
        }
    }
}
