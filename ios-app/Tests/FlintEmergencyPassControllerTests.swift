import XCTest
@testable import FlintCore

/// ADR-012 PC-39..46 and PC-54..60. OS effects are fakes; this proves transaction ordering,
/// scope/idempotency and elapsed-time recovery, not signed-device Screen Time delivery.
final class FlintEmergencyPassControllerTests: XCTestCase {
    private var directory: URL!
    private var marker: Date?
    private var now = Date(timeIntervalSince1970: 1_793_206_800)
    private var monotonic: TimeInterval = 10_000
    private var boot: String? = "boot-a"
    private var revision = "scope-a"
    private var sessions: [FlintActiveSession] = []
    private var rules: [FlintPassCommitment] = []
    private var scopeReadable = true
    private var carrierFails = false
    private var changesScopeDuringRegistration = false
    private var registrationWork: (() -> Void)?
    private var crash = false
    private var releaseFails = false
    private var resumeFails = false
    private var resumeWork: (() -> Void)?
    private var advanceDuringRelease: TimeInterval = 0
    private var disableFails = false
    private var effects: [String] = []
    private var emitted: [FlintPassRecovery] = []
    private var registered: [String] = []
    private var registrationTimes: [Date] = []
    private var cancelledCarriers: [String] = []
    private var observedScopeTimes: [FlintLedgerInstant] = []

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-pass-\(UUID())")
        sessions = [.init(id: "session-a", name: "Focus", startedAt: now.addingTimeInterval(-100),
            endsAt: now.addingTimeInterval(5000), breakLevel: .easy, monitorName: "flint.session.a")]
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var clock: FlintClock {
        .init(now: { self.now }, monotonic: { self.monotonic }, bootSessionID: { self.boot },
              calendar: { Calendar(identifier: .gregorian) })
    }

    private func ledger() -> FlintProtectionLedger {
        .init(directory: directory, clock: clock, initializedMarker: { self.marker },
              setInitializedMarker: { self.marker = $0 }, migrate: { $0.importLegacyPass(.absent, at: $1) })
    }

    private enum Fault: Error { case injected }

    private func controller() -> FlintEmergencyPassController {
        .init(environment: .init(ledger: { self.ledger() }, clock: clock, scope: { _, instant in
            guard self.scopeReadable else { throw Fault.injected }
            self.observedScopeTimes.append(instant)
            return .init(revision: self.revision, sessions: self.sessions, rules: self.rules,
                         stores: ["flint", "flint.rule-a", "flint.rule-b", "flint.a", "flint.b", "flintL.limit-a", "flint.web"])
        }, registerCarrier: { name, _, _ in
            if self.carrierFails { throw Fault.injected }
            self.registered.append(name)
            self.registrationTimes.append(self.now)
            self.registrationWork?()
            if self.changesScopeDuringRegistration { self.revision = "crossed a natural boundary" }
        }, stopCarrier: { self.effects.append("stop:\($0)") }, terminate: { sessions, _ in
            // Every release seam must see the durable consumption and termination list.
            let stored = try self.ledger().peek().get()
            XCTAssertEqual(stored.pass.usage, .used)
            XCTAssertEqual(stored.emergencyRecovery?.scope.sessions, sessions)
            self.effects.append("terminate")
            self.sessions.removeAll { candidate in sessions.contains { $0.id == candidate.id } }
        }, release: { _ in
            let stored = try self.ledger().peek().get()
            XCTAssertEqual(stored.pass.usage, .used)
            if self.releaseFails { throw Fault.injected }
            self.effects.append("release")
            if self.advanceDuringRelease > 0 {
                self.advance(self.advanceDuringRelease)
                self.advanceDuringRelease = 0
            }
        }, resume: { _, _ in
            if self.resumeFails { throw Fault.injected }
            self.effects.append("resume")
            self.resumeWork?()
        },
        disable: { authorization in
            if self.disableFails { throw Fault.injected }
            self.effects.append("disable:\(authorization.rules.map(\.id).joined(separator: ","))")
        },
        emit: { self.emitted.append($0) }, afterCommit: {
            if self.crash { throw Fault.injected }
        }, cancelGrants: { state in state.cancelAccessGrantsForPass() + self.cancelledCarriers }))
    }

    private func advance(_ seconds: TimeInterval) {
        now = now.addingTimeInterval(seconds); monotonic += seconds
    }

    private func use(_ operation: String = "pass-a") throws -> FlintEmergencyPassStatus {
        let controller = controller()
        let preview = try controller.preview().get()
        return try controller.use(previewRevision: preview.revision, operationID: operation).get()
    }

    func testAnyTierAndRulesOnlyCanRedeemGlobalRecoveryPC41() throws {
        for level in BreakLevel.allCases {
            sessions = [.init(name: "\(level)", startedAt: now, endsAt: now.addingTimeInterval(9000),
                              breakLevel: level, monitorName: "session")]
            let preview = try controller().preview().get()
            XCTAssertEqual(preview.scope.sessions.first?.breakLevel, level)
        }
        sessions = []
        rules = [.init(id: "schedule:rule-a", name: "Sleep", kind: .sleep,
                       naturalStart: now.addingTimeInterval(-100), naturalEnd: now.addingTimeInterval(7200),
                       storeName: "flint.rule-a", activityName: "flint.rule.rule-a")]
        let status = try use()
        XCTAssertTrue(status.pass.isRecovering)
        XCTAssertEqual(status.recovery?.scope.rules, rules)
        XCTAssertEqual(effects.prefix(2), ["terminate", "release"])
        XCTAssertEqual(emitted.count, 1)
    }

    func testPreviewIncludesResumeAndNextEligibilityPC39() throws {
        let preview = try controller().preview().get()
        XCTAssertEqual(preview.resumeAt, now.addingTimeInterval(3600))
        XCTAssertEqual(preview.nextPassAt, now.addingTimeInterval(604800))
        XCTAssertEqual(preview.scope.sessions, sessions)
    }

    func testResumingScopeProjectsTheRemainingMonotonicBoundary() throws {
        let start = now
        let startMonotonic = monotonic
        _ = try use()
        advance(2400)
        _ = try controller().resumingScope().get()
        XCTAssertEqual(observedScopeTimes.last?.wall, start.addingTimeInterval(3600))
        XCTAssertEqual(observedScopeTimes.last?.monotonic, startMonotonic + 3600)
        XCTAssertEqual(try ledger().peek().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testNoSpendWithoutActiveCommitment() throws {
        sessions = []
        XCTAssertEqual(controller().preview(), .failure(.nothingToRecover))
        XCTAssertEqual(controller().use(previewRevision: revision, operationID: "empty"), .failure(.nothingToRecover))
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
        XCTAssertFalse(effects.contains("release"))
    }

    func testUnknownScopeCannotSpendOrReleasePC43() throws {
        scopeReadable = false
        XCTAssertEqual(controller().preview(), .failure(.scopeUnavailable))
        XCTAssertEqual(controller().use(previewRevision: revision, operationID: "a"), .failure(.scopeUnavailable))
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
        XCTAssertFalse(effects.contains("release"))
    }

    func testStalePreviewRefreshesWithoutSpend() throws {
        let preview = try controller().preview().get()
        revision = "different occurrence or targets"
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "a"), .failure(.stalePreview))
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
        XCTAssertFalse(effects.contains("release"))
    }

    func testOldConfirmationAndRebootRequireFreshPreview() throws {
        let preview = try controller().preview().get()
        advance(61)
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "old"), .failure(.stalePreview))
        let fresh = try controller().preview().get()
        boot = "different-boot"; monotonic = 1
        XCTAssertEqual(controller().use(previewRevision: fresh.revision, operationID: "reboot"), .failure(.stalePreview))
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
        XCTAssertFalse(effects.contains("release"))
    }

    func testClockJumpInvalidatesDisplayedConfirmationTimesWithoutSpending() throws {
        let preview = try controller().preview().get()
        now = now.addingTimeInterval(86400)
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "clock-jump"), .failure(.stalePreview))
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
        XCTAssertFalse(effects.contains("release"))
    }

    func testUnknownBootCannotConfirmANewRecoveryInterval() throws {
        let preview = try controller().preview().get()
        boot = nil
        XCTAssertEqual(controller().status(), .failure(.unavailable), "a pass that cannot be confirmed must not say Available")
        XCTAssertEqual(controller().preview(), .failure(.unavailable))
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "unknown-boot"), .failure(.unavailable))
        XCTAssertEqual(try ledger().peek().get().pass.usage, .neverUsed)
        XCTAssertTrue(effects.isEmpty)
    }

    func testLosingBootIdentityEndsRecoveryAndReappliesWithoutRefundPC59() throws {
        _ = try use()
        advance(60)
        boot = nil
        XCTAssertEqual(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)), .enforcing)
        let status = try controller().reconcile().get()
        XCTAssertFalse(status.pass.isRecovering)
        XCTAssertTrue(status.timeUncertain)
        XCTAssertEqual(status.pass.eligibility, .coolingDown(remaining: 604740))
        XCTAssertEqual(effects.filter { $0 == "resume" }.count, 1)
        XCTAssertEqual(try ledger().peek().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testInvalidElapsedClockNeverAdvertisesAnAvailablePassOrAuthorizesApply() throws {
        let preview = try controller().preview().get()
        for value in [TimeInterval.nan, .infinity, -1] {
            monotonic = value
            XCTAssertEqual(controller().status(), .failure(.unavailable))
            XCTAssertEqual(controller().preview(), .failure(.unavailable))
            XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "invalid-clock"), .failure(.unavailable))
            XCTAssertEqual(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)), .unavailable)
            XCTAssertTrue(effects.isEmpty)
        }
        monotonic = 10000
        XCTAssertEqual(try ledger().peek().get().pass.usage, .neverUsed)
    }

    func testCarrierMustArmBeforeCommitPC43() throws {
        let preview = try controller().preview().get()
        carrierFails = true
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "a"), .failure(.carrierUnavailable))
        XCTAssertEqual(try ledger().read().get().pass.usage, .neverUsed)
        XCTAssertFalse(effects.contains("terminate"))
        XCTAssertFalse(effects.contains("release"))
    }

    func testScopeChangeWhileArmingCannotSpendTheReviewedPass() throws {
        let preview = try controller().preview().get()
        changesScopeDuringRegistration = true
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "boundary"), .failure(.stalePreview))
        XCTAssertEqual(try ledger().peek().get().pass.usage, .neverUsed)
        XCTAssertNil(try ledger().peek().get().emergencyRecovery)
        XCTAssertFalse(effects.contains("terminate"))
        XCTAssertFalse(effects.contains("release"))
    }

    func testThresholdObservationCanPersistWhileCarrierRegistrationRuns() throws {
        let proof = FlintPassLimitThreshold(window: .init(start: now.addingTimeInterval(-60), end: now.addingTimeInterval(7200)),
            thresholdMinutes: 30, applications: ["opaque-a"], categories: [], webDomains: [], includeEntireCategory: false)
        registrationWork = {
            XCTAssertNoThrow(try self.ledger().transaction { $0.passLimitThresholds = ["limit-a": proof] }.get())
        }
        _ = try use()
        XCTAssertEqual(try ledger().peek().get().passLimitThresholds?["limit-a"], proof)
        XCTAssertEqual(try ledger().peek().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testCorruptWriteCannotReleasePC43() throws {
        let preview = try controller().preview().get()
        try Data("corrupt".utf8).write(to: directory.appendingPathComponent("ledger.json"))
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "a"), .failure(.unavailable))
        XCTAssertFalse(effects.contains("terminate"))
        XCTAssertFalse(effects.contains("release"))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testFailedAtomicWriteDoesNotConsumeOrReleasePC43() throws {
        let preview = try controller().preview().get()
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: directory.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path) }
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "write-failed"), .failure(.unavailable))
        let stored = try ledger().peek().get()
        XCTAssertEqual(stored.pass.usage, .neverUsed)
        XCTAssertNil(stored.emergencyRecovery)
        XCTAssertFalse(effects.contains("release"))
        XCTAssertFalse(effects.contains("terminate"))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testCrashAfterCommitReplaysSameOutboxWithoutSpendingTwicePC44PC45() throws {
        let preview = try controller().preview().get()
        crash = true
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "a"), .failure(.applying))
        XCTAssertEqual(try ledger().peek().get().pass.usage, .used)
        XCTAssertTrue(try controller().status().get().needsApplication)
        XCTAssertTrue(effects.isEmpty)
        XCTAssertTrue(emitted.isEmpty)
        advance(600)
        crash = false
        let replay = try controller().use(previewRevision: "stale is ignored for a committed retry", operationID: "a").get()
        XCTAssertEqual(replay.pass.recoveryRemaining, 3000)
        XCTAssertEqual(emitted.count, 1)
        XCTAssertFalse(replay.needsApplication)
        _ = controller().reconcile()
        XCTAssertEqual(emitted.count, 1)
        XCTAssertEqual(try ledger().read().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testAdapterFailureRetainsOutboxForReplayPC44() throws {
        releaseFails = true
        let preview = try controller().preview().get()
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "a"), .failure(.applying))
        XCTAssertEqual(try ledger().peek().get().emergencyRecovery?.releasePending, true)
        releaseFails = false
        _ = try controller().reconcile().get()
        XCTAssertEqual(try ledger().peek().get().emergencyRecovery?.releasePending, false)
        XCTAssertEqual(emitted.count, 1)
    }

    func testCommittedReplayRepairsResumeCarrierBeforeReleasingAnything() throws {
        let preview = try controller().preview().get()
        crash = true
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "repair-first"), .failure(.applying))
        carrierFails = true
        XCTAssertEqual(controller().reconcile(), .failure(.applying))
        XCTAssertTrue(effects.isEmpty, "lost carrier cannot authorize a new release on replay")
        XCTAssertTrue(try ledger().peek().get().emergencyRecovery?.releasePending == true)
        carrierFails = false
        _ = try controller().reconcile().get()
        XCTAssertTrue(effects.contains("release"))
        XCTAssertEqual(try ledger().peek().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testSecondRedemptionCannotExtendRecoveryPC45() throws {
        _ = try use()
        advance(500)
        let before = try ledger().read().get().pass.status(at: .init(clock: clock), hasActiveCommitment: true)
        XCTAssertEqual(controller().use(previewRevision: revision, operationID: "b"), .failure(.resumeProtectionFirst))
        let after = try ledger().read().get().pass.status(at: .init(clock: clock), hasActiveCommitment: true)
        XCTAssertEqual(before, after)
    }

    func testCarrierEndAndResumeNowReapplyWithoutRefundPC42PC46() throws {
        _ = try use()
        advance(3600)
        _ = try controller().reconcile().get()
        XCTAssertEqual(effects.filter { $0 == "resume" }.count, 1)
        _ = try controller().reconcile().get()
        XCTAssertEqual(effects.filter { $0 == "resume" }.count, 1)
        XCTAssertEqual(try ledger().read().get().pass.cooldownRemaining, 604800 - 3600)
    }

    func testRecoveryCarrierReappliesWithoutTheHostAndNeverSpendsAgain() throws {
        let status = try use()
        let carrier = try XCTUnwrap(status.recovery?.carrierName)
        advance(3599)
        controller().handleRecoveryBoundary(activity: carrier)
        XCTAssertFalse(effects.contains("resume"), "an early native callback cannot end recovery")
        advance(1)
        controller().handleRecoveryBoundary(activity: carrier)
        controller().handleRecoveryBoundary(activity: carrier)
        XCTAssertEqual(effects.filter { $0 == "resume" }.count, 1)
        XCTAssertEqual(try ledger().read().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testUnreadableCarrierCallbackPreservesRegistrationWhileKnownOrphanOnlyStopsItself() throws {
        _ = try controller().preview().get()
        let orphan = FlintEmergencyPassController.carrierPrefix + "orphan"
        controller().handleRecoveryBoundary(activity: orphan)
        XCTAssertEqual(effects, ["stop:\(orphan)"])
        try Data("corrupt".utf8).write(to: directory.appendingPathComponent("ledger.json"))
        controller().handleRecoveryBoundary(activity: orphan)
        XCTAssertEqual(effects, ["stop:\(orphan)"])
    }

    func testResumeNowTruncatesAndNeverRefunds() throws {
        _ = try use()
        advance(30)
        let status = try controller().resumeNow().get()
        XCTAssertFalse(status.pass.isRecovering)
        XCTAssertTrue(effects.contains("resume"))
        XCTAssertEqual(try ledger().read().get().pass.cooldownRemaining, 604770)
    }

    func testFailedResumeRemainsVisibleAndRetryableAfterRecoveryEnds() throws {
        _ = try use()
        resumeFails = true
        advance(3600)
        XCTAssertEqual(controller().reconcile(), .failure(.applying))
        let pending = try controller().status().get()
        XCTAssertFalse(pending.pass.isRecovering)
        XCTAssertTrue(pending.needsApplication)
        XCTAssertFalse(pending.isAvailable)
        XCTAssertEqual(pending.label, "Saved recovery needs to finish applying")
        resumeFails = false
        XCTAssertFalse(try controller().reconcile().get().needsApplication)
    }

    func testApplicationDelayPastExpiryImmediatelyResumesAndNeverRearmsRecovery() throws {
        let acceptedAt = now
        advanceDuringRelease = 4000
        let status = try use()
        XCTAssertFalse(status.pass.isRecovering)
        XCTAssertTrue(registrationTimes.allSatisfy { $0 < acceptedAt.addingTimeInterval(3600) },
                      "pre-commit arm and pre-release verification only; no new recovery after expiry")
        XCTAssertTrue(effects.contains("resume"))
        XCTAssertEqual(try ledger().read().get().pass.cooldownRemaining, 600800)
    }

    func testSameBootWallClockEditsCannotChangeRecoveryOrCooldownPC54PC55() throws {
        _ = try use()
        advance(60)
        now = now.addingTimeInterval(30 * 86400)
        XCTAssertEqual(try controller().status().get().pass.recoveryRemaining, 3540)
        now = now.addingTimeInterval(-60 * 86400)
        let status = try controller().status().get()
        XCTAssertEqual(status.pass.recoveryRemaining, 3540)
        XCTAssertTrue(status.timeUncertain)
        XCTAssertEqual(try ledger().read().get().pass.cooldownRemaining, 604740)
    }

    func testRebootEndsRecoveryRetainsSpendAndReappliesPC59PC60() throws {
        _ = try use()
        advance(60)
        _ = controller().status()
        boot = "boot-b"; monotonic = 1; now = now.addingTimeInterval(100 * 86400)
        let status = try controller().reconcile().get()
        XCTAssertFalse(status.pass.isRecovering)
        XCTAssertTrue(status.timeUncertain)
        XCTAssertTrue(effects.contains("resume"))
        XCTAssertEqual(try ledger().read().get().pass.cooldownRemaining, 604740)
    }

    func testPendingHarderWaitsCancelWithoutRefundingCount() throws {
        _ = ledger().transaction { state in
            state.harder["c"] = .init(committedCount: 3)
            _ = state.requestHarderWait(cause: "c", kind: "stop", scope: [], operationID: "wait", at: .init(clock: clock))
        }
        _ = try use()
        let state = try ledger().read().get()
        XCTAssertNil(state.harder["c"]?.pending)
        XCTAssertEqual(state.harder["c"]?.committedCount, 3)
    }

    func testPassCapturesKnownTimeLimitExhaustionBeforeClearingItsStore() throws {
        let window = DateInterval(start: now.addingTimeInterval(-60), end: now.addingTimeInterval(7200))
        let proof = FlintPassLimitThreshold(window: window, thresholdMinutes: 30,
            applications: ["opaque-a"], categories: [], webDomains: [], includeEntireCategory: false)
        rules = [.init(id: "timeLimit:limit-a", name: "Time", kind: .timeLimit,
            naturalStart: window.start, naturalEnd: window.end, storeName: "flintL.limit-a",
            activityName: "flint.limit.limit-a", timeLimitThreshold: proof)]
        _ = try use()
        XCTAssertEqual(try ledger().peek().get().passLimitThresholds?["limit-a"], proof)
    }

    func testThresholdEvidencePreservesProvableStrengtheningButNotHigherOrDifferentScopeBudget() {
        let window = DateInterval(start: now.addingTimeInterval(-60), end: now.addingTimeInterval(7200))
        func evidence(_ cap: Int, apps: Set<String> = ["a"], categories: Set<String> = ["category"]) -> FlintPassLimitThreshold {
            .init(window: window, thresholdMinutes: cap, applications: apps, categories: categories,
                  webDomains: [], includeEntireCategory: false)
        }
        let original = evidence(30)
        XCTAssertTrue(original.provesExhaustion(of: evidence(15, apps: ["a", "b"]), at: now))
        XCTAssertFalse(original.provesExhaustion(of: evidence(60), at: now))
        XCTAssertFalse(original.provesExhaustion(of: evidence(15, apps: ["b"]), at: now))
        XCTAssertFalse(original.provesExhaustion(of: evidence(15, categories: ["different"]), at: now))
        XCTAssertFalse(original.provesExhaustion(of: evidence(15), at: window.end))
    }

    func testLiveOpenGrantCancellationSurvivesCrashWithoutRefundingOrStoppingBeforeCommit() throws {
        let payload = FlintOpenCreditRestoration(kind: .application,
            encodedToken: try JSONEncoder().encode(["opaque-test-token"]), storeNames: ["flintO.open"])
        let target = payload.targetKey
        let instant = FlintLedgerInstant(clock: clock)
        let grant = FlintOpenCreditGrant(targetKey: target, operationID: "credit-used", grantedAt: instant,
            deadline: now.addingTimeInterval(300), duration: 300, coveredCauses: ["open"],
            coveredFingerprints: ["open": "effective"], coveredScopes: ["open": "scope"],
            coveredCaps: ["open": 5], coveredActivities: ["open": "flint.openLimit.open.r1"], restoration: payload)
        var credits = FlintOpenCreditState()
        credits.availability = .ready; credits.quotaZone = "UTC"
        credits.periodStart = Calendar(identifier: .gregorian).startOfDay(for: now)
        credits.checkpoint = instant; credits.trustedWall = now; credits.resetRemaining = 3600
        credits.counters[target] = .init(targetKey: target, periodStart: try XCTUnwrap(credits.periodStart), used: 3)
        credits.knownTargets = [target]; credits.acceptedOperations = [grant.operationID]; credits.enrolled = true
        credits.grants[target] = grant; credits.carrierCheckpoints[target] = instant
        XCTAssertTrue(credits.isValid)
        _ = try ledger().transaction { $0.openCredits = credits }.get()
        let preview = try controller().preview().get()
        crash = true
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "cancel-credit"), .failure(.applying))
        var stored = try ledger().peek().get()
        XCTAssertTrue(stored.openCredits.grants.isEmpty)
        XCTAssertTrue(stored.openCredits.carrierCheckpoints.isEmpty)
        XCTAssertEqual(stored.openCredits.counters, credits.counters)
        XCTAssertEqual(stored.openCredits.acceptedOperations, credits.acceptedOperations)
        XCTAssertEqual(stored.emergencyRecovery?.cancelledCarriers, [grant.activityName])
        XCTAssertTrue(effects.isEmpty)
        crash = false
        _ = try controller().reconcile().get()
        stored = try ledger().peek().get()
        XCTAssertTrue(effects.contains("stop:\(grant.activityName)"))
        XCTAssertEqual(stored.openCredits.counters, credits.counters)
        XCTAssertEqual(stored.openCredits.acceptedOperations, credits.acceptedOperations)
    }

    func testOldLedgerFixtureWithoutRecoveryFieldsRemainsReadable() throws {
        _ = try ledger().read().get()
        let file = directory.appendingPathComponent("ledger.json")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        for key in ["emergencyRecovery", "passLimitThresholds", "passSuppressedFocusOwners"] { json.removeValue(forKey: key) }
        try JSONSerialization.data(withJSONObject: json).write(to: file)
        XCTAssertEqual(try controller().status().get().pass.eligibility, .available)
    }

    func testUnknownAndCorruptRecoveryRecordsAreUnavailableNeverAvailable() throws {
        _ = try use()
        let file = directory.appendingPathComponent("ledger.json")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        var recovery = try XCTUnwrap(json["emergencyRecovery"] as? [String: Any])
        recovery["version"] = 99
        recovery["futureMeaningfulState"] = ["unsupported": true]
        json["emergencyRecovery"] = recovery
        let futureBytes = try JSONSerialization.data(withJSONObject: json)
        try futureBytes.write(to: file)
        XCTAssertEqual(controller().status(), .failure(.unavailable))
        XCTAssertEqual(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)), .unavailable)
        if case .success = ledger().transaction({ $0.harder["unrelated"] = .init() }) {
            XCTFail("no writer may re-encode unsupported recovery intent")
        }
        XCTAssertEqual(try Data(contentsOf: file), futureBytes)
        json["emergencyRecovery"] = "corrupt"
        try JSONSerialization.data(withJSONObject: json).write(to: file)
        XCTAssertEqual(controller().status(), .failure(.unavailable))
    }

    func testMissingCommittedRecoveryDoesNotBecomeNoRecovery() throws {
        _ = try use()
        _ = ledger().transaction { $0.emergencyRecovery = nil }
        XCTAssertEqual(controller().status(), .failure(.unavailable))
        XCTAssertEqual(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)), .unavailable)
        boot = "new-boot"; monotonic = 1
        XCTAssertEqual(controller().status(), .failure(.unavailable), "checkpointing must not hide the missing outbox")
        XCTAssertEqual(controller().resumeNow(), .failure(.unavailable))
    }

    func testMissingRecoveryThresholdStateIsUnavailableInsteadOfSilentlyReset() throws {
        _ = try use()
        _ = ledger().transaction { $0.passLimitThresholds = nil }
        XCTAssertEqual(controller().status(), .failure(.unavailable))
        XCTAssertEqual(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)), .unavailable)
    }

    func testCorruptCarrierCancellationCannotAuthorizeAnUnrelatedStop() throws {
        _ = try use()
        let file = directory.appendingPathComponent("ledger.json")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        var recovery = try XCTUnwrap(json["emergencyRecovery"] as? [String: Any])
        recovery["cancelledCarriers"] = ["flint.session.unrelated"]
        json["emergencyRecovery"] = recovery
        try JSONSerialization.data(withJSONObject: json).write(to: file)
        let before = effects
        XCTAssertEqual(controller().reconcile(), .failure(.unavailable))
        XCTAssertEqual(effects, before)
    }

    func testInvalidCancellationIntentAbortsTheWholeConsumption() throws {
        cancelledCarriers = ["flint.session.unrelated"]
        let preview = try controller().preview().get()
        XCTAssertEqual(controller().use(previewRevision: preview.revision, operationID: "bad-adapter"), .failure(.unavailable))
        let stored = try ledger().peek().get()
        XCTAssertEqual(stored.pass.usage, .neverUsed)
        XCTAssertNil(stored.emergencyRecovery)
        XCTAssertFalse(effects.contains("release"))
        XCTAssertFalse(effects.contains("terminate"))
    }

    func testRuleWorkCannotStopAnUnrelatedSessionRegistration() throws {
        rules = [.init(id: "schedule:a", name: "Rule", kind: .schedule,
            naturalStart: now.addingTimeInterval(-60), naturalEnd: now.addingTimeInterval(7200),
            storeName: "flint.a", activityName: "flint.session.unrelated")]
        XCTAssertEqual(controller().preview(), .failure(.scopeUnavailable))
        XCTAssertEqual(try ledger().peek().get().pass.usage, .neverUsed)
        rules = [.init(id: "schedule:a", name: "Rule", kind: .schedule,
            naturalStart: now.addingTimeInterval(-60), naturalEnd: now.addingTimeInterval(7200),
            storeName: "flint.a", activityName: "flint.rule.a")]
        _ = try use()
        let file = directory.appendingPathComponent("ledger.json")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        var recovery = try XCTUnwrap(json["emergencyRecovery"] as? [String: Any])
        let scope = try XCTUnwrap(recovery["scope"] as? [String: Any])
        var corrupt = try XCTUnwrap((scope["rules"] as? [[String: Any]])?.first)
        corrupt["activityName"] = "flint.session.unrelated"
        recovery["disabledRules"] = [corrupt]
        json["emergencyRecovery"] = recovery
        try JSONSerialization.data(withJSONObject: json).write(to: file)
        let before = effects
        XCTAssertEqual(controller().reconcile(), .failure(.unavailable))
        XCTAssertEqual(effects, before)
    }

    func testFocusSessionIsSuppressedUntilFreshOffOnCycle() throws {
        sessions = [.init(name: "Focus Filter", startedAt: now, endsAt: nil, breakLevel: .easy,
                          monitorName: "focus", origin: "focus_filter", ownerID: "focus-filter")]
        _ = try use()
        XCTAssertEqual(try ledger().read().get().passSuppressedFocusOwners, ["focus-filter"])
        if case let .failure(error) = controller().prepareForNewSession(focusOwnerID: "focus-filter") {
            XCTAssertEqual(error, .resumeProtectionFirst)
        } else { XCTFail("new starts must require Resume protection first") }
        _ = try controller().resumeNow().get()
        if case let .failure(error) = controller().prepareForNewSession(focusOwnerID: "focus-filter") {
            XCTAssertEqual(error, .focusCycleRequired)
        } else { XCTFail("still-on Focus cannot recreate a pass-ended occurrence") }
        _ = try controller().acknowledgeFocusOff(ownerID: "focus-filter").get()
        _ = try controller().prepareForNewSession(focusOwnerID: "focus-filter").get()
        XCTAssertEqual(try ledger().read().get().passSuppressedFocusOwners, [])
        XCTAssertEqual(try ledger().read().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testEveryNewSessionWaitsForRecoveryButCooldownAloneDoesNotBlockStarts() throws {
        _ = try use()
        if case let .failure(error) = controller().prepareForNewSession() {
            XCTAssertEqual(error, .resumeProtectionFirst)
        } else { XCTFail("new starts cannot be apparently protected under global recovery") }
        _ = try controller().resumeNow().get()
        _ = try controller().prepareForNewSession().get()
        XCTAssertGreaterThan(try ledger().peek().get().pass.cooldownRemaining, 0)
    }

    func testManualSessionNamedLikeFocusDoesNotSuppressAnUnrelatedFocusOwner() throws {
        sessions = [.init(name: "Focus Filter", startedAt: now, endsAt: nil, breakLevel: .easy,
                          monitorName: "manual", origin: "app_ui")]
        _ = try use()
        XCTAssertEqual(try ledger().peek().get().passSuppressedFocusOwners, [])
    }

    func testNewDisableConfirmationPreservesEarlierPendingDisableIntent() throws {
        rules = ["a", "b"].map { id in
            .init(id: "schedule:\(id)", name: id, kind: .schedule,
                  naturalStart: now.addingTimeInterval(-60), naturalEnd: now.addingTimeInterval(7200),
                  storeName: "flint.\(id)", activityName: "flint.rule.\(id)")
        }
        _ = try use()
        disableFails = true
        XCTAssertEqual(controller().disableCommitments(previewRevision: revision, ruleIDs: ["schedule:a"]), .failure(.applying))
        disableFails = false
        _ = try controller().disableCommitments(previewRevision: revision, ruleIDs: ["schedule:b"]).get()
        XCTAssertTrue(effects.contains("disable:schedule:a,schedule:b"))
        XCTAssertTrue(try ledger().read().get().emergencyRecovery?.disabledRules.isEmpty == true)
    }

    func testRecoveryGateSkipsApplicationAndKeepsNaturalEndPC42() throws {
        _ = try use()
        XCTAssertFalse(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)).permitsApplication)
        advance(3599)
        XCTAssertTrue(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)).isRecovering)
        advance(1)
        XCTAssertEqual(FlintPassRecoveryGate.read(ledger: ledger(), at: .init(clock: clock)), .enforcing)
    }

    func testThresholdObservedDuringResumeIsAppliedBeforeAcknowledgingOutbox() throws {
        _ = try use()
        advance(3600)
        let threshold = FlintPassLimitThreshold(window: .init(start: now, duration: 3600), thresholdMinutes: 30,
            applications: ["target"], categories: [], webDomains: [], includeEntireCategory: false)
        resumeWork = {
            self.resumeWork = nil
            _ = self.ledger().transaction { $0.passLimitThresholds?["time"] = threshold }
        }
        let status = try controller().reconcile().get()
        XCTAssertEqual(effects.filter { $0 == "resume" }.count, 2)
        XCTAssertFalse(status.needsApplication)
        XCTAssertEqual(try ledger().peek().get().passLimitThresholds?["time"], threshold)
        XCTAssertEqual(try ledger().peek().get().operations.filter { $0.kind == "pass" }.count, 1)
    }

    func testContinuousThresholdChangesKeepResumePendingInsteadOfFalseAcknowledgement() throws {
        _ = try use()
        advance(3600)
        var observation = 0
        resumeWork = {
            observation += 1
            let threshold = FlintPassLimitThreshold(window: .init(start: self.now, duration: 3600), thresholdMinutes: 30,
                applications: ["target-\(observation)"], categories: [], webDomains: [], includeEntireCategory: false)
            _ = self.ledger().transaction { $0.passLimitThresholds?["time"] = threshold }
        }
        XCTAssertEqual(controller().reconcile(), .failure(.applying))
        XCTAssertEqual(effects.filter { $0 == "resume" }.count, 3, "retry work stays bounded")
        XCTAssertTrue(try controller().status().get().needsApplication)
        XCTAssertTrue(try ledger().peek().get().emergencyRecovery?.resumePending == true)
    }
}
