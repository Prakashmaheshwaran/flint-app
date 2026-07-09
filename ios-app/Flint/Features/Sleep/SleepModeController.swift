import Foundation
import FamilyControls
import FlintCore

/// Bridges `FlintSleepConfig` (the persisted Sleep Mode settings in FlintCore) to the real
/// blocking engine by materializing it into at most two well-known `FlintScheduleRule`s:
///
///  - `sleep.night`   — the bedtime → wake window (Sleep Assist: Wind Down / Full Assist).
///  - `sleep.morning` — the wake → wake + wind-up window (Morning Assist: Full Assist).
///
/// Reusing the Schedules machinery (`FlintSchedulesController` + the monitor extension's rule
/// handling) is deliberate: the extension already applies each rule's allow-list shield on
/// `intervalDidStart`, gated by weekday, and keeps enforcing while the app is closed — so Sleep
/// Mode gets the same device-real enforcement as Schedules without new shared types or extension
/// changes. Side effect (documented, accepted): the two windows also show up in the Schedules
/// list, named "Sleep Mode" and "Morning Assist" so their origin is obvious. `FlintSleepConfig`
/// is the source of truth — `apply(_:)` re-materializes the rules, so an edit or deletion made in
/// the Schedules list is overwritten the next time the Sleep screen applies.
///
/// Out of scope here, per the v1 spec: soundscapes/sleep-stories/meditations (content, not a
/// blocking control), Slow Uplift's gentle reminders (notification behavior, not a shield — the
/// level is stored but arms nothing), and an in-window Emergency Pass exit flow (the pass today
/// ends Hardcore *sessions* via `FlintSessionController`; wiring it to scheduled rules is a
/// follow-up — the UI says so honestly).
struct SleepModeController {

    /// Stable rule ids so apply/remove is idempotent. Store names become "flint.sleep.night" /
    /// "flint.sleep.morning" (via `FlintScheduleRule.storeName`), comfortably under the
    /// named-store limits, and monitor names "flint.rule.sleep.night" / "…morning" route through
    /// the extension's existing rule lookup untouched.
    static let nightRuleID = "sleep.night"
    static let morningRuleID = "sleep.morning"

    /// Defensive ceiling for the morning wind-up (the UI offers 15…180; the model default is 60).
    static let maximumMorningMinutes = 720

    private let schedules = FlintSchedulesController()

    init() {}

    // MARK: Materialization

    /// Re-materialize both rules from `config`. Idempotent — called after edits and on screen
    /// entry (which also self-heals a rule the user deleted from the Schedules list).
    func apply(_ config: FlintSleepConfig) {
        if let window = Self.nightWindow(for: config) {
            schedules.upsert(FlintScheduleRule(
                id: Self.nightRuleID,
                name: "Sleep Mode",
                schedule: window,
                // Wind Down = a strong nudge the user stays in control of; Full Assist registers
                // at Hardcore, whose only sanctioned early exit is the free weekly Emergency Pass.
                breakLevel: config.sleepAssist == .fullAssist ? .hardcore : .harder,
                selection: allowedSelection(config.allowGroupID),
                allowListMode: true,
                enabled: true
            ))
        } else {
            schedules.delete(Self.nightRuleID)
        }

        if let window = Self.morningWindow(for: config) {
            schedules.upsert(FlintScheduleRule(
                id: Self.morningRuleID,
                name: "Morning Assist",
                schedule: window,
                breakLevel: .hardcore,
                selection: allowedSelection(config.allowGroupID),
                allowListMode: true,
                enabled: true
            ))
        } else {
            schedules.delete(Self.morningRuleID)
        }
    }

    /// Remove both rules (clears their shields and stops their monitoring via the schedules API).
    func removeAll() {
        schedules.delete(Self.nightRuleID)
        schedules.delete(Self.morningRuleID)
    }

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
