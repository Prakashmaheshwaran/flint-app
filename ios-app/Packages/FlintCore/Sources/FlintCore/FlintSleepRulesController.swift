import Foundation
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
import FamilyControls

/// Sleep is a batch of rule mutations, never a separate way around the rule gate. Config
/// edits are drafts until this returns applied/queued; loading a screen does not re-save them.
public final class FlintSleepRulesController {
    public static let nightID = "sleep.night"
    public static let morningID = "sleep.morning"
    static let key = "flint.sleepConfig"
    private let env: FlintRuleReloadEnvironment
    public init(defaults: UserDefaults? = .flintGroup) {
        var env = FlintRuleReloadEnvironment.live; env.defaults = defaults; self.env = env
    }
    init(environment: FlintRuleReloadEnvironment) { env = environment }

    @discardableResult
    public func save(_ draft: FlintSleepConfig, confirmation: FlintRuleConfirmation? = nil,
                     origin: FlintRuleMutationOrigin = .background) -> FlintRuleMutationResult {
        FlintRuleStoreLock.withLock(environment: env) {
            guard draft.contractVersion == nil || draft.contractVersion == 12,
                  draft.revision.map({ $0 > 0 }) ?? true else { return .unavailable }
            guard let defaults = self.env.defaults,
                  let all = FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules,
                    from: defaults, at: self.env.clock.now()) else { return .unavailable }
            let previous: FlintSleepConfig?
            if let raw = defaults.object(forKey: Self.key) {
                guard let data = raw as? Data, let config = try? JSONDecoder().decode(FlintSleepConfig.self, from: data),
                      config.contractVersion == nil || config.contractVersion == 12,
                      (config.contractVersion == nil && config.revision == nil)
                        || (config.contractVersion == 12 && (config.revision ?? 0) > 0) else { return .unavailable }
                previous = config
            } else {
                guard defaults.object(forKey: Self.key + ".initialized") == nil else { return .unavailable }
                previous = nil
            }
            guard draft.revision == previous?.revision || previous == nil else { return .staleRevision }
            if draft.enabled, draft.sleepAssist != .off, let issue = draft.schedule.issues.first { return .invalid(issue.message) }
            let oldNight = all.first { $0.id == Self.nightID }
            let oldMorning = all.first { $0.id == Self.morningID }
            let legacy = draft.contractVersion == nil
            let nightTier = draft.nightBreakLevel ?? (legacy ? oldNight?.breakLevel : nil)
                ?? (legacy ? (draft.sleepAssist == .fullAssist ? .hardcore : .harder)
                    : (draft.sleepAssist == .fullAssist ? .harder : .easy))
            let morningTier = draft.morningBreakLevel ?? (legacy ? oldMorning?.breakLevel : nil) ?? (legacy ? .hardcore : .harder)
            let selection = FlintGroupStore()?.loadAppGroups().first { $0.id == draft.allowGroupID }?.selection ?? FamilyActivitySelection()
            var mutations: [FlintRuleArbiter.Mutation<FlintScheduleRule>] = []
            func add(_ id: String, _ old: FlintScheduleRule?, _ window: FlintSchedule?, _ tier: BreakLevel, _ name: String) {
                if let window {
                    let rule = FlintScheduleRule(id: id, name: name, schedule: window, breakLevel: tier,
                        selection: selection, allowListMode: true, protection: old?.protection ?? .init())
                    mutations.append(.init(id: id, proposed: rule, action: .edit, expectedRevision: old?.revision ?? 1))
                } else if let old { mutations.append(.init(id: id, proposed: nil, action: .delete, expectedRevision: old.revision)) }
            }
            add(Self.nightID, oldNight, draft.enabled && draft.sleepAssist != .off ? draft.schedule : nil, nightTier, "Sleep Mode")
            add(Self.morningID, oldMorning, Self.morningWindow(draft), morningTier, "Morning Assist")
            let context = [String(draft.revision ?? 0), draft.enabled.description, draft.sleepAssist.rawValue,
                draft.morningAssist.rawValue, draft.allowGroupID ?? "none", String(draft.morningAssistMinutes),
                nightTier.rawValue, morningTier.rawValue, draft.schedule.daysOfWeek.sorted().map(String.init).joined(separator: ",")]
                .joined(separator: "|")
            let result = mutations.isEmpty ? FlintRuleMutationResult.applied
                : FlintRuleArbiter(environment: self.env).mutate(mutations, confirmation: confirmation, context: context, origin: origin)
            guard result.didSave else { return result }
            var saved = draft
            saved.revision = (previous?.revision ?? 0) + 1; saved.contractVersion = 12
            saved.nightBreakLevel = nightTier; saved.morningBreakLevel = morningTier
            guard let data = try? JSONEncoder().encode(saved) else { return .unavailable }
            if previous?.contractVersion == nil, let original = defaults.data(forKey: Self.key),
               defaults.object(forKey: Self.key + ".original.adr012") == nil {
                defaults.set(original, forKey: Self.key + ".original.adr012")
            }
            defaults.set(data, forKey: Self.key)
            guard defaults.synchronize() else { return .unavailable }
            defaults.set(1, forKey: Self.key + ".initialized")
            return defaults.synchronize() ? result : .unavailable
        } ?? .unavailable
    }

    public func cancel(confirmation: FlintRuleConfirmation? = nil) {
        guard let confirmation else { return }
        let arbiter = FlintRuleArbiter(environment: env)
        _ = arbiter.cancel(FlintScheduleRule.self, id: Self.nightID, confirmation: confirmation)
        _ = arbiter.cancel(FlintScheduleRule.self, id: Self.morningID, confirmation: confirmation)
    }

    private static func morningWindow(_ config: FlintSleepConfig) -> FlintSchedule? {
        guard config.enabled, config.morningAssist == .fullAssist, config.schedule.hasValidEndpoints else { return nil }
        let wake = config.schedule.endMinuteOfDay
        let end = (wake + min(max(config.morningAssistMinutes, FlintSchedule.minimumWindowMinutes), 720)) % FlintSchedule.minutesPerDay
        let days = config.schedule.daysOfWeek
        let shifted = config.schedule.endMinuteOfDay <= config.schedule.startMinuteOfDay
        return .init(daysOfWeek: shifted ? Set(days.map { $0 % 7 + 1 }) : days,
            startHour: wake / 60, startMinute: wake % 60, endHour: end / 60, endMinute: end % 60)
    }
}
#endif
