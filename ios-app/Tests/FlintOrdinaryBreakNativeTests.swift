import XCTest
import FamilyControls
import ManagedSettings
@testable import FlintCore
@testable import Flint

final class FlintOrdinaryBreakNativeTests: XCTestCase {
    private let now = FlintLedgerInstant(wall: Date(timeIntervalSince1970: 1_800_000_000), monotonic: 1000, bootID: "boot")
    private func fixture(_ selection: FamilyActivitySelection, releasing scope: FlintBreakScope,
                         allowList: Bool = false, tier: BreakLevel = .easy)
        throws -> (FlintOrdinaryGrantState.Record, FlintBreakContext) {
        let cause = FlintCauseState(causeID: "session", kind: .session, tier: tier, isActive: true)
        let frozen = try FlintOrdinaryBreaks.scope(selection)
        let contributor = FlintBreakContributor(cause: cause,
            selection: allowList ? .allowList(allowedApplications: frozen.targets) : .blockList(frozen),
            logicalEnd: .init(wall: now.wall.addingTimeInterval(3600), monotonic: 4600, bootID: now.bootID), timeKnown: true, harder: .init())
        let grant = FlintBreakGrant(id: "grant", operationID: "operation", scope: scope, causes: [.init(cause)],
            acceptedAt: now, deadline: .init(wall: now.wall.addingTimeInterval(300), monotonic: 1300, bootID: now.bootID))
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
        status = .init(notice: .reboot)
        vm.updateActive(session)
        XCTAssertNil(vm.ordinaryBreak)
        XCTAssertEqual(vm.errorText, "Access ended after restart; no extra credit was spent.")
        XCTAssertEqual(vm.active, session)
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
