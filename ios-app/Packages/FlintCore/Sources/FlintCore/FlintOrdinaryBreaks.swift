import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

public struct FlintOrdinaryBreakPreview: Equatable {
    public enum Kind { case confirm, wait, deniedHardcore, unavailable, activeGrant }
    public let kind: Kind
    public let message: String
    public var isActionable: Bool { kind == .confirm || kind == .wait }
    public static let unavailable = Self(kind: .unavailable, message: "Access unavailable")
}

public struct FlintOrdinaryBreakStatus {
    public let record: FlintOrdinaryGrantState.Record?
    public let notice: FlintBreakEndReason?
    public let noticeID: String?
    public let isRestoring: Bool
    public init(record: FlintOrdinaryGrantState.Record? = nil, notice: FlintBreakEndReason? = nil,
                noticeID: String? = nil, isRestoring: Bool = false) {
        self.record = record; self.notice = notice; self.noticeID = noticeID; self.isRestoring = isRestoring
    }
    static func project(_ grants: FlintOrdinaryGrantState?, at now: FlintLedgerInstant) -> Self {
        guard let grants else { return .init() }
        let restoring = grants.records.contains(where: { $0.endReason != nil && $0.applicationPending })
        let active = grants.records.first(where: { $0.remaining(at: now) > 0 })
        // A read can precede reconciliation after restart; display the same conservative
        // expiry result without requiring an operation replay or mutating protection.
        if let record = grants.records.last(where: {
            $0.endReason == .reboot || ($0.endReason == nil &&
                ($0.grant.acceptedAt.bootID != now.bootID || now.monotonic < $0.grant.acceptedAt.monotonic))
        }) {
            return .init(record: active, notice: .reboot, noticeID: record.grant.id + ".reboot", isRestoring: restoring || record.applicationPending)
        }
        // Later normal endings and new grants do not dismiss an unacknowledged notice.
        // Carrier-failure copy claims restoration, so expose it only after native apply.
        let receipt = grants.receipts.reversed().compactMap {
            try? FlintProtectionLedger.makeDecoder().decode(FlintOrdinaryGrantState.Receipt.self, from: $0)
        }.first(where: { [.reboot, .carrierFailed].contains($0.reason) })
        return .init(record: active, notice: receipt?.reason,
                     noticeID: receipt.map { $0.grantID + "." + $0.reason.rawValue }, isRestoring: restoring)
    }
}

/// Native ordinary-grant adapter. Opaque tokens stay local, canonically encoded; Safari
/// content filters are never cleared by a token grant whose domain coverage is unprovable.
public enum FlintOrdinaryBreaks {
    public static func isCarrier(_ name: String) -> Bool {
        FlintOrdinaryGrantState.isCarrier(name)
    }

    static func scope(_ selection: FamilyActivitySelection) throws -> FlintBreakScope {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        var targets = Set<FlintBreakScope.Target>()
        for token in selection.applicationTokens { targets.insert(.init(kind: .application, token: try encoder.encode(token))) }
        for token in selection.webDomainTokens { targets.insert(.init(kind: .webDomain, token: try encoder.encode(token))) }
        for token in selection.categoryTokens { targets.insert(.init(kind: .category, token: try encoder.encode(token))) }
        return .init(targets: targets)
    }

    static func requestForCurrentSession() -> FlintBreakOutcome {
        FlintSessionController().withTransition {
            guard let group = FlintGroupStore(), let session = group.loadActiveSession(),
                  let selection = group.loadSessionSelection(sessionID: session.id),
                  let scope = try? scope(selection), let service = service() else { return .refused(.unavailable) }
            return service.request(scope: scope)
        } ?? .refused(.unavailable)
    }

    public static func request(scope: FlintBreakScope, operationID: String? = nil) -> FlintBreakOutcome {
        service()?.request(scope: scope, operationID: operationID) ?? .refused(.unavailable)
    }
    public static func confirm(_ request: FlintBreakAuthorization.Request) -> FlintBreakOutcome {
        service()?.confirm(request) ?? .refused(.unavailable)
    }
    public static func cancel(_ request: FlintBreakAuthorization.Request) { service()?.cancel(request) }
    public static func resumeNow(grantID: String) -> Bool { service()?.resumeNow(grantID: grantID) ?? false }
    public static func resumeNowOutcome(grantID: String) -> FlintOrdinaryBreakResumeOutcome {
        service()?.resumeNowOutcome(grantID: grantID) ?? .unavailable
    }

    @discardableResult
    public static func reconcile(activity: String? = nil, callback: FlintOrdinaryBreakService.Callback? = nil) -> Bool {
        guard activity == nil || isCarrier(activity!) else { return false }
        return service()?.reconcile(activity: activity, callback: callback) ?? false
    }

    public static func activeRecord() -> FlintOrdinaryGrantState.Record? {
        status().record
    }

    public static func status() -> FlintOrdinaryBreakStatus {
        guard case let .success(state)? = FlintProtectionLedger.appGroup()?.peek() else { return .init() }
        return .project(state.ordinaryGrants, at: .init(clock: .system))
    }

    public static func previewForCurrentSession() -> FlintOrdinaryBreakPreview {
        guard case let .success(state)? = FlintProtectionLedger.appGroup()?.peek(),
              let group = FlintGroupStore(), let session = group.loadActiveSession(),
              let selection = group.loadSessionSelection(sessionID: session.id), let scope = try? scope(selection),
              let context = context(state, at: .init(clock: .system)),
              context.canRelease(scope) else { return .unavailable }
        return preview(scope: scope, context: context, grants: state.ordinaryGrants, clock: .system)
    }

    static func preview(scope: FlintBreakScope, context: FlintBreakContext?, grants: FlintOrdinaryGrantState?,
                        clock: FlintClock) -> FlintOrdinaryBreakPreview {
        guard let context, context.canRelease(scope) else { return .unavailable }
        let request = grants?.pending.flatMap { $0.scope == scope ? $0 : nil }
            ?? FlintBreakAuthorization.Request(operationID: "preview", scope: scope)
        let suffix = scope.wholeCategories.isEmpty ? "" : " Selected categories are released as whole categories."
        switch FlintBreakAuthorization.decide(contributors: context.contributors,
            existingGrants: grants?.records.filter { $0.endReason == nil }.map(\.grant) ?? [],
            request: request, clock: clock) {
        case .confirmEasy:
            guard grants?.canRequest ?? true else { return .init(kind: .unavailable, message: FlintOrdinaryBreakError.capacity.localizedDescription) }
            return .init(kind: .confirm, message: "Confirm up to 5 minutes of access to this session’s targets." + suffix)
        case let .wait(seconds, causes):
            guard grants?.canRequest ?? true else { return .init(kind: .unavailable, message: FlintOrdinaryBreakError.capacity.localizedDescription) }
            let binding = FlintBreakAuthorization.waitScope(request: request, contributors: context.contributors)
            let now = FlintLedgerInstant(clock: clock)
            let remaining = causes.map { cause -> TimeInterval in
                guard let harder = context.contributors.first(where: { $0.cause?.causeID == cause.causeID })?.harder else { return seconds }
                let total = FlintProtectionPolicy.harderWait(forCount: harder.committedCount)
                guard let pending = harder.pending, pending.operationID == request.operationID,
                      pending.kind == "break", pending.scope == binding, pending.wait.isFinite, pending.wait >= total,
                      let elapsed = pending.requestedAt.elapsed(until: now) else { return total }
                return max(0, pending.wait - elapsed)
            }.max() ?? seconds
            if remaining == 0 { return .init(kind: .confirm, message: "Your wait is complete. Confirm up to 5 minutes of access." + suffix) }
            return .init(kind: .wait, message: "Wait \(waitDescription(remaining)), then confirm up to 5 minutes of access." + suffix)
        case .deny(.hardcore): return .init(kind: .deniedHardcore, message: FlintOrdinaryBreakError.hardcore.localizedDescription)
        case let .deny(.activeGrant(left)): return .init(kind: .activeGrant, message: "A break is already active (\(Int(left)) seconds left)")
        case .deny(.unavailable), .replay, .replayExpired: return .unavailable
        }
    }

    static func waitDescription(_ remaining: TimeInterval) -> String {
        let seconds = Int(ceil(min(remaining, 86_400)))
        let minutes = seconds / 60, rest = seconds % 60
        var parts: [String] = []
        if minutes > 0 { parts.append("\(minutes) \(minutes == 1 ? "minute" : "minutes")") }
        if rest > 0 { parts.append("\(rest) \(rest == 1 ? "second" : "seconds")") }
        return parts.joined(separator: " ")
    }

    /// Single narrow hook used by policy materialization. Re-entrant on the owning thread;
    /// calling from a shield writer itself would recurse, so writers use filteredSelection.
    static func policyDidChange() {
        if case let .success(state)? = FlintProtectionLedger.appGroup()?.peek(),
           state.ordinaryGrants?.records.isEmpty ?? true,
           state.ordinaryGrants?.pending == nil { return }
        _ = reconcile()
    }

    static func filteredSelection(_ selection: FamilyActivitySelection, storeName: String,
                                  allowList: Bool = false) -> FamilyActivitySelection {
        guard case let .success(state)? = FlintProtectionLedger.appGroup()?.peek(),
              let grants = state.ordinaryGrants, grants.isValid else { return selection }
        let now = FlintLedgerInstant(clock: .system)
        guard let context = context(state, at: now) else { return selection }
        let center = DeviceActivityCenter()
        return filteredSelection(selection, storeName: storeName, allowList: allowList,
            records: grants.records, context: context, at: now,
            registered: { center.schedule(for: .init($0)) != nil })
    }

    static func filteredSelection(_ selection: FamilyActivitySelection, storeName: String, allowList: Bool = false,
                                  records: [FlintOrdinaryGrantState.Record], context: FlintBreakContext,
                                  at now: FlintLedgerInstant, registered: (String) -> Bool) -> FamilyActivitySelection {
        let allowed = records.reduce(into: Set<FlintBreakScope.Target>()) { result, record in
            guard record.authorities.contains(where: { $0.storeName == storeName }),
                  registered(record.activityName) else { return }
            result.formUnion(context.authorizedTargets(for: record, at: now))
        }
        return transform(selection, allowList: allowList, releasing: allowed)
    }

    static func transform(_ selection: FamilyActivitySelection, allowList: Bool,
                                  releasing targets: Set<FlintBreakScope.Target>) -> FamilyActivitySelection {
        var result = selection
        let decoder = JSONDecoder()
        let apps = Set(targets.filter { $0.kind == .application }.compactMap { try? decoder.decode(ApplicationToken.self, from: $0.token) })
        if allowList { result.applicationTokens.formUnion(apps) }
        else {
            result.applicationTokens.subtract(apps)
            result.webDomainTokens.subtract(targets.filter { $0.kind == .webDomain }.compactMap { try? decoder.decode(WebDomainToken.self, from: $0.token) })
            result.categoryTokens.subtract(targets.filter { $0.kind == .category }.compactMap { try? decoder.decode(ActivityCategoryToken.self, from: $0.token) })
        }
        return result
    }

    private static func service() -> FlintOrdinaryBreakService? {
        guard let ledger = FlintProtectionLedger.appGroup() else { return nil }
        let env = FlintRuleReloadEnvironment.live
        let center = DeviceActivityCenter()
        return .init(ledger: ledger, clock: .system, transition: { body in
            FlintSessionController().withTransition {
                guard FlintEmergencyPassController.prepareForPolicyMutation() else { return false }
                let acquired: Void? = FlintRuleStoreLock.withLock(environment: env, body)
                return acquired != nil
            } ?? false
        }, context: { state, now in context(state, at: now) }, register: { record, remaining in
            guard remaining > 0 else { throw FlintOrdinaryBreakError.unavailable }
            let plan = FlintOpenCreditCarrierPlan(start: Date(), remaining: remaining)
            try center.startMonitoring(.init(record.activityName), during: .init(intervalStart: plan.intervalStart,
                intervalEnd: plan.intervalEnd, repeats: false, warningTime: plan.warningTime))
            guard center.activities.contains(.init(record.activityName)), center.schedule(for: .init(record.activityName)) != nil else {
                throw FlintOrdinaryBreakError.unavailable
            }
        }, registered: { center.activities.contains(.init($0)) && center.schedule(for: .init($0)) != nil },
        stop: { FlintScheduling.stopMonitoring([$0]) }, apply: apply,
        gate: { FlintPassRecoveryGate.read().permitsApplication },
        reportFailure: { recordHealthFailure($0, reason: $1) }, reportRecovery: { clearHealthFailures(activeActivities: $0) })
    }

    static func recordHealthFailure(_ name: String, reason: String, in group: FlintGroupStore? = FlintGroupStore()) {
        guard let group else { return }
        let previous = group.loadArmingHealth().domains["ordinaryBreaks"]
        let failures = (previous?.failures ?? []).filter { $0.activityName != name } + [.init(activityName: name, reason: reason)]
        FlintArmingHealth.record(domain: "ordinaryBreaks", enabled: max(1, previous?.enabled ?? 0),
            attempted: max(1, previous?.attempted ?? 0), armed: previous?.armed ?? 0,
            failures: failures, in: group, observedActivities: previous?.observedActivities)
    }

    static func clearHealthFailures(activeActivities: [String] = [], in group: FlintGroupStore? = FlintGroupStore()) {
        guard let group else { return }
        var health = group.loadArmingHealth()
        guard let previous = health.domains["ordinaryBreaks"] else { return }
        let remainingFailures = previous.failures.filter { !isCarrier($0.activityName) }
        if activeActivities.isEmpty && remainingFailures.isEmpty {
            health.domains.removeValue(forKey: "ordinaryBreaks")
            group.saveArmingHealth(health)
            return
        }
        guard previous.failures.contains(where: { isCarrier($0.activityName) }) else { return }
        FlintArmingHealth.record(domain: "ordinaryBreaks", enabled: activeActivities.count, attempted: activeActivities.count,
            armed: activeActivities.count, failures: remainingFailures,
            in: group, observedActivities: activeActivities)
    }

    private static func context(_ state: FlintLedgerState, at now: FlintLedgerInstant) -> FlintBreakContext? {
        guard let defaults = UserDefaults.flintGroup, let group = FlintGroupStore(),
              group.isProtectionStateReadable(), FlintOrdinaryGrantState.validObservation(now),
              let schedules = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: defaults,
                  at: now.wall, migrating: false, ledgerState: { state }),
              let limits = FlintDesiredRules.load(FlintLimitRule.self, key: .limits, from: defaults,
                  at: now.wall, migrating: false, ledgerState: { state }),
              let opens = FlintDesiredRules.load(FlintOpenLimitRule.self, key: .openLimits, from: defaults,
                  at: now.wall, migrating: false, ledgerState: { state }),
              Set(schedules.map(\.id)).count == schedules.count, Set(limits.map(\.id)).count == limits.count,
              Set(opens.map(\.id)).count == opens.count,
              case let .available(retained) = FlintRetainedProtection.load(from: defaults), retained.isEmpty else { return nil }
        let clock = now.clock
        let raw = group.loadActiveSession()
        guard !FlintSessionAuthority.recordIsUnavailable(raw, recordPresent: group.hasActiveSessionRecord(),
            authority: state.sessionAuthority, authorityVersion: state.sessionAuthorityVersion, clock: clock),
            state.sessionAuthority?.latest?.phase != .installing else { return nil }
        var owners: [FlintBreakContext.Owner] = []
        if let session = FlintSessionAuthority.observedProjection(raw, authority: state.sessionAuthority, clock: clock),
           !session.isLogicallyExpired(clock: clock) {
            guard let selection = group.loadSessionSelection(sessionID: session.id), let frozen = try? scope(selection) else { return nil }
            let remaining = session.logicalRemaining(clock: clock)
            let end = remaining.map { FlintLedgerInstant(wall: now.wall.addingTimeInterval($0), monotonic: now.monotonic + $0, bootID: now.bootID) }
            owners.append(.init(.init(cause: .init(causeID: session.id, kind: .session, tier: session.breakLevel,
                isActive: true, end: session.stableLogicalEnd(clock: clock)), selection: .blockList(frozen),
                logicalEnd: end, timeKnown: remaining != nil || session.endsAt == nil,
                harder: state.harder[session.id] ?? .init()), storeName: "flint"))
        }
        func append<Rule: FlintGatedRule>(_ rule: Rule, enforcing: Bool = true) -> Bool {
            guard let frozen = try? scope(rule.selection) else { return false }
            let window = FlintRuleOccurrenceClock.current(for: rule, clock: clock)
            let trusted = FlintRuleOccurrenceClock.date(for: rule, clock: clock)
            guard rule.protection?.queuedChange.map({ !FlintRuleOccurrenceClock.isOver($0.window, for: rule, clock: clock) }) ?? true else { return false }
            let id = FlintRuleArbiter.causeID(rule, window: window)
            let end = window.map { FlintLedgerInstant(wall: now.wall.addingTimeInterval($0.end.timeIntervalSince(trusted)),
                monotonic: now.monotonic + $0.end.timeIntervalSince(trusted), bootID: now.bootID) }
            let isActive = window != nil && enforcing
            if isActive && (rule.protection?.timeAnchor?.isValid != true) { return false }
            let selection: FlintBreakContributor.Selection = rule.allowList
                ? .allowList(allowedApplications: frozen.targets.filter { $0.kind == .application }) : .blockList(frozen)
            owners.append(.init(.init(cause: .init(causeID: id, occurrenceID: window?.identity,
                revision: rule.protection?.activityRevision ?? rule.revision, kind: rule.kind,
                tier: rule.breakLevel, isActive: isActive, end: window?.end), selection: selection,
                logicalEnd: end, timeKnown: !isActive || end != nil, harder: state.harder[id] ?? .init()), storeName: rule.storeName))
            return true
        }
        for rule in schedules { guard append(rule) else { return nil } }
        for rule in limits {
            let window = FlintRuleOccurrenceClock.current(for: rule, clock: clock)
            let candidate = window.flatMap { try? FlintEmergencyPassScope.thresholdRecord(rule, window: .init(start: $0.start, end: $0.end)) }
            let trusted = FlintRuleOccurrenceClock.date(for: rule, clock: clock)
            let recorded = candidate.map { state.passLimitThresholds?[rule.id]?.provesExhaustion(of: $0, at: trusted) == true } ?? false
            let native = ManagedSettingsStore(named: .init(rule.storeName))
            let visible = native.shield.applications != nil || native.shield.applicationCategories != nil || native.shield.webDomains != nil
            let reached = rule.thresholdMinutes == 0 || recorded ||
                FlintLimitThresholds.hasReached(rule, at: now.wall, in: defaults, clock: clock)
            // Shield visibility alone disappears during the grant. Require durable threshold
            // evidence so expiry can still identify and restore this owner after process death.
            if visible && !reached { return nil }
            guard append(rule, enforcing: reached) else { return nil }
        }
        for rule in opens {
            // Existing Open credit exemptions remain separate authorities. Removing their
            // exact token from this contributor prevents charging for already-allowed access.
            let adjusted = FlintOpenCredit.shieldAdditions(for: rule,
                grants: state.openCredits.isValid ? state.openCredits.grants : [:], at: now,
                registered: { DeviceActivityCenter().schedule(for: .init($0)) != nil })
            guard append(adjusted) else { return nil }
        }
        // A domain token cannot prove membership in a content-filter exception. A known
        // content filter therefore contributes unknown domain/category coverage, failing
        // such requests closed while keeping application-only breaks usable.
        let storeNames = Set(owners.map(\.storeName)).union([FlintWebRestrictionLifecycle.legacyStoreName])
        let filterPresent = storeNames.contains { ManagedSettingsStore(named: .init($0)).webContent.blockedByFilter != nil }
        return .init(owners: owners, unavailableKinds: filterPresent ? [.webDomain, .category] : [])
    }

    private static func apply(_ records: [FlintOrdinaryGrantState.Record], _ context: FlintBreakContext?,
                              _ now: FlintLedgerInstant) throws {
        guard FlintPassRecoveryGate.read().permitsApplication else { return }
        let center = DeviceActivityCenter()
        if let context {
            let affectedStores = Set(records.flatMap { $0.authorities.map(\.storeName) })
            for owner in context.owners where owner.contributor.cause?.isActive == true && affectedStores.contains(owner.storeName) {
                guard let selection = owner.contributor.selection else { throw FlintOrdinaryBreakError.unavailable }
                var allowed = Set<FlintBreakScope.Target>()
                for record in records where record.authorities.contains(where: { $0.storeName == owner.storeName }) &&
                    center.schedule(for: .init(record.activityName)) != nil {
                    allowed.formUnion(context.authorizedTargets(for: record, at: now))
                }
                try write(selection, storeName: owner.storeName, releasing: allowed)
            }
        } else {
            try restoreUnavailableAuthorities(records, at: now)
            throw FlintOrdinaryBreakError.unavailable
        }
    }

    /// Restore the owner's frozen policy, including an existing untimed Easy commitment.
    /// An expired finite owner is never recreated; reboot re-anchoring belongs to session reconcile.
    static func restoreUnavailableAuthorities(_ records: [FlintOrdinaryGrantState.Record], at now: FlintLedgerInstant,
                                             writer: ((String) -> FlintRuleShieldWriting)? = nil) throws {
        for record in records {
            for owner in record.authorities where owner.logicalEnd == nil ||
                owner.logicalEnd.map({ $0.bootID == now.bootID && $0.monotonic > now.monotonic }) == true {
                let selection: FlintBreakContributor.Selection = owner.allowList
                    ? .allowList(allowedApplications: owner.selection.targets) : .blockList(owner.selection)
                try write(selection, storeName: owner.storeName, releasing: [], to: writer?(owner.storeName))
            }
        }
    }

    static func write(_ selection: FlintBreakContributor.Selection, storeName: String,
                      releasing targets: Set<FlintBreakScope.Target>, to writer: FlintRuleShieldWriting? = nil) throws {
        let decoder = JSONDecoder()
        let scope: FlintBreakScope; let allowList: Bool
        switch selection {
        case let .blockList(value): scope = value; allowList = false
        case let .allowList(allowed): scope = .init(targets: allowed); allowList = true
        }
        var native = FamilyActivitySelection()
        for target in scope.targets {
            switch target.kind {
            case .application: native.applicationTokens.insert(try decoder.decode(ApplicationToken.self, from: target.token))
            case .webDomain: native.webDomainTokens.insert(try decoder.decode(WebDomainToken.self, from: target.token))
            case .category: native.categoryTokens.insert(try decoder.decode(ActivityCategoryToken.self, from: target.token))
            }
        }
        native = transform(native, allowList: allowList, releasing: targets)
        guard FlintShieldLimits.withinCap(native.applicationTokens.count), FlintShieldLimits.withinCap(native.webDomainTokens.count),
              FlintShieldLimits.withinCap(native.categoryTokens.count) else {
            throw FlintOrdinaryBreakError.unavailable
        }
        let destination = writer ?? OrdinaryShieldWriter(store: ManagedSettingsStore(named: .init(storeName)))
        destination.applications(!allowList && !native.applicationTokens.isEmpty ? native.applicationTokens : nil)
        destination.applicationCategories(allowList ? .all(except: native.applicationTokens)
            : (native.categoryTokens.isEmpty ? nil : .specific(native.categoryTokens)))
        destination.webDomains(!allowList && !native.webDomainTokens.isEmpty ? native.webDomainTokens : nil)
        destination.webDomainCategories(allowList ? .all() : nil)
    }
}

private struct OrdinaryShieldWriter: FlintRuleShieldWriting {
    let store: ManagedSettingsStore
    func applications(_ tokens: Set<ApplicationToken>?) { store.shield.applications = tokens }
    func applicationCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<Application>?) { store.shield.applicationCategories = policy }
    func webDomains(_ tokens: Set<WebDomainToken>?) { store.shield.webDomains = tokens }
    func webDomainCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?) { store.shield.webDomainCategories = policy }
}
#endif
