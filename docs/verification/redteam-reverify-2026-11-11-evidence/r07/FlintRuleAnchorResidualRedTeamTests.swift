import XCTest
import FamilyControls
@testable import FlintCore

/// Red-team re-verification of residual R-07 (P0, iOS) on origin/main b888084, after PR #251.
///
/// R-07: an active Hardcore/Harder occurrence, or a queued weakening, must not be released or
/// become due early because rule activity follows a jumped wall clock while the trusted time
/// anchor is absent, lost or unusable. ADR-012 §9 requires corrupt/unavailable time state to be
/// treated as unavailable and the retained finite occurrence to keep governing until bounded
/// elapsed (monotonic) time runs out; §3/PC-11/PC-14 require the release to wait for the
/// occurrence's trusted end; §12/PC-70 bound any legacy import to its imported occurrence.
///
/// Every case drives the real persisted record -> `FlintDesiredRules.load` -> `observe` ->
/// `FlintRuleArbiter` / `freezeOccurrences` path with an injected clock. No sleeps, no real
/// clock changes; the harness pins the calendar to UTC so results do not depend on the host zone.
final class FlintRuleAnchorResidualRedTeamTests: XCTestCase {

    // MARK: - Clock and persistence helpers

    private func instant(_ iso: String) throws -> Date {
        try XCTUnwrap(ISO8601DateFormatter().date(from: iso), "unparseable fixture instant \(iso)")
    }

    private func reference(_ iso: String) throws -> Double {
        try instant(iso).timeIntervalSinceReferenceDate
    }

    /// Rewrites one persisted record's `protection` object, leaving every other stored byte
    /// shape intact. This is the same seam the merged R-07 tests use for a "selectively lost"
    /// optional field; a record on disk is untrusted state either way.
    private func rewrite(_ h: RuleHarness, key: FlintRuleStoreKey = .schedules, id: String,
                         _ body: (inout [String: Any]) throws -> Void) throws {
        let stored = try XCTUnwrap(h.defaults.data(forKey: key.rawValue), "no stored rules to rewrite")
        var objects = try XCTUnwrap(JSONSerialization.jsonObject(with: stored) as? [[String: Any]])
        let index = try XCTUnwrap(objects.firstIndex { ($0["id"] as? String) == id }, "no stored rule \(id)")
        var protection = try XCTUnwrap(objects[index]["protection"] as? [String: Any], "no protection for \(id)")
        try body(&protection)
        objects[index]["protection"] = protection
        h.defaults.set(try JSONSerialization.data(withJSONObject: objects), forKey: key.rawValue)
    }

    private func removeAnchor(_ h: RuleHarness, id: String) throws {
        try rewrite(h, id: id) { $0.removeValue(forKey: "timeAnchor") }
    }

    private func removeOccurrence(_ h: RuleHarness, id: String) throws {
        try rewrite(h, id: id) { $0.removeValue(forKey: "occurrence") }
    }

    /// Changes only the anchor's saved timeline. Wall, monotonic, boot identity and zone keep
    /// describing the same observation, so every self-consistency check the clock performs still
    /// passes; only the value is implausible.
    private func setAnchorTimeline(_ h: RuleHarness, id: String, to iso: String) throws {
        let value = try reference(iso)
        try rewrite(h, id: id) { protection in
            var anchor = protection["timeAnchor"] as? [String: Any] ?? [:]
            anchor["timeline"] = value
            protection["timeAnchor"] = anchor
        }
    }

    private func seedAnchorlessFixture(_ h: RuleHarness) throws -> FlintScheduleRule {
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        h.defaults.set(try JSONSerialization.data(withJSONObject: [try RuleFixture.object("adr012-schedule-without-anchor")]),
            forKey: FlintRuleStoreKey.schedules.rawValue)
        let seeded = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        h.center.held[.init(seeded.activityName)] = seeded.registration()
        return seeded
    }

    // MARK: - (0) Original repro, unchanged: checked-in anchorless fixture

    private enum AnchorlessOperation { case turnOff, delete }

    func testRedTeamR07AnchorlessFixtureDefersOffWithoutElapsedTime() throws { try exerciseFixtureAnchorless(.turnOff) }
    func testRedTeamR07AnchorlessFixtureDefersDeleteWithoutElapsedTime() throws { try exerciseFixtureAnchorless(.delete) }

    private func exerciseFixtureAnchorless(_ operation: AnchorlessOperation) throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let seeded = try seedAnchorlessFixture(h) // Hardcore 09:00-17:00 UTC, no anchor
        XCTAssertEqual(seeded.protection?.timeAnchor?.isValid, false, "an absent anchor is never trusted time")
        h.now = try instant("2026-10-28T18:00:00Z") // wall jumped past the end, zero elapsed
        let arbiter = FlintRuleArbiter(environment: h.environment)
        func mutate(_ confirmation: FlintRuleConfirmation?) -> FlintRuleMutationResult {
            operation == .delete
                ? arbiter.delete(FlintScheduleRule.self, id: seeded.id, revision: seeded.revision,
                    confirmation: confirmation)
                : arbiter.setEnabled(FlintScheduleRule.self, id: seeded.id, enabled: false,
                    revision: seeded.revision, confirmation: confirmation)
        }
        guard case let .deferRequired(ticket, _) = mutate(nil) else {
            return XCTFail("R-07: an anchorless active Hardcore occurrence must defer, not adopt wall time")
        }
        if case .queued = mutate(ticket) {} else {
            XCTFail("R-07: the confirmed weakening must queue for the retained occurrence end")
        }
        XCTAssertTrue(h.cleared.isEmpty, "R-07: no release without trusted elapsed time")
        XCTAssertTrue(h.center.stops.isEmpty)
        XCTAssertNotNil(h.center.held[.init(seeded.activityName)])
        XCTAssertTrue(h.reports["ruleTime.schedules"]?.failures.contains { $0.reason.hasPrefix("time_uncertain") } == true,
            "R-07: unavailable time must be exposed")
    }

    // MARK: - (a) Anchor present but unusable: undecodable

    func testRedTeamR07UndecodableAnchorIsTreatedAsUnavailableNotWallTime() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        try rewrite(h, id: rule.id) { $0["timeAnchor"] = ["timeline": "not-a-date"] }
        let loaded = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(loaded.protection?.timeAnchor?.isValid, false, "an undecodable anchor must decode to the unavailable sentinel")
        h.now = try instant("2026-10-28T18:00:00Z")
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil) else {
            return XCTFail("R-07: an undecodable anchor must defer like an absent one")
        }
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
    }

    // MARK: - (a) Anchor present but implausible: still "valid" to the clock

    func testRedTeamR07CorruptFutureAnchorDoesNotReleaseActiveHardcoreOccurrence() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore)) // 09:00-17:00, active, anchored at 10:00
        try setAnchorTimeline(h, id: rule.id, to: "2026-10-28T20:00:00Z")
        let arbiter = FlintRuleArbiter(environment: h.environment)
        let result = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil)
        XCTAssertTrue(h.cleared.isEmpty, "R-07: an implausible anchor must not clear the active Hardcore shield")
        XCTAssertTrue(h.center.stops.isEmpty, "R-07: an implausible anchor must not retire the carrier")
        switch result {
        case .deferRequired:
            break // the active Hardcore occurrence correctly holds the weakening to its end
        case let .confirmationRequired(ticket, _):
            // The implausible anchor made a running occurrence look inactive, so the off path
            // downgraded to a plain confirmation and applies immediately.
            XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
                revision: rule.revision, confirmation: ticket), .applied,
                "R-07: the confirmed off applied inside the retained occurrence")
            XCTAssertEqual(h.cleared, [rule.storeName], "R-07: the shield was released inside the occurrence")
        default:
            XCTFail("R-07: got \(result) — an active Hardcore occurrence must defer its weakening")
        }
    }

    func testRedTeamR07CorruptFutureAnchorDoesNotMakeQueuedOffDue() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, _) = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id,
            enabled: false, revision: rule.revision, confirmation: nil) else { return XCTFail("must defer") }
        if case .queued = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: ticket) {} else { return XCTFail("queue must be accepted") }
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertTrue(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled, "control: the queued off is not due yet")
        try setAnchorTimeline(h, id: rule.id, to: "2026-10-28T20:00:00Z")
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertTrue(h.cleared.isEmpty, "R-07: an implausible anchor must not make the queued off due")
        XCTAssertTrue(try XCTUnwrap(h.load(FlintScheduleRule.self).first).enabled,
            "R-07: an implausible anchor must not apply the queued off without elapsed time")
        XCTAssertTrue(h.center.stops.isEmpty)
    }

    func testRedTeamR07CorruptAnchorCannotShortenTheRetainedCommitment() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        let retained = try XCTUnwrap(rule.protection?.occurrence)
        try setAnchorTimeline(h, id: rule.id, to: "2026-10-28T16:59:00Z")
        h.advance(61) // one observed minute; the retained occurrence has eight hours left
        let observed = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self))
        let frozen = try XCTUnwrap(h.environment.freezeOccurrences(observed))
        XCTAssertEqual(frozen.first?.protection?.occurrence, retained,
            "R-07: the retained occurrence must survive until its trusted end")
        XCTAssertTrue(h.cleared.isEmpty, "R-07: one observed minute must not clear an eight-hour Hardcore shield")
        XCTAssertTrue(h.center.stops.isEmpty)
    }

    // MARK: - (b) Anchor lost again after one re-anchor

    func testRedTeamR07SecondAnchorLossReanchorsWithinTheRetainedOccurrence() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        let retained = try XCTUnwrap(rule.protection?.occurrence)

        try removeAnchor(h, id: rule.id)
        h.advance(1800) // 10:30, thirty observed minutes
        let first = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self)?.first)
        XCTAssertEqual(first.protection?.timeAnchor?.timeline, retained.start,
            "the first loss re-anchors at the retained start")
        XCTAssertEqual(first.occurrence(at: h.now, clock: h.clock), retained)

        try removeAnchor(h, id: rule.id) // second loss
        h.advance(1800) // 11:00
        XCTAssertNotNil(h.environment.observedRules(FlintScheduleRule.self))
        let second = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(second.protection?.timeAnchor?.timeline, retained.start,
            "a second loss must not re-anchor at the jumped wall")
        XCTAssertEqual(second.occurrence(at: h.now, clock: h.clock), retained,
            "the retained occurrence is still running after a second loss")
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil) else {
            return XCTFail("R-07: an occurrence still running after a second loss must defer")
        }
    }

    // MARK: - (c) Wall moved backward before the first observation

    func testRedTeamR07BackwardWallBeforeFirstObservationKeepsOccurrenceActive() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        try removeAnchor(h, id: rule.id)
        h.now = try instant("2026-10-28T02:00:00Z") // wall back, monotonic unchanged
        let loaded = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(loaded.occurrence(at: h.now, clock: h.clock), rule.protection?.occurrence,
            "R-07: a backward wall jump must not make the retained occurrence look gone")
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case .deferRequired = arbiter.delete(FlintScheduleRule.self, id: rule.id,
            revision: rule.revision, confirmation: nil) else {
            return XCTFail("R-07: a backward wall jump must not release an active Hardcore occurrence")
        }
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
    }

    // MARK: - (d) Device time-zone change before the first observation

    func testRedTeamR07DeviceZoneChangeBeforeFirstObservationKeepsSavedEndpoints() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore)) // saved rule zone is UTC
        try removeAnchor(h, id: rule.id)
        var env = h.environment
        env.clock = .init(now: { h.now }, monotonic: { h.monotonic }, bootSessionID: { h.boot }, calendar: {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
            return calendar
        })
        h.now = try instant("2026-10-28T18:00:00Z") // device zone and wall both change
        let observed = try XCTUnwrap(env.observedRules(FlintScheduleRule.self)?.first)
        XCTAssertEqual(observed.occurrence(at: h.now, clock: env.clock), rule.protection?.occurrence,
            "R-07: a device-zone change moves display only; saved occurrence instants are preserved")
        let arbiter = FlintRuleArbiter(environment: env)
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil) else {
            return XCTFail("R-07: a zone change plus wall jump must not release an active Hardcore occurrence")
        }
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
    }

    // MARK: - (e) Retained occurrence with an unusable saved duration

    func testRedTeamR07UnusableRetainedDurationsFailClosed() throws {
        let cases: [(label: String, start: String, end: String)] = [
            ("zero", "2026-10-28T10:00:00Z", "2026-10-28T10:00:00Z"),
            ("negative", "2026-10-28T10:00:00Z", "2026-10-28T09:00:00Z"),
            ("huge", "2026-10-28T09:00:00Z", "2026-10-29T15:00:01Z") // > 26h
        ]
        for item in cases {
            let h = try RuleHarness(); defer { h.clean() }
            h.now = try instant("2026-10-28T10:00:00Z")
            let rule = h.install(h.schedule(tier: .hardcore))
            let start = try reference(item.start), end = try reference(item.end)
            try rewrite(h, id: rule.id) { protection in
                protection["occurrence"] = ["start": start, "end": end]
            }
            let arbiter = FlintRuleArbiter(environment: h.environment)
            let result = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
                revision: rule.revision, confirmation: nil)
            XCTAssertEqual(result, .unavailable, "R-07/§8: an unusable \(item.label) duration is unavailable, never a release")
            XCTAssertTrue(h.cleared.isEmpty, "R-07: \(item.label) duration must not clear the shield")
            XCTAssertTrue(h.center.stops.isEmpty)
            XCTAssertNotNil(h.center.held[.init(rule.activityName)])
        }
    }

    // MARK: - (f) Queue-window evidence at the edges

    func testRedTeamR07QueueWindowWithFarPastStartIsNotDueEarly() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        try removeAnchor(h, id: rule.id)
        try removeOccurrence(h, id: rule.id)
        // The only retained evidence is a 24h queue window that started 09:00 today.
        let window: [String: Any] = ["start": try reference("2026-10-28T09:00:00Z"),
                                     "end": try reference("2026-10-29T09:00:00Z")]
        let queue: [String: Any] = ["action": "delete", "baseRevision": 1, "window": window,
                                    "fingerprint": "redteam-r07-past-window"]
        try rewrite(h, id: rule.id) { $0["queuedChange"] = queue }
        let arbiter = FlintRuleArbiter(environment: h.environment)
        h.now = try instant("2026-10-28T18:00:00Z") // wall jumped 8h past the old occurrence end
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.load(FlintScheduleRule.self).count, 1, "R-07: the queued delete is not due on jumped wall time")
        XCTAssertTrue(h.cleared.isEmpty)
        h.advance(24 * 3600) // a full day of observed time reaches the queue window end
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty, "the queue is due only at its bounded end")
    }

    func testRedTeamR07QueueWindowWithFutureStartIsNotDueEarly() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T18:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        try removeAnchor(h, id: rule.id)
        try removeOccurrence(h, id: rule.id)
        try rewrite(h, id: rule.id) { protection in
            let window: [String: Any] = ["start": try reference("2026-10-29T09:00:00Z"),
                                         "end": try reference("2026-10-29T17:00:00Z")]
            let queue: [String: Any] = ["action": "delete", "baseRevision": 1, "window": window,
                                        "fingerprint": "redteam-r07-future-window"]
            protection["queuedChange"] = queue
        }
        let arbiter = FlintRuleArbiter(environment: h.environment)
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.load(FlintScheduleRule.self).count, 1,
            "R-07: a future queue window must not make the weakening due on wall time")
        XCTAssertTrue(h.cleared.isEmpty)
        h.advance(8 * 3600) // observed time to the window end
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertTrue(h.load(FlintScheduleRule.self).isEmpty, "the queue is due only at its bounded end")
    }

    // MARK: - (g) Legacy import records

    func testRedTeamR07LegacyImportIsBoundedByItsImportedOccurrence() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        // The exact pre-ADR-012 shape: no protection metadata at all. Seeded before the
        // initialization receipt, which is how a shipped pre-ADR-012 install looks.
        var legacy = h.schedule(tier: .hardcore)
        legacy.protection = nil
        h.defaults.set(try JSONEncoder().encode([legacy]), forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        let migrated = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        let protection = try XCTUnwrap(migrated.protection)
        XCTAssertNil(protection.timeAnchor, "an unobserved legacy import is carved out of the anchor normalization")
        XCTAssertEqual(protection.legacyRightsUntil, protection.occurrence?.end,
            "legacy rights are bounded by exactly the imported occurrence")
        XCTAssertTrue(protection.usesLegacyActivityName)

        // One observation establishes a trusted anchor, so the carve-out cannot outlive it.
        XCTAssertNotNil(h.environment.observedRules(FlintScheduleRule.self))
        let anchored = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(anchored.protection?.timeAnchor?.isValid, true, "the carve-out ends at the first observation")
        h.now = try instant("2026-10-28T18:00:00Z") // wall jump, no elapsed
        XCTAssertEqual(anchored.occurrence(at: h.now, clock: h.clock), protection.occurrence,
            "R-07: a wall jump cannot end the imported occurrence once an anchor exists")
        XCTAssertEqual(h.environment.observedRules(FlintScheduleRule.self)?.first?.occurrence(at: h.now, clock: h.clock),
            protection.occurrence, "R-07: the imported occurrence keeps its trusted window across a wall jump")
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
        // §12 deliberately keeps the old build's editing rights for this one imported occurrence
        // (its legacy rights end exactly at `legacyRightsUntil`), so the off path itself is not a
        // weakening; only the occurrence's trusted end is asserted here.
    }

    // MARK: - (h) Stripping an active occurrence to no evidence

    func testRedTeamR07ActiveOccurrenceIsNeverStrippedToNoEvidenceByMutations() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, _) = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id,
            enabled: false, revision: rule.revision, confirmation: nil) else { return XCTFail("must defer") }
        if case .queued = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: ticket) {} else { return XCTFail("queue must be accepted") }
        let queued = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNotNil(queued.protection?.occurrence, "an accepted queued weakening keeps the running occurrence")
        XCTAssertNotNil(queued.protection?.queuedChange)
        XCTAssertEqual(queued.protection?.timeAnchor?.isValid, true, "and keeps a trusted anchor")
        // Cancelling the queue keeps both too.
        XCTAssertEqual(arbiter.cancelQueued(FlintScheduleRule.self, id: rule.id,
            expected: try XCTUnwrap(queued.protection)), .applied)
        let cancelled = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertNotNil(cancelled.protection?.occurrence)
        XCTAssertEqual(cancelled.protection?.timeAnchor?.isValid, true)
        XCTAssertTrue(cancelled.enabled)
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
    }

    // MARK: - (i) Process death between re-anchoring and persisting it

    func testRedTeamR07UnsavedReanchorFailsClosedAndRetriesFromTheRetainedStart() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        try removeAnchor(h, id: rule.id)
        h.now = try instant("2026-10-28T18:00:00Z")
        h.saveAllowed = false // observation cannot be persisted: the process "dies" mid-re-anchor
        let arbiter = FlintRuleArbiter(environment: h.environment)
        XCTAssertEqual(arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil), .unavailable,
            "R-07/§8: an unpersistable re-anchor is unavailable, never release authority")
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.center.stops.isEmpty)
        // The retry re-anchors at the retained start, so the jumped wall still buys nothing.
        h.saveAllowed = true
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil) else {
            return XCTFail("R-07: the retry must still defer the active Hardcore occurrence")
        }
        XCTAssertTrue(h.cleared.isEmpty)
        let recovered = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        XCTAssertEqual(recovered.protection?.timeAnchor?.timeline, rule.protection?.occurrence?.start,
            "the recovered anchor is the retained occurrence start, not the jumped wall")
    }

    // MARK: - (j) `time_uncertain` must not become permission

    func testRedTeamR07TimeUncertainIsRecordedAndNeverBecomesReleaseAuthority() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        let rule = h.install(h.schedule(tier: .hardcore))
        try removeAnchor(h, id: rule.id)
        h.now = try instant("2026-10-28T18:00:00Z")
        let observed = try XCTUnwrap(h.environment.observedRules(FlintScheduleRule.self)?.first)
        XCTAssertTrue(h.reports["ruleTime.schedules"]?.failures.contains { $0.reason.hasPrefix("time_uncertain") } == true,
            "the uncertain anchor must be visible in arming health")
        XCTAssertEqual(observed.protection?.timeAnchor?.isValid, true, "and must be replaced by a bounded anchor")
        XCTAssertTrue(h.cleared.isEmpty, "recording uncertainty is not a release")
        XCTAssertTrue(h.center.stops.isEmpty)
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil) else {
            return XCTFail("R-07: uncertain time must keep the weakening deferred")
        }
    }

    // MARK: - Untrusted boot identity: bounded fallback, never an unbounded jump

    func testRedTeamR07UnknownBootIdentityRebootCannotOverCountElapsed() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.now = try instant("2026-10-28T10:00:00Z")
        var env = h.environment
        // `kern.bootsessionuuid` unavailable: the anchor and every later read have no boot id.
        env.clock = .init(now: { h.now }, monotonic: { h.monotonic }, bootSessionID: { nil }, calendar: {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "UTC")!
            return calendar
        })
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: env))
        var rule = h.schedule(tier: .hardcore)
        rule.oneShot = .init(start: h.now, end: h.now.addingTimeInterval(3600)) // finite 10:00-11:00
        let window = rule.templateOccurrence(at: h.now)
        rule.protection?.occurrence = window
        FlintRuleOccurrenceClock.observe(&rule, clock: env.clock)
        XCTAssertNil(rule.protection?.timeAnchor?.bootID)
        let retained = try XCTUnwrap(rule.protection?.occurrence)
        XCTAssertTrue(FlintDesiredRules.save([rule], key: .schedules, to: h.defaults))
        h.center.held[.init(rule.activityName)] = rule.registration()

        // An unmeasured reboot whose new uptime is larger than the anchor's monotonic reading.
        h.monotonic = 3 * 24 * 3600
        h.now = try instant("2026-10-28T10:30:00Z")
        let observed = try XCTUnwrap(env.observedRules(FlintScheduleRule.self)?.first)
        XCTAssertEqual(observed.occurrence(at: h.now, clock: env.clock), retained,
            "§9: with no boot identity the reboot may only delay, using the bounded wall fallback")
        let arbiter = FlintRuleArbiter(environment: env)
        guard case .deferRequired = arbiter.setEnabled(FlintScheduleRule.self, id: rule.id, enabled: false,
            revision: rule.revision, confirmation: nil) else {
            return XCTFail("R-07: an unmeasured reboot with no boot identity must not expire the occurrence")
        }
        XCTAssertTrue(h.cleared.isEmpty)
    }
}
