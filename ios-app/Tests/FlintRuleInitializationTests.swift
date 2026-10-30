import XCTest
import FamilyControls
import DeviceActivity
import Darwin
@testable import FlintCore

final class FlintRuleInitializationTests: XCTestCase {
    func testR22PreparationLatchesEveryPresentLegacyDomainBeforeItsKeyAndActivityDisappear() throws {
        for lost in FlintRuleStoreKey.allCases {
            let h = try RuleHarness(); defer { h.clean() }
            var schedule = h.schedule(tier: .hardcore); schedule.protection = nil
            let limit = FlintLimitRule(id: "legacy", name: "Legacy", thresholdMinutes: 30)
            let open = FlintOpenLimitRule(id: "legacy", name: "Legacy", opensAllowed: 3)
            let sources: [FlintRuleStoreKey: Data] = [.schedules: try JSONEncoder().encode([schedule]),
                .limits: try JSONEncoder().encode([limit]), .openLimits: try JSONEncoder().encode([open])]
            for (key, data) in sources { h.defaults.set(data, forKey: key.rawValue) }
            h.center.held[.init(lost.prefix + "legacy")] = schedule.registration()
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
            let receipts = FlintRuleStoreKey.allCases.map { h.defaults.data(forKey: $0.ownership) }
            for key in FlintRuleStoreKey.allCases {
                XCTAssertNil(h.defaults.object(forKey: key.marker), "preparation cannot migrate policy ahead of credit recovery")
                XCTAssertNotNil(h.defaults.object(forKey: key.ownership))
                XCTAssertEqual(h.defaults.data(forKey: key.rawValue), sources[key])
            }
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
            XCTAssertEqual(FlintRuleStoreKey.allCases.map { h.defaults.data(forKey: $0.ownership) }, receipts,
                "ownership receipts are write-once")
            h.defaults.removeObject(forKey: lost.rawValue)
            h.center.held.removeAll()
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
            switch lost {
            case .schedules: XCTAssertNil(h.environment.observedRules(FlintScheduleRule.self))
            case .limits: XCTAssertNil(h.environment.observedRules(FlintLimitRule.self))
            case .openLimits: XCTAssertNil(h.environment.observedRules(FlintOpenLimitRule.self))
            }
            XCTAssertNil(h.defaults.object(forKey: lost.rawValue), "lost established state cannot become []")
            XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
        }
    }

    func testR22PreparationRecordsUnreadableDomainOwnershipWithoutTouchingItsSource() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let source = Data("future unreadable rules".utf8)
        h.defaults.set(source, forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.marker))
        XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.ownership))
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.rawValue + ".quarantine.adr012"))
        h.defaults.removeObject(forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertNil(h.environment.observedRules(FlintScheduleRule.self))
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.rawValue))
    }

    func testR22PreparingReceiptCompletesKnownEmptyAfterLaterLedgerSessionAndNativeEvidence() throws {
        for partial in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            h.defaults.set(Data("{\"schema\":1,\"phase\":\"preparing\"}".utf8), forKey: FlintRuleInitialization.receiptKey)
            if partial {
                for key in FlintRuleStoreKey.allCases {
                    h.defaults.set(Data("[]".utf8), forKey: key.rawValue)
                    h.defaults.set(Data("{\"schema\":1}".utf8), forKey: key.marker)
                }
            }
            h.defaults.set(h.now, forKey: FlintProtectionLedger.initializedMarkerKey)
            h.defaults.set(Data("later session evidence".utf8), forKey: "flint.activeSession")
            h.center.held[.init("flint.session.later")] = h.schedule(tier: .easy).registration()
            var env = h.environment; env.installationEvidence = { true }
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: env))
            XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
            for key in FlintRuleStoreKey.allCases {
                XCTAssertEqual(h.defaults.data(forKey: key.rawValue), Data("[]".utf8))
                XCTAssertNotNil(h.defaults.object(forKey: key.marker))
            }
            XCTAssertNotNil(h.defaults.object(forKey: "flint.activeSession"))
            XCTAssertNotNil(h.center.held[.init("flint.session.later")])
        }
    }

    func testArbitraryOperationIDTextCannotEstablishRuleDomainOwnership() throws {
        let h = try RuleHarness(); defer { h.clean() }
        _ = try h.ledger.transaction {
            $0.remember(.init(id: "arbitrary#" + FlintRuleStoreKey.schedules.rawValue + ":looks-like-policy",
                kind: "test", result: "ok", at: h.now))
        }.get()
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(h.environment.observedRules(FlintScheduleRule.self), [])
    }

    @MainActor
    func testBusyColdLaunchInitializationDefersWithoutWritingThenRetriesAfterUnlock() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let directory = try XCTUnwrap(h.environment.lockDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let fd = open(directory.appendingPathComponent("mutation.lock").path, O_CREAT | O_RDWR, 0o600)
        XCTAssertGreaterThanOrEqual(fd, 0)
        guard fd >= 0 else { return }
        defer { flock(fd, LOCK_UN); close(fd) }
        XCTAssertEqual(flock(fd, LOCK_EX | LOCK_NB), 0)
        XCTAssertFalse(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .absent)
        XCTAssertEqual(flock(fd, LOCK_UN), 0)
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
    }

    func testR22LegacyRegistrationMissingPreMarkerDomainNeverClearsOrRetires() throws {
        for (key, savedEvidence) in FlintRuleStoreKey.allCases.flatMap({ key in [false, true].map { (key, $0) } }) {
            let h = try RuleHarness(); defer { h.clean() }
            let name = key.prefix + "legacy"
            h.center.held[.init(name)] = h.schedule(tier: .hardcore).registration()
            if savedEvidence { h.defaults.set(Data("retained selection".utf8), forKey: "flint.selection") }
            switch key {
            case .schedules: _ = FlintSchedulesController(environment: h.environment).reload()
            case .limits: FlintLimitsController(environment: h.environment).reload()
            case .openLimits: FlintOpenLimitsController(environment: h.environment).reload()
            }
            XCTAssertNotNil(h.center.held[.init(name)])
            XCTAssertTrue(h.center.starts.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
            XCTAssertTrue(h.cleared.isEmpty)
            XCTAssertFalse(h.reports[key.domain]?.failures.isEmpty ?? true)
            XCTAssertNil(h.defaults.object(forKey: key.rawValue)); XCTAssertNotNil(h.defaults.object(forKey: key.marker))
            XCTAssertNil(h.defaults.object(forKey: FlintRuleInitialization.receiptKey))
        }
    }

    func testR22FreshInstallAfterOnboardingInitializesAllDomainsAndCanSaveFirstRule() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.defaults.set(true, forKey: "flint.onboarded")
        h.defaults.set(true, forKey: "flint.consent.accepted")
        h.defaults.set("large", forKey: "flint.ui.textSize")
        h.defaults.set(Data("history is not protection evidence".utf8), forKey: "flint.history.events")
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
        for key in FlintRuleStoreKey.allCases {
            XCTAssertEqual(h.defaults.data(forKey: key.rawValue), Data("[]".utf8))
            XCTAssertNotNil(h.defaults.data(forKey: key.marker))
            XCTAssertNil(h.defaults.object(forKey: key.ownership), "fresh setup must not manufacture legacy ownership")
        }
        XCTAssertEqual(FlintRuleArbiter(environment: h.environment).upsert(h.schedule(tier: .easy), creating: true), .applied)
        XCTAssertEqual(h.load(FlintScheduleRule.self).count, 1)
        XCTAssertEqual(h.load(FlintLimitRule.self), [])
        XCTAssertEqual(h.load(FlintOpenLimitRule.self), [])
    }

    func testR22OtherProtectionEvidenceDoesNotStrandUnusedDomains() throws {
        for evidence in [FlintRuleStoreKey.limits.marker, FlintProtectionLedger.initializedMarkerKey,
            "flint.activeSession", "flint.activeSession.selection", "flint.webRestrictions", "flint.sleepConfig", "external"] {
            let h = try RuleHarness(); defer { h.clean() }
            var env = h.environment
            if evidence == "external" { env.installationEvidence = { true } }
            else { h.defaults.set(Data("preserve raw evidence".utf8), forKey: evidence) }
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: env))
            XCTAssertEqual(env.observedRules(FlintScheduleRule.self), [], evidence)
            XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.rawValue))
            XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.marker))
            XCTAssertNil(h.defaults.object(forKey: FlintRuleInitialization.receiptKey))
            if evidence != "external" { XCTAssertEqual(h.defaults.data(forKey: evidence), Data("preserve raw evidence".utf8)) }
        }
    }

    func testR22PostMarkerMissingDomainRemainsUnavailableAndPreservesNativeState() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        h.defaults.removeObject(forKey: FlintRuleStoreKey.schedules.rawValue)
        _ = FlintSchedulesController(environment: h.environment).reload()
        XCTAssertNotNil(h.center.held[.init(rule.activityName)])
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.rawValue))
        XCTAssertFalse(h.reports["schedules"]?.failures.isEmpty ?? true)
    }

    func testR22LegacySessionWebAndLedgerUpgradeAllowsPassResumeOpenContextAndFirstRules() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let session = FlintActiveSession(id: "legacy", name: "Legacy session", startedAt: h.now.addingTimeInterval(-60),
            endsAt: h.now.addingTimeInterval(1800), breakLevel: .hardcore, monitorName: "flint.session.legacy")
        h.defaults.set(try JSONEncoder().encode(session), forKey: "flint.activeSession")
        h.defaults.set(h.now, forKey: FlintProtectionLedger.initializedMarkerKey)
        XCTAssertTrue(FlintWebRestrictions().save(to: h.defaults))
        h.center.held[.init(session.monitorName)] = h.schedule(tier: .hardcore).registration()
        h.center.held[.init("flint.open.credit-carrier")] = h.schedule(tier: .hardcore).registration()
        _ = try h.ledger.transaction { state in
            state.importLegacyOpenCredits(.absent, targetKeys: [], established: false,
                at: .init(clock: h.clock), calendar: h.clock.calendar())
        }.get()
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        for key in FlintRuleStoreKey.allCases {
            XCTAssertEqual(h.defaults.data(forKey: key.rawValue), Data("[]".utf8))
            XCTAssertNotNil(h.defaults.object(forKey: key.marker))
        }
        XCTAssertNil(h.defaults.object(forKey: FlintRuleInitialization.receiptKey), "upgrade is not a fresh install")
        var observation = FlintProtectionStatus.ObservationEnvironment.system
        observation.clock = h.clock; observation.loadActiveSession = { session }
        observation.hasActiveSessionRecord = { true }; observation.loadLedgerState = { try? h.ledger.peek().get() }
        let inputs = FlintProtectionStatus.currentInputs(now: h.now, defaults: h.defaults, environment: observation)
        XCTAssertFalse(inputs.recordUnreadable)
        let credit = FlintOpenCreditService(ledger: h.ledger, clock: h.clock, context: {
            guard FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: h.defaults,
                migrating: false) != nil else { return nil }
            return .init(coveringCaps: [:], targetKeys: [])
        }, register: { _, _ in }, registered: { _ in true }, release: { _ in }, reshield: { _ in }, stop: { _ in })
        XCTAssertEqual(credit.preview(target: "uncovered").blockedBy, .notOpenLimited)
        let pass = FlintEmergencyPassController(environment: .init(ledger: { h.ledger }, clock: h.clock,
            scope: { state, at in try FlintEmergencyPassScope.load(state: state, at: at,
                defaults: h.defaults, calendar: h.clock.calendar(), hasThresholdShield: { _ in false }) },
            registerCarrier: { _, _, _ in }, terminate: { _, _ in h.defaults.removeObject(forKey: "flint.activeSession") },
            release: { _ in }, resume: { state, at in try FlintEmergencyPassRuleEffects.resume(state: state, at: at,
                environment: h.environment, web: .init(writer: InitializationNoWebWriter(), clock: h.clock),
                existingWebStores: { false }) }, emit: { _ in }))
        let preview = try pass.preview().get()
        _ = try pass.use(previewRevision: preview.revision, operationID: "legacy-upgrade").get()
        _ = try pass.resumeNow().get()
        let arbiter = FlintRuleArbiter(environment: h.environment)
        XCTAssertEqual(arbiter.upsert(h.schedule(tier: .easy), creating: true), .applied)
        var limit = FlintLimitRule(id: "time", name: "Time", thresholdMinutes: 30, protection: .init(timeZoneID: "UTC"))
        limit.selection.applicationTokens = [try fictionalRuleToken("r22-first-time")]
        XCTAssertEqual(arbiter.upsert(limit, creating: true), .applied)
        var open = FlintOpenLimitRule(id: "open", name: "Open", opensAllowed: 3, protection: .init(timeZoneID: "UTC"))
        open.selection.applicationTokens = [try fictionalRuleToken("r22-first-open")]
        XCTAssertEqual(arbiter.upsert(open, creating: true), .applied)
    }

    func testR22MissingScheduleWithNativeEvidenceDoesNotBlockUnusedLimitDomains() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.center.held[.init("flint.rule.lost")] = h.schedule(tier: .hardcore).registration()
        let observation = FlintProtectionStatus.ObservationEnvironment(authorized: { true },
            registeredActivities: { Set(h.center.activities.map(\.rawValue)) }, clock: h.clock,
            loadActiveSession: { nil }, hasActiveSessionRecord: { false }, loadArmingHealth: { .init() },
            loadLedgerState: { nil })
        XCTAssertTrue(FlintProtectionStatus.currentInputs(now: h.now, defaults: h.defaults,
            environment: observation).recordUnreadable)
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.marker), "read-only evidence cannot initialize")
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertNil(h.environment.observedRules(FlintScheduleRule.self))
        XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.marker))
        XCTAssertEqual(h.environment.observedRules(FlintLimitRule.self), [])
        XCTAssertEqual(h.environment.observedRules(FlintOpenLimitRule.self), [])
        XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.limits.marker))
        XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.openLimits.marker))
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
        h.center.held.removeAll()
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertNil(h.environment.observedRules(FlintScheduleRule.self), "lost native evidence cannot mint an empty domain later")
    }

    func testR22SessionActivityAloneIsNotScheduleEvidence() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.center.held[.init("flint.session.only")] = h.schedule(tier: .hardcore).registration()
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(h.environment.observedRules(FlintScheduleRule.self), [])
        XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.marker))
        XCTAssertNotNil(h.center.held[.init("flint.session.only")])
    }

    func testR22LedgerRuleReferencesAreEvidenceOnlyForTheirOwnDomain() throws {
        for domain in FlintRuleStoreKey.allCases {
            let h = try RuleHarness(); defer { h.clean() }
            _ = try h.ledger.transaction { $0.harder[domain.rawValue + ":lost:legacy@old"] = .init() }.get()
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
            for key in FlintRuleStoreKey.allCases {
                if key == domain {
                    XCTAssertNil(h.defaults.object(forKey: key.rawValue)); XCTAssertNotNil(h.defaults.object(forKey: key.marker))
                } else {
                    XCTAssertEqual(h.defaults.data(forKey: key.rawValue), Data("[]".utf8))
                    XCTAssertNotNil(h.defaults.object(forKey: key.marker))
                }
            }
        }
    }

    func testR22OrphanedRecoveryFileKeepsOnlyItsDomainUnavailable() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let key = FlintRuleStoreKey.limits.original
        XCTAssertTrue(FlintRuleRecovery.retain(Data("saved Time Limit source".utf8), key: key,
            defaults: h.defaults, directory: h.recoveryDirectory))
        h.defaults.removeObject(forKey: key)
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertNil(h.environment.observedRules(FlintLimitRule.self))
        XCTAssertEqual(h.environment.observedRules(FlintScheduleRule.self), [])
        XCTAssertEqual(h.environment.observedRules(FlintOpenLimitRule.self), [])
        XCTAssertNotNil(h.defaults.object(forKey: FlintRuleStoreKey.limits.marker))
    }

    func testR22InterruptedEmptyInitializationResumesButUnknownReceiptDoesNotWrite() throws {
        let h = try RuleHarness(); defer { h.clean() }
        h.defaults.set(Data("{\"schema\":1,\"phase\":\"preparing\"}".utf8), forKey: FlintRuleInitialization.receiptKey)
        h.defaults.set(Data("[]".utf8), forKey: FlintRuleStoreKey.schedules.rawValue)
        h.defaults.set(Data("{\"schema\":1}".utf8), forKey: FlintRuleStoreKey.schedules.marker)
        XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
        let future = Data("{\"schema\":99,\"phase\":\"complete\"}".utf8)
        h.defaults.set(future, forKey: FlintRuleInitialization.receiptKey)
        let source = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertFalse(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertNil(h.environment.observedRules(FlintScheduleRule.self))
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleInitialization.receiptKey), future)
    }

    func testR22IncompleteReceiptCannotOverwriteANonemptyDomain() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let source = try JSONEncoder().encode([h.schedule(tier: .hardcore)])
        h.defaults.set(Data("{\"schema\":1,\"phase\":\"preparing\"}".utf8), forKey: FlintRuleInitialization.receiptKey)
        h.defaults.set(source, forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertFalse(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        XCTAssertNil(h.environment.observedRules(FlintScheduleRule.self))
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.limits.rawValue))
    }

    func testR22MissingSleepConfigCannotOverwriteSavedSleepRulesWithDefaultOff() throws {
        let h = try RuleHarness(); defer { h.clean() }
        var rule = h.schedule(tier: .easy); rule.id = "sleep.night"
        _ = h.install(rule)
        let source = h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue)
        XCTAssertEqual(FlintSleepRulesController(environment: h.environment).save(.default), .unavailable)
        XCTAssertEqual(h.defaults.data(forKey: FlintRuleStoreKey.schedules.rawValue), source)
        XCTAssertNil(h.defaults.object(forKey: FlintSleepRulesController.key))
        XCTAssertTrue(h.cleared.isEmpty); XCTAssertTrue(h.center.stops.isEmpty)
    }

    @MainActor
    func testR22HostPreparationPrecedesLayersThatInitializeTheLedger() async throws {
        let h = try RuleHarness(); defer { h.clean() }
        let reconciler = FlintProtectionReconciler(layers: [InitializationOrderLayer {
            XCTAssertEqual(FlintRuleInitialization.receipt(in: h.defaults), .complete)
            h.defaults.set(1, forKey: FlintProtectionLedger.initializedMarkerKey)
        }], persist: { _ in }, prepare: {
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: h.environment))
        })
        _ = await reconciler.reconcile(trigger: .launch)
    }
}

@MainActor
private struct InitializationOrderLayer: FlintProtectionLayer {
    let domain = "session"
    let body: () -> Void
    func reconcile(trigger: FlintProtectionTrigger) async throws -> FlintArmingHealth.DomainReport {
        body()
        return .init(enabled: 0, attempted: 0, armed: 0, failures: [])
    }
}

private struct InitializationNoWebWriter: FlintWebRestrictionWriting {
    func apply(_ config: FlintWebRestrictions, toStore storeName: String) {}
    func clear(store: String) {}
}
