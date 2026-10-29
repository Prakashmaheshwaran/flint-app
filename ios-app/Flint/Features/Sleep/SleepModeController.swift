import Foundation
import FamilyControls
import FlintCore

/// Bridges `FlintSleepConfig` (the persisted Sleep Mode settings in FlintCore) to the real
/// blocking engine by materializing it into at most two well-known `FlintScheduleRule`s:
///
///  - `sleep.night`   — the bedtime → wake window (Sleep Assist: Wind Down / Full Assist).
///  - `sleep.morning` — the wake → wake + wind-up window (Morning Assist: Full Assist).
///
/// Saves both windows as one arbiter batch. Opening the screen does not overwrite rule edits;
/// active Hardcore changes queue behind their frozen occurrence, just like other schedules.
struct SleepModeController {

    /// Stable rule ids so apply/remove is idempotent. Store names become "flint.sleep.night" /
    /// "flint.sleep.morning" (via `FlintScheduleRule.storeName`), comfortably under the
    /// named-store limits, and monitor names "flint.rule.sleep.night" / "…morning" route through
    /// the extension's existing rule lookup untouched.
    static let nightRuleID = "sleep.night"
    static let morningRuleID = "sleep.morning"

    /// Defensive ceiling for the morning wind-up (the UI offers 15…180; the model default is 60).
    static let maximumMorningMinutes = 720

    private let rules = FlintSleepRulesController()

    init() {}

    // MARK: Materialization

    /// Explicit save only; every affected rule is decided again at commit time.
    func apply(_ config: FlintSleepConfig, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        rules.save(config, confirmation: confirmation, origin: .interactive)
    }

    /// Request removal through the same tier/confirmation gate as an explicit Sleep save.
    @discardableResult
    func removeAll(confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        var config = FlintSleepConfig.load(); config.enabled = false
        return rules.save(config, confirmation: confirmation, origin: .interactive)
    }

    func cancel(confirmation: FlintRuleConfirmation?) { rules.cancel(confirmation: confirmation) }

    // MARK: Window math (pure — shared by apply() and the view's status/summary rows)

    /// The bedtime window to enforce, or nil when Sleep Mode / Sleep Assist is off or the window
    /// isn't one `DeviceActivityCenter` will register (see `FlintSchedule.issues`).
    static func nightWindow(for config: FlintSleepConfig) -> FlintSchedule? {
        guard config.enabled, config.sleepAssist != .off, config.schedule.isValid else { return nil }
        return config.schedule
    }

    /// The post-wake wind-up window to enforce, or nil when Morning Assist isn't Full Assist.
    /// Independent of the night window: Morning Full Assist is meaningful even with Sleep Assist
    /// off (block the first hour of the day, nothing overnight). Derived by clock arithmetic from
    /// the wake time, so a config decoded with an out-of-range wake time arms nothing rather than
    /// producing a window with an hour of 30 for DeviceActivity to reject.
    static func morningWindow(for config: FlintSleepConfig) -> FlintSchedule? {
        guard config.enabled,
              config.morningAssist == .fullAssist,
              config.schedule.hasValidEndpoints else { return nil }
        let minutes = min(
            max(config.morningAssistMinutes, FlintSchedule.minimumWindowMinutes),
            maximumMorningMinutes
        )
        let wake = config.schedule.endMinuteOfDay
        let end = (wake + minutes) % FlintSchedule.minutesPerDay
        return FlintSchedule(
            daysOfWeek: morningDays(for: config.schedule),
            startHour: wake / 60,
            startMinute: wake % 60,
            endHour: end / 60,
            endMinute: end % 60
        )
    }

    /// The monitor gates a rule by the weekday its interval *starts* on. The night rule starts on
    /// the bedtime day; when the night window crosses midnight, the morning window starts the day
    /// after — so Sun–Thu nights guard Mon–Fri mornings (weekdays 1 = Sunday … 7 = Saturday,
    /// wrapping). A same-evening window (start < end) keeps the same days. Empty = every day.
    static func morningDays(for nightSchedule: FlintSchedule) -> Set<Int> {
        guard !nightSchedule.daysOfWeek.isEmpty else { return [] }
        // Wind-down that ends the same evening keeps its days; only a midnight crossing shifts them.
        guard nightSchedule.endMinuteOfDay <= nightSchedule.startMinuteOfDay else {
            return nightSchedule.daysOfWeek
        }
        return Set(nightSchedule.daysOfWeek.map { $0 % 7 + 1 })
    }

    /// Whether `schedule`'s window contains `date`, honoring the same day-of-week gate the
    /// monitor applies (the day the window started). Display-only — enforcement itself happens in
    /// the monitor extension on a real device.
    static func isWindowActive(
        _ schedule: FlintSchedule,
        at date: Date = Date(),
        calendar: Calendar = .current
    ) -> Bool {
        let start = schedule.startMinuteOfDay
        let end = schedule.endMinuteOfDay
        guard start != end else { return false }
        let now = calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date)
        let weekday = calendar.component(.weekday, from: date)
        if start < end {
            return now >= start && now < end && applies(schedule, onWeekday: weekday)
        }
        // Crosses midnight: after bedtime (started today) or before wake (started yesterday).
        if now >= start { return applies(schedule, onWeekday: weekday) }
        if now < end { return applies(schedule, onWeekday: weekday == 1 ? 7 : weekday - 1) }
        return false
    }

    private static func applies(_ schedule: FlintSchedule, onWeekday weekday: Int) -> Bool {
        schedule.daysOfWeek.isEmpty || schedule.daysOfWeek.contains(weekday)
    }

    // MARK: Allow list

    /// The apps that stay reachable during Wind Down / Full Assist: a saved preset's selection,
    /// or an empty selection (= allow nothing beyond the system's own "Always Allowed" apps,
    /// which supersede every shield). Note the monitor's allow-list path excepts *application*
    /// tokens only — whole-category picks in a preset don't carry over to a night shield.
    private func allowedSelection(_ groupID: String?) -> FamilyActivitySelection {
        guard let id = groupID,
              let group = FlintGroupStore()?.loadAppGroups().first(where: { $0.id == id }) else {
            return FamilyActivitySelection()
        }
        return group.selection
    }
}
