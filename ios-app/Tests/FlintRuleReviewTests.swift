import XCTest
import FamilyControls
@testable import FlintCore
@testable import Flint

/// Only injects a failed flush; successful operations still use the real suite.
private final class FailingRuleDefaults: UserDefaults {
    var successfulFlushesBeforeFailure: Int?
    override func synchronize() -> Bool {
        if let remaining = successfulFlushesBeforeFailure {
            guard remaining > 0 else { successfulFlushesBeforeFailure = nil; return false }
            successfulFlushesBeforeFailure = remaining - 1
        }
        return super.synchronize()
    }
}

final class FlintRuleReviewTests: XCTestCase {
    func testBusyRuleStartAddsOnlyTheExactReadableRevisionAndEndDoesNothing() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let schedule = h.install(h.schedule(tier: .hardcore))
        let zero = h.install(FlintLimitRule(id: "zero", name: "Zero", thresholdMinutes: 0,
            protection: .init(timeZoneID: "UTC")))
        let open = h.install(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3,
            protection: .init(timeZoneID: "UTC")))
        let source = [FlintRuleStoreKey.schedules, .limits, .openLimits].map { h.defaults.data(forKey: $0.rawValue) }
        var environment = h.environment; environment.withTransitionBoundary = { _ in false }
        let monitor = FlintRuleMonitor(environment: environment)
        _ = monitor.handle(.start, activity: schedule.activityName + ".stale")
        XCTAssertTrue(h.appliedSchedules.isEmpty)
        _ = monitor.handle(.start, activity: schedule.activityName)
        _ = monitor.handle(.start, activity: zero.activityName)
        _ = monitor.handle(.start, activity: open.activityName)
        XCTAssertEqual(h.appliedSchedules, [schedule]); XCTAssertEqual(h.appliedLimits, [zero]); XCTAssertEqual(h.appliedOpens, [open])
        h.now = try XCTUnwrap(schedule.protection?.occurrence?.end)
        _ = monitor.handle(.end, activity: schedule.activityName)
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.stops.isEmpty); XCTAssertTrue(h.center.starts.isEmpty)
        XCTAssertEqual([FlintRuleStoreKey.schedules, .limits, .openLimits].map { h.defaults.data(forKey: $0.rawValue) }, source)
        XCTAssertNotNil(h.reports["schedules"]?.failures.first)
        XCTAssertTrue(try h.ledger.read().get().harder.isEmpty)
    }

    func testFailedOccurrenceFreezeStillAddsItsScheduleShieldAndReportsFailure() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let source = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        h.advance(86_400); h.saveAllowed = false
        let monitor = FlintRuleMonitor(environment: h.environment)
        _ = monitor.handle(.start, activity: rule.activityName)
        XCTAssertEqual(h.appliedSchedules.map(\.activityName), [rule.activityName])
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        _ = monitor.handle(.end, activity: rule.activityName) // yesterday's delayed end cannot clear today's shield
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertNotNil(h.reports["schedules"]?.failures.first)
    }

    func testFailedDueQueueKeepsExhaustedTimeShieldAndHonorsTheFirstThresholdCallback() throws {
        for alreadyShielded in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
                breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
            var future = old; future.thresholdMinutes = 60
            let arbiter = FlintRuleArbiter(environment: h.environment)
            guard case let .deferRequired(ticket, end) = arbiter.upsert(future) else { return XCTFail() }
            _ = arbiter.upsert(future, confirmation: ticket)
            var refused = future; refused.protection?.activityRevision = old.revision + 2
            h.center.refused = [refused.activityName]
            h.now = end.addingTimeInterval(9 * 3600); h.timeLimitExhausted = alreadyShielded
            var marks = 0; var resets = 0
            var environment = h.environment
            environment.markLimitThreshold = { _, _ in marks += 1 }
            environment.clearLimitThreshold = { _ in resets += 1 }
            _ = FlintRuleMonitor(environment: environment).handle(.threshold(old.eventName), activity: old.activityName)
            XCTAssertFalse(h.appliedLimits.isEmpty, "even the first threshold is honored when queue repair fails")
            XCTAssertTrue(h.cleared.isEmpty); XCTAssertEqual(resets, 0)
            XCTAssertGreaterThan(marks, 0)
            XCTAssertEqual(h.load(FlintLimitRule.self).first?.thresholdMinutes, 30)
            XCTAssertNotNil(h.load(FlintLimitRule.self).first?.protection?.queuedChange)
        }
    }

    func testHardcoreQueuePreflightCannotCrossIntoANewOccurrence() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        let source = h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue)
        var future = old; future.thresholdMinutes = 60
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, end) = arbiter.upsert(future) else { return XCTFail() }
        h.center.onStart = { _ in h.now = end.addingTimeInterval(1) }
        XCTAssertEqual(arbiter.upsert(future, confirmation: ticket), .staleRevision)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.limits.rawValue), source)
        XCTAssertEqual(h.center.activities.map(\.rawValue), [old.activityName])
        XCTAssertTrue(h.cleared.isEmpty)
    }

    func testFailedDefaultsFlushRestoresThePriorBlobAndInitializationMarker() throws {
        for (initialized, successfulFlushes) in [(false, 1), (false, 2), (true, 1), (true, 2)] {
            let h = try RuleHarness(); defer { h.clean() }
            let original = h.install(h.schedule(tier: .easy))
            if !initialized { h.defaults.removeObject(forKey: FlintRuleStoreKey.schedules.marker) }
            let writer = try XCTUnwrap(FailingRuleDefaults(suiteName: h.suite))
            let bytes = writer.data(forKey: FlintRuleStoreKey.schedules.rawValue)
            let marker = writer.data(forKey: FlintRuleStoreKey.schedules.marker)
            var changed = original; changed.enabled = false
            // Flush 1 reads the baseline; 2 writes the blob; 3 writes the marker.
            writer.successfulFlushesBeforeFailure = successfulFlushes
            XCTAssertFalse(FlintDesiredRules.save([changed], key: .schedules, to: writer))
            XCTAssertEqual(writer.data(forKey: FlintRuleStoreKey.schedules.rawValue), bytes)
            XCTAssertEqual(writer.data(forKey: FlintRuleStoreKey.schedules.marker), marker)
            XCTAssertTrue(writer.synchronize())
            XCTAssertEqual(FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules,
                from: writer, at: h.now, recoveryDirectory: h.recoveryDirectory), [original])
        }
    }

    func testFailedDefaultsReadIsUnavailableAndDoesNotRetireNativeProtection() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let original = h.install(h.schedule(tier: .easy))
        let writer = try XCTUnwrap(FailingRuleDefaults(suiteName: h.suite))
        var environment = h.environment; environment.defaults = writer
        let bytes = writer.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        writer.successfulFlushesBeforeFailure = 0
        XCTAssertEqual(FlintSchedulesController(environment: environment).delete(original.id,
            revision: original.revision), .unavailable)
        XCTAssertEqual(writer.data(forKey: FlintRuleStoreKey.schedules.rawValue), bytes)
        XCTAssertTrue(h.center.stops.isEmpty); XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertNotNil(h.center.held[.init(original.activityName)])
    }

    func testOpenCreditLegacyImportDoesNotMigrateOrInitializeTheRulesDomain() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var legacy = FlintOpenLimitRule(id: "legacy", name: "Legacy", opensAllowed: 3,
            breakLevel: .hardcore)
        legacy.protection = nil
        let source = try JSONEncoder().encode([legacy])
        h.defaults.set(source, forKey: FlintRuleStoreKey.openLimits.rawValue)
        var state = FlintLedgerState(initializedAt: h.now)
        FlintOpenCredit.importLegacy(into: &state, defaults: h.defaults,
            at: FlintLedgerInstant(clock: h.clock), calendar: h.clock.calendar())
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.openLimits.rawValue), source)
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.openLimits.marker))
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.openLimits.original))
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.openLimits.rawValue + ".quarantine.adr012"))
    }

    func testOpenRecoveryPrecedesMonitorAndHostRuleReadsAndEveryObservedReapplyPreservesGrants() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var legacy = FlintOpenLimitRule(id: "legacy", name: "Legacy", opensAllowed: 3)
        legacy.protection = nil
        let source = try JSONEncoder().encode([legacy])
        h.defaults.set(source, forKey: FlintRuleStoreKey.openLimits.rawValue)
        h.center.held[.init(legacy.activityName)] = legacy.registration()
        var recovered = 0
        var environment = h.environment
        environment.reconcileOpenCredits = {
            if recovered == 0 {
                XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.openLimits.rawValue), source)
                XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.openLimits.marker),
                    "no migration/queue read may run ahead of credit recovery")
            }
            recovered += 1
        }
        XCTAssertTrue(FlintRuleMonitor(environment: environment).handle(.start, activity: legacy.activityName))
        XCTAssertEqual(recovered, 1)
        XCTAssertEqual(h.appliedOpens.count, 1)
        FlintOpenLimitsController(environment: environment, selectionIssue: { _ in nil }).reload(reapplyShields: false)
        XCTAssertEqual(recovered, 2)
        XCTAssertEqual(h.appliedOpens.count, 2, "every observed reload uses the grant-aware writer")
    }

    func testMixedBatchUsesEachNaturalEndAndAppliesEasyAndInactiveImmediately() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var night = h.schedule(tier: .harder); night.id = "sleep.night"
        night.schedule = .init(startHour: 8, endHour: 7)
        var morning = h.schedule(tier: .hardcore); morning.id = "sleep.morning"
        morning.schedule = .init(startHour: 7, endHour: 19)
        var inactive = h.schedule(tier: .hardcore); inactive.id = "future"
        inactive.schedule = .init(startHour: 20, endHour: 21)
        var easy = h.schedule(tier: .easy); easy.id = "easy"
        let original = install([night, morning, inactive, easy], in: h)
        var nightOff = original[0]; nightOff.enabled = false
        var morningOff = original[1]; morningOff.enabled = false
        var future = original[2]; future.schedule.endHour = 22
        var easyOff = original[3]; easyOff.enabled = false
        let changes = [nightOff, morningOff, future, easyOff].map { (rule: FlintScheduleRule) in
            FlintRuleArbiter.Mutation<FlintScheduleRule>(id: rule.id, proposed: rule,
                action: rule.enabled ? .edit : .turnOff, expectedRevision: rule.revision)
        }
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, latest) = arbiter.mutate(changes, confirmation: nil) else { return XCTFail() }
        let nightEnd = try XCTUnwrap(original[0].occurrence(at: h.now)?.end)
        let morningEnd = try XCTUnwrap(original[1].occurrence(at: h.now)?.end)
        XCTAssertEqual(latest, nightEnd)
        XCTAssertEqual(ticket.preview.map(\.deferredUntil), [nightEnd, morningEnd, nil, nil])
        XCTAssertTrue(ticket.preview[0].offersEarlierHarderEdit)
        XCTAssertEqual(arbiter.mutate(changes, confirmation: ticket), .queued(until: nightEnd))
        var saved = h.load(FlintScheduleRule.self)
        XCTAssertEqual(saved[0].protection?.queuedChange?.window.end, nightEnd)
        XCTAssertEqual(saved[1].protection?.queuedChange?.window.end, morningEnd)
        XCTAssertTrue(saved[0].enabled); XCTAssertTrue(saved[1].enabled)
        XCTAssertEqual(saved[2].schedule.endHour, 22); XCTAssertNil(saved[2].protection?.queuedChange)
        XCTAssertFalse(saved[3].enabled); XCTAssertNil(saved[3].protection?.queuedChange)
        XCTAssertTrue(try h.ledger.read().get().harder.isEmpty, "natural-end deferral spends no Harder wait")
        h.now = morningEnd
        _ = FlintRuleMonitor(environment: h.environment).handle(.end, activity: original[1].activityName)
        saved = h.load(FlintScheduleRule.self)
        XCTAssertFalse(saved[1].enabled)
        XCTAssertTrue(saved[0].enabled, "the still-active Harder night cannot be weakened at its sibling's end")
        h.now = nightEnd
        _ = FlintRuleMonitor(environment: h.environment).handle(.start, activity: original[0].activityName)
        XCTAssertFalse(h.load(FlintScheduleRule.self)[0].enabled)
        let starts = h.center.starts.count
        XCTAssertTrue(arbiter.applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(h.center.starts.count, starts)
        XCTAssertTrue(try h.ledger.read().get().harder.isEmpty)
    }

    func testDeferredBatchNativePreflightFailureWritesNothingAndRollsBackOnlyNewResources() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var easy = h.schedule(tier: .easy); easy.id = "easy"
        var hard = h.schedule(tier: .hardcore); hard.id = "hard"
        let original = install([easy, hard], in: h)
        let bytes = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        var stronger = original[0]; stronger.schedule.endHour = 18
        var weaker = original[1]; weaker.schedule.endHour = 16
        let changes = [stronger, weaker].map { (rule: FlintScheduleRule) in
            FlintRuleArbiter.Mutation<FlintScheduleRule>(id: rule.id, proposed: rule, action: .edit, expectedRevision: rule.revision)
        }
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, _) = arbiter.mutate(changes, confirmation: nil) else { return XCTFail() }
        var refused = weaker; refused.protection?.activityRevision = weaker.revision + 2
        h.center.refused = [refused.activityName]
        XCTAssertEqual(arbiter.mutate(changes, confirmation: ticket), .unavailable)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), bytes)
        XCTAssertEqual(Set(h.center.activities.map(\.rawValue)), Set(original.map(\.activityName)))
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.appliedSchedules.isEmpty)
    }

    func testSecondQueuedActionIsRefusedUntilStoredQueueIsCancelled() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, _) = arbiter.setEnabled(FlintScheduleRule.self, id: old.id,
            enabled: false, revision: old.revision, confirmation: nil) else { return XCTFail() }
        _ = arbiter.setEnabled(FlintScheduleRule.self, id: old.id, enabled: false,
            revision: old.revision, confirmation: ticket)
        let queued = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        let before = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        var another = queued; another.schedule.endHour = 16
        guard case let .invalid(reason) = arbiter.upsert(another) else { return XCTFail() }
        XCTAssertTrue(reason.contains("Cancel"))
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), before)
        XCTAssertEqual(arbiter.cancelQueued(FlintScheduleRule.self, id: old.id,
            expected: try XCTUnwrap(queued.protection)), .applied)
        let current = try XCTUnwrap(h.load(FlintScheduleRule.self).first)
        guard case let .deferRequired(newTicket, _) = arbiter.setEnabled(FlintScheduleRule.self, id: current.id,
            enabled: false, revision: current.revision, confirmation: nil) else { return XCTFail() }
        _ = arbiter.setEnabled(FlintScheduleRule.self, id: current.id, enabled: false,
            revision: current.revision, confirmation: newTicket)
        XCTAssertEqual(arbiter.cancelQueued(FlintScheduleRule.self, id: old.id,
            expected: try XCTUnwrap(queued.protection)), .staleRevision)
        XCTAssertNotNil(h.load(FlintScheduleRule.self).first?.protection?.queuedChange)
    }

    func testValidationRefusalPreservesExistingNativeActivitiesAndShieldsInEveryRuleDomain() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var schedule = h.schedule(tier: .hardcore); schedule.allowListMode = false // empty block list is refused
        let a = h.install(schedule)
        let b = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30))
        let c = h.install(FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3))
        _ = FlintSchedulesController(environment: h.environment).reload()
        FlintLimitsController(environment: h.environment).reload()
        FlintOpenLimitsController(environment: h.environment).reload()
        XCTAssertEqual(Set(h.center.activities.map(\.rawValue)), [a.activityName, b.activityName, c.activityName])
        XCTAssertTrue(h.center.stops.isEmpty); XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertEqual(Set(h.reports.keys), ["schedules", "limits", "openLimits"])
    }

    func testSafariOffUsesSavedZoneFrozenWindowAndOneShotInsteadOfDisplayCalendar() throws {
        for mode in ["zone", "frozen", "oneShot"] {
            let h = try RuleHarness(); defer { h.clean() }
            h.now = ISO8601DateFormatter().date(from: "2026-10-28T20:00:00Z")!
            var rule = h.schedule(tier: .hardcore)
            let explicit = FlintRuleWindow(start: h.now.addingTimeInterval(-3600), end: h.now.addingTimeInterval(3600))
            if mode == "zone" { rule.protection?.timeZoneID = "America/Los_Angeles" }
            if mode == "frozen" { rule.protection?.occurrence = explicit }
            if mode == "oneShot" { rule.oneShot = explicit }
            var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
            let editor = FlintWebRestrictionEditor(load: { config }, snapshot: { .init(session: nil, rules: [rule]) },
                persist: { config = $0; return true }, ledger: { h.ledger }, clock: h.clock)
            var draft = config; draft.applyDuringSchedules = false
            let end = try XCTUnwrap(rule.occurrence(at: h.now)?.end)
            XCTAssertEqual(editor.save(draft, requestID: mode), .protected(until: end))
            XCTAssertTrue(config.applyDuringSchedules)
        }
    }

    func testSafariAndRuleWeakeningShareOneOccurrenceCountSequence() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .harder))
        let cause = FlintRuleArbiter.causeID(old, window: old.occurrence(at: h.now))
        var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        let editor = FlintWebRestrictionEditor(load: { config }, snapshot: {
            .init(session: nil, rules: h.load(FlintScheduleRule.self))
        }, persist: { config = $0; return true }, ledger: { h.ledger }, clock: h.clock)
        var off = config; off.applyDuringSchedules = false
        XCTAssertEqual(editor.save(off, requestID: "web-first"), .waiting(seconds: 60))
        h.advance(60)
        XCTAssertEqual(editor.save(off, requestID: "web-first", confirmed: true), .saved)
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 1)
        var weaker = old; weaker.schedule.endHour = 16
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .waiting(ticket, remaining, _) = arbiter.upsert(weaker) else { return XCTFail() }
        XCTAssertEqual(remaining, 300)
        h.advance(300)
        XCTAssertEqual(arbiter.upsert(weaker, confirmation: ticket), .applied)
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 2)
        var on = config; on.applyDuringSchedules = true
        XCTAssertEqual(editor.save(on, requestID: "on"), .saved)
        XCTAssertEqual(editor.save(off, requestID: "web-third"), .waiting(seconds: 900))
    }

    func testSafariAuthorizationCannotBeReusedAfterTheRuleRevisionChanges() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .harder))
        let cause = FlintRuleArbiter.causeID(old, window: old.occurrence(at: h.now))
        var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        var acceptsSave = false
        let editor = FlintWebRestrictionEditor(load: { config }, snapshot: {
            .init(session: nil, rules: h.load(FlintScheduleRule.self))
        }, persist: { candidate in
            guard acceptsSave else { return false }
            config = candidate; return true
        }, ledger: { h.ledger }, clock: h.clock)
        var off = config; off.applyDuringSchedules = false
        XCTAssertEqual(editor.save(off, requestID: "web-retry"), .waiting(seconds: 60))
        h.advance(60)
        guard case let .invalid(message) = editor.save(off, requestID: "web-retry", confirmed: true) else {
            return XCTFail("a committed authorization must expose the failed save")
        }
        XCTAssertTrue(message.contains("Change authorized but not saved"))
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 1)
        var renamed = old; renamed.name = "Renamed"
        XCTAssertEqual(FlintRuleArbiter(environment: h.environment).upsert(renamed), .applied)
        acceptsSave = true
        XCTAssertEqual(editor.save(off, requestID: "web-retry", confirmed: true), .waiting(seconds: 300))
        XCTAssertTrue(config.applyDuringSchedules)
        h.advance(300)
        XCTAssertEqual(editor.save(off, requestID: "web-retry", confirmed: true), .saved)
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 2)
    }

    func testSafariMaturedWaitRequiresARefreshedConfirmationAfterARuleRename() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .harder))
        let cause = FlintRuleArbiter.causeID(old, window: old.occurrence(at: h.now))
        var config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        let editor = FlintWebRestrictionEditor(load: { config }, snapshot: {
            .init(session: nil, rules: h.load(FlintScheduleRule.self))
        }, persist: { config = $0; return true }, ledger: { h.ledger }, clock: h.clock)
        var off = config; off.applyDuringSchedules = false
        XCTAssertEqual(editor.save(off, requestID: "web-rename"), .waiting(seconds: 60))
        h.advance(60)
        var renamed = old; renamed.name = "Renamed"
        XCTAssertEqual(FlintRuleArbiter(environment: h.environment).upsert(renamed), .applied)
        XCTAssertEqual(editor.save(off, requestID: "web-rename", confirmed: true), .confirmationRequired)
        XCTAssertTrue(config.applyDuringSchedules)
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 0)
        XCTAssertEqual(editor.save(off, requestID: "web-rename", confirmed: true), .saved)
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 1)
    }

    func testFailedDailyQueueRetainsOriginalRegistrationAndRetriesOnlyAfterItsNewNaturalEnd() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30,
            breakLevel: .hardcore, protection: .init(timeZoneID: "UTC")))
        var future = old; future.thresholdMinutes = 60
        let arbiter = FlintRuleArbiter(environment: h.environment)
        guard case let .deferRequired(ticket, end) = arbiter.upsert(future) else { return XCTFail() }
        XCTAssertEqual(arbiter.upsert(future, confirmation: ticket), .queued(until: end))
        var refused = future; refused.protection?.activityRevision = old.revision + 2
        h.center.refused = [refused.activityName]
        h.now = end
        _ = FlintRuleMonitor(environment: h.environment).handle(.start, activity: old.activityName)
        let retained = try XCTUnwrap(h.load(FlintLimitRule.self).first)
        let retry = try XCTUnwrap(retained.protection?.queuedChange)
        XCTAssertEqual(retained.thresholdMinutes, 30)
        XCTAssertEqual(retained.activityName, old.activityName)
        XCTAssertNotNil(h.center.held[.init(old.activityName)])
        XCTAssertNotNil(retry.failureReason)
        XCTAssertEqual(retry.window, retained.templateOccurrence(at: h.now))
        XCTAssertNotNil(h.reports["limits"]?.failures.first)
        let starts = h.center.starts.count
        h.advance(3600)
        XCTAssertTrue(arbiter.applyQueued(FlintLimitRule.self))
        XCTAssertEqual(h.center.starts.count, starts, "foreground cannot retry into a new active Hardcore period")
        h.center.refused = []
        h.now = retry.window.end
        _ = FlintRuleMonitor(environment: h.environment).handle(.start, activity: old.activityName)
        XCTAssertEqual(h.load(FlintLimitRule.self).first?.thresholdMinutes, 60)
        XCTAssertNil(h.load(FlintLimitRule.self).first?.protection?.queuedChange)
        let finalStarts = h.center.starts.count
        XCTAssertTrue(arbiter.applyQueued(FlintLimitRule.self))
        XCTAssertEqual(h.center.starts.count, finalStarts)
    }

    func testBusyDisplayPeekDoesNotReportCorruptionOrAuthorizeAMutation() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .hardcore))
        let bytes = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        var env = h.environment; env.withTransitionBoundary = { _ in false }
        let controller = FlintSchedulesController(environment: env)
        XCTAssertEqual(controller.rules(), [old])
        XCTAssertTrue(h.reports.isEmpty)
        XCTAssertEqual(controller.setEnabled(old.id, false, revision: old.revision), .unavailable)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), bytes)
    }

    func testExplicitOldRevisionRejectsOffAndDeleteBeforeAnyConfirmation() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        let controller = FlintSchedulesController(environment: h.environment)
        var rename = old; rename.name = "Latest"
        XCTAssertEqual(controller.upsert(rename), .applied)
        XCTAssertEqual(controller.setEnabled(old.id, false, revision: old.revision), .staleRevision)
        XCTAssertEqual(controller.delete(old.id, revision: old.revision), .staleRevision)
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertEqual(controller.rules().first?.name, "Latest")
    }

    func testAuthorizedUnsavedChangeKeepsProtectionAndRequiresExplicitExactRetryWithoutDoubleCount() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .harder))
        let controller = FlintSchedulesController(environment: h.environment)
        var weaker = old; weaker.schedule.endHour = 16
        guard case let .waiting(ticket, _, _) = controller.upsert(weaker) else { return XCTFail() }
        h.advance(60); h.saveAllowed = false
        guard case let .authorizedButNotSaved(returned, end) = controller.upsert(weaker,
            confirmation: ticket, origin: .interactive) else { return XCTFail() }
        XCTAssertEqual(returned, ticket)
        XCTAssertEqual(end, old.occurrence(at: h.now)?.end)
        let cause = FlintRuleArbiter.causeID(old, window: old.occurrence(at: h.now))
        var ledger = try h.ledger.read().get()
        XCTAssertEqual(ledger.harder[cause]?.committedCount, 1)
        XCTAssertTrue(ledger.hasCommittedHarderWait(cause: cause, operationID: ticket.fingerprint))
        XCTAssertEqual(controller.rules(), [old])
        XCTAssertEqual(h.center.activities.map(\.rawValue), [old.activityName])
        XCTAssertTrue(h.cleared.isEmpty)
        XCTAssertTrue(h.reports["schedules"]?.failures.first?.reason.contains("authorized but not saved") == true)
        h.saveAllowed = true
        guard case .confirmationRequired = controller.upsert(weaker, confirmation: ticket) else { return XCTFail("background default cannot confirm") }
        guard case .authorizedButNotSaved = controller.upsert(weaker) else { return XCTFail("retry without confirmation cannot save") }
        XCTAssertEqual(controller.rules(), [old])
        XCTAssertEqual(controller.upsert(weaker, confirmation: ticket, origin: .interactive), .applied)
        ledger = try h.ledger.read().get()
        XCTAssertEqual(ledger.harder[cause]?.committedCount, 1)
        XCTAssertEqual(controller.rules().first?.schedule.endHour, 16)
    }

    func testCommittedAuthorizationCannotFollowARevisionBumpOrANewOccurrence() throws {
        for revisionBump in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let old = h.install(h.schedule(tier: .harder))
            let arbiter = FlintRuleArbiter(environment: h.environment)
            var weaker = old; weaker.schedule.endHour = 16
            guard case let .waiting(ticket, _, _) = arbiter.upsert(weaker) else { return XCTFail() }
            h.advance(60); h.saveAllowed = false
            guard case .authorizedButNotSaved = arbiter.upsert(weaker, confirmation: ticket) else { return XCTFail() }
            h.saveAllowed = true
            if revisionBump {
                var rename = old; rename.name = "Updated"
                XCTAssertEqual(arbiter.upsert(rename), .applied)
            } else { h.advance(86400) }
            XCTAssertEqual(arbiter.upsert(weaker, confirmation: ticket), .staleRevision)
            var fresh = try XCTUnwrap(h.load(FlintScheduleRule.self).first); fresh.schedule.endHour = 16
            guard case let .waiting(_, remaining, _) = arbiter.upsert(fresh) else { return XCTFail() }
            XCTAssertEqual(remaining, revisionBump ? 300 : 60)
            let oldCause = FlintRuleArbiter.causeID(old, window: old.protection?.occurrence)
            XCTAssertEqual(try h.ledger.read().get().harder[oldCause]?.committedCount, 1, "no refund after abandonment")
        }
    }

    func testStatusPublicationRunsAfterTheSharedTransitionReturns() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .easy))
        var inside = false
        var publications = 0
        var env = h.environment
        env.withTransitionBoundary = { body in inside = true; body(); inside = false; return true }
        env.publishStatus = { XCTAssertFalse(inside); publications += 1 }
        var rename = old; rename.name = "Renamed"
        XCTAssertEqual(FlintRuleArbiter(environment: env).upsert(rename), .applied)
        XCTAssertEqual(publications, 1)
    }

    func testCommittedAuthorizationExpiresEvenIfNativePreparationCrossesTheOccurrenceEnd() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = h.install(h.schedule(tier: .harder))
        let arbiter = FlintRuleArbiter(environment: h.environment)
        var weaker = old; weaker.schedule.endHour = 16
        guard case let .waiting(ticket, _, _) = arbiter.upsert(weaker) else { return XCTFail() }
        h.advance(60); h.saveAllowed = false
        guard case .authorizedButNotSaved = arbiter.upsert(weaker, confirmation: ticket) else { return XCTFail() }
        let end = try XCTUnwrap(old.protection?.occurrence?.end)
        let source = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        h.saveAllowed = true
        h.center.onStart = { _ in h.now = end.addingTimeInterval(1) }
        XCTAssertEqual(arbiter.upsert(weaker, confirmation: ticket), .staleRevision)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        let cause = FlintRuleArbiter.causeID(old, window: old.protection?.occurrence)
        XCTAssertEqual(try h.ledger.read().get().harder[cause]?.committedCount, 1)
        XCTAssertTrue(h.cleared.isEmpty)
    }

    private func install(_ rules: [FlintScheduleRule], in h: RuleHarness) -> [FlintScheduleRule] {
        let current = rules.map { initial in
            var rule = initial
            let window = rule.templateOccurrence(at: h.now)
            rule.protection?.occurrence = window
            return rule
        }
        XCTAssertTrue(FlintDesiredRules.save(current, key: .schedules, to: h.defaults))
        for rule in current where rule.enabled { h.center.held[.init(rule.activityName)] = rule.registration() }
        return current
    }
}

@MainActor
final class FlintQueuedRuleFlowTests: XCTestCase {
    func testCancelStoredSleepQueueAfterOriginalConfirmationSheetIsGone() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var sleep = h.schedule(tier: .hardcore); sleep.id = "sleep.night"
        let old = h.install(sleep)
        let controller = FlintSchedulesController(environment: h.environment)
        let flow = RuleMutationFlow()
        flow.run(scope: "Sleep", operation: {
            controller.setEnabled(old.id, false, revision: old.revision, confirmation: $0, origin: .interactive)
        })
        guard case let .deferRequired(ticket, end) = flow.result else { return XCTFail() }
        flow.confirm(ticket)
        XCTAssertFalse(flow.isPresented)
        let stored = try XCTUnwrap(controller.rules().first?.protection)
        let reopened = RuleMutationFlow()
        reopened.run(scope: "Sleep", operation: { _ in controller.cancelQueuedChange(old.id, expected: stored) })
        XCTAssertEqual(reopened.result, .applied)
        XCTAssertNil(controller.rules().first?.protection?.queuedChange)
        XCTAssertEqual(controller.rules().first?.activityName, old.activityName)
        h.now = end
        XCTAssertTrue(FlintRuleArbiter(environment: h.environment).applyQueued(FlintScheduleRule.self))
        XCTAssertEqual(controller.rules().first?.enabled, true)
        XCTAssertTrue(try h.ledger.read().get().harder.isEmpty)
    }
}
