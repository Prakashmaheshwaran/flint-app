import XCTest
@testable import FlintCore

private final class CreationWebWriter: FlintWebRestrictionWriting {
    var stores: [String: FlintWebRestrictions] = [:]
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) { stores[storeName] = config }
    func clear(store storeName: String) { stores.removeValue(forKey: storeName) }
}

private final class WebCreationFixture {
    let h: RuleHarness
    let writer = CreationWebWriter()
    let children: FlintWebRestrictionChildren
    let config = FlintWebRestrictions(applyDuringSchedules: true, blockedDomains: ["blocked.example"], denyExplicitMedia: true)
    var notes: [String] = []
    init() throws {
        h = try RuleHarness()
        children = .init(defaults: h.defaults, recoveryDirectory: h.recoveryDirectory)
        XCTAssertTrue(config.save(to: h.defaults))
    }
    var environment: FlintRuleReloadEnvironment {
        var env = h.environment
        let clear = env.clearStore
        env.clearStore = { [self] in clear($0); writer.clear(store: $0) }
        env.applyRuleShield = { [self] rule, clock in
            FlintRuleShield.applyLocked(rule, clock: clock, applyShield: {}, readWeb: {
                FlintWebRestrictions.loadForEnforcement(from: self.h.defaults, existingWebStores: { false })
                    .map(FlintWebRestrictionRead.available) ?? .unavailable
            }, webOwners: { self.h.load(FlintScheduleRule.self) }, webChildren: children, webWriter: writer,
            recordWeb: { self.notes.append($0) })
            return true
        }
        return env
    }
    func create(_ draft: FlintScheduleRule) -> FlintRuleMutationResult {
        let arbiter = FlintRuleArbiter(environment: environment)
        let result = arbiter.upsert(draft, creating: true)
        if case let .confirmationRequired(ticket, _) = result {
            return arbiter.upsert(draft, confirmation: ticket, creating: true)
        }
        return result
    }
}

final class FlintWebRestrictionCreationTests: XCTestCase {
    func testInteractiveSleepBatchAndReenableFreezeBeforeFirstArmAndNativeApply() throws {
        for path in ["interactive", "sleep.batch", "reenable"] {
            let f = try WebCreationFixture(); defer { f.h.clean() }
            var draft = f.h.schedule(tier: .hardcore)
            if path == "sleep.batch" { draft.id = "sleep.night" }
            if path == "reenable" {
                draft.enabled = false; draft = f.h.install(draft); draft.enabled = true
            }
            var armsWithFrozenChild = 0
            f.h.center.onStart = { name in
                let id = FlintRuleActivity.ruleID(name, prefix: "flint.rule.")
                guard case let .available(envelope) = f.children.read(),
                      envelope.children.contains(where: { $0.cause.ownerID == id && $0.config == f.config }) else {
                    return XCTFail("\(path): child must be saved before the first native arm")
                }
                armsWithFrozenChild += 1
            }
            let arbiter = FlintRuleArbiter(environment: f.environment)
            let result: FlintRuleMutationResult
            if path == "sleep.batch" {
                var other = f.h.schedule(tier: .easy); other.id = "batch.sibling"
                let mutations = [draft, other].map {
                    FlintRuleArbiter.Mutation(id: $0.id, proposed: $0, action: .edit, expectedRevision: $0.revision, creating: true)
                }
                guard case let .confirmationRequired(ticket, _) = arbiter.mutate(mutations, confirmation: nil) else {
                    return XCTFail("Expected Sleep batch confirmation")
                }
                result = arbiter.mutate(mutations, confirmation: ticket)
            } else if path == "reenable" {
                guard case let .confirmationRequired(ticket, _) = arbiter.setEnabled(FlintScheduleRule.self,
                    id: draft.id, enabled: true, revision: draft.revision, confirmation: nil) else {
                    return XCTFail("Expected re-enable confirmation")
                }
                result = arbiter.setEnabled(FlintScheduleRule.self, id: draft.id, enabled: true,
                    revision: draft.revision, confirmation: ticket)
            } else { result = f.create(draft) }
            XCTAssertEqual(result, .applied, path)
            let saved = f.h.load(FlintScheduleRule.self)
            XCTAssertEqual(saved.count, path == "sleep.batch" ? 2 : 1)
            XCTAssertEqual(armsWithFrozenChild, saved.count)
            for rule in saved { XCTAssertEqual(f.writer.stores[rule.storeName], f.config, path) }
            XCTAssertTrue(f.notes.isEmpty, path)
            XCTAssertTrue(FlintWebRestrictions.default.save(to: f.h.defaults))
            let health = FlintArmingHealth(domains: [
                "session": .init(enabled: 0, attempted: 0, armed: 0, failures: []),
                "schedules": .init(enabled: saved.count, attempted: saved.count, armed: saved.count,
                    failures: [], observedActivities: saved.map(\.activityName))])
            let report = FlintWebRestrictionHostReconciler.reconcile(snapshot: .init(session: nil, rules: saved),
                config: .default, health: health,
                lifecycle: .init(writer: f.writer, clock: f.h.clock, children: f.children), now: f.h.now, clock: f.h.clock)
            XCTAssertTrue(report.failures.isEmpty, path)
            for rule in saved { XCTAssertEqual(f.writer.stores[rule.storeName], f.config, "\(path): host retains first-apply snapshot") }
        }
    }

    func testQueuedSuccessorAndPassApplyQueuedCaptureOnlyAcceptedOccurrenceThenRolloverUsesFuturePolicy() throws {
        for resumeThroughPass in [false, true] {
            let f = try WebCreationFixture(); defer { f.h.clean() }
            XCTAssertEqual(f.create(f.h.schedule(tier: .hardcore)), .applied)
            let old = try XCTUnwrap(f.h.load(FlintScheduleRule.self).first)
            let frozen = f.children.read()
            XCTAssertTrue(FlintWebRestrictions.default.save(to: f.h.defaults))
            var later = old; later.schedule.startHour = 10
            let arbiter = FlintRuleArbiter(environment: f.environment)
            guard case let .deferRequired(ticket, end) = arbiter.upsert(later) else { return XCTFail("Expected bounded queue") }
            XCTAssertEqual(arbiter.upsert(later, confirmation: ticket), .queued(until: end))
            XCTAssertEqual(f.children.read(), frozen, "Tentative future preflight must not freeze its policy early")
            XCTAssertEqual(f.writer.stores[old.storeName], f.config)
            f.h.advance(5 * 3600) // trusted old 17:00 end
            if resumeThroughPass {
                try FlintEmergencyPassRuleEffects.resume(state: f.h.ledger.read().get(), at: .init(clock: f.h.clock),
                    environment: f.environment, web: .init(writer: f.writer, clock: f.h.clock, children: f.children),
                    existingWebStores: { false })
            } else { XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self)) }
            XCTAssertNil(f.h.load(FlintScheduleRule.self).first?.protection?.queuedChange)
            XCTAssertTrue(f.writer.stores.isEmpty)
            f.h.advance(17 * 3600) // the next accepted 10:00 occurrence
            _ = FlintSchedulesController(environment: f.environment).reload()
            let next = try XCTUnwrap(f.h.load(FlintScheduleRule.self).first)
            let window = try XCTUnwrap(next.occurrence(at: f.h.now, clock: f.h.clock))
            XCTAssertEqual(f.children.frozen(.rule(next, window: window), fallback: nil), .default)
            XCTAssertTrue(f.writer.stores.isEmpty, "Future opt-out applies to the next occurrence")
            XCTAssertTrue(f.notes.isEmpty)
        }
    }

    func testUnavailableChildDefersOnlySafariAndNeverBypassesOrBlocksOwnerEndGates() throws {
        for tier: BreakLevel in [.easy, .hardcore] {
            for deleting in [false, true] {
                let f = try WebCreationFixture(); defer { f.h.clean() }
                XCTAssertEqual(f.create(f.h.schedule(tier: tier)), .applied)
                let old = try XCTUnwrap(f.h.load(FlintScheduleRule.self).first)
                var root = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(f.h.defaults.data(forKey: FlintWebRestrictions.storageKey))) as? [String: Any])
                root[FlintWebRestrictionChildren.schemaKey] = 999
                f.h.defaults.set(try JSONSerialization.data(withJSONObject: root), forKey: FlintWebRestrictions.storageKey)
                let starts = f.h.center.starts.count
                var new = f.h.schedule(tier: .easy); new.id = "new-owner"
                XCTAssertEqual(f.create(new), .applied)
                XCTAssertEqual(f.h.center.starts.count, starts + 1, "Optional Safari storage cannot block app protection")
                XCTAssertNil(f.writer.stores[new.storeName])
                XCTAssertFalse(f.notes.isEmpty)
                XCTAssertEqual(f.writer.stores[old.storeName], f.config)
                let arbiter = FlintRuleArbiter(environment: f.environment)
                var result = deleting
                    ? arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: nil)
                    : arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: false, revision: old.revision, confirmation: nil)
                if tier == .easy {
                    if case let .confirmationRequired(ticket, _) = result {
                        result = deleting
                            ? arbiter.delete(FlintScheduleRule.self, id: old.id, revision: old.revision, confirmation: ticket)
                            : arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: false, revision: old.revision, confirmation: ticket)
                    }
                    XCTAssertEqual(result, .applied)
                    XCTAssertTrue(f.writer.stores.isEmpty)
                } else {
                    guard case .deferRequired = result else { return XCTFail("Unknown web state cannot bypass Hardcore's end gate") }
                    XCTAssertEqual(f.writer.stores[old.storeName], f.config)
                }
                XCTAssertEqual(f.children.read(), .unavailable, "Ending a cause is not a repair of corrupt child intent")
            }
        }
    }
}
