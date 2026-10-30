import XCTest
import FamilyControls
import ManagedSettings
@testable import FlintCore
@testable import Flint

final class FlintOrdinaryBreakNativeTests: XCTestCase {
    private let now = FlintLedgerInstant(wall: Date(timeIntervalSince1970: 1_800_000_000), monotonic: 1000, bootID: "boot")
    private func fixture(_ selection: FamilyActivitySelection, releasing scope: FlintBreakScope,
                         allowList: Bool = false, tier: BreakLevel = .easy, ownerEnd: TimeInterval? = 3600,
                         causeID: String = "session")
        throws -> (FlintOrdinaryGrantState.Record, FlintBreakContext) {
        let cause = FlintCauseState(causeID: causeID, kind: .session, tier: tier, isActive: true)
        let frozen = try FlintOrdinaryBreaks.scope(selection)
        let contributor = FlintBreakContributor(cause: cause,
            selection: allowList ? .allowList(allowedApplications: frozen.targets) : .blockList(frozen),
            logicalEnd: ownerEnd.map { .init(wall: now.wall.addingTimeInterval($0), monotonic: now.monotonic + $0, bootID: now.bootID) }, timeKnown: true, harder: .init())
        let duration = min(300, ownerEnd ?? 300)
        let grant = FlintBreakGrant(id: "grant", operationID: "operation", scope: scope, causes: [.init(cause)],
            acceptedAt: now, deadline: .init(wall: now.wall.addingTimeInterval(duration), monotonic: now.monotonic + duration, bootID: now.bootID))
        return (.init(grant: grant, authorities: [try .init(contributor: contributor, storeName: "owner")]),
                .init(owners: [.init(contributor, storeName: "owner")]))
    }

    func testNativeWriterKeepsBBlockedAndReshieldsAAtExactDeadline() throws {
        let a: ApplicationToken = try fictionalRuleToken("A")
        let b: ApplicationToken = try fictionalRuleToken("B")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a, b]
        var release = FamilyActivitySelection(); release.applicationTokens = [a]
        let (record, context) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(release))
        let writer = OrdinaryWriter()
        for (elapsed, expected) in [(299.0, Set([b])), (300.0, Set([a, b]))] {
            let observation = FlintLedgerInstant(wall: now.wall.addingTimeInterval(elapsed), monotonic: now.monotonic + elapsed, bootID: now.bootID)
            let filtered = FlintOrdinaryBreaks.filteredSelection(selection, storeName: "owner", records: [record],
                context: context, at: observation, registered: { _ in true })
            XCTAssertEqual(filtered.applicationTokens, expected)
            try FlintOrdinaryBreaks.write(.blockList(FlintOrdinaryBreaks.scope(selection)), storeName: "owner",
                releasing: context.authorizedTargets(for: record, at: observation), to: writer)
            XCTAssertEqual(writer.currentApplications, expected)
        }
        let unregistered = FlintOrdinaryBreaks.filteredSelection(selection, storeName: "owner", records: [record],
            context: context, at: now, registered: { _ in false })
        XCTAssertEqual(unregistered.applicationTokens, [a, b])
        let rule = FlintScheduleRule(name: "Fixture", schedule: .init(startHour: 0, startMinute: 0, endHour: 23, endMinute: 59), selection: selection)
        let live = FlintOrdinaryBreaks.filteredSelection(selection, storeName: "owner", records: [record],
            context: context, at: now, registered: { _ in true })
        FlintRuleShield.apply(rule, to: writer, selection: live)
        FlintRuleShield.add(rule, to: writer, selection: live)
        XCTAssertEqual(writer.currentApplications, [b], "Neither ordinary rule writer re-adds A before its deadline")
    }

    func testNativeAllowListReleaseAndCategoryDomainSubtraction() throws {
        let a: ApplicationToken = try fictionalRuleToken("A")
        let b: ApplicationToken = try fictionalRuleToken("B")
        var allowed = FamilyActivitySelection(); allowed.applicationTokens = [b]
        var releasing = FamilyActivitySelection(); releasing.applicationTokens = [a]
        let request = try FlintOrdinaryBreaks.scope(releasing)
        let (record, context) = try fixture(allowed, releasing: request, allowList: true)
        let writer = OrdinaryWriter()
        try FlintOrdinaryBreaks.write(.allowList(allowedApplications: FlintOrdinaryBreaks.scope(allowed).targets),
            storeName: "owner", releasing: context.authorizedTargets(for: record, at: now), to: writer)
        guard case let .all(except)? = writer.currentApplicationCategories else { return XCTFail("Expected allow list") }
        XCTAssertEqual(except, [a, b]); XCTAssertNil(writer.currentApplications)
        let c: ActivityCategoryToken = try fictionalRuleToken("C")
        let d: ActivityCategoryToken = try fictionalRuleToken("D")
        let site: WebDomainToken = try fictionalRuleToken("site")
        var selected = FamilyActivitySelection(); selected.categoryTokens = [c, d]; selected.webDomainTokens = [site]
        var removed = FamilyActivitySelection(); removed.categoryTokens = [c]; removed.webDomainTokens = [site]
        try FlintOrdinaryBreaks.write(.blockList(FlintOrdinaryBreaks.scope(selected)), storeName: "owner",
            releasing: FlintOrdinaryBreaks.scope(removed).targets, to: writer)
        guard case let .specific(categories, _)? = writer.currentApplicationCategories else { return XCTFail("Expected specific category") }
        XCTAssertEqual(categories, [d]); XCTAssertNil(writer.currentWebDomains)
        selected.categoryTokens = try Set((0...FlintShieldLimits.tokenCap).map { try fictionalRuleToken("category-\($0)") as ActivityCategoryToken })
        XCTAssertThrowsError(try FlintOrdinaryBreaks.write(.blockList(FlintOrdinaryBreaks.scope(selected)),
            storeName: "owner", releasing: [], to: writer))
    }

    func testStructuredPreviewOffersOnlyConfirmedOrWaitingActionsAndRefusesContentFilters() throws {
        let a: ApplicationToken = try fictionalRuleToken("A")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a]
        let scope = try FlintOrdinaryBreaks.scope(selection)
        for (tier, kind) in [(BreakLevel.easy, FlintOrdinaryBreakPreview.Kind.confirm), (.harder, .wait), (.hardcore, .deniedHardcore)] {
            let (_, context) = try fixture(selection, releasing: scope, tier: tier)
            let preview = FlintOrdinaryBreaks.preview(scope: scope, context: context, grants: nil, clock: now.clock)
            XCTAssertEqual(preview.kind, kind)
            XCTAssertEqual(preview.isActionable, tier != .hardcore)
        }
        XCTAssertFalse(FlintOrdinaryBreaks.preview(scope: scope, context: nil, grants: nil, clock: now.clock).isActionable)
        let (record, context) = try fixture(selection, releasing: scope)
        var grants = FlintOrdinaryGrantState(); grants.records = [record]
        XCTAssertEqual(FlintOrdinaryBreaks.preview(scope: scope, context: context, grants: grants, clock: now.clock).kind, .activeGrant)
        let site: WebDomainToken = try fictionalRuleToken("site")
        var domains = FamilyActivitySelection(); domains.webDomainTokens = [site]
        let domainScope = try FlintOrdinaryBreaks.scope(domains)
        let (domainRecord, domainContext) = try fixture(domains, releasing: domainScope)
        let filtered = FlintBreakContext(owners: domainContext.owners, unavailableKinds: [.webDomain, .category])
        XCTAssertEqual(FlintOrdinaryBreaks.preview(scope: domainScope, context: filtered, grants: nil, clock: now.clock).kind, .unavailable)
        XCTAssertEqual(FlintOrdinaryBreaks.filteredSelection(domains, storeName: "owner", records: [domainRecord],
            context: filtered, at: now, registered: { _ in true }).webDomainTokens, [site])
    }

    @MainActor
    func testSameSessionRefreshReloadsBreakStateAndShowsRestartNotice() throws {
        let a: ApplicationToken = try fictionalRuleToken("A")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [a]
        let (record, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(selection))
        let session = FlintActiveSession(name: "Focus", startedAt: now.wall, endsAt: now.wall.addingTimeInterval(3600),
            breakLevel: .easy, monitorName: "fixture")
        var status = FlintOrdinaryBreakStatus(record: record)
        let vm = SessionViewModel(initialSession: session, observeLifecycle: false, breakStatus: { status }, breakPreview: { .unavailable })
        vm.updateActive(session)
        XCTAssertEqual(vm.ordinaryBreak?.grant.id, record.grant.id)
        status = .init(notice: .reboot, noticeID: "fixture.reboot")
        vm.updateActive(session)
        XCTAssertNil(vm.ordinaryBreak)
        XCTAssertEqual(vm.breakNotice, "Access ended after restart; no extra credit was spent.")
        XCTAssertNil(vm.errorText)
        XCTAssertEqual(vm.active, session)
    }

    func testUnreadableRuleContextRestoresUntimedAndFutureOwnersButNeverExpiredOwners() throws {
        let token: ApplicationToken = try fictionalRuleToken("legacy session target")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [token]
        let observation = FlintLedgerInstant(wall: now.wall.addingTimeInterval(300), monotonic: now.monotonic + 300, bootID: now.bootID)
        for ownerEnd in [nil, 3600, 100] as [TimeInterval?] {
            let legacySession = FlintActiveSession(name: "Legacy", startedAt: now.wall,
                endsAt: ownerEnd.map { now.wall.addingTimeInterval($0) }, breakLevel: .easy, monitorName: "legacy")
            let (frozen, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(selection),
                ownerEnd: legacySession.endsAt.map { $0.timeIntervalSince(now.wall) }, causeID: legacySession.id)
            var grants = FlintOrdinaryGrantState()
            let operation = try grants.newOperationID()
            let grant = FlintBreakGrant(id: frozen.grant.id, operationID: operation, scope: frozen.grant.scope,
                causes: frozen.grant.causes, acceptedAt: frozen.grant.acceptedAt, deadline: frozen.grant.deadline)
            grants.records = [.init(grant: grant, authorities: frozen.authorities)]; grants.acceptedOperations = [operation]
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: directory) }
            let ledger = FlintProtectionLedger(directory: directory, clock: observation.clock, initializedMarker: { nil }, setInitializedMarker: { _ in })
            try ledger.transaction { $0.ordinaryGrants = grants }.get()
            let writer = OrdinaryWriter()
            let service = FlintOrdinaryBreakService(ledger: ledger, clock: observation.clock,
                transition: { body in body(); return true }, context: { _, _ in nil }, // unreadable rule domain
                register: { _, _ in XCTFail("Expired grant must not register") }, registered: { _ in true }, stop: { _ in },
                apply: { records, context, now in
                    XCTAssertNil(context)
                    try FlintOrdinaryBreaks.restoreUnavailableAuthorities(records, at: now, writer: { _ in writer })
                    throw FlintOrdinaryBreakError.unavailable // retain retry until the domain becomes readable
                })
            XCTAssertFalse(service.reconcile())
            XCTAssertEqual(writer.currentApplications, ownerEnd == 100 ? nil : Set([token]))
            XCTAssertEqual(try ledger.peek().get().ordinaryGrants?.records.first?.endReason, .deadline)
        }
    }

    func testFailureHealthPreservesOtherFailuresUpdatesTimeAndClearsRecoveredCarrier() throws {
        let suite = "flint.tests.breakHealth.\(UUID().uuidString)"
        let group = try XCTUnwrap(FlintGroupStore(suiteName: suite))
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        let unrelated = FlintArmingHealth.Failure(activityName: "unrelated", reason: "Keep this failure")
        let old = FlintArmingHealth.DomainReport(enabled: 2, attempted: 2, armed: 1, failures: [unrelated], updatedAt: .distantPast)
        group.saveArmingHealth(.init(domains: ["ordinaryBreaks": old, "schedules": old]))
        let carrier = FlintOrdinaryGrantState.Record.activityName("failed grant")
        let started = Date()
        FlintOrdinaryBreaks.recordHealthFailure(carrier, reason: "carrier failed", in: group)
        let failed = group.loadArmingHealth()
        XCTAssertEqual(failed.domains["schedules"], old)
        XCTAssertEqual(failed.domains["ordinaryBreaks"]?.failures.map(\.activityName), ["unrelated", carrier])
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(failed.domains["ordinaryBreaks"]?.updatedAt), started)
        FlintOrdinaryBreaks.clearHealthFailures(in: group)
        XCTAssertEqual(group.loadArmingHealth().domains["ordinaryBreaks"]?.failures, [unrelated])
        XCTAssertEqual(group.loadArmingHealth().domains["ordinaryBreaks"]?.enabled, 0)
        XCTAssertEqual(group.loadArmingHealth().domains["schedules"], old)
    }

    @MainActor
    func testNoticeAcknowledgementSurvivesViewRecreationAndDoesNotOverwriteErrors() {
        var acknowledgement: String?
        var status = FlintOrdinaryBreakStatus(notice: .reboot, noticeID: "grant.reboot")
        func model() -> SessionViewModel {
            SessionViewModel(observeLifecycle: false, breakStatus: { status }, breakPreview: { .unavailable },
                readNoticeAcknowledgement: { acknowledgement }, saveNoticeAcknowledgement: { acknowledgement = $0 })
        }
        let vm = model(); vm.errorText = "Unrelated save failed"
        vm.refreshBreakStatus(); vm.refreshBreakStatus()
        XCTAssertEqual(vm.errorText, "Unrelated save failed")
        XCTAssertEqual(vm.breakNotice, FlintBreakEndReason.reboot.message)
        vm.acknowledgeBreakNotice(); vm.refreshBreakStatus()
        XCTAssertNil(vm.breakNotice); XCTAssertEqual(acknowledgement, "grant.reboot")
        let reopened = model(); reopened.refreshBreakStatus(); XCTAssertNil(reopened.breakNotice)
        status = .init(notice: .carrierFailed, noticeID: "next.carrier_failed")
        reopened.refreshBreakStatus(); XCTAssertEqual(reopened.breakNotice, FlintBreakEndReason.carrierFailed.message)
    }

    @MainActor
    func testDeferredResumeShowsApplyingProtectionWithoutUnavailableError() throws {
        let token: ApplicationToken = try fictionalRuleToken("target")
        var selection = FamilyActivitySelection(); selection.applicationTokens = [token]
        let (record, _) = try fixture(selection, releasing: FlintOrdinaryBreaks.scope(selection))
        var status = FlintOrdinaryBreakStatus(record: record)
        let vm = SessionViewModel(observeLifecycle: false, breakStatus: { status }, breakPreview: { .unavailable },
            resumeOrdinaryBreak: { _ in status = .init(isRestoring: true); return .applying })
        vm.refreshBreakStatus(); vm.resumeBreakNow()
        XCTAssertTrue(vm.resumingBreak); XCTAssertNil(vm.errorText); XCTAssertNil(vm.ordinaryBreak)
        status = .init(); vm.refreshBreakStatus(); XCTAssertFalse(vm.resumingBreak)
    }
}

private final class OrdinaryWriter: FlintRuleShieldReading {
    var currentApplications: Set<ApplicationToken>?
    var currentApplicationCategories: ShieldSettings.ActivityCategoryPolicy<Application>?
    var currentWebDomains: Set<WebDomainToken>?
    var currentWebDomainCategories: ShieldSettings.ActivityCategoryPolicy<WebDomain>?
    func applications(_ tokens: Set<ApplicationToken>?) { currentApplications = tokens }
    func applicationCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<Application>?) { currentApplicationCategories = policy }
    func webDomains(_ tokens: Set<WebDomainToken>?) { currentWebDomains = tokens }
    func webDomainCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?) { currentWebDomainCategories = policy }
}
