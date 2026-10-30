import XCTest
import FamilyControls
import DeviceActivity
@testable import FlintCore

final class FlintRuleInitializationTests: XCTestCase {
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
            XCTAssertNil(h.defaults.object(forKey: key.rawValue)); XCTAssertNil(h.defaults.object(forKey: key.marker))
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
        }
        XCTAssertEqual(FlintRuleArbiter(environment: h.environment).upsert(h.schedule(tier: .easy), creating: true), .applied)
        XCTAssertEqual(h.load(FlintScheduleRule.self).count, 1)
        XCTAssertEqual(h.load(FlintLimitRule.self), [])
        XCTAssertEqual(h.load(FlintOpenLimitRule.self), [])
    }

    func testR22AnyProtectionEvidenceWithoutReceiptMakesMissingStateUnavailable() throws {
        for evidence in [FlintRuleStoreKey.limits.marker, FlintProtectionLedger.initializedMarkerKey,
            "flint.activeSession", "flint.activeSession.selection", "flint.webRestrictions", "flint.sleepConfig", "external"] {
            let h = try RuleHarness(); defer { h.clean() }
            var env = h.environment
            if evidence == "external" { env.installationEvidence = { true } }
            else { h.defaults.set(Data("preserve raw evidence".utf8), forKey: evidence) }
            XCTAssertTrue(FlintRuleStoreLock.prepareInitialization(environment: env))
            XCTAssertNil(env.observedRules(FlintScheduleRule.self), evidence)
            XCTAssertNil(h.defaults.object(forKey: FlintRuleStoreKey.schedules.rawValue))
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
