import XCTest
import FamilyControls
import ManagedSettings
import FlintCore

final class FlintProtectionStatusTests: XCTestCase {
    typealias Builder = FlintProtectionStatusBuilder
    typealias Snapshot = FlintProtectionStatusSnapshot
    private let now = Date(timeIntervalSince1970: 1_793_188_800) // fixed instant
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private func inputs(rules: [Builder.RuleInput] = []) -> Builder.Inputs {
        .init(now: now, quotaCalendar: calendar, rules: rules, authorized: true,
              pass: .init(eligibility: .available), timeIntegrity: .consistent)
    }

    private func rule(_ id: String = "rule", tier: BreakLevel = .easy, end: TimeInterval = 600,
                      kind: Snapshot.Kind = .recurringSchedule) -> Builder.RuleInput {
        .init(id: id, kind: kind, displayName: "Focus rule", tier: tier, armed: true, observed: true,
              currentWindow: DateInterval(start: now.addingTimeInterval(-600),
                                          end: now.addingTimeInterval(end)), active: true)
    }

    private func session(armed: Bool = true, end: TimeInterval? = 300) -> Builder.SessionInput {
        .init(.init(id: "session", name: "Deep work", startedAt: now.addingTimeInterval(-60),
                    endsAt: end.map { now.addingTimeInterval($0) }, breakLevel: .harder,
                    monitorName: "private-monitor-token"), armed: armed)
    }

    private func defaults() -> UserDefaults {
        let name = "FlintProtectionStatusTests.\(UUID().uuidString)"
        let store = UserDefaults(suiteName: name)!
        addTeardownBlock { store.removePersistentDomain(forName: name) }
        return store
    }

    private let quiet = FlintProtectionStatus.PublicationEffects(reloadTimelines: { _ in },
                                                                 postDarwinNotification: { _ in })

    private func published(_ snapshot: Snapshot, writer: String = "host") -> Snapshot {
        .init(version: snapshot.version, observedAt: snapshot.observedAt, validUntil: snapshot.validUntil,
              state: snapshot.state, session: snapshot.session, headline: snapshot.headline,
              causes: snapshot.causes, nextOccurrence: snapshot.nextOccurrence, pass: snapshot.pass,
              degradedReasons: snapshot.degradedReasons, writer: writer)
    }

    func testAllFiveStatesAndPrecedence() {
        XCTAssertEqual(Builder.make(inputs()).state, .notProtected)
        var value = inputs(rules: [rule()])
        XCTAssertEqual(Builder.make(value).state, .protected)
        value.authorized = false
        XCTAssertEqual(Builder.make(value).state, .degraded)
        XCTAssertNil(Builder.make(value).headline)
        XCTAssertFalse(Builder.make(value).causes[0].enforcing)
        value.pass = .init(eligibility: .coolingDown, recoveryUntil: now.addingTimeInterval(3600))
        XCTAssertEqual(Builder.make(value).state, .suspended)
        XCTAssertFalse(Builder.make(value).causes[0].enforcing)
        value.recordUnreadable = true
        XCTAssertEqual(Builder.make(value).state, .unavailable)
    }

    func testEveryRequiredUnavailableInputSuppressesAllClaims() {
        let baseline = inputs(rules: [rule()])
        var variants: [Builder.Inputs] = []
        var value = baseline; value.available = false; variants.append(value)
        value = baseline; value.authorized = nil; variants.append(value)
        value = baseline; value.timeIntegrity = nil; variants.append(value)
        value = baseline; value.recordUnreadable = true; variants.append(value)
        value = baseline; value.pass = .init(eligibility: .unavailable); variants.append(value)
        for variant in variants {
            let result = Builder.make(variant)
            XCTAssertEqual(result.state, .unavailable)
            XCTAssertNil(result.headline)
            XCTAssertTrue(result.causes.allSatisfy { !$0.enforcing })
        }
    }

    func testPendingPassOutboxNeverClaimsSuspensionOrProtection() {
        var value = inputs(rules: [rule()])
        value.activeSession = session()
        value.pass = .init(eligibility: .coolingDown, recoveryUntil: now.addingTimeInterval(3600))
        value.recoveryApplying = true
        let applying = Builder.make(value)
        XCTAssertEqual(applying.state, .degraded)
        XCTAssertTrue(applying.degradedReasons.contains("recovery_applying"))
        XCTAssertNil(applying.session)
        XCTAssertNil(applying.headline)
        XCTAssertTrue(applying.causes.allSatisfy { !$0.enforcing })
        XCTAssertEqual(applying.validUntil, now.addingTimeInterval(60))
        value.recoveryApplying = false
        XCTAssertEqual(Builder.make(value).state, .suspended, "release acknowledged during recovery")
        value.pass = .init(eligibility: .coolingDown)
        value.recoveryApplying = true
        XCTAssertEqual(Builder.make(value).state, .degraded, "restoration still pending after recovery")
        value.recoveryApplying = false
        XCTAssertEqual(Builder.make(value).state, .protected)
    }

    func testUnresolvedRuleDegradesWithoutSuppressingUnrelatedEnforcingCause() {
        let unresolved = Builder.RuleInput.schedule(id: "unresolved", displayName: "Unresolved",
            tier: .hardcore, schedule: FlintSchedule(startHour: 25, endHour: 1),
            armed: true, observed: true, now: now, calendar: calendar)
        XCTAssertFalse(unresolved.available)
        let result = Builder.make(inputs(rules: [unresolved, rule("known")]))
        XCTAssertEqual(result.state, .degraded)
        XCTAssertEqual(result.headline?.id, "known")
        XCTAssertTrue(result.headline?.enforcing == true)
        XCTAssertEqual(result.degradedReasons, ["state_unavailable"])
        XCTAssertEqual(result.causes.map(\.id), ["known"])
        let uncertain = FlintProtectionStatus.contributors(for: "target",
            inputs: inputs(rules: [unresolved, rule("known")]),
            coverageByCauseID: ["unresolved": .only(["target"]), "known": .only(["other"])])
        XCTAssertEqual(uncertain.state, .degraded)
        XCTAssertTrue(uncertain.coverageUncertain, "unresolved activity must not authorize release")
        let excluded = FlintProtectionStatus.contributors(for: "excluded",
            inputs: inputs(rules: [unresolved]), coverageByCauseID: ["unresolved": .only(["target"])])
        XCTAssertEqual(excluded.state, .notProtected)
        XCTAssertFalse(excluded.coverageUncertain)

        var activeUnverified = rule("unverified")
        activeUnverified.available = false
        let active = Builder.make(inputs(rules: [activeUnverified, rule("known")]))
        XCTAssertEqual(active.state, .degraded)
        XCTAssertFalse(active.causes.first { $0.id == "unverified" }!.enforcing)
        XCTAssertEqual(active.headline?.id, "known")
    }

    func testUnarmedAndUnobservedEnabledRulesDegradeEvenOutsideTheirWindow() {
        var value = inputs(rules: [rule()])
        value.rules[0].armed = false
        value.rules[0].observed = false
        value.rules[0].currentWindow = nil
        let result = Builder.make(value)
        XCTAssertEqual(result.state, .degraded)
        XCTAssertEqual(result.degradedReasons, ["registration_failed", "registration_unobserved"])
        XCTAssertTrue(result.causes.isEmpty)
        value.rules[0].enabled = false
        XCTAssertEqual(Builder.make(value).state, .notProtected)
    }

    func testTimeUncertaintyDegradesWithoutAnEnforcementClaim() {
        var value = inputs(rules: [rule()])
        for verdict in [FlintTimeIntegrity.Verdict.unverifiable, .wallAhead(seconds: 10), .wallBehind(seconds: 10)] {
            value.timeIntegrity = verdict
            let result = Builder.make(value)
            XCTAssertEqual(result.state, .degraded)
            XCTAssertTrue(result.degradedReasons.contains("time_uncertain"))
            XCTAssertNil(result.headline)
        }
    }

    func testSessionAppearsOnlyWhenArmedAndActive() {
        var value = inputs()
        value.activeSession = session()
        var result = Builder.make(value)
        XCTAssertEqual(result.state, .protected)
        XCTAssertEqual(result.session?.id, "session")
        XCTAssertEqual(result.session?.origin, .appUI)
        XCTAssertEqual(result.validUntil, now.addingTimeInterval(300))
        value.activeSession?.armed = false
        result = Builder.make(value)
        XCTAssertNil(result.session)
        XCTAssertNil(result.headline)
        XCTAssertEqual(result.state, .degraded)
        XCTAssertEqual(result.causes.map(\.id), ["session"])
        XCTAssertEqual(result.causes.first?.kind, .session)
        XCTAssertEqual(result.causes.first?.enforcing, false)
        value.activeSession = session(end: 0)
        result = Builder.make(value)
        XCTAssertNil(result.session)
        XCTAssertTrue(result.causes.isEmpty)
        XCTAssertEqual(result.state, .notProtected)
    }

    func testPomodoroOriginPlannedRestAndPass() {
        var value = inputs()
        value.activeSession = session()
        value.activeSession?.kind = .pomodoro
        value.activeSession?.origin = .shortcut
        value.activeSession?.enforcing = false
        var result = Builder.make(value)
        XCTAssertEqual(result.session?.kind, .pomodoro)
        XCTAssertEqual(result.session?.origin, .shortcut)
        XCTAssertEqual(result.causes.first?.kind, .pomodoro)
        XCTAssertEqual(result.state, .notProtected)
        value.pass = .init(eligibility: .coolingDown, recoveryUntil: now.addingTimeInterval(120))
        result = Builder.make(value)
        XCTAssertEqual(result.state, .suspended)
        XCTAssertNil(result.session)
        XCTAssertEqual(result.validUntil, now.addingTimeInterval(120))
    }

    func testHeadlineOrdersTierThenLatestEndThenStableIDWithoutLosingContributors() {
        let rules = [rule("z", tier: .hardcore, end: 800), rule("a", tier: .hardcore, end: 800),
                     rule("short", tier: .hardcore, end: 200), rule("long", tier: .harder, end: 900),
                     rule("easy", end: 1000)]
        let result = Builder.make(inputs(rules: rules))
        XCTAssertEqual(result.causes.map(\.id), ["a", "z", "short", "long", "easy"])
        XCTAssertEqual(result.headline?.id, "a")
        var value = inputs(rules: [rule("finite", tier: .harder, end: 3600)])
        value.activeSession = session(end: nil)
        XCTAssertEqual(Builder.make(value).headline?.id, "session")
    }

    func testUnarmedStrongerCauseDoesNotBecomeHeadline() {
        var strong = rule("failed", tier: .hardcore)
        strong.armed = false
        let result = Builder.make(inputs(rules: [rule("working"), strong]))
        XCTAssertEqual(result.state, .degraded)
        XCTAssertEqual(result.causes.map(\.id), ["failed", "working"])
        XCTAssertEqual(result.headline?.id, "working")
    }

    func testTimeLimitRequiresKnownReachedThresholdAndOpenLimitCoversBeforeExhaustion() {
        var value = inputs(rules: [rule(kind: .timeLimit)])
        for flag: Bool? in [nil, false] {
            value.rules[0].thresholdReached = flag
            XCTAssertEqual(Builder.make(value).state, .notProtected)
            XCTAssertFalse(Builder.make(value).causes[0].enforcing)
        }
        value.rules[0].thresholdReached = true
        XCTAssertEqual(Builder.make(value).state, .protected)
        value.rules[0].kind = .openLimit
        value.rules[0].thresholdReached = nil
        XCTAssertEqual(Builder.make(value).state, .protected)
        value.rules[0].enforcing = false
        XCTAssertEqual(Builder.make(value).state, .notProtected)
    }

    func testValidityUsesEarliestBoundaryAndOccurrenceTieBreak() {
        var a = rule("a")
        a.nextWindow = DateInterval(start: now.addingTimeInterval(50), end: now.addingTimeInterval(200))
        var z = rule("z")
        z.nextWindow = a.nextWindow
        var value = inputs(rules: [z, a])
        value.activeSession = session()
        var result = Builder.make(value)
        XCTAssertEqual(result.validUntil, now.addingTimeInterval(50))
        XCTAssertEqual(result.nextOccurrence?.causeID, "a")
        value.rules[0].nextBoundary = now.addingTimeInterval(20)
        result = Builder.make(value)
        XCTAssertEqual(result.validUntil, now.addingTimeInterval(20))
        XCTAssertEqual(Builder.make(inputs()).validUntil, now.addingTimeInterval(24 * 3600))
    }

    func testDailyLimitValidityUsesQuotaMidnightAnd24HourCapAcrossDST() {
        var value = inputs(rules: [rule(kind: .openLimit)])
        value.quotaCalendar.timeZone = TimeZone(identifier: "America/New_York")!
        let formatter = ISO8601DateFormatter()
        value.now = formatter.date(from: "2026-03-08T05:00:00Z")!
        XCTAssertEqual(Builder.make(value).validUntil, formatter.date(from: "2026-03-09T04:00:00Z"))
        value.now = formatter.date(from: "2026-11-01T04:00:00Z")!
        XCTAssertEqual(Builder.make(value).validUntil, value.now.addingTimeInterval(24 * 3600))
    }

    func testReaderRejectsMissingCorruptNewerFutureExpiredAndUnavailable() throws {
        let store = defaults()
        XCTAssertNil(FlintProtectionStatus.load(now: now, from: store))
        store.set(Data("broken".utf8), forKey: FlintProtectionStatus.storageKey)
        XCTAssertNil(FlintProtectionStatus.load(now: now, from: store))
        let valid = Builder.make(inputs(rules: [rule()]))
        FlintProtectionStatus.publish(valid, to: store, effects: quiet)
        XCTAssertEqual(FlintProtectionStatus.load(now: now, from: store), published(valid))
        let data = try XCTUnwrap(store.data(forKey: FlintProtectionStatus.storageKey))
        let original = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for (key, replacement) in [("version", 2), ("observedAt", now.timeIntervalSince1970 + 1),
                                   ("validUntil", now.timeIntervalSince1970 - 1)] as [(String, Any)] {
            var json = original
            json[key] = replacement
            store.set(try JSONSerialization.data(withJSONObject: json), forKey: FlintProtectionStatus.storageKey)
            XCTAssertNil(FlintProtectionStatus.load(now: now, from: store))
        }
        var value = inputs(); value.available = false
        FlintProtectionStatus.publish(Builder.make(value), to: store, effects: quiet)
        XCTAssertNil(FlintProtectionStatus.load(now: now, from: store))
    }

    func testDatesUseSecondsSince1970AndExpiryIsInclusive() throws {
        let store = defaults()
        let snapshot = Builder.make(inputs(rules: [rule()]))
        FlintProtectionStatus.publish(snapshot, to: store, effects: quiet)
        let data = try XCTUnwrap(store.data(forKey: FlintProtectionStatus.storageKey))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["observedAt"] as? Double, now.timeIntervalSince1970)
        XCTAssertEqual(json["validUntil"] as? Double, now.timeIntervalSince1970 + 600)
        XCTAssertNotNil(FlintProtectionStatus.load(now: now.addingTimeInterval(600), from: store))
        XCTAssertNil(FlintProtectionStatus.load(now: now.addingTimeInterval(601), from: store))
    }

    func testReaderRejectsOversizedBlobBeforeDecoding() throws {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .secondsSince1970
        let valid = try encoder.encode(Builder.make(inputs()))
        let oversized = valid + Data(repeating: 32, count: 64 * 1024)
        XCTAssertNil(FlintProtectionStatus.decode(oversized, now: now))
    }

    func testPublisherWritesBeforeWidgetReloadAndDarwinNotification() {
        let store = defaults()
        let snapshot = Builder.make(inputs())
        var calls: [String] = []
        let effects = FlintProtectionStatus.PublicationEffects(reloadTimelines: { kind in
            XCTAssertEqual(FlintProtectionStatus.load(now: self.now, from: store), self.published(snapshot))
            calls.append(kind)
        }, postDarwinNotification: { name in
            XCTAssertEqual(calls, ["FlintProtectionWidget"])
            calls.append(name)
        })
        FlintProtectionStatus.publish(snapshot, to: store, effects: effects)
        XCTAssertEqual(calls, ["FlintProtectionWidget", "com.flint.peakfocus.status.changed"])
        calls = []
        FlintProtectionStatus.publish(snapshot, to: nil, effects: effects)
        XCTAssertTrue(calls.isEmpty)
    }

    func testEveryPublicationWriterIsStampedWithoutChangingProjection() {
        let store = defaults()
        let snapshot = Builder.make(inputs())
        for writer in [FlintProtectionStatus.Writer.host, .monitor, .shieldAction] {
            FlintProtectionStatus.publish(snapshot, to: store, writer: writer, effects: quiet)
            XCTAssertEqual(FlintProtectionStatus.load(now: now, from: store),
                           published(snapshot, writer: writer.rawValue))
        }
    }

    func testContributorsAreTargetScopedOrderedAndReturnSharedMinimumOpenCap() {
        var a = rule("open-a", kind: .openLimit); a.opensAllowed = 5
        var b = rule("open-b", tier: .harder, kind: .openLimit); b.opensAllowed = 2
        let value = inputs(rules: [a, b, rule("unrelated", tier: .hardcore)])
        let counts = FlintOpenLimitState(dayStart: now, counts: ["opaque-target": 1])
        let result = FlintProtectionStatus.contributors(for: "opaque-target", inputs: value,
            coverageByCauseID: ["open-a": .only(["opaque-target"]),
                                "open-b": .allExcept(["allowed-target"]),
                                "unrelated": .only(["another-target"])], counterState: counts)
        XCTAssertEqual(result.causes.map(\.id), ["open-b", "open-a"])
        XCTAssertEqual(result.openCount, 1)
        XCTAssertEqual(result.openCap, 2)
        XCTAssertFalse(result.coverageUncertain)
        XCTAssertEqual(counts.counts["opaque-target"], 1)
    }

    func testUnknownCategoryAndCounterStayUnknownAndQueryNeverWrites() {
        var open = rule("open", kind: .openLimit); open.opensAllowed = 2
        let value = inputs(rules: [open, rule("category")])
        let result = FlintProtectionStatus.contributors(for: "opaque", inputs: value,
            coverageByCauseID: ["open": .only(["opaque"]), "category": .unknown])
        XCTAssertTrue(result.coverageUncertain)
        XCTAssertNil(result.openCount)
        XCTAssertEqual(result.causes.map(\.id), ["open"])
        let stale = FlintOpenLimitState(dayStart: now.addingTimeInterval(-86400), counts: ["opaque": 4])
        XCTAssertNil(FlintProtectionStatus.contributors(for: "opaque", inputs: value,
            coverageByCauseID: ["open": .only(["opaque"])], counterState: stale).openCount)
        var unavailable = value; unavailable.available = false
        XCTAssertTrue(FlintProtectionStatus.contributors(for: "opaque", inputs: unavailable,
            coverageByCauseID: [:]).causes.isEmpty)
    }

    func testUncoveredTargetIsNotProtectedAndUnrelatedOpenCapIsExcluded() {
        var covered = rule("covered", kind: .openLimit); covered.opensAllowed = 5
        var unrelated = rule("unrelated", kind: .openLimit); unrelated.opensAllowed = 1
        let value = inputs(rules: [covered, unrelated])
        let scopes: [String: FlintProtectionStatus.TargetScope] = [
            "covered": .only(["target"]), "unrelated": .allExcept(["target"])
        ]
        let result = FlintProtectionStatus.contributors(for: "target", inputs: value, coverageByCauseID: scopes)
        XCTAssertEqual(result.causes.map(\.id), ["covered"])
        XCTAssertEqual(result.openCap, 5)
        let uncovered = FlintProtectionStatus.contributors(for: "target", inputs: value,
            coverageByCauseID: ["covered": .allExcept(["target"]), "unrelated": .only(["other"])])
        XCTAssertEqual(uncovered.state, .notProtected)
        XCTAssertTrue(uncovered.causes.isEmpty)
        XCTAssertNil(uncovered.openCap)
    }

    func testUnspentTargetBudgetDoesNotInheritProtectionFromAnUnrelatedCause() {
        var budget = rule("budget", kind: .timeLimit); budget.thresholdReached = false
        let value = inputs(rules: [budget, rule("elsewhere")])
        XCTAssertEqual(Builder.make(value).state, .protected)
        let result = FlintProtectionStatus.contributors(for: "target", inputs: value,
            coverageByCauseID: ["budget": .only(["target"]), "elsewhere": .only(["other"])])
        XCTAssertEqual(result.causes.map(\.id), ["budget"])
        XCTAssertEqual(result.state, .notProtected)
        XCTAssertFalse(result.causes[0].enforcing)
    }

    func testUnknownTargetBudgetExhaustionRemainsUncertainWithoutClaimingEnforcement() {
        var budget = rule("budget", kind: .timeLimit); budget.thresholdReached = nil
        for rules in [[budget], [budget, rule("elsewhere")]] {
            let value = inputs(rules: rules)
            let result = FlintProtectionStatus.contributors(for: "target", inputs: value,
                coverageByCauseID: ["budget": .only(["target"]), "elsewhere": .only(["other"])])
            XCTAssertEqual(result.causes.map(\.id), ["budget"])
            XCTAssertEqual(result.state, .degraded)
            XCTAssertTrue(result.coverageUncertain)
            XCTAssertFalse(result.causes[0].enforcing)
            XCTAssertFalse(Builder.make(value).causes.first { $0.id == "budget" }!.enforcing)
            let uncovered = FlintProtectionStatus.contributors(for: "uncovered", inputs: value,
                coverageByCauseID: ["budget": .only(["target"]), "elsewhere": .only(["other"])])
            XCTAssertEqual(uncovered.state, .notProtected)
            XCTAssertFalse(uncovered.coverageUncertain)
        }
    }

    func testProjectionDoesNotEncodeSelectionsMonitorNamesOrCallerTargetKeys() throws {
        let tokenBytes = Data("fictional-selection-token-sentinel".utf8)
        let tokenJSON = try JSONSerialization.data(withJSONObject: ["data": tokenBytes.base64EncodedString()])
        let token = try JSONDecoder().decode(ApplicationToken.self, from: tokenJSON)
        var selection = FamilyActivitySelection(includeEntireCategory: true)
        selection.applicationTokens = [token]
        let saved = FlintScheduleRule(id: "schedule", name: "Study",
            schedule: FlintSchedule(startHour: 0, endHour: 23, endMinute: 59), selection: selection)
        let projected = Builder.RuleInput.schedule(saved, armed: true, observed: true,
                                                   now: now, calendar: calendar)
        var value = inputs(rules: [projected]); value.activeSession = session()
        let snapshot = Builder.make(value)
        _ = FlintProtectionStatus.contributors(for: "secret-token-hash", inputs: value,
            coverageByCauseID: ["schedule": .only(["secret-token-hash"])])
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .secondsSince1970
        let data = try encoder.encode(snapshot)
        let text = String(decoding: data, as: UTF8.self)
        for forbidden in ["selection", "applicationTokens", "categoryTokens", "webDomainTokens", "includeEntireCategory",
                          tokenBytes.base64EncodedString(), "secret-token-hash", "private-monitor-token"] {
            XCTAssertFalse(text.contains(forbidden), forbidden)
        }
        XCTAssertNil(data.range(of: try encoder.encode(selection)))
    }

    func testLedgerPassProjectionUsesElapsedRemaindersAndKeepsUnknownUnavailable() throws {
        let instant = FlintLedgerInstant(wall: now, monotonic: 100, bootID: "boot")
        var ledger = FlintLedgerState(initializedAt: now)
        XCTAssertEqual(Snapshot.Pass(ledger.pass, at: instant, hasActiveCommitment: true).eligibility, .unavailable)
        ledger.pass.usage = .neverUsed
        XCTAssertEqual(Snapshot.Pass(ledger.pass, at: instant, hasActiveCommitment: false).eligibility, .nothingToRecover)
        _ = try ledger.consumePass(operationID: "pass", hasActiveCommitment: true, at: instant).get()
        let later = FlintLedgerInstant(wall: now.addingTimeInterval(600), monotonic: 700, bootID: "boot")
        let pass = Snapshot.Pass(ledger.pass, at: later, hasActiveCommitment: true)
        XCTAssertEqual(pass.eligibility, .coolingDown)
        XCTAssertEqual(pass.nextAvailableAt, now.addingTimeInterval(604800))
        XCTAssertEqual(pass.recoveryUntil, now.addingTimeInterval(3600))
        let reboot = FlintLedgerInstant(wall: now.addingTimeInterval(600), monotonic: 10, bootID: "new-boot")
        XCTAssertNil(Snapshot.Pass(ledger.pass, at: reboot, hasActiveCommitment: true).recoveryUntil)
    }
}
