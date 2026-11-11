import XCTest
@testable import FlintCore

/// Red-team re-verification of R2p2-N03 (ADR-012 §5/§8/§12, PC-28/37/70/71).
///
/// Every test below reproduces the *production* order instead of calling the importer alone:
/// `FlintRuleStoreLock.prepareInitialization` (rule bootstrap / legacy upgrade) →
/// `FlintProtectionLedger.appGroup()`-equivalent migration (which runs
/// `FlintOpenCredit.importLegacy` before the pass import) → first Open rule setup → first
/// credit acceptance. The ledger is built exactly like the App Group one so the migration hook,
/// the durable initialized marker and the missing-file rule are identical to production.
final class FlintOpenCreditRedTeamN03Tests: XCTestCase {
    /// Same instant as the other migration fixtures: 2026-10-28T12:00:00Z, UTC.
    private var now: Date { ISO8601DateFormatter().date(from: "2026-10-28T12:00:00Z")! }
    private var calendar: Calendar {
        var result = Calendar(identifier: .gregorian); result.timeZone = TimeZone(identifier: "UTC")!; return result
    }
    /// A stable fake Open target: the credit paths key everything off this hash.
    private let restoration = FlintOpenCreditRestoration(
        kind: .application, encodedToken: Data(#"["n03-app"]"#.utf8), storeNames: ["flintO.first"])

    // MARK: - Production-order helpers

    /// Mirrors `FlintProtectionLedger.appGroup()` exactly (same migration order and marker key),
    /// but rooted in the harness directory/suite so the test owns the state.
    private func productionLedger(_ h: RuleHarness) -> FlintProtectionLedger {
        .init(directory: h.directory.appendingPathComponent("ledger"), clock: h.clock,
              initializedMarker: { h.marker }, setInitializedMarker: { h.marker = $0 },
              migrate: { state, at in
                  // Production order: credit recovery inspects the pre-import rule evidence
                  // before anything else adds ledger state.
                  FlintOpenCredit.importLegacy(into: &state, defaults: h.defaults, at: at,
                                              calendar: h.clock.calendar())
                  let raw = h.defaults.object(forKey: FlintLedgerState.legacyPassKey)
                  state.importLegacyPass(raw.map(FlintLegacyValue.value) ?? .absent, at: at)
              })
    }

    private func instant(_ h: RuleHarness) -> FlintLedgerInstant { .init(clock: h.clock) }

    /// Saves one enabled Open rule through the app's store, then reconciles quota for its
    /// target exactly as `FlintOpenCreditService` does before offering a credit.
    private func firstOpenRuleAndReconcile(_ h: RuleHarness, state: inout FlintLedgerState,
                                          id: String = "first") -> FlintLedgerInstant {
        let rule = FlintOpenLimitRule(id: id, name: "First", opensAllowed: 3,
                                      protection: .init(timeZoneID: "UTC"))
        XCTAssertTrue(FlintOpenLimitRule.saveAll([rule], to: h.defaults))
        let at = instant(h)
        XCTAssertTrue(state.reconcileOpenCredits(at: at, targetKeys: [restoration.targetKey],
                                                 setupCalendar: calendar, reshield: { _ in }))
        return at
    }

    private func preview(_ state: FlintLedgerState, at: FlintLedgerInstant) -> FlintOpenCreditPreview {
        state.openCreditPreview(target: restoration.targetKey, caps: [3], blockedBy: nil, at: at)
    }

    private func accept(_ state: inout FlintLedgerState, at: FlintLedgerInstant, operationID: String)
        -> Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal> {
        state.acceptOpenCredit(target: restoration.targetKey, operationID: operationID,
                               coveringCaps: ["first": 3], coveringFingerprints: ["first": "fingerprint"],
                               coveringScopes: ["first": "scope"], coveringActivities: ["first": "flintO.first"],
                               targetKeys: [restoration.targetKey], restoration: restoration,
                               at: at, setupCalendar: calendar, blocking: { nil },
                               register: { _ in }, reshield: { _ in })
    }

    // MARK: - Direction 1: a fresh / never-used install must keep its first quota

    func testRedTeamN03FreshInstallProductionOrderProvidesTheFirstOpenQuota() throws {
        let h = try RuleHarness(); defer { h.clean() }
        // Fresh install after onboarding: only non-protection preferences exist.
        h.defaults.set(true, forKey: "flint.onboarded")
        h.defaults.set(true, forKey: "flint.consent.accepted")
        h.defaults.set(Data("history is not protection evidence".utf8), forKey: "flint.history.events")
        XCTAssertNil(h.defaults.object(forKey: FlintRuleInitialization.receiptKey))

        // 1. Rule bootstrap (host prepare).
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)

        // 2. Production ledger creation runs the credit migration.
        var state = try productionLedger(h).read().get()
        XCTAssertEqual(state.openCredits.availability, .ready,
                       "a never-used Open setup must not be migrated as a lost established counter")
        XCTAssertTrue(state.migrations.contains(FlintLedgerState.legacyOpenCreditMigration))

        // 3. First Open rule + first credit.
        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(preview(state, at: at).remaining, 3, "first Open quota must be available")
        switch accept(&state, at: at, operationID: "first-credit") {
        case let .success(receipt):
            XCTAssertEqual(receipt.remaining, 2)
        case let .failure(refusal):
            XCTFail("first credit was refused on a fresh install: \(refusal)")
        }
        XCTAssertEqual(state.openCredits.counters[restoration.targetKey]?.used, 1)
    }

    func testRedTeamN03LedgerCreatedBeforeBootstrapStillLeavesTheFirstQuotaAvailable() throws {
        let h = try RuleHarness(); defer { h.clean() }
        // An extension or an early host read creates the ledger before the rule bootstrap runs.
        var state = try productionLedger(h).read().get()
        XCTAssertEqual(state.openCredits.availability, .ready)
        XCTAssertNotNil(h.marker)
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
        // Re-read: the migration is already recorded, so no second import happens.
        state = try productionLedger(h).read().get()
        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(preview(state, at: at).remaining, 3)
    }

    func testRedTeamN03InterruptedBootstrapKeepsTheFirstQuotaAvailable() throws {
        let h = try RuleHarness(); defer { h.clean() }
        // Process death after the receipt and one empty domain were written: the known
        // partial after-image the bootstrap is allowed to resume.
        h.defaults.set(Data("{\"schema\":1,\"phase\":\"preparing\"}".utf8),
                       forKey: FlintRuleInitialization.receiptKey)
        XCTAssertTrue(FlintOpenLimitRule.saveAll([], to: h.defaults))
        var state = try productionLedger(h).read().get()
        // While the bootstrap is unfinished its rules are unreadable, so the import waits and
        // the quota stays closed instead of recording a day-long unavailable verdict.
        XCTAssertEqual(state.openCredits.availability, .pendingImport,
                       "an interrupted fresh bootstrap must not record the credit import yet")
        XCTAssertFalse(state.migrations.contains(FlintLedgerState.legacyOpenCreditMigration))
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
        state = try productionLedger(h).read().get()
        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(preview(state, at: at).remaining, 3)
    }

    func testRedTeamN03FailedBootstrapThenLedgerThenResumeKeepsTheFirstQuota() throws {
        // Production order with a mid-bootstrap write failure: prepare() persists the
        // `preparing` receipt, then the array write is refused, so the host's publish step
        // still creates the ledger (it runs after layers regardless of prepare's result).
        // The bootstrap is resumed on a later launch, but the credit migration is one-shot.
        let h = try RuleHarness(); defer { h.clean() }
        var environment = h.environment
        var saves = 0
        environment.acceptSave = { saves += 1; return saves <= 1 }
        XCTAssertFalse(FlintRuleStoreLock.prepareInitialization(environment: environment),
                       "the bootstrap must report the refused write")
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .preparing)

        // publish: the ledger is initialized. With the bootstrap unfinished the credit import
        // must not record a verdict yet; the quota stays closed (`pendingImport`) meanwhile.
        var state = try productionLedger(h).read().get()
        XCTAssertFalse(state.migrations.contains(FlintLedgerState.legacyOpenCreditMigration))
        XCTAssertEqual(state.openCredits.availability, .pendingImport)
        XCTAssertNil(preview(state, at: instant(h)).remaining, "no credit is offered before the import")

        // A later launch resumes and completes the same bootstrap.
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
        state = try productionLedger(h).read().get()

        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(preview(state, at: at).remaining, 3,
                       "a resumed fresh bootstrap must not lose its first quota for the rest of the day")
    }

    func testRedTeamN03LegacyInstallThatNeverUsedOpenGetsItsFirstQuota() throws {
        // A valid pre-ADR-012 install that never configured an Open Limit. The upgrade must
        // carry the never-used provenance, exactly like a fresh install (finding reproduce #2).
        for evidence in ["session", "schedule"] {
            let h = try RuleHarness(); defer { h.clean() }
            switch evidence {
            case "session":
                let session = FlintActiveSession(id: "legacy", name: "Legacy session",
                    startedAt: h.now.addingTimeInterval(-60), endsAt: h.now.addingTimeInterval(1_800),
                    breakLevel: .hardcore, monitorName: "flint.session.legacy")
                h.defaults.set(try JSONEncoder().encode(session), forKey: "flint.activeSession")
            default:
                h.defaults.set(try JSONEncoder().encode([h.schedule(tier: .hardcore)]),
                               forKey: FlintRuleStoreKey.schedules.rawValue)
            }
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
            XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .absent,
                           "an upgrade is not a fresh install (\(evidence))")
            XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.openLimits.rawValue), Data("[]".utf8),
                           "bootstrap proved the domain never used (\(evidence))")

            var state = try productionLedger(h).read().get()
            let at = firstOpenRuleAndReconcile(h, state: &state)
            XCTAssertEqual(preview(state, at: at).remaining, 3,
                           "legacy \(evidence): a provably never-used Open domain must get its first quota")
            switch accept(&state, at: at, operationID: "legacy-first-credit") {
            case .success: break
            case let .failure(refusal):
                XCTFail("legacy \(evidence): first credit refused after upgrade: \(refusal)")
            }
        }
    }

    // MARK: - Direction 2: the fix must never mint unearned credit

    func testRedTeamN03LegacyDeletedRulesAndLostCounterStayUnavailable() throws {
        // Established legacy install: the user deleted every Open rule and the legacy counter
        // is gone. There is no receipt, so this must keep the closed "lost counter" refusal.
        let h = try RuleHarness(); defer { h.clean() }
        let session = FlintActiveSession(id: "legacy", name: "Legacy session",
            startedAt: h.now.addingTimeInterval(-60), endsAt: h.now.addingTimeInterval(1_800),
            breakLevel: .hardcore, monitorName: "flint.session.legacy")
        h.defaults.set(try JSONEncoder().encode(session), forKey: "flint.activeSession")
        // v0.1.0's `FlintOpenLimitRule.saveAll([])` leaves the encoded empty array behind when
        // the user deletes every rule; that saved `[]` is what distinguishes deletion from a
        // domain the upgrade proves never used.
        h.defaults.set(Data("[]".utf8), forKey: FlintRuleStoreKey.openLimits.rawValue)
        XCTAssertNil(h.defaults.object(forKey: FlintLedgerState.legacyOpenCreditKey))

        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .absent)
        XCTAssertNil(h.defaults.object(forKey: "flint.openLimit.rules.neverUsed.adr012"),
                     "a user's saved empty array is never proof of a never-used domain")
        var state = try productionLedger(h).read().get()
        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(state.openCredits.availability, .unavailable)
        XCTAssertNil(preview(state, at: at).remaining, "a lost established counter cannot become a fresh pool")
        if case .success = accept(&state, at: at, operationID: "bypass-1") {
            XCTFail("a lost established counter was refilled")
        }
        XCTAssertEqual(state.openCredits.counters[restoration.targetKey]?.used ?? 0, 0)
        XCTAssertTrue(state.openCredits.grants.isEmpty)
    }

    func testRedTeamN03ReceiptCompleteCannotOverrideASurvivingLegacyCounter() throws {
        // The receipt only proves "this build has bootstrapped once". If a downgrade wrote a
        // legacy counter, its consumption still has to be honored (no refill through the fix).
        let h = try RuleHarness(); defer { h.clean() }
        h.defaults.set(Data("{\"schema\":1,\"phase\":\"complete\"}".utf8),
                       forKey: FlintRuleInitialization.receiptKey)
        h.defaults.set(Data("{\"schema\":1}".utf8), forKey: FlintRuleStoreKey.openLimits.marker)
        XCTAssertTrue(FlintOpenLimitRule.saveAll([], to: h.defaults))
        let legacy = try JSONEncoder().encode(FlintOpenLimitState(dayStart: now,
            counts: [restoration.targetKey: 3]))
        h.defaults.set(legacy, forKey: FlintLedgerState.legacyOpenCreditKey)

        var state = try productionLedger(h).read().get()
        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(state.openCredits.counters[restoration.targetKey]?.used, 3,
                       "the surviving counter, not the receipt, decides the pool")
        XCTAssertEqual(preview(state, at: at).remaining, 0)
        if case .success = accept(&state, at: at, operationID: "bypass-2") {
            XCTFail("a fully consumed legacy counter was refilled")
        }
    }

    func testRedTeamN03NeverUsedProofCannotOverrideASurvivingLegacyCounter() throws {
        // Legacy install with session evidence and no saved Open rules, but a legacy counter
        // that already spent today's quota: the proof is written, yet the counter still wins.
        let h = try RuleHarness(); defer { h.clean() }
        let session = FlintActiveSession(id: "legacy", name: "Legacy session",
            startedAt: h.now.addingTimeInterval(-60), endsAt: h.now.addingTimeInterval(1_800),
            breakLevel: .hardcore, monitorName: "flint.session.legacy")
        h.defaults.set(try JSONEncoder().encode(session), forKey: "flint.activeSession")
        let legacy = try JSONEncoder().encode(FlintOpenLimitState(dayStart: now,
            counts: [restoration.targetKey: 3]))
        h.defaults.set(legacy, forKey: FlintLedgerState.legacyOpenCreditKey)

        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        var state = try productionLedger(h).read().get()
        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(state.openCredits.counters[restoration.targetKey]?.used, 3)
        XCTAssertEqual(preview(state, at: at).remaining, 0)
        if case .success = accept(&state, at: at, operationID: "bypass-proof") {
            XCTFail("the never-used proof refilled a spent legacy counter")
        }
    }

    func testRedTeamN03UnreadableRestoredRulesWithReceiptStayUnavailable() throws {
        // Partial App Group restore: the receipt and markers survive, but the Open rule bytes
        // are unreadable and no counter is present. That is corruption, never a fresh pool.
        let h = try RuleHarness(); defer { h.clean() }
        h.defaults.set(Data("{\"schema\":1,\"phase\":\"complete\"}".utf8),
                       forKey: FlintRuleInitialization.receiptKey)
        h.defaults.set(Data("{\"schema\":1}".utf8), forKey: FlintRuleStoreKey.openLimits.marker)
        h.defaults.set(Data(#"[{"id":"future-schema"}]"#.utf8),
                       forKey: FlintRuleStoreKey.openLimits.rawValue)

        let state = try productionLedger(h).read().get()
        XCTAssertEqual(state.openCredits.availability, .unavailable)
        let at = instant(h)
        XCTAssertNil(preview(state, at: at).remaining)
        XCTAssertNil(FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: h.defaults,
                                            at: now, migrating: false,
                                            recoveryDirectory: h.recoveryDirectory),
                     "unreadable rules cannot arm, and cannot mint credit either")
    }

    func testRedTeamN03LostLedgerFileAfterInitializationCannotReimportAFreshPool() throws {
        // Partial restore: the App Group preferences (receipt, empty rule array) come back but
        // the ledger directory does not. The durable marker outside the file must make this
        // unavailable instead of re-running the migration and minting a new day.
        let h = try RuleHarness(); defer { h.clean() }
        h.defaults.set(Data("{\"schema\":1,\"phase\":\"complete\"}".utf8),
                       forKey: FlintRuleInitialization.receiptKey)
        h.defaults.set(Data("{\"schema\":1}".utf8), forKey: FlintRuleStoreKey.openLimits.marker)
        XCTAssertTrue(FlintOpenLimitRule.saveAll([], to: h.defaults))
        var state = try productionLedger(h).read().get()
        let at = firstOpenRuleAndReconcile(h, state: &state)
        if case let .failure(refusal) = accept(&state, at: at, operationID: "consumed") {
            return XCTFail("fixture could not spend a credit: \(refusal)")
        }
        switch productionLedger(h).transaction({ $0.openCredits = state.openCredits }) {
        case .failure(let error): return XCTFail("could not persist the consumed counter: \(error)")
        case .success: break
        }
        XCTAssertEqual(try productionLedger(h).read().get().openCredits.counters[restoration.targetKey]?.used, 1)

        try FileManager.default.removeItem(
            at: h.directory.appendingPathComponent("ledger").appendingPathComponent(FlintProtectionLedger.fileName))
        switch productionLedger(h).read() {
        case .success(let reinitialized):
            XCTFail("a lost ledger re-imported as fresh: \(reinitialized.openCredits.availability)")
        case .failure(let error):
            guard case .unavailable = error else { return XCTFail("unexpected ledger error \(error)") }
        }
    }

    func testRedTeamN03LegacyCounterForARotatedIdentityNeverMintsForANewTarget() throws {
        // A fully consumed legacy day whose only counter belongs to an old identity must not
        // hand a fresh pool to a replacement identity, even with a completion receipt.
        let h = try RuleHarness(); defer { h.clean() }
        h.defaults.set(Data("{\"schema\":1,\"phase\":\"complete\"}".utf8),
                       forKey: FlintRuleInitialization.receiptKey)
        h.defaults.set(Data("{\"schema\":1}".utf8), forKey: FlintRuleStoreKey.openLimits.marker)
        XCTAssertTrue(FlintOpenLimitRule.saveAll([], to: h.defaults))
        let legacy = try JSONEncoder().encode(FlintOpenLimitState(dayStart: now, counts: ["old-token": 3]))
        h.defaults.set(legacy, forKey: FlintLedgerState.legacyOpenCreditKey)

        var state = try productionLedger(h).read().get()
        let at = firstOpenRuleAndReconcile(h, state: &state)
        XCTAssertEqual(state.openCredits.counters["old-token"]?.used, 3)
        XCTAssertNil(state.openCredits.counters[restoration.targetKey],
                     "a rotated identity must not inherit a fresh counter")
        if case .success = accept(&state, at: at, operationID: "bypass-3") {
            XCTFail("a rotated identity was granted a fresh pool")
        }
    }
}
