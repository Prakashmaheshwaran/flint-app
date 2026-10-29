import Foundation
import CryptoKit
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
import FamilyControls
import DeviceActivity
import ManagedSettings

protocol FlintGatedRule: Codable, Equatable, Identifiable where ID == String {
    static var domain: FlintRuleStoreKey { get }
    var name: String { get }
    var enabled: Bool { get set }
    var breakLevel: BreakLevel { get }
    var selection: FamilyActivitySelection { get }
    var protection: FlintRuleProtection? { get set }
    var activityName: String { get }
    var storeName: String { get }
    var kind: FlintCauseState.Kind { get }
    var window: FlintSchedule? { get }
    var absoluteWindow: FlintRuleWindow? { get }
    var allowList: Bool { get }
    var budget: Int? { get }
    var legacyTierNeedsReview: Bool { get }
}

extension FlintGatedRule {
    static var storedKeys: Set<String> {
        let common: Set<String> = ["id", "name", "enabled", "breakLevel", "selection", "protection"]
        switch domain {
        case .schedules: return common.union(["schedule", "allowListMode", "oneShot"])
        case .limits: return common.union(["thresholdMinutes", "tierNeedsReview"])
        case .openLimits: return common.union(["opensAllowed", "tierNeedsReview"])
        }
    }
    var revision: Int { protection?.revision ?? 0 }
    var absoluteWindow: FlintRuleWindow? { nil }
    var allowList: Bool { false }
    var legacyTierNeedsReview: Bool { false }
    var zone: TimeZone { TimeZone(identifier: protection?.timeZoneID ?? TimeZone.current.identifier) ?? .current }
    var ruleCalendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }

    /// Current commitment, including an unspent budget / live credit. Never uses shield visibility.
    func occurrence(at now: Date) -> FlintRuleWindow? {
        guard enabled else { return nil }
        if let frozen = protection?.occurrence, frozen.contains(now) { return frozen }
        return templateOccurrence(at: now)
    }

    func templateOccurrence(at now: Date) -> FlintRuleWindow? {
        guard enabled else { return nil }
        if let absoluteWindow { return absoluteWindow.contains(now) ? absoluteWindow : nil }
        let calendar = ruleCalendar
        let today = calendar.startOfDay(for: now)
        guard let window else {
            guard let end = calendar.date(byAdding: .day, value: 1, to: today) else { return nil }
            return .init(start: today, end: end)
        }
        guard let interval = FlintScheduleWindow.resolve(window, at: now, calendar: calendar)?.current else { return nil }
        return .init(start: interval.start, end: interval.end)
    }

    func previewWindow(at now: Date) -> FlintRuleWindow? {
        guard enabled else { return nil }
        if let absoluteWindow { return absoluteWindow.end > now ? absoluteWindow : nil }
        if let current = templateOccurrence(at: now) { return current }
        guard let window, let next = FlintScheduleWindow.resolve(window, at: now, calendar: ruleCalendar)?.next else { return nil }
        return .init(start: next.start, end: next.end)
    }

    func snapshot(end: Date?) throws -> FlintProtectionSnapshot<String> {
        func keys<T: Encodable>(_ set: Set<T>) throws -> Set<String> {
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            return try Set(set.map { try encoder.encode($0).base64EncodedString() })
        }
        let mode: FlintProtectionSnapshot<String>.Mode
        if allowList { mode = .allowList(allowedApplications: try keys(selection.applicationTokens)) }
        else {
            mode = .blockList(applications: try keys(selection.applicationTokens),
                categories: try keys(selection.categoryTokens), webDomains: try keys(selection.webDomainTokens))
        }
        return .init(mode: mode, tier: breakLevel, end: end, budget: budget)
    }

    func impact(comparedTo old: Self, at now: Date) throws -> FlintEditImpact {
        // Opaque categories, mode flips and moved windows/zones cannot prove dominance (§2).
        if allowList != old.allowList || selection.categoryTokens != old.selection.categoryTokens
            || selection.includeEntireCategory != old.selection.includeEntireCategory
            || protection?.timeZoneID != old.protection?.timeZoneID {
            return .weaken
        }
        if absoluteWindow != old.absoluteWindow {
            guard let before = old.absoluteWindow, let after = absoluteWindow,
                  before.start == after.start, after.end >= before.end else { return .weaken }
        }
        if let a = old.window, let b = window, a != b {
            let oldDays = a.daysOfWeek.isEmpty ? Set(1...7) : a.daysOfWeek
            let newDays = b.daysOfWeek.isEmpty ? Set(1...7) : b.daysOfWeek
            guard newDays.isSuperset(of: oldDays), a.startMinuteOfDay == b.startMinuteOfDay,
                  b.windowMinutes >= a.windowMinutes else { return .weaken }
        } else if (old.window == nil) != (window == nil) { return .weaken }
        return FlintProtectionPolicy.editImpact(from: try old.snapshot(end: old.occurrence(at: now)?.end),
            to: try snapshot(end: templateOccurrence(at: now)?.end ?? old.occurrence(at: now)?.end))
    }

    func fingerprint(action: String, includeName: Bool = true) throws -> String {
        func keys<T: Encodable>(_ set: Set<T>) throws -> [String] {
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            return try set.map { try encoder.encode($0).base64EncodedString() }.sorted()
        }
        // Canonical arrays: encoding Swift Sets directly is process-order dependent.
        var parts = [action, id, includeName ? name : "", enabled.description, breakLevel.rawValue, allowList.description,
                     selection.includeEntireCategory.description,
                     protection?.timeZoneID ?? TimeZone.current.identifier, budget.map(String.init) ?? "none"]
        if let window {
            parts += [window.daysOfWeek.sorted().map(String.init).joined(separator: ","),
                      String(window.startMinuteOfDay), String(window.endMinuteOfDay)]
        }
        if let absoluteWindow {
            parts += [String(absoluteWindow.start.timeIntervalSince1970), String(absoluteWindow.end.timeIntervalSince1970)]
        }
        parts += ["applications"] + (try keys(selection.applicationTokens))
        parts += ["categories"] + (try keys(selection.categoryTokens))
        parts += ["domains"] + (try keys(selection.webDomainTokens))
        return SHA256.hash(data: try JSONEncoder().encode(parts)).map { String(format: "%02x", $0) }.joined()
    }

    var validationIssue: String? {
        guard !id.isEmpty, id.utf8.count <= 100 else { return "Invalid rule identity." }
        if let protection, !protection.isValid { return "Saved rule version is unavailable." }
        if let absoluteWindow, !absoluteWindow.isValid { return "Choose a finite window of at most 26 hours." }
        if let window, let issue = window.issues.first { return issue.message }
        if let budget, budget < 0 { return "The daily allowance cannot be negative." }
        if kind == .timeLimit, let budget, budget > Int.max / 60 { return "The daily allowance cannot be represented safely." }
        return nil
    }

    func registration() -> FlintActivityRegistration {
        let schedule: DeviceActivitySchedule
        if let absoluteWindow {
            var start = ruleCalendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: absoluteWindow.start)
            var end = ruleCalendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: absoluteWindow.end)
            start.timeZone = zone; end.timeZone = zone
            schedule = .init(intervalStart: start, intervalEnd: end, repeats: false)
        } else if let window {
            schedule = .init(intervalStart: .init(timeZone: zone, hour: window.startHour, minute: window.startMinute),
                intervalEnd: .init(timeZone: zone, hour: window.endHour, minute: window.endMinute), repeats: true)
        } else {
            schedule = .init(intervalStart: .init(timeZone: zone, hour: 0, minute: 0),
                intervalEnd: .init(timeZone: zone, hour: 23, minute: 59, second: 59), repeats: true)
        }
        var request = FlintActivityRegistration(name: activityName, schedule: schedule)
        if let limit = self as? FlintLimitRule {
            let event: DeviceActivityEvent
            if #available(iOS 17.4, *) {
                event = .init(applications: selection.applicationTokens, categories: selection.categoryTokens,
                    webDomains: selection.webDomainTokens, threshold: .init(minute: max(1, limit.thresholdMinutes)),
                    includesPastActivity: true)
            } else {
                event = .init(applications: selection.applicationTokens, categories: selection.categoryTokens,
                    webDomains: selection.webDomainTokens, threshold: .init(minute: max(1, limit.thresholdMinutes)))
            }
            request.events = [.init(limit.eventName): event]
        }
        return request
    }
}

extension FlintScheduleRule: FlintGatedRule {
    static var domain: FlintRuleStoreKey { .schedules }
    var activityName: String { monitorName }
    var kind: FlintCauseState.Kind { id.hasPrefix("sleep.") ? .sleep : (oneShot == nil ? .recurringSchedule : .oneShotSchedule) }
    var window: FlintSchedule? { schedule }
    var absoluteWindow: FlintRuleWindow? { oneShot }
    var allowList: Bool { allowListMode }
    var budget: Int? { nil }

    /// Display-only projection of the same saved-zone/frozen-window calculation used by the
    /// arbiter. Editors must still submit mutations to the controller for commit-time checks.
    public func currentOccurrence(at date: Date = Date()) -> FlintRuleWindow? { occurrence(at: date) }
}

extension FlintLimitRule: FlintGatedRule {
    static var domain: FlintRuleStoreKey { .limits }
    var kind: FlintCauseState.Kind { .timeLimit }
    var window: FlintSchedule? { nil }
    var budget: Int? { thresholdMinutes }
    var legacyTierNeedsReview: Bool { tierNeedsReview == true }
}

extension FlintOpenLimitRule: FlintGatedRule {
    static var domain: FlintRuleStoreKey { .openLimits }
    var kind: FlintCauseState.Kind { .openLimit }
    var window: FlintSchedule? { nil }
    var budget: Int? { opensAllowed }
    var legacyTierNeedsReview: Bool { tierNeedsReview == true }
}

/// Every write completely assigns the fields this store owns. A mode flip must not retain
/// .all() domains or old explicit app shields (R-35, PC-11/65).
enum FlintRuleShield {
    static func apply<Rule: FlintGatedRule>(_ rule: Rule) {
        _ = FlintSessionController().withTransition {
            guard FlintPassRecoveryGate.read().permitsApplication else { return }
            applyLocked(rule)
        }
    }

    private static func applyLocked<Rule: FlintGatedRule>(_ rule: Rule) {
        apply(rule, to: FlintManagedRuleShieldWriter(store: ManagedSettingsStore(named: .init(rule.storeName))))
        if let schedule = rule as? FlintScheduleRule {
            reconcileWeb(schedule, config: FlintWebRestrictions.loadForEnforcement(), at: Date(),
                writer: FlintManagedWebRestrictionWriter())
        }
    }

    static func reconcileWeb(_ rule: FlintScheduleRule, config: FlintWebRestrictions?, at now: Date,
                              writer: FlintWebRestrictionWriting) {
        guard let config, config.validationMessage == nil else { return }
        if config.applyDuringSchedules && rule.occurrence(at: now) != nil { writer.apply(config, toStore: rule.storeName) }
        else { writer.clear(store: rule.storeName) }
    }

    /// Without mutation authority, union restrictions; never clear a field or widen an
    /// exception. A concurrent newer revision may already have made the store stricter.
    static func add<Rule: FlintGatedRule>(_ rule: Rule) {
        _ = FlintSessionController().withTransition {
            guard FlintPassRecoveryGate.read().permitsApplication else { return }
            addLocked(rule)
        }
    }

    private static func addLocked<Rule: FlintGatedRule>(_ rule: Rule) {
        let store = ManagedSettingsStore(named: .init(rule.storeName))
        add(rule, to: FlintManagedRuleShieldWriter(store: store))
        if let schedule = rule as? FlintScheduleRule, schedule.occurrence(at: Date()) != nil,
           let config = FlintWebRestrictions.loadForEnforcement(), config.applyDuringSchedules,
           config.validationMessage == nil {
            if store.webContent.blockedByFilter == nil {
                store.webContent.blockedByFilter = FlintWebRestrictionStore.filterPolicy(for: config)
            }
            if config.denyExplicitMedia { store.media.denyExplicitContent = true }
        }
    }

    static func add<Rule: FlintGatedRule>(_ rule: Rule, to writer: FlintRuleShieldReading) {
        if rule.allowList {
            writer.applicationCategories(union(.all(except: rule.selection.applicationTokens), writer.currentApplicationCategories))
            writer.webDomainCategories(union(.all(), writer.currentWebDomainCategories))
        } else {
            if !rule.selection.applicationTokens.isEmpty {
                writer.applications((writer.currentApplications ?? []).union(rule.selection.applicationTokens))
            }
            if !rule.selection.categoryTokens.isEmpty {
                writer.applicationCategories(union(.specific(rule.selection.categoryTokens), writer.currentApplicationCategories))
            }
            if !rule.selection.webDomainTokens.isEmpty {
                writer.webDomains((writer.currentWebDomains ?? []).union(rule.selection.webDomainTokens))
            }
        }
    }

    private static func union<Activity>(_ addition: ShieldSettings.ActivityCategoryPolicy<Activity>,
                                        _ previous: ShieldSettings.ActivityCategoryPolicy<Activity>?)
        -> ShieldSettings.ActivityCategoryPolicy<Activity> {
        switch (previous, addition) {
        case (nil, _), (.some(.none), _): return addition
        case let (.some(.all(old)), .all(new)),
             let (.some(.all(old)), .specific(_, new)),
             let (.some(.specific(_, old)), .all(new)):
            return .all(except: old.intersection(new))
        case let (.some(.specific(old, oldExceptions)), .specific(new, newExceptions)):
            return .specific(old.union(new), except: oldExceptions.intersection(newExceptions))
        default: return .all() // unknown policy never proves that releasing a target is safe
        }
    }

    static func apply<Rule: FlintGatedRule>(_ rule: Rule, to writer: FlintRuleShieldWriting) {
        writer.applications(!rule.allowList && !rule.selection.applicationTokens.isEmpty ? rule.selection.applicationTokens : nil)
        writer.applicationCategories(rule.allowList ? .all(except: rule.selection.applicationTokens)
            : (rule.selection.categoryTokens.isEmpty ? nil : .specific(rule.selection.categoryTokens))
        )
        writer.webDomains(!rule.allowList && !rule.selection.webDomainTokens.isEmpty ? rule.selection.webDomainTokens : nil)
        writer.webDomainCategories(rule.allowList ? .all() : nil)
    }
}

protocol FlintRuleShieldWriting {
    func applications(_ tokens: Set<ApplicationToken>?)
    func applicationCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<Application>?)
    func webDomains(_ tokens: Set<WebDomainToken>?)
    func webDomainCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?)
}

protocol FlintRuleShieldReading: FlintRuleShieldWriting {
    var currentApplications: Set<ApplicationToken>? { get }
    var currentApplicationCategories: ShieldSettings.ActivityCategoryPolicy<Application>? { get }
    var currentWebDomains: Set<WebDomainToken>? { get }
    var currentWebDomainCategories: ShieldSettings.ActivityCategoryPolicy<WebDomain>? { get }
}

private struct FlintManagedRuleShieldWriter: FlintRuleShieldReading {
    let store: ManagedSettingsStore
    var currentApplications: Set<ApplicationToken>? { store.shield.applications }
    var currentApplicationCategories: ShieldSettings.ActivityCategoryPolicy<Application>? { store.shield.applicationCategories }
    var currentWebDomains: Set<WebDomainToken>? { store.shield.webDomains }
    var currentWebDomainCategories: ShieldSettings.ActivityCategoryPolicy<WebDomain>? { store.shield.webDomainCategories }
    func applications(_ tokens: Set<ApplicationToken>?) { store.shield.applications = tokens }
    func applicationCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<Application>?) { store.shield.applicationCategories = policy }
    func webDomains(_ tokens: Set<WebDomainToken>?) { store.shield.webDomains = tokens }
    func webDomainCategories(_ policy: ShieldSettings.ActivityCategoryPolicy<WebDomain>?) { store.shield.webDomainCategories = policy }
}

enum FlintRuleCleanup {
    static func clearRemoved<Rule: FlintGatedRule>(_ name: String, rules: [Rule], environment: FlintRuleReloadEnvironment) {
        guard let id = FlintRuleActivity.ruleID(name, prefix: Rule.domain.prefix) else { return }
        // Old and new revisions share a named store. Retiring the old registration must not
        // clear the successor's shield after arm-before-retire.
        if rules.contains(where: { $0.id == id && $0.enabled && ($0.absoluteWindow?.end ?? .distantFuture) > environment.clock.now() }) { return }
        let prefix: String
        switch Rule.domain { case .schedules: prefix = "flint."; case .limits: prefix = "flintL."; case .openLimits: prefix = "flintO." }
        environment.clearStore(prefix + id)
        if Rule.domain == .limits { environment.clearLimitThreshold(id) }
    }
}
#endif
