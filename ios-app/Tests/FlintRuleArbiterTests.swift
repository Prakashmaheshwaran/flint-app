import XCTest
import FamilyControls
import DeviceActivity
import ManagedSettings
@testable import FlintCore

/// Concrete controller/arbiter matrix, including Sleep's actual schedule representation.
/// Fake adapters prove transactions and callback decisions, not physical Screen Time delivery.
final class FlintRuleArbiterTests: XCTestCase {
    func testScheduleMatrixEveryRowTierAndActivityPC11To14() throws {
        try matrix(make: { tier, active in
            FlintScheduleRule(id: "schedule", name: "Work", schedule: .init(startHour: 9, endHour: 17),
                breakLevel: tier, allowListMode: true, enabled: active, protection: .init(timeZoneID: "UTC"))
        }, strengthen: { $0.schedule.endHour = 18 }, weaken: { $0.schedule.endHour = 16 })
    }

    func testSleepMatrixEveryRowTierAndActivityPC11To14() throws {
        try matrix(make: { tier, active in
            FlintScheduleRule(id: "sleep.night", name: "Sleep", schedule: .init(startHour: 9, endHour: 17),
                breakLevel: tier, allowListMode: true, enabled: active, protection: .init(timeZoneID: "UTC"))
        }, strengthen: { $0.schedule.endHour = 18 }, weaken: { $0.schedule.endHour = 16 })
    }

    func testTimeLimitMatrixIncludingUnspentBudgetPC15() throws {
        try matrix(make: { tier, active in
            FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, breakLevel: tier,
                enabled: active, protection: .init(timeZoneID: "UTC"))
        }, strengthen: { $0.thresholdMinutes = 20 }, weaken: { $0.thresholdMinutes = 60 })
    }

    func testOpenLimitMatrixIncludingAvailableCreditPC16() throws {
        try matrix(make: { tier, active in
            FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3, breakLevel: tier,
                enabled: active, protection: .init(timeZoneID: "UTC"))
        }, strengthen: { $0.opensAllowed = 2 }, weaken: { $0.opensAllowed = 5 })
    }

    private func matrix<Rule: FlintGatedRule>(make: (BreakLevel, Bool) -> Rule,
        strengthen: (inout Rule) -> Void, weaken: (inout Rule) -> Void) throws {
        for tier in BreakLevel.allCases {
            for active in [false, true] {
                for row in ["rename", "strengthen", "weaken", "off", "delete"] {
                    let h = try RuleHarness(); defer { h.clean() }
                    let old = h.install(make(tier, active))
                    var draft = old
                    if row == "rename" { draft = renamed(draft) }
                    if row == "strengthen" { strengthen(&draft) }
                    if row == "weaken" { weaken(&draft) }
                    let arbiter = FlintRuleArbiter(environment: h.environment)
                    func request(_ confirmation: FlintRuleConfirmation?) -> FlintRuleMutationResult {
                        switch row {
                        case "delete": return arbiter.delete(Rule.self, id: old.id, revision: old.revision, confirmation: confirmation)
                        case "off": return arbiter.setEnabled(Rule.self, id: old.id, enabled: false,
                            revision: old.revision, confirmation: confirmation)
                        default: return arbiter.upsert(draft, confirmation: confirmation)
                        }
                    }
                    let result = request(nil)
                    let weakening = ["weaken", "off", "delete"].contains(row)
                    let context = "\(Rule.domain) \(tier) active=\(active) \(row)"
                    if active && weakening && tier == .hardcore {
                        guard case let .deferRequired(ticket, end) = result else { XCTFail("\(context): \(result)"); continue }
                        XCTAssertEqual(end, old.occurrence(at: h.now)?.end, context)
                        XCTAssertEqual(request(ticket), .queued(until: end), context)
                        let retained = try XCTUnwrap(h.load(Rule.self).first)
                        XCTAssertEqual(retained.enabled, old.enabled, context)
                        XCTAssertEqual(retained.activityName, old.activityName, context)
                        XCTAssertNotNil(retained.protection?.queuedChange, context)
                        XCTAssertTrue(h.cleared.isEmpty, context)
                    } else if active && weakening && tier == .harder {
                        guard case let .waiting(ticket, remaining, required) = result else { XCTFail("\(context): \(result)"); continue }
                        XCTAssertEqual(remaining, 60, context); XCTAssertEqual(required, 60, context)
                        h.advance(60)
                        XCTAssertFalse(request(nil).didSave, "maturity alone cannot commit: \(context)")
                        XCTAssertEqual(request(ticket), .applied, context)
                    } else {
                        if case let .confirmationRequired(ticket, _) = result { XCTAssertEqual(request(ticket), .applied, context) }
                        else { XCTAssertEqual(result, .applied, context) }
                    }
                }
            }
        }
    }

    private func renamed<Rule: FlintGatedRule>(_ value: Rule) -> Rule {
        if var rule = value as? FlintScheduleRule { rule.name = "Renamed"; return rule as! Rule }
        if var rule = value as? FlintLimitRule { rule.name = "Renamed"; return rule as! Rule }
        var rule = value as! FlintOpenLimitRule; rule.name = "Renamed"; return rule as! Rule
    }

    func testStaleConfirmationCannotReleaseNewRevisionPC10() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let ticket = try confirmation(arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: nil))
        var rename = old; rename.name = "New name"
        XCTAssertEqual(arbiter.upsert(rename), .applied)
        XCTAssertEqual(arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: ticket), .staleRevision)
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.name, "New name")
        XCTAssertTrue(h.cleared.isEmpty)
    }

    func testStrengthenArmsBeforeRetiringAndFailureRetainsOldPC12() throws {
        for fail in [true, false] {
            let h = try RuleHarness(); defer { h.clean() }
            let old = h.install(h.schedule(tier: .hardcore))
            var stronger = old; stronger.schedule.endHour = 18
            let arbiter = FlintRuleArbiter(environment: h.environment)
            let ticket = try confirmation(arbiter.upsert(stronger))
            if fail { h.center.refused = ["flint.rule.work.r2"] }
            h.center.onStart = { _ in
                XCTAssertNotNil(h.center.held[.init(old.activityName)], "old end must still be armed")
                XCTAssertEqual(h.load(FlintScheduleRule.self).first?.schedule.endHour, 17, "arm precedes persistence")
            }
            XCTAssertEqual(arbiter.upsert(stronger, confirmation: ticket), fail ? .unavailable : .applied)
            XCTAssertEqual(h.load(FlintScheduleRule.self).first?.schedule.endHour, fail ? 17 : 18)
            XCTAssertTrue(h.cleared.isEmpty)
            if fail { XCTAssertTrue(h.center.stops.isEmpty) }
            else { XCTAssertTrue(h.center.stops.flatMap { $0 }.contains(old.activityName)) }
        }
    }

    func testFutureOneShotHardcoreRemainsEditableAndNeverReplaysPC13() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var future = h.schedule(tier: .hardcore)
        future.oneShot = .init(start: h.now.addingTimeInterval(3600), end: h.now.addingTimeInterval(7200))
        let old = h.install(future)
        var draft = old; draft.breakLevel = .easy
        let arbiter = FlintRuleArbiter(environment: h.environment)
        XCTAssertEqual(arbiter.upsert(draft), .applied)
        h.advance(8 * 86400)
        XCTAssertNil(h.load(FlintScheduleRule.self).first?.occurrence(at: h.now))
    }

    func testHarderWaitSequencePersistsAcrossRenameAndHostRecreationPC18PC21() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 10, breakLevel: .harder,
            protection: .init(timeZoneID: "UTC")))
        for (index, expected) in [60.0, 300.0, 900.0, 900.0].enumerated() {
            let current = try XCTUnwrap(h.load(FlintLimitRule.self).first)
            var draft = current; draft.thresholdMinutes += 5
            let first = FlintRuleArbiter(environment: h.environment).upsert(draft)
            guard case let .waiting(ticket, remaining, required) = first else { return XCTFail("\(first)") }
            XCTAssertEqual(remaining, expected); XCTAssertEqual(required, expected)
            h.advance(expected)
            let recreated = FlintRuleArbiter(environment: h.environment)
            guard case .waiting(_, remaining: 0, required: _) = recreated.upsert(draft) else { return XCTFail("needs final confirmation") }
            XCTAssertEqual(h.load(FlintLimitRule.self).first?.thresholdMinutes, current.thresholdMinutes)
            XCTAssertEqual(recreated.upsert(draft, confirmation: ticket), .applied)
            let saved = try XCTUnwrap(h.load(FlintLimitRule.self).first)
            let cause = FlintRuleArbiter.causeID(saved, window: saved.occurrence(at: h.now))
            XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, index + 1)
            var rename = saved; rename.name += "x"
            XCTAssertEqual(recreated.upsert(rename), .applied)
            XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, index + 1)
        }
    }

    func testHarderCancelRestartsFullWaitAndDifferentScopeCannotReuseItPC20() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .harder))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        var draft = old; draft.schedule.endHour = 16
        _ = arbiter.upsert(draft); h.advance(59)
        XCTAssertTrue(arbiter.cancel(FlintScheduleRule.self, id: old.id))
        guard case .waiting(_, remaining: 60, required: 60) = arbiter.upsert(draft) else { return XCTFail("full wait must restart") }
        h.advance(60)
        draft.schedule.endHour = 15
        guard case .waiting(_, remaining: 60, required: 60) = arbiter.upsert(draft) else { return XCTFail("changed snapshot needs new wait") }
        let cause = FlintRuleArbiter.causeID(old, window: old.occurrence(at: h.now))
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 0)
    }

    func testQueuedDeleteAppliesInMonitorWithHostDeadAndDoesNotRestartPC11PC14() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        let controller = FlintSchedulesController(environment: h.environment)
        let ticket = try confirmation(controller.delete(old.id, revision: old.revision))
        guard case let .queued(end) = controller.delete(old.id, revision: old.revision, confirmation: ticket) else { return XCTFail() }
        h.now = end
        let monitor = FlintRuleMonitor(environment: h.environment)
        XCTAssertTrue(monitor.handle(.end, activity: old.activityName))
        XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty)
        let starts = h.center.starts.count
        _ = controller.reload(); _ = controller.reload()
        XCTAssertEqual(h.center.starts.count, starts)
        XCTAssertTrue(h.cleared.contains(old.storeName))
    }

    func testQueuedOpenOffPrecedesAdjacentDayInStartCallbackPC14PC16() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3, breakLevel: .hardcore,
            protection: .init(timeZoneID: "UTC")))
        let controller = FlintOpenLimitsController(environment: h.environment, selectionIssue: { _ in nil })
        let ticket = try confirmation(controller.setEnabled(old.id, false, revision: old.revision))
        guard case let .queued(end) = controller.setEnabled(old.id, false, revision: old.revision, confirmation: ticket) else { return XCTFail() }
        h.now = end
        _ = FlintRuleMonitor(environment: h.environment).handle(.start, activity: old.activityName)
        XCTAssertEqual(h.load(FlintOpenLimitRule.self).first?.enabled, false)
        XCTAssertTrue(h.appliedOpens.isEmpty, "old day must not re-shield after queued off")
        XCTAssertNil(h.load(FlintOpenLimitRule.self).first?.protection?.queuedChange)
    }

    func testQueueAppliesOnForegroundOnceAndFailedArmKeepsPending() throws {
        for refuse in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let old = h.install(h.schedule(tier: .hardcore))
            var weaker = old; weaker.schedule.endHour = 16
            let arbiter = FlintRuleArbiter(environment: h.environment)
            let ticket = try confirmation(arbiter.upsert(weaker))
            guard case let .queued(end) = arbiter.upsert(weaker, confirmation: ticket) else { return XCTFail() }
            h.now = end
            if refuse { h.center.refused = ["flint.rule.work.r3"] }
            _ = FlintSchedulesController(environment: h.environment).reload()
            let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
            XCTAssertEqual(saved.schedule.endHour, refuse ? 17 : 16)
            XCTAssertEqual(saved.protection?.queuedChange != nil, refuse)
            if !refuse {
                let starts = h.center.starts.count
                _ = FlintSchedulesController(environment: h.environment).reload()
                XCTAssertEqual(h.center.starts.count, starts, "successful queue cannot restart forever")
            }
        }
    }

    func testUnavailableLedgerBlocksHarderButEasyDoesNotNeedLedger() throws {
        for tier in [BreakLevel.easy, .harder] {
            let h = try RuleHarness(); defer { h.clean() }
            let old = h.install(h.schedule(tier: tier)); h.ledgerAvailable = false
            let arbiter = FlintRuleArbiter(environment: h.environment)
            let result = arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: nil)
            if tier == .easy {
                let ticket = try confirmation(result)
                XCTAssertEqual(arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: ticket), .applied)
            } else { XCTAssertEqual(result, .unavailable); XCTAssertTrue(h.cleared.isEmpty) }
        }
    }

    func testUnavailableSaveAndLockPreserveOldRuleAndResources() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        var draft = old; draft.schedule.endHour = 18
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let ticket = try confirmation(arbiter.upsert(draft))
        h.saveAllowed = false
        XCTAssertEqual(arbiter.upsert(draft, confirmation: ticket), .unavailable)
        XCTAssertEqual(h.load(FlintScheduleRule.self), [old]); XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertNotNil(h.center.held[.init(old.activityName)])
        var env = h.environment; env.lockDirectory = nil
        XCTAssertEqual(FlintRuleArbiter(environment: env).upsert(draft), .unavailable)
    }

    func testTierIncreaseRequiresExplicitConfirmationAtEveryEntryPC11() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        var raised = old; raised.breakLevel = .hardcore
        let controller = FlintSchedulesController(environment: h.environment)
        guard case let .confirmationRequired(ticket, raisesTier: true) = controller.upsert(raised) else { return XCTFail() }
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.breakLevel, .easy)
        let forged = FlintRuleConfirmation(revision: ticket.revision, fingerprint: ticket.fingerprint)
        guard case .confirmationRequired(_, raisesTier: true) = controller.upsert(raised, confirmation: forged) else { return XCTFail() }
        XCTAssertEqual(controller.upsert(raised, confirmation: ticket), .applied)
    }

    func testExplicitCreateCannotOverwriteDuplicateIDR31() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        var other = old; other.name = "Duplicate"
        XCTAssertEqual(FlintSchedulesController(environment: h.environment).upsert(other, creating: true), .staleRevision)
        XCTAssertEqual(h.load(FlintScheduleRule.self), [old])
    }

    func testConfirmationFromPreviousOccurrenceCannotQueueAgainstNextOccurrencePC10() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let ticket = try confirmation(arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: nil))
        h.advance(86400)
        XCTAssertEqual(arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: ticket), .staleRevision)
        XCTAssertNil(h.load(FlintScheduleRule.self).first?.protection?.queuedChange)
    }

    func testBusySharedTransitionBoundaryMakesNoRuleOrOSChange() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        var environment = h.environment
        environment.withTransitionBoundary = { _ in false }
        XCTAssertEqual(FlintRuleArbiter(environment: environment).upsert(old), .unavailable)
        XCTAssertEqual(h.load(FlintScheduleRule.self), [old])
        XCTAssertTrue(h.center.starts.isEmpty); XCTAssertTrue(h.center.stops.isEmpty); XCTAssertTrue(h.cleared.isEmpty)
    }

    func testStrengtheningExtensionRetainsQueuedOffButCannotApplyItAtOldEndPC11PC12PC14() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let off = try confirmation(arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: false,
            revision: old.revision, confirmation: nil))
        _ = arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: false, revision: old.revision, confirmation: off)
        var stronger = try XCTUnwrap(h.load(FlintScheduleRule.self).first); stronger.schedule.endHour = 18
        let extend = try confirmation(arbiter.upsert(stronger))
        XCTAssertEqual(arbiter.upsert(stronger, confirmation: extend), .applied)
        h.now = old.protection!.occurrence!.end
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.enabled, true)
        XCTAssertNotNil(h.load(FlintScheduleRule.self).first?.protection?.queuedChange)
        h.advance(3600)
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.enabled, false)
    }

    func testOlderEditorCannotCancelAnotherOperationsWaitPC20() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .harder))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        var a = old; a.schedule.endHour = 16
        let first = try confirmation(arbiter.upsert(a))
        var b = old; b.schedule.endHour = 15
        _ = arbiter.upsert(b)
        XCTAssertFalse(arbiter.cancel(FlintScheduleRule.self, id: old.id, confirmation: first))
        h.advance(60)
        guard case .waiting(_, remaining: 0, required: _) = arbiter.upsert(b) else { return XCTFail("other operation's wait must remain") }
    }

    func testRuleBatchWaitsLongestAndCommitsEachCauseExactlyOncePC18PC19() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var a = FlintOpenLimitRule(id: "a", name: "A", opensAllowed: 2, breakLevel: .harder,
            protection: .init(timeZoneID: "UTC"))
        var b = a; b.id = "b"; b.name = "B"
        let windowA = a.templateOccurrence(at: h.now)
        let windowB = b.templateOccurrence(at: h.now)
        a.protection?.occurrence = windowA
        b.protection?.occurrence = windowB
        XCTAssertTrue(FlintDesiredRules.save([a, b], key: .openLimits, to: h.defaults))
        let causeA = FlintRuleArbiter.causeID(a, window: a.occurrence(at: h.now))
        let causeB = FlintRuleArbiter.causeID(b, window: b.occurrence(at: h.now))
        _ = h.ledger.transaction { $0.harder[causeB] = .init(committedCount: 2) }
        let controller = FlintOpenLimitsController(environment: h.environment)
        guard case let .waiting(ticket, remaining: 900, required: 900) = controller.deleteBatch([a, b]) else { return XCTFail() }
        h.advance(900)
        XCTAssertEqual(controller.deleteBatch([a, b], confirmation: ticket), .applied)
        XCTAssertEqual(try h.ledger.read().get().harder[causeA]?.committedCount, 1)
        XCTAssertEqual(try h.ledger.read().get().harder[causeB]?.committedCount, 3)
        XCTAssertEqual(controller.deleteBatch([a, b], confirmation: ticket), .staleRevision)
        XCTAssertEqual(try h.ledger.read().get().harder[causeB]?.committedCount, 3)
    }

    func testTargetStrengthenRemovalMixedModeAndCategoryEditsUseExactSnapshotsPC11PC12() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let a: ApplicationToken = try fictionalRuleToken("app-A")
        let b: ApplicationToken = try fictionalRuleToken("app-B")
        let category: ActivityCategoryToken = try fictionalRuleToken("category")
        var original = h.schedule(tier: .hardcore)
        original.allowListMode = false; original.selection.applicationTokens = [a]
        let old = h.install(original)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        var grow = old; grow.selection.applicationTokens.insert(b)
        let growTicket = try confirmation(arbiter.upsert(grow))
        XCTAssertEqual(arbiter.upsert(grow, confirmation: growTicket), .applied)
        let current = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        var remove = current; remove.selection.applicationTokens = [b]
        guard case let .deferRequired(ticket, _) = arbiter.upsert(remove) else { return XCTFail("removing a denied target weakens") }
        var different = remove; different.selection.applicationTokens = [a]
        XCTAssertEqual(arbiter.upsert(different, confirmation: ticket), .staleRevision, "confirmation cannot authorize another exact target set")
        var mixed = remove; mixed.schedule.endHour = 18
        guard case .deferRequired = arbiter.upsert(mixed) else { return XCTFail("extension plus target removal is mixed weakening") }
        var mode = current; mode.allowListMode = true
        guard case .deferRequired = arbiter.upsert(mode) else { return XCTFail("mode flip needs weakening gate") }
        var opaque = current; opaque.selection.categoryTokens = [category]
        guard case .deferRequired = arbiter.upsert(opaque) else { return XCTFail("opaque category change needs weakening gate") }
    }

    private func confirmation(_ result: FlintRuleMutationResult) throws -> FlintRuleConfirmation {
        switch result {
        case let .confirmationRequired(ticket, _), let .deferRequired(ticket, _), let .waiting(ticket, _, _): return ticket
        default: XCTFail("Expected confirmation, got \(result)"); throw TestFailure.unexpected
        }
    }
    private enum TestFailure: Error { case unexpected }
}

func fictionalRuleToken<Token: Decodable>(_ sentinel: String) throws -> Token {
    let data = try JSONSerialization.data(withJSONObject: ["data": Data(sentinel.utf8).base64EncodedString()])
    return try JSONDecoder().decode(Token.self, from: data)
}

final class RuleHarness {
    let suite = "flint.rule-gate.\(UUID().uuidString)"
    let defaults: UserDefaults
    let directory: URL
    let center = FakeActivityCenter()
    var now = ISO8601DateFormatter().date(from: "2026-10-28T12:00:00Z")!
    var monotonic: TimeInterval = 1_000
    var boot = "test-boot"
    var marker: Date?
    var ledgerAvailable = true
    var saveAllowed = true
    var cleared: [String] = []
    var appliedSchedules: [FlintScheduleRule] = []
    var appliedLimits: [FlintLimitRule] = []
    var appliedOpens: [FlintOpenLimitRule] = []
    var reports: [String: FlintArmingHealth.DomainReport] = [:]
    init() throws {
        defaults = UserDefaults(suiteName: suite)!
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    func clean() { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: directory) }
    var clock: FlintClock {
        .init(now: { self.now }, monotonic: { self.monotonic }, bootSessionID: { self.boot }, calendar: {
            var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c
        })
    }
    var ledger: FlintProtectionLedger {
        .init(directory: directory.appendingPathComponent("ledger"), clock: clock,
            initializedMarker: { self.marker }, setInitializedMarker: { self.marker = $0 },
            migrate: { state, now in state.importLegacyPass(.absent, at: now) })
    }
    var environment: FlintRuleReloadEnvironment {
        .init(defaults: defaults, center: center, clearStore: { self.cleared.append($0) },
            applyOpenShield: { self.appliedOpens.append($0) }, record: { self.reports[$0] = $1 },
            loadHealth: { .init(domains: self.reports) }, clock: clock,
            ledger: { self.ledgerAvailable ? self.ledger : nil }, lockDirectory: directory.appendingPathComponent("rules"),
            acceptSave: { self.saveAllowed }, validateSelection: { _, _ in nil },
            applyRuleShield: { self.appliedSchedules.append($0) }, applyLimitShield: { self.appliedLimits.append($0) },
            timeLimitIsShielded: { _ in false })
    }
    func advance(_ seconds: TimeInterval) { now.addTimeInterval(seconds); monotonic += seconds }
    func schedule(tier: BreakLevel) -> FlintScheduleRule {
        .init(id: "work", name: "Work", schedule: .init(startHour: 9, endHour: 17), breakLevel: tier,
            allowListMode: true, protection: .init(timeZoneID: "UTC"))
    }
    @discardableResult func install<Rule: FlintGatedRule>(_ initial: Rule) -> Rule {
        var rule = initial
        let occurrence = rule.templateOccurrence(at: now)
        rule.protection?.occurrence = occurrence
        XCTAssertTrue(FlintDesiredRules.save([rule], key: Rule.domain, to: defaults))
        if rule.enabled { center.held[.init(rule.activityName)] = rule.registration() }
        return rule
    }
    func load<Rule: FlintGatedRule>(_ type: Rule.Type) -> [Rule] {
        FlintDesiredRules.load(type, key: Rule.domain, from: defaults, at: now) ?? []
    }
}
