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
            self.registrations[grant.activityName] = remaining
        }, registered: { self.registrations[$0] != nil }, release: { grant in
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
    private func drawn(hardcore: Bool = false) -> ShieldCopy {
        ShieldCopy(name: "Instagram", unit: .app, contributors: nil, now: now,
                   openCredit: .target(service.preview(target: shieldKey), hardcore: hardcore),
                   locale: english, timeZone: utc)
    }
    /// Exactly the production action wiring, with the injected service standing in for the
    /// App Group adapter behind `FlintOpenCredit.accept(for:)`.
    @discardableResult
    private func press(_ action: ShieldAction) -> ShieldActionResponse {
        ShieldCreditAction.response(to: action) {
            FlintOpenCredit.shieldAcceptanceEnabled &&
                (try? service.accept(target: shieldKey, operationID: UUID().uuidString).get()) != nil
        }
    }
    private func assertNothingSpent(_ expectedUsed: Int, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(used, expectedUsed, "no credit spent", file: file, line: line)
    }

    func testShieldAcceptanceReleaseGateIsEnabled() {
        XCTAssertTrue(FlintOpenCredit.shieldAcceptanceEnabled)
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
            XCTAssertEqual(ShieldCreditAction.response(to: action) {
                XCTFail("action \(raw) must not accept"); return true
            }, .close)
            XCTAssertEqual(press(action), .close)
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, 1)
        assertNothingSpent(0)
        XCTAssertEqual(registerCalls, 0); XCTAssertTrue(released.isEmpty)
    }

    /// Every drawn state lacks a spending button exactly when the real accept would refuse or
    /// replay; when a button is drawn, the real accept charges exactly one credit.
    func testNoCreditButtonExactlyWhenAcceptWouldNotCharge() throws {
        struct Scenario { let name: String; let arrange: (ShieldCreditPathTests) throws -> Void }
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
            .init(name: "nonpositive cap") { test in test.cap = 0 },
            .init(name: "midnight clipped") { test in test.advance(11 * 3600 + 57 * 60 + 30) },
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
            switch result {
            case let .success(receipt) where !receipt.replayed:
                XCTAssertNotNil(shield.secondaryButtonLabel, "\(scenario.name): accept charges, so a button is drawn")
                XCTAssertEqual(charged, 1, scenario.name)
            default:
                XCTAssertNil(shield.secondaryButtonLabel, "\(scenario.name): accept refuses or replays, so no button")
                XCTAssertEqual(charged, 0, scenario.name)
            }
        }
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
        let exhausted = drawn(hardcore: true)
        XCTAssertEqual(exhausted.subtitle,
                       "No credits left today · resets about 00:00. Only the Emergency Pass ends this early.")
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
        XCTAssertEqual(drawn().subtitle, "Flint can't confirm your credits right now · no credit used")
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

    func testMidnightClippedOfferNeverOverstatesTheGrant() throws {
        now = ISO8601DateFormatter().date(from: "2026-10-28T23:57:30Z")!
        try initialize()
        let shield = drawn()
        XCTAssertEqual(shield.secondaryButtonLabel, "Use one credit · 2 minutes")
        XCTAssertEqual(press(.secondaryButtonPressed), .close)
        let grant = try XCTUnwrap(ledger.read().get().openCredits.grants[shieldKey])
        XCTAssertEqual(grant.duration, 150)
        XCTAssertGreaterThanOrEqual(grant.duration, 2 * 60, "the drawn minutes never exceed the grant")
    }
}
