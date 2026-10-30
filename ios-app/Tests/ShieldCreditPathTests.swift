import XCTest
import ManagedSettings
@testable import FlintCore

private let shieldPayload = Data(#"["shield-app"]"#.utf8)
private let shieldKey = FlintOpenCreditRestoration(kind: .application, encodedToken: shieldPayload, storeNames: []).targetKey

/// Shield-path acceptance: the exact `ShieldCopy` presenter and `ShieldCreditAction` seam the
/// extensions ship, driven by lane B's real ledger and `FlintOpenCreditService` with fake
/// native stores and registrar. This is logic evidence only; it does not prove Apple's shield
/// rendering, caching, button delivery or release on a signed device.
final class ShieldCreditPathTests: XCTestCase {
    private var directory: URL!
    private var marker: Date?
    private var now = Date()
    private var monotonic: TimeInterval = 10_000
    private var cap = 3
    private var contributors: [FlintOpenCreditContributor] = []
    private var unavailableContext = false
    private var registrationFails = false
    private var releaseFails = false
    private var registrations: [String: TimeInterval] = [:]
    private var registerCalls = 0
    private var released: [String] = []
    private var shielded: [String] = []
    private let english = Locale(identifier: "en_GB")
    private let utc = TimeZone(secondsFromGMT: 0)!

    override func setUp() {
        super.setUp()
        reset()
    }
    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }
    private func reset() {
        if let directory { try? FileManager.default.removeItem(at: directory) }
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-shield-credit-\(UUID().uuidString)")
        marker = nil
        now = ISO8601DateFormatter().date(from: "2026-10-28T12:00:00Z")!
        monotonic = 10_000; cap = 3; contributors = []; unavailableContext = false
        registrationFails = false; releaseFails = false
        registrations = [:]; registerCalls = 0; released = []; shielded = []
    }

    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian); value.timeZone = utc; return value
    }
    private var clock: FlintClock {
        FlintClock(now: { self.now }, monotonic: { self.monotonic }, bootSessionID: { "boot-a" }, calendar: { self.calendar })
    }
    private func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds); monotonic += seconds }
    private var caps: [String: Int] { ["open-a": cap] }
    private var ledger: FlintProtectionLedger {
        FlintProtectionLedger(directory: directory, clock: clock, lockTimeout: 10,
            initializedMarker: { self.marker }, setInitializedMarker: { self.marker = $0 },
            migrate: { state, at in
                state.importLegacyOpenCredits(.absent, targetKeys: [shieldKey], established: false,
                                              at: at, calendar: self.calendar)
            })
    }
    private var service: FlintOpenCreditService {
        FlintOpenCreditService(ledger: ledger, clock: clock, context: {
            guard !self.unavailableContext else { return nil }
            let blocking = FlintOpenCreditBlocking.query(target: shieldKey, kind: .application, stores: self.contributors)
            return .init(coveringCaps: self.caps, targetKeys: [shieldKey], blockedBy: blocking.reason,
                         blockingContributors: blocking.contributors,
                         restoration: .init(kind: .application, encodedToken: shieldPayload, storeNames: ["flintO.open-a"]),
                         fingerprints: self.caps.mapValues { "fingerprint-\($0)" },
                         scopes: ["open-a": "scope"], activities: ["open-a": "flint.openLimit.open-a.r1"])
        }, register: { grant, remaining in
            self.registerCalls += 1
            if self.registrationFails { throw FlintOpenCreditRefusal.denied(.registrationFailed) }
            self.registrations[grant.activityName] = remaining
        }, registered: { self.registrations[$0] != nil }, release: { grant in
            if self.releaseFails { throw FlintOpenCreditRefusal.denied(.applying) }
            self.released.append(grant.targetKey)
        }, reshield: { grant in self.shielded.append(grant.targetKey) },
        stop: { self.registrations[$0] = nil }, authorized: { grant in
            self.unavailableContext ? nil : grant.authorizes(caps: self.caps,
                fingerprints: self.caps.mapValues { "fingerprint-\($0)" }, scopes: ["open-a": "scope"],
                activities: ["open-a": "flint.openLimit.open-a.r1"])
        })
    }
    private func initialize() throws { _ = try ledger.read().get() }
    private var used: Int { (try? ledger.read().get().openCredits.counters[shieldKey]?.used) ?? -1 }

    /// What the configuration extension draws right now for this target.
    private func drawn() -> ShieldCopy {
        ShieldCopy(name: "Instagram", unit: .app, contributors: nil, now: now,
                   openCredit: .target(service.preview(target: shieldKey)),
                   locale: english, timeZone: utc)
    }
    /// Exactly the production action wiring, with the injected service standing in for the
    /// App Group adapter behind `FlintOpenCredit.accept(for:)`.
    @discardableResult
    private func press(_ action: ShieldAction,
                       acceptanceEnabled: Bool = FlintOpenCredit.shieldAcceptanceEnabled) -> ShieldActionResponse {
        ShieldCreditAction.response(to: action, acceptanceEnabled: acceptanceEnabled) {
            (try? service.accept(target: shieldKey, operationID: UUID().uuidString).get()) != nil
        }
    }
    private func assertNothingSpent(_ expectedUsed: Int, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(used, expectedUsed, "no credit spent", file: file, line: line)
    }

    func testShieldAcceptanceReleaseGateIsEnabled() {
        XCTAssertTrue(FlintOpenCredit.shieldAcceptanceEnabled)
    }

    /// Turning the release gate back off is one line: the shield then draws only "Go back"
    /// with "not available yet" copy, and no action can reach accept.
    func testAcceptanceGateOffDrawsOnlyGoBackAndNeverCharges() throws {
        try initialize()
        XCTAssertTrue(ShieldCopy.offersCredit(service.preview(target: shieldKey)), "a credit is available")
        for unit in [ShieldCopy.Unit.app, .site] {
            let off = ShieldCopy(name: nil, unit: unit, contributors: nil, now: now,
                                 openCredit: .acceptanceDisabled, locale: english, timeZone: utc)
            XCTAssertEqual(off.subtitle, "Open credits aren't available yet")
            XCTAssertEqual(off.primaryButtonLabel, "Go back")
            XCTAssertNil(off.secondaryButtonLabel)
        }
        XCTAssertEqual(press(.secondaryButtonPressed, acceptanceEnabled: false), .defer)
        XCTAssertEqual(press(.primaryButtonPressed, acceptanceEnabled: false), .close)
        for raw in 0..<16 {
            guard let action = ShieldAction(rawValue: raw) else { continue }
            _ = ShieldCreditAction.response(to: action, acceptanceEnabled: false) {
                XCTFail("gate off: action \(raw) must not accept"); return true
            }
        }
        assertNothingSpent(0)
        XCTAssertEqual(registerCalls, 0); XCTAssertTrue(released.isEmpty)
    }

    func testOnlyTheDrawnSecondaryButtonSpendsAnAvailableCredit() throws {
        try initialize()
        let shield = drawn()
        XCTAssertEqual(shield.primaryButtonLabel, "Go back")
        XCTAssertEqual(shield.secondaryButtonLabel, "Use one credit · 5 minutes")
        XCTAssertEqual(shield.subtitle, "3 of 3 credits left today")

        XCTAssertEqual(press(.primaryButtonPressed), .close)
        assertNothingSpent(0)
        XCTAssertEqual(registerCalls, 0); XCTAssertTrue(released.isEmpty)

        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        XCTAssertEqual(used, 1)
        XCTAssertEqual(released, [shieldKey])
        XCTAssertEqual(registrations.values.first, 300)
        XCTAssertEqual(drawn().subtitle, "Credit active for 5 more min · no extra credit used")
    }

    func testGoBackNeverChargesInAnyCreditState() throws {
        try initialize()
        XCTAssertEqual(press(.primaryButtonPressed), .close) // available
        assertNothingSpent(0)
        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        XCTAssertEqual(press(.primaryButtonPressed), .close) // live grant
        assertNothingSpent(1)
        contributors = [.init(name: "Current session", applications: [shieldKey])]
        XCTAssertEqual(press(.primaryButtonPressed), .close) // blocked
        contributors = [.init(name: "Schedule: Deep work", applicationCategoryExceptions: [])]
        XCTAssertEqual(press(.primaryButtonPressed), .close) // category overlap
        unavailableContext = true
        XCTAssertEqual(press(.primaryButtonPressed), .close) // unavailable
        assertNothingSpent(1)
        XCTAssertEqual(registerCalls, 1)
        XCTAssertEqual(released, [shieldKey])
    }

    /// iOS 26.4 secondary submenu items and any other non-secondary action close without
    /// calling accept, even while a credit is available.
    func testEveryNonSecondaryActionClosesWithoutCharging() throws {
        try initialize()
        var checked = 0
        for raw in 0..<16 {
            guard let action = ShieldAction(rawValue: raw), action != .secondaryButtonPressed else { continue }
            XCTAssertEqual(ShieldCreditAction.response(to: action, acceptanceEnabled: true) {
                XCTFail("action \(raw) must not accept"); return true
            }, .close)
            XCTAssertEqual(press(action), .close)
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, 1)
        assertNothingSpent(0)
        XCTAssertEqual(registerCalls, 0); XCTAssertTrue(released.isEmpty)
    }

    /// In each real state: no drawn credit button means the real accept would not charge (it
    /// refuses or replays). A drawn button charges exactly one credit, except an offer that
    /// accept refuses before commitment (registration failure), which charges nothing.
    func testCreditButtonMatchesTheRealAcceptInEachState() throws {
        struct Scenario {
            let name: String
            var refusesAfterOffer = false
            let arrange: (ShieldCreditPathTests) throws -> Void
        }
        let scenarios: [Scenario] = [
            .init(name: "available") { _ in },
            .init(name: "last") { test in test.cap = 1 },
            .init(name: "exhausted") { test in
                test.cap = 1
                _ = try test.service.accept(target: shieldKey, operationID: "spent").get()
                test.advance(300)
                _ = test.service.reconcile()
            },
            .init(name: "live grant") { test in _ = try test.service.accept(target: shieldKey, operationID: "live").get() },
            .init(name: "session") { test in test.contributors = [.init(name: "Current session", applications: [shieldKey])] },
            .init(name: "schedule") { test in test.contributors = [.init(name: "Schedule: Deep work", applications: [shieldKey])] },
            .init(name: "time limit") { test in test.contributors = [.init(name: "Time Limit: Social", applications: [shieldKey])] },
            .init(name: "category overlap") { test in
                test.contributors = [.init(name: "Schedule: Deep work", applicationCategoryExceptions: [])]
            },
            .init(name: "unavailable") { test in test.unavailableContext = true },
            .init(name: "zero cap") { test in test.cap = 0 },
            .init(name: "midnight clipped") { test in test.advance(11 * 3600 + 57 * 60 + 30) },
            .init(name: "registration fails", refusesAfterOffer: true) { test in test.registrationFails = true },
        ]
        for scenario in scenarios {
            reset()
            try initialize()
            try scenario.arrange(self)
            let before = used
            let shield = drawn()
            XCTAssertEqual(shield.primaryButtonLabel, "Go back", scenario.name)
            let result = service.accept(target: shieldKey, operationID: "probe-\(scenario.name)")
            let charged = used - before
            if shield.secondaryButtonLabel == nil {
                if case let .success(receipt) = result { XCTAssertTrue(receipt.replayed, scenario.name) }
                XCTAssertEqual(charged, 0, "\(scenario.name): no button, no charge")
            } else if scenario.refusesAfterOffer {
                XCTAssertEqual(result, .failure(.denied(.registrationFailed)), scenario.name)
                XCTAssertEqual(charged, 0, "\(scenario.name): precommit refusal spends nothing")
            } else {
                XCTAssertEqual((try? result.get())?.replayed, false, scenario.name)
                XCTAssertEqual(charged, 1, scenario.name)
            }
        }
    }

    func testRegistrationFailureAfterAnOfferSpendsNothing() throws {
        try initialize()
        XCTAssertNotNil(drawn().secondaryButtonLabel)
        registrationFails = true
        XCTAssertEqual(press(.secondaryButtonPressed), .defer)
        assertNothingSpent(0)
        XCTAssertTrue(released.isEmpty)
        XCTAssertEqual(drawn().secondaryButtonLabel, "Use one credit · 5 minutes", "a legitimate offer stays drawn")
    }

    /// A post-commit failure is not a free refusal: the credit stays spent and the redraw shows
    /// it as active. Later stray secondary events replay that grant without a second charge.
    func testPostCommitReleaseFailureKeepsTheSpentCreditAndNeverChargesTwice() throws {
        try initialize()
        releaseFails = true
        XCTAssertEqual(press(.secondaryButtonPressed), .defer)
        XCTAssertEqual(used, 1, "consumption is durable before native release")
        XCTAssertTrue(released.isEmpty)
        let redrawn = drawn()
        XCTAssertEqual(redrawn.subtitle, "Credit active for 5 more min · no extra credit used")
        XCTAssertNil(redrawn.secondaryButtonLabel, "no replay button")
        advance(30)
        XCTAssertEqual(press(.secondaryButtonPressed), .defer)
        XCTAssertEqual(used, 1)
        releaseFails = false
        XCTAssertEqual(press(.secondaryButtonPressed), .close, "a replay releases the same grant")
        XCTAssertEqual(used, 1)
        XCTAssertEqual(released, [shieldKey])
        XCTAssertEqual(registerCalls, 1)
    }

    /// Known limit: the action cannot see the drawn offer. An old "Use one credit" label still
    /// offered exactly one credit, so a delayed press after another surface's grant expired
    /// spends exactly one more while credits remain, never more.
    func testDelayedCreditButtonWithCreditsRemainingSpendsExactlyOneOfferedCredit() throws {
        cap = 2
        try initialize()
        XCTAssertEqual(drawn().subtitle, "2 of 2 credits left today")
        _ = try service.accept(target: shieldKey, operationID: "other-surface").get()
        advance(300); _ = service.reconcile()
        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        XCTAssertEqual(used, 2)
        advance(300); _ = service.reconcile()
        XCTAssertEqual(press(.secondaryButtonPressed), .defer, "the pool is now empty")
        XCTAssertEqual(used, 2)
    }

    /// Known limit: a "5 minutes" label drawn earlier can be pressed inside the reset clip;
    /// the grant is clipped at press time (ADR-012 §5) and a fresh draw shows the shorter time.
    func testDelayedFiveMinuteOfferIsClippedAtPressTime() throws {
        now = ISO8601DateFormatter().date(from: "2026-10-28T23:54:00Z")!
        try initialize()
        XCTAssertEqual(drawn().secondaryButtonLabel, "Use one credit · 5 minutes")
        advance(270)
        XCTAssertEqual(drawn().secondaryButtonLabel, "Use one credit · 1 minute", "a fresh draw discloses the clip")
        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        let grant = try XCTUnwrap(ledger.read().get().openCredits.grants[shieldKey])
        XCTAssertEqual(grant.duration, 90)
        XCTAssertEqual(used, 1)
    }

    func testZeroCapNeverPromisesARefillAcrossTheReset() throws {
        cap = 0
        try initialize()
        XCTAssertEqual(drawn().subtitle, "This Open Limit allows no credits")
        advance(13 * 3600)
        XCTAssertEqual(drawn().subtitle, "This Open Limit allows no credits")
        XCTAssertEqual(press(.secondaryButtonPressed), .defer)
        assertNothingSpent(0)
    }

    /// A wall-clock jump makes only the reset estimate uncertain; the reset itself still
    /// follows elapsed time.
    func testWallClockJumpLeavesTheResetOnElapsedTime() throws {
        cap = 1
        try initialize()
        _ = try service.accept(target: shieldKey, operationID: "noon").get()
        advance(300); _ = service.reconcile()
        now = now.addingTimeInterval(2 * 3600) // wall only
        XCTAssertTrue(service.preview(target: shieldKey).timeUncertain)
        XCTAssertEqual(drawn().subtitle, "No credits left today · the reset time is uncertain")
        XCTAssertNil(drawn().secondaryButtonLabel)
        advance(12 * 3600)
        XCTAssertEqual(drawn().subtitle, "Last credit today")
        XCTAssertEqual(drawn().secondaryButtonLabel, "Use one credit · 5 minutes")
    }

    func testStaleCreditButtonAfterThePoolRanOutChargesNothingAndRedraws() throws {
        cap = 1
        try initialize()
        let stale = drawn()
        XCTAssertEqual(stale.secondaryButtonLabel, "Use one credit · 5 minutes")
        _ = try service.accept(target: shieldKey, operationID: "other-surface").get()
        advance(300); _ = service.reconcile()
        XCTAssertEqual(used, 1)

        XCTAssertEqual(press(.secondaryButtonPressed), .defer, "refused presses ask iOS to redraw")
        assertNothingSpent(1)
        XCTAssertEqual(released, [shieldKey])
        let redrawn = drawn()
        XCTAssertEqual(redrawn.subtitle, "No credits left today · resets about 00:00")
        XCTAssertNil(redrawn.secondaryButtonLabel)
    }

    func testStaleGoBackAcrossMidnightCannotSpendTheNewPool() throws {
        cap = 1
        now = ISO8601DateFormatter().date(from: "2026-10-28T23:50:00Z")!
        try initialize()
        _ = try service.accept(target: shieldKey, operationID: "evening").get()
        advance(300); _ = service.reconcile()
        let exhausted = drawn()
        XCTAssertEqual(exhausted.subtitle, "No credits left today · resets about 00:00")
        XCTAssertNil(exhausted.secondaryButtonLabel)

        advance(15 * 60) // the drawn shield is still on screen after the trusted reset
        XCTAssertEqual(press(.primaryButtonPressed), .close)
        XCTAssertEqual(service.preview(target: shieldKey).remaining, 1, "the new period's pool is intact")
        XCTAssertEqual(registerCalls, 1)
        XCTAssertEqual(released, [shieldKey])
    }

    func testRefusedSecondaryWhileAnotherCauseBlocksChargesNothing() throws {
        try initialize()
        XCTAssertNotNil(drawn().secondaryButtonLabel)
        contributors = [.init(name: "Current session", applications: [shieldKey])]
        XCTAssertEqual(press(.secondaryButtonPressed), .defer)
        assertNothingSpent(0)
        XCTAssertEqual(registerCalls, 0); XCTAssertTrue(released.isEmpty)
        let redrawn = drawn()
        XCTAssertEqual(redrawn.subtitle, "A focus session still blocks this app · no credit used")
        XCTAssertNil(redrawn.secondaryButtonLabel)

        contributors = [.init(name: "Schedule: Deep work", applicationCategoryExceptions: [])]
        XCTAssertEqual(press(.secondaryButtonPressed), .defer)
        assertNothingSpent(0)
        XCTAssertEqual(drawn().subtitle, "Another category rule may still block this app; no credit used")

        contributors = []
        unavailableContext = true
        XCTAssertEqual(press(.secondaryButtonPressed), .defer)
        assertNothingSpent(0)
        XCTAssertEqual(drawn().subtitle, "Flint can't confirm your credits right now")
    }

    func testStaleSecondaryDuringALiveGrantReplaysWithoutASecondCharge() throws {
        try initialize()
        let stale = drawn()
        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        XCTAssertNotNil(stale.secondaryButtonLabel)
        advance(60)
        XCTAssertNil(drawn().secondaryButtonLabel, "no replay button is drawn during a live grant")
        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        XCTAssertEqual(used, 1)
        XCTAssertEqual(registerCalls, 1)
    }

    func testFreshClippedOfferRoundsDownToTheGrantPressedAtOnce() throws {
        now = ISO8601DateFormatter().date(from: "2026-10-28T23:57:30Z")!
        try initialize()
        let shield = drawn()
        XCTAssertEqual(shield.secondaryButtonLabel, "Use one credit · 2 minutes")
        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        let grant = try XCTUnwrap(ledger.read().get().openCredits.grants[shieldKey])
        XCTAssertEqual(grant.duration, 150)
        XCTAssertGreaterThanOrEqual(grant.duration, 2 * 60, "an immediate press gets at least the drawn minutes")
    }
}
