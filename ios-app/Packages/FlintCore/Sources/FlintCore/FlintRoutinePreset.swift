import Foundation
#if canImport(FamilyControls)
import FamilyControls
#endif

/// Opal-style routine templates (free in Flint): one tap pre-fills the schedule editor with a
/// name, window, and break level. Targets are always the user's to pick — FamilyControls tokens
/// are opaque, so a preset *couldn't* pre-select "Instagram" even if it wanted to, and the
/// editor's Save gate keeps a target-less rule unsaveable. Pure data; `draftRule()` is the only
/// glue.
///
/// Mirrors Android's `RoutinePresets.kt` (same four routines, same windows, same break levels)
/// with two honest divergences:
///  - **Always-on:** Android models "always on while enabled" as a schedule-less rule; every
///    `FlintScheduleRule` requires a window (each maps 1:1 onto a repeating
///    `DeviceActivitySchedule`, which has no "always" form), so `draftRule()` adapts a `nil`
///    schedule to the widest daily window, 00:00–23:59. The monitor clears that shield at 23:59
///    and re-applies it at 00:00 — the last minute of each day is genuinely uncovered.
///  - **Copy:** Android's descriptions promise break-level behavior ("breaks take friction",
///    "only the weekly Emergency Pass"). On iOS today the strictness tiers gate *session*
///    stopping — every schedule shield is a plain hard block and the rule's enable toggle is
///    ungated by level — so these descriptions stick to what the drafted rule verifiably does.
///    The break level itself still carries through and pre-selects in the editor.
public struct FlintRoutinePreset: Identifiable, Equatable, Sendable {
    public var id: String { name }

    public let name: String
    /// The window to prefill; `nil` = "always on" (adapted by `draftRule()` — see above).
    public let schedule: FlintSchedule?
    public let breakLevel: BreakLevel
    public let allowListMode: Bool
    /// One-line copy shown under the template's name.
    public let description: String

    public init(
        name: String,
        schedule: FlintSchedule?,
        breakLevel: BreakLevel,
        allowListMode: Bool = false,
        description: String
    ) {
        self.name = name
        self.schedule = schedule
        self.breakLevel = breakLevel
        self.allowListMode = allowListMode
        self.description = description
    }

    /// The built-in template library — the same four routines as Android's `ROUTINE_PRESETS`.
    public static let library: [FlintRoutinePreset] = [
        FlintRoutinePreset(
            name: "Work hours",
            schedule: FlintSchedule(daysOfWeek: FlintSchedule.weekdays, startHour: 9, endHour: 17),
            breakLevel: .harder,
            description: "Deep work on weekdays, 9 to 5."
        ),
        FlintRoutinePreset(
            name: "Evenings offline",
            schedule: FlintSchedule(startHour: 20, endHour: 23),
            breakLevel: .easy,
            description: "Wind down every evening, 8 to 11."
        ),
        FlintRoutinePreset(
            name: "Social detox",
            schedule: nil, // always on — drafts as the widest daily window (00:00–23:59)
            breakLevel: .hardcore,
            description: "Every day, all day (00:00–23:59), set to Hardcore."
        ),
        FlintRoutinePreset(
            name: "Weekend mornings",
            schedule: FlintSchedule(daysOfWeek: FlintSchedule.weekend, startHour: 8, endHour: 12),
            breakLevel: .easy,
            description: "Slow Saturday and Sunday mornings, 8 to noon."
        ),
    ]
}

extension FlintSchedule {
    /// Mon–Fri in `Calendar` weekday numbering (1 = Sunday … 7 = Saturday) — the convention
    /// `daysOfWeek` uses throughout FlintCore.
    public static let weekdays: Set<Int> = [2, 3, 4, 5, 6]
    /// Saturday + Sunday, same numbering.
    public static let weekend: Set<Int> = [1, 7]
}

#if canImport(FamilyControls)
extension FlintRoutinePreset {
    /// A brand-new rule prefilled from this preset (fresh id every call — a template is never
    /// an edit). The selection stays empty for the user to pick.
    public func draftRule() -> FlintScheduleRule {
        FlintScheduleRule(
            name: name,
            schedule: schedule ?? FlintSchedule(startHour: 0, startMinute: 0, endHour: 23, endMinute: 59),
            breakLevel: breakLevel,
            allowListMode: allowListMode
        )
    }
}
#endif
