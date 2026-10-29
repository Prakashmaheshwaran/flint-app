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
            if fail {
                var refused = old; refused.protection?.activityRevision = 2
                h.center.refused = [refused.activityName]
            }
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

    func testExhaustedTimeWeakeningQueuesForNextDayWithoutChangingCurrentOrSpendingWait() throws {
        for tier in [BreakLevel.easy, .harder] {
            let h = try RuleHarness(); defer { h.clean() }
            h.timeLimitExhausted = true
            let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                breakLevel: tier, protection: .init(timeZoneID: "UTC")))
            var draft = old; draft.thresholdMinutes = 60
            var ledgerTouches = 0
            var env = h.environment; env.ledger = { ledgerTouches += 1; return h.ledger }
            let arbiter = FlintRuleArbiter(environment: env)
            guard case let .nextOccurrenceRequired(ticket, end) = arbiter.upsert(draft) else { return XCTFail() }
            XCTAssertEqual(arbiter.upsert(draft, confirmation: ticket), .queued(until: end))
            let retained = try XCTUnwrap(h.load(FlintLimitRule.self).first)
            XCTAssertEqual(retained.thresholdMinutes, 30); XCTAssertEqual(retained.activityName, old.activityName)
            XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.appliedLimits.isEmpty)
            XCTAssertEqual(h.center.activities.map(\.rawValue), [old.activityName], "future preflight must leave only the effective registration")
            XCTAssertEqual(ledgerTouches, 0, "future-only edits spend no current-occurrence wait")
            h.now = end
            _ = FlintRuleMonitor(environment: env).handle(.start, activity: old.activityName)
            let next = try XCTUnwrap(h.load(FlintLimitRule.self).first)
            XCTAssertEqual(next.thresholdMinutes, 60); XCTAssertNil(next.protection?.queuedChange)
            XCTAssertEqual(h.cleared, [old.storeName], "yesterday's exhausted shield cannot exhaust the new day")
            XCTAssertTrue(h.appliedLimits.isEmpty)
            let starts = h.center.starts.count
            XCTAssertTrue(arbiter.applyQueued(FlintLimitRule.self))
            XCTAssertEqual(h.center.starts.count, starts, "applied queue never restarts")
        }
    }

    func testExhaustedTimeStrengthenAndOffKeepOrdinaryTierRights() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.timeLimitExhausted = true
        let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            breakLevel: .easy, protection: .init(timeZoneID: "UTC")))
        var draft = old; draft.thresholdMinutes = 20
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .confirmationRequired(ticket, _) = arbiter.upsert(draft) else { return XCTFail() }
        XCTAssertEqual(arbiter.upsert(draft, confirmation: ticket), .applied)
        XCTAssertEqual(h.appliedLimits.count, 1); XCTAssertTrue(h.cleared.isEmpty)
        let saved = try XCTUnwrap(h.load(FlintLimitRule.self).first)
        guard case let .confirmationRequired(off, _) = arbiter.setEnabled(FlintLimitRule.self,
            id: saved.id, enabled: false, revision: saved.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(arbiter.setEnabled(FlintLimitRule.self, id: saved.id, enabled: false,
            revision: saved.revision, confirmation: off), .applied)
        XCTAssertEqual(h.cleared, [saved.storeName])
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

    func testCosmeticRevisionRefreshesConfirmationWithoutRestartingPendingWaitPC10PC18PC21() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            breakLevel: .harder, protection: .init(timeZoneID: "UTC")))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        var draft = old; draft.thresholdMinutes = 60
        guard case let .waiting(oldTicket, _, _) = arbiter.upsert(draft) else { return XCTFail() }
        h.advance(30)
        var rename = old; rename.name = "Renamed"
        XCTAssertEqual(arbiter.upsert(rename), .applied)
        XCTAssertEqual(arbiter.upsert(draft, confirmation: oldTicket), .staleRevision)
        var refreshed = try XCTUnwrap(h.load(FlintLimitRule.self).first); refreshed.thresholdMinutes = 60
        guard case let .waiting(ticket, remaining, required) = arbiter.upsert(refreshed) else { return XCTFail() }
        XCTAssertEqual(remaining, 30); XCTAssertEqual(required, 60)
        XCTAssertNotEqual(ticket.fingerprint, oldTicket.fingerprint)
        h.advance(30)
        guard case .waiting(_, remaining: 0, required: 60) = arbiter.upsert(refreshed) else { return XCTFail() }
        XCTAssertEqual(arbiter.upsert(refreshed, confirmation: ticket), .applied)
        let cause = FlintRuleArbiter.causeID(old, window: old.occurrence(at: h.now))
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 1)
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
        guard case let .queued(end) = controller.delete(old.id, revision: old.revision, confirmation: ticket, origin: .interactive) else { return XCTFail() }
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
        guard case let .queued(end) = controller.setEnabled(old.id, false, revision: old.revision, confirmation: ticket, origin: .interactive) else { return XCTFail() }
        h.now = end
        _ = FlintRuleMonitor(environment: h.environment).handle(.start, activity: old.activityName)
        XCTAssertEqual(h.load(FlintOpenLimitRule.self).first?.enabled, false)
        XCTAssertTrue(h.appliedOpens.isEmpty, "old day must not re-shield after queued off")
        XCTAssertNil(h.load(FlintOpenLimitRule.self).first?.protection?.queuedChange)
    }

    func testQueuedZoneChangeUsesConfirmedFutureZoneWithoutRestartPC11PC14PC15PC16() throws {
        try queuedZoneChange(FlintScheduleRule(id: "schedule", name: "Schedule",
            schedule: .init(startHour: 9, endHour: 17), breakLevel: .hardcore,
            allowListMode: true, protection: .init(timeZoneID: "UTC")))
        try queuedZoneChange(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        try queuedZoneChange(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
    }

    private func queuedZoneChange<Rule: FlintGatedRule>(_ initial: Rule) throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(initial)
        var future = old; future.protection?.timeZoneID = "America/Los_Angeles"
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let ticket = try confirmation(arbiter.upsert(future))
        guard case let .queued(end) = arbiter.upsert(future, confirmation: ticket) else { return XCTFail() }
        let queued = try XCTUnwrap(h.load(Rule.self).first)
        XCTAssertEqual(queued.protection?.timeZoneID, "UTC", "current occurrence keeps its saved zone")
        XCTAssertEqual(queued.activityName, old.activityName)
        h.now = end
        let monitor = FlintRuleMonitor(environment: h.environment)
        _ = monitor.handle(.end, activity: old.activityName)
        let applied = try XCTUnwrap(h.load(Rule.self).first)
        XCTAssertEqual(applied.protection?.timeZoneID, "America/Los_Angeles")
        XCTAssertNil(applied.protection?.queuedChange)
        XCTAssertNotEqual(applied.activityName, old.activityName)
        XCTAssertEqual(applied.protection?.incarnation, old.protection?.incarnation)
        let starts = h.center.starts.count
        XCTAssertTrue(arbiter.applyQueued(Rule.self))
        _ = monitor.handle(.end, activity: old.activityName)
        XCTAssertEqual(h.center.starts.count, starts, "applied future zone cannot loop registration")
    }

    func testQueueBoundaryCannotPrecedeItsFrozenCommitmentEndPC11PC14() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.install(h.schedule(tier: .hardcore))
        let frozen = try XCTUnwrap(rule.protection?.occurrence)
        var metadata = try XCTUnwrap(rule.protection)
        metadata.queuedChange = .init(action: .delete, baseRevision: rule.revision,
            window: .init(start: frozen.start, end: h.now.addingTimeInterval(3600)),
            snapshot: nil, fingerprint: "delete:\(rule.id)")
        rule.protection = metadata
        XCTAssertFalse(FlintDesiredRules.save([rule], key: .schedules, to: h.defaults))
        let corrupt = try JSONEncoder().encode([rule])
        h.defaults.set(corrupt, forKey: FlintRuleStoreKey.schedules.rawValue)
        h.advance(3600)
        _ = FlintRuleMonitor(environment: h.environment).handle(.end, activity: rule.activityName)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), corrupt)
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
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
            if refuse {
                var refused = old; refused.protection?.activityRevision = 3
                h.center.refused = [refused.activityName]
            }
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

    func testUnavailableLedgerCannotSaveActiveHardcoreWeakeningButInactiveTemplateStaysEditable() throws {
        for active in [true, false] {
            let h = try RuleHarness(); defer { h.clean() }
            if !active { h.now = h.now.addingTimeInterval(8 * 3600) }
            let old = h.install(h.schedule(tier: .hardcore))
            let arbiter = FlintRuleArbiter(environment: h.environment)
            var draft = old; draft.schedule.endHour = 16
            let ticket = active ? try confirmation(arbiter.upsert(draft)) : nil
            h.ledgerAvailable = false
            XCTAssertEqual(arbiter.upsert(draft, confirmation: ticket), active ? .unavailable : .applied)
            let saved = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
            if active {
                XCTAssertEqual(saved, old)
                XCTAssertNil(saved.protection?.queuedChange)
                XCTAssertTrue(h.cleared.isEmpty)
            } else { XCTAssertEqual(saved.schedule.endHour, 16) }
        }
    }

    func testTierIncreaseRequiresExplicitConfirmationAtEveryEntryPC11() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        var raised = old; raised.breakLevel = .hardcore
        let controller = FlintSchedulesController(environment: h.environment)
        guard case let .confirmationRequired(ticket, raisesTier: true) = controller.upsert(raised) else { return XCTFail() }
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.breakLevel, .easy)
        let forged = FlintRuleConfirmation(revision: ticket.revision, fingerprint: ticket.fingerprint)
        guard case .confirmationRequired(_, raisesTier: true) = controller.upsert(raised, confirmation: forged, origin: .interactive) else { return XCTFail() }
        XCTAssertEqual(controller.upsert(raised, confirmation: ticket, origin: .interactive), .applied)
    }

    func testEnablingSavedHardcoreRequiresFiniteScopeConfirmationPC11PC13() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var initial = h.schedule(tier: .hardcore); initial.enabled = false
        let old = h.install(initial)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .confirmationRequired(ticket, raisesTier) = arbiter.setEnabled(FlintScheduleRule.self,
            id: old.id, enabled: true, revision: old.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertFalse(raisesTier)
        let preview = try XCTUnwrap(ticket.preview.first)
        XCTAssertEqual(preview.name, old.name); XCTAssertEqual(preview.tier, .hardcore)
        XCTAssertEqual(preview.window?.end, h.now.addingTimeInterval(5 * 3600))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.enabled, false)
        XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: true,
            revision: old.revision, confirmation: ticket), .applied)
    }

    func testExplicitCreateCannotOverwriteDuplicateIDR31() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        var other = old; other.name = "Duplicate"
        XCTAssertEqual(FlintSchedulesController(environment: h.environment).upsert(other, creating: true), .staleRevision)
        XCTAssertEqual(h.load(FlintScheduleRule.self), [old])
    }

    func testCreationPreviewSurvivesRegeneratedDraftButRejectsAnotherOccurrencePC10() throws {
        for nextDay in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let draft = h.schedule(tier: .hardcore)
            let arbiter = FlintRuleArbiter(environment: h.environment)
            guard case let .confirmationRequired(ticket, _) = arbiter.upsert(draft, creating: true) else { return XCTFail() }
            var rebuilt = draft; rebuilt.protection = .init(timeZoneID: "UTC")
            XCTAssertNotEqual(rebuilt.protection?.incarnation, draft.protection?.incarnation)
            if nextDay { h.advance(86400) }
            XCTAssertEqual(arbiter.upsert(rebuilt, confirmation: ticket, creating: true), nextDay ? .staleRevision : .applied)
            if nextDay { XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty) }
            else {
                XCTAssertNotEqual(h.load(FlintScheduleRule.self).first?.protection?.incarnation,
                    rebuilt.protection?.incarnation, "commit authority mints the incarnation")
            }
        }
    }

    func testSameIDRecreationMintsNewIncarnationAndRejectsOldConfirmationsAndCallbacksPC10PC64PC66() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let ticket = try confirmation(arbiter.delete(FlintScheduleRule.self, id: old.id,
            revision: old.revision, confirmation: nil))
        XCTAssertEqual(arbiter.delete(FlintScheduleRule.self, id: old.id,
            revision: old.revision, confirmation: ticket), .applied)
        XCTAssertEqual(arbiter.upsert(old, creating: true), .applied, "even a reused draft must receive a fresh incarnation")
        let recreated = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNotEqual(recreated.protection?.incarnation, old.protection?.incarnation)
        XCTAssertEqual(recreated.protection?.revision, 1)
        XCTAssertNotEqual(recreated.activityName, old.activityName)
        XCTAssertEqual(FlintRuleActivity.ruleID(recreated.activityName, prefix: "flint.rule."), old.id)
        XCTAssertEqual(arbiter.delete(FlintScheduleRule.self, id: old.id,
            revision: recreated.revision, confirmation: ticket), .staleRevision)
        let cleared = h.cleared.count
        _ = FlintRuleMonitor(environment: h.environment).handle(.end, activity: old.activityName)
        XCTAssertEqual(h.cleared.count, cleared)
        XCTAssertNotNil(h.center.held[.init(recreated.activityName)])
    }

    func testSameIDRecreationStartsNewHarderCauseButOffOnPreservesIncarnationAndCountPC18() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 10,
            breakLevel: .harder, protection: .init(timeZoneID: "UTC")))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .waiting(off, _, _) = arbiter.setEnabled(FlintLimitRule.self, id: old.id,
            enabled: false, revision: old.revision, confirmation: nil) else { return XCTFail() }
        h.advance(60)
        XCTAssertEqual(arbiter.setEnabled(FlintLimitRule.self, id: old.id, enabled: false,
            revision: old.revision, confirmation: off), .applied)
        let disabled = try XCTUnwrap(h.load(FlintLimitRule.self).first)
        XCTAssertEqual(arbiter.setEnabled(FlintLimitRule.self, id: old.id, enabled: true,
            revision: disabled.revision, confirmation: nil), .applied)
        let enabled = try XCTUnwrap(h.load(FlintLimitRule.self).first)
        XCTAssertEqual(enabled.protection?.incarnation, old.protection?.incarnation)
        guard case let .waiting(deletion, remaining, _) = arbiter.delete(FlintLimitRule.self,
            id: old.id, revision: enabled.revision, confirmation: nil) else { return XCTFail() }
        XCTAssertEqual(remaining, 300, "off/on cannot reset the current cause's committed count")
        h.advance(300)
        XCTAssertEqual(arbiter.delete(FlintLimitRule.self, id: old.id,
            revision: enabled.revision, confirmation: deletion), .applied)
        XCTAssertEqual(arbiter.upsert(old, creating: true), .applied)
        let recreated = try XCTUnwrap(h.load(FlintLimitRule.self).first)
        XCTAssertNotEqual(recreated.protection?.incarnation, old.protection?.incarnation)
        var draft = recreated; draft.thresholdMinutes += 5
        guard case .waiting(_, remaining: 60, required: 60) = arbiter.upsert(draft) else { return XCTFail("new cause starts at its first wait") }
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

    func testStrengtheningSupersedesQueuedOffAndCannotFireItLaterPC11PC12PC14() throws {
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
        XCTAssertNil(h.load(FlintScheduleRule.self).first?.protection?.queuedChange)
        h.advance(3600)
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.load(FlintScheduleRule.self).first?.enabled, true)
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
        XCTAssertEqual(controller.deleteBatch([a, b], confirmation: ticket, origin: .interactive), .applied)
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
    var timeLimitExhausted = false
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
            timeLimitIsShielded: { _, _ in self.timeLimitExhausted })
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
